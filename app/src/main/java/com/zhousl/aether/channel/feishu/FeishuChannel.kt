package com.zhousl.aether.channel.feishu

import com.lark.oapi.Client
import com.lark.oapi.core.request.RequestOptions
import com.lark.oapi.event.EventDispatcher
import com.lark.oapi.service.im.ImService
import com.lark.oapi.service.im.v1.model.CreateMessageReq
import com.lark.oapi.service.im.v1.model.CreateMessageReqBody
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1
import com.zhousl.aether.channel.BaseAetherChannel
import com.zhousl.aether.channel.ChannelAddress
import com.zhousl.aether.channel.ChannelConfig
import com.zhousl.aether.channel.ChannelConnectionState
import com.zhousl.aether.channel.ChannelIncomingMessage
import com.zhousl.aether.channel.ChannelKind
import com.zhousl.aether.channel.ChannelReply
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Feishu long connection using Lark's official SDK; no public webhook is required. */
class FeishuChannel(
    private val config: ChannelConfig,
    private val scope: CoroutineScope,
) : BaseAetherChannel(ChannelKind.Feishu) {
    private val apiClient = Client.newBuilder(config.appId, config.appSecret)
        .openBaseUrl(config.baseUrl.trimEnd('/'))
        .build()
    private var receiveJob: Job? = null
    private var socketClient: com.lark.oapi.ws.Client? = null

    override suspend fun start() {
        if (!config.enabled) return updateStatus(ChannelConnectionState.Disabled)
        if (!config.isConfigured) return updateStatus(ChannelConnectionState.Error, "App ID and secret are required")
        updateStatus(ChannelConnectionState.Starting)
        val dispatcher = EventDispatcher.newBuilder("", "")
            .onP2MessageReceiveV1(
                object : ImService.P2MessageReceiveV1Handler() {
                    override fun handle(envelope: P2MessageReceiveV1) {
                        val event = envelope.event
                        val message = event?.message
                        val sender = event?.sender?.senderId?.openId.orEmpty()
                        if (message?.messageType == "text") {
                            val text = runCatching {
                                JSONObject(message.content.orEmpty()).optString("text")
                            }.getOrDefault("")
                            if (text.isNotBlank()) scope.launch {
                                emitIncoming(
                                    ChannelIncomingMessage(
                                        channel = kind,
                                        messageId = message.messageId.orEmpty(),
                                        address = ChannelAddress(message.chatId.orEmpty(), sender),
                                        text = text,
                                    )
                                )
                            }
                        }
                    }
                }
            )
            .build()
        socketClient = com.lark.oapi.ws.Client.Builder(config.appId, config.appSecret)
            .eventHandler(dispatcher)
            .domain(config.baseUrl.trimEnd('/'))
            .build()
        receiveJob = scope.launch(Dispatchers.IO) {
            runCatching {
                updateStatus(ChannelConnectionState.Connected)
                socketClient?.start()
            }.onFailure { updateStatus(ChannelConnectionState.Error, it.message.orEmpty()) }
        }
    }

    override suspend fun stop() {
        receiveJob?.cancel()
        receiveJob = null
        socketClient?.close()
        socketClient = null
        updateStatus(ChannelConnectionState.Disabled)
    }

    override suspend fun send(reply: ChannelReply) {
        val request = CreateMessageReq.newBuilder()
            .receiveIdType("chat_id")
            .createMessageReqBody(
                CreateMessageReqBody.newBuilder()
                    .receiveId(reply.address.conversationId)
                    .msgType("text")
                    .content(JSONObject().put("text", reply.text).toString())
                    .build()
            )
            .build()
        val response = apiClient.im().message().create(request, RequestOptions.newBuilder().build())
        if (!response.success()) error("Feishu send failed: ${response.msg}")
    }
}
