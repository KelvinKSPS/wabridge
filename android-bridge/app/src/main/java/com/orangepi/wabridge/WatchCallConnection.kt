package com.orangepi.wabridge

import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import android.telecom.Connection
import android.telecom.DisconnectCause
import android.telecom.TelecomManager
import android.util.Log

/**
 * Adaptador do Telecom: mostra a chamada como ligação do relógio (com nome e
 * número do contato) e delega o áudio para a [VoiceSession].
 */
class WatchCallConnection(
    private val ctx: Context,
    val payload: CallPayload,
) : Connection() {

    private val state = CallStateMachine()
    private var session: VoiceSession? = null
    private var done = false

    init {
        state.onIncoming()
        current = this
        setCallerDisplayName(
            payload.senderName.ifBlank { payload.senderNumber },
            TelecomManager.PRESENTATION_ALLOWED,
        )
        val subtitle = if (payload.durationSec > 0) {
            CallPayload.formatDuration(payload.durationSec)
        } else {
            payload.senderNumber
        }
        setAddress(
            Uri.fromParts("tel", subtitle, null),
            TelecomManager.PRESENTATION_ALLOWED,
        )
        setConnectionCapabilities(Connection.CAPABILITY_MUTE)
    }

    /* ---------------------------------------------------------------- *
     * Estados do Telecom
     * ---------------------------------------------------------------- */

    override fun onAnswer() {
        Log.i(TAG, "atendida: ${payload.senderName}")
        if (!state.onAnswer()) {
            Log.w(TAG, "atendimento fora de ordem (fase ${state.phase})")
            return
        }
        setActive()

        // se a chamada foi atendida pelo discador do sistema, fecha a tela do app
        android.os.Handler(android.os.Looper.getMainLooper()).post { CallActivity.active?.finish() }

        val voice = VoiceSession(ctx, payload, onEnded = { onSessionEnded() })
        session = voice
        voice.answer()
    }

    override fun onReject() {
        Log.i(TAG, "rejeitada")
        hangup(DisconnectCause.REJECTED)
    }

    override fun onDisconnect() {
        Log.i(TAG, "desligada")
        hangup(DisconnectCause.LOCAL)
    }

    override fun onAbort() {
        hangup(DisconnectCause.CANCELED)
    }

    /** Atender pela tela do próprio app (relógio sem discador do sistema). */
    fun answerNow() {
        onAnswer()
    }

    /**
     * Recusar/encerrar pela tela do próprio app.
     * @return true se a chamada foi encerrada; false se o desligar foi
     * recusado (gravação ainda muito curta).
     */
    fun rejectNow(): Boolean {
        hangup(DisconnectCause.REJECTED)
        return done
    }

    private fun onSessionEnded() {
        // a sessão acabou (timeout, hangup do usuário...): o Telecom precisa saber
        if (done) return
        done = true
        runCatching {
            setDisconnected(DisconnectCause(DisconnectCause.LOCAL))
            destroy()
        }
        if (current === this) current = null
        // a próxima nota de voz da fila pode tocar agora
        BridgeForegroundService.notifyCallEnded(ctx)
    }

    private fun hangup(cause: Int) {
        val session = this.session
        if (session != null) {
            // desligar logo depois do bipe mandaria um áudio mudo: a sessão
            // recusa e a chamada continua viva
            session.hangup()
            if (!session.ended) return
            this.session = null
        }

        if (!state.onHangup()) return
        if (current === this) current = null

        // esconde a notificação de chamada
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
            ?.cancel(CallNotifier.NOTIFICATION_CALL_ID)

        if (done) return
        done = true
        setDisconnected(DisconnectCause(cause))
        destroy()
        BridgeForegroundService.notifyCallEnded(ctx)
    }

    companion object {
        private const val TAG = "WatchCall"

        /** Chamada em andamento, para a tela do app e os botões da notificação. */
        @Volatile
        var current: WatchCallConnection? = null
            private set
    }
}
