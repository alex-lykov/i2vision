package gui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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

    // Subscribe to real-time status updates
    LaunchedEffect(agentLauncher) {
        agentLauncher.getStatusStream().collect { newStatus ->
            status = newStatus
        }
    }

    fun submitTask(task: String, mode: TaskMode) {
        if (task.isBlank()) return
        scope.launch {
            events = events + OutputEvent.System(">>> $task")
            agentLauncher.processTask(task, mode).collect { event ->
                events = events + event
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
                // Left side: IntelliJ-style Terminal (prompt at bottom, output above)
                Terminal(
                    events = events,
                    onClear = { events = emptyList() },
                    onSubmit = { task, mode -> submitTask(task, mode) },
                    modifier = Modifier.weight(0.6f).fillMaxHeight()
                )

                // Right side: Management panels
                Column(
                    modifier = Modifier.weight(0.4f).fillMaxHeight().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Project Management Card
                    ProjectManagementPanel(
                        projectRepository = projectRepository,
                        onProjectSelected = { project ->
                            scope.launch {
                                val result = agentLauncher.loadProject(project.path)
                                if (result.isSuccess) {
                                    events = events + OutputEvent.System("Project loaded: ${project.name}")
                                } else {
                                    events = events + OutputEvent.Error("Failed to load project: ${result.exceptionOrNull()?.message}")
                                }
                            }
                        }
                    )
                    // Performance Card (separate)
                    PerformanceCard(status = status)

                    // System Monitor
                    SystemMonitor()

                    // Combined Ollama Monitor and Control
                    OllamaMonitorAndControl()


/*                    ModelListPanel(
                        models = availableModels,
                        currentModelId = status.currentModelId,
                        onModelSelected = { model ->
                            val result = agentLauncher.switchToModel(model.id)
                            if (result.isSuccess) {
                                events = events + OutputEvent.System("Switched to model: ${model.id}")
                            } else {
                                events = events + OutputEvent.Error("Failed to switch model: ${result.exceptionOrNull()?.message}")
                            }
                        }
                    )*/
                    
                    QuickActions(
                        onAction = { submitTask(it, TaskMode.CURRENT_MODEL) }
                    )
                }
            }
        }
    }
}
