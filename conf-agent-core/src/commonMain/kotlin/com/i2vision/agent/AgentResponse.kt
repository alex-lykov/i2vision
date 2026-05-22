/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.agent

/**
 * Complete response from an agent after processing a task.
 * 
 * This is the final output returned by `agent.process()`. It includes:
 * - Task outcome (success, error, cancelled, etc.)
 * - Final response text for the user
 * - Execution metadata (iterations, duration, tool calls)
 * - Reasoning trace for debugging and UI display
 * - Any errors that occurred during execution
 * 
 * Example usage:
 * ```kotlin
 * val response = agent.process(request)
 * 
 * when (response.outcome) {
 *     Outcome.SUCCESS -> displayResponse(response.finalText)
 *     Outcome.ERROR -> displayError(response.errors.firstOrNull()?.message)
 *     Outcome.CANCELLED -> showCancelledMessage()
 *     Outcome.NEEDS_CLARIFICATION -> askForClarification(response.finalText)
 *     Outcome.ITERATION_LIMIT -> suggestIncreasingIterationLimit()
 * }
 * ```
 * 
 * @property requestId Matching request ID from AgentRequest
 * @property agentId Agent instance that processed the request
 * @property outcome Task outcome
 * @property finalText Final response text (may be null for errors)
 * @property iterations Number of iterations (think → act cycles)
 * @property toolCalls Tool calls made during execution
 * @property durationMs Total execution duration in milliseconds
 * @property reasoningTrace Reasoning trace for debugging and UI display
 * @property errors Any errors that occurred during execution
 * @property timestamp Response timestamp
 */
data class AgentResponse(
    val requestId: String,
    val agentId: String,
    val outcome: Outcome,
    val finalText: String?,
    val iterations: Int,
    val toolCalls: List<ToolCallRecord>,
    val durationMs: Long,
    val reasoningTrace: List<ReasoningStep> = emptyList(),
    val errors: List<AgentError> = emptyList(),
    val timestamp: Long = System.currentTimeMillis()
) {
    /**
     * Check if the task completed successfully.
     */
    val isSuccess: Boolean = outcome == Outcome.SUCCESS
    
    /**
     * Check if the task encountered an error.
     */
    val hasErrors: Boolean = errors.isNotEmpty()
    
    /**
     * Get the first error (if any).
     */
    val firstError: AgentError? = errors.firstOrNull()
    
    /**
     * Get tool calls that failed.
     */
    val failedToolCalls: List<ToolCallRecord> = toolCalls.filter { !it.success }
    
    /**
     * Get the total number of tool calls made.
     */
    val toolCallCount: Int = toolCalls.size
    
    companion object {
        /**
         * Create a successful response.
         */
        fun success(
            requestId: String,
            agentId: String,
            finalText: String,
            iterations: Int = 1,
            toolCalls: List<ToolCallRecord> = emptyList(),
            durationMs: Long = 0,
            reasoningTrace: List<ReasoningStep> = emptyList()
        ): AgentResponse = AgentResponse(
            requestId = requestId,
            agentId = agentId,
            outcome = Outcome.SUCCESS,
            finalText = finalText,
            iterations = iterations,
            toolCalls = toolCalls,
            durationMs = durationMs,
            reasoningTrace = reasoningTrace
        )
        
        /**
         * Create an error response.
         */
        fun error(
            requestId: String,
            agentId: String,
            error: AgentError,
            iterations: Int = 0,
            durationMs: Long = 0
        ): AgentResponse = AgentResponse(
            requestId = requestId,
            agentId = agentId,
            outcome = Outcome.ERROR,
            finalText = null,
            iterations = iterations,
            toolCalls = emptyList(),
            durationMs = durationMs,
            errors = listOf(error)
        )
        
        /**
         * Create a cancelled response.
         */
        fun cancelled(
            requestId: String,
            agentId: String,
            iterations: Int = 0,
            durationMs: Long = 0
        ): AgentResponse = AgentResponse(
            requestId = requestId,
            agentId = agentId,
            outcome = Outcome.CANCELLED,
            finalText = null,
            iterations = iterations,
            toolCalls = emptyList(),
            durationMs = durationMs
        )
    }
}

/**
 * Task outcome after agent execution.
 */
enum class Outcome {
    /** Task completed successfully */
    SUCCESS,
    
    /** Hit maximum iterations without completing the task */
    ITERATION_LIMIT,
    
    /** User cancelled the task */
    CANCELLED,
    
    /** Unrecoverable error occurred */
    ERROR,
    
    /** Agent needs more input from the user before proceeding */
    NEEDS_CLARIFICATION
}

/**
 * Record of a tool call made during agent execution.
 * 
 * @property toolName Name of the tool that was called
 * @property args Arguments passed to the tool
 * @property success Whether the tool call succeeded
 * @property output Output from the tool (if successful)
 * @property durationMs Tool execution duration in milliseconds
 * @property error Error message (if failed)
 */
data class ToolCallRecord(
    val toolName: String,
    val args: Map<String, Any>,
    val success: Boolean,
    val output: String?,
    val durationMs: Long,
    val error: String? = null
) {
    /**
     * Check if this tool call failed.
     */
    val isFailed: Boolean = !success
}

/**
 * A step in the agent's reasoning process.
 * 
 * Used for debugging, UI display, and conversation history.
 * 
 * @property iteration Iteration number (1-based)
 * @property type Type of reasoning step
 * @property content The reasoning content
 * @property timestamp When this reasoning step occurred
 */
data class ReasoningStep(
    val iteration: Int,
    val type: ReasoningType,
    val content: String,
    val timestamp: Long
)

/**
 * Types of reasoning steps in agent execution.
 */
enum class ReasoningType {
    /** Initial plan before taking action */
    PLAN,
    
    /** Observation after tool execution */
    OBSERVATION,
    
    /** Decision point (e.g., which tool to call next) */
    DECISION,
    
    /** Self-reflection on progress */
    REFLECTION,
    
    /** Error correction or recovery strategy */
    CORRECTION
}

/**
 * Error that occurred during agent execution.
 * 
 * @property code Error code for programmatic handling
 * @property message Human-readable error message
 * @property iteration Iteration where the error occurred (if applicable)
 * @property recoverable Whether the agent can recover from this error
 */
data class AgentError(
    val code: String,
    val message: String,
    val iteration: Int? = null,
    val recoverable: Boolean = false
) {
    companion object {
        /**
         * Common error codes for agent execution.
         */
        object Codes {
            const val TOOL_NOT_FOUND = "TOOL_NOT_FOUND"
            const val TOOL_TIMEOUT = "TOOL_TIMEOUT"
            const val TOOL_EXECUTION_FAILED = "TOOL_EXECUTION_FAILED"
            const val LLM_ERROR = "LLM_ERROR"
            const val CONTEXT_LIMIT_EXCEEDED = "CONTEXT_LIMIT_EXCEEDED"
            const val CANCELLED = "CANCELLED"
            const val INVALID_REQUEST = "INVALID_REQUEST"
            const val CONFIGURATION_ERROR = "CONFIGURATION_ERROR"
            const val FILE_NOT_FOUND = "FILE_NOT_FOUND"
            const val PERMISSION_DENIED = "PERMISSION_DENIED"
            const val BUILD_FAILED = "BUILD_FAILED"
            const val CONTRACT_VIOLATION = "CONTRACT_VIOLATION"
            const val UNKNOWN = "UNKNOWN"
        }
    }
}
