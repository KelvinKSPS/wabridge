package com.orangepi.wabridge

import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.PhoneAccountHandle
import android.util.Log

/**
 * ConnectionService autogerenciado: o Telecom cria uma [WatchCallConnection]
 * para cada áudio recebido.
 */
class BridgeConnectionService : ConnectionService() {

    override fun onCreate() {
        super.onCreate()
        // Garante que a conta exista (ex.: após reinício do aparelho).
        PhoneAccountHelper.ensureRegistered(this)
    }

    override fun onCreateIncomingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle,
        request: ConnectionRequest,
    ): Connection? {
        val payload = CallPayload.fromJson(request.extras?.getString(BridgeExtras.PAYLOAD))
        if (payload == null) {
            Log.w(TAG, "chamada sem payload válido")
            return null
        }
        Log.i(TAG, "criando chamada de ${payload.senderName} (${payload.senderNumber})")
        return WatchCallConnection(this, payload)
    }

    override fun onCreateIncomingConnectionFailed(
        connectionManagerPhoneAccount: PhoneAccountHandle,
        request: ConnectionRequest,
    ) {
        Log.e(TAG, "falha ao criar chamada entrante")
    }

    override fun onCreateOutgoingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle,
        request: ConnectionRequest,
    ): Connection? = null

    companion object {
        private const val TAG = "BridgeConn"
    }
}
