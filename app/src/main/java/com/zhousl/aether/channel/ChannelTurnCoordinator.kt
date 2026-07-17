package com.zhousl.aether.channel

import com.zhousl.aether.data.AgentExtensionsRepository
import com.zhousl.aether.data.ChatStateStore
import com.zhousl.aether.data.SessionExecutionManager
import com.zhousl.aether.data.SessionFollowUpMode
import com.zhousl.aether.data.SessionTurnRequest
import com.zhousl.aether.data.SettingsRepository
import com.zhousl.aether.ui.ChatMessage
import com.zhousl.aether.ui.ChatSession
import com.zhousl.aether.ui.MessageAuthor
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * QwenPaw-style process handler for Android: one normalized channel message is
 * mapped to a deterministic Aether session, then handed to the existing Agent
 * execution manager. Replies are correlated in FIFO order per session.
 */
class ChannelTurnCoordinator(
    private val scope: CoroutineScope,
    private val settingsRepository: SettingsRepository,
    private val extensionsRepository: AgentExtensionsRepository,
    private val chatStateStore: ChatStateStore,
    private val sessionExecutionManager: SessionExecutionManager,
    private val sendReply: suspend (PendingChannelReply, String) -> Unit,
) {
    private val replyQueues = ConcurrentHashMap<String, ArrayDeque<PendingChannelReply>>()
    private val dedup = LinkedHashMap<String, Unit>(512, 0.75f, true)

    init {
        scope.launch {
            sessionExecutionManager.turnEvents.collect { event ->
                val pending = popReply(event.sessionId) ?: return@collect
                val session = chatStateStore.state.value.sessions.firstOrNull { it.id == event.sessionId }
                val text = session?.messages
                    ?.asReversed()
                    ?.firstOrNull { it.author == MessageAuthor.Agent && it.text.isNotBlank() }
                    ?.text
                    .orEmpty()
                    .ifBlank { "Aether finished without a text response." }
                runCatching { sendReply(pending, text) }
            }
        }
    }

    suspend fun submit(message: ChannelInboundMessage) {
        if (message.text.isBlank()) return
        if (!markFirstDelivery(message)) return

        val account = ChannelAccountConfig(
            id = message.accountId,
            type = message.channelType,
            displayName = message.metadata["account_display_name"].orEmpty()
                .ifBlank { message.channelType.displayName },
            enabled = true,
            options = buildMap {
                message.metadata["share_session_in_group"]?.let { put("share_session_in_group", it) }
            },
        )
        val sessionId = message.resolveSessionId(account)
        val userMessage = ChatMessage(
            id = "channel-user-${UUID.randomUUID()}",
            author = MessageAuthor.User,
            text = message.text,
            createdAtMillis = System.currentTimeMillis(),
        )
        pushReply(
            sessionId,
            PendingChannelReply(
                accountId = message.accountId,
                channelType = message.channelType,
                address = message.replyAddress,
                sourceMessageId = message.messageId,
            ),
        )

        if (sessionExecutionManager.isSessionRunning(sessionId)) {
            if (!sessionExecutionManager.submitFollowUp(sessionId, userMessage, SessionFollowUpMode.Queue)) {
                popReply(sessionId)
            }
            return
        }

        val settings = settingsRepository.settings.first()
        val providerConfigs = settingsRepository.providerConfigs.first()
        val extensions = extensionsRepository.extensionState.first()
        val current = chatStateStore.state.value
        val existing = current.sessions.firstOrNull { it.id == sessionId }
        val messages = existing?.messages.orEmpty() + userMessage
        val session = (existing ?: ChatSession(
            id = sessionId,
            title = message.sessionTitle(account),
            preview = message.text.take(100),
            messages = emptyList(),
            selectedSkillIds = settings.defaultSelectedSkillIds,
            selectedModelKey = settings.defaultChatModelKey,
        )).copy(
            preview = message.text.take(100),
            messages = messages,
            messageCount = messages.size,
            lastMessageAtMillis = userMessage.createdAtMillis,
        )
        chatStateStore.updateAndFlush { state ->
            state.copy(
                sessions = state.sessions.filterNot { it.id == sessionId } + session,
            )
        }

        sessionExecutionManager.startTurn(
            SessionTurnRequest(
                sessionId = sessionId,
                settings = settings,
                requestMessages = messages,
                selectedSkillIds = session.selectedSkillIds,
                activeSkills = session.activeSkills,
                activeMcpServerIds = session.activeMcpServerIds.filter { serverId ->
                    extensions.mcpServers.any { it.id == serverId && it.isEnabled }
                },
                agentModeEnabled = session.agentModeEnabled,
                providerConfigs = providerConfigs,
            )
        )
    }

    private fun markFirstDelivery(message: ChannelInboundMessage): Boolean {
        val key = "${message.channelType.storageValue}:${message.accountId}:${message.messageId}"
        if (message.messageId.isBlank()) return true
        synchronized(dedup) {
            if (dedup.containsKey(key)) return false
            dedup[key] = Unit
            while (dedup.size > 2_000) dedup.remove(dedup.entries.first().key)
        }
        return true
    }

    private fun pushReply(sessionId: String, reply: PendingChannelReply) {
        val queue = replyQueues.computeIfAbsent(sessionId) { ArrayDeque() }
        synchronized(queue) { queue.addLast(reply) }
    }

    private fun popReply(sessionId: String): PendingChannelReply? {
        val queue = replyQueues[sessionId] ?: return null
        val reply = synchronized(queue) { if (queue.isEmpty()) null else queue.removeFirst() }
        if (synchronized(queue) { queue.isEmpty() }) replyQueues.remove(sessionId, queue)
        return reply
    }
}

data class PendingChannelReply(
    val accountId: String,
    val channelType: ChannelType,
    val address: ChannelAddress,
    val sourceMessageId: String,
)
