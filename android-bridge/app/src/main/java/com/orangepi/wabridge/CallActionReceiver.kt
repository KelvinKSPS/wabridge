package com.orangepi.wabridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Botões "Atender" e "Recusar" da notificação de chamada. */
class CallActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_ANSWER -> WatchCallConnection.current?.answerNow()
            ACTION_REJECT -> WatchCallConnection.current?.rejectNow()
        }
    }

    companion object {
        const val ACTION_ANSWER = "com.orangepi.wabridge.ANSWER"
        const val ACTION_REJECT = "com.orangepi.wabridge.REJECT"
    }
}
