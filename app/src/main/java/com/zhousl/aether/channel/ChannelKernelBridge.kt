package com.zhousl.aether.channel

import com.zhousl.aether.data.AetherDiagnosticLogger
import com.zhousl.aether.runtime.AlpineRuntime
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

private const val ChannelBridgeAsset = "pi-bridge/channel-bridge.mjs"
private const val ChannelBridgeGuest = "/root/.aether/pi-bridge/channel-bridge.mjs"
private const val ChannelBridgeWorkingDirectory = "/root/.aether/pi-bridge"

class ChannelKernelBridge(
    private val alpineRuntime: AlpineRuntime,
    private val diagnosticLogger: AetherDiagnosticLogger = AetherDiagnosticLogger.NoOp,
) {
    private val lock = Any()
    private val eventScope = CoroutineScope(Dispatchers.Default)
    private val responses = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    @Volatile private var process: Process? = null
    @Volatile private var writer: BufferedWriter? = null
    @Volatile private var eventHandler: (suspend (String, JSONObject) -> Unit)? = null

    suspend fun subscribe(onEvent: suspend (String, JSONObject) -> Unit) = withContext(Dispatchers.IO) {
        eventHandler = onEvent
        ensureStarted()
        writeRequest("channel-subscribe", "subscribe", JSONObject())
    }

    suspend fun reload(): JSONObject = request("reload")

    suspend fun send(accountId: String, address: ChannelAddress, text: String): JSONObject =
        request(
            type = "send",
            payload = JSONObject()
                .put("account_id", accountId)
                .put("address", address.toJson())
                .put("text", text),
        )

    suspend fun stop() = withContext(Dispatchers.IO) {
        runCatching { request("stop", startIfNeeded = false) }
        synchronized(lock) {
            runCatching { writer?.close() }
            runCatching { process?.destroy() }
            writer = null
            process = null
        }
        responses.values.forEach { it.completeExceptionally(IllegalStateException("Channel bridge stopped")) }
        responses.clear()
    }

    private suspend fun request(
        type: String,
        payload: JSONObject = JSONObject(),
        startIfNeeded: Boolean = true,
    ): JSONObject = withContext(Dispatchers.IO) {
        if (startIfNeeded) ensureStarted()
        val id = "$type-${UUID.randomUUID()}"
        val deferred = CompletableDeferred<JSONObject>()
        responses[id] = deferred
        try {
            writeRequest(id, type, payload)
            withTimeout(120_000L) { deferred.await() }
        } finally {
            responses.remove(id)
        }
    }

    private suspend fun ensureStarted() {
        synchronized(lock) {
            if (process?.isAlive == true && writer != null) return
        }
        val setup = alpineRuntime.initialize()
        check(setup.isReady) { setup.detail.ifBlank { "Alpine runtime is not ready" } }
        alpineRuntime.installAsset(ChannelBridgeAsset, ChannelBridgeGuest, executable = false)
        val started = alpineRuntime.startManagedProcess(
            command = "node '$ChannelBridgeGuest'",
            workingDirectory = ChannelBridgeWorkingDirectory,
        )
        synchronized(lock) {
            process = started
            writer = BufferedWriter(OutputStreamWriter(started.outputStream, Charsets.UTF_8))
        }
        startStdoutReader(started)
        startStderrReader(started)
    }

    private fun writeRequest(id: String, type: String, payload: JSONObject) {
        val line = JSONObject().put("id", id).put("type", type).put("payload", payload).toString()
        synchronized(lock) {
            val output = writer ?: error("Channel bridge is not running")
            output.write(line)
            output.newLine()
            output.flush()
        }
    }

    private fun startStdoutReader(started: Process) {
        Thread({
            BufferedReader(InputStreamReader(started.inputStream, Charsets.UTF_8)).useLines { lines ->
                lines.forEach { line ->
                    runCatching { JSONObject(line) }
                        .onSuccess(::handleFrame)
                        .onFailure { throwable ->
                            diagnosticLogger.exception(
                                category = "channel",
                                event = "invalid_bridge_frame",
                                throwable = throwable,
                            )
                        }
                }
            }
        }, "aether-channel-stdout").apply { isDaemon = true; start() }
    }

    private fun startStderrReader(started: Process) {
        Thread({
            BufferedReader(InputStreamReader(started.errorStream, Charsets.UTF_8)).useLines { lines ->
                lines.forEach { line ->
                    diagnosticLogger.event(
                        category = "channel",
                        event = "bridge_log",
                        level = "warn",
                        details = mapOf("line" to line.take(700)),
                    )
                }
            }
        }, "aether-channel-stderr").apply { isDaemon = true; start() }
    }

    private fun handleFrame(frame: JSONObject) {
        val id = frame.optString("id")
        when (frame.optString("type")) {
            "event" -> {
                val event = frame.optString("event")
                val payload = frame.optJSONObject("payload") ?: JSONObject()
                val handler = eventHandler ?: return
                eventScope.launch { runCatching { handler(event, payload) } }
            }
            "response" -> responses[id]?.complete(frame.optJSONObject("payload") ?: JSONObject())
            "error" -> responses[id]?.completeExceptionally(
                IllegalStateException(frame.optJSONObject("error")?.optString("message") ?: "Channel bridge error")
            )
        }
    }
}
