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

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    private lateinit var urlInput: EditText
    private lateinit var speedSpinner: android.widget.AutoCompleteTextView
    private lateinit var audioDelaySpinner: android.widget.AutoCompleteTextView
    private lateinit var modeSpinner: android.widget.AutoCompleteTextView
    private lateinit var engineStatusText: TextView
    private lateinit var accountStatusText: TextView
    private lateinit var countryCodeInput: EditText
    private lateinit var phoneInput: EditText
    private lateinit var pairButton: Button
    private lateinit var pairResultText: TextView
    private lateinit var allowedInput: EditText
    private lateinit var toggleServiceButton: Button
    private lateinit var serviceStateText: TextView
    private lateinit var languageSpinner: android.widget.AutoCompleteTextView
    private lateinit var languageDescText: TextView
    private lateinit var versionText: TextView
    private lateinit var saveLogButton: Button
    private lateinit var audioDiagnosticsButton: Button
    private var versionTaps = 0
    private var diagnosticFolder: java.io.File? = null
    private var refreshStatusHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // Atualiza o único card de estado no topo.
            if (this@MainActivity::serviceStateText.isInitialized) updateServiceState()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedInstanceState?.getString("diagnosticFolder")?.let { name ->
            diagnosticFolder = ReplyArchive.entries(java.io.File(filesDir, "reply-diagnostics"))
                .firstOrNull { it.name == name }
        }
        setContentView(R.layout.activity_main)

        urlInput = findViewById(R.id.urlInput)
        speedSpinner = findViewById(R.id.speedSpinner)
        audioDelaySpinner = findViewById(R.id.audioDelaySpinner)
        modeSpinner = findViewById(R.id.modeSpinner)
        engineStatusText = findViewById(R.id.engineStatusText)
        accountStatusText = findViewById(R.id.accountStatusText)
        countryCodeInput = findViewById(R.id.countryCodeInput)
        phoneInput = findViewById(R.id.phoneInput)
        pairButton = findViewById(R.id.pairButton)
        pairResultText = findViewById(R.id.pairResultText)
        allowedInput = findViewById(R.id.allowedInput)
        toggleServiceButton = findViewById(R.id.toggleServiceButton)
        serviceStateText = findViewById(R.id.serviceStateText)
        languageSpinner = findViewById(R.id.languageSpinner)
        languageDescText = findViewById(R.id.languageDesc)
        versionText = findViewById(R.id.versionText)
        saveLogButton = findViewById(R.id.saveLogButton)
        audioDiagnosticsButton = findViewById(R.id.audioDiagnosticsButton)
        urlInput.setText(Prefs.url(this))

        setupLanguage()
        setupModeSpinner()
        setupPairing()
        setupBattery()
        setupVersion()
        updateServiceState()
        audioDiagnosticsButton.setOnClickListener { showAudioDiagnostics() }
        saveLogButton.setOnClickListener { saveLog() }

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
        val speedLabels = PlaybackSpeed.menu.map { PlaybackSpeed.label(it) }
        setupDropdown(speedSpinner, speedLabels, PlaybackSpeed.menu.indexOfFirst { it == current }) { pos ->
            Prefs.setSpeed(this@MainActivity, PlaybackSpeed.menu.getOrElse(pos) { 1.0f })
        }

        // delay do áudio até começar a tocar
        val currentDelay = Prefs.audioDelayMs(this)
        val delayLabels = Prefs.AUDIO_DELAY_OPTIONS.map { ms ->
            val secs = ms / 1000.0
            if (secs == secs.toLong().toDouble()) "${secs.toLong()} s" else "$secs s"
        }
        setupDropdown(audioDelaySpinner, delayLabels, Prefs.AUDIO_DELAY_OPTIONS.indexOfFirst { it == currentDelay }) { pos ->
            Prefs.setAudioDelayMs(this@MainActivity, Prefs.AUDIO_DELAY_OPTIONS.getOrElse(pos) { 1000L })
        }

        // Botão único no topo: alterna entre iniciar e parar o serviço.
        toggleServiceButton.setOnClickListener {
            if (BridgeForegroundService.isRunning(this)) {
                stopService(Intent(this, BridgeForegroundService::class.java))
            } else {
                Prefs.setUrl(this, urlInput.text.toString())
                Prefs.setAllowedNumbers(this, allowedInput.text.toString())
                requestMissingPermissions()
                startBridge()
                refreshEngineStatus()
            }
            updateServiceState()
        }

        findViewById<Button>(R.id.testCallButton).setOnClickListener {
            Prefs.setUrl(this, urlInput.text.toString())
            requestMissingPermissions()
            ContextCompat.startForegroundService(
                this,
                Intent(this, BridgeForegroundService::class.java)
                    .setAction(BridgeForegroundService.ACTION_TEST_CALL),
            )
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        diagnosticFolder?.let { outState.putString("diagnosticFolder", it.name) }
        super.onSaveInstanceState(outState)
    }

    private fun showAudioDiagnostics() {
        val entries = ReplyArchive.entries(java.io.File(filesDir, "reply-diagnostics"))
        if (entries.isEmpty()) {
            android.widget.Toast.makeText(this, R.string.audio_diagnostics_empty, android.widget.Toast.LENGTH_LONG).show()
            return
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.audio_diagnostics)
            .setItems(entries.map { it.name }.toTypedArray()) { _, index ->
                diagnosticFolder = entries[index]
                android.app.AlertDialog.Builder(this)
                    .setMessage(R.string.audio_diagnostics_actions)
                    .setPositiveButton(R.string.audio_export) { _, _ ->
                        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "application/zip"
                            putExtra(Intent.EXTRA_TITLE, "${entries[index].name}.zip")
                        }, 2001)
                    }
                    .setNeutralButton(R.string.audio_import) { _, _ ->
                        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "*/*"
                        }, 2002)
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return

        // Salvar log: lê o logcat deste processo e grava no arquivo escolhido.
        if (requestCode == 3001) {
            val uri = data?.data ?: return
            Thread {
                val resultado = runCatching {
                    val proc = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "time"))
                    val log = proc.inputStream.bufferedReader().use { it.readText() }
                    if (log.isBlank()) return@runCatching false
                    contentResolver.openOutputStream(uri)?.use { it.write(log.toByteArray(Charsets.UTF_8)) }
                        ?: error("arquivo indisponível")
                    true
                }
                runOnUiThread {
                    val msg = resultado.fold(
                        onSuccess = { if (it) R.string.save_log_ok else R.string.save_log_empty },
                        onFailure = { R.string.save_log_error },
                    )
                    android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show()
                }
            }.start()
            return
        }

        if (requestCode !in listOf(2001, 2002)) return
        val uri = data?.data ?: return
        val folder = diagnosticFolder ?: return
        Thread {
            runCatching {
                if (requestCode == 2001) {
                    val output = contentResolver.openOutputStream(uri) ?: error("arquivo indisponível")
                    ReplyArchive.export(folder, output)
                    runOnUiThread { toast("Original exportado; ZIP contém voz e destinatário.") }
                } else {
                    val file = java.io.File.createTempFile("converted_", ".ogg", cacheDir)
                    try {
                        val input = contentResolver.openInputStream(uri) ?: error("arquivo indisponível")
                        input.use { source -> file.outputStream().use { target ->
                            val buffer = ByteArray(8192)
                            var total = 0L
                            while (true) {
                                val size = source.read(buffer)
                                if (size < 0) break
                                total += size
                                require(total <= 25L * 1024 * 1024) { "arquivo acima de 25 MB" }
                                target.write(buffer, 0, size)
                            }
                        } }
                        val header = ByteArray(4)
                        file.inputStream().use { require(it.read(header) == 4 && header.contentEquals("OggS".toByteArray())) { "selecione um arquivo OGG" } }
                        val metadata = org.json.JSONObject(java.io.File(folder, "metadata.json").readText())
                        runOnUiThread {
                            android.app.AlertDialog.Builder(this)
                                .setMessage(getString(R.string.audio_confirm, metadata.getString("to"), folder.name))
                                .setPositiveButton("enviar cópia") { _, _ ->
                                    Thread {
                                        try {
                                            check(Prefs.mode(this) == Prefs.Engine.EMBUTIDO) { "selecione o motor embutido antes do teste" }
                                            check(DaemonManager.start(this)) { "motor indisponível" }
                                            android.util.Log.i("AudioDiagnostics", "convertido sha256=${ReplyArchive.sha256(file)} bytes=${file.length()}")
                                            val ok = Uploader.sendReply(DaemonManager.EMBEDDED_URL, "", metadata.getString("to"), file, metadata.getInt("durationSec"))
                                            runOnUiThread { toast(if (ok) "Cópia aceita pelo servidor; aguarde recibo no log." else "Envio falhou; original preservado.") }
                                        } catch (e: Exception) {
                                            runOnUiThread { toast("Diagnóstico: ${e.message}") }
                                        } finally { file.delete() }
                                    }.start()
                                }
                                .setNegativeButton(android.R.string.cancel) { _, _ -> file.delete() }
                                .setOnCancelListener { file.delete() }
                                .show()
                        }
                    } catch (e: Exception) {
                        file.delete()
                        throw e
                    }
                }
            }.onFailure { e -> runOnUiThread { toast("Diagnóstico: ${e.message}") } }
        }.start()
    }

    private fun toast(msg: String) =
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show()

    override fun onStart() {
        super.onStart()
        // ao voltar para o app, releitura: a permissão de bluetooth pode
        // ter sido dada agora, e o serviço pode ter caído
        if (this::allowedInput.isInitialized) {
            updateAllowedSummary()
            setupBluetooth()
            ensureBridgeRunning()
        }
        // Reaplica o idioma (em caso de mudança na tela)
        applyLanguage()
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
    }

    /**
     * Configura um dropdown (ExposedDropdownMenu) que NÃO filtra ao reabrir.
     *
     * O MaterialAutoCompleteTextView, por ser um AutoComplete, filtra a lista
     * pelo texto já escrito no campo. Depois de escolher uma opção ("1.5x"),
     * reabrir mostrava só os itens que casavam com "1.5x" — parecia travado.
     * Usamos um ArrayAdapter com filtro no-op: a lista inteira sempre aparece.
     */
    private fun setupDropdown(
        spinner: android.widget.AutoCompleteTextView,
        labels: List<String>,
        selected: Int,
        onPick: (Int) -> Unit,
    ) {
        val adapter = object : android.widget.ArrayAdapter<String>(
            this, android.R.layout.simple_list_item_1, labels,
        ) {
            private val noopFilter = object : android.widget.Filter() {
                override fun performFiltering(constraint: CharSequence?) =
                    FilterResults().apply { values = labels; count = labels.size }
                override fun publishResults(constraint: CharSequence?, results: FilterResults?) = notifyDataSetChanged()
            }
            override fun getFilter(): android.widget.Filter = noopFilter
        }
        spinner.setAdapter(adapter)
        val pos = selected.coerceAtLeast(0)
        spinner.setText(labels.getOrElse(pos) { "" }, false)
        spinner.setOnItemClickListener { _, _, p, _ -> onPick(p) }
    }

    /** Seletor do motor: embutido (daemon no APK) ou servidor externo. */
    private fun setupModeSpinner() {
        val modes = listOf(Prefs.Engine.EMBUTIDO, Prefs.Engine.EXTERNO)
        val labels = modes.map { if (it == Prefs.Engine.EMBUTIDO) getString(R.string.mode_embedded_short) else getString(R.string.mode_external) }
        setupDropdown(modeSpinner, labels, modes.indexOf(Prefs.mode(this))) { item ->
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
                    // Inicia refresh do status da conta a cada 3s por 3min
                    if (code != null) scheduleStatusRefresh()
                }
            }.start()
        }
        allowedInput.setText(Prefs.allowedNumbers(this).joinToString(", "))
        updateAllowedSummary()
        setupAllowedPicker()
        setupBluetooth()
        setupAcceptOwn()
        setupPending()
        refreshAccountStatus()
    }

    /** Atualiza o status de autenticação da conta. */
    private fun refreshAccountStatus() {
        Thread {
            val status = PairingClient.status(this)
            runOnUiThread {
                accountStatusText.text = when {
                    status == null -> getString(R.string.account_unknown)
                    status.loggedIn -> {
                        val phone = formatPhone(status.phone)
                        if (status.connected) getString(R.string.account_linked, phone)
                        else getString(R.string.account_linked_offline, phone)
                    }
                    else -> getString(R.string.account_not_linked)
                }
            }
        }.start()
    }

    /** Formata número +55 com 12-13 dígitos como +55 DD NNNNN-NNNN. */
    private fun formatPhone(phone: String): String {
        if (!phone.startsWith("55") || phone.length !in 12..13) return "+$phone"
        val parte1 = phone.substring(0, 2)  // 55
        val parte2 = phone.substring(2, 4)  // DD
        val parte3 = phone.substring(4, 9)  // NNNNN
        val parte4 = phone.substring(9)     // NNNN
        return "+$parte1 $parte2 $parte3-$parte4"
    }

    /** Agenda refresh do status a cada 3s por até 3 min. */
    private fun scheduleStatusRefresh() {
        var attempts = 0
        val refresh: Runnable = object : Runnable {
            override fun run() {
                if (!this@MainActivity::accountStatusText.isInitialized || attempts > 60) return
                refreshAccountStatus()
                attempts++
                Thread {
                    val status = PairingClient.status(this@MainActivity)
                    if (status?.loggedIn != true && attempts <= 60) {
                        refreshStatusHandler.postDelayed(this, 3000)
                    }
                }.start()
            }
        }
        refreshStatusHandler.post(refresh)
    }

    /** Checkbox para tocar os próprios áudios (modo de teste). */
    private fun setupAcceptOwn() {
        val check = findViewById<android.widget.CheckBox>(R.id.acceptOwnCheck)
        check.isChecked = Prefs.acceptOwn(this)
        check.setOnCheckedChangeListener { _, v ->
            Prefs.setAcceptOwn(this, v)
            // reinicia o daemon em background (start() bloqueia até 15s)
            if (Prefs.mode(this) == Prefs.Engine.EMBUTIDO) {
                Thread {
                    DaemonManager.stop()
                    DaemonManager.start(this)
                }.start()
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
        val button = findViewById<Button>(R.id.btPickButton)

        check.isChecked = Prefs.btOnlyConnected(this)

        // sem a permissão (Android 12+) a lista vem vazia; pede na hora
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1002)
        }

        val devices = BluetoothHelper.paired(this)
        if (devices.isEmpty()) {
            button.isEnabled = false
            android.widget.Toast.makeText(this, R.string.bt_none, android.widget.Toast.LENGTH_LONG).show()
        } else {
            button.setOnClickListener {
                val saved = Prefs.btDevices(this)
                val checked = BooleanArray(devices.size) { devices[it].address in saved }
                android.app.AlertDialog.Builder(this)
                    .setTitle(R.string.bt_pick)
                    .setMultiChoiceItems(devices.map { it.name }.toTypedArray(), checked) { _, idx, v ->
                        checked[idx] = v
                    }
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        val selected = devices.filterIndexed { i, _ -> checked[i] }.map { it.address }.toSet()
                        Prefs.setBtDevices(this@MainActivity, selected)
                        updateBtState()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }

        check.setOnCheckedChangeListener { _, v -> Prefs.setBtOnlyConnected(this, v) }
        updateBtState()
    }

    /** Mostra estado dos aparelhos escolhidos. */
    private fun updateBtState() {
        val view = findViewById<TextView>(R.id.btState) ?: return
        val addrs = Prefs.btDevices(this)
        if (addrs.isEmpty()) {
            view.text = getString(R.string.bt_any)
            view.removeCallbacks(btTicker)
            view.postDelayed(btTicker, 3000)
            return
        }
        val devices = BluetoothHelper.paired(this)
        val lines = devices.filter { it.address in addrs }.map { dev ->
            val status = if (BluetoothHelper.isConnected(this, dev.address))
                getString(R.string.bt_connected)
            else
                getString(R.string.bt_disconnected)
            "${dev.name} — $status"
        }
        view.text = lines.joinToString("\n")
        view.removeCallbacks(btTicker)
        view.postDelayed(btTicker, 3000)
    }

    /** Atualiza o estado do bluetooth de tempos em tempos. */
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

        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(0, 0, 0, 0)
        }

        // Abas: contatos e grupos
        val tabLayout = com.google.android.material.tabs.TabLayout(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        tabLayout.addTab(tabLayout.newTab().setText(getString(R.string.allowed_tab_contacts_n, contatos.size)))
        tabLayout.addTab(tabLayout.newTab().setText(getString(R.string.allowed_tab_groups_n, grupos.size)))

        // Busca
        val busca = android.widget.EditText(this).apply {
            hint = getString(R.string.allowed_search)
            setSingleLine()
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(16, 8, 16, 8) }
        }

        // ListView com altura fixa (400dp)
        val lista = android.widget.ListView(this).apply {
            choiceMode = android.widget.ListView.CHOICE_MODE_MULTIPLE
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.util.TypedValue.applyDimension(
                    android.util.TypedValue.COMPLEX_UNIT_DIP, 400f, resources.displayMetrics
                ).toInt(),
            )
        }

        container.addView(tabLayout)
        container.addView(busca)
        container.addView(lista)

        // Estado atual da aba
        var abaAtual = 0

        fun rebuild(query: String) {
            val source = if (abaAtual == 0) contatos else grupos
            val filtrados = source.filter { item ->
                if (query.isEmpty()) true else {
                    item.name.lowercase().contains(query) || item.value.contains(query)
                }
            }

            val displayItems = filtrados.map { item ->
                val relativeTime = if (item.lastActivity > 0) {
                    " · " + android.text.format.DateUtils.getRelativeTimeSpanString(item.lastActivity * 1000)
                } else {
                    ""
                }
                "${item.name} (${item.value})$relativeTime"
            }

            if (displayItems.isEmpty()) {
                lista.adapter = android.widget.ArrayAdapter(
                    this@MainActivity,
                    android.R.layout.simple_list_item_multiple_choice,
                    listOf(getString(R.string.allowed_empty_tab)),
                ).apply {
                    // Desabilita o item vazio
                    this.getView(0, null, lista)?.isEnabled = false
                }
                lista.clearChoices()
            } else {
                lista.adapter = android.widget.ArrayAdapter(
                    this@MainActivity,
                    android.R.layout.simple_list_item_multiple_choice,
                    displayItems,
                )
                filtrados.forEachIndexed { pos, item ->
                    lista.setItemChecked(pos, item.value in selecionados)
                }
            }
        }

        // Clique no item → togla em selecionados
        lista.setOnItemClickListener { _, _, pos, _ ->
            val source = if (abaAtual == 0) contatos else grupos
            val filtrados = source.filter { item ->
                if (busca.text.isEmpty()) true else {
                    item.name.lowercase().contains(busca.text.toString().lowercase()) ||
                    item.value.contains(busca.text.toString())
                }
            }
            val item = filtrados.getOrNull(pos) ?: return@setOnItemClickListener
            if (item.value.isEmpty()) return@setOnItemClickListener
            if (item.value in selecionados) selecionados.remove(item.value) else selecionados.add(item.value)
            lista.setItemChecked(pos, item.value in selecionados)
        }

        // Mudança de aba
        tabLayout.addOnTabSelectedListener(object : com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab?) {
                abaAtual = tab?.position ?: 0
                rebuild(busca.text.toString().lowercase())
            }
            override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab?) = Unit
            override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab?) = Unit
        })

        // Texto de busca muda
        busca.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                rebuild(s?.toString()?.trim()?.lowercase().orEmpty())
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
        })

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

    /**
     * Configura o seletor de idioma e carrega o idioma salvo.
     * Suporta: português, inglês, espanhol, mandarim.
     * Usa idioma do sistema como padrão, cai para inglês se não suportado.
     */
    private fun setupLanguage() {
        val languages = listOf(
            Language.PORTUGUESE to getString(R.string.language_portuguese),
            Language.ENGLISH to getString(R.string.language_english),
            Language.SPANISH to getString(R.string.language_spanish),
            Language.CHINESE to getString(R.string.language_chinese)
        )
        val labels = languages.map { it.second }

        languageSpinner.setAdapter(
            object : android.widget.ArrayAdapter<String>(
                this, android.R.layout.simple_list_item_1, labels
            ) {
                private val noopFilter = object : android.widget.Filter() {
                    override fun performFiltering(constraint: CharSequence?) =
                        FilterResults().apply { values = labels; count = labels.size }
                    override fun publishResults(constraint: CharSequence?, results: FilterResults?) = notifyDataSetChanged()
                }
                override fun getFilter(): android.widget.Filter = noopFilter
            }
        )

        val saved = Prefs.language(this)
        val savedPos = languages.indexOfFirst { it.first == saved }.coerceAtLeast(0)
        languageSpinner.setText(labels[savedPos], false)
        languageSpinner.setOnItemClickListener { _, _, pos, _ ->
            val lang = languages.getOrElse(pos) { languages[0] }.first
            if (lang != Prefs.language(this@MainActivity)) {
                Prefs.setLanguage(this@MainActivity, lang)
                // Recria a tela: attachBaseContext reaplica o locale e toda a
                // UI recarrega já no idioma novo.
                recreate()
            }
        }

        applyLanguage()
    }

    /** Texto abaixo do seletor dizendo qual idioma está ativo. */
    private fun applyLanguage() {
        val nome = when (Prefs.language(this)) {
            Language.SYSTEM -> getString(R.string.language_english)
            Language.PORTUGUESE -> getString(R.string.language_portuguese)
            Language.ENGLISH -> getString(R.string.language_english)
            Language.SPANISH -> getString(R.string.language_spanish)
            Language.CHINESE -> getString(R.string.language_chinese)
        }
        languageDescText.text = getString(R.string.language_desc_selected, nome)
    }

    /** Atualiza o estado do serviço no topo (start/stop). */
    private fun updateServiceState() {
        val running = BridgeForegroundService.isRunning(this)
        serviceStateText.text = getString(if (running) R.string.service_on else R.string.service_off)
        toggleServiceButton.text = getString(if (running) R.string.stop else R.string.start)
    }

    /**
     * Mostra a versão ao lado do título. Cinco toques nela liberam o botão
     * de diagnóstico de áudio (fica escondido por padrão).
     */
    private fun setupVersion() {
        val versao = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull() ?: ""
        versionText.text = "v$versao"
        if (Prefs.diagnosticsUnlocked(this)) {
            audioDiagnosticsButton.visibility = android.view.View.VISIBLE
        }
        versionText.setOnClickListener {
            if (Prefs.diagnosticsUnlocked(this)) return@setOnClickListener
            versionTaps++
            if (versionTaps >= 5) {
                Prefs.setDiagnosticsUnlocked(this, true)
                audioDiagnosticsButton.visibility = android.view.View.VISIBLE
                android.widget.Toast.makeText(this, R.string.diag_unlocked, android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Salva o log do app (logcat deste processo) num TXT que o usuário
     * escolhe onde gravar, via seletor de documentos.
     */
    private fun saveLog() {
        startActivityForResult(
            Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "text/plain"
                putExtra(Intent.EXTRA_TITLE, "wabridge-log-${System.currentTimeMillis()}.txt")
            },
            3001,
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

/** Idiomas suportados. */
enum class Language {
    SYSTEM,
    PORTUGUESE,
    ENGLISH,
    SPANISH,
    CHINESE
}
