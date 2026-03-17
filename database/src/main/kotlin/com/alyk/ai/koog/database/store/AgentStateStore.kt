package com.alyk.ai.koog.database.store

import java.util.*

/**
 * Persistence for agent session state and conversation history.
 * Used for Agent State Persistence so sessions survive restarts.
 */
interface AgentStateStore {
    /**
     * Create a new session. Returns the new session id.
     */
    suspend fun createSession(projectPath: String? = null): UUID

    /**
     * Get session by id, or null if not found.
     */
    suspend fun getSession(sessionId: UUID): PersistedSession?

    /**
     * Update session (e.g. project path, active model).
     */
    suspend fun updateSession(sessionId: UUID, update: (PersistedSession) -> PersistedSession)

    /**
     * Append a message to the conversation history for the session.
     */
    suspend fun appendMessage(sessionId: UUID, message: String)

    /**
     * Replace full conversation history for the session.
     */
    suspend fun setConversationHistory(sessionId: UUID, messages: List<String>)

    /**
     * Delete a session.
     */
    suspend fun deleteSession(sessionId: UUID)

    /**
     * List session IDs, optionally filtered by project path or limit.
     */
    suspend fun listSessions(projectPath: String? = null, limit: Int = 100): List<UUID>

    /**
     * Clean up sessions older than [maxAgeMinutes].
     */
    suspend fun cleanupInactiveSessions(maxAgeMinutes: Long = 60)
}

/**
 * Session data as persisted (e.g. in database).
 * decisionLogJson is raw JSON; decode in DatabaseBackedSessionStore using core session types.
 */
data class PersistedSession(
    val id: UUID,
    val projectPath: String?,
    val conversationHistory: List<String>,
    val inputHistory: List<String> = emptyList(),
    val activeModel: String? = null,
    val updatedAtMillis: Long = System.currentTimeMillis(),
    val currentPhase: String? = null,
    val currentGoal: String? = null,
    val workflowPhase: String? = null,
    val completedSteps: List<String> = emptyList(),
    val decisionLogJson: String? = null
)
