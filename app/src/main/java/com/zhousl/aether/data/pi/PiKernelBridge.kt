package com.zhousl.aether.data.pi

import com.zhousl.aether.data.AetherDiagnosticLogger
import com.zhousl.aether.data.DiagnosticRedactor
import com.zhousl.aether.runtime.AlpineRuntime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private const val PiBridgeAssetPath = "pi-bridge/bridge.mjs"
private const val PiBridgeGuestPath = "/root/.aether/pi-bridge/bridge.mjs"
private const val PiBridgeWorkingDirectory = "/root/.aether/pi-bridge"
private const val PiBridgeNodeMinVersion = "22.19.0"
private const val PiBridgeRequestTimeoutMillis = 10 * 60 * 1000L
private const val PiBridgePingTimeoutMillis = 15_000L

class PiKernelBridge(
    private val alpineRuntime: AlpineRuntime,
    private val diagnosticLogger: AetherDiagnosticLogger = AetherDiagnosticLogger.NoOp,
) {
    private val mutex = Mutex()
    private val pendingRequests = ConcurrentHashMap<String, CompletableDeferred<PiBridgeFrame>>()
    private val eventHandlers = ConcurrentHashMap<String, suspend (String, JSONObject) -> Unit>()
    private val eventScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var process: Process? = null
    private var writer: BufferedWriter? = null

    suspend fun ping(): JSONObject =
        request(
            type = "ping",
            timeoutMillis = PiBridgePingTimeoutMillis,
        )

    suspend fun completeOnce(
        payload: JSONObject,
        onEvent: (suspend (String, JSONObject) -> Unit)? = null,
    ): JSONObject =
        request(
            type = "complete_once",
            payload = payload,
            timeoutMillis = PiBridgeRequestTimeoutMillis,
            onEvent = onEvent,
        )

    suspend fun runTurn(
        payload: JSONObject,
        onEvent: suspend (String, JSONObject) -> Unit,
    ): JSONObject =
        request(
            type = "run_turn",
            payload = payload,
            timeoutMillis = PiBridgeRequestTimeoutMillis,
            onEvent = onEvent,
        )

    suspend fun abort(requestId: String): JSONObject =
        request(
            type = "abort",
            payload = JSONObject().put("request_id", requestId),
            timeoutMillis = PiBridgePingTimeoutMillis,
        )

    suspend fun sendHostToolResult(payload: JSONObject) {
        sendNotification(type = "host_tool_result", payload = payload)
    }

    suspend fun sendHostToolProgress(payload: JSONObject) {
        sendNotification(type = "host_tool_progress", payload = payload)
    }

    suspend fun stop() = withContext(Dispatchers.IO) {
        mutex.withLock {
            eventScope.coroutineContext.cancelChildren()
            pendingRequests.values.forEach { deferred ->
                deferred.completeExceptionally(PiBridgeException("Pi bridge stopped."))
            }
            pendingRequests.clear()
            eventHandlers.clear()
            runCatching { writer?.close() }
            runCatching { process?.destroy() }
            process = null
            writer = null
        }
    }

    private suspend fun sendNotification(
        type: String,
        payload: JSONObject = JSONObject(),
    ) = withContext(Dispatchers.IO) {
        val activeWriter = ensureStartedLocked()
        val line = PiBridgeRequest(
            id = nextRequestId(type),
            type = type,
            payload = payload,
        ).toJsonLine()
        synchronized(activeWriter) {
            activeWriter.write(line)
            activeWriter.newLine()
            activeWriter.flush()
        }
    }

    private suspend fun request(
        type: String,
        payload: JSONObject = JSONObject(),
        timeoutMillis: Long,
        onEvent: (suspend (String, JSONObject) -> Unit)? = null,
    ): JSONObject = withContext(Dispatchers.IO) {
        val id = nextRequestId(type)
        val deferred = CompletableDeferred<PiBridgeFrame>()
        pendingRequests[id] = deferred
        if (onEvent != null) eventHandlers[id] = onEvent
        try {
            val activeWriter = ensureStartedLocked()
            val line = PiBridgeRequest(id = id, type = type, payload = payload).toJsonLine()
            synchronized(activeWriter) {
                activeWriter.write(line)
                activeWriter.newLine()
                activeWriter.flush()
            }
            val frame = withTimeout(timeoutMillis) { deferred.await() }
            if (!frame.ok || frame.type == "error") {
                val error = frame.error
                throw PiBridgeException(
                    message = error?.message?.ifBlank { "Pi bridge request failed." }
                        ?: "Pi bridge request failed.",
                    code = error?.code?.ifBlank { "pi_bridge_error" } ?: "pi_bridge_error",
                )
            }
            frame.payload
        } finally {
            pendingRequests.remove(id)
            eventHandlers.remove(id)
        }
    }

    private suspend fun ensureStartedLocked(): BufferedWriter {
        mutex.withLock {
            val existingProcess = process
            val existingWriter = writer
            if (existingProcess?.isAlive == true && existingWriter != null) return existingWriter

            ensureNodeAvailable()
            alpineRuntime.installAsset(
                assetPath = PiBridgeAssetPath,
                guestPath = PiBridgeGuestPath,
                executable = false,
            )
            val started = alpineRuntime.startManagedProcess(
                command = "node ${shellQuote(PiBridgeGuestPath)}",
                workingDirectory = PiBridgeWorkingDirectory,
            )
            process = started
            writer = BufferedWriter(OutputStreamWriter(started.outputStream, Charsets.UTF_8))
            startStdoutReader(started)
            startStderrReader(started)
            diagnosticLogger.event(
                category = "pi_bridge",
                event = "process_started",
                details = mapOf("guest_path" to PiBridgeGuestPath),
            )
            return writer ?: error("Pi bridge writer was not initialized.")
        }
    }

    private suspend fun ensureNodeAvailable() {
        val version = readNodeVersion()
        if (version != null && compareSemver(version, PiBridgeNodeMinVersion) >= 0) return

        diagnosticLogger.event(
            category = "pi_bridge",
            event = "node_profile_install_start",
            level = "warn",
            details = mapOf(
                "current_version" to version.orEmpty(),
                "required_version" to PiBridgeNodeMinVersion,
            ),
        )
        alpineRuntime.installPackageProfile("node")
        val installedVersion = readNodeVersion()
        if (installedVersion == null || compareSemver(installedVersion, PiBridgeNodeMinVersion) < 0) {
            throw PiBridgeException(
                "Pi bridge requires Alpine node >= $PiBridgeNodeMinVersion, found ${installedVersion ?: "none"}.",
                code = "node_version_too_old",
            )
        }
    }

    private suspend fun readNodeVersion(): String? {
        val raw = JSONObject(
            alpineRuntime.executeCommand(
                command = "node --version",
                workingDirectory = alpineRuntime.homeDirectory,
                awaitTimeoutMillis = 30_000L,
            )
        )
        if (!raw.optBoolean("ok")) return null
        return raw.optString("stdout")
            .lineSequence()
            .firstOrNull { it.trim().isNotBlank() }
            ?.trim()
            ?.removePrefix("v")
    }

    private fun startStdoutReader(startedProcess: Process) {
        Thread(
            {
                val parser = PiJsonlParser(
                    onFrame = ::handleFrameFromReader,
                    onInvalidLine = { line, throwable ->
                        diagnosticLogger.exception(
                            category = "pi_bridge",
                            event = "invalid_stdout_json",
                            throwable = throwable,
                            details = mapOf("line" to line.take(700)),
                        )
                    },
                )
                runCatching {
                    BufferedReader(InputStreamReader(startedProcess.inputStream, Charsets.UTF_8)).useLines { lines ->
                        lines.forEach { line -> parser.accept(line + "\n") }
                    }
                    parser.flush()
                }.onFailure { throwable ->
                    diagnosticLogger.exception(
                        category = "pi_bridge",
                        event = "stdout_reader_failed",
                        throwable = throwable,
                    )
                }
                failPendingRequests("Pi bridge process exited.")
            },
            "aether-pi-bridge-stdout",
        ).apply {
            isDaemon = true
            start()
        }
    }

    private fun startStderrReader(startedProcess: Process) {
        Thread(
            {
                runCatching {
                    BufferedReader(InputStreamReader(startedProcess.errorStream, Charsets.UTF_8)).useLines { lines ->
                        lines.forEach { line ->
                            diagnosticLogger.event(
                                category = "pi_bridge",
                                event = "stderr",
                                level = "warn",
                                details = mapOf("line" to DiagnosticRedactor.sanitizeString(line)),
                            )
                        }
                    }
                }
            },
            "aether-pi-bridge-stderr",
        ).apply {
            isDaemon = true
            start()
        }
    }

    private fun handleFrameFromReader(frame: PiBridgeFrame) {
        when (frame.type) {
            "response", "error" -> pendingRequests.remove(frame.id)?.complete(frame)
            "event" -> {
                val handler = eventHandlers[frame.id] ?: return
                eventScope.launch {
                    runCatching {
                        handler(frame.event, frame.payload)
                    }.onFailure { throwable ->
                        diagnosticLogger.exception(
                            category = "pi_bridge",
                            event = "event_handler_failed",
                            throwable = throwable,
                            details = mapOf(
                                "request_id" to frame.id,
                                "event" to frame.event,
                            ),
                        )
                    }
                }
            }
            else -> diagnosticLogger.event(
                category = "pi_bridge",
                event = "unknown_frame",
                level = "warn",
                details = mapOf(
                    "frame_type" to frame.type,
                    "request_id" to frame.id,
                ),
            )
        }
    }

    private fun failPendingRequests(message: String) {
        pendingRequests.values.forEach { deferred ->
            deferred.completeExceptionally(PiBridgeException(message, code = "bridge_exited"))
        }
        pendingRequests.clear()
        eventHandlers.clear()
        process = null
        writer = null
    }

    private fun nextRequestId(type: String): String =
        "${type}-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}"
}

private fun shellQuote(value: String): String =
    "'" + value.replace("'", "'\"'\"'") + "'"

private fun compareSemver(left: String, right: String): Int {
    val leftParts = left.split('.', '-').mapNotNull { it.toIntOrNull() }
    val rightParts = right.split('.', '-').mapNotNull { it.toIntOrNull() }
    val maxSize = maxOf(leftParts.size, rightParts.size, 3)
    for (index in 0 until maxSize) {
        val leftValue = leftParts.getOrNull(index) ?: 0
        val rightValue = rightParts.getOrNull(index) ?: 0
        if (leftValue != rightValue) return leftValue.compareTo(rightValue)
    }
    return 0
}
