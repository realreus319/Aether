package com.zhousl.aether.channel

import java.security.MessageDigest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

enum class ChannelKind(val storageValue: String, val displayName: String) {
    Feishu("feishu", "Feishu"),
    DingTalk("dingtalk", "DingTalk"),
    WeChat("wechat", "WeChat"),
    WeCom("wecom", "WeCom");

    companion object {
        fun fromStorage(value: String): ChannelKind? = entries.firstOrNull { it.storageValue == value }
    }
}

enum class ChannelAccessMode { Open, AllowList, Disabled }

data class ChannelAccessPolicy(
    val mode: ChannelAccessMode = ChannelAccessMode.AllowList,
    val allowedUserIds: Set<String> = emptySet(),
)

/** Platform credentials stay in Android private storage and never enter AgentHarness. */
data class ChannelConfig(
    val kind: ChannelKind,
    val enabled: Boolean = false,
    val appId: String = "",
    val appSecret: String = "",
    val token: String = "",
    val baseUrl: String = "",
    val accessPolicy: ChannelAccessPolicy = ChannelAccessPolicy(),
    val mergeWindowMillis: Long = 600,
) {
    val isConfigured: Boolean
        get() = when (kind) {
            ChannelKind.Feishu, ChannelKind.DingTalk, ChannelKind.WeCom ->
                appId.isNotBlank() && appSecret.isNotBlank()
            ChannelKind.WeChat -> token.isNotBlank()
        }

    fun toJson(): JSONObject = JSONObject()
        .put("kind", kind.storageValue)
        .put("enabled", enabled)
        .put("appId", appId)
        .put("appSecret", appSecret)
        .put("token", token)
        .put("baseUrl", baseUrl)
        .put("accessMode", accessPolicy.mode.name)
        .put("allowedUserIds", JSONArray(accessPolicy.allowedUserIds.toList()))
        .put("mergeWindowMillis", mergeWindowMillis)

    companion object {
        fun default(kind: ChannelKind) = ChannelConfig(
            kind = kind,
            baseUrl = when (kind) {
                ChannelKind.Feishu -> "https://open.feishu.cn"
                ChannelKind.DingTalk -> "https://api.dingtalk.com"
                ChannelKind.WeChat -> "https://ilinkai.weixin.qq.com"
                ChannelKind.WeCom -> "wss://openws.work.weixin.qq.com"
            },
        )

        fun fromJson(json: JSONObject): ChannelConfig? {
            val kind = ChannelKind.fromStorage(json.optString("kind")) ?: return null
            val users = json.optJSONArray("allowedUserIds") ?: JSONArray()
            return ChannelConfig(
                kind = kind,
                enabled = json.optBoolean("enabled"),
                appId = json.optString("appId"),
                appSecret = json.optString("appSecret"),
                token = json.optString("token"),
                baseUrl = json.optString("baseUrl").ifBlank { default(kind).baseUrl },
                accessPolicy = ChannelAccessPolicy(
                    mode = runCatching {
                        ChannelAccessMode.valueOf(json.optString("accessMode"))
                    }.getOrDefault(ChannelAccessMode.AllowList),
                    allowedUserIds = buildSet {
                        repeat(users.length()) { users.optString(it).trim().takeIf(String::isNotEmpty)?.let(::add) }
                    },
                ),
                mergeWindowMillis = json.optLong("mergeWindowMillis", 600).coerceIn(0, 5_000),
            )
        }
    }
}

enum class ChannelConnectionState { Disabled, Starting, Connected, Reconnecting, Error }

data class ChannelStatus(
    val kind: ChannelKind,
    val state: ChannelConnectionState = ChannelConnectionState.Disabled,
    val detail: String = "",
    val updatedAtMillis: Long = System.currentTimeMillis(),
)

data class ChannelAddress(
    val conversationId: String,
    val userId: String,
    val replyToken: String = "",
)

data class ChannelIncomingMessage(
    val channel: ChannelKind,
    val messageId: String,
    val address: ChannelAddress,
    val text: String,
    val receivedAtMillis: Long = System.currentTimeMillis(),
) {
    val sessionId: String by lazy {
        val input = "${channel.storageValue}:${address.conversationId}"
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        "channel:${channel.storageValue}:${digest.take(12).joinToString("") { "%02x".format(it) }}"
    }
}

data class ChannelReply(
    val address: ChannelAddress,
    val text: String,
    val isFinal: Boolean = true,
)

interface AetherChannel {
    val kind: ChannelKind
    val supportsStreamingReplies: Boolean get() = false
    val status: StateFlow<ChannelStatus>
    val incomingMessages: Flow<ChannelIncomingMessage>
    suspend fun start()
    suspend fun stop()
    suspend fun send(reply: ChannelReply)
}
