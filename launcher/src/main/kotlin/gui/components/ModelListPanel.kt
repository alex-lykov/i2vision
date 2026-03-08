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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

@Composable
fun ModelListPanel(
    models: List<ModelInfo>,
    currentModelId: String?,
    onModelSelected: (ModelInfo) -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    var ollamaModels by remember { mutableStateOf<List<OllamaModel>>(emptyList()) }
    var isLoadingOllama by remember { mutableStateOf(false) }
    var selectedModel by remember { mutableStateOf<ModelInfo?>(null) }
    var showDetails by remember { mutableStateOf(false) }

    // Load Ollama models from API
    LaunchedEffect(Unit) {
        while (true) {
            scope.launch {
                isLoadingOllama = true
                ollamaModels = getOllamaModels()
                isLoadingOllama = false
            }
            delay(5000) // Refresh every 5 seconds
        }
    }

    RightPanelCardWithCount(
        title = "Available Models",
        count = models.size + ollamaModels.size,
        modifier = modifier
    ) {
        currentModelId?.let { current ->
            Text(
                text = "Active: ${current.substringAfter(":")}",
                style = MaterialTheme.typography.caption,
                color = MaterialTheme.colors.primary
            )
        }

        Spacer(Modifier.height(8.dp))

        if (models.isEmpty() && ollamaModels.isEmpty()) {
            Text(
                text = if (isLoadingOllama) "Loading Ollama models..." else "No models found. Check Ollama service.",
                style = MaterialTheme.typography.body2,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
            )
        } else {
            LazyColumn(
                modifier = Modifier.heightIn(max = 250.dp)
            ) {
                // Local models
                if (models.isNotEmpty()) {
                    item {
                        Text(
                            text = "Local Models",
                            style = MaterialTheme.typography.caption,
                            color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }
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

                // Ollama models
                if (ollamaModels.isNotEmpty()) {
                    if (models.isNotEmpty()) {
                        item {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "Ollama Models",
                                style = MaterialTheme.typography.caption,
                                color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                        }
                    }
                    items(ollamaModels) { ollamaModel ->
                        OllamaModelListItem(
                            ollamaModel = ollamaModel,
                            isCurrent = ollamaModel.name == currentModelId,
                            isSelected = false,
                            onSelect = { /* TODO: Handle Ollama model selection */ },
                            onActivate = { /* TODO: Handle Ollama model activation */ }
                        )
                    }
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

@Composable
internal fun OllamaModelListItem(
    ollamaModel: OllamaModel,
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
                text = ollamaModel.name,
                style = MaterialTheme.typography.body1,
                color = if (isCurrent) MaterialTheme.colors.primary else MaterialTheme.colors.onSurface
            )
            Text(
                text = "${ollamaModel.size} • ${ollamaModel.modified}",
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

private suspend fun getOllamaModels(): List<OllamaModel> = withContext(Dispatchers.IO) {
    try {
        val client = HttpClient.newHttpClient()
        val request = HttpRequest.newBuilder()
            .uri(java.net.URI.create("http://localhost:11434/api/tags"))
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        
        if (response.statusCode() == 200) {
            parseModelsResponse(response.body())
        } else {
            emptyList()
        }
    } catch (e: Exception) {
        emptyList()
    }
}

private fun parseModelsResponse(response: String): List<OllamaModel> {
    return try {
        val json = Json { ignoreUnknownKeys = true }
        val jsonObject = json.decodeFromString<kotlinx.serialization.json.JsonObject>(response)
        val modelsArray = jsonObject["models"]?.toString()?.let { 
            json.decodeFromString<kotlinx.serialization.json.JsonArray>(it)
        } ?: return emptyList()
        
        modelsArray.mapNotNull { modelElement ->
            val modelStr = modelElement.toString()
            try {
                val modelObj = json.decodeFromString<kotlinx.serialization.json.JsonObject>(modelStr)
                val name = modelObj["name"]?.toString()?.removeSurrounding("\"") ?: return@mapNotNull null
                val size = modelObj["size"]?.toString()?.removeSurrounding("\"") ?: "Unknown"
                val modified = modelObj["modified"]?.toString()?.removeSurrounding("\"") ?: "Unknown"
                val digest = modelObj["digest"]?.toString()?.removeSurrounding("\"")
                
                OllamaModel(name, size, modified, digest)
            } catch (e: Exception) {
                null
            }
        }
    } catch (e: Exception) {
        emptyList()
    }
}

@Composable
internal fun ModelListItem(
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
internal fun ModelDetailsDialog(
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
fun DetailRow(label: String, value: String) {
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

