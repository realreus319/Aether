package com.zhousl.aether.data

import com.zhousl.aether.BuildConfig
import okhttp3.Request
import java.net.HttpURLConnection

private val HttpHeaderNamePattern = Regex("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$")

internal val AetherLlmUserAgent: String
    get() = "Aether/${BuildConfig.VERSION_NAME} (Android)"

internal fun normalizeLlmUserAgent(value: String?): String =
    value?.trim().orEmpty().ifBlank { AetherLlmUserAgent }

internal fun Request.Builder.applyAetherLlmHeaders(
    userAgent: String,
    customHeaders: List<LlmCustomHeader>,
): Request.Builder = apply {
    header("User-Agent", normalizeLlmUserAgent(userAgent))
    customHeaders.normalizedLlmHeaders().forEach { header ->
        header(header.name, header.value)
    }
}

internal fun HttpURLConnection.applyAetherLlmHeaders(
    userAgent: String,
    customHeaders: List<LlmCustomHeader>,
) {
    setRequestProperty("User-Agent", normalizeLlmUserAgent(userAgent))
    customHeaders.normalizedLlmHeaders().forEach { header ->
        setRequestProperty(header.name, header.value)
    }
}

internal fun List<LlmCustomHeader>.normalizedLlmHeaders(): List<LlmCustomHeader> =
    map { header -> LlmCustomHeader(header.name.trim(), header.value) }
        .filter { header ->
            header.name.isNotBlank() &&
                !header.name.equals("User-Agent", ignoreCase = true) &&
                HttpHeaderNamePattern.matches(header.name)
        }
        .distinctBy { header -> header.name.lowercase() }
