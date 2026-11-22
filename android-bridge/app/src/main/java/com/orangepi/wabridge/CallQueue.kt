package com.orangepi.wabridge

/**
 * Call queue: one call at a time.
 *
 * Without this, a voice note arriving while another is playing opens a
 * second call on top of the first: the user hears two things at once and
 * the reply comes out mixed.
 *
 * Rule: the first note rings; the following ones wait in the queue and play
 * when the current call ends.
 *
 * Pure class (no Android) so it can be tested on the JVM.
 */
class CallQueue {

    private val pending = ArrayDeque<CallPayload>()

    /** true while a call is in progress. */
    var active: Boolean = false
        private set

    /** How many notes are waiting in the queue. */
    val size: Int get() = pending.size

    /**
     * Accepts a voice note.
     * @return true if the call rings now; false if it was queued.
     */
    @Synchronized
    fun offer(payload: CallPayload): Boolean {
        if (active) {
            pending.addLast(payload)
            return false
        }
        active = true
        return true
    }

    /**
     * The current call ended.
     * @return the next note in the queue (to play now), or null.
     */
    @Synchronized
    fun release(): CallPayload? {
        val next = pending.removeFirstOrNull()
        if (next == null) {
            active = false
            return null
        }
        return next
    }

    /** Empties the queue (used when the app stops). */
    @Synchronized
    fun clear() {
        pending.clear()
        active = false
    }
}
