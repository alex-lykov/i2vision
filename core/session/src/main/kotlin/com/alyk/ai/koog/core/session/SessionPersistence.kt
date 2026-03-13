package com.alyk.ai.koog.core.session

import kotlinx.serialization.json.Json
import java.util.*

/**
 * Session persistence and rollback capabilities
 * Implements the persistence features described in Koog documentation
 */

/**
 * Rollback strategies for session persistence
 */
enum class RollbackStrategy {
    DEFAULT,              // Full state (message history + current node + input data)
    MESSAGE_HISTORY_ONLY  // Only conversation history
}

/**
 * Rollback tool registry for handling tool side-effects
 */
class RollbackToolRegistry {
    private val rollbackActions = mutableMapOf<Class<*>, (Map<String, Any>) -> Unit>()
    
    fun registerRollback(toolClass: Class<*>, rollbackAction: (Map<String, Any>) -> Unit) {
        rollbackActions[toolClass] = rollbackAction
    }
    
    fun executeRollback(toolClass: Class<*>, parameters: Map<String, Any>) {
        rollbackActions[toolClass]?.invoke(parameters)
    }
}

/**
 * Session persistence manager
 * Handles saving, restoring, and rolling back session state
 */
class SessionPersistence(
    private val sessionStore: ISessionStore,
    private val rollbackRegistry: RollbackToolRegistry = RollbackToolRegistry(),
    private val json: Json = Json { ignoreUnknownKeys = true }
) {
    
    /**
     * Save session state to persistent storage
     */
    suspend fun saveSession(
        sessionId: UUID,
        session: AIAgentLLMWriteSession,
        strategy: RollbackStrategy = RollbackStrategy.DEFAULT
    ): Result<Unit> {
        return try {
            val sessionData = when (strategy) {
                RollbackStrategy.DEFAULT -> serializeFullSession(session)
                RollbackStrategy.MESSAGE_HISTORY_ONLY -> serializeHistoryOnly(session)
            }
            
            sessionStore.updateSession(sessionId) { currentSession ->
                currentSession.copy(
                    conversationHistory = sessionData.history.map { json.encodeToString(it) }.toMutableList(),
                    activeModel = sessionData.modelName
                )
            }
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * Restore session state from persistent storage
     */
    suspend fun restoreSession(
        sessionId: UUID,
        strategy: RollbackStrategy = RollbackStrategy.DEFAULT
    ): Result<SessionData> {
        return try {
            val storedSession = sessionStore.getSession(sessionId)
                ?: return Result.failure(IllegalStateException("Session not found: $sessionId"))
            
            val sessionData = when (strategy) {
                RollbackStrategy.DEFAULT -> deserializeFullSession(storedSession)
                RollbackStrategy.MESSAGE_HISTORY_ONLY -> deserializeHistoryOnly(storedSession)
            }
            
            Result.success(sessionData)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * Roll back tool side-effects
     */
    suspend fun rollbackToolEffects(
        toolExecutions: List<ToolExecution>
    ): Result<Unit> {
        return try {
            // Roll back in reverse order (LIFO)
            toolExecutions.reversed().forEach { execution ->
                try {
                    rollbackRegistry.executeRollback(execution.toolClass, execution.parameters)
                } catch (e: Exception) {
                    // Log error but continue with other rollbacks
                    println("Failed to rollback tool ${execution.toolClass.simpleName}: ${e.message}")
                }
            }
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * Clean up old sessions
     */
    suspend fun cleanup(maxAgeMinutes: Long = 60): Result<Int> {
        return try {
            sessionStore.cleanupInactiveSessions(maxAgeMinutes)
            Result.success(0) // Actual count would be returned by sessionStore
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    private fun serializeFullSession(session: AIAgentLLMWriteSession): SessionData {
        return SessionData(
            sessionId = session.sessionId,
            history = session.getHistory(),
            tools = session.getCurrentTools().tools.map { it.name },
            modelName = "unknown", // Can't access protected modelWrapper
            timestamp = System.currentTimeMillis()
        )
    }
    
    private fun serializeHistoryOnly(session: AIAgentLLMWriteSession): SessionData {
        return SessionData(
            sessionId = session.sessionId,
            history = session.getHistory(),
            tools = emptyList(), // Tools not saved in history-only mode
            modelName = "unknown", // Can't access protected modelWrapper
            timestamp = System.currentTimeMillis()
        )
    }
    
    private fun deserializeFullSession(storedSession: Session): SessionData {
        val history = storedSession.conversationHistory.mapNotNull { messageStr ->
            try {
                json.decodeFromString<SessionMessage>(messageStr)
            } catch (e: Exception) {
                // Fallback for old format
                SessionMessage(role = "user", content = messageStr)
            }
        }
        
        return SessionData(
            sessionId = storedSession.id.toString(),
            history = history,
            tools = emptyList(), // Tools would need to be reconstructed
            modelName = storedSession.activeModel ?: "unknown",
            timestamp = System.currentTimeMillis()
        )
    }
    
    private fun deserializeHistoryOnly(storedSession: Session): SessionData {
        return deserializeFullSession(storedSession) // Same implementation for now
    }
}

/**
 * Data class for session serialization
 */
data class SessionData(
    val sessionId: String,
    val history: List<SessionMessage>,
    val tools: List<String>,
    val modelName: String,
    val timestamp: Long
)

/**
 * Record of tool execution for rollback purposes
 */
data class ToolExecution(
    val toolClass: Class<*>,
    val parameters: Map<String, Any>,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Extension functions for session persistence
 */
suspend fun AIAgentLLMWriteSession.saveToPersistence(
    persistence: SessionPersistence,
    strategy: RollbackStrategy = RollbackStrategy.DEFAULT
): Result<Unit> {
    val sessionId = UUID.fromString(sessionId)
    return persistence.saveSession(sessionId, this, strategy)
}

suspend fun AIAgentLLMWriteSession.restoreFromPersistence(
    persistence: SessionPersistence,
    strategy: RollbackStrategy = RollbackStrategy.DEFAULT
): Result<SessionData> {
    val sessionId = UUID.fromString(sessionId)
    return persistence.restoreSession(sessionId, strategy)
}
