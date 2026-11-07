package com.orangepi.wabridge

import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
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
    private lateinit var answerButton: Button
    private lateinit var rejectButton: Button

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
        answerButton = findViewById(R.id.answerButton)
        rejectButton = findViewById(R.id.rejectButton)

        callerName.text = payload?.senderName ?: getString(R.string.unknown_caller)
        callerNumber.text = payload?.senderNumber ?: ""

        answerButton.setOnClickListener {
            WatchCallConnection.current?.answerNow()
            finish()
        }
        rejectButton.setOnClickListener {
            WatchCallConnection.current?.rejectNow()
            finish()
        }
    }

    override fun onDestroy() {
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
