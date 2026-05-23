/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.agent.server

import com.i2vision.agent.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Represents an active agent session.
 * 
 * @param id Session ID
 * @param displayName Human-readable name
 * @param layer Agent layer (koog, etc.)
 * @param agent The agent instance
 */
class AgentSession(
    val id: String,
    val displayName: String,
    val layer: VslfcLayer,
    private val agent: I2VisionAgent
) {
    /**
     * Current job for streaming requests.
     */
    private var currentJob: Job? = null
    
    /**
     * Session creation timestamp.
     */
    val createdAt: Long = System.currentTimeMillis()
    
    /**
     * Last activity timestamp.
     */
    var lastActivityAt: Long = System.currentTimeMillis()
        private set
    
    /**
     * Check if session is idle.
     */
    fun isIdle(timeoutMs: Long): Boolean {
        return System.currentTimeMillis() - lastActivityAt > timeoutMs
    }
    
    /**
     * Process a task and return the response (blocking).
     * 
     * @param task The task to process
     * @return The agent response
     */
    suspend fun process(task: String): AgentResponse {
        lastActivityAt = System.currentTimeMillis()
        return agent.process(task)
    }
    
    /**
     * Process a task with streaming.
     * 
     * @param task The task to process
     * @param collector Callback for each chunk
     */
    suspend fun processStreaming(task: String, collector: suspend (AgentChunk) -> Unit) {
        lastActivityAt = System.currentTimeMillis()
        
        currentJob?.cancel()
        currentJob = CoroutineScope(Dispatchers.Default).launch {
            agent.processStreaming(task).collect(collector)
        }
        currentJob?.join()
    }
    
    /**
     * Cancel the current running request.
     */
    fun cancel() {
        currentJob?.cancel()
        currentJob = null
    }
    
    /**
     * Dispose the session and release resources.
     */
    fun dispose() {
        cancel()
        agent.dispose()
    }
}

/**
 * Manages agent sessions with automatic cleanup.
 * 
 * @param idleTimeoutMs Session idle timeout in milliseconds
 * @param agentProvider Provider for creating agent instances
 */
class SessionManager(
    private val idleTimeoutMs: Long,
    private val agentProvider: I2VisionAgentProvider
) {
    /**
     * Active sessions.
     */
    private val sessions = ConcurrentHashMap<String, AgentSession>()
    
    /**
     * Scope for session management.
     */
    private val managerScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    /**
     * Start idle session cleanup.
     */
    init {
        managerScope.launch {
            while (isActive) {
                delay(idleTimeoutMs / 2)
                cleanupIdleSessions()
            }
        }
    }
    
    /**
     * Create a new session.
     * 
     * @param sessionId Optional session ID (generated if null)
     * @param configId Configuration ID
     * @param layerName Layer name
     * @return The created session
     */
    fun createSession(sessionId: String? = null, configId: String = "default", layerName: String = "koog"): AgentSession {
        val id = sessionId ?: UUID.randomUUID().toString()
        
        // Remove existing session with same ID
        sessions.remove(id)?.dispose()
        
        // Create agent instance
        val layer = VslfcLayer.valueOf(layerName.uppercase())
        val agent = agentProvider.createAgent(configId, layer)
        
        val session = AgentSession(
            id = id,
            displayName = "${configId}-${layer.name}",
            layer = layer,
            agent = agent
        )
        
        sessions[id] = session
        return session
    }
    
    /**
     * Get a session by ID.
     * 
     * @param sessionId Session ID
     * @return The session or null if not found
     */
    fun getSession(sessionId: String): AgentSession? {
        return sessions[sessionId]
    }
    
    /**
     * Remove a session.
     * 
     * @param sessionId Session ID
     */
    fun removeSession(sessionId: String) {
        sessions.remove(sessionId)?.dispose()
    }
    
    /**
     * List all available configurations.
     * 
     * @return List of agent configurations
     */
    fun listConfigurations(): List<AgentConfig> {
        return agentProvider.listConfigurations()
    }
    
    /**
     * Get a specific configuration.
     * 
     * @param configId Configuration ID
     * @return The configuration or null if not found
     */
    fun getConfiguration(configId: String): AgentConfig? {
        return agentProvider.getConfiguration(configId)
    }
    
    /**
     * Update a configuration.
     * 
     * @param config The updated configuration
     */
    fun updateConfiguration(config: AgentConfig) {
        agentProvider.updateConfiguration(config)
    }
    
    /**
     * Get all active session IDs.
     */
    fun getActiveSessionIds(): Set<String> {
        return sessions.keys.toSet()
    }
    
    /**
     * Get session count.
     */
    fun getSessionCount(): Int {
        return sessions.size
    }
    
    /**
     * Cleanup idle sessions.
     */
    private fun cleanupIdleSessions() {
        val idleSessions = sessions.filterValues { it.isIdle(idleTimeoutMs) }
        idleSessions.forEach { (id, session) ->
            println("Removing idle session: $id")
            sessions.remove(id)?.dispose()
        }
    }
    
    /**
     * Dispose all sessions and stop cleanup job.
     */
    fun dispose() {
        managerScope.cancel()
        sessions.values.forEach { it.dispose() }
        sessions.clear()
    }
}
