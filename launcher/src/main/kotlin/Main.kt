import cli.CliLauncher
import com.alyk.ai.koog.context.hierarchy.HierarchyBuilder
import com.alyk.ai.koog.context.provider.ContextProvider
import com.alyk.ai.koog.core.orchestrator.AgentOrchestrator
import com.alyk.ai.koog.core.session.SessionStore
import com.alyk.ai.koog.models.wrappers.LocalModelWrapper
import com.alyk.ai.koog.models.wrappers.ModelInfo
import com.alyk.ai.koog.models.wrappers.ModelRegistry
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
    private val modelRegistry = ModelRegistry("D:\\dev\\AI\\ollama\\models")
    private val initialModel = LocalModelWrapper(
        modelName = "qwen3:4b",
        maxContextLength = 4096,
        performanceMonitor = performanceMonitor
    )
    private val modelSwitchControl = ModelSwitchControl(performanceMonitor, initialModel)
    private val orchestrator = AgentOrchestrator(
        sessionStore = SessionStore(),
        decisionEngine = DecisionEngine(
            contextAnalyzer = ContextAnalyzer(),
            performanceMonitor = performanceMonitor
        ),
        contextProvider = contextProvider,
        hierarchyBuilder = hierarchyBuilder,
        localModel = initialModel
    )
    private val client = AgentClient(orchestrator)
    private val listeners = mutableListOf<StatusListener>()
    private var availableModels: List<ModelInfo> = emptyList()
    private val statusUpdateScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var sessionStartTime = Instant.now()

    override fun initialize(config: Config): Result<Unit> {
        return try {
            sessionStartTime = Instant.now()
            // Scan available models from Ollama directory
            availableModels = modelRegistry.scanModels()
            println("Found ${availableModels.size} models: ${availableModels.map { it.id }}")
            
            runBlocking {
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
        
        return AgentStatus(
            currentModel = ModelType.LOCAL,
            contextUsage = 0.7f, // TODO: Calculate actual context usage
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
            modelRegistry.createModelWrapper(id, monitor)
        }
    }

    override fun processTask(task: String, mode: TaskMode): Flow<OutputEvent> {
        return client.processTask(task, mode)
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
