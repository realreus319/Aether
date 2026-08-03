package com.zhousl.aether.channel

import java.util.LinkedHashMap

internal class ChannelMessageDeduplicator(
    private val ttlMillis: Long = 10 * 60_000L,
    private val maxEntries: Int = 2_048,
) {
    private val seen = LinkedHashMap<String, Long>(16, .75f, true)

    @Synchronized
    fun accept(channel: ChannelKind, messageId: String, nowMillis: Long = System.currentTimeMillis()): Boolean {
        val key = "${channel.storageValue}:$messageId"
        seen.entries.removeAll { nowMillis - it.value > ttlMillis }
        if (seen.containsKey(key)) return false
        seen[key] = nowMillis
        while (seen.size > maxEntries) seen.remove(seen.entries.first().key)
        return true
    }
}

internal object ChannelAccessController {
    fun isAllowed(policy: ChannelAccessPolicy, userId: String): Boolean = when (policy.mode) {
        ChannelAccessMode.Disabled -> false
        ChannelAccessMode.Open -> true
        ChannelAccessMode.AllowList -> userId.isNotBlank() && userId in policy.allowedUserIds
    }
}

/**
 * QwenPaw-compatible media-only input state. A null result means that the
 * message was intentionally held for a later text message.
 */
internal class ChannelNoTextDebouncer {
    private val pending = mutableMapOf<String, MutableList<ChannelIncomingMessage>>()

    @Synchronized
    fun offer(
        sessionId: String,
        enabled: Boolean,
        message: ChannelIncomingMessage,
    ): List<ChannelIncomingMessage>? {
        if (
            enabled &&
            message.hasAttachments &&
            !message.hasText &&
            !message.hasAudioAttachment
        ) {
            pending.getOrPut(sessionId) { mutableListOf() }.add(message)
            return null
        }
        val previous = pending.remove(sessionId).orEmpty()
        return (previous + message).toList()
    }

    @Synchronized
    fun clear() {
        pending.clear()
    }

    @Synchronized
    fun pendingCount(sessionId: String): Int = pending[sessionId]?.size ?: 0
}

internal fun String.normalizedChannelReply(maxChars: Int = 12_000): String =
    trim().ifBlank { "Aether completed the turn without a text response." }.take(maxChars)
