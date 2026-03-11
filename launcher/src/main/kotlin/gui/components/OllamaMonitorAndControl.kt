package gui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import core.UnifiedModelManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// Forward declarations for service management functions
private suspend fun startOllamaService() = withContext(Dispatchers.IO) {
    try {
        // For Windows - use PowerShell to start Ollama service
        val process = ProcessBuilder("powershell", "-Command", "Start-Service ollama").start()
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            // Try alternative method - run ollama directly
            ProcessBuilder("ollama", "serve").start()
        }
    } catch (e: Exception) {
        // Fallback for different systems or manual installation
        try {
            ProcessBuilder("ollama", "serve").start()
        } catch (ex: Exception) {
            ex.printStackTrace()
        }
    }
}

private suspend fun stopOllamaService() = withContext(Dispatchers.IO) {
    try {
        // For Windows - use PowerShell to stop Ollama service
        val process = ProcessBuilder("powershell", "-Command", "Stop-Service ollama").start()
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            // Alternative: kill ollama processes
            val killProcess = ProcessBuilder("taskkill", "/F", "/IM", "ollama.exe").start()
            killProcess.waitFor()
        }
    } catch (e: Exception) {
        // Fallback for different systems
        try {
            val killProcess = ProcessBuilder("pkill", "-f", "ollama").start()
            killProcess.waitFor()
        } catch (ex: Exception) {
            ex.printStackTrace()
        }
    }
}

@Composable
fun OllamaMonitorAndControl(
    unifiedModelManager: UnifiedModelManager,
    //modifier: UnifiedModelManager = Modifier
) {
    val scope = rememberCoroutineScope()
    var availableModels by remember { mutableStateOf<List<OllamaModel>>(emptyList()) }
    var runningModels by remember { mutableStateOf<List<OllamaModel>>(emptyList()) }
    var ollamaLogs by remember { mutableStateOf<List<OllamaLogEntry>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var selectedModelName by remember { mutableStateOf("") }
    var showPullDialog by remember { mutableStateOf(false) }
    var pullModelName by remember { mutableStateOf("") }
    var pullProgress by remember { mutableStateOf("") }
    var isPulling by remember { mutableStateOf(false) }
    var isConnected by remember { mutableStateOf(false) }
    var logsExpanded by remember { mutableStateOf(false) }
    var libraryModels by remember { mutableStateOf<List<LibraryModel>>(emptyList()) }
    var isLoadingLibrary by remember { mutableStateOf(false) }
    var pullProgressStates by remember { mutableStateOf<List<PullProgress>>(emptyList()) }
    var isServiceStarting by remember { mutableStateOf(false) }
    var isServiceStopping by remember { mutableStateOf(false) }
    var unifiedModels by remember { mutableStateOf<List<UnifiedModel>>(emptyList()) }
    var cloudModels by remember { mutableStateOf<List<LibraryModel>>(emptyList()) }

    // State for tracking cloud models that have been started (must remember so updates survive recomposition)
    val runningCloudModels = remember { mutableStateOf<Set<String>>(emptySet()) }

    // Trigger recomposition when pullProgress changes
    LaunchedEffect(pullProgress) {
        if (pullProgress.isNotBlank()) {
            println("[DEBUG] LaunchedEffect: pullProgress changed to: $pullProgress")
        }
    }

    // Trigger recomposition when isPulling changes
    LaunchedEffect(isPulling) {
        println("[DEBUG] LaunchedEffect: isPulling changed to: $isPulling")
    }
    val listState = rememberLazyListState()
    val logListState = rememberLazyListState()
    val quickStartFocusRequester = remember { FocusRequester() }
    var quickStartExpanded by remember { mutableStateOf(false) }

    // Load cloud and library (registry) models at boot so both appear in the list for pick/pull
    LaunchedEffect(Unit) {
        try {
            println("[LIBRARY] Loading cloud models using UnifiedModelManager...")
            val cloudModelInfos = unifiedModelManager.getCloudModels()
            cloudModels = cloudModelInfos.map { modelInfo ->
                LibraryModel(
                    name = modelInfo.name,
                    description = modelInfo.id,
                    tags = listOf("cloud", modelInfo.tag),
                    size = modelInfo.formattedSize
                )
            }
            println("[LIBRARY] Loaded ${cloudModels.size} cloud models")
        } catch (e: Exception) {
            println("[LIBRARY] Failed to load cloud models: ${e.message}")
            cloudModels = emptyList()
        }
        try {
            println("[LIBRARY] Loading library (registry) models for pick/pull...")
            libraryModels = loadLibraryModels()
            println("[LIBRARY] Loaded ${libraryModels.size} library models")
        } catch (e: Exception) {
            println("[LIBRARY] Failed to load library models: ${e.message}")
            libraryModels = emptyList()
        }
    }

    // Update data every 3 seconds
    LaunchedEffect(Unit) {
        while (true) {
            updateAllData { available, running, logs, connected ->
                availableModels = available
                runningModels = running
                ollamaLogs = (ollamaLogs + logs).takeLast(50)
                isConnected = connected
            }
            delay(3000)
        }
    }

    // Auto-scroll logs to bottom when expanded
    LaunchedEffect(ollamaLogs.size, logsExpanded) {
        if (logsExpanded && ollamaLogs.isNotEmpty()) {
            logListState.animateScrollToItem(ollamaLogs.size - 1)
        }
    }

    // Update unified models when available, library, cloud, and running models change.
    // Use stable key for runningCloudModels (string) to avoid LaunchedEffect restart loops from Set reference churn.
    val runningCloudKey = runningCloudModels.value.joinToString(",")
    LaunchedEffect(availableModels, libraryModels, cloudModels, runningModels, runningCloudKey) {
        val runningModelNames = runningModels.map { it.name }.toSet()
        val availableModelNames = availableModels.map { it.name }.toSet()
        val runningCloudModelNames = runningCloudModels.value

        val combined = mutableListOf<UnifiedModel>()

        // Add available/running models (but exclude cloud-named models that should show cloud UI)
        availableModels.forEach { model ->
            // Skip models that have cloud naming patterns - they'll be handled by cloud logic
            val isCloudNamed = model.name.contains("-cloud", ignoreCase = true) ||
                             model.name.contains("cloud", ignoreCase = true) ||
                             model.name.startsWith("generic:", ignoreCase = true) ||
                             model.name.startsWith("hugging_face:", ignoreCase = true)
            
            if (!isCloudNamed) {
                combined.add(
                    UnifiedModel(
                        name = model.name,
                        size = model.formattedSize,
                        status = if (runningModelNames.contains(model.name)) ModelStatus.RUNNING else ModelStatus.LOADED,
                        digest = model.digest
                    )
                )
            }
        }

        // Add cloud models (including cloud-named models that are locally available)
        cloudModels.forEach { cloudModel ->
            // Handle name matching with tags - check if available models contain the cloud model name (with or without tag)
            val isLocallyAvailable = availableModelNames.any { availableName ->
                // Check exact match first
                availableName == cloudModel.name ||
                // Check if available name starts with cloud model name (handles tag differences)
                availableName.startsWith("${cloudModel.name}:") ||
                // Check if cloud model name starts with available name (reverse check)
                cloudModel.name.startsWith("${availableName.split(":").first()}:")
            }
            val isRunning = runningModelNames.any { runningName ->
                runningName == cloudModel.name ||
                runningName.startsWith("${cloudModel.name}:") ||
                cloudModel.name.startsWith("${runningName.split(":").first()}:")
            } || runningCloudModelNames.contains(cloudModel.name) || 
               runningCloudModelNames.any { runningCloud ->
                   runningCloud.startsWith("${cloudModel.name}:") ||
                   cloudModel.name.startsWith("${runningCloud.split(":").first()}:")
               }
            
            combined.add(
                UnifiedModel(
                    name = cloudModel.name,
                    description = cloudModel.description,
                    tags = cloudModel.tags,
                    size = cloudModel.size,
                    status = when {
                        isRunning -> ModelStatus.RUNNING
                        isLocallyAvailable -> ModelStatus.LOADED  // Available locally but show cloud UI
                        else -> ModelStatus.CLOUD  // True cloud-only model
                    },
                    libraryInfo = LibraryInfo(
                        description = cloudModel.description,
                        tags = cloudModel.tags,
                        size = cloudModel.size
                    )
                )
            )
        }

        // Add library models that aren't already available or in cloud
        libraryModels.forEach { libModel ->
            if (!availableModelNames.contains(libModel.name) && !cloudModels.any { it.name == libModel.name }) {
                combined.add(
                    UnifiedModel(
                        name = libModel.name,
                        description = libModel.description,
                        tags = libModel.tags,
                        size = libModel.size,
                        status = ModelStatus.LIBRARY,
                        libraryInfo = LibraryInfo(
                            description = libModel.description,
                            tags = libModel.tags,
                            size = libModel.size
                        )
                    )
                )
            }
        }

        unifiedModels = combined.sortedWith(compareBy<UnifiedModel> {
            when (it.status) {
                ModelStatus.RUNNING -> 0
                ModelStatus.LOADED -> 1
                ModelStatus.CLOUD -> 2
                ModelStatus.LIBRARY -> 3
            }
        }.thenBy { it.name })
    }

    RightPanelCard(
        title = "Ollama Control",
        //modifier = modifier
    ) {
        Column {
            // Connection Status and Control Buttons Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(
                                color = if (isConnected) Color.Green else Color.Red,
                                shape = MaterialTheme.shapes.small
                            )
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (isConnected) "Connected" else "Disconnected",
                        style = MaterialTheme.typography.caption,
                        color = if (isConnected) Color.Green else Color.Red
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Service Start/Stop buttons
                    if (!isConnected) {
                        TextButton(
                            onClick = {
                                isServiceStarting = true
                                scope.launch {
                                    startOllamaService()
                                    delay(2000) // Give service time to start
                                    updateAllData { available, running, logs, connected ->
                                        availableModels = available
                                        runningModels = running
                                        ollamaLogs = (ollamaLogs + logs).takeLast(50)
                                        isConnected = connected
                                    }
                                    isServiceStarting = false
                                }
                            },
                            enabled = !isServiceStarting,
                            modifier = Modifier.height(32.dp).defaultMinSize(minWidth = 60.dp)
                        ) {
                            if (isServiceStarting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(12.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Text(
                                    "Start Service",
                                    style = MaterialTheme.typography.caption,
                                    color = Color(0xFF4CAF50)
                                )
                            }
                        }
                    } else {
                        TextButton(
                            onClick = {
                                isServiceStopping = true
                                scope.launch {
                                    stopOllamaService()
                                    delay(1000) // Give service time to stop
                                    updateAllData { available, running, logs, connected ->
                                        availableModels = available
                                        runningModels = running
                                        ollamaLogs = (ollamaLogs + logs).takeLast(50)
                                        isConnected = connected
                                    }
                                    isServiceStopping = false
                                }
                            },
                            enabled = !isServiceStopping,
                            modifier = Modifier.height(32.dp).defaultMinSize(minWidth = 70.dp)
                        ) {
                            if (isServiceStopping) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(12.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Text(
                                    "Stop Service",
                                    style = MaterialTheme.typography.caption,
                                    color = Color(0xFFFF5252)
                                )
                            }
                        }
                    }

                    TextButton(
                        onClick = {
                            isLoading = true
                            scope.launch {
                                updateAllData { available, running, logs, connected ->
                                    availableModels = available
                                    runningModels = running
                                    ollamaLogs = (ollamaLogs + logs).takeLast(50)
                                    isConnected = connected
                                }
                                isLoading = false
                            }
                        },
                        enabled = !isLoading,
                        modifier = Modifier.height(32.dp).defaultMinSize(minWidth = 60.dp)
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(12.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text("Refresh", style = MaterialTheme.typography.caption)
                        }
                    }

                    TextButton(
                        onClick = { showPullDialog = true },
                        modifier = Modifier.height(32.dp).defaultMinSize(minWidth = 80.dp)
                    ) {
                        Text("Find Model", style = MaterialTheme.typography.caption)
                    }

                    TextButton(
                        onClick = {
                            isLoadingLibrary = true
                            scope.launch {
                                try {
                                    println("[LIBRARY] Refreshing cloud models...")
                                    val refreshResult = unifiedModelManager.scanAllModels()
                                    if (refreshResult.isNotEmpty()) {
                                        val cloudModelInfos = unifiedModelManager.getCloudModels()
                                        cloudModels = cloudModelInfos.map { modelInfo ->
                                            LibraryModel(
                                                name = modelInfo.name,
                                                description = "Cloud model: ${modelInfo.id}",
                                                tags = listOf("cloud", modelInfo.tag),
                                                size = modelInfo.formattedSize
                                            )
                                        }
                                        println("[LIBRARY] Refreshed ${cloudModels.size} cloud models")
                                    } else {
                                        cloudModels = emptyList()
                                    }
                                    println("[LIBRARY] Updating library repo from URL...")
                                    val repoModels = LibraryModelRepository.updateFromUrl()
                                    libraryModels = repoModels.sortedBy { m ->
                                        when {
                                            m.name.contains("7b") -> 7
                                            m.name.contains("13b") -> 13
                                            m.name.contains("70b") -> 70
                                            else -> 7
                                        }
                                    }
                                    println("[LIBRARY] Library repo: ${libraryModels.size} models")
                                } catch (e: Exception) {
                                    println("[LIBRARY] Refresh failed: ${e.message}")
                                } finally {
                                    isLoadingLibrary = false
                                }
                            }
                        },
                        enabled = !isLoadingLibrary,
                        modifier = Modifier.height(32.dp).defaultMinSize(minWidth = 100.dp)
                    ) {
                        Text("Update from URL", style = MaterialTheme.typography.caption)
                    }

                    TextButton(
                        onClick = {
                            scope.launch { stopAllModels() }
                        },
                        modifier = Modifier.height(32.dp).defaultMinSize(minWidth = 60.dp)
                    ) {
                        Text("Stop All", style = MaterialTheme.typography.caption, color = Color(0xFFFF5252))
                    }

                    // Debug button for testing
                    TextButton(
                        onClick = {
                            scope.launch {
                                val testLog = OllamaLogEntry(
                                    timestamp = java.time.Instant.now(),
                                    level = LogLevel.DEBUG,
                                    message = "Debug: Testing pull functionality - Ollama API reachable: $isConnected"
                                )
                                ollamaLogs = (ollamaLogs + testLog).takeLast(50)
                            }
                        },
                        modifier = Modifier.height(32.dp).defaultMinSize(minWidth = 50.dp)
                    ) {
                        Text("Debug", style = MaterialTheme.typography.caption, color = Color(0xFF9C27B0))
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // Quick Start Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Quick Start:",
                    style = MaterialTheme.typography.caption,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier.width(70.dp)
                )

                // Simple autocomplete for Quick Start
                Column(modifier = Modifier.weight(1f)) {
                    TextField(
                        value = selectedModelName,
                        onValueChange = {
                            selectedModelName = it
                            quickStartExpanded = selectedModelName.isNotEmpty()
                        },
                        placeholder = { Text("model:name") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.body2,
                        trailingIcon = {
                            Icon(
                                imageVector = if (quickStartExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                contentDescription = "Dropdown",
                                Modifier.clickable { quickStartExpanded = !quickStartExpanded }
                            )
                        },
                        colors = TextFieldDefaults.textFieldColors(
                            unfocusedIndicatorColor = MaterialTheme.colors.onSurface.copy(alpha = 0.3f),
                            focusedIndicatorColor = MaterialTheme.colors.primary
                        )
                    )

                    if (quickStartExpanded && selectedModelName.isNotEmpty()) {
                        val filteredModels = unifiedModels.filter {
                            it.name.contains(selectedModelName, ignoreCase = true)
                        }

                        if (filteredModels.isNotEmpty()) {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                elevation = 4.dp
                            ) {
                                LazyColumn(
                                    modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp)
                                ) {
                                    items(filteredModels.take(5)) { model ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    selectedModelName = model.name
                                                    quickStartExpanded = false
                                                }
                                                .padding(horizontal = 16.dp, vertical = 12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = model.name,
                                                style = MaterialTheme.typography.body2
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.width(6.dp))

                TextButton(
                    onClick = {
                        if (selectedModelName.isNotBlank()) {
                            scope.launch { startModel(selectedModelName, runningCloudModels) }
                        }
                    },
                    enabled = selectedModelName.isNotBlank(),
                    modifier = Modifier.height(32.dp).defaultMinSize(minWidth = 50.dp)
                ) {
                    Text("Start", style = MaterialTheme.typography.caption)
                }
            }

            Spacer(Modifier.height(8.dp))

            // Model Lists
            // Single Models tab - no need for separate Running tab since we have status indicators
            Spacer(Modifier.height(6.dp))

            UnifiedModelsList(unifiedModels, listState, scope, pullProgressStates, availableModels, runningCloudModels, { modelName ->
                scope.launch {
                    pullModel(modelName) { progress ->
                        pullProgress = progress
                    }
                }
            }) { newStates ->
                pullProgressStates = newStates
            }

            Spacer(Modifier.height(8.dp))

            // Expandable Activity Logs Section
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = 2.dp
            ) {
                Column {
                    // Logs Header with Expand/Collapse
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { logsExpanded = !logsExpanded }
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "📋 Activity Logs",
                                style = MaterialTheme.typography.subtitle2,
                                color = MaterialTheme.colors.onSurface
                            )
                            if (ollamaLogs.isNotEmpty()) {
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = "(${ollamaLogs.size})",
                                    style = MaterialTheme.typography.caption,
                                    color = MaterialTheme.colors.primary
                                )
                                // Show error count
                                val errorCount = ollamaLogs.count { it.level == LogLevel.ERROR }
                                if (errorCount > 0) {
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        text = "⚠️ $errorCount",
                                        style = MaterialTheme.typography.caption,
                                        color = Color(0xFFF44336)
                                    )
                                }
                            }
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(
                                onClick = {
                                    scope.launch {
                                        ollamaLogs = emptyList()
                                        // Add clear log entry
                                        val clearLog = OllamaLogEntry(
                                            timestamp = java.time.Instant.now(),
                                            level = LogLevel.INFO,
                                            message = "Activity logs cleared"
                                        )
                                        ollamaLogs = listOf(clearLog)
                                    }
                                },
                                modifier = Modifier.height(32.dp).defaultMinSize(minWidth = 40.dp)
                            ) {
                                Text("Clear", style = MaterialTheme.typography.caption)
                            }
                            Icon(
                                imageVector = if (logsExpanded)
                                    Icons.Default.KeyboardArrowUp
                                else
                                    Icons.Default.KeyboardArrowDown,
                                contentDescription = if (logsExpanded) "Collapse" else "Expand",
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    // Expandable Logs Content
                    if (logsExpanded) {
                        Divider()

                        if (ollamaLogs.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(200.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        text = if (isConnected) "🔄 Waiting for Ollama activity..." else "❌ No connection to Ollama",
                                        style = MaterialTheme.typography.body2,
                                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                                    )
                                    if (!isConnected) {
                                        Spacer(Modifier.height(8.dp))
                                        Text(
                                            text = "Click 'Start Service' to begin",
                                            style = MaterialTheme.typography.caption,
                                            color = MaterialTheme.colors.primary
                                        )
                                    }
                                }
                            }
                        } else {
                            LazyColumn(
                                state = logListState,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(200.dp)
                                    .padding(horizontal = 8.dp)
                            ) {
                                items(ollamaLogs.reversed()) { log -> // Show newest first
                                    LogEntryItem(log)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Pull Model Dialog
    if (showPullDialog) {
        println("[DEBUG] DIALOG: Rendering PullDialog, isPulling=$isPulling, pullModelName='$pullModelName'")
        AlertDialog(
            onDismissRequest = { showPullDialog = false },
            title = { Text("Pull Model") },
            text = {
                Column {
                    Text("Enter model name to pull (will be validated against library registry):")
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = pullModelName,
                        onValueChange = { pullModelName = it },
                        placeholder = { Text("e.g., llama2:7b, mistral:7b") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    // Test button to check if clicks work
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            println("[DEBUG] *** TEST BUTTON CLICKED ***")
                        }
                    ) {
                        Text("TEST BUTTON")
                    }

                    // Always show current progress if we're pulling
                    if (isPulling) {
                        println("[DEBUG] DIALOG RENDER: isPulling=true, pullProgress='$pullProgress'")
                        Spacer(Modifier.height(8.dp))

                        println("[DEBUG] DIALOG RENDER: Showing pullProgress = '$pullProgress'")
                        Text(
                            text = pullProgress.ifBlank { "Connecting..." },
                            style = MaterialTheme.typography.body2,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colors.primary,
                            maxLines = 3
                        )

                        // Enhanced progress display
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            elevation = 2.dp,
                            backgroundColor = MaterialTheme.colors.surface.copy(alpha = 0.1f)
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp)
                            ) {
                                Text(
                                    text = "Progress:",
                                    style = MaterialTheme.typography.caption,
                                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f)
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = pullProgress,
                                    style = MaterialTheme.typography.body2,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colors.primary,
                                    maxLines = 3
                                )

                                // Show active downloads if any
                                val activeOperations = pullProgressStates.filter {
                                    it.status == PullStatus.DOWNLOADING ||
                                            it.status == PullStatus.CONNECTING ||
                                            it.status == PullStatus.PULLING_MANIFEST ||
                                            it.status == PullStatus.VERIFYING ||
                                            it.status == PullStatus.STARTING ||
                                            it.status == PullStatus.STOPPING
                                }

                                if (activeOperations.isNotEmpty()) {
                                    Spacer(Modifier.height(8.dp))
                                    activeOperations.forEach { progress ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = progress.modelName,
                                                style = MaterialTheme.typography.caption,
                                                modifier = Modifier.weight(1f)
                                            )
                                            when (progress.status) {
                                                PullStatus.DOWNLOADING -> {
                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                        CircularProgressIndicator(
                                                            progress = progress.progressPercent / 100f,
                                                            modifier = Modifier.size(16.dp),
                                                            strokeWidth = 2.dp
                                                        )
                                                        Spacer(Modifier.width(4.dp))
                                                        Text(
                                                            text = "${progress.progressPercent}%",
                                                            style = MaterialTheme.typography.caption
                                                        )
                                                    }
                                                }

                                                PullStatus.CONNECTING -> {
                                                    CircularProgressIndicator(
                                                        modifier = Modifier.size(16.dp),
                                                        strokeWidth = 2.dp
                                                    )
                                                }

                                                PullStatus.PULLING_MANIFEST -> {
                                                    Text(
                                                        text = "Manifest...",
                                                        style = MaterialTheme.typography.caption,
                                                        color = Color(0xFF9C27B0)
                                                    )
                                                }

                                                PullStatus.VERIFYING -> {
                                                    Text(
                                                        text = "Verify...",
                                                        style = MaterialTheme.typography.caption,
                                                        color = Color(0xFFFF9800)
                                                    )
                                                }

                                                PullStatus.STARTING -> {
                                                    Text(
                                                        text = "Starting...",
                                                        style = MaterialTheme.typography.caption,
                                                        color = Color(0xFF4CAF50)
                                                    )
                                                }

                                                PullStatus.STOPPING -> {
                                                    Text(
                                                        text = "Stopping...",
                                                        style = MaterialTheme.typography.caption,
                                                        color = Color(0xFFFF5252)
                                                    )
                                                }

                                                else -> {}
                                            }
                                        }
                                        Spacer(Modifier.height(2.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        println("[DEBUG] *** BUTTON CLICK DETECTED ***")
                        println("[DEBUG] BUTTON CLICK: isPulling=$isPulling, pullModelName='$pullModelName'")
                        if (pullModelName.isNotBlank()) {
                            isPulling = true
                            println("[DEBUG] BUTTON: Set isPulling=true")
                            scope.launch {
                                // Add activity log for pull start
                                val logEntry = OllamaLogEntry(
                                    timestamp = java.time.Instant.now(),
                                    level = LogLevel.INFO,
                                    message = "Starting pull for model: $pullModelName",
                                    model = pullModelName
                                )
                                ollamaLogs = (ollamaLogs + logEntry).takeLast(50)

                                // Add to progress states
                                val initialProgress = PullProgress(
                                    modelName = pullModelName,
                                    operationType = OperationType.PULL,
                                    status = PullStatus.CONNECTING,
                                    message = "Starting pull..."
                                )
                                pullProgressStates = (pullProgressStates + initialProgress).takeLast(10)

                                pullModel(pullModelName) { progress ->
                                    println("[DEBUG] UI STATE: Updating pullProgress to: $progress")
                                    pullProgress = progress

                                    // Update progress states with detailed info
                                    val updatedProgress =
                                        pullProgressStates.find { it.modelName == pullModelName && it.operationType == OperationType.PULL }
                                            ?.copy(message = progress, timestamp = System.currentTimeMillis())
                                    if (updatedProgress != null) {
                                        pullProgressStates = pullProgressStates.map {
                                            if (it.modelName == pullModelName && it.operationType == OperationType.PULL) updatedProgress else it
                                        }
                                    } else {
                                        // Create new progress entry if not found
                                        val newProgress = PullProgress(
                                            modelName = pullModelName,
                                            operationType = OperationType.PULL,
                                            status = when {
                                                progress.contains("Connecting") -> PullStatus.CONNECTING
                                                progress.contains("manifest") -> PullStatus.PULLING_MANIFEST
                                                progress.contains("Downloading") -> PullStatus.DOWNLOADING
                                                progress.contains("Verifying") -> PullStatus.VERIFYING
                                                progress.contains("Successfully") -> PullStatus.COMPLETED
                                                progress.contains("Error") -> PullStatus.ERROR
                                                else -> PullStatus.CONNECTING
                                            },
                                            message = progress,
                                            timestamp = System.currentTimeMillis()
                                        )
                                        pullProgressStates = (pullProgressStates + newProgress).takeLast(10)
                                    }

                                    // Add progress logs
                                    val progressLog = OllamaLogEntry(
                                        timestamp = java.time.Instant.now(),
                                        level = LogLevel.INFO,
                                        message = progress,
                                        model = pullModelName
                                    )
                                    ollamaLogs = (ollamaLogs + progressLog).takeLast(50)
                                }
                                println("[DEBUG] Pull completed, showing completion for 2 seconds before closing")

                                // Show completion for 2 seconds before closing dialog
                                kotlinx.coroutines.delay(2000)

                                isPulling = false

                                // Remove from progress states on completion
                                pullProgressStates = pullProgressStates.filter {
                                    !(it.modelName == pullModelName && it.operationType == OperationType.PULL)
                                }

                                // Close dialog only after completion
                                showPullDialog = false
                                pullModelName = ""
                                pullProgress = ""
                            }
                        }
                    },
                    enabled = pullModelName.isNotBlank() && !isPulling
                ) {
                    println("[DEBUG] BUTTON RENDER: isPulling=$isPulling, enabled=${pullModelName.isNotBlank() && !isPulling}")
                    if (isPulling) {
                        println("[DEBUG] BUTTON: Showing loading spinner")
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        println("[DEBUG] BUTTON: Showing 'PULL MODEL' text")
                        Text("PULL MODEL")
                    }
                }
            },
            dismissButton = {
                println("[DEBUG] DIALOG: Rendering dismissButton")
                TextButton(
                    onClick = {
                        if (isPulling) {
                            // TODO: Implement cancellation logic
                            isPulling = false
                            showPullDialog = false
                            pullModelName = ""
                            pullProgress = ""
                        } else {
                            showPullDialog = false
                            pullModelName = ""
                            pullProgress = ""
                        }
                    }
                ) {
                    Text(if (isPulling) "Cancel" else "Close")
                }
            }
        )
    }
}


@Composable
private fun UnifiedModelsList(
    models: List<UnifiedModel>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    scope: kotlinx.coroutines.CoroutineScope,
    pullProgressStates: List<PullProgress>,
    availableModels: List<OllamaModel>,  // Add availableModels parameter
    runningCloudModels: MutableState<Set<String>>,  // Add runningCloudModels parameter
    onPullModel: (String) -> Unit,
    onUpdateProgressStates: (List<PullProgress>) -> Unit
) {
    if (models.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "No models found",
                style = MaterialTheme.typography.body2,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
            )
        }
    } else {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
        ) {
            items(models, key = { it.name + it.status.toString() }) { model ->
                UnifiedModelItem(
                    model = model,
                    pullProgress = pullProgressStates.find { it.modelName == model.name },
                    scope = scope,
                    onStart = {
                        scope.launch {
                            // Add start progress state
                            var startProgress = PullProgress(
                                modelName = model.name,
                                operationType = OperationType.START,
                                status = PullStatus.STARTING,
                                progressPercent = 0,
                                message = "Starting model..."
                            )
                            onUpdateProgressStates((pullProgressStates + startProgress).takeLast(10))

                            try {
                                // Find the actual model name from available models (handles tag differences)
                                val actualModelName = availableModels.find { available ->
                                    available.name == model.name ||
                                    available.name.startsWith("${model.name}:") ||
                                    model.name.startsWith("${available.name.split(":").first()}:")
                                }?.name ?: model.name
                                
                                println("[DEBUG] Starting model: display='${model.name}', actual='$actualModelName'")
                                startModel(actualModelName, runningCloudModels)

                                // Update progress to completed
                                val completedProgress = startProgress.copy(
                                    status = PullStatus.COMPLETED,
                                    message = "Model started successfully"
                                )
                                onUpdateProgressStates(pullProgressStates.map {
                                    if (it.modelName == model.name && it.operationType == OperationType.START) completedProgress
                                    else it
                                })

                                // Trigger immediate UI refresh
                                updateAllData { available, running, logs, connected ->
                                    // Just trigger the refresh - existing LaunchedEffect will handle state updates
                                }

                                // Remove progress state after delay
                                kotlinx.coroutines.delay(2000)
                                onUpdateProgressStates(pullProgressStates.filter {
                                    !(it.modelName == model.name && it.operationType == OperationType.START)
                                })

                            } catch (e: Exception) {
                                // Update progress to error
                                val errorProgress = startProgress.copy(
                                    status = PullStatus.ERROR,
                                    message = "Failed to start model"
                                )
                                onUpdateProgressStates(pullProgressStates.map {
                                    if (it.modelName == model.name && it.operationType == OperationType.START) errorProgress
                                    else it
                                })

                                // Remove error state after delay
                                kotlinx.coroutines.delay(3000)
                                onUpdateProgressStates(pullProgressStates.filter {
                                    !(it.modelName == model.name && it.operationType == OperationType.START)
                                })
                            }
                        }
                    },
                    onStop = {
                        scope.launch {
                            println("[${java.time.LocalDateTime.now()}] USER CLICKED STOP for model: ${model.name}")

                            // Add stop progress state
                            var stopProgress = PullProgress(
                                modelName = model.name,
                                operationType = OperationType.STOP,
                                status = PullStatus.STOPPING,
                                progressPercent = 0,
                                message = "Stopping model..."
                            )
                            onUpdateProgressStates((pullProgressStates + stopProgress).takeLast(10))

                            try {
                                stopModel(model.name, runningCloudModels)

                                // Update progress to completed
                                stopProgress = stopProgress.copy(
                                    status = PullStatus.COMPLETED,
                                    message = "Model stopped successfully"
                                )
                                onUpdateProgressStates(pullProgressStates.map {
                                    if (it.modelName == model.name && it.operationType == OperationType.STOP) stopProgress
                                    else it
                                })

                                // Trigger immediate UI refresh
                                updateAllData { available, running, logs, connected ->
                                    // Just trigger the refresh - existing LaunchedEffect will handle state updates
                                }

                                // Remove progress state after delay
                                kotlinx.coroutines.delay(2000)
                                onUpdateProgressStates(pullProgressStates.filter {
                                    !(it.modelName == model.name && it.operationType == OperationType.STOP)
                                })

                            } catch (e: Exception) {
                                // Update progress to error
                                stopProgress = stopProgress.copy(
                                    status = PullStatus.ERROR,
                                    message = "Failed to stop model"
                                )
                                onUpdateProgressStates(pullProgressStates.map {
                                    if (it.modelName == model.name && it.operationType == OperationType.STOP) stopProgress
                                    else it
                                })

                                // Remove error state after delay
                                kotlinx.coroutines.delay(3000)
                                onUpdateProgressStates(pullProgressStates.filter {
                                    !(it.modelName == model.name && it.operationType == OperationType.STOP)
                                })
                            }
                        }
                    },
                    onPull = { onPullModel(model.name) },
                    onRemove = {
                        scope.launch {
                            println("[${java.time.LocalDateTime.now()}] USER CLICKED REMOVE for model: ${model.name}")

                            // Find the actual model name from available models (handles tag differences)
                            val actualModelName = availableModels.find { available ->
                                available.name == model.name ||
                                available.name.startsWith("${model.name}:") ||
                                model.name.startsWith("${available.name.split(":").first()}:")
                            }?.name ?: model.name
                            
                            println("[DEBUG] Removing model: display='${model.name}', actual='$actualModelName'")

                            // Add remove progress state
                            var removeProgress = PullProgress(
                                modelName = model.name,
                                operationType = OperationType.REMOVE,
                                status = PullStatus.REMOVING,
                                progressPercent = 0,
                                message = "Removing model..."
                            )
                            onUpdateProgressStates((pullProgressStates + removeProgress).takeLast(10))

                            try {
                                removeModel(actualModelName)

                                // Update progress to completed
                                val completedProgress = removeProgress.copy(
                                    status = PullStatus.COMPLETED,
                                    message = "Model removed successfully"
                                )
                                onUpdateProgressStates(pullProgressStates.map {
                                    if (it.modelName == model.name && it.operationType == OperationType.REMOVE) completedProgress
                                    else it
                                })

                                // Trigger immediate UI refresh
                                updateAllData { available, running, logs, connected ->
                                    // Just trigger the refresh - existing LaunchedEffect will handle state updates
                                }

                                // Remove progress state after delay
                                kotlinx.coroutines.delay(2000)
                                onUpdateProgressStates(pullProgressStates.filter {
                                    !(it.modelName == model.name && it.operationType == OperationType.REMOVE)
                                })

                            } catch (e: Exception) {
                                // Update progress to error
                                val errorProgress = removeProgress.copy(
                                    status = PullStatus.ERROR,
                                    message = "Failed to remove model"
                                )
                                onUpdateProgressStates(pullProgressStates.map {
                                    if (it.modelName == model.name && it.operationType == OperationType.REMOVE) errorProgress
                                    else it
                                })

                                // Remove error state after delay
                                kotlinx.coroutines.delay(3000)
                                onUpdateProgressStates(pullProgressStates.filter {
                                    !(it.modelName == model.name && it.operationType == OperationType.REMOVE)
                                })
                            }
                        }
                    }
                )
            }
        }
    }
}


@Composable
private fun UnifiedModelItem(
    model: UnifiedModel,
    pullProgress: PullProgress?,
    scope: kotlinx.coroutines.CoroutineScope,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onPull: () -> Unit,
    onRemove: () -> Unit
) {
    var isStarting by remember { mutableStateOf(false) }
    var isStopping by remember { mutableStateOf(false) }
    var isRemoving by remember { mutableStateOf(false) }

    val backgroundColor = when (model.status) {
        ModelStatus.RUNNING -> MaterialTheme.colors.primary.copy(alpha = 0.15f)
        ModelStatus.LIBRARY -> Color(0xFFFFF3E0)
        ModelStatus.LOADED -> MaterialTheme.colors.surface
        ModelStatus.CLOUD -> Color(0xFFE3F2FD)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(backgroundColor)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Status icon
        Icon(
            imageVector = when (model.status) {
                ModelStatus.RUNNING -> Icons.Default.CheckCircle
                ModelStatus.LOADED -> Icons.Default.Settings
                ModelStatus.LIBRARY -> Icons.Default.Settings
                ModelStatus.CLOUD -> Icons.Default.Settings
            },
            contentDescription = null,
            tint = when (model.status) {
                ModelStatus.RUNNING -> MaterialTheme.colors.primary
                ModelStatus.LOADED -> MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                ModelStatus.LIBRARY -> MaterialTheme.colors.secondary
                ModelStatus.CLOUD -> Color(0xFF2196F3)
            },
            modifier = Modifier.size(20.dp)
        )

        Spacer(Modifier.width(12.dp))

        // Model info
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = model.name,
                style = MaterialTheme.typography.body1,
                color = if (model.status == ModelStatus.RUNNING) MaterialTheme.colors.primary else MaterialTheme.colors.onSurface
            )

            if (model.description.isNotBlank()) {
                Text(
                    text = model.description,
                    style = MaterialTheme.typography.caption,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                    maxLines = 1
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = model.size,
                    style = MaterialTheme.typography.caption,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                )

                // Status badge
                Text(
                    text = when (model.status) {
                        ModelStatus.RUNNING -> "🟢"
                        ModelStatus.LOADED -> ""
                        ModelStatus.LIBRARY -> "📚 Library"
                        ModelStatus.CLOUD -> "☁️ Cloud"
                    },
                    style = MaterialTheme.typography.caption,
                    color = when (model.status) {
                        ModelStatus.RUNNING -> Color(0xFF4CAF50)
                        ModelStatus.LOADED -> MaterialTheme.colors.primary
                        ModelStatus.LIBRARY -> MaterialTheme.colors.secondary
                        ModelStatus.CLOUD -> Color(0xFF2196F3)
                    }
                )

                if (model.tags.isNotEmpty()) {
                    Text(
                        text = "• ${model.tags.take(2).joinToString(", ")}",
                        style = MaterialTheme.typography.caption,
                        color = MaterialTheme.colors.primary
                    )
                }
            }
        }

        // Action buttons
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            // Check for active operations specifically for this model and operation type
            val activeOperation = pullProgress?.let { progress ->
                // Only show progress if it's for this exact model and is a start/stop/remove operation
                if (progress.modelName == model.name) {
                    when (progress.operationType) {
                        OperationType.START -> if (model.status == ModelStatus.LOADED) progress else null
                        OperationType.STOP -> if (model.status == ModelStatus.RUNNING) progress else null
                        OperationType.REMOVE -> if (model.status == ModelStatus.LOADED) progress else null
                        else -> null
                    }
                } else null
            }

            if (activeOperation != null) {
                when (activeOperation.status) {
                    PullStatus.STARTING -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp,
                                color = Color(0xFF4CAF50)
                            )
                            Text(
                                text = "Starting...",
                                style = MaterialTheme.typography.caption,
                                color = Color(0xFF4CAF50)
                            )
                        }
                    }

                    PullStatus.STOPPING -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp,
                                color = Color(0xFFFF5252)
                            )
                            Text(
                                text = "Stopping...",
                                style = MaterialTheme.typography.caption,
                                color = Color(0xFFFF5252)
                            )
                        }
                    }

                    PullStatus.REMOVING -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp,
                                color = Color(0xFFFF9800)
                            )
                            Text(
                                text = "Removing...",
                                style = MaterialTheme.typography.caption,
                                color = Color(0xFFFF9800)
                            )
                        }
                    }

                    PullStatus.COMPLETED -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "✅",
                                style = MaterialTheme.typography.caption,
                                color = Color(0xFF4CAF50)
                            )
                            Text(
                                text = when (activeOperation.operationType) {
                                    OperationType.START -> "Started"
                                    OperationType.STOP -> "Stopped"
                                    OperationType.REMOVE -> "Removed"
                                    else -> "Done"
                                },
                                style = MaterialTheme.typography.caption,
                                color = Color(0xFF4CAF50)
                            )
                        }
                    }

                    PullStatus.ERROR -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "❌",
                                style = MaterialTheme.typography.caption,
                                color = Color(0xFFF44336)
                            )
                            Text(
                                text = "Error",
                                style = MaterialTheme.typography.caption,
                                color = Color(0xFFF44336)
                            )
                        }
                    }

                    else -> {
                        // Fallback to pull progress handling
                        when (pullProgress.status) {
                            PullStatus.CONNECTING -> {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(24.dp),
                                        strokeWidth = 2.dp,
                                        color = Color(0xFF2196F3)
                                    )
                                    Text(
                                        text = "Connecting...",
                                        style = MaterialTheme.typography.caption,
                                        color = Color(0xFF2196F3)
                                    )
                                }
                            }

                            PullStatus.PULLING_MANIFEST -> {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(24.dp),
                                        strokeWidth = 2.dp,
                                        color = Color(0xFF9C27B0)
                                    )
                                    Text(
                                        text = "Manifest...",
                                        style = MaterialTheme.typography.caption,
                                        color = Color(0xFF9C27B0)
                                    )
                                }
                            }

                            PullStatus.DOWNLOADING -> {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator(
                                        progress = pullProgress.progressPercent / 100f,
                                        modifier = Modifier.size(24.dp),
                                        strokeWidth = 2.dp,
                                        color = Color(0xFF4CAF50)
                                    )
                                    Text(
                                        text = "${pullProgress.progressPercent}%",
                                        style = MaterialTheme.typography.caption,
                                        color = Color(0xFF4CAF50)
                                    )
                                }
                            }

                            PullStatus.VERIFYING -> {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(24.dp),
                                        strokeWidth = 2.dp,
                                        color = Color(0xFFFF9800)
                                    )
                                    Text(
                                        text = "Verify...",
                                        style = MaterialTheme.typography.caption,
                                        color = Color(0xFFFF9800)
                                    )
                                }
                            }

                            PullStatus.COMPLETED -> {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        text = "✅",
                                        style = MaterialTheme.typography.caption,
                                        color = Color(0xFF4CAF50)
                                    )
                                    Text(
                                        text = "Done",
                                        style = MaterialTheme.typography.caption,
                                        color = Color(0xFF4CAF50)
                                    )
                                }
                            }

                            PullStatus.ERROR -> {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        text = "❌",
                                        style = MaterialTheme.typography.caption,
                                        color = Color(0xFFF44336)
                                    )
                                    Text(
                                        text = "Error",
                                        style = MaterialTheme.typography.caption,
                                        color = Color(0xFFF44336)
                                    )
                                }
                            }

                            PullStatus.CANCELLED -> {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        text = "⏹️",
                                        style = MaterialTheme.typography.caption,
                                        color = Color(0xFF9E9E9E)
                                    )
                                    Text(
                                        text = "Cancelled",
                                        style = MaterialTheme.typography.caption,
                                        color = Color(0xFF9E9E9E)
                                    )
                                }
                            }

                            else -> {
                                TextButton(
                                    onClick = onPull,
                                    modifier = Modifier.height(32.dp).defaultMinSize(minWidth = 50.dp)
                                ) {
                                    Text(
                                        "PULL",
                                        style = MaterialTheme.typography.caption,
                                        color = MaterialTheme.colors.secondary
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                when (model.status) {
                    ModelStatus.RUNNING -> {
                        TextButton(
                            onClick = {
                                isStopping = true
                                onStop()
                                scope.launch {
                                    kotlinx.coroutines.delay(2000)
                                    isStopping = false
                                }
                            },
                            modifier = Modifier.height(32.dp).defaultMinSize(minWidth = 50.dp),
                            enabled = !isStopping
                        ) {
                            if (isStopping) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = Color(0xFFFF5252)
                                )
                            } else {
                                Text("STOP", style = MaterialTheme.typography.caption, color = Color(0xFFFF5252))
                            }
                        }
                    }

                    ModelStatus.LOADED -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(
                                onClick = {
                                    isStarting = true
                                    onStart()
                                    scope.launch {
                                        // Wait for the actual start operation to complete
                                        // The startModel function now waits up to 15 seconds plus 2s delay
                                        kotlinx.coroutines.delay(18000) // Slightly longer than startModel timeout
                                        isStarting = false
                                    }
                                },
                                modifier = Modifier.height(32.dp).defaultMinSize(minWidth = 50.dp),
                                enabled = !isStarting && !isRemoving
                            ) {
                                if (isStarting) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = Color(0xFF4CAF50)
                                    )
                                } else {
                                    Text("START", style = MaterialTheme.typography.caption)
                                }
                            }
                            TextButton(
                                onClick = {
                                    isRemoving = true
                                    onRemove()
                                    scope.launch {
                                        kotlinx.coroutines.delay(5000) // Wait for remove operation to complete
                                        isRemoving = false
                                    }
                                },
                                modifier = Modifier.height(32.dp).defaultMinSize(minWidth = 50.dp),
                                enabled = !isRemoving && !isStarting
                            ) {
                                if (isRemoving) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = Color(0xFFFF9800)
                                    )
                                } else {
                                    Text("REMOVE", style = MaterialTheme.typography.caption, color = Color(0xFFFF9800))
                                }
                            }
                        }
                    }

                    ModelStatus.LIBRARY -> {
                        TextButton(
                            onClick = onPull,
                            modifier = Modifier.height(32.dp).defaultMinSize(minWidth = 50.dp)
                        ) {
                            Text(
                                "PULL",
                                style = MaterialTheme.typography.caption,
                                color = MaterialTheme.colors.secondary
                            )
                        }
                    }

                    ModelStatus.CLOUD -> {
                        // Check if cloud model is actually available locally
                        // Note: This case handles true cloud-only models (not cloud-named local models)
                        // Cloud-named local models get ModelStatus.LOADED status, not CLOUD
                        
                        // For true cloud models, always show PULL since they're not locally available
                        TextButton(
                            onClick = onPull,
                            modifier = Modifier.height(32.dp).defaultMinSize(minWidth = 50.dp)
                        ) {
                            Text(
                                "PULL",
                                style = MaterialTheme.typography.caption,
                                color = Color(0xFF2196F3)
                            )
                        }
                    }
                }
            }
        }
    }
}


@Composable
private fun LogEntryItem(log: OllamaLogEntry) {
    val backgroundColor = when (log.level) {
        LogLevel.ERROR -> Color(0xFFFFEBEE)
        LogLevel.WARNING -> Color(0xFFFFF8E1)
        LogLevel.INFO -> Color.Transparent
        LogLevel.DEBUG -> Color(0xFFE3F2FD)
    }

    val textColor = when (log.level) {
        LogLevel.ERROR -> Color(0xFFC62828)
        LogLevel.WARNING -> Color(0xFFF57C00)
        LogLevel.INFO -> MaterialTheme.colors.onSurface
        LogLevel.DEBUG -> MaterialTheme.colors.onSurface.copy(alpha = 0.7f)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(backgroundColor)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = log.timestamp.atZone(ZoneId.systemDefault()).toLocalDateTime()
                    .format(DateTimeFormatter.ofPattern("HH:mm:ss")),
                style = MaterialTheme.typography.caption,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                fontFamily = FontFamily.Monospace
            )

            Text(
                text = log.level.name,
                style = MaterialTheme.typography.caption,
                color = textColor,
                fontFamily = FontFamily.Monospace
            )
        }

        Spacer(Modifier.height(2.dp))

        Text(
            text = log.message,
            style = MaterialTheme.typography.body2,
            color = textColor,
            fontFamily = FontFamily.Monospace
        )

        if (log.model != null) {
            Text(
                text = "Model: ${log.model}",
                style = MaterialTheme.typography.caption,
                color = MaterialTheme.colors.primary,
                fontFamily = FontFamily.Monospace
            )
        }
    }

    Spacer(Modifier.height(2.dp))
}

// Data classes and API functions
data class OllamaModel(
    val name: String,
    val size: String,
    val modified: String,
    val digest: String? = null
) {
    val formattedSize: String
        get() = try {
            val sizeBytes = size.toLongOrNull()
            if (sizeBytes != null) {
                when {
                    sizeBytes >= 1_000_000_000 -> "${sizeBytes / 1_000_000_000} GB"
                    sizeBytes >= 1_000_000 -> "${sizeBytes / 1_000_000} MB"
                    sizeBytes >= 1_000 -> "${sizeBytes / 1_000} KB"
                    else -> "$sizeBytes B"
                }
            } else {
                size // Return original if parsing fails
            }
        } catch (e: Exception) {
            size // Return original if any error
        }
}


data class OllamaLogEntry(
    val timestamp: Instant,
    val level: LogLevel,
    val message: String,
    val model: String? = null,
    val tokensPerSecond: Double? = null,
    val contextSize: Int? = null
)

enum class LogLevel {
    DEBUG, INFO, WARNING, ERROR
}

// Missing data classes and functions
// Enhanced progress tracking data class
data class PullProgress(
    val modelName: String,
    val operationType: OperationType,
    val status: PullStatus,
    val progressPercent: Int = 0,
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long = 0L,
    val speed: String = "",
    val eta: String = "",
    val message: String = "",
    val timestamp: Long = System.currentTimeMillis()
)

enum class OperationType {
    PULL, START, STOP, STOP_ALL, SERVICE_START, SERVICE_STOP, REMOVE
}

enum class PullStatus {
    IDLE,           // Not started
    CONNECTING,     // Connecting to Ollama
    PULLING_MANIFEST, // Getting model info
    DOWNLOADING,    // Downloading model
    VERIFYING,      // Verifying download
    STARTING,       // Starting model
    STOPPING,       // Stopping model
    REMOVING,       // Removing model
    COMPLETED,      // Successfully completed
    ERROR,          // Error occurred
    CANCELLED       // User cancelled
}

data class LibraryModel(
    val name: String,
    val description: String,
    val tags: List<String>,
    val size: String
)

// Unified model data structure
data class UnifiedModel(
    val name: String,
    val description: String = "",
    val tags: List<String> = emptyList(),
    val size: String,
    val status: ModelStatus,
    val libraryInfo: LibraryInfo? = null,
    val digest: String? = null
)

data class LibraryInfo(
    val description: String,
    val tags: List<String>,
    val size: String
)

enum class ModelStatus {
    LIBRARY,     // Available in library but not installed
    LOADED,      // Installed locally
    RUNNING,     // Currently running
    CLOUD        // Available from cloud repository
}

// Repository for library models
object LibraryModelRepository {
    private var _cachedModels: List<LibraryModel> = emptyList()
    private var _lastUpdated: Long = 0
    
    // ollama.com public API (same shape as local /api/tags). Fallback: third-party library list.
    private val DEFAULT_LIBRARY_URL = "https://ollama.com/api/tags"
    private val FALLBACK_LIBRARY_URL = "https://yuma-shintani.github.io/ollama-model-library/model.json"
    
    // Get cached models
    fun getCachedModels(): List<LibraryModel> {
        return _cachedModels
    }
    
    // Get last updated timestamp
    fun getLastUpdated(): Long {
        return _lastUpdated
    }
    
    // Update models from URL; try fallback if primary returns no models
    suspend fun updateFromUrl(url: String = DEFAULT_LIBRARY_URL): List<LibraryModel> = withContext(Dispatchers.IO) {
        fun fetch(u: String): List<LibraryModel> {
            try {
                println("[LIBRARY] Fetching from: $u")
                val client = HttpClient.newHttpClient()
                val request = HttpRequest.newBuilder()
                    .uri(java.net.URI.create(u))
                    .timeout(java.time.Duration.ofSeconds(30))
                    .header("Accept", "application/json")
                    .build()
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() != 200) {
                    println("[LIBRARY] HTTP ${response.statusCode()}")
                    return emptyList()
                }
                val body = response.body()
                val models = parseModelsFromJson(body)
                if (models.isEmpty() && body.isNotBlank()) {
                    println("[LIBRARY] Parse returned 0 models. Response sample: ${body.take(400)}")
                }
                return models
            } catch (e: Exception) {
                println("[LIBRARY] Fetch error: ${e.message}")
                return emptyList()
            }
        }
        val models = fetch(url)
        val result = if (models.isEmpty() && url == DEFAULT_LIBRARY_URL) fetch(FALLBACK_LIBRARY_URL) else models
        if (result.isNotEmpty()) {
            _cachedModels = result
            _lastUpdated = System.currentTimeMillis()
            println("[LIBRARY] Loaded ${result.size} models")
        }
        result
    }
    
    // Helper: get string from JSON element without extra quotes (JsonPrimitive.content)
    private fun stringVal(je: kotlinx.serialization.json.JsonElement?): String? =
        (je as? JsonPrimitive)?.content?.removeSurrounding("\"")

    // Parse models from JSON (ollama.com/api/tags or local /api/tags shape: { "models": [ { "name", "model", "details"? } ] })
    private fun parseModelsFromJson(json: String): List<LibraryModel> {
        return try {
            val jsonElement = Json.parseToJsonElement(json)
            val models = mutableListOf<LibraryModel>()
            val modelsArray = when (jsonElement) {
                is JsonObject -> jsonElement["models"] as? JsonArray
                    ?: jsonElement["items"] as? JsonArray
                    ?: jsonElement["data"] as? JsonArray
                is JsonArray -> jsonElement
                else -> null
            }
            if (modelsArray == null) {
                val keys = (jsonElement as? JsonObject)?.keys?.joinToString()
                println("[LIBRARY] Parse: no models array (top-level keys: $keys)")
                return emptyList()
            }

            modelsArray.forEach { modelElement ->
                if (modelElement is JsonObject) {
                    val name = stringVal(modelElement["name"]) ?: stringVal(modelElement["model"]) ?: return@forEach
                    val details = modelElement["details"] as? JsonObject
                    val paramSize = details?.let { stringVal(it["parameter_size"]) }
                    val family = details?.let { stringVal(it["family"]) }
                    val description = stringVal(modelElement["description"])
                        ?: paramSize?.let { "$family $it" }?.trim()
                        ?: family
                        ?: "No description available"
                    val tags = mutableListOf<String>()
                    family?.let { tags.add(it) }
                    val tagsArray = modelElement["tags"] as? JsonArray
                    tagsArray?.forEach { tag -> stringVal(tag)?.let { tags.add(it) } }
                    models.add(
                        LibraryModel(
                            name = name,
                            description = description,
                            tags = tags,
                            size = paramSize?.let { "~$it" } ?: estimateModelSize(name)
                        )
                    )
                }
            }
            models
        } catch (e: Exception) {
            println("[LIBRARY] Error parsing JSON: ${e.message}")
            emptyList()
        }
    }
    
    // Estimate model size based on name patterns
    private fun estimateModelSize(name: String): String {
        val lowerName = name.lowercase()
        return when {
            lowerName.contains("70b") -> "~40 GB"
            lowerName.contains("34b") -> "~20 GB"
            lowerName.contains("13b") -> "~8 GB"
            lowerName.contains("7b") -> "~4 GB"
            lowerName.contains("3b") || lowerName.contains("1b") -> "~2 GB"
            lowerName.contains("tiny") || lowerName.contains("small") -> "~1 GB"
            else -> "~4 GB" // Default estimate
        }
    }
    
    fun getDefaultModels(): List<LibraryModel> = emptyList()
    
    // Clear cache
    fun clearCache() {
        _cachedModels = emptyList()
        _lastUpdated = 0
    }
}

private suspend fun loadLibraryModels(): List<LibraryModel> = withContext(Dispatchers.IO) {
    // Try to load from cache first, then update if needed
    val cachedModels = LibraryModelRepository.getCachedModels()
    val lastUpdated = LibraryModelRepository.getLastUpdated()
    
    // If cache is empty or older than 1 hour, try to update
    if (cachedModels.isEmpty() || (System.currentTimeMillis() - lastUpdated) > 3600000) { // 1 hour
        println("[LIBRARY] Cache empty or stale, updating from URL...")
        try {
            val updatedModels = LibraryModelRepository.updateFromUrl()
            if (updatedModels.isNotEmpty()) {
                return@withContext updatedModels
            }
        } catch (e: Exception) {
            println("[LIBRARY] Failed to update from URL, using cache: ${e.message}")
        }
    }
    
    val allModels = if (cachedModels.isNotEmpty()) cachedModels else emptyList()

    // Sort by context length (smaller first) for display
    allModels.sortedBy { model ->
        val paramSize = when {
            model.name.contains("7b") -> 7
            model.name.contains("13b") -> 13
            model.name.contains("70b") -> 70
            else -> 7
        }
        paramSize
    }
}

private suspend fun downloadModel(modelName: String) = withContext(Dispatchers.IO) {
    // TODO: Implement actual model downloading logic
    // This would typically involve downloading from a remote source
    try {
        // Simulate download progress
        delay(1000)
        // In a real implementation, you would:
        // 1. Start the download
        // 2. Update progress as it downloads
        // 3. Handle errors appropriately
    } catch (e: Exception) {
        e.printStackTrace()
    }
}


private suspend fun isOllamaServiceRunning(): Boolean = withContext(Dispatchers.IO) {
    try {
        // Check if Ollama API is responding
        val client = HttpClient.newHttpClient()
        val request = HttpRequest.newBuilder()
            .uri(java.net.URI.create("http://localhost:11434/api/tags"))
            .timeout(java.time.Duration.ofSeconds(3))
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        response.statusCode() == 200
    } catch (e: Exception) {
        false
    }
}

private suspend fun updateAllData(onResult: (List<OllamaModel>, List<OllamaModel>, List<OllamaLogEntry>, Boolean) -> Unit) =
    withContext(Dispatchers.IO) {
        try {
            val client = HttpClient.newHttpClient()

            // Get available models
            val tagsRequest = HttpRequest.newBuilder()
                .uri(java.net.URI.create("http://localhost:11434/api/tags"))
                .build()
            val tagsResponse = client.send(tagsRequest, HttpResponse.BodyHandlers.ofString())

            // Get running models and logs
            val psRequest = HttpRequest.newBuilder()
                .uri(java.net.URI.create("http://localhost:11434/api/ps"))
                .build()
            val psResponse = client.send(psRequest, HttpResponse.BodyHandlers.ofString())

            val availableModels = if (tagsResponse.statusCode() == 200) {
                parseModelsResponse(tagsResponse.body())
            } else emptyList()

            val (runningModels, logs) = if (psResponse.statusCode() == 200) {
                val models = parseModelsResponse(psResponse.body())
                val logEntries = parseLogsResponse(psResponse.body())

                // Add launcher module as a running model
                val launcherModel = OllamaModel(
                    name = "koog-launcher",
                    size = "512MB",
                    modified = java.time.Instant.now().toString(),
                    digest = "launcher-active"
                )

                val allRunningModels = models + launcherModel
                Pair(allRunningModels, logEntries)
            } else {
                // Even if Ollama API fails, show launcher as running
                val launcherModel = OllamaModel(
                    name = "koog-launcher",
                    size = "512MB",
                    modified = java.time.Instant.now().toString(),
                    digest = "launcher-active"
                )
                Pair(listOf(launcherModel), emptyList())
            }

            onResult(availableModels, runningModels, logs, true)
        } catch (e: Exception) {
            onResult(emptyList(), emptyList(), emptyList(), false)
        }
    }

/** True if the model is a cloud model (name-based; used to choose start/stop flow). */
private fun isCloudModelName(modelName: String): Boolean =
    modelName.contains("-cloud", ignoreCase = true) || modelName.contains("cloud", ignoreCase = true)

private suspend fun startModel(modelName: String, runningCloudModels: MutableState<Set<String>>) = withContext(Dispatchers.IO) {
    println("[${java.time.LocalDateTime.now()}] Starting model operation: $modelName")
    try {
        if (isCloudModelName(modelName)) {
            startCloudModel(modelName, runningCloudModels)
        } else {
            startLocalModel(modelName)
        }
    } catch (e: Exception) {
        println("[${java.time.LocalDateTime.now()}] ERROR: Failed to start model $modelName: ${e.message}")
        throw Exception("Failed to start model: ${e.message}")
    }
}

/** Cloud start: rely on HTTP connection flow only. Update running state from response success/failure; no /api/ps polling. */
private suspend fun startCloudModel(modelName: String, runningCloudModels: MutableState<Set<String>>) {
    kotlinx.coroutines.delay(2000)
    val client = HttpClient.newHttpClient()
    println("[${java.time.LocalDateTime.now()}] Sending start request for cloud model: $modelName")
    val request = HttpRequest.newBuilder()
        .uri(java.net.URI.create("http://localhost:11434/api/generate"))
        .header("Content-Type", "application/json")
        .POST(
            HttpRequest.BodyPublishers.ofString(
                """
                {
                    "model": "$modelName",
                    "prompt": "",
                    "stream": false,
                    "keep_alive": -1
                }
            """.trimIndent()
            )
        )
        .build()

    val response = client.send(request, HttpResponse.BodyHandlers.ofString())
    val responseBody = response.body()
    println("[${java.time.LocalDateTime.now()}] Cloud start response: ${response.statusCode()} - $responseBody")

    if (response.statusCode() != 200) {
        println("[${java.time.LocalDateTime.now()}] Cloud start failed for $modelName. Response: $responseBody")
        throw Exception("Failed to start cloud model: HTTP ${response.statusCode()}")
    }

    // Rely on HTTP success: connection accepted => consider model started for UI
    val successByResponse = responseBody.contains("\"done\":true") ||
        responseBody.contains("remote_host") ||
        responseBody.contains("remote_model")
    if (successByResponse) {
        println("[${java.time.LocalDateTime.now()}] SUCCESS: Cloud model $modelName started (HTTP flow OK)")
    } else {
        println("[${java.time.LocalDateTime.now()}] Cloud model $modelName: HTTP 200, updating running state from connection flow")
    }
    val displayName = modelName.split(":").first()
    withContext(Dispatchers.Main) {
        val updated = runningCloudModels.value + modelName + displayName
        runningCloudModels.value = updated
        println("[DEBUG] Updated runningCloudModels to: ${runningCloudModels.value.joinToString(", ")}")
    }
}

/** Local start: optional already-running check, then poll /api/ps until model appears. */
private suspend fun startLocalModel(modelName: String) {
    kotlinx.coroutines.delay(2000)
    val client = HttpClient.newHttpClient()

    try {
        println("[${java.time.LocalDateTime.now()}] Checking if local model $modelName is already running...")
        val psRequest = HttpRequest.newBuilder()
            .uri(java.net.URI.create("http://localhost:11434/api/ps"))
            .timeout(java.time.Duration.ofSeconds(5))
            .GET()
            .build()
        val psResponse = client.send(psRequest, HttpResponse.BodyHandlers.ofString())
        if (psResponse.statusCode() == 200 && psResponse.body().contains("\"name\": \"$modelName\"")) {
            println("[${java.time.LocalDateTime.now()}] Model $modelName is already running - skipping start")
            return
        }
    } catch (e: Exception) {
        println("[${java.time.LocalDateTime.now()}] Error checking initial model status: ${e.message}")
    }

    println("[${java.time.LocalDateTime.now()}] Sending start request for local model: $modelName")
    val request = HttpRequest.newBuilder()
        .uri(java.net.URI.create("http://localhost:11434/api/generate"))
        .header("Content-Type", "application/json")
        .POST(
            HttpRequest.BodyPublishers.ofString(
                """
                {
                    "model": "$modelName",
                    "prompt": "",
                    "stream": false,
                    "keep_alive": -1
                }
            """.trimIndent()
            )
        )
        .build()

    val response = client.send(request, HttpResponse.BodyHandlers.ofString())
    if (response.statusCode() != 200) {
        println("[${java.time.LocalDateTime.now()}] Start request failed for model $modelName. Response: ${response.body()}")
        throw Exception("Failed to start model: HTTP ${response.statusCode()}")
    }

    println("[${java.time.LocalDateTime.now()}] Waiting 3 seconds for model to initialize...")
    kotlinx.coroutines.delay(3000)

    var attempts = 0
    val maxAttempts = 15
    while (attempts < maxAttempts) {
        attempts++
        kotlinx.coroutines.delay(1000)
        try {
            val psRequest = HttpRequest.newBuilder()
                .uri(java.net.URI.create("http://localhost:11434/api/ps"))
                .timeout(java.time.Duration.ofSeconds(5))
                .GET()
                .build()
            val psResponse = client.send(psRequest, HttpResponse.BodyHandlers.ofString())
            if (psResponse.statusCode() == 200 && psResponse.body().contains("\"name\":\"$modelName\"")) {
                println("[${java.time.LocalDateTime.now()}] SUCCESS: Local model $modelName is now running")
                return
            }
            if (attempts > 3) {
                val modelsRequest = HttpRequest.newBuilder()
                    .uri(java.net.URI.create("http://localhost:11434/api/tags"))
                    .timeout(java.time.Duration.ofSeconds(5))
                    .GET()
                    .build()
                val modelsResponse = client.send(modelsRequest, HttpResponse.BodyHandlers.ofString())
                if (modelsResponse.statusCode() == 200 && modelsResponse.body().contains("\"name\": \"$modelName\"")) {
                    println("[${java.time.LocalDateTime.now()}] Model $modelName exists but not running, likely stopped by user")
                    return
                }
            }
        } catch (e: Exception) {
            println("[${java.time.LocalDateTime.now()}] Error checking model status: ${e.message}")
        }
    }
    println("[${java.time.LocalDateTime.now()}] WARNING: Local model $modelName may not have started properly within timeout")
}

private suspend fun stopModel(modelName: String, runningCloudModels: MutableState<Set<String>>) = withContext(Dispatchers.IO) {
    println("[${java.time.LocalDateTime.now()}] Stopping model operation: $modelName")
    try {
        val base = modelName.split(":").first()
        val isCloudRunning = withContext(Dispatchers.Main) {
            runningCloudModels.value.any { it == modelName || it == base || it.startsWith("$base:") }
        }
        if (isCloudModelName(modelName) || isCloudRunning) {
            stopCloudModel(modelName, runningCloudModels)
        } else {
            stopLocalModel(modelName)
        }
    } catch (e: Exception) {
        println("[${java.time.LocalDateTime.now()}] ERROR: Failed to stop model $modelName: ${e.message}")
        throw Exception("Failed to stop model: ${e.message}")
    }
}

/** Cloud stop: send unload/keep_alive:0; update running state from HTTP flow (and on timeout so UI doesn't stick). */
private suspend fun stopCloudModel(modelName: String, runningCloudModels: MutableState<Set<String>>) {
    val displayName = modelName.split(":").first()
    // Resolve actual model name for Ollama (e.g. qwen3-coder -> qwen3-coder:480b-cloud)
    val actualModelName = withContext(Dispatchers.Main) {
        runningCloudModels.value.firstOrNull { it.startsWith("$displayName:") }
            ?: runningCloudModels.value.firstOrNull { it == modelName || it == displayName }
            ?: modelName
    }
    suspend fun removeFromRunning() {
        withContext(Dispatchers.Main) {
            // Remove both display name and full model name (e.g. qwen3-coder and qwen3-coder:480b-cloud)
            runningCloudModels.value = runningCloudModels.value.filter {
                it != modelName && !it.startsWith("$modelName:") && it != displayName && !it.startsWith("$displayName:")
            }.toSet()
            println("[${java.time.LocalDateTime.now()}] Removed cloud model $modelName from running state")
        }
    }

    try {
        val client = HttpClient.newHttpClient()
        try {
            println("[${java.time.LocalDateTime.now()}] Stopping cloud model $actualModelName via /api/generate keep_alive: 0")
            val request = HttpRequest.newBuilder()
                .uri(java.net.URI.create("http://localhost:11434/api/generate"))
                .header("Content-Type", "application/json")
                .timeout(java.time.Duration.ofSeconds(10))
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        """
                    {
                        "model": "$actualModelName",
                        "prompt": "",
                        "stream": false,
                        "keep_alive": 0,
                        "options": { "temperature": 0, "max_tokens": 1 }
                    }
                """.trimIndent()
                    )
                )
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() == 200) {
                println("[${java.time.LocalDateTime.now()}] Cloud stop request accepted for $actualModelName")
                removeFromRunning()
                return
            }
        } catch (_: java.net.http.HttpTimeoutException) {
            println("[${java.time.LocalDateTime.now()}] Timeout on cloud stop - updating UI state from connection flow")
            removeFromRunning()
            return
        }
        try {
            val process = ProcessBuilder("ollama", "stop", actualModelName).redirectErrorStream(true).start()
            val exitCode = process.waitFor()
            if (exitCode == 0) {
                removeFromRunning()
                return
            }
        } catch (_: Exception) { }
        // Still update UI so button state is correct
        removeFromRunning()
    } catch (e: Exception) {
        removeFromRunning()
        throw e
    }
}

/** Local stop: /api/generate keep_alive:0, then CLI or force unload; no cloud state. */
private suspend fun stopLocalModel(modelName: String) {
    val client = HttpClient.newHttpClient()
    try {
        val request = HttpRequest.newBuilder()
            .uri(java.net.URI.create("http://localhost:11434/api/generate"))
            .header("Content-Type", "application/json")
            .timeout(java.time.Duration.ofSeconds(10))
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    """
                    {
                        "model": "$modelName",
                        "prompt": "",
                        "stream": false,
                        "keep_alive": 0,
                        "options": { "temperature": 0, "max_tokens": 1 }
                    }
                """.trimIndent()
                )
            )
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() == 200) {
            println("[${java.time.LocalDateTime.now()}] Stop request sent successfully for local model: $modelName")
            return
        }
    } catch (_: java.net.http.HttpTimeoutException) { }

    try {
        val process = ProcessBuilder("ollama", "stop", modelName).redirectErrorStream(true).start()
        if (process.waitFor() == 0) {
            println("[${java.time.LocalDateTime.now()}] CLI stop successful for $modelName")
            return
        }
    } catch (e: Exception) {
        println("[${java.time.LocalDateTime.now()}] CLI stop not available: ${e.message}")
    }

    try {
        val forceRequest = HttpRequest.newBuilder()
            .uri(java.net.URI.create("http://localhost:11434/api/generate"))
            .header("Content-Type", "application/json")
            .timeout(java.time.Duration.ofSeconds(5))
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    """
                    {
                        "model": "$modelName",
                        "prompt": "unload",
                        "stream": false,
                        "keep_alive": 0,
                        "options": { "temperature": 0, "max_tokens": 1 }
                    }
                """.trimIndent()
                )
            )
            .build()
        client.send(forceRequest, HttpResponse.BodyHandlers.ofString())
    } catch (e: Exception) {
        println("Force unload failed: ${e.message}")
    }
    throw Exception("[${java.time.LocalDateTime.now()}] All stop methods failed for model: $modelName")
}

private suspend fun stopAllModels() = withContext(Dispatchers.IO) {
    println("[${java.time.LocalDateTime.now()}] Stopping all models operation")
    try {
        val client = HttpClient.newHttpClient()
        println("[${java.time.LocalDateTime.now()}] Sending stop request for all models")
        val request = HttpRequest.newBuilder()
            .uri(java.net.URI.create("http://localhost:11434/api/generate"))
            .header("Content-Type", "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    """
                {
                    "model": "*",
                    "prompt": "Hello",
                    "keep_alive": "0"
                }
            """.trimIndent()
                )
            )
            .build()

        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        println("[${java.time.LocalDateTime.now()}] Stop all models response status: ${response.statusCode()}")
        if (response.statusCode() != 200) {
            println("[${java.time.LocalDateTime.now()}] Stop all models failed. Response: ${response.body()}")
            throw Exception("Failed to stop all models: HTTP ${response.statusCode()}")
        }
        println("[${java.time.LocalDateTime.now()}] All models stopped successfully")
    } catch (e: Exception) {
        println("[${java.time.LocalDateTime.now()}] ERROR: Failed to stop all models: ${e.message}")
        throw Exception("Failed to stop all models: ${e.message}")
    }
}

private suspend fun pullModel(modelName: String, onProgress: (String) -> Unit) = withContext(Dispatchers.IO) {
    println("[${java.time.LocalDateTime.now()}] Pull model operation: $modelName")
    if (modelName.isBlank()) {
        println("[${java.time.LocalDateTime.now()}] ERROR: Pull failed - Model name cannot be empty")
        val errorLog = OllamaLogEntry(
            timestamp = java.time.Instant.now(),
            level = LogLevel.ERROR,
            message = "Pull failed: Model name cannot be empty"
        )
        // Note: We can't update ollamaLogs here directly, so we'll use onProgress
        onProgress("❌ Error: Model name cannot be empty")
        return@withContext
    }

    // Initialize progress tracking
    val startTime = System.currentTimeMillis()
    var lastProgressUpdate = startTime
    var lastBytesDownloaded = 0L

    try {
        println("[${java.time.LocalDateTime.now()}] Starting pull process for model: $modelName")
        // Update initial status
        onProgress("🔍 Starting pull for $modelName...")

        // Step 1: Validate model exists in library repository
        onProgress("🔍 Validating model in library repository...")
        try {
            val libraryModels = LibraryModelRepository.updateFromUrl()
            val modelExists = libraryModels.any { it.name.equals(modelName, ignoreCase = true) }
            
            if (!modelExists) {
                onProgress("⚠️ Model '$modelName' not found in official library. Proceeding anyway...")
            } else {
                val modelInfo = libraryModels.find { it.name.equals(modelName, ignoreCase = true) }
                onProgress("✅ Model found in library: ${modelInfo?.description ?: "No description"}")
            }
        } catch (e: Exception) {
            println("[LIBRARY] Could not validate model in repository: ${e.message}")
            onProgress("⚠️ Could not validate model in library, proceeding anyway...")
        }

        // Step 2: Download model from registry URL
        onProgress("🔗 Connecting to Ollama registry...")
        
        val client = HttpClient.newHttpClient()
        println("[${java.time.LocalDateTime.now()}] Creating download request for model: $modelName")
        val request = HttpRequest.newBuilder()
            .uri(java.net.URI.create("https://registry.ollama.ai/api/v1/models/$modelName/pull"))
            .header("Content-Type", "application/json")
            .timeout(java.time.Duration.ofMinutes(30))
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    """
                {
                    "name": "$modelName"
                }
            """.trimIndent()
                )
            )
            .build()

        println("[${java.time.LocalDateTime.now()}] Sending download request for model: $modelName")
        onProgress("🔗 Connecting to Ollama registry API...")

        val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        println("[${java.time.LocalDateTime.now()}] Pull request response status: ${response.statusCode()}")

        if (response.statusCode() == 200) {
            println("[${java.time.LocalDateTime.now()}] Successfully connected to Ollama registry for pulling: $modelName")
            onProgress("✅ Connected to Ollama registry, preparing to download $modelName...")

            var lineCount = 0
            var lastStatus = ""
            var lastProgressUpdate = startTime
            var lastBytesDownloaded = 0L

            // Read the stream line by line
            response.body().bufferedReader().use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    lineCount++
                    if (!line.isNullOrBlank()) {
                        try {
                            val json = Json { ignoreUnknownKeys = true }
                            val jsonObject = json.decodeFromString<kotlinx.serialization.json.JsonObject>(line)

                            // Log raw JSON for debugging
                            println("[DEBUG] Raw JSON: $jsonObject")

                            val status = jsonObject["status"]?.toString()?.replace("\"", "") ?: ""
                            val total = jsonObject["total"]?.toString()?.replace("\"", "") ?: ""
                            val completed = jsonObject["completed"]?.toString()?.replace("\"", "") ?: ""
                            val digest = jsonObject["digest"]?.toString()?.replace("\"", "") ?: ""

                            val now = System.currentTimeMillis()
                            val totalBytes = total.toLongOrNull() ?: 0L
                            val completedBytes = completed.toLongOrNull() ?: 0L
                            val progressPercent = if (totalBytes > 0) (completedBytes * 100 / totalBytes).toInt() else 0

                            // Debug the values we're getting
                            println("[DEBUG] Total: $totalBytes, Completed: $completedBytes, Percent: $progressPercent%")

                            // Calculate download speed
                            val speed = if (now - lastProgressUpdate > 1000 && completedBytes > lastBytesDownloaded) {
                                val bytesPerSecond =
                                    (completedBytes - lastBytesDownloaded) * 1000 / (now - lastProgressUpdate)
                                val speedMBs = bytesPerSecond / (1024 * 1024)
                                String.format("%.1f MB/s", speedMBs)
                            } else {
                                println("[DEBUG] Speed calculation: now-lastUpdate=${now - lastProgressUpdate}, completed>last=$completedBytes>lastBytesDownloaded")
                                ""
                            }

                            // Calculate ETA
                            val eta = if (totalBytes > 0 && completedBytes > 0 && speed.isNotEmpty()) {
                                val remainingBytes = totalBytes - completedBytes
                                val speedMBs = speed.replace(" MB/s", "").toDoubleOrNull() ?: 0.0
                                val speedBytesPerSecond = speedMBs * 1024 * 1024
                                if (speedBytesPerSecond > 0) {
                                    val etaSeconds = remainingBytes / speedBytesPerSecond
                                    if (etaSeconds < 60) "${etaSeconds.toInt()}s"
                                    else if (etaSeconds < 3600) "${(etaSeconds / 60).toInt()}m"
                                    else "${(etaSeconds / 3600).toInt()}h"
                                } else ""
                            } else ""

                            // Throttle progress updates
                            if (now - lastProgressUpdate > 500 || status == "success" || status.startsWith("error")) {
                                println("[DEBUG] Processing status update: $status")
                                when {
                                    status == "pulling manifest" -> {
                                        println("[DEBUG] Updating progress: Pulling manifest")
                                        onProgress("🔍 Pulling manifest for $modelName...")
                                    }

                                    status.startsWith("pulling") -> {
                                        // This is download progress (with or without digest)
                                        val progressMsg = if (progressPercent > 0) {
                                            "⬇️ Downloading $modelName... ${progressPercent}% ${if (speed.isNotBlank()) "• $speed" else ""}${if (eta.isNotBlank()) " • ETA: $eta" else ""}"
                                        } else {
                                            "⬇️ Downloading $modelName...${if (speed.isNotBlank()) " • $speed" else ""}"
                                        }
                                        println("[DEBUG] Updating progress: Downloading - $progressMsg")
                                        onProgress(progressMsg)
                                    }

                                    status == "downloading" -> {
                                        val progressMsg = if (progressPercent > 0) {
                                            "⬇️ Downloading $modelName... ${progressPercent}% ${if (speed.isNotBlank()) "• $speed" else ""}${if (eta.isNotBlank()) " • ETA: $eta" else ""}"
                                        } else {
                                            "⬇️ Downloading $modelName...${if (speed.isNotBlank()) " • $speed" else ""}"
                                        }
                                        println("[DEBUG] Updating progress: Downloading - $progressMsg")
                                        onProgress(progressMsg)
                                    }

                                    status == "success" -> {
                                        val duration = System.currentTimeMillis() - startTime
                                        val durationSeconds = duration / 1000
                                        println("[DEBUG] Updating progress: Success - $durationSeconds")
                                        onProgress("✅ Successfully pulled $modelName in ${durationSeconds}s")
                                    }

                                    status == "error" -> {
                                        val error =
                                            jsonObject["error"]?.toString()?.replace("\"", "") ?: "Unknown error"
                                        println("[DEBUG] Updating progress: Error - $error")
                                        onProgress("❌ Error pulling $modelName: $error")
                                    }

                                    else -> {
                                        println("[DEBUG] Updating progress: Other - $status")
                                        onProgress("$modelName: $status")
                                    }
                                }
                                lastProgressUpdate = now
                                lastBytesDownloaded = completedBytes
                            }
                        } catch (e: Exception) {
                            val errorMsg = "JSON parsing error on line $lineCount: ${e.message}"
                            onProgress("⚠️ $errorMsg")
                            if (line.contains("error") || line.contains("failed")) {
                                onProgress("❌ $line")
                            } else {
                                onProgress("📝 $line")
                            }
                        }
                    }
                }
            }
        } else {
            onProgress("❌ Failed to pull $modelName: HTTP ${response.statusCode()} - ${response.body()}")
        }
    } catch (e: java.net.ConnectException) {
        onProgress("❌ Cannot connect to Ollama registry. Check your internet connection.")
    } catch (e: java.net.SocketTimeoutException) {
        onProgress("❌ Download timeout for $modelName after 30 minutes. The model might be too large or network is slow.")
    } catch (e: java.net.UnknownHostException) {
        onProgress("❌ Cannot resolve host 'registry.ollama.ai'. Check your internet connection.")
    } catch (e: Exception) {
        onProgress("❌ Unexpected error downloading model: ${e.message}")
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

private suspend fun removeModel(modelName: String) = withContext(Dispatchers.IO) {
    println("[${java.time.LocalDateTime.now()}] Remove model operation: $modelName")
    try {
        val client = HttpClient.newHttpClient()
        println("[${java.time.LocalDateTime.now()}] Creating delete request for model: $modelName")
        val request = HttpRequest.newBuilder()
            .uri(java.net.URI.create("http://localhost:11434/api/delete"))
            .header("Content-Type", "application/json")
            .method("DELETE", HttpRequest.BodyPublishers.ofString("""{"name":"$modelName"}"""))
            .build()

        println("[${java.time.LocalDateTime.now()}] Sending delete request for model: $modelName")
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        println("[${java.time.LocalDateTime.now()}] Delete request response status: ${response.statusCode()}")
        println("[${java.time.LocalDateTime.now()}] Delete request response body: ${response.body()}")

        if (response.statusCode() != 200) {
            println("[${java.time.LocalDateTime.now()}] Delete request failed for model $modelName. Status: ${response.statusCode()}")
            println("[${java.time.LocalDateTime.now()}] Delete request response: ${response.body()}")
            throw Exception("Failed to remove model: ${response.statusCode()}")
        }

        println("[${java.time.LocalDateTime.now()}] Successfully removed model: $modelName")
    } catch (e: Exception) {
        println("[${java.time.LocalDateTime.now()}] ERROR: Failed to remove model $modelName: ${e.message}")
        throw Exception("Failed to remove model: ${e.message}")
    }
}

private fun parseLogsResponse(response: String): List<OllamaLogEntry> {
    return try {
        val json = Json { ignoreUnknownKeys = true }
        val jsonObject = json.decodeFromString<kotlinx.serialization.json.JsonObject>(response)
        val models = jsonObject["models"]?.toString()?.let {
            try {
                json.decodeFromString<kotlinx.serialization.json.JsonArray>(it)
            } catch (e: Exception) {
                null
            }
        } ?: return emptyList()

        val logs = mutableListOf<OllamaLogEntry>()
        val timestamp = Instant.now()

        models.forEach { modelElement ->
            val modelStr = modelElement.toString()
            try {
                val modelObj = json.decodeFromString<kotlinx.serialization.json.JsonObject>(modelStr)
                val modelName = modelObj["name"]?.toString()?.removeSurrounding("\"")
                val sizeVRAM = modelObj["size_vram"]?.toString()?.removeSurrounding("\"")
                val contextSize = modelObj["context_length"]?.toString()?.removeSurrounding("\"")
                val processingTime = modelObj["processing_time"]?.toString()?.removeSurrounding("\"")

                if (modelName != null) {
                    logs.add(
                        OllamaLogEntry(
                            timestamp = timestamp,
                            level = LogLevel.INFO,
                            message = "Model running: $modelName",
                            model = modelName,
                            contextSize = contextSize?.toIntOrNull()
                        )
                    )

                    if (sizeVRAM != null) {
                        logs.add(
                            OllamaLogEntry(
                                timestamp = timestamp,
                                level = LogLevel.DEBUG,
                                message = "VRAM usage: $sizeVRAM",
                                model = modelName
                            )
                        )
                    }

                    if (processingTime != null) {
                        logs.add(
                            OllamaLogEntry(
                                timestamp = timestamp,
                                level = LogLevel.DEBUG,
                                message = "Processing time: ${processingTime}ms",
                                model = modelName
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                // Skip invalid model entries
            }
        }

        logs
    } catch (e: Exception) {
        emptyList()
    }
}
