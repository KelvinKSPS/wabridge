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
                if (DaemonManager.start(this)) {
                    runOnUiThread { refreshEngineStatus() }
                }
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
        updateAllowedSummary()
        setupAllowedPicker()
        setupBluetooth()
        setupPending()
    }

    /**
     * Fila de áudios acumulados: o daemon guarda em /pending e o app
     * mostra na tela para ouvir e responder depois.
     */
    private fun setupPending() {
        findViewById<Button>(R.id.pendingButton).setOnClickListener {
            Thread {
                val lista = runCatching {
                    val conn = URL(Prefs.engineUrl(this) + "/pending")
                        .openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 15_000
                    conn.readTimeout = 20_000
                    val body = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
                        .use { it.readText() }
                    conn.disconnect()
                    val arr = org.json.JSONArray(body)
                    (0 until arr.length()).mapNotNull { i ->
                        val o = arr.optJSONObject(i) ?: return@mapNotNull null
                        Triple(
                            o.optString("audioId"),
                            o.optString("senderNumber"),
                            o.optString("senderName"),
                        )
                    }
                }.getOrElse { null }

                runOnUiThread {
                    if (lista == null) {
                        android.widget.Toast.makeText(
                            this, R.string.pending_error,
                            android.widget.Toast.LENGTH_LONG,
                        ).show()
                    } else if (lista.isEmpty()) {
                        android.widget.Toast.makeText(
                            this, R.string.pending_none,
                            android.widget.Toast.LENGTH_LONG,
                        ).show()
                    } else {
                        showPendingDialog(lista)
                    }
                }
            }.start()
        }
    }

    /** Lista as notas acumuladas; ao tocar, abre a tela de chamada. */
    private fun showPendingDialog(lista: List<Triple<String, String, String>>) {
        val titulos = lista.map { (_, numero, nome) ->
            if (nome.isBlank()) numero else getString(R.string.pending_from, nome, numero)
        }.toTypedArray()
        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.pending_title)
            .setItems(titulos) { _, which ->
                val (id, numero, nome) = lista[which]
                val payload = CallPayload(
                    audioId = id,
                    senderName = nome.ifBlank { numero },
                    senderNumber = numero,
                    audioUrl = Prefs.engineUrl(this) + "/media/$id.ogg",
                )
                startActivity(CallActivity.intent(this, payload))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Seletor de relógio/fone: só toca se o aparelho escolhido estiver conectado. */
    private fun setupBluetooth() {
        val check = findViewById<android.widget.CheckBox>(R.id.btOnlyCheck)
        val spinner = findViewById<android.widget.Spinner>(R.id.btDeviceSpinner)
        val state = findViewById<TextView>(R.id.btState)

        check.isChecked = Prefs.btOnlyConnected(this)

        val devices = BluetoothHelper.paired(this)
        val nomes = if (devices.isEmpty()) {
            listOf(getString(R.string.bt_none))
        } else {
            listOf(getString(R.string.bt_pick)) + devices.map { it.name }
        }
        spinner.adapter = android.widget.ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, nomes,
        )

        val salvo = Prefs.btDevice(this)
        devices.indexOfFirst { it.address == salvo }.takeIf { it > 0 }?.let {
            spinner.setSelection(it + 1)
        }

        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                Prefs.setBtDevice(this@MainActivity, devices.getOrNull(pos - 1)?.address ?: "")
                updateBtState()
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) = Unit
        }
        check.setOnCheckedChangeListener { _, v -> Prefs.setBtOnlyConnected(this, v) }

        updateBtState()
    }

    /** Mostra se o aparelho escolhido está conectado agora. */
    private fun updateBtState() {
        val state = findViewById<TextView>(R.id.btState)
        val addr = Prefs.btDevice(this)
        if (addr.isBlank()) {
            state.text = getString(R.string.bt_none)
            return
        }
        val ok = BluetoothHelper.isConnected(this, addr)
        state.text = getString(if (ok) R.string.bt_connected else R.string.bt_disconnected)
    }

    /** Atualiza o resumo ao lado do botão "escolher". */
    private fun updateAllowedSummary() {
        val total = Prefs.allowedNumbers(this).size
        findViewById<TextView>(R.id.allowedSummary).text =
            if (total == 0) getString(R.string.allowed_empty)
            else getString(R.string.allowed_selected, total)
    }

    /**
     * Lista interativa: abre um seletor com os contatos e grupos da conta.
     * Só existe no modo embutido (o servidor Node não tem /contacts).
     */
    private fun setupAllowedPicker() {
        val button = findViewById<Button>(R.id.allowedPickButton)
        button.setOnClickListener {
            if (Prefs.mode(this) != Prefs.Engine.EMBUTIDO) {
                android.widget.Toast.makeText(
                    this, R.string.allowed_only_embedded,
                    android.widget.Toast.LENGTH_LONG,
                ).show()
                return@setOnClickListener
            }
            button.isEnabled = false
            findViewById<TextView>(R.id.allowedSummary).text = getString(R.string.allowed_loading)
            Thread {
                val loaded = PairingClient.contacts(this)
                runOnUiThread {
                    button.isEnabled = true
                    if (loaded == null) {
                        updateAllowedSummary()
                        android.widget.Toast.makeText(
                            this, R.string.allowed_error,
                            android.widget.Toast.LENGTH_LONG,
                        ).show()
                        return@runOnUiThread
                    }
                    showAllowedDialog(loaded.first, loaded.second)
                }
            }.start()
        }
    }

    /** Diálogo com abas de contatos e grupos, cada item com checkbox. */
    private fun showAllowedDialog(
        contatos: List<ContactItem>,
        grupos: List<ContactItem>,
    ) {
        if (contatos.isEmpty() && grupos.isEmpty()) {
            updateAllowedSummary()
            android.widget.Toast.makeText(
                this, R.string.allowed_none, android.widget.Toast.LENGTH_LONG,
            ).show()
            return
        }

        val atual = Prefs.allowedNumbers(this).toMutableSet()
        val itens = mutableListOf<ContactItem>()
        if (contatos.isNotEmpty()) {
            itens += ContactItem("— contatos —", "")
            itens += contatos
        }
        if (grupos.isNotEmpty()) {
            itens += ContactItem("— grupos —", "")
            itens += grupos
        }

        val marcados = BooleanArray(itens.size)
        itens.forEachIndexed { i, item -> marcados[i] = item.value.isNotEmpty() && item.value in atual }

        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.allowed_title)
            .setMultiChoiceItems(
                itens.map { if (it.value.isEmpty()) it.name else "${it.name} (${it.value})" }
                    .toTypedArray(),
                marcados,
            ) { _, which, checked ->
                val valor = itens[which].value
                if (valor.isNotEmpty()) {
                    if (checked) atual += valor else atual -= valor
                }
            }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val lista = atual.filter { it.isNotBlank() }
                Prefs.setAllowedNumbers(this, lista.joinToString(", "))
                allowedInput.setText(lista.joinToString(", "))
                updateAllowedSummary()
            }
            .setNeutralButton(android.R.string.cancel, null)
            .show()
        updateAllowedSummary()
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
