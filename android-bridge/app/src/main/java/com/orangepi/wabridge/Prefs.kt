package com.orangepi.wabridge

import android.content.Context

object Prefs {
    private const val FILE = "wabridge"
    private const val KEY_URL = "server_url"
    private const val KEY_SPEED = "playback_speed"

    /** Endereço do servidor Node (padrão = IP da rede local). */
    const val DEFAULT_URL = "http://192.168.15.100:8097"

    /** Velocidade padrão de reprodução das notas de voz. */
    val DEFAULT_SPEED = 1.0f

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

    /** Velocidade padrão de reprodução (0.5 a 2.0). */
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
