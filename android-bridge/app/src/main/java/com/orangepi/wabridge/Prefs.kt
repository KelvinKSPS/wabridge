package com.orangepi.wabridge

import android.content.Context

object Prefs {
    private const val FILE = "wabridge"
    private const val KEY_URL = "server_url"
    private const val KEY_SPEED = "playback_speed"
    private const val KEY_AUDIO_DELAY = "audio_delay_ms"
    private const val KEY_BEEP_DELAY = "beep_delay_ms"
    private const val KEY_MIN_REPLY = "min_reply_ms"
    private const val KEY_MODE = "engine_mode"
    private const val KEY_LANGUAGE = "app_language"
    private const val KEY_DIAG_UNLOCKED = "diagnostics_unlocked"
    private const val KEY_CALL_HISTORY = "call_history"
    private const val KEY_PHONE = "phone_number"
    private const val KEY_ALLOWED = "allowed_numbers"
    private const val KEY_BT_DEVICE = "bt_device"
    private const val KEY_BT_DEVICES = "bt_devices"
    private const val KEY_BT_ONLY = "bt_only_connected"
    private const val KEY_COUNTRY_CODE = "country_code"
    private const val KEY_ACCEPT_OWN = "accept_own"
    private const val KEY_QUIET_NOTIFICATIONS = "quiet_notifications"
    private const val KEY_SAMPLE_RATE = "audio_sample_rate"

    /** Node server address (default = local network IP). */
    const val DEFAULT_URL = "http://192.168.15.100:8097"

    /** Default playback speed for voice notes. */
    val DEFAULT_SPEED = 1.0f

    /** Audio delay options (in ms). */
    val AUDIO_DELAY_OPTIONS = listOf(0L, 500L, 1000L, 2000L, 3000L, 5000L)
    const val DEFAULT_AUDIO_DELAY_MS = 1000L

    /** Delay options before the "speak now" beep, counted from when the note finishes playing (in ms). */
    val BEEP_DELAY_OPTIONS = listOf(300L, 500L, 1000L, 1500L, 2000L, 3000L)
    const val DEFAULT_BEEP_DELAY_MS = 300L

    /** Recorded replies shorter than this are discarded instead of sent (in ms). */
    val MIN_REPLY_OPTIONS = listOf(500L, 1000L, 2000L, 3000L, 5000L)

    /** Recording sample rate. The external server re-encodes with ffmpeg
     *  to 16000 Hz (libopus); the app records at 48000 Hz. A/B-testable via broadcast. */
    val DEFAULT_SAMPLE_RATE = 48000

    fun minReplyMs(ctx: Context): Long =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getLong(KEY_MIN_REPLY, CallStateMachine.MIN_RECORDING_MS)
            .coerceIn(CallStateMachine.MIN_RECORDING_MS, 10_000L)

    fun setMinReplyMs(ctx: Context, value: Long) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_MIN_REPLY, value.coerceIn(CallStateMachine.MIN_RECORDING_MS, 10_000L))
            .apply()
    }

    fun audioSampleRate(ctx: Context): Int =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getInt(KEY_SAMPLE_RATE, DEFAULT_SAMPLE_RATE)

    fun setAudioSampleRate(ctx: Context, rate: Int) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_SAMPLE_RATE, rate)
            .apply()
    }

    /** Time until audio starts after answering (in ms). */
    fun audioDelayMs(ctx: Context): Long =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getLong(KEY_AUDIO_DELAY, DEFAULT_AUDIO_DELAY_MS)
            .coerceIn(0L, 10_000L)

    fun setAudioDelayMs(ctx: Context, value: Long) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_AUDIO_DELAY, value.coerceIn(0L, 10_000L))
            .apply()
    }

    /** Delay before the "speak now" beep, counted from when the note finishes playing (in ms). */
    fun beepDelayMs(ctx: Context): Long =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getLong(KEY_BEEP_DELAY, DEFAULT_BEEP_DELAY_MS)
            .coerceIn(0L, 10_000L)

    fun setBeepDelayMs(ctx: Context, value: Long) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_BEEP_DELAY, value.coerceIn(0L, 10_000L))
            .apply()
    }

    /** How the app communicates with WhatsApp. */
    enum class Engine { EMBUTIDO, EXTERNO }

    /** Engine mode: embedded (daemon in the APK) or external server. */
    fun mode(ctx: Context): Engine {
        val v = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY_MODE, Engine.EMBUTIDO.name)
        return runCatching { Engine.valueOf(v ?: Engine.EMBUTIDO.name) }.getOrDefault(Engine.EMBUTIDO)
    }

    fun setMode(ctx: Context, mode: Engine) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MODE, mode.name)
            .apply()
    }

    /** Effective engine URL (embedded mode always uses 127.0.0.1:8097). */
    fun engineUrl(ctx: Context): String =
        if (mode(ctx) == Engine.EMBUTIDO) DaemonManager.EMBEDDED_URL else url(ctx)

    /** Country calling code for pairing (default = 55). */
    fun countryCode(ctx: Context): String =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_COUNTRY_CODE, "55") ?: "55"

    fun setCountryCode(ctx: Context, value: String) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_COUNTRY_CODE, value.filter { it.isDigit() }.take(4))
            .apply()
    }

    /** Phone used for code pairing (so it does not need to be entered again). */
    fun phone(ctx: Context): String =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_PHONE, "") ?: ""

    fun setPhone(ctx: Context, value: String) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PHONE, value.trim())
            .apply()
    }

    /** Allowed numbers (empty = all). */
    fun allowedNumbers(ctx: Context): List<String> =
        (ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_ALLOWED, "") ?: "")
            .split(',', '\n')
            .map { it.trim() }
            .filter { it.isNotBlank() }

    fun setAllowedNumbers(ctx: Context, value: String) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ALLOWED, value.trim())
            .apply()
    }

    /** Addresses of devices that allow ringing (empty = any device). */
    fun btDevices(ctx: Context): Set<String> {
        val prefs = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        // Read KEY_BT_DEVICES (new); if absent, try migrating from KEY_BT_DEVICE (old).
        val csv = prefs.getString(KEY_BT_DEVICES, null)
        if (csv != null) {
            return csv.split(',').map { it.trim() }.filter { it.isNotBlank() }.toSet()
        }
        // Migration from the old KEY_BT_DEVICE.
        val oldDevice = prefs.getString(KEY_BT_DEVICE, "") ?: ""
        return if (oldDevice.isNotBlank()) setOf(oldDevice) else emptySet()
    }

    fun setBtDevices(ctx: Context, devices: Set<String>) {
        val prefs = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val csv = devices.filter { it.isNotBlank() }.sorted().joinToString(",")
        prefs.edit()
            .putString(KEY_BT_DEVICES, csv)
            .remove(KEY_BT_DEVICE) // remove the old key
            .apply()
    }

    /** Ring only when the selected device is connected. */
    fun btOnlyConnected(ctx: Context): Boolean =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_BT_ONLY, false)

    fun setBtOnlyConnected(ctx: Context, value: Boolean) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_BT_ONLY, value)
            .apply()
    }

    /** Process audio sent by the user (for testing). */
    fun acceptOwn(ctx: Context): Boolean =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_ACCEPT_OWN, false)

    fun setAcceptOwn(ctx: Context, value: Boolean) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ACCEPT_OWN, value)
            .apply()
    }

    /**
     * Quiet mode: incoming notes show as a plain notification (one buzz, like
     * an SMS) with a Play action instead of ringing like a phone call and
     * opening the call screen automatically.
     */
    fun quietNotifications(ctx: Context): Boolean =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_QUIET_NOTIFICATIONS, false)

    fun setQuietNotifications(ctx: Context, value: Boolean) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_QUIET_NOTIFICATIONS, value)
            .apply()
    }

    fun url(ctx: Context): String =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY_URL, DEFAULT_URL)
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_URL

    fun setUrl(ctx: Context, value: String) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_URL, value.trim().trimEnd('/'))
            .apply()
    }

    /** Default playback speed (0.5 to 3.0). */
    fun speed(ctx: Context): Float =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getFloat(KEY_SPEED, DEFAULT_SPEED)
            .let { PlaybackSpeed.clamp(it) }

    fun setSpeed(ctx: Context, value: Float) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putFloat(KEY_SPEED, PlaybackSpeed.clamp(value))
            .apply()
    }

    /** App language: system, Portuguese, English, Spanish, or Mandarin. */
    fun language(ctx: Context): Language {
        val v = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY_LANGUAGE, Language.SYSTEM.name)
        return runCatching { Language.valueOf(v ?: Language.SYSTEM.name) }.getOrDefault(Language.SYSTEM)
    }

    fun setLanguage(ctx: Context, value: Language) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LANGUAGE, value.name)
            .apply()
    }

    /** Audio diagnostics unlocked (5 taps on the version). */
    fun diagnosticsUnlocked(ctx: Context): Boolean =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_DIAG_UNLOCKED, false)

    fun setDiagnosticsUnlocked(ctx: Context, value: Boolean) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_DIAG_UNLOCKED, value)
            .apply()
    }

    @Synchronized
    fun callHistory(ctx: Context): List<CallHistoryEntry> {
        val raw = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_CALL_HISTORY, "[]") ?: "[]"
        return runCatching {
            val array = org.json.JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                CallHistoryEntry(
                    audioId = item.optString("audioId"),
                    name = item.optString("name"),
                    number = item.optString("number"),
                    outcome = item.optString("outcome"),
                    timestamp = item.optLong("timestamp"),
                    audioUrl = item.optString("audioUrl"),
                    durationSec = item.optInt("durationSec"),
                )
            }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun addCallHistory(ctx: Context, payload: CallPayload, outcome: String) {
        val entries = callHistory(ctx).filterNot { it.audioId == payload.audioId }.toMutableList()
        entries.add(0, CallHistoryEntry(
            audioId = payload.audioId,
            name = payload.senderName,
            number = payload.senderNumber,
            outcome = outcome,
            timestamp = System.currentTimeMillis(),
            audioUrl = payload.audioUrl,
            durationSec = payload.durationSec,
        ))
        val array = org.json.JSONArray().apply {
            entries.take(100).forEach { entry ->
                put(org.json.JSONObject().apply {
                    put("audioId", entry.audioId)
                    put("name", entry.name)
                    put("number", entry.number)
                    put("outcome", entry.outcome)
                    put("timestamp", entry.timestamp)
                    put("audioUrl", entry.audioUrl)
                    put("durationSec", entry.durationSec)
                })
            }
        }
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(KEY_CALL_HISTORY, array.toString())
            .apply()
    }

    @Synchronized
    fun clearCallHistory(ctx: Context) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .remove(KEY_CALL_HISTORY)
            .apply()
    }
}

data class CallHistoryEntry(
    val audioId: String,
    val name: String,
    val number: String,
    val outcome: String,
    val timestamp: Long,
    val audioUrl: String,
    val durationSec: Int,
)

object CallHistoryOutcome {
    const val RECEIVED = "received"
    const val ANSWERED = "answered"
    const val REJECTED = "rejected"
}
