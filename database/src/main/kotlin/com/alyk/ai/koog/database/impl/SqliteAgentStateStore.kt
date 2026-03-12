package com.alyk.ai.koog.database.impl

import com.alyk.ai.koog.database.schema.SessionsTable
import com.alyk.ai.koog.database.store.AgentStateStore
import com.alyk.ai.koog.database.store.PersistedSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.*

class SqliteAgentStateStore(private val db: Database) : AgentStateStore {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun createSession(projectPath: String?): UUID = withContext(Dispatchers.IO) {
        transaction(db) {
            val id = UUID.randomUUID()
            SessionsTable.insert {
                it[SessionsTable.id] = id.toString()
                it[SessionsTable.projectPath] = projectPath
                it[SessionsTable.conversationHistory] = json.encodeToString(ListSerializer(String.serializer()), emptyList())
                it[SessionsTable.activeModel] = null
                it[SessionsTable.updatedAt] = System.currentTimeMillis()
            }
            id
        }
    }

    override suspend fun getSession(sessionId: UUID): PersistedSession? = withContext(Dispatchers.IO) {
        transaction(db) {
            SessionsTable.select { SessionsTable.id eq sessionId.toString() }.singleOrNull()?.toSession()
        }
    }

    override suspend fun updateSession(sessionId: UUID, update: (PersistedSession) -> PersistedSession) = withContext(Dispatchers.IO) {
        transaction(db) {
            val current = SessionsTable.select { SessionsTable.id eq sessionId.toString() }.singleOrNull()?.toSession() ?: return@transaction
            val updated = update(current)
            SessionsTable.update({ SessionsTable.id eq sessionId.toString() }) {
                it[SessionsTable.projectPath] = updated.projectPath
                it[SessionsTable.conversationHistory] = json.encodeToString(ListSerializer(String.serializer()), updated.conversationHistory)
                it[SessionsTable.activeModel] = updated.activeModel
                it[SessionsTable.updatedAt] = System.currentTimeMillis()
            }
        }
    }

    override suspend fun appendMessage(sessionId: UUID, message: String) = withContext(Dispatchers.IO) {
        transaction(db) {
            val row = SessionsTable.select { SessionsTable.id eq sessionId.toString() }.singleOrNull() ?: return@transaction
            val list = json.decodeFromString<List<String>>(row[SessionsTable.conversationHistory]) + message
            SessionsTable.update({ SessionsTable.id eq sessionId.toString() }) {
                it[SessionsTable.conversationHistory] = json.encodeToString(ListSerializer(String.serializer()), list)
                it[SessionsTable.updatedAt] = System.currentTimeMillis()
            }
        }
    }

    override suspend fun setConversationHistory(sessionId: UUID, messages: List<String>) = withContext(Dispatchers.IO) {
        transaction(db) {
            SessionsTable.update({ SessionsTable.id eq sessionId.toString() }) {
                it[SessionsTable.conversationHistory] = json.encodeToString(ListSerializer(String.serializer()), messages)
                it[SessionsTable.updatedAt] = System.currentTimeMillis()
            }
            Unit
        }
    }

    override suspend fun deleteSession(sessionId: UUID) = withContext(Dispatchers.IO) {
        transaction(db) {
            SessionsTable.deleteWhere { SessionsTable.id eq sessionId.toString() }
            Unit
        }
    }

    override suspend fun listSessions(projectPath: String?, limit: Int): List<UUID> = withContext(Dispatchers.IO) {
        transaction(db) {
            val query = if (projectPath != null) {
                SessionsTable.select { SessionsTable.projectPath eq projectPath }
            } else {
                SessionsTable.selectAll()
            }
            query.orderBy(SessionsTable.updatedAt, SortOrder.DESC)
                .limit(limit)
                .map { UUID.fromString(it[SessionsTable.id]) }
        }
    }

    override suspend fun cleanupInactiveSessions(maxAgeMinutes: Long) = withContext(Dispatchers.IO) {
        transaction(db) {
            val cutoff = System.currentTimeMillis() - maxAgeMinutes * 60 * 1000
            SessionsTable.deleteWhere { SessionsTable.updatedAt less cutoff }
            Unit
        }
    }

    private fun org.jetbrains.exposed.sql.ResultRow.toSession(): PersistedSession {
        val history = json.decodeFromString<List<String>>(this[SessionsTable.conversationHistory])
        return PersistedSession(
            id = UUID.fromString(this[SessionsTable.id]),
            projectPath = this[SessionsTable.projectPath],
            conversationHistory = history,
            activeModel = this[SessionsTable.activeModel],
            updatedAtMillis = this[SessionsTable.updatedAt]
        )
    }
}
