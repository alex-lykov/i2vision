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
 * Idea Agent: Handles high-level concepts, brainstorming, and planning
 * Uses MCP Workspace: Idea
 * Has access to Koog file access tools for exploring project structure
 */
class IdeaAgent(
    workspace: McpWorkspace,
    toolRegistry: ToolRegistry = ToolRegistry.EMPTY
) : BaseAgent(AgentType.IDEA, workspace, toolRegistry) {
    
    override suspend fun process(task: String, context: TaskContext): AgentResponse {
        // Use Koog AIAgent with ToolRegistry for exploring project structure
        val prompt = buildPromptWithContext(
            task, 
            context,
            "Explore the project structure to understand the codebase before generating ideas."
        )
        
        // Create AIAgent with tools - can explore project files
        val agent = createKoogAgent()
        val result = agent.run(prompt)
        
        return AgentResponse(
            agentType = AgentType.IDEA,
            result = result,
            metadata = mapOf(
                "workspace" to workspace.name,
                "agent" to "idea",
                "tools_used" to "true"
            )
        )
    }
    
    override suspend fun processStreaming(task: String, context: TaskContext): Flow<AgentResponseChunk> = flow {
        emit(AgentResponseChunk.Progress(10, "Analyzing idea requirements"))
        emit(AgentResponseChunk.Progress(30, "Exploring concepts with MCP tools"))
        emit(AgentResponseChunk.Progress(60, "Generating ideas"))
        emit(AgentResponseChunk.Progress(90, "Finalizing concept"))
        
        val response = process(task, context)
        emit(AgentResponseChunk.Text(response.result))
        emit(AgentResponseChunk.Complete)
    }
    
    override protected fun getSystemPrompt(): String {
        return """
            You are an Idea Agent specialized in:
            - Generating high-level concepts and ideas
            - Brainstorming creative solutions
            - Planning and strategic thinking
            - Exploring possibilities without implementation details
            
            Use MCP tools from the Idea workspace to explore concepts.
        """.trimIndent()
    }
}
