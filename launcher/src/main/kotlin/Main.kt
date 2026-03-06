import cli.CliLauncher
import core.*
import gui.launch
import kotlinx.coroutines.flow.Flow
import java.time.Duration

class AgentLauncherImpl : AgentLauncher {
    private val client = AgentClient()
    private val switchControl = SwitchControl()
    private val listeners = mutableListOf<StatusListener>()

    override fun initialize(config: Config): Result<Unit> {
        client.initialize()
        return Result.success(Unit)
    }

    override fun start() {
        // Start status monitoring, etc.
    }

    override fun shutdown() {
        client.shutdown()
    }

    override fun getStatus(): core.AgentStatus {
        return core.AgentStatus(
            currentModel = ModelType.LOCAL,
            contextUsage = 0.7f,
            filesLoaded = 12,
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
    val launcher = AgentLauncherImpl()
    launcher.initialize(Config())

    if (args.contains("--cli")) {
        println("Starting in CLI mode")
        val cli = CliLauncher(launcher)
        cli.launch()
    } else {
        println("Starting in GUI mode")
        launch()
    }
}
