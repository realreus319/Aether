package com.zhousl.aether.channel.wecom

import com.zhousl.aether.channel.BaseAetherChannel
import com.zhousl.aether.channel.ChannelAddress
import com.zhousl.aether.channel.ChannelConfig
import com.zhousl.aether.channel.ChannelConnectionState
import com.zhousl.aether.channel.ChannelIncomingMessage
import com.zhousl.aether.channel.ChannelKind
import com.zhousl.aether.channel.ChannelReply
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

/** WeCom intelligent-bot WebSocket transport. Replies reuse the inbound frame request id. */
class WeComChannel(
    private val config: ChannelConfig,
    private val scope: CoroutineScope,
    private val http: OkHttpClient,
) : BaseAetherChannel(ChannelKind.WeCom) {
    override val supportsStreamingReplies: Boolean = true
    private var socket: WebSocket? = null
    private val requestIds = ConcurrentHashMap<String, String>()
    private val activeStreams = ConcurrentHashMap<String, String>()
    private var heartbeatJob: Job? = null

    override suspend fun start() {
        if (!config.enabled) return updateStatus(ChannelConnectionState.Disabled)
        if (!config.isConfigured) return updateStatus(ChannelConnectionState.Error, "Bot ID and secret are required")
        updateStatus(ChannelConnectionState.Starting)
        val request = Request.Builder()
            .url(config.baseUrl)
            .header("x-wecom-bot-id", config.appId)
            .header("x-wecom-bot-secret", config.appSecret)
            .build()
        socket = http.newWebSocket(request, listener)
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send(
                JSONObject().put("cmd", "aibot_subscribe")
                    .put("headers", JSONObject()
                    .put("req_id", "aibot_subscribe_${UUID.randomUUID()}"))
                    .put("body", JSONObject().put("bot_id", config.appId).put("secret", config.appSecret))
                    .toString()
            )
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val frame = runCatching { JSONObject(text) }.getOrNull() ?: return
            val headers = frame.optJSONObject("headers") ?: JSONObject()
            val command = frame.optString("cmd")
            val requestId = headers.optString("req_id")
            if (command.isBlank() && requestId.startsWith("aibot_subscribe")) {
                if (frame.optInt("errcode") == 0) {
                    updateStatus(ChannelConnectionState.Connected)
                    startHeartbeat(webSocket)
                } else {
                    updateStatus(ChannelConnectionState.Error, frame.optString("errmsg"))
                }
                return
            }
            if (command != "aibot_msg_callback" && command != "aibot_event_callback") return
            val body = frame.optJSONObject("body") ?: return
            if (body.optString("msgtype") != "text") return
            val sender = body.optJSONObject("from")?.optString("userid").orEmpty()
            val chatId = body.optString("chatid").ifBlank { sender }
            val content = body.optJSONObject("text")?.optString("content").orEmpty().trim()
            if (content.isBlank()) return
            val replyId = requestId
            requestIds[chatId] = replyId
            scope.launch {
                emitIncoming(
                    ChannelIncomingMessage(
                        kind,
                        body.optString("msgid").ifBlank { "$sender:${body.optLong("send_time")}" },
                        ChannelAddress(chatId, sender, replyId),
                        content,
                    )
                )
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            heartbeatJob?.cancel()
            updateStatus(ChannelConnectionState.Reconnecting, t.message.orEmpty())
            scope.launch {
                delay(2_000)
                runCatching { start() }
                    .onFailure { updateStatus(ChannelConnectionState.Error, it.message.orEmpty()) }
            }
        }
    }

    private fun startHeartbeat(webSocket: WebSocket) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(30_000)
                webSocket.send(
                    JSONObject().put("cmd", "ping")
                        .put("headers", JSONObject().put("req_id", "ping_${UUID.randomUUID()}"))
                        .toString()
                )
            }
        }
    }

    override suspend fun stop() {
        socket?.close(1000, "Aether channel stopped")
        socket = null
        heartbeatJob?.cancel()
        heartbeatJob = null
        requestIds.clear()
        activeStreams.clear()
        updateStatus(ChannelConnectionState.Disabled)
    }

    override suspend fun send(reply: ChannelReply) {
        val requestId = reply.address.replyToken.ifBlank { requestIds[reply.address.conversationId].orEmpty() }
        require(requestId.isNotBlank()) { "WeCom inbound frame is no longer available" }
        val streamId = activeStreams.computeIfAbsent(reply.address.conversationId) { "aether-${UUID.randomUUID()}" }
        val frame = JSONObject()
            .put("cmd", "aibot_respond_msg")
            .put("headers", JSONObject().put("req_id", requestId))
            .put("body", JSONObject().put("msgtype", "stream").put(
                "stream", JSONObject().put("id", streamId).put("finish", reply.isFinal).put("content", reply.text)
            ))
        check(socket?.send(frame.toString()) == true) { "WeCom socket is not connected" }
        if (reply.isFinal) activeStreams.remove(reply.address.conversationId)
    }
}
