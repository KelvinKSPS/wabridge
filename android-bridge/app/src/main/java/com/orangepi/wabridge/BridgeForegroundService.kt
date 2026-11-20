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
import androidx.core.content.ContextCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Foreground service: keeps the WebSocket connection to the server alive and
 * turns each received audio message into an incoming Telecom call.
 */
class BridgeForegroundService : Service() {

    override fun attachBaseContext(newBase: Context) {
        // Same locale wrapper as the Activity: without it, the service uses the
        // system locale and notifications appear in a different language from the
        // screen (Bluetooth status flickered between languages).
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Default)
    private var ws: WsManager? = null

    /** Uma chamada por vez: as demais notas de voz esperam na fila. */
    private val callQueue = CallQueue()

    /** Most recent text shown in the service notification. */
    private var statusText: String = ""

    override fun onCreate() {
        super.onCreate()
        instance = this
        CallNotifier.ensureChannels(this)

        // On Android 12+, startForeground is blocked if the service was
        // started in the background (ForegroundServiceStartNotAllowedException).
        // Previously this stopped the service — and with it, the WebSocket.
        // Do not request the "microphone" type here: it can only be added
        // while the app is in the foreground (when the call arrives).
        try {
            ServiceCompat.startForeground(
                this,
                CallNotifier.NOTIFICATION_ID,
                CallNotifier.serviceNotification(this, getString(R.string.service_notification_active)),
                baseForegroundType(),
            )
        } catch (e: Exception) {
            Log.e(TAG, "startForeground bloqueado (${e.message}); serviço parado")
            stopSelf()
            return
        }

        // Nothing here can be allowed to stop the service: the WebSocket would stop too.
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
            ACTION_RING_PAYLOAD -> {
                val payload = CallPayload.fromJson(intent.getStringExtra(BridgeExtras.PAYLOAD))
                if (payload != null) ringIncoming(payload, forceRing = true)
            }
        }
        startSocket()
        return START_STICKY
    }

    /**
     * Base foreground-service type without microphone access.
     *
     * `specialUse` exists only on Android 14+; before that, no type is
     * declared here and the service starts with the manifest type.
     */
    private fun baseForegroundType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }

    /**
     * Promote the service to foreground with the `microphone` type.
     *
     * Without this, Android silences the microphone whenever the app is not
     * on screen (the reply was empty). The type can only be added here —
     * in `onCreate`, the system may create the service in the background, and
     * Android 14+ does not allow a microphone FGS in that case.
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
                CallNotifier.serviceNotification(this, getString(R.string.service_notification_active)),
                type,
            )
            Log.i(TAG, "serviço promovido para foreground com microfone")
        }.onFailure {
            Log.w(TAG, "não foi possível adicionar o tipo microphone: ${it.message}")
        }
    }

    /** Returns the foreground service to its base type without microphone access. */
    private fun demoteFromMicrophone() {
        runCatching {
            ServiceCompat.startForeground(
                this,
                CallNotifier.NOTIFICATION_ID,
                CallNotifier.serviceNotification(this, getString(R.string.service_notification_active)),
                baseForegroundType(),
            )
            Log.i(TAG, "serviço voltou ao tipo base (sem microfone)")
        }.onFailure {
            Log.w(TAG, "não foi possível remover o tipo microphone: ${it.message}")
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
        if (ws != null) {
            broadcastStatus(statusText)
            return
        }
        // No modo embutido o daemon mora no APK: sobe ele antes de conectar.
        if (Prefs.mode(this) == Prefs.Engine.EMBUTIDO) {
            if (!DaemonManager.start(this)) {
                broadcastStatus("daemon não disponível neste APK")
                return
            }
        }
        // The daemon/server starts with no allowlist of its own; push the one
        // saved in the app so it does not silently accept everyone until the
        // user happens to re-save the list from the screen.
        val numbers = Prefs.allowedNumbers(this)
        Thread { PairingClient.syncAllowedNumbers(this, numbers) }.start()
        ws = WsManager(
            urlProvider = { Prefs.engineUrl(this) },
            onEvent = { json -> onServerEvent(json) },
            onState = { state -> broadcastStatus(state) },
        ).also { it.start() }
    }

    private fun onServerEvent(json: org.json.JSONObject) {
        when (json.optString("type")) {
            "NEW_VOICE_NOTE" -> {
                val payload = CallPayload.fromJson(json.toString()) ?: return
                Prefs.addCallHistory(this, payload, CallHistoryOutcome.RECEIVED)
                Log.i(TAG, "áudio recebido de ${payload.senderName}")
                broadcastStatus("chamada de ${payload.senderName}")
                ringIncoming(payload)
            }
            "HELLO" -> broadcastStatus("ligado ao servidor")
        }
    }

    /** Test call: uses the server's synthetic WAV. */
    private fun testPayload(): CallPayload? {
        val base = Prefs.engineUrl(this)
        return CallPayload(
            audioId = "teste_${System.currentTimeMillis()}",
            senderName = getString(R.string.test_caller),
            senderNumber = "0000000000000",
            audioUrl = WsUrls.httpUrl(base, "/media/test.wav"),
        )
    }

    /**
     * Play a voice note. If a call is already in progress, queue the note
     * and play it afterward — never overlap calls.
     *
     * [forceRing] skips quiet-notification mode and always rings as a call
     * (used by "transfer to watch" from history, which is an explicit user action).
     */
    private fun ringIncoming(payload: CallPayload, forceRing: Boolean = false) {
        // If none of the selected devices is connected, the note waits in the daemon queue
        // (/pending) — the app does not ring or wake the screen.
        if (!watchConnected()) {
            Log.i(TAG, "nenhum aparelho escolhido conectado; nota de voz na fila")
            broadcastStatus("nenhum aparelho conectado — nota na fila")
            return
        }
        if (!forceRing && Prefs.quietNotifications(this)) {
            // Each note is an independent notification (no global "in call" queue)
            // so several arrivals can be shown at once and played one by one.
            showQuietNote(payload)
            return
        }
        if (!callQueue.offer(payload)) {
            Log.i(TAG, "nota de voz enfileirada (chamada em andamento)")
            broadcastStatus("nova nota de voz na fila")
            return
        }
        startRinging(payload)
    }

    /** Posts a single quiet notification for one incoming note. */
    private fun showQuietNote(payload: CallPayload) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        try {
            manager.notify(CallNotifier.quietNotificationId(payload), CallNotifier.quietNotification(this, payload))
            QuietMedia.offer(this, payload)
            Log.i(TAG, "notificação quieta: ${payload.senderName}")
        } catch (e: Exception) {
            Log.w(TAG, "falha ao postar notificação quieta: ${e.message}")
        }
    }

    /** true se o gate de bluetooth libera o toque. */
    private fun watchConnected(): Boolean {
        if (!Prefs.btOnlyConnected(this)) return true
        val addrs = Prefs.btDevices(this)
        if (addrs.isEmpty()) return true
        return addrs.any { BluetoothHelper.isConnected(this, it) }
    }

    private fun startRinging(payload: CallPayload) {
        CallPayload.setCurrent(payload)

        // The app's call screen does not depend on Telecom: it appears even on
        // devices without telephony.
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
            // Indicates that the call has audio.
            putBoolean(BridgeExtras.HAS_AUDIO, true)
        }

        try {
            telecom.addNewIncomingCall(accountHandle, extras)
        } catch (e: Exception) {
            Log.e(TAG, "addNewIncomingCall falhou: ${e.message}")
            broadcastStatus("erro ao tocar: ${e.message}")
        }
    }

    /** The current call ended; play the next queued call, if any. */
    private fun onCallEnded() {
        val next = callQueue.release()
        if (next == null) {
            Log.i(TAG, "chamada encerrada; fila vazia")
            // No more calls: release the microphone type so its icon disappears.
            demoteFromMicrophone()
            return
        }
        Log.i(TAG, "chamada encerrada; tocando a próxima da fila")
        startRinging(next)
    }

    /** Dismissed or finished a quiet note without going through the call queue. */
    fun endQuietCall(audioId: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        // Best-effort: cancel by the same hash scheme used to post it.
        val pseudo = CallPayload(audioId = audioId, senderName = "", senderNumber = "", audioUrl = "")
        manager?.cancel(CallNotifier.quietNotificationId(pseudo))
        Log.i(TAG, "nota quieta encerrada: $audioId")
    }

    private fun broadcastStatus(text: String) {
        Log.i(TAG, text)
        statusText = text
        sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName).putExtra(EXTRA_TEXT, text))
        // The service notification is required (foreground), but should not
        // flicker through "starting/connecting...": always show the stable
        // active-service label. Transient states appear only in the log
        // and broadcast (the screen ignores them).
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        manager.notify(
            CallNotifier.NOTIFICATION_ID,
            CallNotifier.serviceNotification(this, getString(R.string.service_notification_active)),
        )
    }

    /** Update the service notification (used by actions). */
    fun refreshStatus() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        manager.notify(
            CallNotifier.NOTIFICATION_ID,
            CallNotifier.serviceNotification(this, statusText),
        )
    }

    companion object {
        private const val TAG = "BridgeFg"
        const val ACTION_STATUS = "com.orangepi.wabridge.STATUS"
        const val ACTION_TEST_CALL = "com.orangepi.wabridge.TEST_CALL"
        const val ACTION_CALL_ENDED = "com.orangepi.wabridge.CALL_ENDED"
        const val ACTION_NEED_MIC = "com.orangepi.wabridge.NEED_MIC"
        const val ACTION_RING_PAYLOAD = "com.orangepi.wabridge.RING_PAYLOAD"
        const val EXTRA_TEXT = "text"

        /**
         * Rings a specific payload on the watch (used by the "transfer to watch"
         * button in the audio-history player). Goes through the same queue as a
         * normal incoming note, so it never overlaps an active call.
         */
        fun ringPayload(context: Context, payload: CallPayload) {
            context.startService(
                Intent(context, BridgeForegroundService::class.java)
                    .setAction(ACTION_RING_PAYLOAD)
                    .putExtra(BridgeExtras.PAYLOAD, payload.toJson()),
            )
        }

        /** Ends a quiet note (dismissed from its notification). */
        fun endQuietCall(context: Context, audioId: String) {
            val svc = instance
            if (svc != null) {
                svc.endQuietCall(audioId)
            } else {
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
                val pseudo = CallPayload(audioId = audioId, senderName = "", senderNumber = "", audioUrl = "")
                manager?.cancel(CallNotifier.quietNotificationId(pseudo))
            }
        }

        /** Live service instance (used to notify it that a call ended). */
        @Volatile
        var instance: BridgeForegroundService? = null
            private set

        /** True if the service is running. */
        fun isRunning(context: Context): Boolean = instance != null

        /** Start the foreground service (used by boot and the screen button). */
        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, BridgeForegroundService::class.java),
                )
            }.onFailure {
                Log.w(TAG, "não foi possível subir o serviço: ${it.message}")
            }
        }

        /** Notify the service that the call ended. */
        fun notifyCallEnded(context: Context) {
            val svc = instance
            if (svc != null) {
                svc.onCallEnded()
                return
            }
            // Service is not running: try to wake it with an intent.
            runCatching {
                context.startService(
                    Intent(context, BridgeForegroundService::class.java)
                        .setAction(ACTION_CALL_ENDED),
                )
            }
        }

        /**
         * Ensure the service can use the microphone.
         *
         * Without the `microphone` type on the foreground service, Android silences
         * recording when the app is not on screen — the reply was empty.
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

        fun releaseMicrophone(@Suppress("UNUSED_PARAMETER") context: Context) {
            instance?.demoteFromMicrophone()
        }
    }
}
