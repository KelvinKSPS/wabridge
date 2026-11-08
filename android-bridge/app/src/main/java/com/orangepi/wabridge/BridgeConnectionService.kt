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
        return WatchCallConnection(this, payload)
    }

    /**
     * O Telecom pode chamar isso com a conta nula (ex.: a conta foi removida
     * entre o addNewIncomingCall e a criação). Declarar o parâmetro como
     * não-nulo fazia o app estourar NullPointerException e morrer — e com
     * ele o WebSocket.
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
