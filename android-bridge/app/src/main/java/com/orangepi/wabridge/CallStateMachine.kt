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

    /**
     * Atender uma sessão que não passou pelo fluxo de chamada do Telecom
     * (tela do app em aparelho sem telephony): o "chamada entrando" é implícito.
     */
    fun onSessionAnswer(): Boolean {
        onIncoming()
        return onAnswer()
    }

    fun onPlaybackStarted(): Boolean = transition(CallPhase.ANSWERED, CallPhase.PLAYING)

    /** Vale tanto para o fim normal da reprodução quanto para erro na Preparação. */
    fun onPlaybackFinished(): Boolean {
        if (phase != CallPhase.ANSWERED && phase != CallPhase.PLAYING) return false
        phase = CallPhase.RECORDING
        return true
    }

    fun onRecordStarted(): Boolean = transition(CallPhase.RECORDING, CallPhase.RECORDING)

    /**
     * Desligar.
     *
     * Durante a gravação só aceita desligar depois de [MIN_RECORDING_MS]: o
     * usuário desligava logo depois do bipe e mandava um áudio mudo (0,5 s de
     * silêncio) para o contato.
     */
    fun onHangup(recordElapsedMs: Long = Long.MAX_VALUE): Boolean {
        if (ended) return false
        if (phase == CallPhase.RECORDING && recordElapsedMs < MIN_RECORDING_MS) return false
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

        /** Gravação mínima antes de permitir desligar (evita resposta muda). */
        const val MIN_RECORDING_MS = 500L
    }
}
