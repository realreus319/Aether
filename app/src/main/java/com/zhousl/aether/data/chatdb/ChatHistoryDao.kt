package com.zhousl.aether.data.chatdb

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatHistoryDao {
    @Query("SELECT * FROM chat_sessions ORDER BY sortOrder ASC")
    fun observeSessions(): Flow<List<ChatSessionEntity>>

    @Query("SELECT * FROM chat_sessions ORDER BY sortOrder ASC")
    suspend fun getSessions(): List<ChatSessionEntity>

    @Query("SELECT * FROM chat_state_meta WHERE id = :id")
    fun observeMeta(id: String = ChatStateMetaEntityId): Flow<ChatStateMetaEntity?>

    @Query("SELECT * FROM chat_state_meta WHERE id = :id")
    suspend fun getMeta(id: String = ChatStateMetaEntityId): ChatStateMetaEntity?

    @Query("""
        SELECT sessionId, id, position, author, text, createdAtMillis, responseGroupId, displayKind, messageSchemaVersion, length(messageJson) AS messageJsonLength
        FROM chat_messages
        WHERE sessionId = :sessionId
        ORDER BY position ASC
    """)
    fun observeMessageSummariesForSession(sessionId: String): Flow<List<ChatMessageSummaryEntity>>

    @Query("""
        SELECT sessionId, id, position, author, text, createdAtMillis, responseGroupId, displayKind, messageSchemaVersion, length(messageJson) AS messageJsonLength
        FROM chat_messages
        WHERE sessionId IN (:sessionIds)
        ORDER BY sessionId ASC, position ASC
    """)
    fun observeMessageSummariesForSessions(sessionIds: List<String>): Flow<List<ChatMessageSummaryEntity>>

    @Query("""
        SELECT length(messageJson)
        FROM chat_messages
        WHERE sessionId = :sessionId AND id = :messageId
    """)
    suspend fun getMessageJsonLength(
        sessionId: String,
        messageId: String,
    ): Int?

    @Query("""
        SELECT substr(messageJson, :start, :length)
        FROM chat_messages
        WHERE sessionId = :sessionId AND id = :messageId
    """)
    suspend fun getMessageJsonChunk(
        sessionId: String,
        messageId: String,
        start: Int,
        length: Int,
    ): String?

    @Upsert
    suspend fun upsertMeta(meta: ChatStateMetaEntity)

    @Upsert
    suspend fun upsertSession(session: ChatSessionEntity)

    @Upsert
    suspend fun upsertSessions(sessions: List<ChatSessionEntity>)

    @Upsert
    suspend fun upsertMessage(message: ChatMessageEntity)

    @Upsert
    suspend fun upsertMessages(messages: List<ChatMessageEntity>)

    @Query("DELETE FROM chat_messages WHERE sessionId = :sessionId AND position >= :fromPosition")
    suspend fun deleteMessagesFromPosition(sessionId: String, fromPosition: Int)

    @Query("DELETE FROM chat_sessions WHERE id = :sessionId")
    suspend fun deleteSession(sessionId: String)

    @Query("DELETE FROM chat_sessions WHERE id NOT IN (:sessionIds)")
    suspend fun deleteSessionsExcept(sessionIds: List<String>)

    @Query("DELETE FROM chat_messages WHERE sessionId = :sessionId")
    suspend fun deleteMessagesForSession(sessionId: String)

    @Query("DELETE FROM chat_messages WHERE sessionId NOT IN (:sessionIds)")
    suspend fun deleteMessagesExceptSessions(sessionIds: List<String>)

    @Query("DELETE FROM chat_messages")
    suspend fun deleteAllMessages()

    @Query("DELETE FROM chat_sessions")
    suspend fun deleteAllSessions()
}
