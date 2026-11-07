package com.orangepi.wabridge

import org.junit.Assert.assertEquals
import org.junit.Test

class WsUrlsTest {

    @Test
    fun `http vira ws`() {
        assertEquals("ws://192.168.0.10:8097/ws", WsUrls.wsUrl("http://192.168.0.10:8097"))
    }

    @Test
    fun `https vira wss`() {
        assertEquals("wss://exemplo.com/ws", WsUrls.wsUrl("https://exemplo.com"))
    }

    @Test
    fun `remove barra final`() {
        assertEquals("ws://192.168.0.10:8097/ws", WsUrls.wsUrl("http://192.168.0.10:8097/"))
    }

    @Test
    fun `ignora espacos`() {
        assertEquals("ws://10.0.0.2:8090/ws", WsUrls.wsUrl("  http://10.0.0.2:8090  "))
    }

    @Test
    fun `descarta caminho extra`() {
        assertEquals("ws://10.0.0.2:8090/ws", WsUrls.wsUrl("http://10.0.0.2:8090/api"))
    }

    @Test
    fun `monta url http`() {
        assertEquals(
            "http://10.0.0.2:8090/reply/audio",
            WsUrls.httpUrl("http://10.0.0.2:8090", "/reply/audio"),
        )
    }

    @Test
    fun `monta url http sem barra inicial`() {
        assertEquals(
            "http://10.0.0.2:8090/reply/audio",
            WsUrls.httpUrl("http://10.0.0.2:8090/", "reply/audio"),
        )
    }
}
