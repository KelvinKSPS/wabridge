package com.orangepi.wabridge

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.core.content.ContextCompat

class MainActivity : Activity() {

    private lateinit var urlInput: EditText
    private lateinit var statusText: TextView
    private lateinit var speedSpinner: android.widget.Spinner
    private lateinit var modeSpinner: android.widget.Spinner
    private lateinit var engineStatusText: TextView
    private lateinit var phoneInput: EditText
    private lateinit var pairButton: Button
    private lateinit var pairResultText: TextView
    private lateinit var allowedInput: EditText

    /** Velocidades oferecidas no menu (mesma ordem do array de recursos). */
    private val speedOptions = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            statusText.text = intent?.getStringExtra(BridgeForegroundService.EXTRA_TEXT) ?: ""
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        urlInput = findViewById(R.id.urlInput)
        statusText = findViewById(R.id.statusText)
        speedSpinner = findViewById(R.id.speedSpinner)
        modeSpinner = findViewById(R.id.modeSpinner)
        engineStatusText = findViewById(R.id.engineStatusText)
        phoneInput = findViewById(R.id.phoneInput)
        pairButton = findViewById(R.id.pairButton)
        pairResultText = findViewById(R.id.pairResultText)
        allowedInput = findViewById(R.id.allowedInput)
        urlInput.setText(Prefs.url(this))

        setupModeSpinner()
        setupPairing()
        setupBattery()
        refreshEngineStatus()

        // No modo embutido o daemon precisa estar no ar antes de qualquer
        // coisa (gerar código, escanear QR, receber nota de voz).
        if (Prefs.mode(this) == Prefs.Engine.EMBUTIDO && !DaemonManager.running) {
            Thread {
                if (DaemonManager.start(this)) refreshEngineStatus()
            }.start()
        }

        // velocidade padrão de reprodução
        val current = Prefs.speed(this)
        speedSpinner.setSelection(speedOptions.indexOfFirst { it == current }.coerceAtLeast(0))
        speedSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, pos: Int, id: Long) {
                Prefs.setSpeed(this@MainActivity, speedOptions.getOrElse(pos) { 1.0f })
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }

        findViewById<Button>(R.id.connectButton).setOnClickListener {
            Prefs.setUrl(this, urlInput.text.toString())
            Prefs.setAllowedNumbers(this, allowedInput.text.toString())
            requestMissingPermissions()
            startBridge()
            refreshEngineStatus()
        }

        findViewById<Button>(R.id.stopButton).setOnClickListener {
            stopService(Intent(this, BridgeForegroundService::class.java))
            statusText.text = getString(R.string.stopped)
        }

        findViewById<Button>(R.id.testCallButton).setOnClickListener {
            Prefs.setUrl(this, urlInput.text.toString())
            requestMissingPermissions()
            ContextCompat.startForegroundService(
                this,
                Intent(this, BridgeForegroundService::class.java)
                    .setAction(BridgeForegroundService.ACTION_TEST_CALL),
            )
            statusText.text = getString(R.string.test_caller)
        }
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this,
            statusReceiver,
            IntentFilter(BridgeForegroundService.ACTION_STATUS),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onStop() {
        super.onStop()
        runCatching { unregisterReceiver(statusReceiver) }
    }

    private fun startBridge() {
        ContextCompat.startForegroundService(
            this,
            Intent(this, BridgeForegroundService::class.java),
        )
        statusText.text = getString(R.string.starting)
    }

    /** Seletor do motor: embutido (daemon no APK) ou servidor externo. */
    private fun setupModeSpinner() {
        val modes = listOf(Prefs.Engine.EMBUTIDO, Prefs.Engine.EXTERNO)
        modeSpinner.setSelection(modes.indexOf(Prefs.mode(this)).coerceAtLeast(0))
        modeSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, pos: Int, id: Long) {
                Prefs.setMode(this@MainActivity, modes.getOrElse(pos) { Prefs.Engine.EMBUTIDO })
                val embutido = Prefs.mode(this@MainActivity) == Prefs.Engine.EMBUTIDO
                urlInput.isEnabled = !embutido
                refreshEngineStatus()
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
        urlInput.isEnabled = Prefs.mode(this) != Prefs.Engine.EMBUTIDO
    }

    /** Gera o código de 8 dígitos para parear sem QR. */
    private fun setupPairing() {
        phoneInput.setText(Prefs.phone(this))
        pairButton.setOnClickListener {
            val phone = phoneInput.text.toString().trim()
            if (phone.isEmpty()) {
                pairResultText.text = getString(R.string.phone_required)
                return@setOnClickListener
            }
            Prefs.setPhone(this, phone)
            pairButton.isEnabled = false
            pairResultText.text = getString(R.string.pairing)
            Thread {
                // o daemon tem que estar no ar antes do pedido
                if (Prefs.mode(this@MainActivity) == Prefs.Engine.EMBUTIDO &&
                    !DaemonManager.running && !DaemonManager.start(this@MainActivity)
                ) {
                    runOnUiThread {
                        pairButton.isEnabled = true
                        pairResultText.text = getString(R.string.daemon_missing)
                    }
                    return@Thread
                }
                val code = PairingClient.requestCode(this, phone)
                runOnUiThread {
                    pairButton.isEnabled = true
                    pairResultText.text = code ?: getString(R.string.pair_failed)
                }
            }.start()
        }
        allowedInput.setText(Prefs.allowedNumbers(this).joinToString(", "))
    }

    /** Pede a isenção da otimização de bateria (o daemon precisa rodar o dia todo). */
    private fun setupBattery() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val pm = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager ?: return
        if (pm.isIgnoringBatteryOptimizations(packageName)) return

        val button = findViewById<Button>(R.id.batteryButton) ?: return
        button.visibility = android.view.View.VISIBLE
        button.setOnClickListener {
            runCatching {
                startActivity(
                    Intent(
                        android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        android.net.Uri.parse("package:$packageName"),
                    ),
                )
            }.onFailure {
                pairResultText.text = getString(R.string.pair_failed)
            }
        }
    }

    /** Mostra o estado do motor (e se o daemon está rodando). */
    private fun refreshEngineStatus() {
        if (Prefs.mode(this) != Prefs.Engine.EMBUTIDO) {
            engineStatusText.text = getString(R.string.mode_external)
            return
        }
        if (!DaemonManager.available(this)) {
            engineStatusText.text = getString(R.string.daemon_missing)
            return
        }
        engineStatusText.text = getString(
            R.string.mode_embedded,
            if (DaemonManager.running) "rodando" else "parado",
        )
    }

    private fun requestMissingPermissions() {
        val wanted = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            wanted += Manifest.permission.BLUETOOTH_CONNECT
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            wanted += Manifest.permission.POST_NOTIFICATIONS
        }
        val missing = wanted.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), 1001)
        }
    }
}
