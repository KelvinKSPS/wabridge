package com.orangepi.wabridge

import android.content.ComponentName
import android.content.Context
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.util.Log

/**
 * Conta telefônica autogerenciada (self-managed) usada pelo Telecom para
 * exibir a chamada como se fosse uma ligação normal do relógio.
 */
object PhoneAccountHelper {
    private const val TAG = "PhoneAccount"
    const val PHONE_ACCOUNT_ID = "com.orangepi.wabridge.account"

    fun handle(context: Context): PhoneAccountHandle =
        PhoneAccountHandle(ComponentName(context, BridgeConnectionService::class.java), PHONE_ACCOUNT_ID)

    /** Registra a conta se ainda não existir. Idempotente. */
    fun ensureRegistered(context: Context): Boolean {
        val telecom = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            ?: return false
        val accountHandle = handle(context)

        val already = telecom.getSelfManagedPhoneAccounts().any { it == accountHandle }
        if (already) return true

        val account = PhoneAccount.Builder(
            accountHandle,
            context.getString(R.string.app_name),
        )
            .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
            .build()

        return try {
            telecom.registerPhoneAccount(account)
            Log.i(TAG, "PhoneAccount registrada")
            true
        } catch (e: Exception) {
            Log.e(TAG, "falha ao registrar PhoneAccount: ${e.message}")
            false
        }
    }
}
