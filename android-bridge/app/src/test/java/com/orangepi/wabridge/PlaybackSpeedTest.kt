package com.orangepi.wabridge

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackSpeedTest {

    @Test
    fun `o ciclo passa pelas sete velocidades e volta para o normal`() {
        var speed = 1.0f
        speed = PlaybackSpeed.next(speed)
        assertEquals(1.25f, speed, 0.001f)
        speed = PlaybackSpeed.next(speed)
        assertEquals(1.5f, speed, 0.001f)
        speed = PlaybackSpeed.next(speed)
        assertEquals(1.75f, speed, 0.001f)
        speed = PlaybackSpeed.next(speed)
        assertEquals(2.0f, speed, 0.001f)
        speed = PlaybackSpeed.next(speed)
        assertEquals(2.5f, speed, 0.001f)
        speed = PlaybackSpeed.next(speed)
        assertEquals(3.0f, speed, 0.001f)
        speed = PlaybackSpeed.next(speed)
        assertEquals(1.0f, speed, 0.001f)
    }

    @Test
    fun `velocidade fora do ciclo volta para o padrao`() {
        assertEquals(1.0f, PlaybackSpeed.next(1.7f), 0.001f)
        assertEquals(1.0f, PlaybackSpeed.next(0.0f), 0.001f)
    }

    @Test
    fun `custom speed is clamped to supported range`() {
        assertEquals(0.25f, PlaybackSpeed.clamp(0.1f), 0.001f)
        assertEquals(4.0f, PlaybackSpeed.clamp(5.0f), 0.001f)
        assertEquals(2.35f, PlaybackSpeed.clamp(2.35f), 0.001f)
    }

    @Test
    fun `o rotulo mostra a velocidade`() {
        assertEquals("1x", PlaybackSpeed.label(1.0f))
        assertEquals("1.75x", PlaybackSpeed.label(1.75f))
        assertEquals("1.5x", PlaybackSpeed.label(1.5f))
        assertEquals("2x", PlaybackSpeed.label(2.0f))
        assertEquals("2.5x", PlaybackSpeed.label(2.5f))
        assertEquals("3x", PlaybackSpeed.label(3.0f))
        assertEquals("0.5x", PlaybackSpeed.label(0.5f))
    }

    @Test
    fun `o menu tem as velocidades esperadas`() {
        assertEquals(
            listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 2.5f, 3.0f),
            PlaybackSpeed.menu,
        )
    }
}
