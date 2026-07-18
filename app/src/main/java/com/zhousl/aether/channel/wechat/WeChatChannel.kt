package com.zhousl.aether.channel.wechat

import android.util.Base64
import com.zhousl.aether.channel.BaseAetherChannel
import com.zhousl.aether.channel.ChannelAddress
import com.zhousl.aether.channel.ChannelConfig
import com.zhousl.aether.channel.ChannelConnectionState
import com.zhousl.aether.channel.ChannelIncomingMessage
import com.zhousl.aether.channel.ChannelKind
import com.zhousl.aether.channel.ChannelReply
import com.zhousl.aether.channel.postJson
import java.util.UUID
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.json.JSONObject

/** WeChat iLink Bot long polling, following QwenPaw's cursor and context-token design. */
class WeChatChannel(
    private val config: ChannelConfig,
    private val scope: CoroutineScope,
    private val http: OkHttpClient,
) : BaseAetherChannel(ChannelKind.WeChat) {
    private var pollingJob: Job? = null

    override suspend fun start() {
        if (!config.enabled) return updateStatus(ChannelConnectionState.Disabled)
        if (!config.isConfigured) return updateStatus(ChannelConnectionState.Error, "Bot token is required")
        if (pollingJob?.isActive == true) return
        updateStatus(ChannelConnectionState.Starting)
        pollingJob = scope.launch {
            var cursor = ""
            var failures = 0
            while (isActive) {
                try {
                    val response = http.postJson(
                        "${config.baseUrl.trimEnd('/')}/ilink/bot/getupdates",
                        JSONObject().put("get_updates_buf", cursor)
                            .put("base_info", JSONObject().put("channel_version", "1.0.2")),
                        headers(),
                    )
                    val ret = response.optInt("ret")
                    if (ret != 0 && ret != -1) error("WeChat getupdates ret=$ret")
                    cursor = response.optString("get_updates_buf", cursor)
                    val messages = response.optJSONArray("msgs")
                    if (messages != null) repeat(messages.length()) { index ->
                        val message = messages.optJSONObject(index) ?: return@repeat
                        val from = message.optString("from_user_id")
                        val contextToken = message.optString("context_token")
                        val items = message.optJSONArray("item_list")
                        val text = buildList {
                            if (items != null) repeat(items.length()) { itemIndex ->
                                items.optJSONObject(itemIndex)?.optJSONObject("text_item")
                                    ?.optString("text")?.trim()?.takeIf(String::isNotBlank)?.let(::add)
                            }
                        }.joinToString("\n")
                        if (from.isNotBlank() && text.isNotBlank()) {
                            emitIncoming(
                                ChannelIncomingMessage(
                                    kind,
                                    contextToken.ifBlank { message.optString("msg_id").ifBlank { UUID.randomUUID().toString() } },
                                    ChannelAddress(from, from, contextToken),
                                    text,
                                )
                            )
                        }
                    }
                    failures = 0
                    updateStatus(ChannelConnectionState.Connected)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    failures++
                    updateStatus(ChannelConnectionState.Reconnecting, error.message.orEmpty())
                    delay((1_000L shl failures.coerceAtMost(5)).coerceAtMost(30_000L))
                }
            }
        }
    }

    override suspend fun stop() {
        pollingJob?.cancel()
        pollingJob = null
        updateStatus(ChannelConnectionState.Disabled)
    }

    override suspend fun send(reply: ChannelReply) {
        require(reply.address.replyToken.isNotBlank()) { "WeChat context token is missing" }
        val message = JSONObject()
            .put("from_user_id", "")
            .put("to_user_id", reply.address.userId)
            .put("client_id", UUID.randomUUID().toString())
            .put("message_type", 2)
            .put("message_state", 2)
            .put("context_token", reply.address.replyToken)
            .put("item_list", org.json.JSONArray().put(
                JSONObject().put("type", 1).put("text_item", JSONObject().put("text", reply.text))
            ))
        val response = http.postJson(
            "${config.baseUrl.trimEnd('/')}/ilink/bot/sendmessage",
            JSONObject().put("msg", message).put("base_info", JSONObject().put("channel_version", "1.0.2")),
            headers(),
        )
        if (response.optInt("ret") != 0) error("WeChat send failed: ret=${response.optInt("ret")}")
    }

    private fun headers(): Map<String, String> {
        val uin = Base64.encodeToString(
            Random.nextLong(0, 0xffff_ffffL).toString().toByteArray(),
            Base64.NO_WRAP,
        )
        return mapOf(
            "AuthorizationType" to "ilink_bot_token",
            "Authorization" to "Bearer ${config.token}",
            "X-WECHAT-UIN" to uin,
        )
    }
}
