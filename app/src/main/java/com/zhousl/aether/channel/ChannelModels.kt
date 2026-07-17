package com.zhousl.aether.channel

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Supported external messaging transports. */
enum class ChannelType(
    val storageValue: String,
    val displayName: String,
) {
    Feishu("feishu", "Feishu"),
    DingTalk("dingtalk", "DingTalk"),
    WeChat("wechat", "WeChat");

    companion object {
        fun fromStorage(value: String?): ChannelType? = entries.firstOrNull {
            it.storageValue == value?.trim()?.lowercase(Locale.US)
        }
    }
}

enum class ChannelConnectionStatus {
    Disabled,
    Connecting,
    Connected,
    Reconnecting,
    AwaitingAuthentication,
    Error,
    Stopped;

    companion object {
        fun fromBridge(value: String?): ChannelConnectionStatus = when (
            value?.trim()?.lowercase(Locale.US)
        ) {
            "connecting" -> Connecting
            "connected" -> Connected
            "reconnecting" -> Reconnecting
            "awaiting_authentication", "auth_required" -> AwaitingAuthentication
            "error", "unhealthy" -> Error
            "stopped" -> Stopped
            else -> Disabled
        }
    }
}

data class ChannelConnectionState(
    val accountId: String,
    val channelType: ChannelType,
    val status: ChannelConnectionStatus = ChannelConnectionStatus.Disabled,
    val detail: String = "",
    val updatedAtMillis: Long = System.currentTimeMillis(),
)

data class ChannelAccountConfig(
    val id: String,
    val type: ChannelType,
    val displayName: String = type.displayName,
    val enabled: Boolean = false,
    val credentials: Map<String, String> = emptyMap(),
    val options: Map<String, String> = emptyMap(),
    val createdAtMillis: Long = System.currentTimeMillis(),
    val updatedAtMillis: Long = createdAtMillis,
) {
    fun toJson(includeCredentials: Boolean = true): JSONObject = JSONObject().apply {
        put("account_id", id)
        put("type", type.storageValue)
        put("display_name", displayName)
        put("enabled", enabled)
        put("credentials", if (includeCredentials) credentials.toJsonObject() else JSONObject())
        put("credential_fields", JSONArray(credentials.keys.sorted()))
        put("options", options.toJsonObject())
        put("created_at_millis", createdAtMillis)
        put("updated_at_millis", updatedAtMillis)
    }

    fun toPublicJson(): JSONObject = JSONObject().apply {
        put("account_id", id)
        put("type", type.storageValue)
        put("display_name", displayName)
        put("enabled", enabled)
        put("configured", credentials.values.any(String::isNotBlank))
        put("credential_fields", JSONArray(credentials.filterValues(String::isNotBlank).keys.sorted()))
        put("options", options.toJsonObject())
        put("created_at_millis", createdAtMillis)
        put("updated_at_millis", updatedAtMillis)
    }

    companion object {
        fun fromJson(json: JSONObject): ChannelAccountConfig? {
            val type = ChannelType.fromStorage(json.optString("type")) ?: return null
            val id = json.optString("account_id")
                .trim()
                .ifBlank { json.optString("id").trim() }
            if (id.isBlank()) return null
            val createdAt = json.optLong("created_at_millis", System.currentTimeMillis())
            return ChannelAccountConfig(
                id = id,
                type = type,
                displayName = json.optString("display_name").trim().ifBlank { type.displayName },
                enabled = json.optBoolean("enabled", false),
                credentials = json.optJSONObject("credentials").toStringMap(),
                options = json.optJSONObject("options").toStringMap(),
                createdAtMillis = createdAt,
                updatedAtMillis = json.optLong("updated_at_millis", createdAt),
            )
        }
    }
}

data class ChannelAddress(
    val kind: String,
    val id: String,
    val extra: Map<String, String> = emptyMap(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("kind", kind)
        put("id", id)
        put("extra", extra.toJsonObject())
    }

    companion object {
        fun fromJson(json: JSONObject?): ChannelAddress? {
            if (json == null) return null
            val id = json.optString("id").trim()
            if (id.isBlank()) return null
            return ChannelAddress(
                kind = json.optString("kind").trim().ifBlank { "conversation" },
                id = id,
                extra = json.optJSONObject("extra").toStringMap(),
            )
        }
    }
}

data class ChannelInboundMessage(
    val accountId: String,
    val channelType: ChannelType,
    val messageId: String,
    val senderId: String,
    val senderName: String = "",
    val conversationId: String,
    val isGroup: Boolean,
    val botMentioned: Boolean,
    val text: String,
    val replyAddress: ChannelAddress,
    val metadata: Map<String, String> = emptyMap(),
) {
    fun resolveSessionId(account: ChannelAccountConfig): String {
        val accountPart = account.id.toSessionSegment()
        val senderPart = senderId.toSessionSegment().ifBlank { "unknown" }
        val conversationPart = conversationId.toSessionSegment().ifBlank { senderPart }
        if (!isGroup) {
            return "channel:${channelType.storageValue}:$accountPart:user:$senderPart"
        }
        val shareSession = account.options["share_session_in_group"]
            ?.toBooleanStrictOrNull()
            ?: channelType.defaultShareSessionInGroup()
        return if (shareSession) {
            "channel:${channelType.storageValue}:$accountPart:group:$conversationPart"
        } else {
            "channel:${channelType.storageValue}:$accountPart:group:$conversationPart:user:$senderPart"
        }
    }

    fun sessionTitle(account: ChannelAccountConfig): String {
        val peer = senderName.trim().ifBlank { senderId.trim() }.ifBlank { conversationId }
        return "${account.displayName} · ${peer.take(48)}"
    }

    companion object {
        fun fromBridge(payload: JSONObject): ChannelInboundMessage? {
            val type = ChannelType.fromStorage(payload.optString("channel_type")) ?: return null
            val accountId = payload.optString("account_id").trim()
            val senderId = payload.optString("sender_id").trim()
            val conversationId = payload.optString("conversation_id").trim().ifBlank { senderId }
            val address = ChannelAddress.fromJson(payload.optJSONObject("reply_address")) ?: return null
            if (accountId.isBlank() || senderId.isBlank()) return null
            return ChannelInboundMessage(
                accountId = accountId,
                channelType = type,
                messageId = payload.optString("message_id").trim(),
                senderId = senderId,
                senderName = payload.optString("sender_name").trim(),
                conversationId = conversationId,
                isGroup = payload.optBoolean("is_group", false),
                botMentioned = payload.optBoolean("bot_mentioned", false),
                text = payload.optString("text"),
                replyAddress = address,
                metadata = payload.optJSONObject("metadata").toStringMap(),
            )
        }
    }
}

internal fun parseChannelAccounts(rawValue: String): List<ChannelAccountConfig> {
    if (rawValue.isBlank()) return emptyList()
    return runCatching {
        val array = JSONArray(rawValue)
        buildList {
            for (index in 0 until array.length()) {
                ChannelAccountConfig.fromJson(array.optJSONObject(index) ?: continue)?.let(::add)
            }
        }.distinctBy(ChannelAccountConfig::id)
    }.getOrDefault(emptyList())
}

internal fun serializeChannelAccounts(accounts: List<ChannelAccountConfig>): String =
    JSONArray().apply {
        accounts.forEach { put(it.toJson(includeCredentials = true)) }
    }.toString()

private fun ChannelType.defaultShareSessionInGroup(): Boolean = when (this) {
    ChannelType.Feishu -> false
    ChannelType.DingTalk,
    ChannelType.WeChat,
    -> true
}

private fun String.toSessionSegment(): String = trim()
    .replace(Regex("[^A-Za-z0-9._-]"), "_")
    .trim('_')
    .take(120)

internal fun JSONObject?.toStringMap(): Map<String, String> {
    if (this == null) return emptyMap()
    return buildMap {
        keys().forEach { key ->
            val value = opt(key)
            if (value != null && value != JSONObject.NULL) {
                put(key, value.toString())
            }
        }
    }
}

private fun Map<String, String>.toJsonObject(): JSONObject = JSONObject().apply {
    this@toJsonObject.toSortedMap().forEach { (key, value) -> put(key, value) }
}
