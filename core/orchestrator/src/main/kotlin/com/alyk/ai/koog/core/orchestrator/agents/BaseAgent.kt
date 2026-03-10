package com.alyk.ai.koog.core.orchestrator.agents

import com.alyk.ai.koog.core.orchestrator.mcp.workspace.McpWorkspace
import com.alyk.ai.koog.core.orchestrator.router.AgentResponse
import com.alyk.ai.koog.core.orchestrator.router.AgentResponseChunk
import com.alyk.ai.koog.core.orchestrator.router.AgentType
import com.alyk.ai.koog.core.orchestrator.router.TaskContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Base class for all specialized agents in the layered architecture
 */
abstract class BaseAgent(
    protected val agentType: AgentType,
    protected val workspace: McpWorkspace
) {
    /**
     * Process a task and return response
     */
    abstract suspend fun process(task: String, context: TaskContext): AgentResponse
    
    /**
     * Process a task with streaming response
     */
    open suspend fun processStreaming(task: String, context: TaskContext): Flow<AgentResponseChunk> = flow {
        // Default implementation: process and stream result
        val response = process(task, context)
        emit(AgentResponseChunk.Text(response.result))
        emit(AgentResponseChunk.Complete)
    }
    
    /**
     * Get agent-specific system prompt
     */
    protected abstract fun getSystemPrompt(): String
}
