package com.orangepi.wabridge

import android.content.Context
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

    /**
     * Busca contatos e grupos da conta (lista interativa).
     * Devolve null se falhar (ex.: modo servidor, que não tem /contacts).
     */
    fun contacts(context: Context): Pair<List<ContactItem>, List<ContactItem>>? =
        runCatching {
            val url = URL(Prefs.engineUrl(context) + "/contacts")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            val body = readAll(conn.inputStream)
            conn.disconnect()
            val json = org.json.JSONObject(body)
            parseItems(json.optJSONArray("contatos"), "number") to
                parseItems(json.optJSONArray("grupos"), "jid")
        }.getOrElse {
            android.util.Log.w("WabridgeContacts", "contacts: ${it.message}")
            null
        }

    private fun parseItems(array: org.json.JSONArray?, key: String): List<ContactItem> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            ContactItem(
                name = o.optString("name").ifBlank { o.optString(key) },
                value = o.optString(key),
            )
        }
    }

    private fun readAll(input: java.io.InputStream): String =
        BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { it.readText() }
}
