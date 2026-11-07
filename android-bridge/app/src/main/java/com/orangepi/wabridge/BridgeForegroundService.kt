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
        // nada aqui pode derrubar o serviço: sem ele o WebSocket cai junto
        runCatching { PhoneAccountHelper.ensureRegistered(this) }
            .onFailure { Log.w(TAG, "registro da conta falhou: ${it.message}") }
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
        // A tela de chamada do app não depende do Telecom: aparece mesmo em
        // aparelho sem telephony.
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        try {
            manager?.notify(CallNotifier.NOTIFICATION_CALL_ID, CallNotifier.callNotification(this, payload))
        } catch (e: Exception) {
            Log.w(TAG, "notificação de chamada falhou: ${e.message}")
        }

        try {
            startActivity(CallActivity.intent(this, payload))
            Log.i(TAG, "tela de chamada aberta")
        } catch (e: Exception) {
            Log.w(TAG, "startActivity da tela de chamada falhou: ${e.message}")
        }

        val telecom = getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
        if (telecom == null) {
            Log.w(TAG, "TelecomManager indisponível; chamada só pela tela do app")
            return
        }

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
