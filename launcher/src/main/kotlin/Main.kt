import ai.koog.agents.core.tools.ToolRegistry
import cli.CliLauncher
import com.alyk.ai.koog.config.Config
import com.alyk.ai.koog.config.ConfigLoader
import com.alyk.ai.koog.context.hierarchy.HierarchyBuilder
import com.alyk.ai.koog.context.provider.ContextProvider
import com.alyk.ai.koog.core.orchestrator.AgentOrchestrator
import com.alyk.ai.koog.core.orchestrator.mcp.McpStatus
import com.alyk.ai.koog.core.session.ISessionStore
import com.alyk.ai.koog.core.session.project.ProjectRepository
import com.alyk.ai.koog.database.DatabaseFactory
import com.alyk.ai.koog.database.DatabaseProvider
import com.alyk.ai.koog.database.store.LoadedModelsStore
import com.alyk.ai.koog.models.wrappers.ModelInfo
import com.alyk.ai.koog.models.wrappers.ModelRegistry
import com.alyk.ai.koog.models.wrappers.ModelWrapper
import com.alyk.ai.koog.switching.analyzer.ContextAnalyzer
import com.alyk.ai.koog.switching.decision.DecisionEngine
import com.alyk.ai.koog.switching.decision.ModelSwitchControl
import com.alyk.ai.koog.switching.monitor.PerformanceMonitor
import core.*
import gui.launch
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.slf4j.LoggerFactory
import session.DatabaseBackedSessionStore
import session.DatabaseProjectRepository
import java.time.Duration
import java.time.Instant

class AgentLauncherImpl : AgentLauncher {
    private val log = LoggerFactory.getLogger(AgentLauncherImpl::class.java)
    private val hierarchyBuilder = HierarchyBuilder()
    private val contextProvider = ContextProvider(hierarchyBuilder)
    private val performanceMonitor = PerformanceMonitor()
    private val config: Config = ConfigLoader.load()
    private val localRegistry = ModelRegistry(config.ollamaApiUrl)
    private val unifiedModelManager = UnifiedModelManager(localRegistry, config.cloudConfigs)
    // Initial model will be set during initialize() to the smallest available model
    private lateinit var initialModel: ModelWrapper
    private lateinit var modelSwitchControl: ModelSwitchControl
    private lateinit var orchestrator: AgentOrchestrator
    private lateinit var client: AgentClient
    private val listeners = mutableListOf<StatusListener>()
    private var availableModels: List<ModelInfo> = emptyList()
    private val statusUpdateScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var sessionStartTime = Instant.now()
    private lateinit var projectRepository: ProjectRepository
    private lateinit var loadedModelsStore: LoadedModelsStore
    private lateinit var sessionStore: ISessionStore
    private var currentSessionId: java.util.UUID? = null
    private val beforeExitHooks = mutableListOf<suspend () -> Unit>()

    override fun initialize(config: Config): Result<Unit> {
        return try {
            log.info("========================================")
            log.info("Initializing AgentLauncher...")
            log.info("========================================")
            sessionStartTime = Instant.now()

            val dbProvider = DatabaseFactory.init()
            log.info("Database provider initialized (project settings, agent state, RAG, memory, prompt cache)")

            sessionStore = DatabaseBackedSessionStore(dbProvider.agentState)
            log.info("Session store: database-backed (persistent)")

            projectRepository = DatabaseProjectRepository(dbProvider.projects)
            log.info("Project repository: database-backed (projects from UI stored in DB)")
            loadedModelsStore = dbProvider.loadedModels
            log.info("Loaded models store: database-backed (restore running models on boot)")

            log.info("Using Ollama API: {}", config.ollamaApiUrl)
            log.info("Project path: {}", config.projectPath ?: "null")
            
            if (config.cloudConfigs.isNotEmpty()) {
                log.info("Cloud providers configured: {}", config.cloudConfigs.map { it.provider })
                config.cloudConfigs.forEach { c -> log.debug("  {}: {}", c.provider, c.apiUrl) }
                log.info("Cloud fallback enabled={} threshold={}ms", config.enableCloudFallback, config.cloudFallbackThreshold)
            } else {
                log.info("No cloud providers configured")
            }
            
            runBlocking<Unit> {
                availableModels = unifiedModelManager.scanAllModels()
                log.info("Found {} models total", availableModels.size)

                val localModels = unifiedModelManager.getLocalModels()
                val cloudModels = unifiedModelManager.getCloudModels()

                log.info("Local models: {}", localModels.size)
                localModels.forEach { m -> log.debug("  {} ({})", m.id, m.formattedSize) }
                log.info("Cloud models: {}", cloudModels.size)
                cloudModels.forEach { m -> log.debug("  {} ({})", m.id, m.formattedSize) }
                
                // Select smallest available model as initial model (prefer local, fallback to cloud)
                val smallestLocalModel = localModels.minByOrNull { it.size }
                val smallestCloudModel = cloudModels.minByOrNull { it.size }
                
                val initialModelInfo = when {
                    smallestLocalModel != null -> {
                        log.info("Selecting smallest local model as initial: {} ({})", smallestLocalModel.id, smallestLocalModel.formattedSize)
                        smallestLocalModel
                    }
                    smallestCloudModel != null -> {
                        log.info("No local models; selecting cloud model as initial: {} ({})", smallestCloudModel.id, smallestCloudModel.formattedSize)
                        smallestCloudModel
                    }
                    else -> throw IllegalStateException("No models available (local or cloud)")
                }
                
                // Create initial model wrapper
                initialModel = unifiedModelManager.createModelWrapper(
                    initialModelInfo.id,
                    performanceMonitor
                ) ?: throw IllegalStateException("Failed to create model wrapper for ${initialModelInfo.id}")
                
                // Create cloud model wrapper if cloud models are available and different from initial
                val cloudModelWrapper: ModelWrapper? = if (cloudModels.isNotEmpty() && smallestCloudModel != null && smallestCloudModel.id != initialModelInfo.id) {
                    unifiedModelManager.createModelWrapper(
                        smallestCloudModel.id,
                        performanceMonitor
                    ).also {
                        if (it != null) log.info("Cloud model available: {}", smallestCloudModel.id)
                    }
                } else if (cloudModels.isNotEmpty() && smallestLocalModel != null) {
                    unifiedModelManager.createModelWrapper(
                        smallestCloudModel?.id ?: cloudModels.first().id,
                        performanceMonitor
                    ).also {
                        if (it != null) log.info("Cloud model available for fallback: {}", smallestCloudModel?.id ?: cloudModels.first().id)
                    }
                } else null

                modelSwitchControl = ModelSwitchControl(performanceMonitor, initialModel)

                log.info("Creating AgentOrchestrator (sessionStore=database-backed)...")
                orchestrator = AgentOrchestrator(
                    sessionStore = sessionStore,
                    decisionEngine = DecisionEngine(
                        contextAnalyzer = ContextAnalyzer(),
                        performanceMonitor = performanceMonitor
                    ),
                    contextProvider = contextProvider,
                    hierarchyBuilder = hierarchyBuilder,
                    localModel = initialModel,
                    cloudModel = cloudModelWrapper,
                    modelSwitchControl = modelSwitchControl,
                    mcpToolRegistry = ToolRegistry.EMPTY
                )
                
                client = AgentClient(orchestrator)

                log.info("Initializing orchestrator with project path: {}", config.projectPath ?: "null")
                orchestrator.initialize(config.projectPath)
                log.info("Orchestrator initialized")

                log.info("Initializing client with project path: {}", config.projectPath ?: "null")
                client.initialize(config.projectPath)
                log.info("Client initialized")
            }
            log.info("Agent initialized successfully")
            log.info("========================================")
            Result.success(Unit)
        } catch (e: Exception) {
            log.error("Initialization failed: {}", e.message, e)
            Result.failure(e)
        }
    }

    override fun start() {
        // Start status monitoring, etc.
    }

    override fun shutdown() {
        log.info("Shutting down launcher...")
        statusUpdateScope.cancel()
        unifiedModelManager.close()
        client.shutdown()
        DatabaseFactory.close()
        log.info("Launcher shutdown complete")
    }

    override fun getStatusStream(): Flow<AgentStatus> {
        return flow {
            try {
                while (currentCoroutineContext().isActive) {
                    try {
                        emit(getStatus())
                        delay(2000) // Update every 2 seconds to reduce load
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        // Cancellation is expected when scope is cancelled
                        println("[STATUS] Status stream cancelled (expected)")
                        break
                    } catch (e: Exception) {
                        println("[STATUS] Error in status stream: ${e.message}")
                        delay(5000) // Wait longer on error
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Ignore cancellation exceptions
                println("[STATUS] Status stream scope cancelled")
            }
        }.flowOn(Dispatchers.Default)
    }

    private var lastStatusHash: Int = 0
    
    override fun getStatus(): core.AgentStatus {
        if (!::orchestrator.isInitialized) {
            println("[STATUS] Orchestrator not initialized yet, returning default status")
            return core.AgentStatus(
                currentModel = core.ModelType.ERROR,
                contextUsage = 0.0f,
                filesLoaded = 0,
                sessionTime = Duration.ZERO,
                confidence = 0.0f,
                lastSwitch = null,
                errors = listOf("Orchestrator not initialized"),
                currentModelId = "unknown",
                toolUsageStats = core.ToolUsageStats(0, 0, 0.0f, 0, emptyMap()),
                fileAccessStats = core.FileAccessStats(0, 0, 0, 0, 0, 0, 0),
                projectPath = null,
                mcpToolsCount = 0
            )
        }
        
        val stats = orchestrator.getPerformanceStats()
        val warnings = orchestrator.getPerformanceWarnings()
        val alerts = orchestrator.getPerformanceAlerts()
        val currentModelId = modelSwitchControl.getCurrentModelId()
        val sessionTime = Duration.between(sessionStartTime, Instant.now())
        
        // Get tool usage and file access stats
        val toolUsageStats = orchestrator.getToolUsageStats()
        val fileAccessStats = orchestrator.getFileAccessStats()
        
        // Get MCP status and tool count (cache to avoid blocking)
        val mcpStatus = orchestrator.getMcpStatus()
        val mcpToolsCount = if (mcpStatus is com.alyk.ai.koog.core.orchestrator.mcp.McpStatus.CONNECTED) {
            // Use cached count or estimate from MCP status
            6 // Default from logs, could be cached
        } else {
            0
        }
        
        // Get project info
        val projectPath = when (mcpStatus) {
            is com.alyk.ai.koog.core.orchestrator.mcp.McpStatus.CONNECTED -> mcpStatus.projectPath
            else -> orchestrator.getCurrentProject()
        }
        
        // Calculate actual context usage
        val contextUsage = try {
            val currentModel = modelSwitchControl.getCurrentModel()
            val maxContext = currentModel.maxContextLength
            
            // Get a sample context to estimate usage
            val sampleContext = runBlocking {
                contextProvider.getContextForTask("status check", 1)
            }
            
            // Estimate tokens in current context
            val estimatedTokens = currentModel.estimateTokens(sampleContext)
            val usageRatio = estimatedTokens.toFloat() / maxContext.toFloat()
            
            // Clamp between 0 and 1
            usageRatio.coerceIn(0f, 1f)
        } catch (e: Exception) {
            println("[STATUS] ❌ Error calculating context usage: ${e.message}")
            0.0f // Fallback to 0 if calculation fails
        }
        
        val filesLoaded = contextProvider.getLoadedFiles().size
        
        // Only log when status changes significantly
        val currentHash = (filesLoaded * 1000 + stats.totalRequests * 100 + contextUsage.toInt() * 10).hashCode()
        if (currentHash != lastStatusHash) {
            println("[STATUS] Update: model=$currentModelId, files=$filesLoaded, context=${(contextUsage * 100).toInt()}%, requests=${stats.totalRequests}, tools=${toolUsageStats.totalToolCalls}")
            lastStatusHash = currentHash
        }
        
        return AgentStatus(
            currentModel = ModelType.LOCAL,
            contextUsage = contextUsage,
            filesLoaded = contextProvider.getLoadedFiles().size,
            sessionTime = sessionTime,
            confidence = 0.85f, // TODO: Calculate actual confidence
            lastSwitch = null, // TODO: Track last switch time
            errors = emptyList(),
            avgResponseTimeMs = stats.avgResponseTimeMs,
            performanceWarnings = warnings.map { "[${it.severity}] ${it.message}" },
            hasPerformanceAlert = alerts.isNotEmpty(),
            currentModelId = currentModelId,
            // Enhanced monitoring
            totalRequests = stats.totalRequests,
            totalTokensUsed = stats.totalTokensUsed,
            totalTokensGenerated = stats.totalTokensGenerated,
            maxResponseTimeMs = stats.maxResponseTimeMs,
            toolUsageStats = core.ToolUsageStats(
                totalToolCalls = toolUsageStats.totalToolCalls,
                recentToolCalls = toolUsageStats.recentToolCalls,
                successRate = toolUsageStats.successRate,
                avgToolDurationMs = toolUsageStats.avgToolDurationMs,
                toolBreakdown = toolUsageStats.toolBreakdown
            ),
            fileAccessStats = core.FileAccessStats(
                totalFileOperations = fileAccessStats.totalFileOperations,
                readOperations = fileAccessStats.readOperations,
                writeOperations = fileAccessStats.writeOperations,
                searchOperations = fileAccessStats.searchOperations,
                listOperations = fileAccessStats.listOperations,
                successfulOperations = fileAccessStats.successfulOperations,
                failedOperations = fileAccessStats.failedOperations
            ),
            projectPath = when (mcpStatus) {
                is com.alyk.ai.koog.core.orchestrator.mcp.McpStatus.CONNECTED -> mcpStatus.projectPath
                else -> null
            },
            mcpToolsCount = mcpToolsCount
        )
    }

    /**
     * Get list of available models from registry
     */
    override fun getAvailableModels(): List<ModelInfo> = availableModels
    
    /**
     * Get the UnifiedModelManager for advanced model operations
     */
    override fun getUnifiedModelManager(): UnifiedModelManager = unifiedModelManager
    
    /**
     * Switch to a specific model by ID
     */
    override fun switchToModel(modelId: String): Result<Unit> {
        // Check if this is a cloud model
        val isCloudModel = modelId.contains(":") && modelId.split(":").first().lowercase() in 
            listOf("generic", "ollama_cloud", "hugging_face", "replicate", "anyscale")
        
        return if (isCloudModel) {
            // Switch to cloud model via orchestrator
            orchestrator.switchToCloudModel()
        } else {
            // Switch to local model
            val result = modelSwitchControl.switchToModel(modelId) { id, monitor ->
                unifiedModelManager.createModelWrapper(id, monitor)
            }
            if (result.isSuccess) {
                orchestrator.switchToLocalModel()
            }
            result
        }
    }

    /**
     * Auto-detect running models and switch to the smallest one
     * Delegates to ModelSwitchControl for proper separation of concerns
     */
    private suspend fun detectAndSwitchToRunningModel(): Result<Unit> {
        return try {
            println("🔍 [SWITCH] Checking for running models...")
            val runningModels = unifiedModelManager.getRunningModels()
            val currentModelId = modelSwitchControl.getCurrentModelId()
            
            println("🔍 [SWITCH] Current model: $currentModelId")
            if (runningModels.isEmpty()) {
                val isCloud = currentModelId.contains(":cloud", ignoreCase = true) || currentModelId.contains("cloud", ignoreCase = true)
                if (isCloud) {
                    println("🔍 [SWITCH] Cloud model in use (not listed in api/ps), keeping current model")
                } else {
                    println("🔍 [SWITCH] Found 0 running models:")
                }
            } else {
                println("🔍 [SWITCH] Found ${runningModels.size} running models:")
                runningModels.forEach { model ->
                    println("  - ${model.id} (${model.formattedSize})")
                }
            }
            
            // Use ModelSwitchControl to select and switch to smallest running model
            val result = modelSwitchControl.detectAndSwitchToSmallestRunningModel(runningModels) { id, monitor ->
                unifiedModelManager.createModelWrapper(id, monitor)
            }
            
            if (result.isSuccess) {
                val smallestModel = runningModels.minByOrNull { it.size }
                if (smallestModel != null && smallestModel.id != currentModelId) {
                    println("✅ [SWITCH] Successfully switched to smallest running model: ${smallestModel.id} (${smallestModel.formattedSize})")
                } else if (smallestModel != null) {
                    println("ℹ️ [SWITCH] Already using smallest running model: ${smallestModel.id}")
                } else {
                    if (runningModels.isEmpty()) {
                        println("ℹ️ [SWITCH] No running models in api/ps, keeping current model: $currentModelId")
                    } else {
                        println("ℹ️ [SWITCH] No running models found, keeping current model: $currentModelId")
                    }
                }
            }
            
            result
        } catch (e: Exception) {
            println("❌ [SWITCH] Error detecting running models: ${e.message}")
            e.printStackTrace()
            Result.failure(e)
        }
    }

    override fun processTask(task: String, mode: TaskMode, agentType: gui.data.AgentTypeDto): Flow<OutputEvent> {
        return flow {
            // Before processing, check if we should switch to a running model
            try {
                runBlocking {
                    detectAndSwitchToRunningModel()
                }
            } catch (e: Exception) {
                println("Warning: Failed to detect running models: ${e.message}")
            }

            val sessionId = runBlocking {
                if (currentSessionId == null) {
                    val projectPath = runCatching { orchestrator.getCurrentProject() }.getOrNull()
                    currentSessionId = sessionStore.createSession(projectPath)
                    log.info("Created session: {}", currentSessionId)
                }
                currentSessionId!!.toString()
            }
            
            client.processTask(task, mode, agentType, sessionId).collect { event ->
                emit(event)
            }
        }
    }

    override fun switchModel(target: ModelType): Result<Unit> {
        return when (target) {
            ModelType.CLOUD, ModelType.CLOUD_OLLAMA, ModelType.CLOUD_HF, 
            ModelType.CLOUD_REPLICATE, ModelType.CLOUD_ANYSCALE -> {
                if (orchestrator.hasCloudModel()) {
                    orchestrator.switchToCloudModel()
                } else {
                    Result.failure(IllegalStateException("No cloud model configured"))
                }
            }
            else -> {
                orchestrator.switchToLocalModel()
            }
        }
    }

    override fun addStatusListener(listener: StatusListener) {
        listeners.add(listener)
    }

    override suspend fun loadProject(projectPath: String): Result<Unit> {
        if (!::orchestrator.isInitialized) {
            println("[PROJECT] Orchestrator not initialized yet, cannot load project")
            return Result.failure(IllegalStateException("Orchestrator not initialized"))
        }
        return orchestrator.loadProject(projectPath)
    }

    override suspend fun unloadProject(): Result<Unit> {
        if (!::orchestrator.isInitialized) return Result.success(Unit)
        return orchestrator.unloadProject()
    }

    /**
     * Get MCP integration status
     */
    override fun getMcpStatus(): McpStatus {
        if (!::orchestrator.isInitialized) {
            println("[MCP] Orchestrator not initialized yet, returning error status")
            return McpStatus.ERROR("Orchestrator not initialized")
        }
        return orchestrator.getMcpStatus()
    }

    /**
     * Get available MCP tools for current project
     */
    override fun getAvailableMCPTools(): Flow<String> {
        if (!::orchestrator.isInitialized) {
            println("[MCP] Orchestrator not initialized yet, returning empty flow")
            return emptyFlow()
        }
        return orchestrator.getAvailableMCPTools()
    }

    override fun getProjectRepository(): ProjectRepository = projectRepository

    override fun getLoadedModelsStore(): LoadedModelsStore = loadedModelsStore

    override fun getDatabaseProvider(): DatabaseProvider? = DatabaseFactory.getProvider()

    override fun getTerminalSettingsRepository(): com.alyk.ai.koog.database.settings.TerminalSettingsRepository? =
        DatabaseFactory.getProvider()?.terminalSettingsRepository("default", null)

    override fun registerBeforeExit(callback: suspend () -> Unit) {
        beforeExitHooks.add(callback)
    }

    override suspend fun runBeforeExitHooks() {
        beforeExitHooks.forEach { it.invoke() }
    }
}

fun main(args: Array<String>) {
    val launcher = AgentLauncherImpl()
    // No project loaded at startup; projects are added via UI (Projects card) and stored in DB
    launcher.initialize(Config(projectPath = null))

    if (args.contains("--cli")) {
        println("Starting in CLI mode (no project loaded by default)")
        val cli = CliLauncher(launcher)
        cli.launch()
    } else {
        println("Starting in GUI mode — add/select projects from the Projects card")
        launch(launcher)
    }
}
