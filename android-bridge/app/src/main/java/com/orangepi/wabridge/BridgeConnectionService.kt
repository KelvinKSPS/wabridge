package com.orangepi.wabridge

import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.PhoneAccountHandle
import android.util.Log

/**
 * Self-managed ConnectionService: Telecom creates a [WatchCallConnection]
 * for each received audio.
 */
class BridgeConnectionService : ConnectionService() {

    override fun onCreate() {
        super.onCreate()
        // Make sure the account exists (e.g. after a device restart).
        runCatching { PhoneAccountHelper.ensureRegistered(this) }
            .onFailure { Log.w(TAG, "registro da conta falhou: ${it.message}") }
    }

    override fun onCreateIncomingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest,
    ): Connection? {
        val payload = CallPayload.fromJson(request.extras?.getString(BridgeExtras.PAYLOAD))
        if (payload == null) {
            Log.w(TAG, "chamada sem payload válido")
            return null
        }
        Log.i(TAG, "criando chamada de ${payload.senderName} (${payload.senderNumber})")
        val connection = WatchCallConnection(this, payload)
        return connection
    }

    /**
     * Telecom may call this with a null account (e.g. the account was removed
     * between addNewIncomingCall and creation). Declaring the parameter as
     * non-null made the app throw NullPointerException and die, and with it
     * the WebSocket.
     */
    override fun onCreateIncomingConnectionFailed(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest,
    ) {
        Log.e(TAG, "falha ao criar chamada entrante (conta=${connectionManagerPhoneAccount?.toString() ?: "nula"})")
    }

    override fun onCreateOutgoingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest,
    ): Connection? = null

    companion object {
        private const val TAG = "BridgeConn"
    }
}
