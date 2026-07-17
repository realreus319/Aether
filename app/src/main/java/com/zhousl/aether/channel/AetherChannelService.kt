package com.zhousl.aether.channel

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.zhousl.aether.aetherRuntime
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Persistent transport host for Feishu, DingTalk and WeChat channels. */
class AetherChannelService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _states = MutableStateFlow<Map<String, ChannelConnectionState>>(emptyMap())
    val states: StateFlow<Map<String, ChannelConnectionState>> = _states.asStateFlow()
    private lateinit var bridge: ChannelKernelBridge

    override fun onCreate() {
        super.onCreate()
        running.set(true)
        val runtime = aetherRuntime
        ServiceCompat.startForeground(
            this,
            NotificationId,
            runtime.notificationController.buildForegroundNotification(
                sessions = emptyList(),
                executionStates = emptyMap(),
            ),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        bridge = ChannelKernelBridge(runtime.alpineRuntime, runtime.diagnosticLogger)
        val coordinator = ChannelTurnCoordinator(
            scope = scope,
            settingsRepository = runtime.settingsRepository,
            extensionsRepository = runtime.extensionsRepository,
            chatStateStore = runtime.chatStateStore,
            sessionExecutionManager = runtime.sessionExecutionManager,
            sendReply = { pending, text -> bridge.send(pending.accountId, pending.address, text) },
        )
        scope.launch {
            runCatching {
                bridge.subscribe { event, payload ->
                    when (event) {
                        "channel_message" -> ChannelInboundMessage.fromBridge(payload)?.let { coordinator.submit(it) }
                        "channel_status" -> updateStatus(payload)
                        "channel_ready" -> if (payload.optInt("configured", 0) == 0) stopSelf()
                    }
                }
            }.onFailure { throwable ->
                runtime.diagnosticLogger.exception(
                    category = "channel",
                    event = "service_start_failed",
                    throwable = throwable,
                )
                stopSelf()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        running.set(false)
        scope.launch { bridge.stop() }
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun updateStatus(payload: org.json.JSONObject) {
        val type = ChannelType.fromStorage(payload.optString("channel_type")) ?: return
        val accountId = payload.optString("account_id").trim()
        if (accountId.isBlank()) return
        _states.value = _states.value + (
            accountId to ChannelConnectionState(
                accountId = accountId,
                channelType = type,
                status = ChannelConnectionStatus.fromBridge(payload.optString("status")),
                detail = payload.optString("detail"),
                updatedAtMillis = payload.optLong("updated_at_millis", System.currentTimeMillis()),
            )
        )
    }

    companion object {
        private const val NotificationId = 4203
        private val running = AtomicBoolean(false)

        fun ensureRunning(context: Context) {
            if (!running.compareAndSet(false, true)) return
            runCatching {
                ContextCompat.startForegroundService(
                    context.applicationContext,
                    Intent(context.applicationContext, AetherChannelService::class.java),
                )
            }.onFailure {
                running.set(false)
                throw it
            }
        }
    }
}
