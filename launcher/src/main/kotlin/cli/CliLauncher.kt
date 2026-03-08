package cli

import com.alyk.ai.koog.config.ConfigLoader
import core.AgentLauncher
import core.ModelType
import core.TaskMode
import kotlinx.coroutines.runBlocking

class CliLauncher(private val launcher: AgentLauncher) {

    fun launch() = runBlocking {
        val config = ConfigLoader.load()
        launcher.initialize(config)
        
        println("KOOG Coding Agent CLI")
        println("Type '/help' for commands")
        println("Using Ollama API: ${config.ollamaApiUrl}")

        while (true) {
            print("> ")
            val input = readLine() ?: break
            if (input.isBlank()) continue

            if (input.startsWith("/")) {
                handleCommand(input)
            } else {
                processTask(input)
            }
        }
    }

    private suspend fun handleCommand(command: String) {
        when (command) {
            "/status" -> {
                val status = launcher.getStatus()
                println("Status: ${status.currentModel}")
            }
            "/switch local" -> launcher.switchModel(ModelType.LOCAL)
            "/switch cloud" -> launcher.switchModel(ModelType.CLOUD)
            "/help" -> println("Commands: /status, /switch [local|cloud], /help, /exit")
            "/exit" -> System.exit(0)
            else -> println("Unknown command: $command")
        }
    }

    private suspend fun processTask(task: String) {
        launcher.processTask(task, TaskMode.CURRENT_MODEL).collect { event ->
            println(event)
        }
    }
}
