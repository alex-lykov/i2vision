package com.alyk.ai.koog.core.session

import java.util.*

/**
 * Contract for session persistence (in-memory or database-backed).
 */
interface ISessionStore {
    suspend fun createSession(projectPath: String? = null): UUID
    suspend fun getSession(sessionId: UUID): Session?
    suspend fun updateSession(sessionId: UUID, update: (Session) -> Session)
    suspend fun deleteSession(sessionId: UUID)
    suspend fun cleanupInactiveSessions(maxAgeMinutes: Long = 60)
}
