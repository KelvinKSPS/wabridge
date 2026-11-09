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
 * A chamada de voz em si: sobe o canal SCO do relógio, toca o áudio recebido,
 * grava a resposta do microfone e sobe o arquivo ao servidor.
 *
 * Não depende do Telecom — funciona tanto dentro de uma `Connection` quanto
 * sozinha, pela tela do app (relógio sem discador do sistema).
 */
class VoiceSession(
    private val ctx: Context,
    private val payload: CallPayload,
    private val baseUrl: String = Prefs.url(ctx),
    /** Chamado quando a sessão termina (timeout, hangup do usuário...). */
    private val onEnded: (() -> Unit)? = null,
    /** Chamado quando o usuário tentou desligar antes da gravação mínima. */
    private val onTooShort: ((Long) -> Unit)? = null,
) {

    private val audio: AudioManager = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var player: MediaPlayer? = null
    private var recorder: MediaRecorder? = null
    private var recorderFile: File? = null
    private var scoReceiver: BroadcastReceiver? = null
    private val scoConnected = CompletableDeferred<Boolean>()
    private var timeoutJob: Job? = null
    private val state = CallStateMachine()

    val phase: CallPhase get() = state.phase
    val recording: Boolean get() = recorder != null

    /** true quando a sessão já terminou (não dá mais para gravar). */
    val ended: Boolean get() = state.ended

    /** Momento em que a gravação começou (0 = não está gravando). */
    @Volatile
    private var recordingStartedAt: Long = 0L

    /** Quanto tempo já foi gravado, em ms. */
    val recordElapsedMs: Long
        get() = if (recordingStartedAt == 0L) 0L else SystemClock.elapsedRealtime() - recordingStartedAt

    /* ---------------------------------------------------------------- *
     * Atender: SCO -> tocar o áudio recebido
     * ---------------------------------------------------------------- */

    fun answer(): Boolean {
        // a sessão começa aqui: o evento "chamada entrando" é implícito
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

        scope.launch {
            val ok = withTimeoutOrNull(SCO_TIMEOUT_MS) { scoConnected.await() } ?: false
            if (!ok) Log.w(TAG, "SCO não conectou a tempo; seguindo assim mesmo")
            // buffer de negociação: o canal SCO precisa estabilizar antes do áudio
            delay(SCO_SETTLE_MS)
            startPlayback()
        }
        return true
    }

    /**
     * Roteia o microfone para o dispositivo SCO **em uso**.
     *
     * `availableCommunicationDevices` lista até os SCO disponíveis, mesmo
     * desconectados — apontar o microfone para um fone que não está
     * conectado produz silêncio na gravação. Por isso usamos o dispositivo
     * de comunicação ativo (ou o estado do SCO).
     */
    private fun routeToScoDevice() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        runCatching {
            val active = audio.activeCommunicationDevice
            if (active != null && active.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                audio.setCommunicationDevice(active)
                Log.i(TAG, "dispositivo de comunicação: ${active.productName}")
            } else {
                Log.i(
                    TAG,
                    "sem SCO em uso (ativo=${active?.productName}); microfone fica no aparelho",
                )
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
     * Reprodução -> gravação
     * ---------------------------------------------------------------- */

    private fun onPlaybackFinished() {
        if (!state.onPlaybackFinished()) {
            Log.w(TAG, "playback finished fora de ordem (fase ${state.phase})")
            return
        }
        scope.launch {
            delay(PLAYBACK_TO_RECORD_DELAY_MS)
            // aviso de "fale agora": bipe + vibração (no relógio o toque é
            // bem mais perceptível que o som — sem isso o usuário desligava
            // logo depois e mandava 0,4 s de silêncio)
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

    /** Vibração curta: "agora é a sua vez de falar". */
    private fun vibrateToSpeak() {
        runCatching {
            val vibrator = ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            if (!vibrator.hasVibrator()) return
            vibrator.vibrate(VibrationEffect.createOneShot(400, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    /**
     * Pede foco de áudio para a gravação.
     *
     * Sem foco, o sistema pode cortar o microfone quando o app não está na
     * tela — a resposta saía com 0,4 s de silêncio.
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
        val file = File(ctx.cacheDir, "reply_${payload.audioId}_${System.currentTimeMillis()}.m4a")
        // Se há um dispositivo SCO (fone/BT) em uso, a fonte de voz é a que
        // pega o microfone dele. Sem SCO, o microfone é o do aparelho:
        // VOICE_COMMUNICATION nesse caso fica muda quando o app está em
        // background (o sistema corta a "fonte de voz").
        val scoConnected = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching {
                audio.activeCommunicationDevice?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            }.getOrDefault(false)
        } else {
            runCatching { audio.isBluetoothScoOn }.getOrDefault(false)
        }
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

        // neste momento a tela de chamada está na frente: o app está em
        // primeiro plano, então é seguro pedir o tipo "microphone" do
        // foreground service (sem isso o Android silencia a gravação).
        BridgeForegroundService.requestMicrophone(ctx)

        // foco de áudio: sem ele o sistema pode silenciar a gravação
        requestAudioFocus()

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
                recordingStartedAt = SystemClock.elapsedRealtime()
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
            hangup()
        }

        // Vigia de silêncio: se o microfone não pegar nada (amplitude 0),
        // o sistema está cortando a fonte. Avisa no log para diagnóstico.
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
        if (!state.onHangup(elapsed)) {
            // gravação ainda muito curta: desligar agora mandaria silêncio
            Log.w(TAG, "desligar ignorado: só ${elapsed}ms de gravação")
            onTooShort?.invoke(elapsed)
            return
        }
        timeoutJob?.cancel()
        recordingStartedAt = 0L

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
        // devolve o foco de áudio
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

        if (file != null && file.length() > 0) {
            upload(file)
        } else {
            Log.i(TAG, "sem gravação para enviar")
        }

        if (current === this) current = null
        onEnded?.invoke()
    }

    private fun upload(file: File) {
        // escopo próprio: o da sessão pode estar sendo cancelado
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val ok = Uploader.sendReply(baseUrl, payload.audioId, payload.senderNumber, file)
            Log.i(TAG, if (ok) "resposta enviada" else "falha ao enviar resposta")
            file.delete()
        }
    }

    companion object {
        private const val TAG = "VoiceSession"
        private const val SCO_TIMEOUT_MS = 3_000L
        private const val SCO_SETTLE_MS = 800L
        private const val PLAYBACK_TO_RECORD_DELAY_MS = 300L
        private const val RECORD_TIMEOUT_MS = 90_000L

        /** Sessão de voz em andamento (a tela usa para mostrar o estado). */
        @Volatile
        var current: VoiceSession? = null
            private set
    }
}
