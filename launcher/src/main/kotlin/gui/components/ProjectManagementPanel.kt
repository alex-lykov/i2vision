package gui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.alyk.ai.koog.core.session.project.Project
import com.alyk.ai.koog.core.session.project.ProjectRepository
import kotlinx.coroutines.delay
import javax.swing.JFileChooser

@Composable
fun ProjectManagementPanel(
    projectRepository: ProjectRepository,
    onProjectSelected: (Project) -> Unit,
    onProjectUnselected: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var projects by remember { mutableStateOf(projectRepository.getAllProjects()) }
    var activeProject by remember { mutableStateOf(projectRepository.getActiveProject()) }
    var showAddDialog by remember { mutableStateOf(false) }

    // Restore active project on boot (load it in the orchestrator so agent uses it)
    LaunchedEffect(projectRepository) {
        delay(1500) // let app and orchestrator be ready
        val active = projectRepository.getActiveProject()
        if (active != null) {
            println("[PROJECTS] Restoring active project from DB: ${active.name} (${active.path})")
            onProjectSelected(active)
        }
    }

    RightPanelCardWithAction(
        title = "Projects (${projects.size})",
        actionLabel = "Add",
        onAction = { showAddDialog = true },
        modifier = modifier
    ) {
        if (projects.isEmpty()) {
            Text(
                text = "No projects added yet. Click 'Add' to get started.",
                style = MaterialTheme.typography.body2,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
            )
        } else {
            Column {
                // Unselect control: show when a project is active
                if (activeProject != null) {
                    Text(
                        text = "Unselect",
                        style = MaterialTheme.typography.caption,
                        color = MaterialTheme.colors.primary.copy(alpha = 0.9f),
                        modifier = Modifier
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                            .clickable {
                                projectRepository.clearActiveProject()
                                activeProject = null
                                projects = projectRepository.getAllProjects()
                                onProjectUnselected()
                            }
                    )
                }
                projects.forEach { project ->
                    ProjectListItem(
                        project = project,
                        isActive = project.id == activeProject?.id,
                        onSelect = {
                            projectRepository.setActiveProject(project.id)
                            activeProject = project
                            onProjectSelected(project)
                            projects = projectRepository.getAllProjects()
                        },
                        onRemove = {
                            projectRepository.removeProject(project.id)
                            projects = projectRepository.getAllProjects()
                            activeProject = projectRepository.getActiveProject()
                        }
                    )
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
            onPickFolder = {
                val chooser = JFileChooser().apply {
                    fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                    dialogTitle = "Select Project Folder"
                }
                if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                    val path = chooser.selectedFile.absolutePath
                    val name = chooser.selectedFile.name
                    projectRepository.addProject(Project(id = System.currentTimeMillis().toString(), name = name, path = path))
                    projects = projectRepository.getAllProjects()
                    showAddDialog = false
                }
            }
        )
    }
}

@Composable
internal fun ProjectListItem(
    project: Project,
    isActive: Boolean,
    onSelect: () -> Unit,
    onRemove: () -> Unit
) {
    val backgroundColor = if (isActive) {
        MaterialTheme.colors.primary.copy(alpha = 0.1f)
    } else {
        MaterialTheme.colors.surface
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(backgroundColor)
            .clickable(onClick = onSelect)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (isActive) Icons.Default.CheckCircle else Icons.Default.Menu,
            contentDescription = null,
            tint = if (isActive) MaterialTheme.colors.primary else MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.size(20.dp)
        )

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = project.name,
                style = MaterialTheme.typography.body1,
                color = if (isActive) MaterialTheme.colors.primary else MaterialTheme.colors.onSurface
            )
            Text(
                text = project.path,
                style = MaterialTheme.typography.caption,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                maxLines = 1
            )
        }

        IconButton(
            onClick = onRemove,
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = "Remove",
                tint = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}
