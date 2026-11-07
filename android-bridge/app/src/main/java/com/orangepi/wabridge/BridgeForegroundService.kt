package com.orangepi.wabridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.os.IBinder
import android.telecom.TelecomManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Serviço em primeiro plano: mantém o WebSocket com o servidor vivo e
 * transforma cada áudio recebido em uma chamada entrante no Telecom.
 */
class BridgeForegroundService : Service() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Default)
    private var ws: WsManager? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification("iniciando..."))
        PhoneAccountHelper.ensureRegistered(this)
        startSocket()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startSocket()
        return START_STICKY
    }

    override fun onDestroy() {
        ws?.stop()
        job.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startSocket() {
        if (ws != null) return
        ws = WsManager(
            urlProvider = { Prefs.url(this) },
            onEvent = { json -> onServerEvent(json) },
            onState = { state -> broadcastStatus(state) },
        ).also { it.start() }
    }

    private fun onServerEvent(json: org.json.JSONObject) {
        when (json.optString("type")) {
            "NEW_VOICE_NOTE" -> {
                val payload = CallPayload.fromJson(json.toString()) ?: return
                Log.i(TAG, "áudio recebido de ${payload.senderName}")
                broadcastStatus("chamada de ${payload.senderName}")
                ringIncoming(payload)
            }
            "HELLO" -> broadcastStatus("ligado ao servidor")
        }
    }

    private fun ringIncoming(payload: CallPayload) {
        val telecom = getSystemService(Context.TELECOM_SERVICE) as? TelecomManager ?: return
        val accountHandle = PhoneAccountHelper.handle(this)

        val extras = Bundle().apply {
            putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, accountHandle)
            putString(BridgeExtras.PAYLOAD, payload.toJson())
            // indica que a chamada tem áudio
            putBoolean(BridgeExtras.HAS_AUDIO, true)
        }

        try {
            telecom.addNewIncomingCall(accountHandle, extras)
        } catch (e: Exception) {
            Log.e(TAG, "addNewIncomingCall falhou: ${e.message}")
            broadcastStatus("erro ao tocar: ${e.message}")
        }
    }

    private fun broadcastStatus(text: String) {
        Log.i(TAG, text)
        sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName).putExtra(EXTRA_TEXT, text))
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    companion object {
        private const val TAG = "BridgeFg"
        private const val CHANNEL_ID = "wabridge"
        private const val NOTIFICATION_ID = 42
        const val ACTION_STATUS = "com.orangepi.wabridge.STATUS"
        const val EXTRA_TEXT = "text"
    }
}
