package com.orangepi.wabridge

import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.telecom.CallAudioState
import android.telecom.Connection
import android.telecom.DisconnectCause
import android.telecom.TelecomManager
import android.util.Log

/**
 * Telecom adapter: shows the call as a watch call (with contact name and
 * number) and delegates audio to [VoiceSession].
 */
class WatchCallConnection(
    private val ctx: Context,
    val payload: CallPayload,
) : Connection() {

    private val state = CallStateMachine()
    private var session: VoiceSession? = null
    private var done = false

    init {
        state.onIncoming()
        current = this
        setCallerDisplayName(
            payload.displayName.ifBlank { payload.senderNumber },
            TelecomManager.PRESENTATION_ALLOWED,
        )
        setAddress(
            Uri.fromParts("tel", payload.senderNumber, null),
            TelecomManager.PRESENTATION_ALLOWED,
        )
        setConnectionCapabilities(Connection.CAPABILITY_MUTE)
    }

    /* ---------------------------------------------------------------- *
     * Estados do Telecom
     * ---------------------------------------------------------------- */

    override fun onAnswer() {
        Log.i(TAG, "atendida: ${payload.senderName}")
        if (!state.onAnswer()) {
            Log.w(TAG, "atendimento fora de ordem (fase ${state.phase})")
            return
        }
        Prefs.addCallHistory(ctx, payload, CallHistoryOutcome.ANSWERED)
        setActive()
        // A route requested while still ringing may be dropped; ask again now.
        if (preferBluetooth) setAudioRoute(CallAudioState.ROUTE_BLUETOOTH)

        // If the call was answered in the system dialer, close the app screen.
        android.os.Handler(android.os.Looper.getMainLooper()).post { CallActivity.active?.finish() }

        val voice = VoiceSession(ctx, payload, onEnded = { onSessionEnded() })
        session = voice
        voice.answer()
    }

    override fun onReject() {
        Log.i(TAG, "rejeitada")
        if (session == null) Prefs.addCallHistory(ctx, payload, CallHistoryOutcome.REJECTED)
        hangup(DisconnectCause.REJECTED)
    }

    override fun onDisconnect() {
        Log.i(TAG, "desligada")
        hangup(DisconnectCause.LOCAL)
    }

    override fun onAbort() {
        hangup(DisconnectCause.CANCELED)
    }

    /** Auto-answered calls are not picked up on the watch, so Android may keep audio on the phone. */
    private var preferBluetooth = false
    private var routeRetries = 0

    override fun onCallAudioStateChanged(state: CallAudioState?) {
        state ?: return
        val canBluetooth = state.supportedRouteMask and CallAudioState.ROUTE_BLUETOOTH != 0
        Log.i(TAG, "rota de áudio: ${CallAudioState.audioRouteToString(state.route)} (bluetooth disponível=$canBluetooth)")
        if (preferBluetooth && !done && canBluetooth &&
            state.route != CallAudioState.ROUTE_BLUETOOTH && routeRetries < 3
        ) {
            routeRetries++
            setAudioRoute(CallAudioState.ROUTE_BLUETOOTH)
        }
    }

    /** Answer from the app screen (watch without a system dialer). */
    fun answerNow() {
        onAnswer()
    }

    /**
     * Answers on its own once the watch can take the call audio. Answering
     * right away keeps the call on the phone: the watch needs a moment to
     * learn about the call before Bluetooth becomes an available route.
     */
    fun answerWhenWatchReady() {
        val handler = Handler(Looper.getMainLooper())
        val startedAt = SystemClock.elapsedRealtime()
        handler.post(object : Runnable {
            override fun run() {
                if (done || session != null) return
                val waited = SystemClock.elapsedRealtime() - startedAt
                val route = (callAudioState?.supportedRouteMask ?: 0) and CallAudioState.ROUTE_BLUETOOTH != 0
                val headset = BluetoothHelper.headsetConnected(ctx)
                val sco = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    (ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
                        .availableCommunicationDevices.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
                val ready = route || headset || sco
                if (!ready && waited < AUTO_ANSWER_MAX_WAIT_MS) {
                    handler.postDelayed(this, 200)
                    return
                }
                Log.i(TAG, "atendendo sozinho após ${waited}ms (rota=$route headset=$headset sco=$sco)")
                preferBluetooth = ready
                if (ready) setAudioRoute(CallAudioState.ROUTE_BLUETOOTH)
                onAnswer()
            }
        })
    }

    /**
     * Reject/end the call from the app screen.
     * @return true if the call ended; false if hangup was
     * rejected (recording is still too short).
     */
    fun rejectNow(): Boolean {
        if (session == null) Prefs.addCallHistory(ctx, payload, CallHistoryOutcome.REJECTED)
        hangup(DisconnectCause.REJECTED)
        return done
    }

    private fun onSessionEnded() {
        // The session ended (timeout, user hangup, etc.); Telecom needs to know.
        if (done) return
        done = true
        runCatching {
            setDisconnected(DisconnectCause(DisconnectCause.LOCAL))
            destroy()
        }
        if (current === this) current = null
        // The next queued voice note can play now.
        BridgeForegroundService.notifyCallEnded(ctx)
    }

    private fun hangup(cause: Int) {
        val session = this.session
        if (session != null) {
            // Hanging up always ends the session now; if the recording was too
            // short, VoiceSession discards it instead of blocking the hangup.
            session.hangup()
            this.session = null
        }

        if (!state.onHangup()) return
        if (current === this) current = null

        // Hide the call notification.
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
            ?.cancel(CallNotifier.NOTIFICATION_CALL_ID)

        if (done) return
        done = true
        setDisconnected(DisconnectCause(cause))
        destroy()
        BridgeForegroundService.notifyCallEnded(ctx)
    }

    companion object {
        private const val AUTO_ANSWER_MAX_WAIT_MS = 4000L
        private const val TAG = "WatchCall"

        /** Ongoing call, used by the app screen and notification buttons. */
        @Volatile
        var current: WatchCallConnection? = null
            private set
    }
}
