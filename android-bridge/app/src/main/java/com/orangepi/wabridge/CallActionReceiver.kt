package com.orangepi.wabridge

import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

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
            ACTION_DISMISS -> dismissQuiet(context, intent)
            ACTION_PLAY_QUIET, ACTION_PLAY_REPLY -> playQuiet(context, intent)
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

    /** User dismissed a quiet note notification without playing it. */
    private fun dismissQuiet(context: Context, intent: Intent) {
        val payload = CallPayload.fromJson(intent.getStringExtra(BridgeExtras.PAYLOAD)) ?: return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
        manager?.cancel(CallNotifier.quietNotificationId(payload))
        Prefs.addCallHistory(context, payload, CallHistoryOutcome.REJECTED)
        BridgeForegroundService.endQuietCall(context, payload.audioId)
    }

    /** Play a quiet note: launch the call screen which will auto-answer. */
    private fun playQuiet(context: Context, intent: Intent) {
        val payload = CallPayload.fromJson(intent.getStringExtra(BridgeExtras.PAYLOAD)) ?: return
        // RemoteInput (reply-style action used for watch bridges that only show
        // "quick reply" buttons): the text is irrelevant, the tap means "play".
        RemoteInput.getResultsFromIntent(intent)?.let { results ->
            Log.i("CallActionReceiver", "play via remote reply: ${results.getCharSequence(CallNotifier.KEY_PLAY_REPLY)}")
        }
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
        manager?.cancel(CallNotifier.quietNotificationId(payload))
        try {
            context.startActivity(CallActivity.intent(context, payload, autoAnswer = true))
        } catch (e: Exception) {
            Log.w("CallActionReceiver", "falha ao abrir chamada: ${e.message}")
        }
    }

    companion object {
        const val ACTION_ANSWER = "com.orangepi.wabridge.ANSWER"
        const val ACTION_REJECT = "com.orangepi.wabridge.REJECT"
        const val ACTION_SPEED = "com.orangepi.wabridge.SPEED"
        const val ACTION_QUICK_REPLY = "com.orangepi.wabridge.QUICK_REPLY"
        const val ACTION_DISMISS = "com.orangepi.wabridge.DISMISS"
        const val ACTION_PLAY_QUIET = "com.orangepi.wabridge.PLAY_QUIET"
        /** RemoteInput/reply-style "Play" action for watch bridges (e.g. Huawei). */
        const val ACTION_PLAY_REPLY = "com.orangepi.wabridge.PLAY_REPLY"
    }
}
