package com.zhousl.aether.channel

import com.zhousl.aether.data.SessionTurnOutcome
import kotlinx.coroutines.flow.Flow

data class SessionAgentRequest(
    val sessionId: String,
    val text: String,
    val sessionTitle: String,
    val source: String,
)

sealed interface SessionAgentEvent {
    data object Started : SessionAgentEvent
    data class TextDelta(val text: String, val accumulatedText: String) : SessionAgentEvent
    data class Completed(val text: String, val outcome: SessionTurnOutcome) : SessionAgentEvent
    data class Failed(val message: String, val cause: Throwable? = null) : SessionAgentEvent
}

fun interface SessionAgentProcessor {
    fun process(request: SessionAgentRequest): Flow<SessionAgentEvent>
}
