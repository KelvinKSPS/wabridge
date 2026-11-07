package com.orangepi.wabridge

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telecom.Connection
import android.telecom.DisconnectCause
import android.telecom.TelecomManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * Simula uma chamada de voz: toca o áudio recebido pelo canal SCO do relógio
 * e grava a resposta do microfone, enviando-a ao servidor ao desligar.
 */
class WatchCallConnection(
    private val ctx: Context,
    private val payload: CallPayload,
) : Connection() {

    private val audio: AudioManager = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var player: MediaPlayer? = null
    private var recorder: MediaRecorder? = null
    private var recorderFile: File? = null
    private var scoReceiver: BroadcastReceiver? = null
    private val scoConnected = CompletableDeferred<Boolean>()
    private var timeoutJob: Job? = null
    private val state = CallStateMachine()

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
     * Estado: chamada atendida -> reproduz o áudio recebido
     * ---------------------------------------------------------------- */

    override fun onAnswer() {
        Log.i(TAG, "atendida: ${payload.senderName}")
        if (!state.onAnswer()) {
            Log.w(TAG, "atendimento fora de ordem (fase ${state.phase})")
            return
        }
        setActive()

        // se a chamada foi atendida pelo discador do sistema, fecha a tela do app
        Handler(Looper.getMainLooper()).post { CallActivity.active?.finish() }

        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        audio.isSpeakerphoneOn = false
        routeToScoDevice()

        registerScoReceiver()
        audio.startBluetoothSco()
        audio.isBluetoothScoOn = true

        scope.launch {
            val ok = withTimeoutOrNull(SCO_TIMEOUT_MS) { scoConnected.await() } ?: false
            if (!ok) Log.w(TAG, "SCO não conectado a tempo; seguindo assim mesmo")
            // buffer de negociação: o canal SCO precisa estabilizar antes do áudio
            delay(SCO_SETTLE_MS)
            startPlayback()
        }
    }

    private fun routeToScoDevice() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        runCatching {
            val device = audio.availableCommunicationDevices
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
            if (device != null) {
                audio.setCommunicationDevice(device)
                Log.i(TAG, "dispositivo de comunicação: ${device.productName}")
            }
        }
    }

    private fun registerScoReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED) return
                val state = intent.getIntExtra(
                    AudioManager.EXTRA_SCO_AUDIO_STATE,
                    AudioManager.SCO_AUDIO_STATE_ERROR,
                )
                when (state) {
                    AudioManager.SCO_AUDIO_STATE_CONNECTED -> {
                        Log.i(TAG, "SCO conectado")
                        scoConnected.complete(true)
                    }
                    AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> Log.i(TAG, "SCO desconectado")
                }
            }
        }
        ContextCompat.registerReceiver(
            ctx,
            receiver,
            IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        scoReceiver = receiver
    }

    private fun startPlayback() {
        state.onPlaybackStarted()
        try {
            val mp = MediaPlayer()
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            mp.setDataSource(payload.audioUrl)
            mp.setOnPreparedListener { it.start() }
            mp.setOnCompletionListener { onPlaybackFinished() }
            mp.setOnErrorListener { _, _, _ ->
                Log.e(TAG, "erro na reprodução; indo para gravação")
                onPlaybackFinished()
                true
            }
            player = mp
            mp.prepareAsync()
        } catch (e: Exception) {
            Log.e(TAG, "falha ao tocar áudio: ${e.message}")
            onPlaybackFinished()
        }
    }

    /* ---------------------------------------------------------------- *
     * Estado: reprodução -> gravação
     * ---------------------------------------------------------------- */

    private fun onPlaybackFinished() {
        if (!state.onPlaybackFinished()) {
            Log.w(TAG, "playback finished fora de ordem (fase ${state.phase})")
            return
        }
        scope.launch {
            delay(PLAYBACK_TO_RECORD_DELAY_MS)
            // tom curto avisando que a resposta está sendo gravada
            runCatching {
                val tone = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 80)
                tone.startTone(ToneGenerator.TONE_PROP_BEEP, 200)
                delay(250)
                tone.release()
            }
            startRecording()
        }
    }

    private fun startRecording() {
        val file = File(ctx.cacheDir, "reply_${payload.audioId}_${System.currentTimeMillis()}.m4a")
        val sources = listOf(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.VOICE_CALL,
            MediaRecorder.AudioSource.MIC,
        )

        for (source in sources) {
            try {
                val rec = MediaRecorder()
                rec.setAudioSource(source)
                rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                rec.setAudioSamplingRate(16000)
                rec.setAudioEncodingBitRate(32000)
                rec.setOutputFile(file.absolutePath)
                rec.prepare()
                rec.start()
                recorder = rec
                recorderFile = file
                Log.i(TAG, "gravando em ${file.absolutePath} (source=$source)")
                break
            } catch (e: Exception) {
                Log.w(TAG, "falha com source=$source: ${e.message}")
                runCatching { file.delete() }
            }
        }

        if (recorder == null) {
            Log.e(TAG, "não foi possível iniciar a gravação")
            return
        }

        // Timeout de segurança: 90s de gravação.
        timeoutJob = scope.launch {
            delay(RECORD_TIMEOUT_MS)
            Log.w(TAG, "timeout de gravação; encerrando")
            hangup(DisconnectCause.LOCAL)
        }
    }

    /* ---------------------------------------------------------------- *
     * Estado: desligar -> envia a resposta
     * ---------------------------------------------------------------- */

    override fun onReject() {
        Log.i(TAG, "rejeitada")
        hangup(DisconnectCause.REJECTED)
    }

    /** Atender pela tela do próprio app (relógio sem discador do sistema). */
    fun answerNow() {
        onAnswer()
    }

    /** Recusar pela tela do próprio app. */
    fun rejectNow() {
        hangup(DisconnectCause.REJECTED)
    }

    override fun onDisconnect() {
        Log.i(TAG, "desligada")
        hangup(DisconnectCause.LOCAL)
    }

    override fun onAbort() {
        hangup(DisconnectCause.CANCELED)
    }

    private fun hangup(cause: Int) {
        if (!state.onHangup()) return
        if (current === this) current = null
        timeoutJob?.cancel()

        runCatching { player?.stop() }
        player?.release()
        player = null

        val file = recorderFile
        runCatching { recorder?.stop() }
        recorder?.release()
        recorder = null

        runCatching {
            audio.stopBluetoothSco()
            audio.isBluetoothScoOn = false
        }
        scoReceiver?.let { runCatching { ctx.unregisterReceiver(it) } }
        scoReceiver = null
        audio.mode = AudioManager.MODE_NORMAL

        // esconde a notificação de chamada
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
            ?.cancel(CallNotifier.NOTIFICATION_CALL_ID)

        setDisconnected(DisconnectCause(cause))
        destroy()

        if (file != null && file.length() > 0) {
            upload(file)
        } else {
            Log.i(TAG, "sem gravação para enviar")
        }
    }

    private fun upload(file: File) {
        val baseUrl = Prefs.url(ctx)
        // escopo próprio: o da conexão pode estar sendo cancelado
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val ok = Uploader.sendReply(baseUrl, payload.audioId, payload.senderNumber, file)
            Log.i(TAG, if (ok) "resposta enviada" else "falha ao enviar resposta")
            file.delete()
        }
    }

    companion object {
        private const val TAG = "WatchCall"
        private const val SCO_TIMEOUT_MS = 3_000L
        private const val SCO_SETTLE_MS = 800L
        private const val PLAYBACK_TO_RECORD_DELAY_MS = 300L
        private const val RECORD_TIMEOUT_MS = 90_000L

        /** Chamada em andamento, para a tela do app e os botões da notificação. */
        @Volatile
        var current: WatchCallConnection? = null
            private set
    }
}
