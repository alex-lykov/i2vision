package gui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.alyk.ai.koog.core.session.project.Project
import com.alyk.ai.koog.core.session.project.ProjectRepository
import core.AgentStatus
import core.ModelType

@Composable
fun ProjectPerformanceCard(
    projectRepository: ProjectRepository,
    status: AgentStatus,
    onProjectSelected: (Project) -> Unit,
    modifier: Modifier = Modifier
) {
    var projects by remember { mutableStateOf<List<Project>>(projectRepository.getAllProjects()) }
    var showAddDialog by remember { mutableStateOf(false) }
    var showFolderPicker by remember { mutableStateOf(false) }

    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = 4.dp
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            // Performance Section (on top)
            PerformanceSection(status = status)

            Divider(modifier = Modifier.padding(vertical = 8.dp))

            // Project Management Section
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Projects (${projects.size})",
                    style = MaterialTheme.typography.h6
                )
                Button(
                    onClick = { showAddDialog = true },
                    modifier = Modifier.height(32.dp)
                ) {
                    Text("Add")
                }
            }

            Spacer(Modifier.height(8.dp))

            if (projects.isEmpty()) {
                Text(
                    text = "No projects yet. Add one to start.",
                    style = MaterialTheme.typography.body2,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                )
            } else {
                Column {
                    projects.forEach { project ->
                        ProjectListItem(
                            project = project,
                            isActive = false,
                            onSelect = { onProjectSelected(project) },
                            onRemove = {
                                projectRepository.removeProject(project.id)
                                projects = projectRepository.getAllProjects()
                            }
                        )
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddProjectDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { name, path ->
                projectRepository.addProject(Project(id = System.currentTimeMillis().toString(), name = name, path = path))
                projects = projectRepository.getAllProjects()
                showAddDialog = false
            },
            onPickFolder = { showFolderPicker = true }
        )
    }
}

@Composable
private fun PerformanceSection(status: AgentStatus) {
    Column {
        Text(
            text = "Performance",
            style = MaterialTheme.typography.h6
        )
        
        Spacer(Modifier.height(8.dp))
        
        // Model and Response Time Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Model Indicator
            Row(verticalAlignment = Alignment.CenterVertically) {
                val color = when (status.currentModel) {
                    ModelType.LOCAL -> if (status.hasPerformanceAlert) Color.Red else Color.Green
                    ModelType.CLOUD -> Color.Blue
                    ModelType.SWITCHING -> Color.Yellow
                    ModelType.ERROR -> Color.Red
                }
                Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(color))
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = status.currentModelId.takeIf { it.isNotBlank() } ?: status.currentModel.name,
                    style = MaterialTheme.typography.body2
                )
                if (status.hasPerformanceAlert) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(text = "⚠️", color = Color.Red, style = MaterialTheme.typography.caption)
                }
            }
            
            // Response Time
            Text(
                text = "${status.avgResponseTimeMs}ms",
                style = MaterialTheme.typography.body2,
                color = when {
                    status.avgResponseTimeMs > 15000 -> Color.Red
                    status.avgResponseTimeMs > 5000 -> Color(0xFFFFA500)
                    else -> MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                }
            )
        }
        
        Spacer(Modifier.height(6.dp))
        
        // Context Usage and Files Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Context Usage
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Context: ",
                    style = MaterialTheme.typography.caption,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                )
                LinearProgressIndicator(
                    progress = status.contextUsage,
                    modifier = Modifier.width(80.dp).height(4.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "${(status.contextUsage * 100).toInt()}%",
                    style = MaterialTheme.typography.caption
                )
            }
            
            // Files and Session Time
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "📁 ${status.filesLoaded}",
                    style = MaterialTheme.typography.caption,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                )
                val minutes = status.sessionTime.toMinutes()
                val seconds = status.sessionTime.seconds % 60
                Text(
                    text = "⏱️ %02d:%02d".format(minutes, seconds),
                    style = MaterialTheme.typography.caption,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                )
            }
        }
        
        // Performance Warnings (if any)
        if (status.performanceWarnings.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFFFF3E0), shape = MaterialTheme.shapes.small)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                status.performanceWarnings.take(2).forEach { warning ->
                    Text(
                        text = "⚠️ $warning",
                        color = Color(0xFFEF6C00),
                        style = MaterialTheme.typography.caption
                    )
                }
            }
        }
    }
}
