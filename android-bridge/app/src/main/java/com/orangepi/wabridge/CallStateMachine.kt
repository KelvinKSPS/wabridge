package com.orangepi.wabridge

/**
 * Phases of the simulated call. The machine is the source of truth: the
 * [WatchCallConnection] only acts when the transition is valid.
 */
enum class CallPhase {
    /** Nothing happening: waiting for the server event. */
    IDLE,

    /** Call ringing on the watch, waiting for the user to answer. */
    INCOMING,

    /** Answered, SCO coming up. */
    ANSWERED,

    /** Playing the received audio. */
    PLAYING,

    /** Recording the reply. */
    RECORDING,

    /** Ended (triggers sending the audio). */
    ENDED,
}

/**
 * Pure state machine (no Android dependency) so it can be tested
 * on the JVM.
 */
class CallStateMachine(val recordTimeoutMs: Long = DEFAULT_RECORD_TIMEOUT_MS) {

    var phase: CallPhase = CallPhase.IDLE
        private set

    /** true when the call has already ended (avoids duplicate sending). */
    val ended: Boolean get() = phase == CallPhase.ENDED

    fun onIncoming(): Boolean = transition(CallPhase.IDLE, CallPhase.INCOMING)

    fun onAnswer(): Boolean = transition(CallPhase.INCOMING, CallPhase.ANSWERED)

    /**
     * Answers a session that did not go through the Telecom call flow
     * (app screen on a device without telephony): the "incoming call" is implicit.
     */
    fun onSessionAnswer(): Boolean {
        onIncoming()
        return onAnswer()
    }

    fun onPlaybackStarted(): Boolean = transition(CallPhase.ANSWERED, CallPhase.PLAYING)

    /** Applies both to the normal end of playback and to an error during Preparation. */
    fun onPlaybackFinished(): Boolean {
        if (phase != CallPhase.ANSWERED && phase != CallPhase.PLAYING) return false
        phase = CallPhase.RECORDING
        return true
    }

    fun onRecordStarted(): Boolean = transition(CallPhase.RECORDING, CallPhase.RECORDING)

    /**
     * Hang up. Always ends the call (idempotent: the second call
     * returns false). The caller decides whether the recording is too short to
     * send (see [isTooShort]); in that case it discards the audio instead of
     * refusing the hangup (before this, the call got stuck).
     */
    fun onHangup(recordElapsedMs: Long = Long.MAX_VALUE): Boolean {
        if (ended) return false
        phase = CallPhase.ENDED
        return true
    }

    /** true if the [recordElapsedMs] of recording is too short to be worth sending. */
    fun isTooShort(recordElapsedMs: Long, minMs: Long = MIN_RECORDING_MS): Boolean = recordElapsedMs < minMs

    /** Elapsed recording time that triggers the safety timeout. */
    fun shouldTimeout(elapsedMs: Long): Boolean = elapsedMs >= recordTimeoutMs

    private fun transition(from: CallPhase, to: CallPhase): Boolean {
        if (phase != from) return false
        phase = to
        return true
    }

    companion object {
        const val DEFAULT_RECORD_TIMEOUT_MS = 90_000L

        /** Minimum recording before hangup is allowed (avoids a silent reply). */
        const val MIN_RECORDING_MS = 500L
    }
}
