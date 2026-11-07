package com.orangepi.wabridge

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.telecom.TelecomManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
        CallNotifier.ensureChannels(this)
        startForeground(CallNotifier.NOTIFICATION_ID, CallNotifier.serviceNotification(this, getString(R.string.starting)))
        PhoneAccountHelper.ensureRegistered(this)
        startSocket()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_TEST_CALL) {
            val payload = testPayload()
            if (payload != null) ringIncoming(payload)
        }
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

    /** Chamada de teste: usa o wav sintético do servidor. */
    private fun testPayload(): CallPayload? {
        val base = Prefs.url(this)
        return CallPayload(
            audioId = "teste_${System.currentTimeMillis()}",
            senderName = getString(R.string.test_caller),
            senderNumber = "0000000000000",
            audioUrl = WsUrls.httpUrl(base, "/media/test.wav"),
        )
    }

    private fun ringIncoming(payload: CallPayload) {
        val telecom = getSystemService(Context.TELECOM_SERVICE) as? TelecomManager ?: return
        val accountHandle = PhoneAccountHelper.handle(this)

        // notificação de chamada: caminho garantido para abrir a tela no relógio
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.notify(CallNotifier.NOTIFICATION_CALL_ID, CallNotifier.callNotification(this, payload))

        // e tenta abrir a tela direto (funciona quando o app já está em foco)
        runCatching {
            startActivity(CallActivity.intent(this, payload))
        }

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
        // atualiza a notificação de serviço
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        manager.notify(
            CallNotifier.NOTIFICATION_ID,
            CallNotifier.serviceNotification(this, text),
        )
    }

    companion object {
        private const val TAG = "BridgeFg"
        const val ACTION_STATUS = "com.orangepi.wabridge.STATUS"
        const val ACTION_TEST_CALL = "com.orangepi.wabridge.TEST_CALL"
        const val EXTRA_TEXT = "text"
    }
}
