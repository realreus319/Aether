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

/**
 * User-visible Agent event controls. Defaults intentionally match QwenPaw:
 * rich events are visible, while remote streaming is opt-in.
 */
data class ChannelDisplayOptions(
    val showToolCalls: Boolean = true,
    val showToolResults: Boolean = true,
    val showThinking: Boolean = true,
    val streamingEnabled: Boolean = false,
    val toolCallMaxLength: Int = 200,
    val toolResultMaxLength: Int = 500,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("showToolCalls", showToolCalls)
        .put("showToolResults", showToolResults)
        .put("showThinking", showThinking)
        .put("streamingEnabled", streamingEnabled)
        .put("toolCallMaxLength", toolCallMaxLength)
        .put("toolResultMaxLength", toolResultMaxLength)

    companion object {
        fun fromJson(json: JSONObject?): ChannelDisplayOptions {
            if (json == null) return ChannelDisplayOptions()
            return ChannelDisplayOptions(
                showToolCalls = json.optBoolean("showToolCalls", true),
                showToolResults = json.optBoolean("showToolResults", true),
                showThinking = json.optBoolean("showThinking", true),
                streamingEnabled = json.optBoolean("streamingEnabled", false),
                toolCallMaxLength = json.optInt("toolCallMaxLength", 200).coerceIn(0, 20_000),
                toolResultMaxLength = json.optInt("toolResultMaxLength", 500).coerceIn(0, 50_000),
            )
        }
    }
}

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
    /**
     * Keep media-only messages until a following text message arrives. This is
     * the QwenPaw-compatible default and is intentionally enabled for migrated
     * configurations as well.
     */
    val noTextDebounce: Boolean = true,
    val display: ChannelDisplayOptions = ChannelDisplayOptions(),
    /** DingTalk robot code is distinct from the Stream client ID for some applications. */
    val robotCode: String = "",
    /** DingTalk AI Card template used when streaming is enabled. */
    val cardTemplateId: String = "",
    val cardTemplateKey: String = "content",
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
        .put("noTextDebounce", noTextDebounce)
        .put("display", display.toJson())
        .put("robotCode", robotCode)
        .put("cardTemplateId", cardTemplateId)
        .put("cardTemplateKey", cardTemplateKey)

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
                noTextDebounce = json.optBoolean("noTextDebounce", true),
                display = ChannelDisplayOptions.fromJson(json.optJSONObject("display")),
                robotCode = json.optString("robotCode"),
                cardTemplateId = json.optString("cardTemplateId"),
                cardTemplateKey = json.optString("cardTemplateKey").ifBlank { "content" },
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
    val attributes: Map<String, String> = emptyMap(),
)

enum class ChannelIncomingAttachmentKind { Image, Audio, Video, File }

private fun channelIncomingKindForMime(mimeType: String): ChannelIncomingAttachmentKind = when {
    mimeType.startsWith("image/", ignoreCase = true) -> ChannelIncomingAttachmentKind.Image
    mimeType.startsWith("audio/", ignoreCase = true) -> ChannelIncomingAttachmentKind.Audio
    mimeType.startsWith("video/", ignoreCase = true) -> ChannelIncomingAttachmentKind.Video
    else -> ChannelIncomingAttachmentKind.File
}

/**
 * Media received from a platform. Adapters download the short-lived platform
 * resource before emitting this value; ChannelManager imports the bytes into
 * the session workspace before they are passed to the agent.
 */
data class ChannelIncomingAttachment(
    val id: String,
    val name: String,
    val mimeType: String,
    val bytes: ByteArray,
    val kind: ChannelIncomingAttachmentKind = channelIncomingKindForMime(mimeType),
    val workspacePath: String = "",
    val inlineBase64: String = "",
    val declaredSizeBytes: Long? = null,
) {
    val sizeBytes: Long get() = declaredSizeBytes ?: bytes.size.toLong()
    val isPrepared: Boolean get() = workspacePath.isNotBlank()
}

data class ChannelIncomingMessage(
    val channel: ChannelKind,
    val messageId: String,
    val address: ChannelAddress,
    val text: String = "",
    val attachments: List<ChannelIncomingAttachment> = emptyList(),
    val receivedAtMillis: Long = System.currentTimeMillis(),
) {
    val hasText: Boolean get() = text.trim().isNotEmpty()
    val hasAttachments: Boolean get() = attachments.isNotEmpty()
    val hasAudioAttachment: Boolean
        get() = attachments.any { it.kind == ChannelIncomingAttachmentKind.Audio }

    val sessionId: String by lazy {
        val input = "${channel.storageValue}:${address.conversationId}"
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        "channel:${channel.storageValue}:${digest.take(12).joinToString("") { "%02x".format(it) }}"
    }
}

/**
 * Selects the platform transport for one outgoing reply.
 *
 * QwenPaw keeps ordinary text/tool events on the channel's rich-text
 * transport, while only reasoning/message deltas use a live streaming card
 * when the channel supports it. Keeping this choice on the reply prevents a
 * channel from accidentally turning every message into a stream just because
 * streaming is enabled in its settings.
 */
enum class ChannelReplyDelivery {
    /** A completed message rendered by the channel's normal rich-text path. */
    RichText,

    /** An incremental assistant/reasoning update (or its final card update). */
    Streaming,
}

data class ChannelReply(
    val address: ChannelAddress,
    val text: String = "",
    val files: List<ChannelFile> = emptyList(),
    val isFinal: Boolean = true,
    val delivery: ChannelReplyDelivery = ChannelReplyDelivery.RichText,
)

enum class ChannelFileKind { Image, Audio, Video, File }

data class ChannelFile(
    val name: String,
    val mimeType: String,
    val bytes: ByteArray,
) {
    val kind: ChannelFileKind
        get() = when {
            mimeType.startsWith("image/") -> ChannelFileKind.Image
            mimeType.startsWith("audio/") -> ChannelFileKind.Audio
            mimeType.startsWith("video/") -> ChannelFileKind.Video
            else -> ChannelFileKind.File
        }
}

data class ChannelSendReceipt(
    /** Platform message/card identifier, when the transport exposes one. */
    val messageId: String = "",
)

interface AetherChannel {
    val kind: ChannelKind
    val supportsStreamingReplies: Boolean get() = false
    val status: StateFlow<ChannelStatus>
    val incomingMessages: Flow<ChannelIncomingMessage>
    suspend fun start()
    suspend fun stop()
    suspend fun onProcessing(message: ChannelIncomingMessage) = Unit
    suspend fun onCompleted(message: ChannelIncomingMessage, receipt: ChannelSendReceipt) = Unit
    suspend fun onFailed(message: ChannelIncomingMessage) = Unit
    suspend fun send(reply: ChannelReply): ChannelSendReceipt
}
