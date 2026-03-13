package gui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import core.TaskMode
import gui.data.TerminalEventDto
import gui.viewmodel.TerminalViewModel
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun Terminal(
    viewModel: TerminalViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsState()
    val displayOptions by viewModel.displayOptions.collectAsState()
    val agentStatus by viewModel.agentStatus.collectAsState()
    val listState = rememberLazyListState()
    var showFilterDialog by remember { mutableStateOf(false) }

    LaunchedEffect(state.events.size) {
        if (state.events.isNotEmpty()) {
            listState.animateScrollToItem(state.events.size - 1)
        }
    }

    Card(
        modifier = modifier.fillMaxSize(),
        elevation = 2.dp,
        backgroundColor = Color(0xFF1E1E1E) // Dark terminal background
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Session info strip (when show_status_bar enabled)
            if (displayOptions.showStatusBar && agentStatus != null) {
                val s = agentStatus!!
                val contextPct = (s.contextUsage * 100).toInt()
                val sessionInfo = buildString {
                    append("LLM: ${s.currentModelId}")
                    append(" | Tools: ${s.mcpToolsCount}")
                    append(" | Context: ${contextPct}%")
                    append(" | Files: ${s.filesLoaded}")
                    s.projectPath?.let { append(" | Project: $it") }
                }
                Text(
                    text = sessionInfo,
                    color = Color(0xFF00FF00).copy(alpha = 0.9f),
                    style = MaterialTheme.typography.caption,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF252526))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )
                Divider(color = Color(0xFF404040), thickness = 1.dp)
            }

            // Output area (selectable, takes all available space)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(8.dp)
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(
                        items = state.events,
                        key = { it.id }
                    ) { event ->
                        val timeStr = if (displayOptions.showTimestamps)
                            event.timestamp.atZone(ZoneId.systemDefault())
                                .format(DateTimeFormatter.ofPattern("HH:mm:ss"))
                        else null
                        val (color, prefix) = when (event.type) {
                            TerminalEventDto.EventType.STANDARD -> Color.White to ""
                            TerminalEventDto.EventType.SUCCESS -> Color.Green to "✓"
                            TerminalEventDto.EventType.WARNING -> Color(0xFFFFA500) to "⚠"
                            TerminalEventDto.EventType.ERROR -> Color.Red to "✗"
                            TerminalEventDto.EventType.SYSTEM -> Color.Cyan to "ℹ"
                            TerminalEventDto.EventType.DEBUG -> Color.Gray to "🐛"
                            TerminalEventDto.EventType.PROGRESS -> Color.White to "⏳"
                            TerminalEventDto.EventType.COMPLETE -> Color.Green to "✓"
                        }
                        val message = if (event.type == TerminalEventDto.EventType.PROGRESS && event.progressPercent != null) {
                            "[${event.progressPercent}%] ${event.message}"
                        } else {
                            event.message
                        }
                        val lineText = when {
                            timeStr != null && prefix.isEmpty() -> "[$timeStr] $message"
                            timeStr != null -> "[$timeStr] $prefix $message"
                            prefix.isEmpty() -> message
                            else -> "$prefix $message"
                        }
                        SelectionContainer {
                            Text(
                                text = lineText,
                                color = color,
                                style = MaterialTheme.typography.body2,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = if (displayOptions.compactMode) 0.dp else 1.dp)
                            )
                        }
                    }
                }
            }

            // Divider between output and input
            Divider(color = Color(0xFF404040), thickness = 1.dp)

            // Input area at bottom (like IntelliJ terminal)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF252526))
                    .padding(12.dp)
            ) {
                // Agent selector (simplified - always uses ImplementationAgent)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Agent: ${state.selectedAgentType.name}",
                        color = Color(0xFF00FF00),
                        style = MaterialTheme.typography.caption
                    )
                    // Simplified mode: Always uses ImplementationAgent
                    // Router defaults to ImplementationAgent, UI can override in future
                    Text(
                        text = "⚙️ Implementation (Default)",
                        color = Color(0xFF00FF00),
                        style = MaterialTheme.typography.caption
                    )
                }
                
                // Prompt line
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = ">",
                        color = Color.Green,
                        style = MaterialTheme.typography.body1,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    val focusManager = LocalFocusManager.current
                    TextField(
                        value = state.inputText,
                        onValueChange = { viewModel.updateInputText(it) },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Enter task and press Enter or click PROCESS", color = Color.Gray) },
                        colors = TextFieldDefaults.textFieldColors(
                            textColor = Color.White,
                            backgroundColor = Color.Transparent,
                            cursorColor = Color.Green,
                            focusedIndicatorColor = Color.Green,
                            unfocusedIndicatorColor = Color(0xFF404040)
                        ),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {
                            if (state.inputText.isNotBlank() && !state.isProcessing) {
                                viewModel.submitTask(state.inputText, TaskMode.CURRENT_MODEL)
                                focusManager.clearFocus()
                            }
                        }),
                        enabled = !state.isProcessing
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Action buttons row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { 
                                if (state.inputText.isNotBlank()) {
                                    viewModel.submitTask(state.inputText, TaskMode.CURRENT_MODEL)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF0D7377)),
                            enabled = !state.isProcessing
                        ) {
                            Text("PROCESS", color = Color.White)
                        }
                        Button(
                            onClick = { 
                                if (state.inputText.isNotBlank()) {
                                    viewModel.submitTask(state.inputText, TaskMode.SMART_ANALYZE)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF1470B8)),
                            enabled = !state.isProcessing
                        ) {
                            Text("ANALYZE", color = Color.White)
                        }
                        Button(
                            onClick = { 
                                if (state.inputText.isNotBlank()) {
                                    viewModel.submitTask(state.inputText, TaskMode.DEBUG)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFB8860B)),
                            enabled = !state.isProcessing
                        ) {
                            Text("DEBUG", color = Color.White)
                        }
                    }
                    
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        IconButton(
                            onClick = {
                                viewModel.loadFilterSettings()
                                showFilterDialog = true
                            },
                            enabled = !state.isProcessing
                        ) {
                            Icon(
                                Icons.Default.Settings,
                                contentDescription = "Output filter",
                                tint = Color(0xFF00FF00)
                            )
                        }
                        TextButton(
                            onClick = { viewModel.clear() },
                            enabled = !state.isProcessing
                        ) {
                            Text("Clear", color = Color.Gray)
                        }
                    }
                }
            }
        }
    }

    if (showFilterDialog) {
        TerminalFilterDialog(
            viewModel = viewModel,
            onDismiss = { showFilterDialog = false }
        )
    }
}
