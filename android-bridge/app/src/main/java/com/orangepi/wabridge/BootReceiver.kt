package com.orangepi.wabridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Starts the service (and the embedded daemon) when the phone finishes booting.
 *
 * Without this, the app would only work if the user opened it after boot.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (Prefs.mode(context) != Prefs.Engine.EMBUTIDO) return

        Log.i(TAG, "boot: subindo o serviço")
        runCatching {
            BridgeForegroundService.start(context)
        }.onFailure {
            Log.w(TAG, "não foi possível subir o serviço no boot: ${it.message}")
        }
    }

    companion object {
        private const val TAG = "WabridgeBoot"
    }
}
