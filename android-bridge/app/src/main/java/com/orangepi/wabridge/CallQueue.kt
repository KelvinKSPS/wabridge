package com.orangepi.wabridge

/**
 * Fila de chamadas: uma por vez.
 *
 * Sem isso, uma nota de voz que chega enquanto outra está em andamento abre
 * uma segunda chamada por cima da primeira — o usuário ouve duas coisas ao
 * mesmo tempo e a resposta sai misturada.
 *
 * Regra: a primeira nota toca; as siguientes ficam na fila e tocam quando a
 * chamada atual terminar.
 *
 * Classe pura (sem Android) para poder ser testada em JVM.
 */
class CallQueue {

    private val pending = ArrayDeque<CallPayload>()

    /** true enquanto há uma chamada em andamento. */
    var active: Boolean = false
        private set

    /** Quantas notas estão esperando na fila. */
    val size: Int get() = pending.size

    /**
     * Aceita uma nota de voz.
     * @return true se a chamada toca agora; false se foi enfileirada.
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
     * A chamada atual terminou.
     * @return a próxima nota da fila (para tocar agora), ou null.
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

    /** Esvazia a fila (usado quando o app para). */
    @Synchronized
    fun clear() {
        pending.clear()
        active = false
    }
}
