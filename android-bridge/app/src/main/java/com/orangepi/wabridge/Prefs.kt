package com.orangepi.wabridge

import android.content.Context

object Prefs {
    private const val FILE = "wabridge"
    private const val KEY_URL = "server_url"
    private const val KEY_SPEED = "playback_speed"
    private const val KEY_AUDIO_DELAY = "audio_delay_ms"
    private const val KEY_MODE = "engine_mode"
    private const val KEY_PHONE = "phone_number"
    private const val KEY_ALLOWED = "allowed_numbers"
    private const val KEY_BT_DEVICE = "bt_device"
    private const val KEY_BT_DEVICES = "bt_devices"
    private const val KEY_BT_ONLY = "bt_only_connected"
    private const val KEY_COUNTRY_CODE = "country_code"
    private const val KEY_ACCEPT_OWN = "accept_own"
    private const val KEY_SAMPLE_RATE = "audio_sample_rate"

    /** Endereço do servidor Node (padrão = IP da rede local). */
    const val DEFAULT_URL = "http://192.168.15.100:8097"

    /** Velocidade padrão de reprodução das notas de voz. */
    val DEFAULT_SPEED = 1.0f

    /** Opções de delay do áudio (em ms). */
    val AUDIO_DELAY_OPTIONS = listOf(0L, 500L, 1000L, 2000L, 3000L, 5000L)
    const val DEFAULT_AUDIO_DELAY_MS = 1000L

    /** Taxa de amostragem da gravação. O servidor externo re-encoda com ffmpeg
     *  a 16000 Hz (libopus); o app grava 48000 Hz. A/B testável por broadcast. */
    val DEFAULT_SAMPLE_RATE = 48000

    fun audioSampleRate(ctx: Context): Int =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getInt(KEY_SAMPLE_RATE, DEFAULT_SAMPLE_RATE)

    fun setAudioSampleRate(ctx: Context, rate: Int) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_SAMPLE_RATE, rate)
            .apply()
    }

    /** Tempo até o áudio começar após atender (em ms). */
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

    /** Como o app fala com o WhatsApp. */
    enum class Engine { EMBUTIDO, EXTERNO }

    /** Modo do motor: embutido (daemon no APK) ou servidor externo. */
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

    /** URL efetiva do motor (o embutido é sempre 127.0.0.1:8097). */
    fun engineUrl(ctx: Context): String =
        if (mode(ctx) == Engine.EMBUTIDO) DaemonManager.EMBEDDED_URL else url(ctx)

    /** DDI para o pareamento (padrão = 55). */
    fun countryCode(ctx: Context): String =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_COUNTRY_CODE, "55") ?: "55"

    fun setCountryCode(ctx: Context, value: String) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_COUNTRY_CODE, value.filter { it.isDigit() }.take(4))
            .apply()
    }

    /** Telefone usado no pareamento por código (para não digitar de novo). */
    fun phone(ctx: Context): String =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_PHONE, "") ?: ""

    fun setPhone(ctx: Context, value: String) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PHONE, value.trim())
            .apply()
    }

    /** Números permitidos (vazio = todos). */
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

    /** Endereços dos aparelhos que liberam o toque (vazio = qualquer um). */
    fun btDevices(ctx: Context): Set<String> {
        val prefs = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        // Tenta ler KEY_BT_DEVICES (novo); se não existir, tenta migrar de KEY_BT_DEVICE (antigo)
        val csv = prefs.getString(KEY_BT_DEVICES, null)
        if (csv != null) {
            return csv.split(',').map { it.trim() }.filter { it.isNotBlank() }.toSet()
        }
        // Migração do antigo KEY_BT_DEVICE
        val oldDevice = prefs.getString(KEY_BT_DEVICE, "") ?: ""
        return if (oldDevice.isNotBlank()) setOf(oldDevice) else emptySet()
    }

    fun setBtDevices(ctx: Context, devices: Set<String>) {
        val prefs = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val csv = devices.filter { it.isNotBlank() }.sorted().joinToString(",")
        prefs.edit()
            .putString(KEY_BT_DEVICES, csv)
            .remove(KEY_BT_DEVICE) // remove a chave antiga
            .apply()
    }

    /** Só toca quando o aparelho escolhido está conectado. */
    fun btOnlyConnected(ctx: Context): Boolean =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_BT_ONLY, false)

    fun setBtOnlyConnected(ctx: Context, value: Boolean) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_BT_ONLY, value)
            .apply()
    }

    /** Processa áudios enviados pelo próprio usuário (para testes). */
    fun acceptOwn(ctx: Context): Boolean =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_ACCEPT_OWN, false)

    fun setAcceptOwn(ctx: Context, value: Boolean) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ACCEPT_OWN, value)
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

    /** Velocidade padrão de reprodução (0.5 a 3.0). */
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
}
