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

    /**
     * Registra a conta self-managed.
     *
     * Não consulta `getSelfManagedPhoneAccounts()` de propósito: essa chamada
     * exige a permissão READ_PHONE_STATE e estourava com SecurityException —
     * derrubando o serviço em primeiro plano (e com ele o WebSocket).
     * Registrar de novo é inofensivo: o Telecom aceita a conta repetida.
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
            // já registrada é o caso normal ao reabrir o app
            Log.w(TAG, "registro da PhoneAccount: ${e.message}")
            true
        }
    }
}
