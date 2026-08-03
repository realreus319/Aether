package com.zhousl.aether.channel

import java.io.IOException
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject

internal val JsonMediaType = "application/json; charset=utf-8".toMediaType()

internal suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : okhttp3.Callback {
        override fun onFailure(call: Call, error: IOException) {
            if (continuation.isActive) continuation.resumeWithException(error)
        }

        override fun onResponse(call: Call, response: Response) {
            if (continuation.isActive) continuation.resume(response) else response.close()
        }
    })
}

internal suspend fun OkHttpClient.postJson(
    url: String,
    json: JSONObject,
    headers: Map<String, String> = emptyMap(),
): JSONObject {
    val request = Request.Builder().url(url).post(json.toString().toRequestBody(JsonMediaType)).apply {
        headers.forEach { (name, value) -> header(name, value) }
    }.build()
    return newCall(request).awaitResponse().use { response ->
        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) error("HTTP ${response.code}: ${body.take(300)}")
        if (body.isBlank()) JSONObject() else JSONObject(body)
    }
}

internal suspend fun OkHttpClient.getText(
    url: String,
    headers: Map<String, String> = emptyMap(),
): String {
    val request = Request.Builder().url(url).get().apply {
        headers.forEach { (name, value) -> header(name, value) }
    }.build()
    return newCall(request).awaitResponse().use { response ->
        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) error("HTTP ${response.code}: ${body.take(300)}")
        body
    }
}

internal suspend fun OkHttpClient.getBytes(
    url: String,
    headers: Map<String, String> = emptyMap(),
    maxBytes: Int = 32 * 1024 * 1024,
): ByteArray = withContext(Dispatchers.IO) {
    require(maxBytes > 0) { "maxBytes must be greater than zero" }
    val request = Request.Builder().url(url).get().apply {
        headers.forEach { (name, value) -> header(name, value) }
    }.build()
    newCall(request).awaitResponse().use { response ->
        if (!response.isSuccessful) {
            error("HTTP ${response.code}: ${response.message}")
        }
        val contentLength = response.body?.contentLength() ?: -1L
        require(contentLength <= maxBytes || contentLength < 0) {
            "Downloaded media exceeds the $maxBytes byte limit"
        }
        val output = ByteArrayOutputStream(
            contentLength.takeIf { it in 0..maxBytes.toLong() }?.toInt() ?: 8 * 1024
        )
        response.body?.byteStream()?.use { input ->
            val buffer = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                require(total <= maxBytes) {
                    "Downloaded media exceeds the $maxBytes byte limit"
                }
                output.write(buffer, 0, read)
            }
        } ?: error("HTTP response contained no media body")
        output.toByteArray()
    }
}

internal suspend fun OkHttpClient.getJson(
    url: String,
    headers: Map<String, String> = emptyMap(),
): JSONObject = getText(url, headers).let { body ->
    if (body.isBlank()) JSONObject() else JSONObject(body)
}

internal suspend fun OkHttpClient.postForm(
    url: String,
    fields: Map<String, String>,
    headers: Map<String, String> = emptyMap(),
): JSONObject {
    val form = FormBody.Builder().apply {
        fields.forEach { (name, value) -> add(name, value) }
    }.build()
    val request = Request.Builder().url(url).post(form).apply {
        headers.forEach { (name, value) -> header(name, value) }
    }.build()
    return newCall(request).awaitResponse().use { response ->
        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) error("HTTP ${response.code}: ${body.take(300)}")
        if (body.isBlank()) JSONObject() else JSONObject(body)
    }
}
