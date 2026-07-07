package com.zhousl.aether.data.chatdb

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.json.JSONObject

@Database(
    entities = [
        ChatSessionEntity::class,
        ChatMessageEntity::class,
        ChatWorkspaceFileRefEntity::class,
        ChatStateMetaEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class ChatHistoryDatabase : RoomDatabase() {
    abstract fun chatHistoryDao(): ChatHistoryDao

    companion object {
        @Volatile
        private var instance: ChatHistoryDatabase? = null

        fun getInstance(context: Context): ChatHistoryDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                ChatHistoryDatabase::class.java,
                "aether_chat_history.db",
            ).addMigrations(Migration1To2)
                .build()
                .also { instance = it }
        }
    }
}

internal object Migration1To2 : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `chat_workspace_file_refs` (
                `sessionId` TEXT NOT NULL,
                `messageId` TEXT NOT NULL,
                `path` TEXT NOT NULL,
                PRIMARY KEY(`sessionId`, `messageId`, `path`),
                FOREIGN KEY(`sessionId`, `messageId`) REFERENCES `chat_messages`(`sessionId`, `id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_chat_workspace_file_refs_path` ON `chat_workspace_file_refs` (`path`)",
        )
        val workspaceFileRefsBackfilled = backfillWorkspaceFileRefs(db)
        db.execSQL(
            "ALTER TABLE `chat_state_meta` ADD COLUMN `workspaceFileRefsComplete` INTEGER NOT NULL DEFAULT 0",
        )
        if (workspaceFileRefsBackfilled) {
            db.execSQL(
                """
                INSERT OR IGNORE INTO `chat_state_meta` (
                    `id`,
                    `currentSessionId`,
                    `roomMigrationComplete`,
                    `workspaceFileRefsComplete`
                ) VALUES (?, NULL, 0, 1)
                """.trimIndent(),
                arrayOf(ChatStateMetaEntityId),
            )
            db.execSQL("UPDATE `chat_state_meta` SET `workspaceFileRefsComplete` = 1")
        }
    }

    private fun backfillWorkspaceFileRefs(db: SupportSQLiteDatabase): Boolean {
        var complete = true
        db.query("SELECT `sessionId`, `id`, `messageJson` FROM `chat_messages`").use { cursor ->
            while (cursor.moveToNext()) {
                val sessionId = cursor.getString(0)
                val messageId = cursor.getString(1)
                val messageJson = cursor.getString(2)
                val paths = runCatching { collectWorkspaceFilePaths(JSONObject(messageJson)) }
                    .getOrElse {
                        complete = false
                        emptySet()
                    }
                paths.forEach { path ->
                    db.execSQL(
                        """
                        INSERT OR IGNORE INTO `chat_workspace_file_refs` (`sessionId`, `messageId`, `path`)
                        VALUES (?, ?, ?)
                        """.trimIndent(),
                        arrayOf(sessionId, messageId, path),
                    )
                }
            }
        }
        return complete
    }

    private fun collectWorkspaceFilePaths(message: JSONObject): Set<String> = buildSet {
        collectWorkspaceFilePaths(message, this)
    }

    private fun collectWorkspaceFilePaths(
        message: JSONObject,
        paths: MutableSet<String>,
    ) {
        val attachments = message.optJSONArray("attachments")
        if (attachments != null) {
            for (index in 0 until attachments.length()) {
                attachments.optJSONObject(index)
                    ?.optString("workspacePath")
                    ?.trim()
                    ?.takeIf(String::isNotEmpty)
                    ?.let(paths::add)
            }
        }

        val branches = message.optJSONObject("branchGroup")?.optJSONArray("branches") ?: return
        for (branchIndex in 0 until branches.length()) {
            val branch = branches.optJSONArray(branchIndex) ?: continue
            for (messageIndex in 0 until branch.length()) {
                branch.optJSONObject(messageIndex)?.let { branchMessage ->
                    collectWorkspaceFilePaths(branchMessage, paths)
                }
            }
        }
    }
}
