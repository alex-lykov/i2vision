package com.alyk.ai.koog.core.orchestrator.agents

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.executor.llms.all.simpleOllamaAIExecutor
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import com.alyk.ai.koog.core.orchestrator.mcp.workspace.McpWorkspace
import com.alyk.ai.koog.core.orchestrator.router.AgentResponse
import com.alyk.ai.koog.core.orchestrator.router.AgentResponseChunk
import com.alyk.ai.koog.core.orchestrator.router.AgentType
import com.alyk.ai.koog.core.orchestrator.router.TaskContext
import com.alyk.ai.koog.core.session.ISessionStore
import com.alyk.ai.koog.models.wrappers.ModelWrapper
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Base class for all specialized agents in the layered architecture
 * All agents have access to Koog's ToolRegistry for file access and other tools
 */
abstract class BaseAgent(
    protected val agentType: AgentType,
    protected val workspace: McpWorkspace,
    protected val toolRegistry: ToolRegistry = ToolRegistry.EMPTY,
    protected val modelWrapper: ModelWrapper? = null,
    protected val sessionStore: ISessionStore? = null
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
    
    /**
     * Create a Koog AIAgent with ToolRegistry support
     * All agents can use this to create AIAgent instances with tool access
     */
    protected fun createKoogAgent(
        modelName: String = modelWrapper?.modelName ?: "qwen3:4b",
        contextLength: Long = (modelWrapper?.maxContextLength ?: 4096).toLong(),
        maxOutputTokens: Long = 2048L
    ): AIAgent<String, String> {
        val promptExecutor = simpleOllamaAIExecutor()
        val llmModel = LLModel(
            id = modelName,
            provider = LLMProvider.Ollama,
            contextLength = contextLength,
            maxOutputTokens = maxOutputTokens,
            capabilities = emptyList()
        )
        
        return AIAgent(
            promptExecutor = promptExecutor,
            llmModel = llmModel,
            toolRegistry = toolRegistry,  // All agents have access to tools
            systemPrompt = getSystemPrompt()
        )
    }
    
    /**
     * Build a prompt with workspace context and task information
     */
    protected suspend fun buildPromptWithContext(
        task: String,
        context: TaskContext,
        additionalContext: String = ""
    ): String {
        val workspaceContext = workspace.getContext()
        
        return """
            ${getSystemPrompt()}
            
            Task: $task
            
            Workspace Context:
            $workspaceContext
            
            ${if (additionalContext.isNotEmpty()) "Additional Context:\n$additionalContext\n" else ""}
            Current Files: ${context.currentFiles.joinToString(", ")}
            Project Path: ${context.projectPath ?: "Not specified"}
            
            You have access to file access tools (list_directory, read_file, regex_search, write_file) 
            through the ToolRegistry. Use these tools to explore the codebase and complete the task.
        """.trimIndent()
    }
}
