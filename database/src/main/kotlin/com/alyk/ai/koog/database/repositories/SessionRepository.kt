package com.alyk.ai.koog.database.repositories

import com.alyk.ai.koog.database.DatabaseProvider
import com.alyk.ai.koog.database.store.AgentStateStore
import com.alyk.ai.koog.database.store.PersistedSession
import java.util.*

/**
 * Session persistence. Prefer using [DatabaseProvider.agentState] ([AgentStateStore])
 * for new code; this class delegates to it when a [DatabaseProvider] is supplied.
 */
class SessionRepository(private val agentState: AgentStateStore? = null) {

    suspend fun createSession(projectPath: String? = null): UUID =
        agentState?.createSession(projectPath) ?: throw UnsupportedOperationException("No DatabaseProvider.agentState")

    suspend fun getSession(sessionId: UUID): PersistedSession? = agentState?.getSession(sessionId)

    suspend fun updateSession(sessionId: UUID, update: (PersistedSession) -> PersistedSession) {
        agentState?.updateSession(sessionId, update) ?: throw UnsupportedOperationException("No DatabaseProvider.agentState")
    }

    suspend fun appendMessage(sessionId: UUID, message: String) {
        agentState?.appendMessage(sessionId, message) ?: throw UnsupportedOperationException("No DatabaseProvider.agentState")
    }

    suspend fun deleteSession(sessionId: UUID) {
        agentState?.deleteSession(sessionId) ?: throw UnsupportedOperationException("No DatabaseProvider.agentState")
    }
}
