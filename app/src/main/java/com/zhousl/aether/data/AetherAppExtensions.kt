package com.zhousl.aether.data

import com.zhousl.aether.data.pi.PiKernelBridge
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

data class AetherAppExtensionInfo(
    val id: String,
    val name: String,
    val path: String,
)

data class AetherAppExtensionSurface(
    val id: String,
    val extensionId: String,
    val extensionName: String,
    val slot: String,
    val order: Int,
    val tree: Any?,
)

data class AetherAppExtensionComponent(
    val id: String,
    val extensionId: String,
    val extensionName: String,
    val target: String,
    val mode: String,
    val order: Int,
    val tree: Any?,
)

data class AetherAppExtensionPage(
    val id: String,
    val localId: String,
    val extensionId: String,
    val extensionName: String,
    val title: String,
    val subtitle: String,
    val icon: String,
    val order: Int,
    val tree: Any?,
)

data class AetherAppExtensionError(
    val path: String,
    val extensionId: String,
    val phase: String,
    val message: String,
)

data class AetherAppExtensionSnapshot(
    val apiVersion: Int = 2,
    val version: Long = 0L,
    val extensions: List<AetherAppExtensionInfo> = emptyList(),
    val surfaces: List<AetherAppExtensionSurface> = emptyList(),
    val components: List<AetherAppExtensionComponent> = emptyList(),
    val pages: List<AetherAppExtensionPage> = emptyList(),
    val eventNames: Set<String> = emptySet(),
    val errors: List<AetherAppExtensionError> = emptyList(),
) {
    fun surfacesAt(slot: String): List<AetherAppExtensionSurface> =
        surfaces.filter { it.slot == slot }.sortedWith(
            compareBy<AetherAppExtensionSurface> { it.order }.thenBy { it.id }
        )

    fun componentsAt(target: String): List<AetherAppExtensionComponent> =
        components.filter { it.target == target }.sortedWith(
            compareBy<AetherAppExtensionComponent> { it.order }.thenBy { it.id }
        )
}

data class AetherAppExtensionState(
    val snapshot: AetherAppExtensionSnapshot = AetherAppExtensionSnapshot(),
    val isLoading: Boolean = false,
    val error: String = "",
)

data class AetherAppExtensionNotification(
    val message: String,
    val level: String,
)

data class AetherAppExtensionEventResult(
    val handled: Boolean,
    val cancelled: Boolean,
    val reason: String,
    val payload: JSONObject,
)

class AetherAppExtensionManager(
    private val bridge: PiKernelBridge,
    private val scope: CoroutineScope,
    private val diagnosticLogger: AetherDiagnosticLogger = AetherDiagnosticLogger.NoOp,
    private val loadOptionsProvider: suspend () -> PiExtensionLoadOptions = {
        PiExtensionLoadOptions()
    },
) {
    private val started = AtomicBoolean(false)
    private val refreshMutex = Mutex()
    private val _state = MutableStateFlow(AetherAppExtensionState())
    private val _notifications = MutableSharedFlow<AetherAppExtensionNotification>(
        extraBufferCapacity = 8,
    )
    private var subscriptionJob: Job? = null
    private var invalidationJob: Job? = null
    private var latestContextJson = "{}"

    @Volatile
    private var hostHandler: (suspend (String, JSONObject) -> JSONObject)? = null

    val state: StateFlow<AetherAppExtensionState> = _state.asStateFlow()
    val notifications: SharedFlow<AetherAppExtensionNotification> = _notifications.asSharedFlow()

    fun setHostHandler(handler: suspend (String, JSONObject) -> JSONObject) {
        hostHandler = handler
    }

    fun clearHostHandler() {
        hostHandler = null
    }

    fun start(context: JSONObject = JSONObject()) {
        latestContextJson = context.toString()
        if (!started.compareAndSet(false, true)) return
        subscriptionJob = scope.launch {
            while (true) {
                try {
                    bridge.subscribeAetherExtensions(::handleBridgeEvent)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (throwable: Throwable) {
                    diagnosticLogger.exception(
                        category = "aether_extension",
                        event = "subscription_failed",
                        throwable = throwable,
                        level = "warn",
                    )
                    delay(2_000L)
                }
            }
        }
        scope.launch {
            reload(context)
        }
    }

    fun updateContext(context: JSONObject) {
        val serialized = context.toString()
        if (serialized == latestContextJson) return
        latestContextJson = serialized
        if (!started.get()) {
            start(context)
            return
        }
        scope.launch {
            refresh(context)
        }
    }

    suspend fun reload(context: JSONObject = currentContext()): Result<AetherAppExtensionSnapshot> =
        runCatching {
            refreshMutex.withLock {
                _state.value = _state.value.copy(isLoading = true, error = "")
                val response = bridge.reloadAetherExtensions(
                    context = context,
                    loadOptions = loadOptionsProvider(),
                    onEvent = ::handleBridgeEvent,
                )
                val snapshot = parseSnapshot(response.optJSONObject("snapshot"))
                val reloadError = response.extensionReloadError()
                _state.value = AetherAppExtensionState(
                    snapshot = snapshot,
                    error = reloadError,
                )
                if (!response.optBoolean("reloaded", true)) {
                    error(reloadError.ifBlank { "Aether extensions rejected the reload." })
                }
                snapshot
            }
        }.onFailure(::recordFailure)

    suspend fun refresh(context: JSONObject = currentContext()): Result<AetherAppExtensionSnapshot> =
        runCatching {
            refreshMutex.withLock {
                val response = bridge.getAetherExtensions(
                    context = context,
                    loadOptions = loadOptionsProvider(),
                    onEvent = ::handleBridgeEvent,
                )
                val snapshot = parseSnapshot(response.optJSONObject("snapshot"))
                _state.value = _state.value.copy(snapshot = snapshot, isLoading = false, error = "")
                snapshot
            }
        }.onFailure(::recordFailure)

    fun invokeAction(
        extensionId: String,
        action: String,
        args: JSONObject = JSONObject(),
        context: JSONObject = currentContext(),
    ) {
        scope.launch {
            runCatching {
                val response = bridge.invokeAetherExtensionAction(
                    extensionId = extensionId,
                    action = action,
                    args = args,
                    context = context,
                    onEvent = ::handleBridgeEvent,
                )
                parseSnapshot(response.optJSONObject("snapshot"))
            }.onSuccess { snapshot ->
                _state.value = _state.value.copy(snapshot = snapshot, error = "")
            }.onFailure(::recordFailure)
        }
    }

    suspend fun dispatchEvent(
        event: String,
        data: JSONObject = JSONObject(),
        context: JSONObject = currentContext(),
    ): Result<AetherAppExtensionEventResult> {
        if (event !in _state.value.snapshot.eventNames) {
            return Result.success(
                AetherAppExtensionEventResult(
                    handled = false,
                    cancelled = false,
                    reason = "",
                    payload = data,
                )
            )
        }
        return runCatching {
            val response = bridge.dispatchAetherExtensionEvent(
                event = event,
                data = data,
                context = context,
                onEvent = ::handleBridgeEvent,
            )
            response.optJSONObject("snapshot")?.let(::parseSnapshot)?.let { snapshot ->
                _state.value = _state.value.copy(snapshot = snapshot, error = "")
            }
            AetherAppExtensionEventResult(
                handled = response.optBoolean("handled"),
                cancelled = response.optBoolean("cancelled"),
                reason = response.optString("reason"),
                payload = response.optJSONObject("payload") ?: data,
            )
        }.onFailure(::recordFailure)
    }

    fun emitEvent(
        event: String,
        data: JSONObject = JSONObject(),
        context: JSONObject = currentContext(),
    ) {
        if (event !in _state.value.snapshot.eventNames) return
        scope.launch {
            dispatchEvent(event, data, context)
        }
    }

    private suspend fun handleBridgeEvent(
        event: String,
        payload: JSONObject,
    ) {
        when (event) {
            "aether_invalidated" -> {
                scheduleRefresh()
            }

            "aether_notification" -> {
                _notifications.emit(
                    AetherAppExtensionNotification(
                        message = payload.optString("message"),
                        level = payload.optString("level").ifBlank { "info" },
                    )
                )
            }

            "aether_host_call" -> {
                val callId = payload.optString("call_id")
                val method = payload.optString("method")
                val args = payload.optJSONObject("args") ?: JSONObject()
                val result = runCatching {
                    val handler = hostHandler
                        ?: error("The Aether UI host is not attached.")
                    handler(method, args)
                }
                bridge.sendAetherHostResult(
                    callId = callId,
                    result = result.getOrDefault(JSONObject()),
                    error = result.exceptionOrNull()?.message.orEmpty(),
                )
            }
        }
    }

    private fun currentContext(): JSONObject =
        runCatching { JSONObject(latestContextJson) }.getOrElse { JSONObject() }

    private fun scheduleRefresh() {
        if (invalidationJob?.isActive == true) return
        invalidationJob = scope.launch {
            delay(100L)
            refresh()
        }
    }

    private fun recordFailure(throwable: Throwable) {
        if (throwable is CancellationException) return
        diagnosticLogger.exception(
            category = "aether_extension",
            event = "operation_failed",
            throwable = throwable,
        )
        _state.value = _state.value.copy(
            isLoading = false,
            error = throwable.message ?: throwable.javaClass.simpleName,
        )
    }
}

internal fun JSONObject.extensionReloadError(): String {
    if (optBoolean("reloaded", true)) return ""
    val errors = optJSONArray("errors") ?: return "Aether extensions rejected the reload."
    return buildList {
        for (index in 0 until errors.length()) {
            errors.optJSONObject(index)
                ?.optString("error")
                ?.takeIf(String::isNotBlank)
                ?.let(::add)
        }
    }.distinct().take(3).joinToString("; ")
        .ifBlank { "Aether extensions rejected the reload." }
}

internal fun parseAetherAppExtensionSnapshot(json: JSONObject?): AetherAppExtensionSnapshot {
    if (json == null) return AetherAppExtensionSnapshot()
    return AetherAppExtensionSnapshot(
        apiVersion = json.optInt("api_version", 2),
        version = json.optLong("version"),
        extensions = json.optJSONArray("extensions").objects().map { item ->
            AetherAppExtensionInfo(
                id = item.optString("id"),
                name = item.optString("name"),
                path = item.optString("path"),
            )
        },
        surfaces = json.optJSONArray("surfaces").objects().map { item ->
            AetherAppExtensionSurface(
                id = item.optString("id"),
                extensionId = item.optString("extension_id"),
                extensionName = item.optString("extension_name"),
                slot = item.optString("slot"),
                order = item.optInt("order"),
                tree = item.opt("tree"),
            )
        },
        components = json.optJSONArray("components").objects().map { item ->
            AetherAppExtensionComponent(
                id = item.optString("id"),
                extensionId = item.optString("extension_id"),
                extensionName = item.optString("extension_name"),
                target = item.optString("target"),
                mode = item.optString("mode").ifBlank { "wrap" },
                order = item.optInt("order"),
                tree = item.opt("tree"),
            )
        },
        pages = json.optJSONArray("pages").objects().map { item ->
            AetherAppExtensionPage(
                id = item.optString("id"),
                localId = item.optString("local_id"),
                extensionId = item.optString("extension_id"),
                extensionName = item.optString("extension_name"),
                title = item.optString("title"),
                subtitle = item.optString("subtitle"),
                icon = item.optString("icon"),
                order = item.optInt("order"),
                tree = item.opt("tree"),
            )
        },
        eventNames = json.optJSONArray("event_names").strings().toSet(),
        errors = json.optJSONArray("errors").objects().map { item ->
            AetherAppExtensionError(
                path = item.optString("path"),
                extensionId = item.optString("extension_id"),
                phase = item.optString("phase"),
                message = item.optString("error"),
            )
        },
    )
}

private fun parseSnapshot(json: JSONObject?): AetherAppExtensionSnapshot =
    parseAetherAppExtensionSnapshot(json)

private fun JSONArray?.objects(): List<JSONObject> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) {
            optJSONObject(index)?.let(::add)
        }
    }
}

private fun JSONArray?.strings(): List<String> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) {
            optString(index).takeIf(String::isNotBlank)?.let(::add)
        }
    }
}
