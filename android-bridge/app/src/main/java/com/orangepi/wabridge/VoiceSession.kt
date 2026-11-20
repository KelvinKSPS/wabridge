package com.orangepi.wabridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.SystemClock
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
 * The voice call itself: starts the watch's SCO channel, plays received audio,
 * records the microphone reply, and uploads the file to the server.
 *
 * Does not depend on Telecom — works both inside a `Connection` and
 * on its own through the app screen (watch without a system dialer).
 */
class VoiceSession(
    private val ctx: Context,
    private val payload: CallPayload,
    private val baseUrl: String = Prefs.engineUrl(ctx),
    /** Called when the session ends (timeout, user hangup, etc.). */
    private val onEnded: (() -> Unit)? = null,
    /** Called when the user tries to hang up before the minimum recording duration. */
    private val onTooShort: ((Long) -> Unit)? = null,
) {

    private val audio: AudioManager = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var player: MediaPlayer? = null
    private var recorder: MediaRecorder? = null
    private var recorderFile: File? = null
    private var scoReceiver: BroadcastReceiver? = null
    private val scoConnected = CompletableDeferred<Boolean>()
    private val playbackGate = CompletableDeferred<Unit>()
    private var timeoutJob: Job? = null
    private val state = CallStateMachine()

    val phase: CallPhase get() = state.phase
    val recording: Boolean get() = recorder != null

    /** True when the session has ended (recording is no longer possible). */
    val ended: Boolean get() = state.ended

    /** Current playback speed (1.0 = normal). */
    @Volatile
    var speed: Float = 1.0f
        private set

    /**
     * Change playback speed (0.5 to 3.0).
     *
     * Applies to the voice note currently playing: listen faster
     * (or slower) without losing the call.
     */
    fun setSpeed(value: Float): Float {
        val v = PlaybackSpeed.clamp(value)
        val p = player ?: return speed
        runCatching {
            val params = p.playbackParams ?: android.media.PlaybackParams()
            p.playbackParams = params.setSpeed(v)
            speed = v
            Log.i(TAG, "velocidade: ${v}x")
        }.onFailure {
            Log.w(TAG, "não foi possível mudar a velocidade: ${it.message}")
        }
        return speed
    }

    /** When recording started (0 = not recording). */
    @Volatile
    private var recordingStartedAt: Long = 0L

    /** Elapsed recording time, in ms. */
    val recordElapsedMs: Long
        get() = if (recordingStartedAt == 0L) 0L else SystemClock.elapsedRealtime() - recordingStartedAt

    /* ---------------------------------------------------------------- *
     * Answer: SCO -> play received audio
     * ---------------------------------------------------------------- */

    fun answer(): Boolean {
        // The session starts here: the "incoming call" event is implicit.
        if (!state.onSessionAnswer()) {
            Log.w(TAG, "atendimento fora de ordem (fase ${state.phase})")
            return false
        }
        Log.i(TAG, "sessão de voz iniciada: ${payload.senderName}")
        current = this

        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        audio.isSpeakerphoneOn = false
        routeToScoDevice()

        registerScoReceiver()
        audio.startBluetoothSco()
        audio.isBluetoothScoOn = true

        val answeredAt = SystemClock.elapsedRealtime()
        val delayMs = Prefs.audioDelayMs(ctx)
        preparePlayback()               // baixa e prepara o áudio JÁ, em paralelo à espera

        scope.launch {
            val ok = withTimeoutOrNull(SCO_TIMEOUT_MS) { scoConnected.await() } ?: false
            if (!ok) Log.w(TAG, "SCO não conectou a tempo; seguindo assim mesmo")
            val restante = delayMs - (SystemClock.elapsedRealtime() - answeredAt)
            if (restante > 0) delay(restante)
            Log.i(TAG, "áudio liberado ${SystemClock.elapsedRealtime() - answeredAt} ms após atender (config ${delayMs} ms)")
            playbackGate.complete(Unit)
        }
        return true
    }

    /**
     * Routes microphone input to the SCO device currently in use.
     *
     * `availableCommunicationDevices` includes available SCO devices, even
     * disconnected ones — routing the microphone to a headset that is not
     * connected results in silent recording. Therefore, use the active
     * communication device (or the SCO state).
     */
    private fun routeToScoDevice() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        runCatching {
            val current = audio.communicationDevice
            if (current != null && current.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                audio.setCommunicationDevice(current)
                Log.i(TAG, "dispositivo de comunicação: ${current.productName}")
            } else {
                // Without a Telecom call nothing routes audio to the watch, so pick
                // a Bluetooth SCO device ourselves — only one that is really
                // connected, since routing to a disconnected one records silence.
                // Android hides the real MAC from normal apps, so the device cannot
                // be matched by address; ask the headset profile instead.
                val available = audio.availableCommunicationDevices
                val headset = BluetoothHelper.headsetConnected(ctx)
                Log.i(TAG, "saídas: ${available.map { "${it.type}:${it.productName}" }} (headset=$headset)")
                val sco = available.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO && headset
                }
                if (sco != null) {
                    val ok = audio.setCommunicationDevice(sco)
                    Log.i(TAG, "roteando para ${sco.productName} (ok=$ok)")
                } else {
                    Log.i(
                        TAG,
                        "sem SCO em uso (atual=${current?.productName}); microfone fica no aparelho",
                    )
                }
            }
        }
    }

    private fun registerScoReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED) return
                val scoState = intent.getIntExtra(
                    AudioManager.EXTRA_SCO_AUDIO_STATE,
                    AudioManager.SCO_AUDIO_STATE_ERROR,
                )
                when (scoState) {
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

    private fun preparePlayback() {
        try {
            val mp = MediaPlayer()
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            mp.setDataSource(payload.audioUrl)
            mp.setOnPreparedListener {
                scope.launch {
                    playbackGate.await()
                    startPrepared(it)
                }
            }
            mp.setOnCompletionListener { onPlaybackFinished() }
            mp.setOnErrorListener { _, _, _ ->
                Log.e(TAG, "erro na reprodução; indo para gravação")
                scope.launch {
                    playbackGate.await()
                    onPlaybackFinished()
                }
                true
            }
            player = mp
            mp.prepareAsync()
        } catch (e: Exception) {
            Log.e(TAG, "falha ao tocar áudio: ${e.message}")
            scope.launch {
                playbackGate.await()
                onPlaybackFinished()
            }
        }
    }

    private fun startPrepared(mp: MediaPlayer) {
        if (ended || player !== mp) {
            Log.i(TAG, "sessão encerrada antes de reproduzir (ended=$ended, playerMatch=${player === mp})")
            return
        }
        state.onPlaybackStarted()
        // Apply the default speed before starting playback.
        val v = Prefs.speed(ctx)
        if (v != 1.0f) setSpeed(v)
        if (!mp.isPlaying) mp.start()
    }

    /* ---------------------------------------------------------------- *
     * Playback to recording.
     * ---------------------------------------------------------------- */

    private fun onPlaybackFinished() {
        if (!state.onPlaybackFinished()) {
            Log.w(TAG, "playback finished fora de ordem (fase ${state.phase})")
            return
        }
        scope.launch {
            // Configurable delay before the "speak now" beep. The user may need
            // more time to realize playback ended before the recording starts.
            delay(Prefs.beepDelayMs(ctx))
            // "Speak now" cue: beep + vibration (on the watch, vibration is
            // much more noticeable than sound — without it, the user would hang up
            // right afterward and send 0.4 seconds of silence).
            runCatching {
                val tone = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 100)
                tone.startTone(ToneGenerator.TONE_PROP_BEEP, 400)
                delay(450)
                tone.release()
            }
            vibrateToSpeak()
            delay(200)
            startRecording()
        }
    }

    /** Short vibration: "it's your turn to speak now." */
    private fun vibrateToSpeak() {
        runCatching {
            val vibrator = ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            if (!vibrator.hasVibrator()) return
            vibrator.vibrate(VibrationEffect.createOneShot(400, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    /**
     * Request audio focus for recording.
     *
     * Without focus, the system may cut off the microphone when the app is
     * not on screen — the reply contained 0.4 seconds of silence.
     */
    private fun requestAudioFocus() {
        runCatching {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener { }
                .build()
            audio.requestAudioFocus(request)
            Log.i(TAG, "foco de áudio pedido para a gravação")
        }
    }

    private fun startRecording() {
        val useOpus = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val ext = if (useOpus) "ogg" else "m4a"
        val file = File(ctx.cacheDir, "reply_${payload.audioId}_${System.currentTimeMillis()}.$ext")
        // If an SCO device (headset/BT) is in use, the voice source captures
        // its microphone. Without SCO, use the device microphone:
        // VOICE_COMMUNICATION is silent in this case when the app runs in
        // the background (the system cuts off the "voice source").
        val scoConnected = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audio.communicationDevice?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            } else {
                audio.isBluetoothScoOn
            }
        }.getOrDefault(false)
        val sources = if (scoConnected) {
            listOf(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                MediaRecorder.AudioSource.MIC,
                MediaRecorder.AudioSource.VOICE_CALL,
            )
        } else {
            listOf(
                MediaRecorder.AudioSource.MIC,
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                MediaRecorder.AudioSource.VOICE_CALL,
            )
        }
        Log.i(TAG, "fontes de áudio: $sources (sco conectado=$scoConnected)")

        // Request microphone foreground access immediately before capture starts.
        requestAudioFocus()

        for (source in sources) {
            var microphonePromoted = false
            try {
                val rec = MediaRecorder()
                rec.setAudioSource(source)
                if (useOpus) {
                    rec.setOutputFormat(MediaRecorder.OutputFormat.OGG)
                    rec.setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
                } else {
                    rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                }
                rec.setAudioSamplingRate(Prefs.audioSampleRate(ctx))
                rec.setAudioEncodingBitRate(32000)
                rec.setAudioChannels(1)
                rec.setOutputFile(file.absolutePath)
                rec.prepare()
                BridgeForegroundService.requestMicrophone(ctx)
                microphonePromoted = true
                rec.start()
                recorder = rec
                recorderFile = file
                recordingStartedAt = SystemClock.elapsedRealtime()
                Log.i(TAG, "gravando em ${file.absolutePath} (source=$source)")
                break
            } catch (e: Exception) {
                if (microphonePromoted) BridgeForegroundService.releaseMicrophone(ctx)
                Log.w(TAG, "falha com source=$source: ${e.message}")
                runCatching { file.delete() }
            }
        }

        if (recorder == null) {
            BridgeForegroundService.releaseMicrophone(ctx)
            Log.e(TAG, "não foi possível iniciar a gravação")
            return
        }

        // Safety timeout: 90 seconds of recording.
        timeoutJob = scope.launch {
            delay(RECORD_TIMEOUT_MS)
            Log.w(TAG, "timeout de gravação; encerrando")
            hangup()
        }

        // Silence monitor: if the microphone captures nothing (amplitude 0),
        // the system is cutting off the source. Log this for diagnostics.
        scope.launch {
            var maxAmp = 0
            repeat(6) {
                delay(500)
                val rec = recorder ?: return@launch
                val amp = runCatching { rec.maxAmplitude }.getOrDefault(0)
                if (amp > maxAmp) maxAmp = amp
            }
            if (maxAmp == 0) {
                Log.w(TAG, "microfone MUDO (amplitude 0 após 3s) — o sistema silenciou a fonte")
            } else {
                Log.i(TAG, "microfone ok (amplitude máx $maxAmp)")
            }
        }
    }

    /* ---------------------------------------------------------------- *
     * Desligar -> envia a resposta
     * ---------------------------------------------------------------- */

    fun hangup() {
        val elapsed = recordElapsedMs
        val wasRecording = recorder != null
        if (!state.onHangup(elapsed)) {
            // Already ended; a second hangup (e.g. double tap) is a no-op.
            return
        }
        val tooShort = wasRecording && state.isTooShort(elapsed)
        timeoutJob?.cancel()
        recordingStartedAt = 0L

        runCatching { player?.stop() }
        player?.release()
        player = null

        val durationSec = (elapsed / 1000).coerceAtLeast(1).toInt()
        val file = recorderFile
        val stopError = runCatching { recorder?.stop() }.exceptionOrNull()
        if (stopError != null) Log.e(TAG, "falha ao finalizar gravação", stopError)
        recorder?.release()
        recorder = null
        BridgeForegroundService.releaseMicrophone(ctx)

        runCatching {
            audio.stopBluetoothSco()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) audio.clearCommunicationDevice()
            audio.isBluetoothScoOn = false
        }
        scoReceiver?.let { runCatching { ctx.unregisterReceiver(it) } }
        scoReceiver = null
        audio.mode = AudioManager.MODE_NORMAL
        // Release audio focus.
        runCatching {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            audio.abandonAudioFocusRequest(
                AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(attrs)
                    .setOnAudioFocusChangeListener { }
                    .build(),
            )
        }

        if (tooShort) {
            // Recording too short to be useful: discard it instead of sending near-silence.
            Log.w(TAG, "gravação de ${elapsed}ms descartada (abaixo do mínimo de ${CallStateMachine.MIN_RECORDING_MS}ms)")
            runCatching { file?.delete() }
            onTooShort?.invoke(elapsed)
        } else if (file != null && file.length() > 0) {
            upload(file, durationSec, stopError?.toString())
        } else {
            Log.i(TAG, "sem gravação para enviar")
        }

        if (current === this) current = null
        onEnded?.invoke()
    }

    private fun upload(file: File, durationSec: Int, stopError: String?) {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val metadata = org.json.JSONObject()
                .put("audioId", payload.audioId)
                .put("to", payload.senderNumber)
                .put("durationSec", durationSec)
                .put("engine", Prefs.mode(ctx).name)
                .put("sampleRateRequested", Prefs.audioSampleRate(ctx))
                .put("stopError", stopError ?: org.json.JSONObject.NULL)
            val archived = runCatching {
                ReplyArchive.preserve(File(ctx.filesDir, "reply-diagnostics"), file, metadata).also {
                    Log.i(TAG, "original preservado arquivo=${file.name} sha256=${metadata.getString("sha256")} bytes=${file.length()}")
                }
            }.onFailure { Log.e(TAG, "não foi possível preservar gravação; original mantido no cache", it) }
            if (stopError != null) {
                Log.e(TAG, "gravação não enviada: falha na finalização; original preservado")
                return@launch
            }
            val ok = Uploader.sendReply(baseUrl, payload.audioId, payload.senderNumber, file, durationSec)
            Log.i(TAG, if (ok) "servidor aceitou resposta; entrega não confirmada" else "falha ao enviar resposta; original preservado")
            if (archived.isSuccess) file.delete()
        }
    }

    companion object {
        private const val TAG = "VoiceSession"
        private const val SCO_TIMEOUT_MS = 3_000L
        private const val RECORD_TIMEOUT_MS = 90_000L

        /** Ongoing voice session (used by the screen to show status). */
        @Volatile
        var current: VoiceSession? = null
            private set
    }
}
