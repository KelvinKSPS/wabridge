package com.orangepi.wabridge

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** Sends the watch recording to the server (POST /reply/audio). */
object Uploader {
    private const val TAG = "Uploader"

    private val client = OkHttpClient.Builder()
        .callTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .build()

    fun sendReply(baseUrl: String, audioId: String, to: String, file: File, durationSec: Int = 0): Boolean {
        return try {
            val mime = if (file.name.endsWith(".ogg")) "audio/ogg; codecs=opus" else "audio/mp4"
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("audioId", audioId)
                .addFormDataPart("to", to)
                .addFormDataPart("mimeType", mime)
                .addFormDataPart("durationSec", durationSec.toString())
                .addFormDataPart(
                    "file",
                    file.name,
                    file.asRequestBody(mime.toMediaType()),
                )
                .build()

            val request = Request.Builder()
                .url(WsUrls.httpUrl(baseUrl, "/reply/audio"))
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                Log.i(TAG, "reply/audio -> ${response.code}")
                response.isSuccessful
            }
        } catch (e: Exception) {
            Log.e(TAG, "falha no upload: ${e.message}")
            false
        }
    }

    /**
     * Sends a quick text reply (POST /reply/text).
     *
     * It is the "Can't answer right now" option in the menu: one tap and the contact
     * receives the message, without having to speak.
     */
    fun sendText(baseUrl: String, audioId: String, to: String, text: String): Boolean {
        return try {
            val json = JSONObject()
                .put("audioId", audioId)
                .put("to", to)
                .put("text", text)
                .toString()
                .toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url(WsUrls.httpUrl(baseUrl, "/reply/text"))
                .post(json)
                .build()

            client.newCall(request).execute().use { response ->
                Log.i(TAG, "reply/text -> ${response.code}")
                response.isSuccessful
            }
        } catch (e: Exception) {
            Log.e(TAG, "falha no envio do texto: ${e.message}")
            false
        }
    }
}
