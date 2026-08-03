package com.zhousl.aether.channel

import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/**
 * Owns platform lifecycles and routes every external conversation through the same
 * SessionExecutionManager entry point as Compose. Per-conversation actors preserve order.
 */
class ChannelManager(
    private val scope: CoroutineScope,
    private val configRepository: ChannelConfigRepository,
    private val processor: SessionAgentProcessor,
    private val attachmentImporter: ChannelAttachmentImporter = ChannelAttachmentImporter { _, _attachment ->
        Result.failure(IllegalStateException("Inbound channel attachments are not configured"))
    },
    private val registry: ChannelRegistry = ChannelRegistry(scope),
    private val onKeepAliveRequired: (Boolean) -> Unit = {},
) {
    private data class DispatchEnvelope(val messages: List<ChannelIncomingMessage>)
    private data class Actor(val queue: Channel<DispatchEnvelope>, val job: Job)

    private val lock = Any()
    private val deduplicator = ChannelMessageDeduplicator()
    private val noTextDebouncer = ChannelNoTextDebouncer()
    private val routeLocks = ConcurrentHashMap<String, Mutex>()
    private val channels = mutableMapOf<ChannelKind, AetherChannel>()
    private val channelJobs = mutableListOf<Job>()
    private val sessionActors = ConcurrentHashMap<String, Actor>()
    private val mutableStatuses = MutableStateFlow(
        ChannelKind.entries.associateWith { ChannelStatus(it) }
    )
    val statuses: StateFlow<Map<ChannelKind, ChannelStatus>> = mutableStatuses.asStateFlow()
    private var configJob: Job? = null

    fun start() {
        if (configJob?.isActive == true) return
        configJob = scope.launch {
            configRepository.configs.collect(::reconfigure)
        }
    }

    suspend fun stop() {
        configJob?.cancel()
        configJob = null
        val oldChannels = synchronized(lock) {
            channelJobs.forEach { it.cancel() }
            channelJobs.clear()
            channels.values.toList().also { channels.clear() }
        }
        oldChannels.forEach { runCatching { it.stop() } }
        sessionActors.values.forEach { actor -> actor.queue.close(); actor.job.cancel() }
        sessionActors.clear()
        noTextDebouncer.clear()
        routeLocks.clear()
        onKeepAliveRequired(false)
    }

    private suspend fun reconfigure(configs: List<ChannelConfig>) {
        sessionActors.values.forEach { actor ->
            actor.queue.close()
            actor.job.cancel()
        }
        sessionActors.clear()
        val previous = synchronized(lock) {
            channelJobs.forEach { it.cancel() }
            channelJobs.clear()
            channels.values.toList().also { channels.clear() }
        }
        previous.forEach { runCatching { it.stop() } }

        val enabled = configs.filter { it.enabled && it.isConfigured }
        enabled.forEach { config ->
            val channel = registry.create(config)
            synchronized(lock) { channels[config.kind] = channel }
            channelJobs += scope.launch {
                channel.status.collect { status ->
                    mutableStatuses.value = mutableStatuses.value.toMutableMap().apply { put(config.kind, status) }
                }
            }
            channelJobs += scope.launch {
                channel.incomingMessages.collect { route(config, it) }
            }
            scope.launch {
                runCatching { channel.start() }.onFailure {
                    mutableStatuses.value = mutableStatuses.value.toMutableMap().apply {
                        put(config.kind, ChannelStatus(config.kind, ChannelConnectionState.Error, it.message.orEmpty()))
                    }
                }
            }
        }
        val disabledKinds = ChannelKind.entries - enabled.map { it.kind }.toSet()
        mutableStatuses.value = mutableStatuses.value.toMutableMap().apply {
            disabledKinds.forEach { put(it, ChannelStatus(it)) }
        }
        onKeepAliveRequired(enabled.isNotEmpty())
    }

    private fun route(config: ChannelConfig, message: ChannelIncomingMessage) {
        if (!deduplicator.accept(message.channel, message.messageId)) return
        if (!ChannelAccessController.isAllowed(config.accessPolicy, message.address.userId)) return
        val routeLock = routeLocks.computeIfAbsent(message.sessionId) { Mutex() }
        scope.launch {
            routeLock.lock()
            try {
                val prepared = prepareIncomingMessage(message) ?: return@launch
                val dispatch = noTextDebouncer.offer(message.sessionId, config.noTextDebounce, prepared)
                    ?: return@launch
                if (dispatch.isEmpty()) return@launch
                val actor = sessionActors.computeIfAbsent(message.sessionId) { sessionId ->
                    val queue = Channel<DispatchEnvelope>(Channel.UNLIMITED)
                    val job = scope.launch { consumeSession(sessionId, config, queue) }
                    Actor(queue, job)
                }
                actor.queue.trySend(DispatchEnvelope(dispatch))
            } finally {
                routeLock.unlock()
            }
        }
    }

    private suspend fun prepareIncomingMessage(
        message: ChannelIncomingMessage,
    ): ChannelIncomingMessage? {
        if (message.attachments.isEmpty()) return message
        val prepared = mutableListOf<ChannelIncomingAttachment>()
        for (attachment in message.attachments) {
            if (attachment.isPrepared) {
                prepared += attachment
            } else {
                val imported = try {
                    attachmentImporter.importAttachment(message.sessionId, attachment)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    Result.failure(error)
                }
                val preparedAttachment = imported.getOrNull()
                if (preparedAttachment == null) {
                    val error = imported.exceptionOrNull()
                    val channel = synchronized(lock) { channels[message.channel] }
                    try {
                        channel?.send(
                            ChannelReply(
                                address = message.address,
                                text = "Aether 无法读取这个附件：${error?.message ?: "导入失败"}",
                            )
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        // The platform can disconnect while an attachment is being imported.
                    }
                    continue
                }
                prepared += preparedAttachment
            }
        }
        return message.copy(attachments = prepared).takeIf { it.hasText || it.hasAttachments }
    }

    private suspend fun consumeSession(
        sessionId: String,
        initialConfig: ChannelConfig,
        queue: Channel<DispatchEnvelope>,
    ) {
        try {
            for (firstEnvelope in queue) {
                val messages = firstEnvelope.messages.toMutableList()
                if (initialConfig.mergeWindowMillis > 0) {
                    delay(initialConfig.mergeWindowMillis)
                    while (true) {
                        val next = queue.tryReceive().getOrNull() ?: break
                        messages += next.messages
                    }
                }
                val latest = messages.last()
                val input = messages
                    .map { it.text.trim() }
                    .filter(String::isNotBlank)
                    .joinToString("\n")
                val attachments = messages
                    .flatMap(ChannelIncomingMessage::attachments)
                    .distinctBy(ChannelIncomingAttachment::id)
                val channel = synchronized(lock) { channels[latest.channel] } ?: continue
                val renderer = ChannelMessageRenderer(initialConfig.display)
                var finalText = ""
                var reasoningText = ""
                var failure: String? = null
                var finalReceipt = ChannelSendReceipt()
                val toolTimeline = mutableListOf<String>()
                var latestSnapshot = ""
                val streaming = channel.supportsStreamingReplies &&
                    initialConfig.display.streamingEnabled
                val keepAlive = if (streaming) {
                    scope.launch {
                        var lastSent = ""
                        while (isActive) {
                            val rendered = latestSnapshot.normalizedChannelReply()
                            if (rendered.isNotBlank() && rendered != lastSent) {
                                runCatching {
                                    channel.send(ChannelReply(latest.address, rendered, isFinal = false))
                                }
                                lastSent = rendered
                            }
                            delay(750)
                        }
                    }
                } else {
                    null
                }
                try {
                    messages.forEach { message ->
                        runCatching { channel.onProcessing(message) }
                    }
                    processor.process(
                        SessionAgentRequest(
                            sessionId = sessionId,
                            text = input,
                            sessionTitle = "${latest.channel.displayName} · ${latest.address.conversationId.take(18)}",
                            source = latest.channel.storageValue,
                            attachments = attachments.map { attachment ->
                                SessionAgentAttachment(
                                    id = attachment.id,
                                    name = attachment.name,
                                    mimeType = attachment.mimeType,
                                    sizeBytes = attachment.sizeBytes,
                                    kind = attachment.kind,
                                    workspacePath = attachment.workspacePath,
                                    inlineBase64 = attachment.inlineBase64,
                                )
                            },
                        )
                    ).collect { event ->
                        when (event) {
                            SessionAgentEvent.Started -> Unit
                            is SessionAgentEvent.TextDelta -> {
                                finalText = event.accumulatedText
                                latestSnapshot = renderer.streamingSnapshot(
                                    reasoningText,
                                    toolTimeline,
                                    finalText,
                                )
                            }
                            is SessionAgentEvent.ReasoningDelta -> {
                                reasoningText = event.accumulatedText
                                latestSnapshot = renderer.streamingSnapshot(
                                    reasoningText,
                                    toolTimeline,
                                    finalText,
                                )
                            }
                            is SessionAgentEvent.ToolCall -> {
                                renderer.toolCall(event.name, event.argumentsJson)?.let { rendered ->
                                    toolTimeline += rendered
                                    latestSnapshot = renderer.streamingSnapshot(
                                        reasoningText,
                                        toolTimeline,
                                        finalText,
                                    )
                                    if (!streaming) {
                                        channel.send(ChannelReply(latest.address, rendered))
                                    }
                                }
                            }
                            is SessionAgentEvent.ToolResult -> {
                                renderer.toolResult(event.name, event.output, event.isError)?.let { rendered ->
                                    toolTimeline += rendered
                                    latestSnapshot = renderer.streamingSnapshot(
                                        reasoningText,
                                        toolTimeline,
                                        finalText,
                                    )
                                    if (!streaming) {
                                        channel.send(ChannelReply(latest.address, rendered))
                                    }
                                }
                            }
                            is SessionAgentEvent.FileReady -> {
                                channel.send(
                                    ChannelReply(
                                        address = latest.address,
                                        files = listOf(event.file),
                                    )
                                )
                            }
                            is SessionAgentEvent.Completed -> finalText = event.text
                            is SessionAgentEvent.Failed -> failure = event.message
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    failure = error.message ?: error::class.java.simpleName
                } finally {
                    keepAlive?.cancelAndJoin()
                }
                try {
                    if (!streaming) {
                        renderer.thinking(reasoningText)?.let { rendered ->
                            channel.send(ChannelReply(latest.address, rendered))
                        }
                    }
                    val output = failure?.let { "Aether could not complete this turn: $it" }
                        ?: finalText.normalizedChannelReply()
                    finalReceipt = channel.send(ChannelReply(latest.address, output))
                    if (failure == null) {
                        messages.forEach { message ->
                            channel.onCompleted(message, finalReceipt)
                        }
                    } else {
                        messages.forEach { message -> channel.onFailed(message) }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (sendError: Throwable) {
                    messages.forEach { message ->
                        runCatching { channel.onFailed(message) }
                    }
                }
            }
        } finally {
            sessionActors.remove(sessionId)
        }
    }
}
