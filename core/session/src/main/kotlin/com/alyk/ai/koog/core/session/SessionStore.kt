package com.alyk.ai.koog.core.session

import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory session store. Preserve conversation context, manage session lifecycle.
 * For persistent storage use a database-backed implementation (see launcher).
 */
class SessionStore : ISessionStore {
    private val sessions = ConcurrentHashMap<UUID, Session>()

    /**
     * SessionStore: ConcurrentHashMap with coroutine-safe access patterns
     */
    override suspend fun createSession(projectPath: String?): UUID {
        val sessionId = UUID.randomUUID()
        sessions[sessionId] = Session(
            id = sessionId,
            projectPath = projectPath,
            conversationHistory = mutableListOf()
        )
        return sessionId
    }

    override suspend fun getSession(sessionId: UUID): Session? {
        return sessions[sessionId]
    }

    override suspend fun updateSession(sessionId: UUID, update: (Session) -> Session) {
        sessions[sessionId]?.let { session ->
            sessions[sessionId] = update(session)
        }
    }

    override suspend fun deleteSession(sessionId: UUID) {
        sessions.remove(sessionId)
    }

    /**
     * Session cleanup scheduler for inactive sessions
     */
    override suspend fun cleanupInactiveSessions(maxAgeMinutes: Long) {
        // TODO: Implement cleanup logic
    }
}

data class Session(
    val id: UUID,
    val projectPath: String?,
    val conversationHistory: MutableList<String>,
    val activeModel: String? = null,
    /** Current phase (e.g. "design", "implementation", "testing") */
    val currentPhase: String? = null,
    /** Current goal (e.g. "Add auth to feature X") */
    val currentGoal: String? = null,
    /** Workflow phase for progress tracking */
    val workflowPhase: String? = null,
    /** Completed workflow steps (e.g. ["requirements", "design"]) */
    val completedSteps: MutableList<String> = mutableListOf(),
    /** Log of execution decisions with rationale */
    val decisionLog: MutableList<DecisionLogEntry> = mutableListOf()
)
