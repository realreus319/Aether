package com.zhousl.aether.channel.dingtalk

import com.zhousl.aether.channel.BaseAetherChannel
import com.zhousl.aether.channel.ChannelAddress
import com.zhousl.aether.channel.ChannelConfig
import com.zhousl.aether.channel.ChannelConnectionState
import com.zhousl.aether.channel.ChannelIncomingMessage
import com.zhousl.aether.channel.ChannelKind
import com.zhousl.aether.channel.ChannelReply
import com.zhousl.aether.channel.JsonMediaType
import com.zhousl.aether.channel.postJson
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

/** DingTalk Stream mode. The gateway callback URL carried by each message is used for replies. */
class DingTalkChannel(
    private val config: ChannelConfig,
    private val scope: CoroutineScope,
    private val http: OkHttpClient,
) : BaseAetherChannel(ChannelKind.DingTalk) {
    private var socket: WebSocket? = null
    private val opened = CompletableDeferred<Unit>()

    override suspend fun start() {
        if (!config.enabled) return updateStatus(ChannelConnectionState.Disabled)
        if (!config.isConfigured) return updateStatus(ChannelConnectionState.Error, "Client ID and secret are required")
        updateStatus(ChannelConnectionState.Starting)
        val gateway = http.postJson(
            "${config.baseUrl.trimEnd('/')}/v1.0/gateway/connections/open",
            JSONObject().put("clientId", config.appId).put("clientSecret", config.appSecret),
        )
        val endpoint = gateway.optString("endpoint")
        val ticket = gateway.optString("ticket")
        require(endpoint.isNotBlank() && ticket.isNotBlank()) { "DingTalk gateway returned no endpoint" }
        val request = Request.Builder().url("$endpoint?ticket=$ticket").build()
        socket = http.newWebSocket(request, listener)
        opened.await()
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            updateStatus(ChannelConnectionState.Connected)
            opened.complete(Unit)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val frame = runCatching { JSONObject(text) }.getOrNull() ?: return
            val headers = frame.optJSONObject("headers") ?: JSONObject()
            when (headers.optString("type")) {
                "SYSTEM" -> if (headers.optString("topic").contains("KEEPALIVE")) {
                    webSocket.send(JSONObject().put("code", 200).put("headers", headers).put("message", "OK").toString())
                }
                "CALLBACK" -> {
                    val payload = runCatching { JSONObject(frame.optString("data")) }.getOrNull() ?: frame.optJSONObject("data") ?: return
                    val messageId = payload.optString("msgId").ifBlank { headers.optString("messageId") }
                    val textValue = payload.optJSONObject("text")?.optString("content").orEmpty().trim()
                    if (textValue.isNotBlank()) scope.launch {
                        emitIncoming(
                            ChannelIncomingMessage(
                                kind,
                                messageId.ifBlank { UUID.randomUUID().toString() },
                                ChannelAddress(
                                    conversationId = payload.optString("conversationId").ifBlank { payload.optString("senderId") },
                                    userId = payload.optString("senderStaffId").ifBlank { payload.optString("senderId") },
                                    replyToken = payload.optString("sessionWebhook"),
                                ),
                                textValue,
                            )
                        )
                    }
                    webSocket.send(JSONObject().put("code", 200).put("headers", headers).put("message", "OK").toString())
                }
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            updateStatus(ChannelConnectionState.Error, t.message.orEmpty())
            opened.completeExceptionally(t)
        }
    }

    override suspend fun stop() {
        socket?.close(1000, "Aether channel stopped")
        socket = null
        updateStatus(ChannelConnectionState.Disabled)
    }

    override suspend fun send(reply: ChannelReply) {
        require(reply.address.replyToken.isNotBlank()) { "DingTalk message has no session webhook" }
        val body = JSONObject().put("msgtype", "text").put("text", JSONObject().put("content", reply.text))
        val request = Request.Builder().url(reply.address.replyToken)
            .post(body.toString().toRequestBody(JsonMediaType)).build()
        http.newCall(request).execute().use { if (!it.isSuccessful) error("DingTalk send failed: HTTP ${it.code}") }
    }
}
