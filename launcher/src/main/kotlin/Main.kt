import ai.koog.agents.core.tools.ToolRegistry
import cli.CliLauncher
import com.alyk.ai.koog.config.Config
import com.alyk.ai.koog.config.ConfigLoader
import com.alyk.ai.koog.context.hierarchy.HierarchyBuilder
import com.alyk.ai.koog.context.provider.ContextProvider
import com.alyk.ai.koog.core.orchestrator.AgentOrchestrator
import com.alyk.ai.koog.core.orchestrator.mcp.McpStatus
import com.alyk.ai.koog.core.session.SessionStore
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
import java.time.Duration
import java.time.Instant

class AgentLauncherImpl : AgentLauncher {
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

    override fun initialize(config: Config): Result<Unit> {
        return try {
            println("[INIT] ========================================")
            println("[INIT] Initializing AgentLauncher...")
            println("[INIT] ========================================")
            sessionStartTime = Instant.now()
            println("[INIT] Using Ollama API: ${config.ollamaApiUrl}")
            println("[INIT] Project path: ${config.projectPath ?: "null"}")
            
            // Show cloud configuration
            if (config.cloudConfigs.isNotEmpty()) {
                println("Cloud providers configured:")
                config.cloudConfigs.forEach { cloudConfig ->
                    println("  - ${cloudConfig.provider}: ${cloudConfig.apiUrl}")
                }
                println("Cloud fallback enabled: ${config.enableCloudFallback}")
                println("Cloud fallback threshold: ${config.cloudFallbackThreshold}ms")
            } else {
                println("No cloud providers configured")
            }
            
            runBlocking {
                // Scan available models from both local and cloud sources
                availableModels = unifiedModelManager.scanAllModels()
                println("Found ${availableModels.size} models total:")
                
                val localModels = unifiedModelManager.getLocalModels()
                val cloudModels = unifiedModelManager.getCloudModels()
                
                println("  Local models: ${localModels.size}")
                localModels.forEach { model ->
                    println("    - ${model.id} (${model.formattedSize})")
                }
                
                println("  Cloud models: ${cloudModels.size}")
                cloudModels.forEach { model ->
                    println("    - ${model.id} (${model.formattedSize})")
                }
                
                // Select smallest available model as initial model (prefer local, fallback to cloud)
                val smallestLocalModel = localModels.minByOrNull { it.size }
                val smallestCloudModel = cloudModels.minByOrNull { it.size }
                
                val initialModelInfo = when {
                    smallestLocalModel != null -> {
                        println("📦 [INIT] Selecting smallest local model as initial: ${smallestLocalModel.id} (${smallestLocalModel.formattedSize})")
                        smallestLocalModel
                    }
                    smallestCloudModel != null -> {
                        println("📦 [INIT] No local models, selecting cloud model as initial: ${smallestCloudModel.id} (${smallestCloudModel.formattedSize})")
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
                        if (it != null) {
                            println("☁️ [INIT] Cloud model available: ${smallestCloudModel.id}")
                        }
                    }
                } else if (cloudModels.isNotEmpty() && smallestLocalModel != null) {
                    // If we're using local, still create a cloud model wrapper for fallback
                    unifiedModelManager.createModelWrapper(
                        smallestCloudModel?.id ?: cloudModels.first().id,
                        performanceMonitor
                    ).also {
                        if (it != null) {
                            println("☁️ [INIT] Cloud model available for fallback: ${smallestCloudModel?.id ?: cloudModels.first().id}")
                        }
                    }
                } else null
                
                // Initialize model switch control with the initial model
                modelSwitchControl = ModelSwitchControl(performanceMonitor, initialModel)
                
                // Initialize orchestrator with both local and cloud models
                println("[INIT] Creating AgentOrchestrator...")
                orchestrator = AgentOrchestrator(
                    sessionStore = SessionStore(),
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
                
                // Initialize client with orchestrator
                client = AgentClient(orchestrator)
                
                println("[INIT] Initializing orchestrator with project path: ${config.projectPath ?: "null"}")
                orchestrator.initialize(config.projectPath)
                println("[INIT] Orchestrator initialized")
                
                println("[INIT] Initializing client with project path: ${config.projectPath ?: "null"}")
                client.initialize(config.projectPath)
                println("[INIT] Client initialized")
            }
            println("[INIT] ✅ Agent initialized successfully")
            println("[INIT] ========================================")
            Result.success(Unit)
        } catch (e: Exception) {
            println("[INIT] ❌ Initialization failed: ${e.message}")
            e.printStackTrace()
            Result.failure(e)
        }
    }

    override fun start() {
        // Start status monitoring, etc.
    }

    override fun shutdown() {
        statusUpdateScope.cancel()
        unifiedModelManager.close()
        client.shutdown()
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
            println("🔍 [SWITCH] Found ${runningModels.size} running models:")
            runningModels.forEach { model ->
                println("  - ${model.id} (${model.formattedSize})")
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
                    println("ℹ️ [SWITCH] No running models found, keeping current model: $currentModelId")
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
            
            // Process the task with the selected agent type
            client.processTask(task, mode, agentType).collect { event ->
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
}

fun main(args: Array<String>) {
    val projectPath = System.getProperty("user.dir")
    val launcher = AgentLauncherImpl()
    launcher.initialize(Config(projectPath = projectPath))

    if (args.contains("--cli")) {
        println("Starting in CLI mode")
        println("Project path: $projectPath")
        val cli = CliLauncher(launcher)
        cli.launch()
    } else {
        println("Starting in GUI mode")
        println("Project path: $projectPath")
        launch(launcher)
    }
}
