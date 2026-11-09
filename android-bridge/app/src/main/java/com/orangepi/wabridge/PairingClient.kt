package com.orangepi.wabridge

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Cliente das rotas de pareamento do motor (embutido ou externo).
 *
 * Tudo síncrono e pequeno: chamadas são feitas de uma Thread própria.
 */
object PairingClient {

    /** Gera o código de 8 dígitos. Devolve null se falhar. */
    fun requestCode(context: Context, phone: String): String? =
        runCatching {
            val url = URL(Prefs.engineUrl(context) + "/auth/pair-code")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 20_000
            conn.readTimeout = 30_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { os ->
                os.write("""{"phoneNumber":"$phone"}""".toByteArray(Charsets.UTF_8))
            }
            val body = readAll(conn.inputStream)
            conn.disconnect()
            val json = org.json.JSONObject(body)
            json.optString("code").takeIf { it.isNotBlank() }
        }.getOrElse {
            android.util.Log.w("WabridgePairing", "pair-code: ${it.message}")
            null
        }

    private fun readAll(input: java.io.InputStream): String =
        BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { it.readText() }
}
