package com.orangepi.wabridge

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

/** Envia a gravação do relógio para o servidor (POST /reply/audio). */
object Uploader {
    private const val TAG = "Uploader"

    private val client = OkHttpClient.Builder()
        .callTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .build()

    fun sendReply(baseUrl: String, audioId: String, to: String, file: File): Boolean {
        return try {
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("audioId", audioId)
                .addFormDataPart("to", to)
                .addFormDataPart(
                    "file",
                    file.name,
                    file.asRequestBody("audio/mp4".toMediaType()),
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
}
