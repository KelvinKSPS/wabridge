package com.orangepi.wabridge

/**
 * Velocidades de reprodução das notas de voz.
 *
 * Objeto puro (sem Android) para poder ser testado em JVM.
 */
object PlaybackSpeed {

    /** Passos do botão de velocidade (ciclo). */
    val options = listOf(1.0f, 1.25f, 1.5f, 2.0f)

    /** Opções do menu de velocidade padrão. */
    val menu = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

    const val MIN = 0.5f
    const val MAX = 2.0f
    const val DEFAULT = 1.0f

    /** Próxima velocidade no ciclo: 1x → 1.25x → 1.5x → 2x → 1x. */
    fun next(current: Float): Float {
        val index = options.indexOfFirst { it == current }
        return if (index < 0) DEFAULT else options[(index + 1) % options.size]
    }

    /** Mantém a velocidade dentro dos limites. */
    fun clamp(value: Float): Float = value.coerceIn(MIN, MAX)

    /** Texto do botão: "1.5x". */
    fun label(speed: Float): String {
        val v = clamp(speed)
        return if (v % 1f == 0f) {
            "${v.toInt()}x"
        } else {
            "${v}x"
        }
    }
}
