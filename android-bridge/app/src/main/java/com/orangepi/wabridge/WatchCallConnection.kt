package com.orangepi.wabridge

import android.app.NotificationManager
import android.content.Context
import android.net.Uri
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
     * Telecom states
     * ---------------------------------------------------------------- */

    override fun onAnswer() {
        Log.i(TAG, "atendida: ${payload.senderName}")
        if (!state.onAnswer()) {
            Log.w(TAG, "atendimento fora de ordem (fase ${state.phase})")
            return
        }
        Prefs.addCallHistory(ctx, payload, CallHistoryOutcome.ANSWERED)
        setActive()
        // If the call was answered in the system dialer, close the app screen.
        android.os.Handler(android.os.Looper.getMainLooper()).post { CallActivity.active?.finish() }

        val voice = VoiceSession(ctx, payload, onEnded = { onSessionEnded() }, telecomCall = true)
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

    override fun onCallAudioStateChanged(state: CallAudioState?) {
        state ?: return
        val canBluetooth = state.supportedRouteMask and CallAudioState.ROUTE_BLUETOOTH != 0
        Log.i(TAG, "rota de áudio: ${CallAudioState.audioRouteToString(state.route)} (bluetooth disponível=$canBluetooth)")
        if (state.route == CallAudioState.ROUTE_BLUETOOTH) session?.onBluetoothRouted()
    }

    /** Answer from the app screen (watch without a system dialer). */
    fun answerNow() {
        onAnswer()
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
        private const val TAG = "WatchCall"

        /** Ongoing call, used by the app screen and notification buttons. */
        @Volatile
        var current: WatchCallConnection? = null
            private set
    }
}
