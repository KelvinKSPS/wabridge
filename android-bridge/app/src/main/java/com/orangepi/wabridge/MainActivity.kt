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
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URL

class MainActivity : Activity() {

    private lateinit var urlInput: EditText
    private lateinit var statusText: TextView
    private lateinit var speedSpinner: android.widget.AutoCompleteTextView
    private lateinit var modeSpinner: android.widget.AutoCompleteTextView
    private lateinit var engineStatusText: TextView
    private lateinit var countryCodeInput: EditText
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
        countryCodeInput = findViewById(R.id.countryCodeInput)
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
        val speedLabels = speedOptions.map { PlaybackSpeed.label(it) }
        speedSpinner.setAdapter(
            android.widget.ArrayAdapter(this, android.R.layout.simple_list_item_1, speedLabels),
        )
        val posAtual = speedOptions.indexOfFirst { it == current }.coerceAtLeast(0)
        speedSpinner.setText(speedLabels[posAtual], false)
        speedSpinner.setOnItemClickListener { _, _, pos, _ ->
            Prefs.setSpeed(this@MainActivity, speedOptions.getOrElse(pos) { 1.0f })
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
        // ao voltar para o app, releitura: a permissão de bluetooth pode
        // ter sido dada agora, e o serviço pode ter caído
        if (this::allowedInput.isInitialized) {
            updateAllowedSummary()
            setupBluetooth()
            ensureBridgeRunning()
        }
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
        val labels = modes.map { if (it == Prefs.Engine.EMBUTIDO) getString(R.string.mode_embedded_short) else getString(R.string.mode_external) }
        modeSpinner.setAdapter(
            android.widget.ArrayAdapter(this, android.R.layout.simple_list_item_1, labels),
        )
        val pos = modes.indexOf(Prefs.mode(this)).coerceAtLeast(0)
        modeSpinner.setText(labels[pos], false)
        modeSpinner.setOnItemClickListener { _, _, item, _ ->
            Prefs.setMode(this@MainActivity, modes.getOrElse(item) { Prefs.Engine.EMBUTIDO })
            val embutido = Prefs.mode(this@MainActivity) == Prefs.Engine.EMBUTIDO
            urlInput.isEnabled = !embutido
            refreshEngineStatus()
        }
        urlInput.isEnabled = Prefs.mode(this) != Prefs.Engine.EMBUTIDO
    }

    /** Gera o código de 8 dígitos para parear sem QR. */
    private fun setupPairing() {
        countryCodeInput.setText(Prefs.countryCode(this))
        phoneInput.setText(Prefs.phone(this))
        pairButton.setOnClickListener {
            val ddi = countryCodeInput.text.toString().filter { it.isDigit() }
            val numero = phoneInput.text.toString().filter { it.isDigit() }
            val phone = ddi + numero
            if (numero.length < 8 || ddi.isEmpty()) {
                pairResultText.visibility = android.view.View.VISIBLE
                pairResultText.text = getString(R.string.phone_required)
                return@setOnClickListener
            }
            Prefs.setCountryCode(this, ddi)
            Prefs.setPhone(this, numero)
            pairButton.isEnabled = false
            pairResultText.text = getString(R.string.pairing)
            Thread {
                // o daemon tem que estar no ar antes do pedido
                if (Prefs.mode(this@MainActivity) == Prefs.Engine.EMBUTIDO &&
                    !DaemonManager.running && !DaemonManager.start(this@MainActivity)
                ) {
                    runOnUiThread {
                        pairButton.isEnabled = true
                        pairResultText.visibility = android.view.View.VISIBLE
                        pairResultText.text = getString(R.string.daemon_missing)
                    }
                    return@Thread
                }
                val code = PairingClient.requestCode(this, phone)
                runOnUiThread {
                    pairButton.isEnabled = true
                    pairResultText.visibility = android.view.View.VISIBLE
                    pairResultText.text = code ?: getString(R.string.pair_failed)
                }
            }.start()
        }
        allowedInput.setText(Prefs.allowedNumbers(this).joinToString(", "))
        updateAllowedSummary()
        setupAllowedPicker()
        setupBluetooth()
        setupAcceptOwn()
        setupPending()
    }

    /** Checkbox para tocar os próprios áudios (modo de teste). */
    private fun setupAcceptOwn() {
        val check = findViewById<android.widget.CheckBox>(R.id.acceptOwnCheck)
        check.isChecked = Prefs.acceptOwn(this)
        check.setOnCheckedChangeListener { _, v ->
            Prefs.setAcceptOwn(this, v)
            // reinicia o daemon para ler a nova env var
            if (Prefs.mode(this) == Prefs.Engine.EMBUTIDO) {
                DaemonManager.stop()
                DaemonManager.start(this)
            }
        }
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
        val spinner = findViewById<android.widget.AutoCompleteTextView>(R.id.btDeviceSpinner)

        check.isChecked = Prefs.btOnlyConnected(this)

        // sem a permissão (Android 12+) a lista vem vazia; pede na hora
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1002)
        }

        val devices = BluetoothHelper.paired(this)
        val nomes = if (devices.isEmpty()) {
            listOf(getString(R.string.bt_none))
        } else {
            listOf(getString(R.string.bt_pick)) + devices.map { it.name }
        }
        spinner.setAdapter(
            android.widget.ArrayAdapter(this, android.R.layout.simple_list_item_1, nomes),
        )

        val salvo = Prefs.btDevice(this)
        val posSalvo = devices.indexOfFirst { it.address == salvo }
        if (posSalvo >= 0) {
            spinner.setText(nomes[posSalvo + 1], false)
        }

        spinner.setOnItemClickListener { _, _, pos, _ ->
            Prefs.setBtDevice(this@MainActivity, devices.getOrNull(pos - 1)?.address ?: "")
            updateBtState()
        }
        check.setOnCheckedChangeListener { _, v -> Prefs.setBtOnlyConnected(this, v) }

        updateBtState()
    }

    /** Mostra se o aparelho escolhido está conectado agora. */
    private fun updateBtState() {
        val view = findViewById<TextView>(R.id.btState) ?: return
        val addr = Prefs.btDevice(this)
        if (addr.isBlank()) {
            view.text = getString(R.string.bt_none)
            return
        }
        val ok = BluetoothHelper.isConnected(this, addr)
        view.text = getString(if (ok) R.string.bt_connected else R.string.bt_disconnected)
        view.removeCallbacks(btTicker)
        view.postDelayed(btTicker, 3000)
    }

    /** Rele o estado do bluetooth de tempos em tempos. */
    private val btTicker = Runnable { updateBtState() }

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

    /** Diálogo com busca e multi-escolha (contatos e grupos). */
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

        // fonte da verdade: valores selecionados (atualizado em cada click)
        val selecionados = Prefs.allowedNumbers(this).toMutableSet()

        val itens = mutableListOf<ContactItem>()
        if (contatos.isNotEmpty()) {
            itens += ContactItem(getString(R.string.allowed_tab_contacts), "")
            itens += contatos
        }
        if (grupos.isNotEmpty()) {
            itens += ContactItem(getString(R.string.allowed_tab_groups), "")
            itens += grupos
        }

        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
        }
        val busca = android.widget.EditText(this).apply {
            hint = getString(R.string.allowed_search)
            setSingleLine()
        }
        val lista = android.widget.ListView(this).apply {
            choiceMode = android.widget.ListView.CHOICE_MODE_MULTIPLE
        }
        container.addView(busca)
        container.addView(lista)

        // índices (em itens) dos itens visíveis no momento
        var visiveis = itens.indices.toList()

        fun rebuild(query: String) {
            visiveis = itens.indices.filter { i ->
                val item = itens[i]
                if (item.value.isEmpty()) {
                    val grupo = if (item.name == getString(R.string.allowed_tab_contacts)) contatos else grupos
                    grupo.any { combina(it, query) }
                } else {
                    combina(item, query)
                }
            }
            lista.adapter = android.widget.ArrayAdapter(
                this, android.R.layout.simple_list_item_multiple_choice,
                visiveis.map { i ->
                    val item = itens[i]
                    if (item.value.isEmpty()) item.name else "${item.name} (${item.value})"
                },
            )
            visiveis.forEachIndexed { pos, i ->
                lista.setItemChecked(pos, itens[i].value.isNotEmpty() && itens[i].value in selecionados)
            }
        }

        // clique no item → togla em selecionados e sincroniza o check
        lista.setOnItemClickListener { _, _, pos, _ ->
            val item = itens.getOrNull(visiveis.getOrNull(pos) ?: return@setOnItemClickListener) ?: return@setOnItemClickListener
            if (item.value.isEmpty()) return@setOnItemClickListener // cabeçalho
            if (item.value in selecionados) selecionados.remove(item.value) else selecionados.add(item.value)
            lista.setItemChecked(pos, item.value in selecionados)
        }

        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle(R.string.allowed_title)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val listaSalva = selecionados.filter { it.isNotBlank() }.sorted()
                Prefs.setAllowedNumbers(this, listaSalva.joinToString(", "))
                allowedInput.setText(listaSalva.joinToString(", "))
                updateAllowedSummary()
            }
            .setNeutralButton(android.R.string.cancel, null)
            .create()

        busca.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                rebuild(s?.toString()?.trim()?.lowercase().orEmpty())
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
        })

        rebuild("")
        dialog.show()
    }

    private fun combina(item: ContactItem, q: String): Boolean {
        if (q.isEmpty()) return true
        return item.name.lowercase().contains(q) || item.value.contains(q)
    }

    /**
     * Garante que o serviço está no ar.
     *
     * O Android derruba o serviço de vez em quando; ao abrir o app a gente
     * confere e sobe de novo, em vez de deixar o usuário sem o relógio.
     */
    private fun ensureBridgeRunning() {
        if (Prefs.mode(this) != Prefs.Engine.EMBUTIDO && urlInput.text.isNotBlank()) {
            ContextCompat.startForegroundService(this, Intent(this, BridgeForegroundService::class.java))
        }
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
