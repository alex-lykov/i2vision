package gui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.alyk.ai.koog.models.wrappers.ModelInfo

@Composable
fun ModelListPanel(
    models: List<ModelInfo>,
    currentModelId: String?,
    onModelSelected: (ModelInfo) -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedModel by remember { mutableStateOf<ModelInfo?>(null) }
    var showDetails by remember { mutableStateOf(false) }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(8.dp),
        elevation = 4.dp
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Available Models (${models.size})",
                    style = MaterialTheme.typography.h6
                )
                
                currentModelId?.let { current ->
                    Text(
                        text = "Active: ${current.substringAfter(":")}",
                        style = MaterialTheme.typography.caption,
                        color = MaterialTheme.colors.primary
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            if (models.isEmpty()) {
                Text(
                    text = "No models found. Check Ollama models directory.",
                    style = MaterialTheme.typography.body2,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                )
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 250.dp)
                ) {
                    items(models) { model ->
                        ModelListItem(
                            model = model,
                            isCurrent = model.id == currentModelId,
                            isSelected = model == selectedModel,
                            onSelect = {
                                selectedModel = model
                                showDetails = true
                            },
                            onActivate = { onModelSelected(model) }
                        )
                    }
                }
            }

            // Model Details Dialog
            if (showDetails && selectedModel != null) {
                ModelDetailsDialog(
                    model = selectedModel!!,
                    isCurrent = selectedModel!!.id == currentModelId,
                    onDismiss = { showDetails = false },
                    onActivate = {
                        onModelSelected(selectedModel!!)
                        showDetails = false
                    }
                )
            }
        }
    }
}

@Composable
private fun ModelListItem(
    model: ModelInfo,
    isCurrent: Boolean,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onActivate: () -> Unit
) {
    val backgroundColor = when {
        isCurrent -> MaterialTheme.colors.primary.copy(alpha = 0.15f)
        isSelected -> MaterialTheme.colors.primary.copy(alpha = 0.05f)
        else -> MaterialTheme.colors.surface
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(backgroundColor)
            .clickable(onClick = onSelect)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Status icon
        Icon(
            imageVector = if (isCurrent) Icons.Default.CheckCircle else Icons.Default.Settings,
            contentDescription = null,
            tint = if (isCurrent) MaterialTheme.colors.primary else MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.size(20.dp)
        )

        Spacer(Modifier.width(12.dp))

        // Model info
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = model.id,
                style = MaterialTheme.typography.body1,
                color = if (isCurrent) MaterialTheme.colors.primary else MaterialTheme.colors.onSurface
            )
            Text(
                text = "${model.formattedSize} • ${model.contextLength} ctx • ${model.layers} layers",
                style = MaterialTheme.typography.caption,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
            )
        }

        // Activate button
        if (!isCurrent) {
            TextButton(
                onClick = onActivate,
                modifier = Modifier.height(32.dp)
            ) {
                Text("ACTIVATE")
            }
        }
    }
}

@Composable
private fun ModelDetailsDialog(
    model: ModelInfo,
    isCurrent: Boolean,
    onDismiss: () -> Unit,
    onActivate: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Model Details") },
        text = {
            Column {
                DetailRow("ID", model.id)
                DetailRow("Name", model.name)
                DetailRow("Tag", model.tag)
                DetailRow("Size", model.formattedSize)
                DetailRow("Context Length", "${model.contextLength} tokens")
                DetailRow("Digest", model.digest.take(16) + "...")
                DetailRow("Layers", model.layers.toString())
                DetailRow("Path", model.path)
                
                if (model.error != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Error: ${model.error}",
                        color = MaterialTheme.colors.error,
                        style = MaterialTheme.typography.caption
                    )
                }
                
                if (isCurrent) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "✓ Currently Active",
                        color = MaterialTheme.colors.primary,
                        style = MaterialTheme.typography.body2
                    )
                }
            }
        },
        confirmButton = {
            if (!isCurrent) {
                Button(onClick = onActivate) {
                    Text("Activate Model")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.caption,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.caption,
            color = MaterialTheme.colors.onSurface
        )
    }
}
