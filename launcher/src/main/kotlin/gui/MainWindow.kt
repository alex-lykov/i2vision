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
import core.AgentLauncher
import core.TaskMode
import gui.components.*
import gui.viewmodel.MainViewModel
import gui.viewmodel.TerminalViewModel

@Composable
fun MainWindow(agentLauncher: AgentLauncher, onCloseRequest: () -> Unit) {
    // Use rememberCoroutineScope() which provides the correct Main dispatcher for Compose Desktop
    val coroutineScope = rememberCoroutineScope()
    
    // Create ViewModels (terminal settings from DB filter/format output when available)
    val mainViewModel = remember {
        MainViewModel(
            agentLauncher,
            coroutineScope,
            terminalSettingsRepository = agentLauncher.getTerminalSettingsRepository()
        )
    }
    val terminalViewModel = remember {
        TerminalViewModel(mainViewModel)
    }
    
    // Observe state from ViewModels
    val terminalState by terminalViewModel.state.collectAsState()
    val agentStatus by mainViewModel.agentStatus.collectAsState()
    val mcpStatus by mainViewModel.mcpStatus.collectAsState()
    
    val projectRepository = remember { agentLauncher.getProjectRepository() }
    val availableModels = remember { agentLauncher.getAvailableModels() }

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
                    viewModel = terminalViewModel,
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
                            mainViewModel.loadProject(project.path) { _, _ -> }
                        },
                        onProjectUnselected = {
                            mainViewModel.unloadProject { _, _ -> }
                        }
                    )
                    // Performance Card (separate)
                    val currentStatus = agentStatus
                    if (currentStatus != null) {
                        PerformanceCard(status = currentStatus)
                    } else {
                        RightPanelCardWithStatus(
                            title = "Performance",
                            statusText = "Loading...",
                            statusColor = androidx.compose.ui.graphics.Color.Gray,
                            modifier = Modifier
                        ) {
                            androidx.compose.material.Text("Waiting for status...")
                        }
                    }

                    // System Monitor
                    SystemMonitor()

                    // Combined Ollama Monitor and Control
                    OllamaMonitorAndControl(agentLauncher.getUnifiedModelManager(), agentLauncher)


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
                        onAction = { task ->
                            mainViewModel.submitTask(task, TaskMode.CURRENT_MODEL)
                        }
                    )
                }
            }
        }
    }
}
