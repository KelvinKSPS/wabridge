package com.orangepi.wabridge

/** Converte a URL do servidor (http) nas URLs usadas pelo app. */
object WsUrls {

    /** http://host:port -> ws://host:port/ws */
    fun wsUrl(baseUrl: String): String {
        val base = baseUrl.trim().trimEnd('/')
        val scheme = if (base.startsWith("https://")) "wss://" else "ws://"
        val host = base.substringAfter("://").substringBefore('/')
        return "$scheme$host/ws"
    }

    /** http://host:port + /reply/audio */
    fun httpUrl(baseUrl: String, path: String): String {
        val base = baseUrl.trim().trimEnd('/')
        val p = if (path.startsWith('/')) path else "/$path"
        return "$base$p"
    }
}
