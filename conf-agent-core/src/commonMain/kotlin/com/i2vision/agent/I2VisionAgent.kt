/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.agent

import kotlinx.coroutines.flow.Flow

/**
 * Core interface for all i2vision agents.
 * 
 * An agent is bound to a specific VSLFC layer and processes tasks
 * with full i2vision context (instant context, discovery cache, contracts).
 * 
 * ## Implementation Types
 * 
 * 1. **KoogI2VisionAgent** - Kotlin/JVM implementation using JetBrains Koog framework
 * 2. **LocalTypeScriptAgent** - TypeScript implementation for VS Code development/testing
 * 3. **BridgedAgent** - TypeScript wrapper communicating with remote Kotlin agent via JSON-RPC
 * 
 * ## Usage Example
 * 
 * ```kotlin
 * // Create an agent for the CODE layer
 * val agent = createAgent(VslfcLayer.CODE, config)
 * 
 * // Process a task synchronously
 * val request = AgentRequest(
 *     id = AgentRequest.generateId(),
 *     task = "Refactor this function to use coroutines",
 *     context = AgentContext(
 *         workspaceRoot = "/path/to/project",
 *         currentFile = "/path/to/project/src/main/kotlin/MyClass.kt",
 *         sessionId = "session-123"
 *     )
 * )
 * 
 * val response = agent.process(request)
 * println("Outcome: ${response.outcome}")
 * println("Response: ${response.finalText}")
 * 
 * // Or process with streaming
 * agent.processStreaming(request).collect { chunk ->
 *     when (chunk) {
 *         is AgentChunk.Reasoning -> println("Thinking: ${chunk.text}")
 *         is AgentChunk.ToolCallStarted -> println("Calling tool: ${chunk.toolName}")
 *         is AgentChunk.Text -> print(chunk.text)
 *         is AgentChunk.Done -> println("\nCompleted: ${chunk.outcome}")
 *         is AgentChunk.ChunkError -> println("Error: ${chunk.error.message}")
 *         else -> {}
 *     }
 * }
 * ```
 * 
 * ## Lifecycle
 * 
 * 1. **Create** - Agent is created with a specific VSLFC layer and configuration
 * 2. **Process** - Agent processes tasks via process() or processStreaming()
 * 3. **Cancel** - Long-running tasks can be cancelled via cancel()
 * 4. **Update** - Configuration can be updated at runtime via updateConfig()
 * 5. **Dispose** - Agent resources should be cleaned up when no longer needed
 * 
 * @property id Unique agent instance identifier
 * @property layer VSLFC layer this agent specializes in
 * @property displayName Human-readable name for UI display
 * @property capabilities Agent capabilities (tools, streaming, etc.)
 */
interface I2VisionAgent {
    
    /**
     * Unique agent instance identifier.
     * 
     * Format: "agent-{layer}-{timestamp}-{random}"
     * Example: "agent-CODE-1234567890-abc123"
     */
    val id: String
    
    /**
     * VSLFC layer this agent specializes in.
     * 
     * The layer influences:
     * - Which tools are available
     * - What context is prioritized
     * - How responses are structured
     * - What contracts are validated
     */
    val layer: VslfcLayer
    
    /**
     * Human-readable name for UI display.
     * 
     * Example: "Code Agent (Ollama llama3.2:3b)"
     */
    val displayName: String
    
    /**
     * Agent capabilities declaration.
     * 
     * Used by clients to:
     * - Adapt UI based on capabilities
     * - Validate requests against limits
     * - Display available tools
     * - Select appropriate agents for tasks
     */
    val capabilities: AgentCapabilities
    
    /**
     * Process a task synchronously.
     * 
     * Executes the agent's reasoning loop until:
     * - Task is completed successfully
     * - Maximum iterations are reached
     * - User cancels the task
     * - An unrecoverable error occurs
     * 
     * This method blocks until completion. For real-time UI updates,
     * use processStreaming() instead.
     * 
     * @param request The task request with context and configuration
     * @return Complete response after all iterations complete
     * @throws kotlinx.coroutines.CancellationException if the task is cancelled
     * @throws AgentExecutionException if an unrecoverable error occurs
     */
    suspend fun process(request: AgentRequest): AgentResponse
    
    /**
     * Process a task with streaming responses.
     * 
     * Emits chunks as the agent:
     * - Reasons about the task
     * - Calls tools
     * - Generates response text
     * - Updates progress
     * 
     * The Flow completes with a Done chunk when the task finishes.
     * 
     * Example:
     * ```kotlin
     * agent.processStreaming(request).collect { chunk ->
     *     when (chunk) {
     *         is AgentChunk.Text -> ui.appendText(chunk.text)
     *         is AgentChunk.ToolCallStarted -> ui.showToolCall(chunk.toolName)
     *         is AgentChunk.Done -> ui.markComplete(chunk.outcome)
     *     }
     * }
     * ```
     * 
     * @param request The task request with context and configuration
     * @return Flow of streaming chunks
     * @throws kotlinx.coroutines.CancellationException if the task is cancelled
     */
    fun processStreaming(request: AgentRequest): Flow<AgentChunk>
    
    /**
     * Cancel a running task.
     * 
     * Attempts to gracefully stop the agent's execution. The agent
     * may complete the current iteration before stopping.
     * 
     * After cancellation:
     * - process() throws CancellationException
     * - processStreaming() Flow completes with a Done chunk (outcome = CANCELLED)
     * 
     * @param requestId The request ID returned from process/processStreaming
     * @return true if the task was cancelled, false if already completed
     */
    suspend fun cancel(requestId: String): Boolean
    
    /**
     * Get the agent's current configuration.
     * 
     * @return The current agent configuration
     */
    fun getConfig(): AgentConfig
    
    /**
     * Update runtime configuration without restarting.
     * 
     * Changes take effect immediately for subsequent iterations.
     * Not all configuration options can be updated at runtime:
     * - ✅ Can update: maxIterations, toolTimeoutSeconds, enableBuildVerification
     * - ❌ Cannot update: fileOperationMode, buildCommand
     * 
     * @param overrides Configuration overrides to apply
     * @return The new effective configuration
     * @throws IllegalArgumentException if an override cannot be applied at runtime
     */
    suspend fun updateConfig(overrides: AgentConfigOverrides): AgentConfig
    
    /**
     * Dispose of agent resources.
     * 
     * Should be called when the agent is no longer needed.
     * After disposal, the agent should not be used for new tasks.
     * 
     * Implementations should:
     * - Cancel any running tasks
     * - Close network connections
     * - Release file handles
     * - Clean up temporary files
     */
    suspend fun dispose()
}

/**
 * Exception thrown when agent execution fails.
 * 
 * @param message Error message
 * @param cause Underlying cause (if any)
 * @param errorCode Error code for programmatic handling
 * @param recoverable Whether the error can be recovered from
 */
class AgentExecutionException(
    message: String,
    cause: Throwable? = null,
    val errorCode: String = AgentError.Codes.UNKNOWN,
    val recoverable: Boolean = false
) : Exception(message, cause)

/**
 * Create a display name for an agent based on layer and model.
 */
fun createAgentDisplayName(layer: VslfcLayer, modelId: String, modelProvider: String = "Unknown"): String =
    "${layer.displayName} Agent ($modelProvider $modelId)"

/**
 * Generate a unique agent ID.
 * 
 * Format: "agent-{LAYER}-{timestamp}-{random}"
 */
fun generateAgentId(layer: VslfcLayer): String =
    "agent-${layer.name}-${System.currentTimeMillis()}-${(Math.random() * 10000).toInt().toString(16)}"
