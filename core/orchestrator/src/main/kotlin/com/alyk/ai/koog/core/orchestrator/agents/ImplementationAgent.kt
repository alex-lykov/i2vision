package com.alyk.ai.koog.core.orchestrator.agents

import com.alyk.ai.koog.core.orchestrator.mcp.workspace.McpWorkspace
import com.alyk.ai.koog.core.orchestrator.router.AgentResponse
import com.alyk.ai.koog.core.orchestrator.router.AgentResponseChunk
import com.alyk.ai.koog.core.orchestrator.router.AgentType
import com.alyk.ai.koog.core.orchestrator.router.TaskContext
import com.alyk.ai.koog.models.wrappers.ModelWrapper
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Implementation Agent: Handles actual code implementation
 * Uses MCP Workspace: Implementation
 * This agent uses the current Terminal+Prompt UI
 */
class ImplementationAgent(
    workspace: McpWorkspace,
    private val modelWrapper: ModelWrapper
) : BaseAgent(AgentType.IMPLEMENTATION, workspace) {
    
    override suspend fun process(task: String, context: TaskContext): AgentResponse {
        // Use the actual model wrapper to generate implementation
        val workspaceContext = workspace.getContext()
        val prompt = buildPrompt(task, workspaceContext, context)
        
        val result = modelWrapper.generate(prompt)
        
        return AgentResponse(
            agentType = AgentType.IMPLEMENTATION,
            result = result,
            metadata = mapOf(
                "workspace" to workspace.name,
                "agent" to "implementation",
                "model" to modelWrapper.modelName
            )
        )
    }
    
    override suspend fun processStreaming(task: String, context: TaskContext): Flow<AgentResponseChunk> = flow {
        emit(AgentResponseChunk.Progress(10, "Analyzing implementation requirements"))
        emit(AgentResponseChunk.Progress(30, "Gathering context from workspace"))
        
        val workspaceContext = workspace.getContext()
        val prompt = buildPrompt(task, workspaceContext, context)
        
        emit(AgentResponseChunk.Progress(50, "Generating implementation"))
        
        // Stream the model response
        modelWrapper.generateStreaming(prompt).collect { chunk ->
            emit(AgentResponseChunk.Text(chunk))
        }
        
        emit(AgentResponseChunk.Progress(100, "Implementation complete"))
        emit(AgentResponseChunk.Complete)
    }
    
    private fun buildPrompt(task: String, workspaceContext: String, context: TaskContext): String {
        return """
            ${getSystemPrompt()}
            
            Task: $task
            
            Workspace Context:
            $workspaceContext
            
            Current Files: ${context.currentFiles.joinToString(", ")}
            Project Path: ${context.projectPath ?: "Not specified"}
            
            Please implement the requested changes.
        """.trimIndent()
    }
    
    override protected fun getSystemPrompt(): String {
        return """
            You are an Implementation Agent specialized in:
            - Writing actual code implementations
            - Fixing bugs
            - Adding features
            - Refactoring code
            - Code quality and best practices
            
            Use MCP tools from the Implementation workspace to:
            - Read and write files
            - Search codebase
            - Execute build commands
            - Run tests
            
            Provide complete, working code solutions.
        """.trimIndent()
    }
}
