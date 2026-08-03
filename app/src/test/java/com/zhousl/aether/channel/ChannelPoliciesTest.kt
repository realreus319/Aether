package com.zhousl.aether.channel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelPoliciesTest {
    @Test
    fun accessPolicyDefaultsToExplicitAllowList() {
        val policy = ChannelAccessPolicy(allowedUserIds = setOf("allowed"))
        assertTrue(ChannelAccessController.isAllowed(policy, "allowed"))
        assertFalse(ChannelAccessController.isAllowed(policy, "unknown"))
    }

    @Test
    fun deduplicatorRejectsReplayButAllowsOtherPlatforms() {
        val dedupe = ChannelMessageDeduplicator()
        assertTrue(dedupe.accept(ChannelKind.Feishu, "same", 100))
        assertFalse(dedupe.accept(ChannelKind.Feishu, "same", 101))
        assertTrue(dedupe.accept(ChannelKind.DingTalk, "same", 102))
    }

    @Test
    fun deduplicatorExpiresOldEntries() {
        val dedupe = ChannelMessageDeduplicator(ttlMillis = 10)
        assertTrue(dedupe.accept(ChannelKind.WeChat, "id", 100))
        assertTrue(dedupe.accept(ChannelKind.WeChat, "id", 111))
    }

    @Test
    fun mediaOnlyMessagesWaitForTextByDefault() {
        val debouncer = ChannelNoTextDebouncer()
        val media = ChannelIncomingMessage(
            channel = ChannelKind.Feishu,
            messageId = "image-1",
            address = ChannelAddress("chat", "user"),
            attachments = listOf(
                ChannelIncomingAttachment("attachment-1", "image.png", "image/png", byteArrayOf(1))
            ),
        )
        val text = media.copy(messageId = "text-1", text = "请分析")

        assertEquals(null, debouncer.offer(media.sessionId, enabled = true, message = media))
        assertEquals(1, debouncer.pendingCount(media.sessionId))
        assertEquals(listOf(media, text), debouncer.offer(media.sessionId, enabled = true, message = text))
    }

    @Test
    fun disablingMediaDebounceProcessesImmediatelyAndAudioBypassesIt() {
        val debouncer = ChannelNoTextDebouncer()
        val media = ChannelIncomingMessage(
            channel = ChannelKind.WeChat,
            messageId = "file-1",
            address = ChannelAddress("chat", "user"),
            attachments = listOf(
                ChannelIncomingAttachment("attachment-1", "file.pdf", "application/pdf", byteArrayOf(1))
            ),
        )
        assertEquals(listOf(media), debouncer.offer(media.sessionId, enabled = false, message = media))

        val audio = media.copy(
            messageId = "audio-1",
            attachments = listOf(
                ChannelIncomingAttachment("attachment-2", "voice.amr", "audio/amr", byteArrayOf(1))
            ),
        )
        assertEquals(listOf(audio), debouncer.offer(audio.sessionId, enabled = true, message = audio))
    }
}
