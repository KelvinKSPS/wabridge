package com.orangepi.wabridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A fila garante uma chamada por vez: nota de voz que chega durante outra
 * chamada não abre uma segunda chamada por cima.
 */
class CallQueueTest {

    private fun nota(id: String) = CallPayload(
        audioId = id,
        senderName = "Sara",
        senderNumber = "5511999999999",
        audioUrl = "http://servidor/$id.wav",
    )

    @Test
    fun `primeira nota toca na hora`() {
        val q = CallQueue()
        assertTrue(q.offer(nota("a")))
        assertTrue(q.active)
        assertEquals(0, q.size)
    }

    @Test
    fun `nota durante a chamada vai para a fila`() {
        val q = CallQueue()
        assertTrue(q.offer(nota("a")))
        assertFalse(q.offer(nota("b")))
        assertFalse(q.offer(nota("c")))
        assertEquals(2, q.size)
    }

    @Test
    fun `ao terminar a chamada toca a proxima da fila na ordem`() {
        val q = CallQueue()
        q.offer(nota("a"))
        q.offer(nota("b"))
        q.offer(nota("c"))

        assertEquals("b", q.release()?.audioId)
        assertTrue(q.active)
        assertEquals("c", q.release()?.audioId)
        assertNull(q.release())
        assertFalse(q.active)
    }

    @Test
    fun `fila vazia libera a chamada`() {
        val q = CallQueue()
        q.offer(nota("a"))
        assertNull(q.release())
        assertFalse(q.active)
        // a próxima nota toca direto
        assertTrue(q.offer(nota("b")))
    }

    @Test
    fun `clear esvazia a fila`() {
        val q = CallQueue()
        q.offer(nota("a"))
        q.offer(nota("b"))
        q.clear()
        assertEquals(0, q.size)
        assertFalse(q.active)
    }
}
