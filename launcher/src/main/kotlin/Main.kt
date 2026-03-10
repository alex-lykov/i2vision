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
            sessionStartTime = Instant.now()
            println("Using Ollama API: ${config.ollamaApiUrl}")
            
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
                
                // Select smallest available model as initial model
                val smallestModel = localModels.minByOrNull { it.size }
                    ?: throw IllegalStateException("No local models available")
                
                println("📦 [INIT] Selecting smallest model as initial: ${smallestModel.id} (${smallestModel.formattedSize})")
                
                // Create initial model wrapper
                initialModel = unifiedModelManager.createModelWrapper(
                    smallestModel.id,
                    performanceMonitor
                ) ?: throw IllegalStateException("Failed to create model wrapper for ${smallestModel.id}")
                
                // Initialize model switch control with the smallest model
                modelSwitchControl = ModelSwitchControl(performanceMonitor, initialModel)
                
                // Initialize orchestrator with the selected model
                orchestrator = AgentOrchestrator(
                    sessionStore = SessionStore(),
                    decisionEngine = DecisionEngine(
                        contextAnalyzer = ContextAnalyzer(),
                        performanceMonitor = performanceMonitor
                    ),
                    contextProvider = contextProvider,
                    hierarchyBuilder = hierarchyBuilder,
                    localModel = initialModel,
                    cloudModel = null,
                    modelSwitchControl = modelSwitchControl,
                    mcpToolRegistry = ToolRegistry.EMPTY
                )
                
                // Initialize client with orchestrator
                client = AgentClient(orchestrator)
                
                orchestrator.initialize(config.projectPath)
                client.initialize(config.projectPath)
            }
            Result.success(Unit)
        } catch (e: Exception) {
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
            while (currentCoroutineContext().isActive) {
                emit(getStatus())
                delay(1000) // Update every second
            }
        }.flowOn(Dispatchers.Default)
    }

    override fun getStatus(): core.AgentStatus {
        val stats = orchestrator.getPerformanceStats()
        val warnings = orchestrator.getPerformanceWarnings()
        val alerts = orchestrator.getPerformanceAlerts()
        val currentModelId = modelSwitchControl.getCurrentModelId()
        val sessionTime = Duration.between(sessionStartTime, Instant.now())
        
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
            println("Error calculating context usage: ${e.message}")
            0.0f // Fallback to 0 if calculation fails
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
            currentModelId = currentModelId
        )
    }

    /**
     * Get list of available models from registry
     */
    override fun getAvailableModels(): List<ModelInfo> = availableModels
    
    /**
     * Switch to a specific model by ID
     */
    override fun switchToModel(modelId: String): Result<Unit> {
        return modelSwitchControl.switchToModel(modelId) { id, monitor ->
            unifiedModelManager.createModelWrapper(id, monitor)
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
        return Result.success(Unit)
    }

    override fun addStatusListener(listener: StatusListener) {
        listeners.add(listener)
    }

    override suspend fun loadProject(projectPath: String): Result<Unit> {
        return orchestrator.loadProject(projectPath)
    }

    /**
     * Get MCP integration status
     */
    override fun getMcpStatus(): McpStatus {
        return orchestrator.getMcpStatus()
    }

    /**
     * Get available MCP tools for current project
     */
    override fun getAvailableMCPTools(): Flow<String> {
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
