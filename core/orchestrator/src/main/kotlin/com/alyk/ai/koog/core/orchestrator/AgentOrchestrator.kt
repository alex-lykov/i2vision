package com.alyk.ai.koog.core.orchestrator

import ai.koog.agents.core.tools.ToolRegistry
import com.alyk.ai.koog.context.hierarchy.HierarchyBuilder
import com.alyk.ai.koog.context.provider.ContextProvider
import com.alyk.ai.koog.core.orchestrator.agents.*
import com.alyk.ai.koog.core.orchestrator.mcp.McpIntegration
import com.alyk.ai.koog.core.orchestrator.mcp.McpStatus
import com.alyk.ai.koog.core.orchestrator.mcp.workspace.WorkspaceFactory
import com.alyk.ai.koog.core.orchestrator.router.AgentResponseChunk
import com.alyk.ai.koog.core.orchestrator.router.AgentRouter
import com.alyk.ai.koog.core.orchestrator.router.TaskContext
import com.alyk.ai.koog.core.session.SessionStore
import com.alyk.ai.koog.models.wrappers.ModelWrapper
import com.alyk.ai.koog.switching.decision.DecisionEngine
import com.alyk.ai.koog.switching.decision.ModelSwitchControl
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Central coordination of all agent components, managing request routing,
 * model selection, and session lifecycle.
 */
class AgentOrchestrator(
    private val sessionStore: SessionStore,
    private val decisionEngine: DecisionEngine,
    private val contextProvider: ContextProvider,
    private val hierarchyBuilder: HierarchyBuilder,
    private val localModel: ModelWrapper,
    private val cloudModel: ModelWrapper? = null,
    private val modelSwitchControl: ModelSwitchControl? = null,
    private val mcpToolRegistry: ToolRegistry = ToolRegistry.EMPTY
) {
    
    private val mcpIntegration = McpIntegration(contextProvider, mcpToolRegistry)
    private val workspaceFactory = WorkspaceFactory(contextProvider, emptyList())
    private var agentRouter: AgentRouter? = null
    
    /**
     * Initialize and maintain references to local/cloud model wrappers
     */
    suspend fun initialize(projectPath: String? = null) {
        projectPath?.let {
            loadProject(it)
        }
        
        // Initialize agent router with workspaces
        val workspaces = workspaceFactory.createAllWorkspaces(projectPath)
        val currentModel = modelSwitchControl?.getCurrentModel() ?: localModel
        
        // Collect MCP tools for Implementation workspace
        val mcpToolsList = mutableListOf<com.alyk.ai.koog.core.orchestrator.mcp.ProjectMcpTool>()
        if (mcpIntegration.isInitialized()) {
            kotlinx.coroutines.runBlocking {
                mcpIntegration.getAvailableTools().collect { tool ->
                    mcpToolsList.add(tool)
                }
            }
        }
        
        // Update workspace factory with MCP tools
        val updatedWorkspaceFactory = WorkspaceFactory(contextProvider, mcpToolsList)
        val updatedWorkspaces = updatedWorkspaceFactory.createAllWorkspaces(projectPath)
        
        agentRouter = AgentRouter(
            ideaAgent = IdeaAgent(updatedWorkspaces[com.alyk.ai.koog.core.orchestrator.mcp.workspace.WorkspaceType.IDEA]!!),
            architectureAgent = ArchitectureAgent(updatedWorkspaces[com.alyk.ai.koog.core.orchestrator.mcp.workspace.WorkspaceType.ARCHITECTURE]!!),
            moduleAgent = ModuleAgent(updatedWorkspaces[com.alyk.ai.koog.core.orchestrator.mcp.workspace.WorkspaceType.MODULE]!!),
            testAgent = TestAgent(updatedWorkspaces[com.alyk.ai.koog.core.orchestrator.mcp.workspace.WorkspaceType.TEST]!!),
            implementationAgent = ImplementationAgent(
                updatedWorkspaces[com.alyk.ai.koog.core.orchestrator.mcp.workspace.WorkspaceType.IMPLEMENTATION]!!,
                currentModel
            )
        )
    }

    /**
     * Load or switch to a different project at runtime and integrate with MCP tools
     */
    suspend fun loadProject(projectPath: String): Result<Unit> {
        return try {
            // Load project context
            contextProvider.loadProject(projectPath)
            
            // Initialize MCP integration for the project
            val enhancedRegistry = mcpIntegration.initializeForProject(projectPath)
            
            println("[MCP] Successfully linked project $projectPath with MCP integration")
            println("[MCP] Available MCP tools: ${mcpIntegration.getAvailableTools()}")
            
            Result.success(Unit)
        } catch (e: Exception) {
            println("[MCP] Failed to link project to MCP: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Get currently loaded project information
     */
    fun getCurrentProject(): String? = contextProvider.getProjectRoot()

    /**
     * Route incoming tasks using the layered agent router
     * Simplified: Always uses ImplementationAgent (can be controlled via UI)
     */
    suspend fun routeTask(task: String, sessionId: String, agentType: com.alyk.ai.koog.core.orchestrator.router.AgentType = com.alyk.ai.koog.core.orchestrator.router.AgentType.IMPLEMENTATION): String {
        val router = agentRouter ?: run {
            // Fallback to direct model if router not initialized
            val context = contextProvider.getContextForTask(task)
            val mcpTools = getAvailableMCPTools()
            val promptWithContext = buildPrompt(task, context, mcpTools)
            val currentModel = modelSwitchControl?.getCurrentModel() ?: localModel
            return currentModel.generate(promptWithContext)
        }
        
        val taskContext = TaskContext(
            projectPath = getCurrentProject(),
            currentFiles = contextProvider.getLoadedFiles(),
            sessionId = sessionId
        )
        
        val response = router.routeTask(task, taskContext, agentType)
        return response.result
    }
    
    /**
     * Route task with streaming response using the layered agent router
     * Simplified: Always uses ImplementationAgent by default (can be controlled via UI)
     */
    suspend fun routeTaskStreaming(task: String, sessionId: String, agentType: com.alyk.ai.koog.core.orchestrator.router.AgentType = com.alyk.ai.koog.core.orchestrator.router.AgentType.IMPLEMENTATION): Flow<AgentResponseChunk> {
        val router = agentRouter ?: run {
            // Fallback: return single chunk
            return flow {
                val result = routeTask(task, sessionId, agentType)
                emit(AgentResponseChunk.Text(result))
                emit(AgentResponseChunk.Complete)
            }
        }
        
        val taskContext = TaskContext(
            projectPath = getCurrentProject(),
            currentFiles = contextProvider.getLoadedFiles(),
            sessionId = sessionId
        )
        
        return router.routeTaskStreaming(task, taskContext, agentType)
    }

    private fun buildPrompt(task: String, context: String, mcpTools: Flow<String>): String {
        val toolsList = mutableListOf<String>()
        kotlinx.coroutines.runBlocking {
            mcpTools.collect { toolName ->
                toolsList.add(toolName)
            }
        }
        
        return """
            |Task: $task
            |
            |$context
            |
            |Available MCP Tools: ${toolsList.joinToString(", ")}
            |
            |You can use the above tools to help complete the task. Each tool provides specific capabilities for working with this project.
            |Please complete the task considering the project context and available tools above.
        """.trimMargin()
    }

    /**
     * Maintain session state across model switches using coroutines
     */
    suspend fun switchModel(sessionId: String, newModel: ModelWrapper) {
        // TODO: Implement model switching with state preservation
    }

    /**
     * Implement fallback strategies when primary model fails
     */
    suspend fun handleFallback(sessionId: String, error: Throwable): String {
        // TODO: Implement fallback logic
        return "Fallback handled (stub)"
    }

    /**
     * Track agent health metrics and expose status endpoint
     */
    fun getHealthStatus(): HealthStatus {
        // Minimal health status for launcher integration.
        return HealthStatus(isHealthy = true, activeSessions = 0)
    }

    /**
     * Get performance monitoring statistics
     */
    fun getPerformanceStats(): com.alyk.ai.koog.switching.monitor.PerformanceStats {
        return decisionEngine.getPerformanceStats()
    }

    /**
     * Get current warning signals from performance monitoring
     */
    fun getPerformanceWarnings(): List<com.alyk.ai.koog.switching.monitor.WarningSignal> {
        return decisionEngine.getPerformanceWarnings()
    }

    /**
     * Check for performance alerts that exceed thresholds
     */
    fun getPerformanceAlerts(): List<com.alyk.ai.koog.switching.monitor.Alert> {
        return decisionEngine.getPerformanceAlerts()
    }
    
    /**
     * Get MCP tool registry for project integration
     */
    fun getMcpToolRegistry(): ToolRegistry {
        return if (mcpIntegration.isInitialized()) {
            mcpIntegration.getCurrentToolRegistry()
        } else {
            mcpToolRegistry
        }
    }
    
    /**
     * Get available MCP tools for the current project
     */
    fun getAvailableMCPTools(): Flow<String> = flow {
        mcpIntegration.getAvailableTools().collect { tool ->
            emit(tool.name)
        }
    }
    
    /**
     * Get MCP integration status
     */
    fun getMcpStatus(): McpStatus {
        return if (mcpIntegration.isInitialized()) {
            val projectPath = mcpIntegration.getCurrentProject()
            if (projectPath != null) {
                McpStatus.CONNECTED(projectPath)
            } else {
                McpStatus.ERROR("Project path is null")
            }
        } else {
            McpStatus.NOT_CONNECTED
        }
    }
}

data class HealthStatus(
    val isHealthy: Boolean,
    val activeSessions: Int
)
