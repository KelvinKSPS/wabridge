package com.orangepi.wabridge

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Cliente WebSocket com reconexão automática.
 */
class WsManager(
    private val urlProvider: () -> String,
    private val onEvent: (JSONObject) -> Unit,
    private val onState: (String) -> Unit,
) {
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var socket: WebSocket? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch { loop() }
    }

    fun stop() {
        job?.cancel()
        job = null
        socket?.close(1000, "bye")
        socket = null
    }

    private fun wsUrl(): String = WsUrls.wsUrl(urlProvider())

    private suspend fun loop() {
        var attempt = 0
        while (scope.isActive) {
            try {
                onState("conectando...")
                val cleanClose = connectOnce()
                attempt = 0
                if (cleanClose) return
            } catch (e: Exception) {
                onState("desconectado: ${e.message}")
            }
            val backoff = minOf(30_000L, 1000L * (1L shl minOf(attempt++, 5)))
            delay(backoff)
        }
    }

    /** Suspende até o socket fechar. Retorna true se fechou de forma limpa. */
    private suspend fun connectOnce(): Boolean {
        val closed = CompletableDeferred<Boolean>()
        var opened = false

        val ws = client.newWebSocket(
            Request.Builder().url(wsUrl()).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    opened = true
                    onState("conectado")
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    try {
                        onEvent(JSONObject(text))
                    } catch (e: Exception) {
                        Log.w(TAG, "mensagem invalida: ${e.message}")
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                    if (closed.isActive) closed.complete(true)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    onState("desconectado")
                    if (closed.isActive) closed.complete(true)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    onState("erro: ${t.message}")
                    if (closed.isActive) {
                        if (opened) closed.complete(false) else closed.completeExceptionally(t)
                    }
                }
            },
        )
        socket = ws

        return try {
            closed.await()
        } finally {
            socket = null
        }
    }

    companion object {
        private const val TAG = "WsManager"
    }
}
