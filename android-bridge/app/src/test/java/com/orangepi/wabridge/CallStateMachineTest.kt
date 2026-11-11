package com.orangepi.wabridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallStateMachineTest {

    @Test
    fun `comeca em IDLE`() {
        assertEquals(CallPhase.IDLE, CallStateMachine().phase)
    }

    @Test
    fun `fluxo completo da chamada`() {
        val m = CallStateMachine()
        assertTrue(m.onIncoming())
        assertEquals(CallPhase.INCOMING, m.phase)
        assertTrue(m.onAnswer())
        assertEquals(CallPhase.ANSWERED, m.phase)
        assertTrue(m.onPlaybackStarted())
        assertEquals(CallPhase.PLAYING, m.phase)
        assertTrue(m.onPlaybackFinished())
        assertEquals(CallPhase.RECORDING, m.phase)
        assertTrue(m.onHangup())
        assertEquals(CallPhase.ENDED, m.phase)
        assertTrue(m.ended)
    }

    @Test
    fun `nao aceita atender antes de tocar`() {
        val m = CallStateMachine()
        assertFalse(m.onAnswer())
        assertEquals(CallPhase.IDLE, m.phase)
    }

    @Test
    fun `sessao sem Telecom atende direto`() {
        val m = CallStateMachine()
        assertTrue(m.onSessionAnswer())
        assertEquals(CallPhase.ANSWERED, m.phase)
    }

    @Test
    fun `nao aceita duas chamadas`() {
        val m = CallStateMachine()
        assertTrue(m.onIncoming())
        assertFalse(m.onIncoming())
    }

    @Test
    fun `desligar duas vezes so envia uma vez`() {
        val m = CallStateMachine()
        m.onIncoming()
        m.onAnswer()
        assertTrue(m.onHangup())
        assertFalse(m.onHangup())
        assertTrue(m.ended)
    }

    @Test
    fun `erro na preparacao ainda leva a gravacao`() {
        val m = CallStateMachine()
        m.onIncoming()
        m.onAnswer()
        // nunca entrou em PLAYING
        assertTrue(m.onPlaybackFinished())
        assertEquals(CallPhase.RECORDING, m.phase)
    }

    @Test
    fun `timeout de seguranca em 90 segundos`() {
        val m = CallStateMachine()
        assertFalse(m.shouldTimeout(89_999))
        assertTrue(m.shouldTimeout(90_000))
        assertTrue(m.shouldTimeout(120_000))
    }

    @Test
    fun `timeout customizado`() {
        val m = CallStateMachine(recordTimeoutMs = 5_000)
        assertFalse(m.shouldTimeout(4_999))
        assertTrue(m.shouldTimeout(5_000))
    }

    @Test
    fun `desligar durante gravacao curta e recusado`() {
        val m = CallStateMachine()
        m.onSessionAnswer()
        m.onPlaybackStarted()
        m.onPlaybackFinished()
        m.onRecordStarted()

        // 300 ms de gravação: ainda abaixo do mínimo, desligar é recusado
        assertFalse(m.onHangup(300))
        assertEquals(CallPhase.RECORDING, m.phase)

        // depois do mínimo, desliga
        assertTrue(m.onHangup(CallStateMachine.MIN_RECORDING_MS))
        assertEquals(CallPhase.ENDED, m.phase)
    }

    @Test
    fun `desligar antes de gravar sempre passa`() {
        val m = CallStateMachine()
        m.onIncoming()
        assertTrue(m.onHangup(0))
        assertEquals(CallPhase.ENDED, m.phase)
    }
}
