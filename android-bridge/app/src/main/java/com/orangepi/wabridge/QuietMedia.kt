package com.orangepi.wabridge

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper

/**
 * Exposes the pending quiet note as a media session, so anything that controls
 * phone media (watch music card, headset button, car) can start it:
 * play = listen and record the reply, pause/stop = finish and send.
 */
object QuietMedia {
    private val main = Handler(Looper.getMainLooper())
    private var session: MediaSession? = null
    private var pending: CallPayload? = null
    private var voice: VoiceSession? = null

    fun offer(ctx: Context, payload: CallPayload) = main.post {
        val app = ctx.applicationContext
        pending = payload
        val s = session ?: MediaSession(app, "wabridge").also { created ->
            created.setCallback(object : MediaSession.Callback() {
                override fun onPlay() = play(app)
                override fun onPause() = stop()
                override fun onStop() = stop()
            }, main)
            session = created
        }
        s.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, payload.displayName.ifBlank { payload.senderNumber })
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "Wabridge")
                .putLong(MediaMetadata.METADATA_KEY_DURATION, payload.durationSec * 1000L)
                .build(),
        )
        s.isActive = true
        claimMediaControl()
    }

    /**
     * Android routes media controls to the app that most recently played real
     * audio, so play a short silent clip to become that app, then show "paused".
     */
    private fun claimMediaControl() {
        setState(PlaybackState.STATE_PLAYING)
        val silence = ByteArray(SILENCE_BYTES)
        val track = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SILENCE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(silence.size)
                .build()
                .also {
                    it.write(silence, 0, silence.size)
                    it.play()
                }
        }.getOrNull()
        main.postDelayed({
            runCatching { track?.stop() }
            track?.release()
            if (voice == null && !viaCall && pending != null) setState(PlaybackState.STATE_PAUSED)
        }, SILENCE_MS)
    }

    private const val SILENCE_RATE = 8000
    private const val SILENCE_MS = 1500L
    private const val SILENCE_BYTES = (SILENCE_RATE * 2 * SILENCE_MS / 1000).toInt()

    private fun play(app: Context) {
        if (viaCall || VoiceSession.current?.ended == false) return
        val payload = pending ?: return
        (app.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
            ?.cancel(CallNotifier.quietNotificationId(payload))
        // Prefer a call (answered on the watch) so audio goes through the watch;
        // without the service, fall back to a direct session on the phone.
        val service = BridgeForegroundService.instance
        if (service?.startCardCall(payload) == true) {
            viaCall = true
            main.postDelayed({
                // Telecom never created the call: do not stay stuck in "playing".
                if (viaCall && WatchCallConnection.current == null && VoiceSession.current == null) {
                    BridgeForegroundService.notifyCallEnded(app)
                }
            }, CALL_SETUP_TIMEOUT_MS)
        } else {
            Prefs.addCallHistory(app, payload, CallHistoryOutcome.ANSWERED)
            val started = VoiceSession(service ?: app, payload, onEnded = { main.post { finished() } })
            voice = started
            started.answer()
        }
        setState(PlaybackState.STATE_PLAYING)
    }

    private fun stop() {
        if (viaCall) WatchCallConnection.current?.rejectNow() else voice?.hangup()
    }

    /** Called by the service whenever a call ends. */
    fun callEnded() = main.post { if (viaCall) finished() }

    private const val CALL_SETUP_TIMEOUT_MS = 5000L
    private var viaCall = false

    private fun finished() {
        viaCall = false
        voice = null
        pending = null
        setState(PlaybackState.STATE_STOPPED)
        session?.isActive = false
    }

    private fun setState(state: Int) {
        session?.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP,
                )
                .setState(state, 0L, 1f)
                .build(),
        )
    }
}
