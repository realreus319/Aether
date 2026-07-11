package com.zhousl.aether.data

data class AetherAgentTurnResult(
    val assistantText: String,
    val tokenUsage: LlmTokenUsage? = null,
    val providerPayloadJson: String = "",
)

data class AgentToolEvent(
    val id: String,
    val name: String,
    val argumentsJson: String,
    val outputJson: String? = null,
    val isRunning: Boolean? = null,
)

data class StreamingStatus(
    val text: String,
    val detail: String = "",
)
