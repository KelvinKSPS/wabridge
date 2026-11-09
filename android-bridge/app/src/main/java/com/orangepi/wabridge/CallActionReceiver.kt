package com.orangepi.wabridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Botões das notificações: "Atender", "Recusar", "Velocidade" e a resposta
 * rápida "Não posso atender".
 */
class CallActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_ANSWER -> WatchCallConnection.current?.answerNow()
            ACTION_REJECT -> WatchCallConnection.current?.rejectNow()
            ACTION_SPEED -> cycleSpeed(context)
            ACTION_QUICK_REPLY -> quickReply(context)
        }
    }

    /** Troca a velocidade de reprodução: 1x → 1.25x → 1.5x → 2x → 1x. */
    private fun cycleSpeed(context: Context) {
        val session = VoiceSession.current ?: return
        val next = session.setSpeed(PlaybackSpeed.next(session.speed))
        // Atualiza a notificação de serviço para mostrar a nova velocidade
        val svc = BridgeForegroundService.instance
        if (svc != null) {
            svc.refreshStatus()
        } else {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE)
                as? android.app.NotificationManager ?: return
            manager.notify(
                CallNotifier.NOTIFICATION_ID,
                CallNotifier.serviceNotification(context, "velocidade ${PlaybackSpeed.label(next)}"),
            )
        }
    }

    /**
     * Resposta rápida: manda "Não posso atender o telefone no momento" para o
     * contato e desliga a chamada — sem precisar falar.
     */
    private fun quickReply(context: Context) {
        val payload = WatchCallConnection.current?.payload ?: CallPayload.current ?: return
        val text = context.getString(R.string.quick_reply_busy)
        Uploader.sendText(Prefs.engineUrl(context), payload.audioId, payload.senderNumber, text)
        WatchCallConnection.current?.rejectNow()
    }

    companion object {
        const val ACTION_ANSWER = "com.orangepi.wabridge.ANSWER"
        const val ACTION_REJECT = "com.orangepi.wabridge.REJECT"
        const val ACTION_SPEED = "com.orangepi.wabridge.SPEED"
        const val ACTION_QUICK_REPLY = "com.orangepi.wabridge.QUICK_REPLY"
    }
}
