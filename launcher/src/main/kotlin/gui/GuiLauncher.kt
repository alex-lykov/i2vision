package gui

import androidx.compose.ui.window.application
import core.AgentLauncher

fun launch(agentLauncher: AgentLauncher) {
    application {
        MainWindow(agentLauncher = agentLauncher, onCloseRequest = ::exitApplication)
    }
}
