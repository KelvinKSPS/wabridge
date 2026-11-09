package com.orangepi.wabridge

import org.json.JSONObject

/** Evento NEW_VOICE_NOTE enviado pelo servidor. */
data class CallPayload(
    val audioId: String,
    val senderName: String,
    val senderNumber: String,
    val audioUrl: String,
    /** Duração da nota de voz em segundos (0 = desconhecido). */
    val durationSec: Int = 0,
) {
    /** Nome exibido na tela de chamada: "Sara · 0:12". */
    val displayName: String
        get() = if (durationSec > 0) {
            "$senderName · ${formatDuration(durationSec)}"
        } else {
            senderName
        }

    fun toJson(): String = JSONObject().apply {
        put("type", "NEW_VOICE_NOTE")
        put("audioId", audioId)
        put("senderName", senderName)
        put("senderNumber", senderNumber)
        put("audioUrl", audioUrl)
        put("durationSec", durationSec)
    }.toString()

    companion object {
        /** Chamada em andamento (usado pelas ações da notificação). */
        @Volatile
        var current: CallPayload? = null
            private set

        fun setCurrent(payload: CallPayload?) {
            current = payload
        }

        /** "0:12", "1:05", "2:00:10" */
        fun formatDuration(seconds: Int): String {
            val h = seconds / 3600
            val m = (seconds % 3600) / 60
            val s = seconds % 60
            return if (h > 0) {
                String.format("%d:%02d:%02d", h, m, s)
            } else {
                String.format("%d:%02d", m, s)
            }
        }

        fun fromJson(raw: String?): CallPayload? {
            if (raw.isNullOrBlank()) return null
            return try {
                val o = JSONObject(raw)
                CallPayload(
                    audioId = o.optString("audioId"),
                    senderName = o.optString("senderName"),
                    senderNumber = o.optString("senderNumber"),
                    audioUrl = o.optString("audioUrl"),
                    durationSec = o.optInt("durationSec", 0),
                ).takeIf { it.audioUrl.isNotBlank() }
            } catch (e: Exception) {
                null
            }
        }
    }
}
