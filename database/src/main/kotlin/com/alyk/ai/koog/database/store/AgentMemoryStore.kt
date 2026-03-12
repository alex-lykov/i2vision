package com.alyk.ai.koog.database.store

import java.util.*

/**
 * Agent memory: persistent facts and knowledge for the Koog agent.
 * Used for knowledge retention across runs and sharing between agents.
 */
interface AgentMemoryStore {
    /**
     * Store a fact (concept-based or freeform).
     * @param concept e.g. "project-structure", "agent-goal", "important-achievements"
     * @param content fact content
     * @param sessionId optional session scope
     * @param agentId optional agent scope (e.g. "implementation", "idea")
     * @param projectId optional project scope
     */
    suspend fun addFact(
        concept: String,
        content: String,
        sessionId: UUID? = null,
        agentId: String? = null,
        projectId: String? = null
    ): String

    /**
     * Get facts by concept and optional scope.
     */
    suspend fun getFacts(
        concept: String? = null,
        sessionId: UUID? = null,
        agentId: String? = null,
        projectId: String? = null,
        limit: Int = 50
    ): List<StoredFact>

    /**
     * Delete facts by id or by scope (e.g. clear session memory).
     */
    suspend fun deleteFacts(
        factIds: List<String>? = null,
        sessionId: UUID? = null,
        projectId: String? = null
    )

    /**
     * List distinct concept names (for discovery).
     */
    suspend fun listConcepts(projectId: String? = null): List<String>
}

data class StoredFact(
    val id: String,
    val concept: String,
    val content: String,
    val sessionId: UUID?,
    val agentId: String?,
    val projectId: String?,
    val createdAtMillis: Long
)
