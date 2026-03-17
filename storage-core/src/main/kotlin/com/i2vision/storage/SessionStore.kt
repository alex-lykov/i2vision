package com.i2vision.storage

import java.util.*

/**
 * Contract for session persistence (in-memory or database-backed).
 */
interface SessionStore {
    suspend fun createSession(projectPath: String? = null): UUID
    suspend fun getSession(sessionId: UUID): Session?
    suspend fun updateSession(sessionId: UUID, update: (Session) -> Session)
    suspend fun deleteSession(sessionId: UUID)
    suspend fun cleanupInactiveSessions(maxAgeMinutes: Long = 60)
}

/**
 * Session data model
 */
data class Session(
    val sessionId: UUID,
    val projectPath: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val lastActiveAt: Long = System.currentTimeMillis(),
    val metadata: Map<String, Any> = emptyMap()
)
