package com.zhousl.aether.channel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelModelsTest {
    @Test
    fun configRoundTripPreservesCredentialsAndPolicy() {
        val original = ChannelConfig(
            kind = ChannelKind.Feishu,
            enabled = true,
            appId = "app-id",
            appSecret = "secret",
            accessPolicy = ChannelAccessPolicy(
                mode = ChannelAccessMode.AllowList,
                allowedUserIds = setOf("alice", "bob"),
            ),
            mergeWindowMillis = 900,
        )

        assertEquals(original, ChannelConfig.fromJson(original.toJson()))
    }

    @Test
    fun channelSessionIdIsStableAndConversationScoped() {
        fun message(conversation: String) = ChannelIncomingMessage(
            channel = ChannelKind.WeCom,
            messageId = "message",
            address = ChannelAddress(conversation, "user"),
            text = "hello",
        )

        assertEquals(message("chat-a").sessionId, message("chat-a").sessionId)
        assertNotEquals(message("chat-a").sessionId, message("chat-b").sessionId)
        assertTrue(message("chat-a").sessionId.startsWith("channel:wecom:"))
    }

    @Test
    fun configurationRequirementsArePlatformSpecific() {
        assertTrue(ChannelConfig.default(ChannelKind.WeChat).copy(token = "token").isConfigured)
        assertFalse(ChannelConfig.default(ChannelKind.WeChat).copy(appId = "id", appSecret = "secret").isConfigured)
        assertTrue(ChannelConfig.default(ChannelKind.DingTalk).copy(appId = "id", appSecret = "secret").isConfigured)
    }
}
