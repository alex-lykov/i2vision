import cli.CliLauncher
import com.alyk.ai.koog.context.hierarchy.HierarchyBuilder
import com.alyk.ai.koog.context.provider.ContextProvider
import com.alyk.ai.koog.core.orchestrator.AgentOrchestrator
import com.alyk.ai.koog.core.session.SessionStore
import com.alyk.ai.koog.models.wrappers.LocalModelWrapper
import com.alyk.ai.koog.switching.analyzer.ContextAnalyzer
import com.alyk.ai.koog.switching.decision.DecisionEngine
import com.alyk.ai.koog.switching.monitor.PerformanceMonitor
import core.*
import gui.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import java.time.Duration

class AgentLauncherImpl : AgentLauncher {
    private val hierarchyBuilder = HierarchyBuilder()
    private val contextProvider = ContextProvider(hierarchyBuilder)
    private val orchestrator = AgentOrchestrator(
        sessionStore = SessionStore(),
        decisionEngine = DecisionEngine(
            contextAnalyzer = ContextAnalyzer(),
            performanceMonitor = PerformanceMonitor()
        ),
        contextProvider = contextProvider,
        hierarchyBuilder = hierarchyBuilder,
        localModel = LocalModelWrapper(
            modelName = "gpt-oss:20b",
            maxContextLength = 1024
        )
    )
    private val client = AgentClient(orchestrator)
    private val switchControl = SwitchControl()
    private val listeners = mutableListOf<StatusListener>()

    override fun initialize(config: Config): Result<Unit> {
        runBlocking {
            orchestrator.initialize(config.projectPath)
            client.initialize(config.projectPath)
        }
        return Result.success(Unit)
    }

    override fun start() {
        // Start status monitoring, etc.
    }

    override fun shutdown() {
        client.shutdown()
    }

    override fun getStatus(): core.AgentStatus {
        return AgentStatus(
            currentModel = ModelType.LOCAL,
            contextUsage = 0.7f,
            filesLoaded = contextProvider.getLoadedFiles().size,
            sessionTime = Duration.ofMinutes(5),
            confidence = 0.85f,
            lastSwitch = null,
            errors = emptyList()
        )
    }

    override fun processTask(task: String, mode: TaskMode): Flow<OutputEvent> {
        return client.processTask(task, mode)
    }

    override fun switchModel(target: ModelType): Result<Unit> {
        return switchControl.switchModel(target)
    }

    override fun addStatusListener(listener: StatusListener) {
        listeners.add(listener)
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
