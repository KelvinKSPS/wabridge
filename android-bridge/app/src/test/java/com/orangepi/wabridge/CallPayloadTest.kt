package com.orangepi.wabridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CallPayloadTest {

    private val payload = CallPayload(
        audioId = "MSG_1",
        senderName = "João da Silva",
        senderNumber = "5511999999999",
        audioUrl = "http://10.0.0.2:8090/media/MSG_1.wav",
    )

    @Test
    fun `faz round trip do json`() {
        val parsed = CallPayload.fromJson(payload.toJson())
        assertNotNull(parsed)
        assertEquals(payload.audioId, parsed!!.audioId)
        assertEquals(payload.senderName, parsed.senderName)
        assertEquals(payload.senderNumber, parsed.senderNumber)
        assertEquals(payload.audioUrl, parsed.audioUrl)
    }

    @Test
    fun `json tem o tipo esperado`() {
        assertEquals("NEW_VOICE_NOTE", org.json.JSONObject(payload.toJson()).optString("type"))
    }

    @Test
    fun `payload invalido devolve null`() {
        assertNull(CallPayload.fromJson(null))
        assertNull(CallPayload.fromJson(""))
        assertNull(CallPayload.fromJson("nao é json"))
    }

    @Test
    fun `payload sem audioUrl devolve null`() {
        assertNull(CallPayload.fromJson("""{"type":"NEW_VOICE_NOTE","audioId":"X"}"""))
    }

    @Test
    fun `parseia payload do servidor`() {
        val raw = """
            {"type":"NEW_VOICE_NOTE","audioId":"MSG_2","senderName":"Ana",
             "senderNumber":"5511888888888","audioUrl":"http://10.0.0.2:8090/media/MSG_2.wav"}
        """.trimIndent()
        val parsed = CallPayload.fromJson(raw)
        assertNotNull(parsed)
        assertEquals("MSG_2", parsed!!.audioId)
        assertEquals("Ana", parsed.senderName)
        assertEquals("5511888888888", parsed.senderNumber)
    }
}
