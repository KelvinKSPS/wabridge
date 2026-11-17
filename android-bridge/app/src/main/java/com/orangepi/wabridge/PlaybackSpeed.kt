package com.orangepi.wabridge

/**
 * Playback rates for voice notes.
 *
 * This Android-free object can be tested on the JVM.
 */
object PlaybackSpeed {

    /** Steps used by the call-screen speed button. */
    val options = listOf(1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 2.5f, 3.0f)

    /** Preset values shown in the playback-speed menu. */
    val menu = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 2.5f, 3.0f)

    const val MIN = 0.25f
    const val MAX = 4.0f
    const val DEFAULT = 1.0f

    /** Returns the next preset rate in the cycle. */
    fun next(current: Float): Float {
        val index = options.indexOfFirst { it == current }
        return if (index < 0) DEFAULT else options[(index + 1) % options.size]
    }

    /** Clamps a playback rate to the supported range. */
    fun clamp(value: Float): Float = value.coerceIn(MIN, MAX)

    /** Formats a playback-rate label such as "1.5x". */
    fun label(speed: Float): String {
        val v = clamp(speed)
        return if (v % 1f == 0f) {
            "${v.toInt()}x"
        } else {
            "${v}x"
        }
    }
}
