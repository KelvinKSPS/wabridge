package com.orangepi.wabridge

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Manages the embedded Go daemon process (the WhatsApp engine).
 *
 * The binary lives at `jniLibs/arm64-v8a/libwabridge_core.so` and is run
 * directly from `nativeLibraryDir` (Android extracts it and makes it executable
 * with `android:extractNativeLibs="true"`).
 *
 * The daemon listens on 127.0.0.1:8097, the same HTTP/WS contract the app
 * already consumes from the Node server. In "embedded" mode nothing else is needed.
 */
object DaemonManager {

    private const val TAG = "WabridgeDaemon"

    /** URL of the embedded engine. */
    const val EMBEDDED_URL = "http://127.0.0.1:8097"

    @Volatile
    var process: Process? = null
        private set

    /** Name of the embedded binary (the app looks for exactly this file). */
    fun binaryName(): String = "libwabridge_core.so"

    /** true when the daemon is running. */
    val running: Boolean get() = process?.isAlive == true

    /** Path of the embedded binary (null if none). */
    fun binaryPath(context: Context): String? {
        val dir = context.applicationInfo.nativeLibraryDir ?: return null
        val file = File(dir, "libwabridge_core.so")
        return if (file.exists()) file.absolutePath else null
    }

    /** true if the APK has the embedded daemon. */
    fun available(context: Context): Boolean = binaryPath(context) != null

    /**
     * Starts the daemon (if it is not up). Returns true if it is running.
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
            val pb = ProcessBuilder(cmd)
                .directory(dataDir)
                .redirectErrorStream(true)
            if (Prefs.acceptOwn(context)) {
                pb.environment()["WABRIDGE_ACCEPT_OWN"] = "1"
            }
            process = pb.start()
            // consumes the output in the background (if run on the main thread the
            // app freezes until the daemon closes)
            Thread { runCatching { process?.inputStream?.bufferedReader()?.forEachLine { Log.i(TAG, it) } } }.start()
            // waits for the daemon to open the port (up to 15s)
            repeat(30) {
                if (portOpen("127.0.0.1", 8097)) {
                    Log.i(TAG, "daemon no ar em 127.0.0.1:8097")
                    return true
                }
                Thread.sleep(500)
            }
            Log.e(TAG, "o daemon não abriu a porta 8097 em 15s")
            false
        } catch (e: Exception) {
            Log.e(TAG, "falha ao subir o daemon: ${e.message}")
            false
        }
    }

    /** true if the daemon's port is accepting connections. */
    private fun portOpen(host: String, port: Int): Boolean = try {
        java.net.Socket(host, port).use { true }
    } catch (_: Exception) {
        false
    }

    /** Stops the daemon. */
    @Synchronized
    fun stop() {
        runCatching { process?.destroy() }
        process = null
    }
}
