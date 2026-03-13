package com.alyk.ai.koog.core.session

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.ToolRegistry
import com.alyk.ai.koog.models.wrappers.ModelWrapper
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Session lifecycle manager
 * Coordinates the creation and management of AIAgentLLMSession instances
 * Provides factory methods for different session types
 */
class SessionManager(
    private val modelWrapper: ModelWrapper,
    private val initialToolRegistry: ToolRegistry = ToolRegistry.EMPTY
) {
    private val activeSessions = ConcurrentHashMap<String, AIAgentLLMSession>()
    private val sessionMutex = Mutex()
    
    /**
     * Create a new write session
     */
    suspend fun writeSession(block: suspend (AIAgentLLMWriteSession) -> Unit) {
        writeSessionWithResult { block(it); Unit }
    }

    /**
     * Create a new write session and return a result from the block
     */
    suspend fun <T> writeSessionWithResult(block: suspend (AIAgentLLMWriteSession) -> T): T {
        return sessionMutex.withLock {
            val session = AIAgentLLMWriteSession(modelWrapper, initialToolRegistry)
            activeSessions[session.sessionId] = session
            try {
                block(session)
            } finally {
                session.close()
                activeSessions.remove(session.sessionId)
            }
        }
    }
    
    /**
     * Create a new read session for an existing write session
     */
    suspend fun readSession(sessionId: String, block: suspend (AIAgentLLMReadSession) -> Unit) {
        sessionMutex.withLock {
            val writeSession = activeSessions[sessionId] as? AIAgentLLMWriteSession
                ?: throw IllegalArgumentException("Session not found: $sessionId")
            
            val readSession = AIAgentLLMReadSession(writeSession)
            block(readSession)
        }
    }
    
    /**
     * Get active session count
     */
    fun getActiveSessionCount(): Int = activeSessions.size
    
    /**
     * Get all active session IDs
     */
    fun getActiveSessionIds(): Set<String> = activeSessions.keys.toSet()
    
    /**
     * Check if a session is active
     */
    fun isSessionActive(sessionId: String): Boolean = activeSessions.containsKey(sessionId)
    
    /**
     * Close all active sessions
     */
    suspend fun closeAllSessions() {
        sessionMutex.withLock {
            activeSessions.values.forEach { it.close() }
            activeSessions.clear()
        }
    }
}

/**
 * Extension functions for easier session usage
 */
suspend fun SessionManager.createCodingSession(
    task: String,
    tools: List<Tool<*, *>> = emptyList(),
    block: suspend (AIAgentLLMWriteSession, String) -> String
): String {
    var result = ""
    
    writeSession { session ->
        // Add coding-specific tools
        tools.forEach { session.appendTool(it) }
        
        // Add initial context
        session.appendPrompt {
            system("You are a coding assistant with access to file system and development tools.")
            user("Task: $task")
        }
        
        // Execute task
        result = block(session, task)
    }
    
    return result
}
