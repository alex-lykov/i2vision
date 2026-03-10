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
 * Architecture Agent: Handles system design, structure, and patterns
 * Uses MCP Workspace: Architecture
 * Has access to Koog file access tools for analyzing code structure
 */
class ArchitectureAgent(
    workspace: McpWorkspace,
    toolRegistry: ToolRegistry = ToolRegistry.EMPTY
) : BaseAgent(AgentType.ARCHITECTURE, workspace, toolRegistry) {
    
    override suspend fun process(task: String, context: TaskContext): AgentResponse {
        // Use Koog AIAgent with ToolRegistry for analyzing architecture
        val prompt = buildPromptWithContext(
            task,
            context,
            "Analyze the codebase structure, module boundaries, and dependencies to design architecture."
        )
        
        // Create AIAgent with tools - can read files and search codebase
        val agent = createKoogAgent()
        val result = agent.run(prompt)
        
        return AgentResponse(
            agentType = AgentType.ARCHITECTURE,
            result = result,
            metadata = mapOf(
                "workspace" to workspace.name,
                "agent" to "architecture",
                "tools_used" to "true"
            )
        )
    }
    
    override suspend fun processStreaming(task: String, context: TaskContext): Flow<AgentResponseChunk> = flow {
        emit(AgentResponseChunk.Progress(10, "Analyzing architecture requirements"))
        emit(AgentResponseChunk.Progress(30, "Evaluating current structure"))
        emit(AgentResponseChunk.Progress(60, "Designing architecture"))
        emit(AgentResponseChunk.Progress(90, "Validating design"))
        
        val response = process(task, context)
        emit(AgentResponseChunk.Text(response.result))
        emit(AgentResponseChunk.Complete)
    }
    
    override protected fun getSystemPrompt(): String {
        return """
            You are an Architecture Agent specialized in:
            - System design and architecture
            - Module boundaries and structure
            - Design patterns application
            - Architecture documentation
            - Dependency analysis
            
            Use MCP tools from the Architecture workspace to analyze and design.
        """.trimIndent()
    }
}
