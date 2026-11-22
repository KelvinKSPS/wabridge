package com.orangepi.wabridge

import android.content.ComponentName
import android.content.Context
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.util.Log

/**
 * Self-managed phone account used by Telecom to
 * display the call as if it were a normal watch call.
 */
object PhoneAccountHelper {
    private const val TAG = "PhoneAccount"
    const val PHONE_ACCOUNT_ID = "com.orangepi.wabridge.account"

    fun handle(context: Context): PhoneAccountHandle =
        PhoneAccountHandle(ComponentName(context, BridgeConnectionService::class.java), PHONE_ACCOUNT_ID)

    /**
     * Registers the self-managed account.
     *
     * Deliberately does not query `getSelfManagedPhoneAccounts()`: that call
     * requires the READ_PHONE_STATE permission and threw SecurityException,
     * bringing down the foreground service (and with it the WebSocket).
     * Registering again is harmless: Telecom accepts the repeated account.
     */
    fun ensureRegistered(context: Context): Boolean {
        val telecom = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
        if (telecom == null) {
            Log.w(TAG, "TelecomManager indisponível neste aparelho")
            return false
        }

        val account = PhoneAccount.Builder(
            handle(context),
            context.getString(R.string.app_name),
        )
            .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
            .build()

        return try {
            telecom.registerPhoneAccount(account)
            Log.i(TAG, "PhoneAccount registrada")
            true
        } catch (e: Exception) {
            // already registered is the normal case when reopening the app
            Log.w(TAG, "registro da PhoneAccount: ${e.message}")
            true
        }
    }
}
