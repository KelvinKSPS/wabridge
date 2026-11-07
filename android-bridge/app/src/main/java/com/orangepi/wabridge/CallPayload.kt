package com.orangepi.wabridge

import org.json.JSONObject

/** Evento NEW_VOICE_NOTE enviado pelo servidor. */
data class CallPayload(
    val audioId: String,
    val senderName: String,
    val senderNumber: String,
    val audioUrl: String,
) {
    fun toJson(): String = JSONObject().apply {
        put("type", "NEW_VOICE_NOTE")
        put("audioId", audioId)
        put("senderName", senderName)
        put("senderNumber", senderNumber)
        put("audioUrl", audioUrl)
    }.toString()

    companion object {
        fun fromJson(raw: String?): CallPayload? {
            if (raw.isNullOrBlank()) return null
            return try {
                val o = JSONObject(raw)
                CallPayload(
                    audioId = o.optString("audioId"),
                    senderName = o.optString("senderName"),
                    senderNumber = o.optString("senderNumber"),
                    audioUrl = o.optString("audioUrl"),
                ).takeIf { it.audioUrl.isNotBlank() }
            } catch (e: Exception) {
                null
            }
        }
    }
}
