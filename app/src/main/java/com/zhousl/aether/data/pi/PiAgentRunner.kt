package com.zhousl.aether.data.pi

import com.zhousl.aether.data.ActiveSkillContext
import com.zhousl.aether.data.AetherToolExecutor
import com.zhousl.aether.data.AetherAgentTurnResult
import com.zhousl.aether.data.AgentToolEvent
import com.zhousl.aether.data.AppSettings
import com.zhousl.aether.data.InstalledSkill
import com.zhousl.aether.data.LlmMessage
import com.zhousl.aether.data.LlmProviderConfig
import com.zhousl.aether.data.McpToolBinding
import com.zhousl.aether.data.StreamingStatus
import org.json.JSONArray
import org.json.JSONObject

/**
 * First-stage Pi runner that preserves AetherAgent.runTurn callback semantics.
 *
 * It intentionally does not replace AetherAgent by default until host tool execution,
 * dynamic MCP tool registration, and Pi harness session replay are complete. The
 * JSONL/event contract here is the compatibility surface the later harness runner
 * will keep.
 */
class PiAgentRunner(
    private val bridge: PiKernelBridge,
    private val toolExecutor: AetherToolExecutor? = null,
) {
    suspend fun runTurn(
        settings: AppSettings,
        messages: List<LlmMessage>,
        workspaceDirectory: String,
        availableSkills: List<InstalledSkill> = emptyList(),
        activeSkills: List<ActiveSkillContext> = emptyList(),
        mcpToolBindings: List<McpToolBinding> = emptyList(),
        agentModeEnabled: Boolean = false,
        providerConfigs: List<LlmProviderConfig> = emptyList(),
        sessionId: String = "",
        onToolEvent: suspend (AgentToolEvent) -> Unit = {},
        onToolProgress: (suspend (AgentToolEvent) -> Unit)? = null,
        onAssistantTextDelta: suspend (String) -> Unit = {},
        onAssistantReasoningDelta: suspend (String) -> Unit = {},
        onStreamingStatus: suspend (StreamingStatus?) -> Unit = {},
    ): Result<AetherAgentTurnResult> = runCatching {
        onStreamingStatus(StreamingStatus("Thinking", "Pi bridge is running this turn."))
        val payload = JSONObject().apply {
            put("model_config", settings.toPiModelConfig().toJson())
            if (sessionId.isNotBlank()) put("session_id", sessionId)
            put("system_prompt", settings.systemPrompt)
            put("messages", messages.toPiJson())
            put("workspace_directory", workspaceDirectory)
            put("available_skills", JSONArray().apply {
                availableSkills.forEach { skill ->
                    put(
                        JSONObject().apply {
                            put("id", skill.id)
                            put("name", skill.name)
                            put("description", skill.description)
                        }
                    )
                }
            })
            put("active_skills", JSONArray().apply {
                activeSkills.forEach { skill ->
                    put(
                        JSONObject().apply {
                            put("skill_id", skill.skillId)
                            put("name", skill.name)
                            put("root_path", skill.skillRootPath)
                        }
                    )
                }
            })
            put("mcp_tool_bindings", JSONArray().apply {
                mcpToolBindings.forEach { binding ->
                    put(
                        JSONObject().apply {
                            put("server_id", binding.serverId)
                            put("server_name", binding.serverName)
                            put("tool_name", binding.toolName)
                            put("namespaced_name", binding.namespacedToolName)
                            put("description", binding.description)
                            put("input_schema", binding.inputSchema)
                        }
                    )
                }
            })
            put("agent_mode_enabled", agentModeEnabled)
            put("provider_config_count", providerConfigs.size)
            if (toolExecutor != null) {
                put("host_tools", AetherToolExecutor.hostToolDefinitions())
            }
        }
        val response = bridge.runTurn(payload) { event, eventPayload ->
            when (event) {
                "assistant_text_delta" -> onAssistantTextDelta(eventPayload.optString("delta"))
                "assistant_reasoning_delta" -> onAssistantReasoningDelta(eventPayload.optString("delta"))
                "tool_call_start" -> onToolEvent(eventPayload.toToolEvent(isRunning = true))
                "tool_call_delta" -> {
                    val toolEvent = eventPayload.toToolEvent(isRunning = true)
                    if (toolEvent.outputJson == null) {
                        onToolEvent(toolEvent)
                    } else {
                        (onToolProgress ?: onToolEvent)(toolEvent)
                    }
                }
                "tool_call_end" -> onToolEvent(eventPayload.toToolEvent(isRunning = false))
                "host_tool_request" -> handleHostToolRequest(
                    payload = eventPayload,
                    settings = settings,
                    workspaceDirectory = workspaceDirectory,
                )
                "assistant_error" -> onStreamingStatus(
                    StreamingStatus(
                        text = "Pi bridge error",
                        detail = eventPayload.optString("error_message"),
                    )
                )
            }
        }
        onStreamingStatus(null)
        val result = response.toPiCompletionResult()
        if (result.errorMessage.isNotBlank()) {
            error(result.errorMessage)
        }
        AetherAgentTurnResult(
            assistantText = result.assistantText,
            tokenUsage = result.usage,
        )
    }.also {
        onStreamingStatus(null)
    }

    private suspend fun handleHostToolRequest(
        payload: JSONObject,
        settings: AppSettings,
        workspaceDirectory: String,
    ) {
        val toolRequestId = payload.optString("tool_request_id").trim()
        val toolCallId = payload.optString("tool_call_id").trim()
        val toolName = payload.optString("tool_name").trim()
        if (toolRequestId.isBlank()) return

        val executor = toolExecutor
        if (executor == null || !AetherToolExecutor.supports(toolName)) {
            bridge.sendHostToolResult(
                hostToolPayload(
                    toolRequestId = toolRequestId,
                    toolCallId = toolCallId,
                    toolName = toolName.ifBlank { "unknown" },
                    argumentsJson = payload.argumentsJson(),
                    rawOutput = JSONObject().apply {
                        put("ok", false)
                        put("errmsg", "Host tool '$toolName' is not available.")
                    }.toString(),
                    isError = true,
                )
            )
            return
        }

        val argumentsJson = payload.argumentsJson()
        val result = runCatching {
            executor.execute(
                settings = settings,
                workspaceDirectory = workspaceDirectory,
                toolName = toolName,
                argumentsJson = argumentsJson,
                onProgress = { progress ->
                    bridge.sendHostToolProgress(
                        hostToolPayload(
                            toolRequestId = toolRequestId,
                            toolCallId = toolCallId,
                            toolName = toolName,
                            argumentsJson = argumentsJson,
                            rawOutput = progress,
                            isError = !AetherToolExecutor.inferToolOutputOk(
                                AetherToolExecutor.sanitizeToolOutputForConversation(toolName, progress),
                            ),
                        )
                    )
                },
            )
        }

        val responsePayload = result.fold(
            onSuccess = { executionResult ->
                hostToolPayload(
                    toolRequestId = toolRequestId,
                    toolCallId = toolCallId,
                    toolName = toolName,
                    argumentsJson = argumentsJson,
                    rawOutput = executionResult.rawOutput,
                    visibleOutput = executionResult.visibleOutput,
                    isError = executionResult.isError,
                )
            },
            onFailure = { throwable ->
                hostToolPayload(
                    toolRequestId = toolRequestId,
                    toolCallId = toolCallId,
                    toolName = toolName,
                    argumentsJson = argumentsJson,
                    rawOutput = JSONObject().apply {
                        put("ok", false)
                        put("errmsg", throwable.message ?: "Tool execution failed.")
                    }.toString(),
                    isError = true,
                )
            },
        )
        bridge.sendHostToolResult(responsePayload)
    }
}

private fun JSONObject.toToolEvent(isRunning: Boolean): AgentToolEvent =
    AgentToolEvent(
        id = optString("id").ifBlank { "pi-tool-${optInt("content_index", 0)}" },
        name = optString("name").ifBlank { "tool_call" },
        argumentsJson = argumentsJson(),
        outputJson = outputJson(),
        isRunning = isRunning,
    )

private fun JSONObject.argumentsJson(): String {
    val explicit = optString("arguments_json").trim()
    if (explicit.isNotBlank()) return explicit
    return when (val arguments = opt("arguments")) {
        is JSONObject -> arguments.toString()
        is JSONArray -> arguments.toString()
        is String -> arguments.ifBlank { "{}" }
        null,
        JSONObject.NULL -> optString("delta").ifBlank { "{}" }
        else -> JSONObject.wrap(arguments)?.toString() ?: "{}"
    }
}

private fun JSONObject.outputJson(): String? {
    val explicit = optString("output_json")
    if (explicit.isNotBlank()) return explicit
    return when (val output = opt("output")) {
        is JSONObject -> output.toString()
        is JSONArray -> output.toString()
        is String -> output.takeIf { it.isNotBlank() }
        else -> null
    }
}

private fun hostToolPayload(
    toolRequestId: String,
    toolCallId: String,
    toolName: String,
    argumentsJson: String,
    rawOutput: String,
    visibleOutput: String = AetherToolExecutor.sanitizeToolOutputForConversation(toolName, rawOutput),
    isError: Boolean,
): JSONObject = JSONObject().apply {
    put("tool_request_id", toolRequestId)
    put("tool_call_id", toolCallId)
    put("tool_name", toolName)
    put("arguments_json", argumentsJson)
    put("output_json", visibleOutput)
    put("raw_output_json", rawOutput)
    put("is_error", isError)
    put(
        "content",
        JSONArray().put(
            JSONObject().apply {
                put("type", "text")
                put("text", visibleOutput)
            }
        ),
    )
    put(
        "details",
        JSONObject().apply {
            put("tool_request_id", toolRequestId)
            put("tool_call_id", toolCallId)
            put("tool_name", toolName)
            put("arguments_json", argumentsJson)
            put("output_json", visibleOutput)
            put("raw_output_json", rawOutput)
            put("is_error", isError)
        },
    )
}
