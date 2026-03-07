package gui

import androidx.compose.foundation.layout.*
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import com.alyk.ai.koog.core.session.project.JsonProjectRepository
import com.alyk.ai.koog.core.session.project.ProjectRepository
import core.AgentLauncher
import core.OutputEvent
import core.TaskMode
import gui.components.*
import kotlinx.coroutines.launch

@Composable
fun MainWindow(agentLauncher: AgentLauncher, onCloseRequest: () -> Unit) {
    val scope = rememberCoroutineScope()
    val projectRepository: ProjectRepository = remember { JsonProjectRepository() }
    val availableModels = remember { agentLauncher.getAvailableModels() }
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
        state = rememberWindowState(width = 1200.dp, height = 800.dp, position = WindowPosition.Aligned(Alignment.Center))
    ) {
        MaterialTheme {
            Row(
                modifier = Modifier.fillMaxSize().padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Left side: Terminal window (prompt + output combined)
                Column(
                    modifier = Modifier.weight(0.6f).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TaskInput(
                        onProcess = { submitTask(it, TaskMode.CURRENT_MODEL) },
                        onAnalyze = { submitTask(it, TaskMode.SMART_ANALYZE) },
                        onDebug = { submitTask(it, TaskMode.DEBUG) }
                    )
                    OutputConsole(
                        events = events,
                        onClear = { events = emptyList() },
                        modifier = Modifier.weight(1f)
                    )
                }

                // Right side: Management panels
                Column(
                    modifier = Modifier.weight(0.4f).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ProjectManagementPanel(
                        projectRepository = projectRepository,
                        onProjectSelected = { project ->
                            scope.launch {
                                val result = agentLauncher.loadProject(project.path)
                                if (result.isSuccess) {
                                    events = events + OutputEvent.System("Project loaded: ${project.name} (${project.path})")
                                    status = agentLauncher.getStatus()
                                } else {
                                    events = events + OutputEvent.Error("Failed to load project: ${result.exceptionOrNull()?.message}")
                                }
                            }
                        }
                    )
                    ModelListPanel(
                        models = availableModels,
                        currentModelId = status.currentModelId,
                        onModelSelected = { model ->
                            val result = agentLauncher.switchToModel(model.id)
                            if (result.isSuccess) {
                                events = events + OutputEvent.System("Switched to model: ${model.id}")
                                status = agentLauncher.getStatus()
                            } else {
                                events = events + OutputEvent.Error("Failed to switch model: ${result.exceptionOrNull()?.message}")
                            }
                        }
                    )
                    QuickActions(
                        onAction = { submitTask(it, TaskMode.CURRENT_MODEL) }
                    )
                    StatusBar(
                        status = status,
                        onSwitchClick = {
                            events = events + OutputEvent.System("Model switch requested")
                        }
                    )
                }
            }
        }
    }
}
