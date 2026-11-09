package com.orangepi.wabridge

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Gerencia o processo do daemon Go embarcado (o motor do WhatsApp).
 *
 * O binário fica em `jniLibs/arm64-v8a/libwabridge_core.so` e é executado
 * direto de `nativeLibraryDir` (o Android extrai e deixa executável com
 * `android:extractNativeLibs="true"`).
 *
 * O daemon escuta em 127.0.0.1:8097 — o mesmo contrato HTTP/WS que o app
 * já consome do servidor Node. No modo "embutido" nada mais é preciso.
 */
object DaemonManager {

    private const val TAG = "WabridgeDaemon"

    /** URL do motor embutido. */
    const val EMBEDDED_URL = "http://127.0.0.1:8097"

    @Volatile
    var process: Process? = null
        private set

    /** Nome do binário embutido (o app procura exatamente este arquivo). */
    fun binaryName(): String = "libwabridge_core.so"

    /** true quando o daemon está rodando. */
    val running: Boolean get() = process?.isAlive == true

    /** Caminho do binário embarcado (null se não houver). */
    fun binaryPath(context: Context): String? {
        val dir = context.applicationInfo.nativeLibraryDir ?: return null
        val file = File(dir, "libwabridge_core.so")
        return if (file.exists()) file.absolutePath else null
    }

    /** true se o APK tem o daemon embutido. */
    fun available(context: Context): Boolean = binaryPath(context) != null

    /**
     * Sobe o daemon (se não estiver no ar). Devolve true se está rodando.
     */
    @Synchronized
    fun start(context: Context): Boolean {
        if (running) return true
        val bin = binaryPath(context) ?: run {
            Log.w(TAG, "daemon não está no APK (rode scripts/build-daemon.sh)")
            return false
        }
        val dataDir = File(context.filesDir, "wabridge").apply { mkdirs() }
        return try {
            val cmd = listOf(bin, "--dir", dataDir.absolutePath, "--port", "8097")
            Log.i(TAG, "subindo o daemon: $cmd")
            process = ProcessBuilder(cmd)
                .directory(dataDir)
                .redirectErrorStream(true)
                .start()
            // consome a saída para o logcat (e para o processo não travar)
            process?.inputStream?.bufferedReader()?.forEachLine { Log.i(TAG, it) }
            true
        } catch (e: Exception) {
            Log.e(TAG, "falha ao subir o daemon: ${e.message}")
            false
        }
    }

    /** Derruba o daemon. */
    @Synchronized
    fun stop() {
        runCatching { process?.destroy() }
        process = null
    }
}
