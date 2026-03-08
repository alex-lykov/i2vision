package gui

import androidx.compose.ui.window.application
import com.alyk.ai.koog.config.ConfigLoader
import core.AgentLauncher

fun launch(agentLauncher: AgentLauncher) {
    val config = ConfigLoader.load()
    agentLauncher.initialize(config)
    application {
        MainWindow(agentLauncher = agentLauncher, onCloseRequest = ::exitApplication)
    }
}
