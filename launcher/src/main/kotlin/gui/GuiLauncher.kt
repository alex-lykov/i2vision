package gui

import androidx.compose.ui.window.application
import core.AgentLauncher
import kotlinx.coroutines.runBlocking

fun launch(agentLauncher: AgentLauncher) {
    // Launcher already initialized in main() with projectPath = null; do not re-initialize
    // with ConfigLoader.load() (which would set projectPath = user.dir and load the launcher as project)
    application {
        MainWindow(
            agentLauncher = agentLauncher,
            onCloseRequest = {
                runBlocking { agentLauncher.runBeforeExitHooks() }
                agentLauncher.shutdown()
                exitApplication()
            }
        )
    }
}
