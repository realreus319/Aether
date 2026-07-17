package com.zhousl.aether.channel

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelModelsTest {
    @Test
    fun directMessagesUseStableAccountAndSenderSession() {
        val account = ChannelAccountConfig(
            id = "company-main",
            type = ChannelType.Feishu,
            enabled = true,
        )
        val message = inbound(
            accountId = account.id,
            type = account.type,
            sender = "ou_user_1",
            conversation = "oc_chat_1",
            isGroup = false,
        )

        assertEquals(
            "channel:feishu:company-main:user:ou_user_1",
            message.resolveSessionId(account),
        )
    }

    @Test
    fun feishuGroupDefaultsToPerMemberSession() {
        val account = ChannelAccountConfig(id = "main", type = ChannelType.Feishu)
        val message = inbound("main", ChannelType.Feishu, "user-a", "group-a", true)

        assertEquals(
            "channel:feishu:main:group:group-a:user:user-a",
            message.resolveSessionId(account),
        )
    }

    @Test
    fun sharedGroupOptionUsesOneConversationSession() {
        val account = ChannelAccountConfig(
            id = "main",
            type = ChannelType.Feishu,
            options = mapOf("share_session_in_group" to "true"),
        )
        val first = inbound("main", ChannelType.Feishu, "user-a", "group-a", true)
        val second = inbound("main", ChannelType.Feishu, "user-b", "group-a", true)

        assertEquals(first.resolveSessionId(account), second.resolveSessionId(account))
        assertEquals("channel:feishu:main:group:group-a", first.resolveSessionId(account))
    }

    @Test
    fun publicAccountJsonNeverContainsCredentialValues() {
        val account = ChannelAccountConfig(
            id = "ding-main",
            type = ChannelType.DingTalk,
            credentials = mapOf("client_id" to "ding-id", "client_secret" to "super-secret"),
        )
        val text = account.toPublicJson().toString()

        assertTrue(text.contains("client_secret"))
        assertFalse(text.contains("super-secret"))
        assertFalse(text.contains("ding-id"))
    }

    @Test
    fun parsesBridgeEnvelope() {
        val parsed = ChannelInboundMessage.fromBridge(
            JSONObject()
                .put("account_id", "wechat-main")
                .put("channel_type", "wechat")
                .put("message_id", "m1")
                .put("sender_id", "wx-user")
                .put("conversation_id", "wx-user")
                .put("text", "hello")
                .put(
                    "reply_address",
                    JSONObject()
                        .put("kind", "dm")
                        .put("id", "wx-user")
                        .put("extra", JSONObject().put("context_token", "token")),
                ),
        )

        requireNotNull(parsed)
        assertEquals(ChannelType.WeChat, parsed.channelType)
        assertEquals("hello", parsed.text)
        assertEquals("token", parsed.replyAddress.extra["context_token"])
    }

    private fun inbound(
        accountId: String,
        type: ChannelType,
        sender: String,
        conversation: String,
        isGroup: Boolean,
    ) = ChannelInboundMessage(
        accountId = accountId,
        channelType = type,
        messageId = "message-1",
        senderId = sender,
        conversationId = conversation,
        isGroup = isGroup,
        botMentioned = true,
        text = "hello",
        replyAddress = ChannelAddress("conversation", conversation),
    )
}
