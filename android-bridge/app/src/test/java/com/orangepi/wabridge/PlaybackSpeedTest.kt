package com.orangepi.wabridge

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackSpeedTest {

    @Test
    fun `o ciclo passa por 1x, 1.25x, 1.5x, 2x e volta para 1x`() {
        var speed = 1.0f
        speed = PlaybackSpeed.next(speed)
        assertEquals(1.25f, speed, 0.001f)
        speed = PlaybackSpeed.next(speed)
        assertEquals(1.5f, speed, 0.001f)
        speed = PlaybackSpeed.next(speed)
        assertEquals(2.0f, speed, 0.001f)
        speed = PlaybackSpeed.next(speed)
        assertEquals(1.0f, speed, 0.001f)
    }

    @Test
    fun `velocidade fora do ciclo volta para o padrao`() {
        assertEquals(1.0f, PlaybackSpeed.next(1.7f), 0.001f)
        assertEquals(1.0f, PlaybackSpeed.next(0.0f), 0.001f)
    }

    @Test
    fun `a velocidade e limitada entre 0.5 e 2.0`() {
        assertEquals(0.5f, PlaybackSpeed.clamp(0.1f), 0.001f)
        assertEquals(2.0f, PlaybackSpeed.clamp(5.0f), 0.001f)
        assertEquals(1.5f, PlaybackSpeed.clamp(1.5f), 0.001f)
    }

    @Test
    fun `o rotulo mostra a velocidade`() {
        assertEquals("1x", PlaybackSpeed.label(1.0f))
        assertEquals("1.5x", PlaybackSpeed.label(1.5f))
        assertEquals("2x", PlaybackSpeed.label(2.0f))
        assertEquals("0.5x", PlaybackSpeed.label(0.5f))
    }

    @Test
    fun `o menu tem as velocidades esperadas`() {
        assertEquals(
            listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f),
            PlaybackSpeed.menu,
        )
    }
}
