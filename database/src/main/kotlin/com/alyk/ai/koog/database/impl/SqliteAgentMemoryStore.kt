package com.alyk.ai.koog.database.impl

import com.alyk.ai.koog.database.schema.AgentMemoryTable
import com.alyk.ai.koog.database.store.AgentMemoryStore
import com.alyk.ai.koog.database.store.StoredFact
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.*

class SqliteAgentMemoryStore(private val db: Database) : AgentMemoryStore {

    override suspend fun addFact(
        concept: String,
        content: String,
        sessionId: UUID?,
        agentId: String?,
        projectId: String?
    ): String = withContext(Dispatchers.IO) {
        transaction(db) {
            val id = "fact_${UUID.randomUUID().toString().replace("-", "").take(24)}"
            val now = System.currentTimeMillis()
            AgentMemoryTable.insert {
                it[AgentMemoryTable.id] = id
                it[AgentMemoryTable.concept] = concept
                it[AgentMemoryTable.content] = content
                it[AgentMemoryTable.sessionId] = sessionId?.toString()
                it[AgentMemoryTable.agentId] = agentId
                it[AgentMemoryTable.projectId] = projectId
                it[AgentMemoryTable.createdAt] = now
            }
            id
        }
    }

    override suspend fun getFacts(
        concept: String?,
        sessionId: UUID?,
        agentId: String?,
        projectId: String?,
        limit: Int
    ): List<StoredFact> = withContext(Dispatchers.IO) {
        transaction(db) {
            val conditions = listOfNotNull(
                concept?.let { AgentMemoryTable.concept eq it },
                sessionId?.let { AgentMemoryTable.sessionId eq it.toString() },
                agentId?.let { AgentMemoryTable.agentId eq it },
                projectId?.let { AgentMemoryTable.projectId eq it }
            )
            val query = when {
                conditions.isEmpty() -> AgentMemoryTable.selectAll()
                conditions.size == 1 -> AgentMemoryTable.select { conditions.single() }
                else -> AgentMemoryTable.select { conditions.reduce { a, b -> a and b } }
            }
            query.limit(limit).map { it.toFact() }
        }
    }

    override suspend fun deleteFacts(
        factIds: List<String>?,
        sessionId: UUID?,
        projectId: String?
    ) = withContext(Dispatchers.IO) {
        transaction(db) {
            when {
                factIds != null && factIds.isNotEmpty() -> {
                    factIds.forEach { id ->
                        AgentMemoryTable.deleteWhere { AgentMemoryTable.id eq id }
                    }
                }
                sessionId != null -> AgentMemoryTable.deleteWhere { AgentMemoryTable.sessionId eq sessionId.toString() }
                projectId != null -> AgentMemoryTable.deleteWhere { AgentMemoryTable.projectId eq projectId }
                else -> { /* no-op if no filter */ }
            }
            Unit
        }
    }

    override suspend fun listConcepts(projectId: String?): List<String> = withContext(Dispatchers.IO) {
        transaction(db) {
            val query = if (projectId != null) {
                AgentMemoryTable.select { AgentMemoryTable.projectId eq projectId }
            } else {
                AgentMemoryTable.selectAll()
            }
            query.map { it[AgentMemoryTable.concept] }.distinct()
        }
    }

    private fun org.jetbrains.exposed.sql.ResultRow.toFact(): StoredFact = StoredFact(
        id = this[AgentMemoryTable.id],
        concept = this[AgentMemoryTable.concept],
        content = this[AgentMemoryTable.content],
        sessionId = this[AgentMemoryTable.sessionId]?.let { UUID.fromString(it) },
        agentId = this[AgentMemoryTable.agentId],
        projectId = this[AgentMemoryTable.projectId],
        createdAtMillis = this[AgentMemoryTable.createdAt]
    )
}
