package com.orangepi.wabridge

import android.app.NotificationManager
import android.content.Context
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
        setState(PlaybackState.STATE_PAUSED)
        s.isActive = true
    }

    private fun play(app: Context) {
        if (VoiceSession.current?.ended == false) return
        val payload = pending ?: return
        val ctx = BridgeForegroundService.instance ?: app
        (app.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
            ?.cancel(CallNotifier.quietNotificationId(payload))
        Prefs.addCallHistory(app, payload, CallHistoryOutcome.ANSWERED)
        val started = VoiceSession(ctx, payload, onEnded = { main.post { finished() } })
        voice = started
        started.answer()
        setState(PlaybackState.STATE_PLAYING)
    }

    private fun stop() {
        voice?.hangup()
    }

    private fun finished() {
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
