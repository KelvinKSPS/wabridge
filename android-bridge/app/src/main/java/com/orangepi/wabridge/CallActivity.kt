package com.orangepi.wabridge

import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView

/**
 * Tela de chamada do próprio app.
 *
 * Relógios nem sempre têm o discador do sistema para mostrar a chamada
 * entrante; esta tela garante que sempre exista um jeito de atender e
 * recusar. Se o sistema também mostrar a chamada, as duas telas
 * coexistem — atender por qualquer uma das duas dá no mesmo lugar.
 */
class CallActivity : Activity() {

    private lateinit var callerName: TextView
    private lateinit var callerNumber: TextView
    private lateinit var statusLine: TextView
    private lateinit var answerButton: Button
    private lateinit var rejectButton: Button
    private lateinit var speedButton: Button
    private var localSession: VoiceSession? = null
    private var answered = false
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        active = this
        setContentView(R.layout.activity_call)

        // tela de chamada: acorda o relógio e fica por cima da tela de bloqueio
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }

        val payload = CallPayload.fromJson(intent.getStringExtra(EXTRA_PAYLOAD))
        callerName = findViewById(R.id.callerName)
        callerNumber = findViewById(R.id.callerNumber)
        statusLine = findViewById(R.id.statusLine)
        answerButton = findViewById(R.id.answerButton)
        rejectButton = findViewById(R.id.rejectButton)
        speedButton = findViewById(R.id.speedButton)

        callerName.text = payload?.senderName ?: getString(R.string.unknown_caller)
        // nome, número e duração da nota de voz
        callerNumber.text = if (payload != null && payload.durationSec > 0) {
            "${payload.senderNumber} · ${CallPayload.formatDuration(payload.durationSec)}"
        } else {
            payload?.senderNumber ?: ""
        }

        answerButton.setOnClickListener {
            if (answered) {
                if (hangupNow()) {
                    finish()
                } else {
                    // desligar antes da gravação mínima: mostra o aviso
                    statusLine.visibility = android.view.View.VISIBLE
                    statusLine.setText(R.string.status_too_short)
                }
            } else {
                answered = true
                answerWhenReady()
                // depois de atender, o botão vira "Desligar"
                answerButton.setText(R.string.hangup)
                rejectButton.visibility = android.view.View.GONE
            }
        }
        rejectButton.setOnClickListener {
            rejectWhenReady()
            finish()
        }

        // botão de velocidade: só faz sentido enquanto o áudio toca
        speedButton.setOnClickListener {
            val session = VoiceSession.current ?: return@setOnClickListener
            val next = when (session.speed) {
                1.0f -> 1.25f
                1.25f -> 1.5f
                1.5f -> 2.0f
                else -> 1.0f
            }
            session.setSpeed(next)
            speedButton.text = getString(R.string.speed_value, "${next}x")
        }

        // A tela de chamada garante que o app está em primeiro plano: é o
        // momento seguro para pedir o tipo "microphone" do foreground service.
        // Sem isso, quando a chamada é atendida pelo discador do sistema (o
        // app fecha a própria tela e vai para background), o Android silencia
        // o microfone e a resposta sai vazia.
        BridgeForegroundService.requestMicrophone(this)

        // linha de estado: tocando / gravando / há quanto tempo
        handler.post(statusTicker)
    }

    private fun hangupNow(): Boolean {
        val call = WatchCallConnection.current
        if (call != null) {
            return call.rejectNow()
        }
        val session = localSession
        if (session != null) {
            session.hangup()
            return session.ended
        }
        return true
    }

    /* ---------------------------------------------------------------- *
     * Linha de estado: mostra que está gravando (e há quanto tempo)
     * ---------------------------------------------------------------- */

    private val statusTicker = object : Runnable {
        override fun run() {
            updateStatus()
            handler.postDelayed(this, 500)
        }
    }

    private fun updateStatus() {
        val session = VoiceSession.current ?: return
        when (session.phase) {
            CallPhase.PLAYING -> {
                statusLine.visibility = android.view.View.VISIBLE
                statusLine.setText(R.string.status_playing)
                speedButton.visibility = android.view.View.VISIBLE
                speedButton.text = getString(R.string.speed_value, "${session.speed}x")
            }
            CallPhase.RECORDING -> {
                speedButton.visibility = android.view.View.GONE
                val elapsed = session.recordElapsedMs
                statusLine.visibility = android.view.View.VISIBLE
                if (elapsed < CallStateMachine.MIN_RECORDING_MS) {
                    val wait = ((CallStateMachine.MIN_RECORDING_MS - elapsed + 999) / 1000).toInt()
                    statusLine.setText(getString(R.string.status_wait, wait))
                } else {
                    statusLine.setText(getString(R.string.status_recorded, (elapsed / 1000).toInt()))
                }
            }
            else -> statusLine.visibility = android.view.View.GONE
        }
    }

    /**
     * A conexão é criada pelo Telecom de forma assíncrona: pode ainda não
     * existir quando o usuário toca em Atender. Espera um instante; se o
     * Telecom não estiver disponível (aparelho sem telephony), a própria tela
     * do app conduz a sessão de voz.
     */
    private fun answerWhenReady() {
        val handler = Handler(Looper.getMainLooper())
        var attempts = 0
        val tryAnswer = object : Runnable {
            override fun run() {
                val call = WatchCallConnection.current
                if (call != null) {
                    call.answerNow()
                } else if (attempts++ < 20) {
                    handler.postDelayed(this, 100)
                } else {
                    localSession()?.answer()
                }
            }
        }
        handler.post(tryAnswer)
    }

    private fun rejectWhenReady() {
        val handler = Handler(Looper.getMainLooper())
        var attempts = 0
        val tryReject = object : Runnable {
            override fun run() {
                val call = WatchCallConnection.current
                if (call != null) {
                    call.rejectNow()
                } else if (attempts++ < 20) {
                    handler.postDelayed(this, 100)
                } else {
                    localSession()?.hangup()
                }
            }
        }
        handler.post(tryReject)
    }

    /** Sessão de voz fora do Telecom (aparelho sem telephony). */
    private fun localSession(): VoiceSession? {
        localSession?.let { return it }
        val payload = CallPayload.fromJson(intent.getStringExtra(EXTRA_PAYLOAD)) ?: return null
        return VoiceSession(
            this,
            payload,
            onEnded = {
                runOnUiThread {
                    BridgeForegroundService.notifyCallEnded(this)
                    finish()
                }
            },
            onTooShort = {
                runOnUiThread {
                    statusLine.visibility = android.view.View.VISIBLE
                    statusLine.setText(R.string.status_too_short)
                }
            },
        ).also { localSession = it }
    }

    override fun onDestroy() {
        handler.removeCallbacks(statusTicker)
        super.onDestroy()
        if (active === this) active = null
        // se a activity sair sem decisão, a chamada continua (o Telecom
        // cuida do tempo limite); só escondemos a notificação.
        dismissNotification()
    }

    private fun dismissNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        manager.cancel(CallNotifier.NOTIFICATION_ID)
    }

    companion object {
        const val EXTRA_PAYLOAD = "com.orangepi.wabridge.CALL_PAYLOAD"

        /** Tela de chamada aberta no momento (usada para fechar ao atender). */
        @Volatile
        var active: CallActivity? = null
            private set

        fun intent(context: Context, payload: CallPayload): Intent =
            Intent(context, CallActivity::class.java)
                .putExtra(EXTRA_PAYLOAD, payload.toJson())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
}
