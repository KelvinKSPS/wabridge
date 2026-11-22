package com.orangepi.wabridge

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.core.content.ContextCompat

class MainActivity : Activity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    private lateinit var urlInput: EditText
    private lateinit var speedSpinner: android.widget.AutoCompleteTextView
    private lateinit var audioDelaySpinner: android.widget.AutoCompleteTextView
    private lateinit var beepDelaySpinner: android.widget.AutoCompleteTextView
    private lateinit var minReplySpinner: android.widget.AutoCompleteTextView
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
    private lateinit var callHistoryButton: Button
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
            if (this@MainActivity::serviceStateText.isInitialized) updateServiceState()
            if (this@MainActivity::callHistoryButton.isInitialized) updateCallHistoryButton()
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
        beepDelaySpinner = findViewById(R.id.beepDelaySpinner)
        minReplySpinner = findViewById(R.id.minReplySpinner)
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
        callHistoryButton = findViewById(R.id.callHistoryButton)
        callHistoryButton.setOnClickListener { showCallHistory() }
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
        updateCallHistoryButton()
        audioDiagnosticsButton.setOnClickListener { showAudioDiagnostics() }
        saveLogButton.setOnClickListener { saveLog() }

        // Start the embedded daemon before pairing, scanning a QR code, or receiving voice notes.
        if (Prefs.mode(this) == Prefs.Engine.EMBUTIDO && !DaemonManager.running) {
            Thread {
                if (DaemonManager.start(this)) {
                    runOnUiThread { refreshEngineStatus() }
                }
            }.start()
        }

        setupSpeedDropdown()

        // Delay before audio playback starts.
        val currentDelay = Prefs.audioDelayMs(this)
        val delayLabels = Prefs.AUDIO_DELAY_OPTIONS.map { ms ->
            val secs = ms / 1000.0
            if (secs == secs.toLong().toDouble()) "${secs.toLong()} s" else "$secs s"
        }
        setupDropdown(audioDelaySpinner, delayLabels, Prefs.AUDIO_DELAY_OPTIONS.indexOfFirst { it == currentDelay }) { pos ->
            Prefs.setAudioDelayMs(this@MainActivity, Prefs.AUDIO_DELAY_OPTIONS.getOrElse(pos) { 1000L })
        }

        // Delay before the "speak now" beep, counted from when the note finishes playing.
        val currentBeepDelay = Prefs.beepDelayMs(this)
        val beepDelayLabels = Prefs.BEEP_DELAY_OPTIONS.map { ms ->
            val secs = ms / 1000.0
            if (secs == secs.toLong().toDouble()) "${secs.toLong()} s" else "$secs s"
        }
        setupDropdown(beepDelaySpinner, beepDelayLabels, Prefs.BEEP_DELAY_OPTIONS.indexOfFirst { it == currentBeepDelay }) { pos ->
            Prefs.setBeepDelayMs(this@MainActivity, Prefs.BEEP_DELAY_OPTIONS.getOrElse(pos) { Prefs.DEFAULT_BEEP_DELAY_MS })
        }

        // Replies shorter than this are discarded instead of sent.
        val currentMinReply = Prefs.minReplyMs(this)
        val minReplyLabels = Prefs.MIN_REPLY_OPTIONS.map { ms ->
            val secs = ms / 1000.0
            if (secs == secs.toLong().toDouble()) "${secs.toLong()} s" else "$secs s"
        }
        setupDropdown(minReplySpinner, minReplyLabels, Prefs.MIN_REPLY_OPTIONS.indexOfFirst { it == currentMinReply }) { pos ->
            Prefs.setMinReplyMs(this@MainActivity, Prefs.MIN_REPLY_OPTIONS.getOrElse(pos) { CallStateMachine.MIN_RECORDING_MS })
        }

        // Single button at the top toggles the service on and off.
        toggleServiceButton.setOnClickListener {
            if (BridgeForegroundService.isRunning(this)) {
                stopService(Intent(this, BridgeForegroundService::class.java))
            } else {
                Prefs.setUrl(this, urlInput.text.toString())
                Prefs.setAllowedNumbers(this, allowedInput.text.toString())
                syncAllowedNumbers()
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

    private fun setupSpeedDropdown() {
        val current = Prefs.speed(this)
        val labels = PlaybackSpeed.menu.map { PlaybackSpeed.label(it) } + getString(R.string.speed_custom)
        val selected = PlaybackSpeed.menu.indexOfFirst { it == current }
        setupDropdown(speedSpinner, labels, if (selected >= 0) selected else labels.lastIndex) { pos ->
            if (pos == PlaybackSpeed.menu.size) {
                showCustomSpeedDialog()
            } else {
                Prefs.setSpeed(this@MainActivity, PlaybackSpeed.menu[pos])
            }
        }
        if (selected < 0) speedSpinner.setText(PlaybackSpeed.label(current), false)
    }

    private fun showCustomSpeedDialog() {
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(Prefs.speed(this@MainActivity).toString())
            selectAll()
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.speed_custom_title)
            .setMessage(R.string.speed_custom_range)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val value = input.text.toString().trim().replace(',', '.').toFloatOrNull()
                if (value == null || !value.isFinite() || value !in PlaybackSpeed.MIN..PlaybackSpeed.MAX) {
                    android.widget.Toast.makeText(this, R.string.speed_custom_invalid, android.widget.Toast.LENGTH_LONG).show()
                    setupSpeedDropdown()
                    return@setPositiveButton
                }
                Prefs.setSpeed(this, value)
                speedSpinner.setText(PlaybackSpeed.label(value), false)
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> setupSpeedDropdown() }
            .setOnCancelListener { setupSpeedDropdown() }
            .show()
    }

    private fun showCallHistory() {
        val entries = Prefs.callHistory(this)
        if (entries.isEmpty()) {
            android.app.AlertDialog.Builder(this)
                .setTitle(R.string.call_history_title)
                .setMessage(R.string.call_history_empty)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        val labels = entries.map { entry ->
            val outcome = when (entry.outcome) {
                CallHistoryOutcome.ANSWERED -> R.string.call_history_answered
                CallHistoryOutcome.REJECTED -> R.string.call_history_rejected
                else -> R.string.call_history_received
            }
            val whenText = android.text.format.DateUtils.getRelativeTimeSpanString(entry.timestamp)
            "${getString(outcome)} · ${entry.name} (${entry.number}) · $whenText"
        }.toTypedArray()
        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.call_history_title)
            .setItems(labels) { _, index ->
                val entry = entries[index]
                if (entry.outcome == CallHistoryOutcome.RECEIVED) {
                    showHistoryAudioDialog(entry)
                }
            }
            .setNeutralButton(R.string.call_history_clear) { _, _ ->
                android.app.AlertDialog.Builder(this)
                    .setMessage(R.string.call_history_confirm_clear)
                    .setPositiveButton(R.string.call_history_clear) { _, _ ->
                        Prefs.clearCallHistory(this)
                        updateCallHistoryButton()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun updateCallHistoryButton() {
        val count = Prefs.callHistory(this).size
        callHistoryButton.text = if (count == 0) getString(R.string.call_history_button_empty)
        else getString(R.string.call_history_button, count)
    }

    /**
     * Lets the user listen to a received note on the phone itself — useful
     * when the watch is out of reach — with play/pause and speed control,
     * plus a button to ring it on the watch instead (same flow as a live call).
     */
    private fun showHistoryAudioDialog(entry: CallHistoryEntry) {
        var player: MediaPlayer? = null
        var speed = Prefs.speed(this)

        val playButton = Button(this).apply {
            text = getString(R.string.history_play)
        }
        val speedButton = Button(this).apply {
            text = getString(R.string.speed_value, PlaybackSpeed.label(speed))
        }
        val transferButton = Button(this).apply {
            text = getString(R.string.history_transfer_to_watch)
        }
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
            addView(TextView(this@MainActivity).apply {
                text = "${entry.name} (${entry.number})"
                textSize = 16f
            })
            addView(playButton)
            addView(speedButton)
            addView(transferButton)
        }

        fun releasePlayer() {
            runCatching { player?.stop() }
            player?.release()
            player = null
        }

        fun applySpeed() {
            val p = player ?: return
            runCatching {
                p.playbackParams = (p.playbackParams ?: android.media.PlaybackParams()).setSpeed(speed)
            }
        }

        var preparing = false

        playButton.setOnClickListener {
            if (preparing) return@setOnClickListener // still loading; ignore extra taps
            val current = player
            if (current != null) {
                if (current.isPlaying) {
                    current.pause()
                    playButton.text = getString(R.string.history_play)
                } else {
                    current.start()
                    playButton.text = getString(R.string.history_pause)
                }
                return@setOnClickListener
            }
            preparing = true
            runCatching {
                val mp = MediaPlayer()
                mp.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                mp.setDataSource(entry.audioUrl)
                mp.setOnPreparedListener {
                    // The dialog may have been dismissed (and the player released)
                    // while loading; a stale callback must not touch it or the UI.
                    if (player !== mp) return@setOnPreparedListener
                    preparing = false
                    applySpeed()
                    it.start()
                    playButton.text = getString(R.string.history_pause)
                }
                mp.setOnCompletionListener {
                    if (player !== mp) return@setOnCompletionListener
                    playButton.text = getString(R.string.history_play)
                }
                mp.setOnErrorListener { _, _, _ ->
                    if (player === mp) {
                        preparing = false
                        playButton.text = getString(R.string.history_play)
                        player = null
                        android.widget.Toast.makeText(this, R.string.history_play_error, android.widget.Toast.LENGTH_LONG).show()
                    }
                    true
                }
                player = mp
                mp.prepareAsync()
            }.onFailure {
                preparing = false
                player = null
                android.widget.Toast.makeText(this, R.string.history_play_error, android.widget.Toast.LENGTH_LONG).show()
            }
        }

        speedButton.setOnClickListener {
            speed = PlaybackSpeed.next(speed)
            speedButton.text = getString(R.string.speed_value, PlaybackSpeed.label(speed))
            applySpeed()
        }

        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle(R.string.call_history_title)
            .setView(container)
            .setNegativeButton(android.R.string.cancel, null)
            .setOnDismissListener { releasePlayer() }
            .create()

        transferButton.setOnClickListener {
            releasePlayer()
            BridgeForegroundService.ringPayload(
                this,
                CallPayload(
                    audioId = entry.audioId,
                    senderName = entry.name,
                    senderNumber = entry.number,
                    audioUrl = entry.audioUrl,
                    durationSec = entry.durationSec,
                ),
            )
            android.widget.Toast.makeText(this, R.string.history_transferred, android.widget.Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        dialog.show()
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

        // Save log: read this process's logcat and write it to the selected file.
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
        // When returning to the app, reload: Bluetooth permission may have
        // just been granted, and the service may have stopped.
        if (this::allowedInput.isInitialized) {
            updateAllowedSummary()
            setupBluetooth()
            ensureBridgeRunning()
        }
        // Reapply the language (in case it changed while this screen was open).
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
     * Configure an ExposedDropdownMenu that does not filter when reopened.
     *
     * MaterialAutoCompleteTextView filters the list because it is an AutoComplete.
     * It filters by the text already in the field. After choosing an option ("1.5x"),
     * reopening showed only items matching "1.5x" — it looked stuck.
     * Use an ArrayAdapter with a no-op filter so the full list always appears.
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

    /** Engine selector: embedded (daemon in the APK) or external server. */
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

    /** Generate the 8-digit code to pair without a QR code. */
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
                // The daemon must be running before making the request.
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
                    // Refresh account status every 3 seconds for 3 minutes.
                    if (code != null) scheduleStatusRefresh()
                }
            }.start()
        }
        allowedInput.setText(Prefs.allowedNumbers(this).joinToString(", "))
        updateAllowedSummary()
        setupAllowedPicker()
        setupBluetooth()
        setupAcceptOwn()
        setupQuietNotifications()
        refreshAccountStatus()
    }

    /** Update the account authentication status. */
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

    /** Format a +55 number with 12–13 digits as +55 DD NNNNN-NNNN. */
    private fun formatPhone(phone: String): String {
        if (!phone.startsWith("55") || phone.length !in 12..13) return "+$phone"
        val parte1 = phone.substring(0, 2)  // 55
        val parte2 = phone.substring(2, 4)  // DD
        val parte3 = phone.substring(4, 9)  // NNNNN
        val parte4 = phone.substring(9)     // NNNN
        return "+$parte1 $parte2 $parte3-$parte4"
    }

    /** Schedule a status refresh every 3 seconds for up to 3 minutes. */
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

    /** Checkbox to play the user's own audio (test mode). */
    private fun setupAcceptOwn() {
        val check = findViewById<android.widget.CheckBox>(R.id.acceptOwnCheck)
        check.isChecked = Prefs.acceptOwn(this)
        check.setOnCheckedChangeListener { _, v ->
            Prefs.setAcceptOwn(this, v)
            // Restart the daemon in the background (start() blocks for up to 15s).
            if (Prefs.mode(this) == Prefs.Engine.EMBUTIDO) {
                Thread {
                    DaemonManager.stop()
                    DaemonManager.start(this)
                }.start()
            }
        }
    }

    /**
     * Custom integration: instead of ringing, offer the note through a
     * device-specific flow. Huawei is the only one so far, so the choice is
     * not stored — the checkbox alone turns it on.
     */
    private fun setupQuietNotifications() {
        val check = findViewById<android.widget.CheckBox>(R.id.quietNotificationsCheck)
        val layout = findViewById<android.view.View>(R.id.integrationLayout)
        val hint = findViewById<android.view.View>(R.id.quietNotificationsHint)
        setupDropdown(
            findViewById(R.id.integrationSpinner),
            listOf(getString(R.string.integration_huawei)),
            0,
        ) { }
        fun show(on: Boolean) {
            layout.visibility = if (on) android.view.View.VISIBLE else android.view.View.GONE
            hint.visibility = if (on) android.view.View.VISIBLE else android.view.View.GONE
        }
        check.isChecked = Prefs.quietNotifications(this)
        show(check.isChecked)
        check.setOnCheckedChangeListener { _, v ->
            Prefs.setQuietNotifications(this, v)
            show(v)
        }
    }

    /** Watch/headset selector: ring only when the selected device is connected. */
    private fun setupBluetooth() {
        val check = findViewById<android.widget.CheckBox>(R.id.btOnlyCheck)
        val button = findViewById<Button>(R.id.btPickButton)

        check.isChecked = Prefs.btOnlyConnected(this)

        // Without permission (Android 12+), the list is empty; request it now.
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

    /** Shows the state of the chosen devices. */
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

    /** Updates the Bluetooth state from time to time. */
    private val btTicker = Runnable { updateBtState() }

    /** Update the summary next to the "Choose" button. */
    private fun updateAllowedSummary() {
        val total = Prefs.allowedNumbers(this).size
        findViewById<TextView>(R.id.allowedSummary).text =
            if (total == 0) getString(R.string.allowed_empty)
            else getString(R.string.allowed_selected, total)
    }

    /**
     * Pushes the saved allowlist to the running engine.
     *
     * Saving the list locally (SharedPreferences) is not enough: the engine
     * (embedded daemon or external server) is what filters incoming audio,
     * and it only learns about the list through this HTTP call. Called after
     * every edit and once the daemon is confirmed running, so the filter is
     * never silently stuck on "accept everyone".
     */
    private fun syncAllowedNumbers() {
        val numbers = Prefs.allowedNumbers(this)
        Thread {
            val ok = PairingClient.syncAllowedNumbers(this, numbers)
            if (!ok) android.util.Log.w("WabridgeAllowed", "falha ao sincronizar lista de permitidos com o motor")
        }.start()
    }

    /**
     * Interactive list: opens a selector with the account's contacts and groups.
     * Available only in embedded mode (the Node server has no /contacts).
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

    /** Dialog with search and multi-selection (contacts and groups). */
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

        // Source of truth: selected values (updated on each click).
        val selecionados = Prefs.allowedNumbers(this).toMutableSet()

        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(0, 0, 0, 0)
        }

        // Tabs: contacts and groups.
        val tabLayout = com.google.android.material.tabs.TabLayout(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        tabLayout.addTab(tabLayout.newTab().setText(getString(R.string.allowed_tab_contacts_n, contatos.size)))
        tabLayout.addTab(tabLayout.newTab().setText(getString(R.string.allowed_tab_groups_n, grupos.size)))

        // Search.
        val busca = android.widget.EditText(this).apply {
            hint = getString(R.string.allowed_search)
            setSingleLine()
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(16, 8, 16, 8) }
        }

        // ListView with fixed height (400dp).
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

        // Current tab state.
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
                    // Disable the empty item.
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

        // Clicking an item toggles its selected state.
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

        // Tab change.
        tabLayout.addOnTabSelectedListener(object : com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab?) {
                abaAtual = tab?.position ?: 0
                rebuild(busca.text.toString().lowercase())
            }
            override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab?) = Unit
            override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab?) = Unit
        })

        // Search text changed.
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
                syncAllowedNumbers()
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
     * Ensure the service is running.
     *
     * Android occasionally stops the service; when the app opens, we check
     * and restart it so the user is not left without the watch connection.
     */
    private fun ensureBridgeRunning() {
        if (Prefs.mode(this) != Prefs.Engine.EMBUTIDO && urlInput.text.isNotBlank()) {
            ContextCompat.startForegroundService(this, Intent(this, BridgeForegroundService::class.java))
        }
    }

    /** Request exemption from battery optimization (the daemon must run all day). */
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

    /** Show engine status (and whether the daemon is running). */
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
     * Configure the language selector and load the saved language.
     * Supports Portuguese, English, Spanish, and Mandarin.
     * Uses the system language by default, falling back to English if unsupported.
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
                // Recreate the screen: attachBaseContext reapplies the locale and the
                // entire UI reloads in the new language.
                recreate()
            }
        }

        applyLanguage()
    }

    /** Text below the selector showing the active language. */
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

    /** Update the service state at the top (start/stop). */
    private fun updateServiceState() {
        val running = BridgeForegroundService.isRunning(this)
        serviceStateText.text = getString(if (running) R.string.service_on else R.string.service_off)
        toggleServiceButton.text = getString(if (running) R.string.stop else R.string.start)
    }

    /**
     * Show the version next to the title. Five taps unlock the
     * audio diagnostics button (hidden by default).
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
     * Save the app log (this process's logcat) to a TXT file chosen
     * by the user through the document picker.
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

/** Supported languages. */
enum class Language {
    SYSTEM,
    PORTUGUESE,
    ENGLISH,
    SPANISH,
    CHINESE
}
