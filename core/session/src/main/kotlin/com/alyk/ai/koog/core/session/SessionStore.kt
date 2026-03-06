package com.alyk.ai.koog.core.session

import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Preserve conversation context, manage session lifecycle,
 * and provide persistence for agent state.
 */
class SessionStore {
    private val sessions = ConcurrentHashMap<UUID, Session>()

    /**
     * SessionStore: ConcurrentHashMap with coroutine-safe access patterns
     */
    suspend fun createSession(projectPath: String? = null): UUID {
        val sessionId = UUID.randomUUID()
        sessions[sessionId] = Session(
            id = sessionId,
            projectPath = projectPath,
            conversationHistory = mutableListOf()
        )
        return sessionId
    }

    suspend fun getSession(sessionId: UUID): Session? {
        return sessions[sessionId]
    }

    suspend fun updateSession(sessionId: UUID, update: (Session) -> Session) {
        sessions[sessionId]?.let { session ->
            sessions[sessionId] = update(session)
        }
    }

    suspend fun deleteSession(sessionId: UUID) {
        sessions.remove(sessionId)
    }

    /**
     * Session cleanup scheduler for inactive sessions
     */
    suspend fun cleanupInactiveSessions(maxAgeMinutes: Long = 60) {
        // TODO: Implement cleanup logic
    }
}

data class Session(
    val id: UUID,
    val projectPath: String?,
    val conversationHistory: MutableList<String>,
    val activeModel: String? = null
)
