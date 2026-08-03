package com.zhousl.aether.channel

import android.util.Base64
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

internal fun channelMimeTypeForName(
    name: String,
    fallback: String = "application/octet-stream",
): String {
    return when (name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "bmp" -> "image/bmp"
        "heic", "heif" -> "image/heic"
        "mp4" -> "video/mp4"
        "mov" -> "video/quicktime"
        "avi" -> "video/x-msvideo"
        "mkv" -> "video/x-matroska"
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "amr" -> "audio/amr"
        "ogg", "opus" -> "audio/ogg"
        "pdf" -> "application/pdf"
        "json" -> "application/json"
        "txt", "log", "md" -> "text/plain"
        "html", "htm" -> "text/html"
        "doc" -> "application/msword"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xls" -> "application/vnd.ms-excel"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "ppt" -> "application/vnd.ms-powerpoint"
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        "zip" -> "application/zip"
        "rar" -> "application/vnd.rar"
        else -> fallback
    }
}

internal fun channelMediaId(prefix: String, value: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
    return "$prefix-${digest.take(20)}"
}

internal fun decryptChannelAesEcb(
    encrypted: ByteArray,
    encodedKey: String,
): ByteArray {
    val key = decodeChannelAesKey(encodedKey)
    val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding").apply {
        init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
    }
    return cipher.doFinal(encrypted)
}

private fun decodeChannelAesKey(raw: String): ByteArray {
    val normalized = raw.trim()
    require(normalized.isNotBlank()) { "Encrypted media did not include an AES key" }
    val hexKey = normalized.removePrefix("0x")
    if (hexKey.length == 32 && hexKey.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
        return ByteArray(16) { index -> hexKey.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
    }
    val decoded = runCatching { Base64.decode(normalized, Base64.DEFAULT) }
        .getOrElse { error("Invalid encrypted media key") }
    return when {
        decoded.size == 16 -> decoded
        decoded.size == 32 && decoded.all { it.toInt().toChar().isDigit() || it.toInt().toChar().lowercaseChar() in 'a'..'f' } ->
            ByteArray(16) { index -> decoded.toString(Charsets.US_ASCII).substring(index * 2, index * 2 + 2).toInt(16).toByte() }
        else -> error("Encrypted media key must decode to 16 bytes")
    }
}
