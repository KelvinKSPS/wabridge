package com.orangepi.wabridge

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.telecom.TelecomManager
import android.util.Log
import androidx.core.app.ServiceCompat
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

    /** Uma chamada por vez: as demais notas de voz esperam na fila. */
    private val callQueue = CallQueue()

    override fun onCreate() {
        super.onCreate()
        instance = this
        CallNotifier.ensureChannels(this)

        // No Android 12+ o startForeground é bloqueado se o serviço foi
        // iniciado em segundo plano (ForegroundServiceStartNotAllowedException).
        // Antes isso derrubava o serviço — e com ele o WebSocket.
        // Aqui não pedimos o tipo "microphone": ele só pode ser adicionado
        // com o app em primeiro plano (quando a chamada chega).
        try {
            ServiceCompat.startForeground(
                this,
                CallNotifier.NOTIFICATION_ID,
                CallNotifier.serviceNotification(this, getString(R.string.starting)),
                baseForegroundType(),
            )
        } catch (e: Exception) {
            Log.e(TAG, "startForeground bloqueado (${e.message}); serviço parado")
            stopSelf()
            return
        }

        // nada aqui pode derrubar o serviço: sem ele o WebSocket cai junto
        runCatching { PhoneAccountHelper.ensureRegistered(this) }
            .onFailure { Log.w(TAG, "registro da conta falhou: ${it.message}") }
        startSocket()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TEST_CALL -> {
                val payload = testPayload()
                if (payload != null) ringIncoming(payload)
            }
            ACTION_CALL_ENDED -> onCallEnded()
            ACTION_NEED_MIC -> promoteToMicrophone()
        }
        startSocket()
        return START_STICKY
    }

    /**
     * Tipo base do foreground service (sem microfone).
     *
     * `specialUse` só existe no Android 14+; antes disso o tipo não é
     * declarado e o serviço sobe com o tipo do manifesto.
     */
    private fun baseForegroundType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }

    /**
     * Promove o serviço para foreground com o tipo `microphone`.
     *
     * Sem isso o Android silencia o microfone sempre que o app não está na
     * tela (a resposta saía vazia). O tipo só pode ser adicionado aqui —
     * no `onCreate` o serviço pode ser criado pelo sistema em background, e
     * o Android 14+ não aceita FGS de microfone nesse caso.
     */
    private fun promoteToMicrophone() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            0
        }
        runCatching {
            ServiceCompat.startForeground(
                this,
                CallNotifier.NOTIFICATION_ID,
                CallNotifier.serviceNotification(this, getString(R.string.starting)),
                type,
            )
            Log.i(TAG, "serviço promovido para foreground com microfone")
        }.onFailure {
            Log.w(TAG, "não foi possível adicionar o tipo microphone: ${it.message}")
        }
    }

    override fun onDestroy() {
        if (instance === this) instance = null
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

    /**
     * Toca uma nota de voz. Se já houver uma chamada em andamento, a nota vai
     * para a fila e toca depois — nada de chamada por cima da outra.
     */
    private fun ringIncoming(payload: CallPayload) {
        if (!callQueue.offer(payload)) {
            Log.i(TAG, "nota de voz enfileirada (chamada em andamento)")
            broadcastStatus("nova nota de voz na fila")
            return
        }
        startRinging(payload)
    }

    private fun startRinging(payload: CallPayload) {
        // A chamada chega com o app em primeiro plano (a tela de chamada vai
        // abrir): é o momento seguro para pedir o tipo "microphone" do
        // foreground service. Sem isso o Android silencia o microfone
        // quando o app sair da tela e a resposta sai vazia.
        promoteToMicrophone()

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

    /** A chamada atual terminou: toca a próxima da fila, se houver. */
    private fun onCallEnded() {
        val next = callQueue.release()
        if (next == null) {
            Log.i(TAG, "chamada encerrada; fila vazia")
            return
        }
        Log.i(TAG, "chamada encerrada; tocando a próxima da fila")
        startRinging(next)
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
        const val ACTION_CALL_ENDED = "com.orangepi.wabridge.CALL_ENDED"
        const val ACTION_NEED_MIC = "com.orangepi.wabridge.NEED_MIC"
        const val EXTRA_TEXT = "text"

        /** Serviço vivo (para avisar que uma chamada terminou). */
        @Volatile
        var instance: BridgeForegroundService? = null
            private set

        /** Avisa o serviço de que a chamada terminou. */
        fun notifyCallEnded(context: Context) {
            val svc = instance
            if (svc != null) {
                svc.onCallEnded()
                return
            }
            // serviço não está no ar: tenta acordar por intent
            runCatching {
                context.startService(
                    Intent(context, BridgeForegroundService::class.java)
                        .setAction(ACTION_CALL_ENDED),
                )
            }
        }

        /**
         * Garante que o serviço pode usar o microfone.
         *
         * Sem o tipo `microphone` no foreground service, o Android silencia
         * a gravação quando o app não está na tela — a resposta saía vazia.
         */
        fun requestMicrophone(context: Context) {
            val svc = instance
            if (svc != null) {
                svc.promoteToMicrophone()
                return
            }
            runCatching {
                context.startService(
                    Intent(context, BridgeForegroundService::class.java)
                        .setAction(ACTION_NEED_MIC),
                )
            }
        }
    }
}
