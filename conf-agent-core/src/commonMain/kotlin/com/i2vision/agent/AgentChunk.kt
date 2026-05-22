/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.agent

/**
 * Streaming chunk emitted during agent execution.
 * 
 * Clients (e.g., VS Code extension) use these chunks to update the UI in real-time:
 * - Show reasoning as the agent thinks
 * - Display tool calls as they happen
 * - Stream response text token-by-token
 * - Update progress indicators
 * - Handle errors immediately
 * 
 * Example usage:
 * ```kotlin
 * agent.processStreaming(request).collect { chunk ->
 *     when (chunk) {
 *         is AgentChunk.Reasoning -> ui.appendReasoning(chunk.text)
 *         is AgentChunk.ToolCallStarted -> ui.showToolCall(chunk.toolName, chunk.args)
 *         is AgentChunk.ToolCallCompleted -> ui.hideToolCall(chunk.toolName, chunk.output)
 *         is AgentChunk.Text -> ui.appendText(chunk.text)
 *         is AgentChunk.Progress -> ui.updateProgress(chunk.iteration, chunk.maxIterations)
 *         is AgentChunk.Done -> ui.markComplete(chunk.outcome, chunk.finalText)
 *         is AgentChunk.ChunkError -> ui.showError(chunk.error.message)
 *     }
 * }
 * ```
 */
sealed class AgentChunk {
    /** Unique request identifier this chunk belongs to */
    abstract val requestId: String
    
    /** When this chunk was emitted */
    abstract val timestamp: Long
    
    /**
     * Agent is thinking/reasoning about the next step.
     * 
     * @property requestId Request identifier
     * @property text Reasoning text (may be partial)
     * @property iteration Current iteration number (1-based)
     * @property timestamp When this chunk was emitted
     */
    data class Reasoning(
        override val requestId: String,
        val text: String,
        val iteration: Int,
        override val timestamp: Long = System.currentTimeMillis()
    ) : AgentChunk()
    
    /**
     * Agent is about to call a tool.
     * 
     * @property requestId Request identifier
     * @property toolName Name of the tool being called
     * @property args Arguments being passed to the tool
     * @property iteration Current iteration number
     * @property timestamp When this chunk was emitted
     */
    data class ToolCallStarted(
        override val requestId: String,
        val toolName: String,
        val args: Map<String, Any>,
        val iteration: Int,
        override val timestamp: Long = System.currentTimeMillis()
    ) : AgentChunk()
    
    /**
     * Tool call completed (successfully or with error).
     * 
     * @property requestId Request identifier
     * @property toolName Name of the tool that was called
     * @property success Whether the tool call succeeded
     * @property output Output from the tool (if successful)
     * @property durationMs Tool execution duration in milliseconds
     * @property timestamp When this chunk was emitted
     */
    data class ToolCallCompleted(
        override val requestId: String,
        val toolName: String,
        val success: Boolean,
        val output: String?,
        val durationMs: Long,
        override val timestamp: Long = System.currentTimeMillis()
    ) : AgentChunk()
    
    /**
     * Agent is streaming its response text.
     * 
     * Multiple Text chunks may be emitted as the agent generates text.
     * The final Text chunk will have isFinal = true.
     * 
     * @property requestId Request identifier
     * @property text Text content (may be partial)
     * @property isFinal Whether this is the final text chunk
     * @property timestamp When this chunk was emitted
     */
    data class Text(
        override val requestId: String,
        val text: String,
        val isFinal: Boolean = false,
        override val timestamp: Long = System.currentTimeMillis()
    ) : AgentChunk()
    
    /**
     * Execution progress update.
     * 
     * @property requestId Request identifier
     * @property iteration Current iteration number
     * @property maxIterations Maximum iterations configured
     * @property message Progress message for UI display
     * @property timestamp When this chunk was emitted
     */
    data class Progress(
        override val requestId: String,
        val iteration: Int,
        val maxIterations: Int,
        val message: String,
        override val timestamp: Long = System.currentTimeMillis()
    ) : AgentChunk()
    
    /**
     * Task completed (successfully or with error).
     * 
     * This is always the final chunk in a streaming sequence.
     * 
     * @property requestId Request identifier
     * @property outcome Task outcome
     * @property finalText Final response text (if successful)
     * @property iterations Total iterations executed
     * @property totalDurationMs Total execution duration in milliseconds
     * @property timestamp When this chunk was emitted
     */
    data class Done(
        override val requestId: String,
        val outcome: Outcome,
        val finalText: String?,
        val iterations: Int,
        val totalDurationMs: Long,
        override val timestamp: Long = System.currentTimeMillis()
    ) : AgentChunk()
    
    /**
     * Error occurred during execution.
     * 
     * May be followed by recovery attempts or task termination.
     * 
     * @property requestId Request identifier
     * @property error Error details
     * @property timestamp When this chunk was emitted
     */
    data class ChunkError(
        override val requestId: String,
        val error: AgentError,
        override val timestamp: Long = System.currentTimeMillis()
    ) : AgentChunk()
}

/**
 * Extension function to check if a chunk is terminal (ends the stream).
 */
fun AgentChunk.isTerminal(): Boolean = this is AgentChunk.Done || 
    (this is AgentChunk.ChunkError && !this.error.recoverable)

/**
 * Extension function to extract text from any text-bearing chunk.
 * Returns null if the chunk doesn't contain text.
 */
fun AgentChunk.extractText(): String? = when (this) {
    is AgentChunk.Reasoning -> text
    is AgentChunk.Text -> text
    is AgentChunk.Done -> finalText
    is AgentChunk.ChunkError -> error.message
    else -> null
}

/**
 * Extension function to get the iteration number from a chunk.
 * Returns null if the chunk doesn't have iteration information.
 */
fun AgentChunk.getIteration(): Int? = when (this) {
    is AgentChunk.Reasoning -> iteration
    is AgentChunk.ToolCallStarted -> iteration
    is AgentChunk.ToolCallCompleted -> null // No iteration field
    is AgentChunk.Progress -> iteration
    is AgentChunk.Done -> iterations
    else -> null
}
