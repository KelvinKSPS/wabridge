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
    private val payload: CallPayload,
) : Connection() {

    private val state = CallStateMachine()
    private var session: VoiceSession? = null

    init {
        state.onIncoming()
        current = this
        setCallerDisplayName(
            payload.senderName.ifBlank { payload.senderNumber },
            TelecomManager.PRESENTATION_ALLOWED,
        )
        setAddress(
            Uri.fromParts("tel", payload.senderNumber, null),
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

        val voice = VoiceSession(ctx, payload) { onSessionEnded() }
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

    /** Recusar pela tela do próprio app. */
    fun rejectNow() {
        hangup(DisconnectCause.REJECTED)
    }

    private fun onSessionEnded() {
        // a sessão acabou (timeout): o Telecom precisa saber
        runCatching {
            setDisconnected(DisconnectCause(DisconnectCause.LOCAL))
            destroy()
        }
        if (current === this) current = null
    }

    private fun hangup(cause: Int) {
        if (!state.onHangup()) return
        if (current === this) current = null

        session?.hangup()
        session = null

        // esconde a notificação de chamada
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
            ?.cancel(CallNotifier.NOTIFICATION_CALL_ID)

        setDisconnected(DisconnectCause(cause))
        destroy()
    }

    companion object {
        private const val TAG = "WatchCall"

        /** Chamada em andamento, para a tela do app e os botões da notificação. */
        @Volatile
        var current: WatchCallConnection? = null
            private set
    }
}
