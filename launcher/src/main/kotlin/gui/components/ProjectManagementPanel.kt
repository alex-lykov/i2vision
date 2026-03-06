package gui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.alyk.ai.koog.core.session.project.Project
import com.alyk.ai.koog.core.session.project.ProjectRepository
import javax.swing.JFileChooser

@Composable
fun ProjectManagementPanel(
    projectRepository: ProjectRepository,
    onProjectSelected: (Project) -> Unit,
    modifier: Modifier = Modifier
) {
    var projects by remember { mutableStateOf(projectRepository.getAllProjects()) }
    var activeProject by remember { mutableStateOf(projectRepository.getActiveProject()) }
    var showAddDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf<Project?>(null) }
    var newProjectName by remember { mutableStateOf("") }
    var newProjectPath by remember { mutableStateOf("") }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(8.dp),
        elevation = 4.dp
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Projects",
                    style = MaterialTheme.typography.h6
                )
                Button(
                    onClick = { showAddDialog = true }
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Add")
                    Spacer(Modifier.width(4.dp))
                    Text("Add Project")
                }
            }

            Spacer(Modifier.height(8.dp))

            if (projects.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No projects added yet.\nClick 'Add Project' to get started.",
                        style = MaterialTheme.typography.body2,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 200.dp)
                ) {
                    items(projects) { project ->
                        ProjectListItem(
                            project = project,
                            isActive = project.id == activeProject?.id,
                            onSelect = {
                                projectRepository.setActiveProject(project.id)
                                activeProject = project
                                onProjectSelected(project)
                                projects = projectRepository.getAllProjects()
                            },
                            onDelete = {
                                showDeleteConfirm = project
                            }
                        )
                    }
                }
            }
        }
    }

    // Add Project Dialog
    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("Add New Project") },
            text = {
                Column {
                    OutlinedTextField(
                        value = newProjectName,
                        onValueChange = { newProjectName = it },
                        label = { Text("Project Name") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = newProjectPath,
                            onValueChange = { newProjectPath = it },
                            label = { Text("Project Folder") },
                            modifier = Modifier.weight(1f),
                            readOnly = true
                        )
                        IconButton(
                            onClick = {
                                val chooser = JFileChooser().apply {
                                    fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                                    dialogTitle = "Select Project Folder"
                                }
                                if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                                    newProjectPath = chooser.selectedFile.absolutePath
                                    if (newProjectName.isBlank()) {
                                        newProjectName = chooser.selectedFile.name
                                    }
                                }
                            }
                        ) {
                            Icon(Icons.Default.Search, contentDescription = "Browse")
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newProjectName.isNotBlank() && newProjectPath.isNotBlank()) {
                            val project = Project(
                                id = System.currentTimeMillis().toString(),
                                name = newProjectName,
                                path = newProjectPath
                            )
                            projectRepository.addProject(project)
                            projects = projectRepository.getAllProjects()
                            activeProject = project
                            onProjectSelected(project)
                            newProjectName = ""
                            newProjectPath = ""
                            showAddDialog = false
                        }
                    },
                    enabled = newProjectName.isNotBlank() && newProjectPath.isNotBlank()
                ) {
                    Text("Add")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Delete Confirmation Dialog
    showDeleteConfirm?.let { project ->
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = null },
            title = { Text("Remove Project") },
            text = { Text("Are you sure you want to remove '${project.name}' from the list?") },
            confirmButton = {
                Button(
                    onClick = {
                        projectRepository.removeProject(project.id)
                        projects = projectRepository.getAllProjects()
                        activeProject = projectRepository.getActiveProject()
                        showDeleteConfirm = null
                    },
                    colors = ButtonDefaults.buttonColors(backgroundColor = MaterialTheme.colors.error)
                ) {
                    Text("Remove")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun ProjectListItem(
    project: Project,
    isActive: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit
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
            onClick = onDelete,
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
