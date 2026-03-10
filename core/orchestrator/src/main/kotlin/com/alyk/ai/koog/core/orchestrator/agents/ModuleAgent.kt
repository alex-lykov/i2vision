package com.alyk.ai.koog.core.orchestrator.agents

import ai.koog.agents.core.tools.ToolRegistry
import com.alyk.ai.koog.core.orchestrator.mcp.workspace.McpWorkspace
import com.alyk.ai.koog.core.orchestrator.router.AgentResponse
import com.alyk.ai.koog.core.orchestrator.router.AgentResponseChunk
import com.alyk.ai.koog.core.orchestrator.router.AgentType
import com.alyk.ai.koog.core.orchestrator.router.TaskContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Module Agent: Handles module-level changes and refactoring
 * Uses MCP Workspace: Module
 * Has access to Koog file access tools for module operations
 */
class ModuleAgent(
    workspace: McpWorkspace,
    toolRegistry: ToolRegistry = ToolRegistry.EMPTY
) : BaseAgent(AgentType.MODULE, workspace, toolRegistry) {
    
    override suspend fun process(task: String, context: TaskContext): AgentResponse {
        // Use Koog AIAgent with ToolRegistry for module operations
        val prompt = buildPromptWithContext(
            task,
            context,
            "Analyze module structure and dependencies to perform module-level operations."
        )
        
        // Create AIAgent with tools - can read/write files for refactoring
        val agent = createKoogAgent()
        val result = agent.run(prompt)
        
        return AgentResponse(
            agentType = AgentType.MODULE,
            result = result,
            metadata = mapOf(
                "workspace" to workspace.name,
                "agent" to "module",
                "tools_used" to "true"
            )
        )
    }
    
    override suspend fun processStreaming(task: String, context: TaskContext): Flow<AgentResponseChunk> = flow {
        emit(AgentResponseChunk.Progress(10, "Analyzing module structure"))
        emit(AgentResponseChunk.Progress(30, "Identifying affected modules"))
        emit(AgentResponseChunk.Progress(60, "Planning module changes"))
        emit(AgentResponseChunk.Progress(90, "Validating module structure"))
        
        val response = process(task, context)
        emit(AgentResponseChunk.Text(response.result))
        emit(AgentResponseChunk.Complete)
    }
    
    override protected fun getSystemPrompt(): String {
        return """
            You are a Module Agent specialized in:
            - Module-level refactoring
            - Package restructuring
            - Dependency management
            - Module extraction and creation
            - Module boundary analysis
            
            Use MCP tools from the Module workspace to analyze and modify modules.
        """.trimIndent()
    }
}
