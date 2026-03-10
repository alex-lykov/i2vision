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
 * Test Agent: Handles test generation, execution, and coverage
 * Uses MCP Workspace: Test
 * Has access to Koog file access tools for reading code and writing tests
 */
class TestAgent(
    workspace: McpWorkspace,
    toolRegistry: ToolRegistry = ToolRegistry.EMPTY
) : BaseAgent(AgentType.TEST, workspace, toolRegistry) {
    
    override suspend fun process(task: String, context: TaskContext): AgentResponse {
        // Use Koog AIAgent with ToolRegistry for test operations
        val prompt = buildPromptWithContext(
            task,
            context,
            "Read the code to understand what needs testing, then generate appropriate tests."
        )
        
        // Create AIAgent with tools - can read source files and write test files
        val agent = createKoogAgent()
        val result = agent.run(prompt)
        
        return AgentResponse(
            agentType = AgentType.TEST,
            result = result,
            metadata = mapOf(
                "workspace" to workspace.name,
                "agent" to "test",
                "tools_used" to "true"
            )
        )
    }
    
    override suspend fun processStreaming(task: String, context: TaskContext): Flow<AgentResponseChunk> = flow {
        emit(AgentResponseChunk.Progress(10, "Analyzing test requirements"))
        emit(AgentResponseChunk.Progress(30, "Generating test cases"))
        emit(AgentResponseChunk.Progress(60, "Writing test code"))
        emit(AgentResponseChunk.Progress(90, "Validating tests"))
        
        val response = process(task, context)
        emit(AgentResponseChunk.Text(response.result))
        emit(AgentResponseChunk.Complete)
    }
    
    override protected fun getSystemPrompt(): String {
        return """
            You are a Test Agent specialized in:
            - Test generation (unit, integration, e2e)
            - Test execution and debugging
            - Test coverage analysis
            - Test refactoring
            - Test best practices
            
            Use MCP tools from the Test workspace to execute and analyze tests.
        """.trimIndent()
    }
}
