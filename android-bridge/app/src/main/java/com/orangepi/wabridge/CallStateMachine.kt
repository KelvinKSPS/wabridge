package com.orangepi.wabridge

/**
 * Fases da chamada simulada. A máquina é a fonte de verdade: o
 * [WatchCallConnection] só age quando a transição é válida.
 */
enum class CallPhase {
    /** Nada happening: aguardando o evento do servidor. */
    IDLE,

    /** Chamada tocando no relógio, aguardando o usuário atender. */
    INCOMING,

    /** Atendida, SCO subindo. */
    ANSWERED,

    /** Tocando o áudio recebido. */
    PLAYING,

    /** Gravando a resposta. */
    RECORDING,

    /** Encerrada (puxa o envio do áudio). */
    ENDED,
}

/**
 * Máquina de estados pura (sem dependência do Android) para poder ser testada
 * em JVM.
 */
class CallStateMachine(val recordTimeoutMs: Long = DEFAULT_RECORD_TIMEOUT_MS) {

    var phase: CallPhase = CallPhase.IDLE
        private set

    /** true quando a chamada já foi encerrada (evita envio duplicado). */
    val ended: Boolean get() = phase == CallPhase.ENDED

    fun onIncoming(): Boolean = transition(CallPhase.IDLE, CallPhase.INCOMING)

    fun onAnswer(): Boolean = transition(CallPhase.INCOMING, CallPhase.ANSWERED)

    fun onPlaybackStarted(): Boolean = transition(CallPhase.ANSWERED, CallPhase.PLAYING)

    /** Vale tanto para o fim normal da reprodução quanto para erro na Preparação. */
    fun onPlaybackFinished(): Boolean {
        if (phase != CallPhase.ANSWERED && phase != CallPhase.PLAYING) return false
        phase = CallPhase.RECORDING
        return true
    }

    fun onRecordStarted(): Boolean = transition(CallPhase.RECORDING, CallPhase.RECORDING)

    fun onHangup(): Boolean {
        if (ended) return false
        phase = CallPhase.ENDED
        return true
    }

    /** Tempo já decorrido de gravação que dispara o timeout de segurança. */
    fun shouldTimeout(elapsedMs: Long): Boolean = elapsedMs >= recordTimeoutMs

    private fun transition(from: CallPhase, to: CallPhase): Boolean {
        if (phase != from) return false
        phase = to
        return true
    }

    companion object {
        const val DEFAULT_RECORD_TIMEOUT_MS = 90_000L
    }
}
