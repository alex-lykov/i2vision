package gui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import core.AgentLauncher
import core.OutputEvent
import core.TaskMode
import gui.components.OutputConsole
import gui.components.QuickActions
import gui.components.StatusBar
import gui.components.TaskInput
import kotlinx.coroutines.launch

@Composable
fun MainWindow(agentLauncher: AgentLauncher, onCloseRequest: () -> Unit) {
    val scope = rememberCoroutineScope()
    var events by remember { mutableStateOf<List<OutputEvent>>(emptyList()) }
    var status by remember { mutableStateOf(agentLauncher.getStatus()) }

    fun submitTask(task: String, mode: TaskMode) {
        if (task.isBlank()) return
        scope.launch {
            agentLauncher.processTask(task, mode).collect { event ->
                events = events + event
                status = agentLauncher.getStatus()
            }
        }
    }

    Window(
        onCloseRequest = onCloseRequest,
        title = "KOOG Coding Agent",
        state = rememberWindowState(width = 900.dp, height = 600.dp, position = WindowPosition.Aligned(Alignment.Center))
    ) {
        MaterialTheme {
            Column(modifier = Modifier.fillMaxSize()) {
                StatusBar(
                    status = status,
                    onSwitchClick = {
                        events = events + OutputEvent.System("Model switch requested (simplified mode)")
                    }
                )
                TaskInput(
                    onProcess = { submitTask(it, TaskMode.CURRENT_MODEL) },
                    onAnalyze = { submitTask(it, TaskMode.SMART_ANALYZE) },
                    onDebug = { submitTask(it, TaskMode.DEBUG) }
                )
                QuickActions(
                    onAction = { submitTask(it, TaskMode.CURRENT_MODEL) }
                )
                OutputConsole(
                    events = events,
                    onClear = { events = emptyList() }
                )
            }
        }
    }
}
