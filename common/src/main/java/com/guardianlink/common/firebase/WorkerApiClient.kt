package com.guardianlink.common.firebase

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

object WorkerApiClient {
    private val client = OkHttpClient.Builder()
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun postJson(
        baseUrl: String,
        path: String,
        bearerToken: String?,
        values: Map<String, Any?>
    ): JSONObject = execute(
        Request.Builder()
            .url(baseUrl.trimEnd('/') + path)
            .apply { bearerToken?.let { header("Authorization", "Bearer $it") } }
            .post(JSONObject(values).toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
    )

    suspend fun getJson(
        baseUrl: String,
        path: String,
        bearerToken: String
    ): JSONObject = execute(
        Request.Builder()
            .url(baseUrl.trimEnd('/') + path)
            .header("Authorization", "Bearer $bearerToken")
            .get()
            .build()
    )

    suspend fun delete(
        baseUrl: String,
        path: String,
        bearerToken: String
    ): JSONObject = execute(
        Request.Builder()
            .url(baseUrl.trimEnd('/') + path)
            .header("Authorization", "Bearer $bearerToken")
            .delete()
            .build()
    )

    suspend fun putBytes(
        baseUrl: String,
        path: String,
        bearerToken: String,
        contentType: String,
        bytes: ByteArray
    ): JSONObject = execute(
        Request.Builder()
            .url(baseUrl.trimEnd('/') + path)
            .header("Authorization", "Bearer $bearerToken")
            .put(bytes.toRequestBody(contentType.toMediaType()))
            .build()
    )

    suspend fun getBytes(
        baseUrl: String,
        path: String,
        bearerToken: String
    ): ByteArray = withContext(Dispatchers.IO) {
        client.newCall(
            Request.Builder()
                .url(baseUrl.trimEnd('/') + path)
                .header("Authorization", "Bearer $bearerToken")
                .get()
                .build()
        ).execute().use { response ->
            if (!response.isSuccessful) {
                val body = response.body?.string().orEmpty()
                val message = runCatching { JSONObject(body).optString("error") }
                    .getOrDefault(body)
                throw IOException(message.ifBlank { "Worker request failed (${response.code})" })
            }
            response.body?.bytes() ?: throw IOException("Worker returned an empty media response")
        }
    }

    private suspend fun execute(request: Request): JSONObject = withContext(Dispatchers.IO) {
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val message = runCatching { JSONObject(body).optString("error") }
                    .getOrDefault(body)
                throw IOException(message.ifBlank { "Worker request failed (${response.code})" })
            }
            if (body.isBlank()) JSONObject() else JSONObject(body)
        }
    }
}