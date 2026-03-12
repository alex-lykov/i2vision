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
import com.alyk.ai.koog.core.session.ISessionStore
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
    private val sessionStore: ISessionStore,
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
    private val toolUsageTracker = com.alyk.ai.koog.core.orchestrator.tools.ToolUsageTracker()
    private var useCloudModel: Boolean = false
    
    /**
     * Initialize and maintain references to local/cloud model wrappers
     */
    suspend fun initialize(projectPath: String? = null) {
        Logger.info("ORCHESTRATOR", "Initializing AgentOrchestrator...")
        Logger.debug("ORCHESTRATOR", "Project path: ${projectPath ?: "null"}")
        
        if (projectPath != null) {
            Logger.info("ORCHESTRATOR", "Project path provided, loading project...")
            loadProject(projectPath)
        } else {
            Logger.info("ORCHESTRATOR", "No project path, activating MCP structure without project...")
            // Activate MCP structure even without a project
            activateMcpStructure(projectPath)
        }
        
        Logger.info("ORCHESTRATOR", "AgentOrchestrator initialization complete")
    }
    
    /**
     * Activate MCP structure: Initialize agent router with workspaces and MCP tools
     * This should be called both during initialization and when a project is loaded
     */
    private suspend fun activateMcpStructure(projectPath: String?) {
        Logger.info("ORCHESTRATOR", "Activating MCP structure...")
        Logger.debug("ORCHESTRATOR", "Project path: ${projectPath ?: "null"}")
        
        val currentModel = selectModel()
        Logger.debug("ORCHESTRATOR", "Current model: ${currentModel.modelName} (${if (useCloudModel) "cloud" else "local"})")
        
        // Collect MCP tools for Implementation workspace
        val mcpToolsList = mutableListOf<com.alyk.ai.koog.core.orchestrator.mcp.ProjectMcpTool>()
        if (mcpIntegration.isInitialized()) {
            Logger.debug("ORCHESTRATOR", "MCP integration initialized, collecting tools...")
            kotlinx.coroutines.runBlocking {
                mcpIntegration.getAvailableTools().collect { tool ->
                    mcpToolsList.add(tool)
                    Logger.debug("ORCHESTRATOR", "Collected MCP tool: ${tool.name}")
                }
            }
            Logger.info("ORCHESTRATOR", "Collected ${mcpToolsList.size} MCP tools")
        } else {
            Logger.warn("ORCHESTRATOR", "MCP integration not initialized, no MCP tools available")
        }
        
        // Create Koog ToolRegistry with file access tools for all agents
        Logger.debug("ORCHESTRATOR", "Creating Koog ToolRegistry with file access tools...")
        val projectRoot = contextProvider.getProjectRoot() ?: projectPath ?: "."
        Logger.debug("ORCHESTRATOR", "Project root for tools: $projectRoot")
        val koogToolRegistry = com.alyk.ai.koog.core.orchestrator.tools.KoogToolRegistryBuilder(contextProvider).build()
        Logger.info("ORCHESTRATOR", "✅ Created Koog ToolRegistry with file access tools")
        
        // Combine with MCP tool registry
        val combinedToolRegistry = mcpIntegration.getCurrentToolRegistry() + koogToolRegistry
        Logger.info("ORCHESTRATOR", "✅ Combined with MCP tools: ${mcpToolsList.size} MCP tools")
        
        // Update workspace factory with MCP tools
        Logger.debug("ORCHESTRATOR", "Creating workspaces...")
        val updatedWorkspaceFactory = WorkspaceFactory(contextProvider, mcpToolsList)
        val updatedWorkspaces = updatedWorkspaceFactory.createAllWorkspaces(projectPath)
        Logger.info("ORCHESTRATOR", "Created ${updatedWorkspaces.size} workspaces: ${updatedWorkspaces.keys.joinToString()}")
        
        // Initialize agent router with all workspaces and ToolRegistry (layered MCP architecture)
        Logger.debug("ORCHESTRATOR", "Initializing agent router with all agents...")
        agentRouter = AgentRouter(
            ideaAgent = IdeaAgent(
                updatedWorkspaces[com.alyk.ai.koog.core.orchestrator.mcp.workspace.WorkspaceType.IDEA]!!,
                combinedToolRegistry
            ),
            architectureAgent = ArchitectureAgent(
                updatedWorkspaces[com.alyk.ai.koog.core.orchestrator.mcp.workspace.WorkspaceType.ARCHITECTURE]!!,
                combinedToolRegistry
            ),
            moduleAgent = ModuleAgent(
                updatedWorkspaces[com.alyk.ai.koog.core.orchestrator.mcp.workspace.WorkspaceType.MODULE]!!,
                combinedToolRegistry
            ),
            testAgent = TestAgent(
                updatedWorkspaces[com.alyk.ai.koog.core.orchestrator.mcp.workspace.WorkspaceType.TEST]!!,
                combinedToolRegistry
            ),
            implementationAgent = ImplementationAgent(
                updatedWorkspaces[com.alyk.ai.koog.core.orchestrator.mcp.workspace.WorkspaceType.IMPLEMENTATION]!!,
                currentModel,
                combinedToolRegistry,
                toolUsageTracker
            )
        )
        
        Logger.info("ORCHESTRATOR", "✅ Activated MCP structure with ${updatedWorkspaces.size} workspaces")
        Logger.info("ORCHESTRATOR", "✅ All agents initialized with Koog ToolRegistry (file access tools enabled)")
    }

    /**
     * Load or switch to a different project at runtime and activate MCP structure
     * This activates the layered MCP agent architecture for the selected project
     */
    suspend fun loadProject(projectPath: String): Result<Unit> {
        return try {
            Logger.info("ORCHESTRATOR", "📦 Loading project: $projectPath")
            
            // Step 1: Load project context
            Logger.debug("ORCHESTRATOR", "Step 1: Loading project context...")
            contextProvider.loadProject(projectPath)
            val loadedFiles = contextProvider.getLoadedFiles()
            Logger.info("ORCHESTRATOR", "✅ Project context loaded - ${loadedFiles.size} files loaded")
            Logger.debug("ORCHESTRATOR", "Loaded files: ${loadedFiles.take(10).joinToString(", ")}${if (loadedFiles.size > 10) "..." else ""}")
            
            // Step 2: Initialize MCP integration for the project
            Logger.debug("ORCHESTRATOR", "Step 2: Initializing MCP integration...")
            val enhancedRegistry = mcpIntegration.initializeForProject(projectPath)
            Logger.info("ORCHESTRATOR", "✅ MCP integration initialized")
            
            // Step 3: Activate MCP structure (workspaces + agent router)
            // This creates all workspaces and initializes the agent router with MCP tools
            Logger.debug("ORCHESTRATOR", "Step 3: Activating MCP structure...")
            activateMcpStructure(projectPath)
            
            Logger.info("ORCHESTRATOR", "✅ Successfully activated MCP structure for project: $projectPath")
            
            Result.success(Unit)
        } catch (e: Exception) {
            Logger.error("ORCHESTRATOR", "❌ Failed to load project and activate MCP structure: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Unload the current project (e.g. when user unselects). Agent will have no project until one is loaded again.
     */
    suspend fun unloadProject(): Result<Unit> {
        return try {
            Logger.info("ORCHESTRATOR", "📤 Unloading project...")
            contextProvider.clearProject()
            mcpIntegration.clearProject()
            activateMcpStructure(null)
            Logger.info("ORCHESTRATOR", "✅ Project unloaded")
            Result.success(Unit)
        } catch (e: Exception) {
            Logger.error("ORCHESTRATOR", "❌ Failed to unload project: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Get currently loaded project information
     */
    fun getCurrentProject(): String? = contextProvider.getProjectRoot()
    
    /**
     * Select the appropriate model (local or cloud) based on current settings
     */
    private fun selectModel(): ModelWrapper {
        return when {
            useCloudModel && cloudModel != null -> {
                Logger.debug("ORCHESTRATOR", "Using cloud model: ${cloudModel.modelName}")
                cloudModel
            }
            modelSwitchControl != null -> {
                val model = modelSwitchControl.getCurrentModel()
                Logger.debug("ORCHESTRATOR", "Using model from switch control: ${model.modelName}")
                model
            }
            else -> {
                Logger.debug("ORCHESTRATOR", "Using default local model: ${localModel.modelName}")
                localModel
            }
        }
    }
    
    /**
     * Switch to cloud model if available
     */
    fun switchToCloudModel(): Result<Unit> {
        return if (cloudModel != null) {
            useCloudModel = true
            modelSwitchControl?.switchModel(cloudModel)
            Logger.info("ORCHESTRATOR", "Switched to cloud model: ${cloudModel.modelName}")
            Result.success(Unit)
        } else {
            Logger.warn("ORCHESTRATOR", "No cloud model available")
            Result.failure(IllegalStateException("No cloud model configured"))
        }
    }
    
    /**
     * Switch back to local model
     */
    fun switchToLocalModel(): Result<Unit> {
        useCloudModel = false
        modelSwitchControl?.switchModel(localModel)
        Logger.info("ORCHESTRATOR", "Switched to local model: ${localModel.modelName}")
        return Result.success(Unit)
    }
    
    /**
     * Check if cloud model is available
     */
    fun hasCloudModel(): Boolean = cloudModel != null
    
    /**
     * Get current model type (local or cloud)
     */
    fun getCurrentModelType(): String = if (useCloudModel && cloudModel != null) "cloud" else "local"

    /**
     * Route incoming tasks using the layered agent router
     * Simplified: Always uses ImplementationAgent (can be controlled via UI)
     */
    suspend fun routeTask(task: String, sessionId: String, agentType: com.alyk.ai.koog.core.orchestrator.router.AgentType = com.alyk.ai.koog.core.orchestrator.router.AgentType.IMPLEMENTATION): String {
        Logger.info("ORCHESTRATOR", "Routing task: '$task' (session: $sessionId, agent: $agentType)")
        
        val router = agentRouter ?: run {
            Logger.warn("ORCHESTRATOR", "Agent router not initialized, using fallback direct model")
            // Fallback to direct model if router not initialized
            val context = contextProvider.getContextForTask(task)
            Logger.debug("ORCHESTRATOR", "Context retrieved: ${context.length} chars")
            val mcpTools = getAvailableMCPTools()
            val promptWithContext = buildPrompt(task, context, mcpTools)
            val currentModel = selectModel()
            Logger.debug("ORCHESTRATOR", "Using model: ${currentModel.modelName} (${if (useCloudModel) "cloud" else "local"})")
            return currentModel.generate(promptWithContext)
        }
        
        val projectPath = getCurrentProject()
        val loadedFiles = contextProvider.getLoadedFiles()
        Logger.debug("ORCHESTRATOR", "Project path: ${projectPath ?: "null"}")
        Logger.debug("ORCHESTRATOR", "Loaded files: ${loadedFiles.size}")
        
        val taskContext = TaskContext(
            projectPath = projectPath,
            currentFiles = loadedFiles,
            sessionId = sessionId
        )
        
        Logger.debug("ORCHESTRATOR", "Calling router.routeTask with agent type: $agentType")
        val response = router.routeTask(task, taskContext, agentType)
        Logger.info("ORCHESTRATOR", "Task completed, response length: ${response.result.length}")
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
     * Get tool usage statistics
     */
    fun getToolUsageStats(): com.alyk.ai.koog.core.orchestrator.tools.ToolUsageStats {
        return toolUsageTracker.getToolUsageStats()
    }
    
    /**
     * Get file access statistics
     */
    fun getFileAccessStats(): com.alyk.ai.koog.core.orchestrator.tools.FileAccessStats {
        return toolUsageTracker.getFileAccessStats()
    }
    
    /**
     * Verify file access tools are working
     * This can be called to test tool functionality
     */
    fun verifyFileAccessTools(): com.alyk.ai.koog.core.orchestrator.tools.VerificationReport {
        val verifier = com.alyk.ai.koog.core.orchestrator.tools.ToolVerification(contextProvider, toolUsageTracker)
        return verifier.verifyFileAccessTools()
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
