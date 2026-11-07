package com.orangepi.wabridge

import android.content.Context

object Prefs {
    private const val FILE = "wabridge"
    private const val KEY_URL = "server_url"

    /** Endereço do servidor Node (padrão = IP da rede local). */
    const val DEFAULT_URL = "http://192.168.15.100:8097"

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
}
