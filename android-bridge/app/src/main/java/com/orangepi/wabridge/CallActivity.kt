package com.orangepi.wabridge

import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView

/**
 * The app's own call screen.
 *
 * Watches do not always have the system dialer to show incoming calls;
 * this screen ensures there is always a way to answer or reject. If the
 * system also shows the call, both screens coexist — answering from either
 * leads to the same session.
 */
class CallActivity : Activity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    private lateinit var callerName: TextView
    private lateinit var callerNumber: TextView
    private lateinit var statusLine: TextView
    private lateinit var answerButton: Button
    private lateinit var rejectButton: Button
    private lateinit var speedButton: Button
    private var localSession: VoiceSession? = null
    private var answered = false
    private val handler = Handler(Looper.getMainLooper())
    /** Toque da chamada entrante (para ao atender ou desligar). */
    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        active = this
        setContentView(R.layout.activity_call)

        // Call screen: wake the watch and show over the lock screen.
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
        // Voice note sender name, number, and duration.
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
                    // Hanging up before the minimum recording time shows a warning.
                    statusLine.visibility = android.view.View.VISIBLE
                    statusLine.setText(R.string.status_too_short)
                }
            } else {
                answered = true
                stopRing()
                answerWhenReady()
                // After answering, the button becomes "Hang up".
                answerButton.setText(R.string.hangup)
                rejectButton.visibility = android.view.View.GONE
            }
        }
        rejectButton.setOnClickListener {
            CallPayload.fromJson(intent.getStringExtra(EXTRA_PAYLOAD))?.let {
                Prefs.addCallHistory(this, it, CallHistoryOutcome.REJECTED)
            }
            stopRing()
            rejectWhenReady()
            finish()
        }

        // Speed button: only relevant while audio is playing.
        speedButton.setOnClickListener {
            val session = VoiceSession.current ?: return@setOnClickListener
            val next = session.setSpeed(PlaybackSpeed.next(session.speed))
            speedButton.text = getString(R.string.speed_value, PlaybackSpeed.label(next))
        }

        // Contact initial in the avatar.
        val nome = payload?.senderName.orEmpty()
        val inicial = nome.trim().take(1).uppercase().ifBlank { "?" }
        findViewById<TextView>(R.id.callerInitial)?.text = inicial

        // The voice session requests microphone access only when recording starts.

        // Status line: playing / recording / elapsed time.
        handler.post(statusTicker)
    }

    /** Incoming call ringtone and vibration. */
    private fun startRing() {
        ringtone = runCatching {
            RingtoneManager.getRingtone(
                this,
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
            )
        }.getOrNull()?.also { rt ->
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                rt.audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            }
            if (!rt.isPlaying) rt.play()
        }

        val vib = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        vibrator = vib
        if (vib?.hasVibrator() == true) {
            val padrao = longArrayOf(0, 400, 300)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                vib.vibrate(VibrationEffect.createWaveform(padrao, 0))
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(padrao, 0)
            }
        }
    }

    private fun stopRing() {
        ringtone?.let { if (it.isPlaying) it.stop() }
        ringtone = null
        vibrator?.cancel()
        vibrator = null
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
     * Status line: shows that recording is in progress (and for how long).
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
                speedButton.text = getString(R.string.speed_value, PlaybackSpeed.label(session.speed))
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
     * Telecom creates the connection asynchronously, so it may not yet
     * exist when the user taps Answer. Wait briefly; if Telecom is unavailable
     * (device without telephony), the app screen itself handles the voice session.
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

    /** Voice session outside Telecom (device without telephony). */
    private fun localSession(): VoiceSession? {
        localSession?.let { return it }
        val payload = CallPayload.fromJson(intent.getStringExtra(EXTRA_PAYLOAD)) ?: return null
        return VoiceSession(
            this,
            payload,
            onEnded = {
                Prefs.addCallHistory(this, payload, CallHistoryOutcome.ANSWERED)
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
        stopRing()
        super.onDestroy()
        if (active === this) active = null
        // If the Activity exits without a decision, the call continues (Telecom
        // handles the timeout); only dismiss the notification.
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
