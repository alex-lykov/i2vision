package gui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import core.OutputEvent
import core.TaskMode
import java.time.LocalTime
import java.time.format.DateTimeFormatter

@Composable
fun Terminal(
    events: List<OutputEvent>,
    onClear: () -> Unit,
    onSubmit: (String, TaskMode) -> Unit,
    modifier: Modifier = Modifier
) {
    var text by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(events.size) {
        if (events.isNotEmpty()) {
            listState.animateScrollToItem(events.size - 1)
        }
    }

    Card(
        modifier = modifier.fillMaxSize(),
        elevation = 2.dp,
        backgroundColor = Color(0xFF1E1E1E) // Dark terminal background
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Output area (takes all available space)
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
                    items(events) { event ->
                        val time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
                        val (styleInfo, message) = when (event) {
                            is OutputEvent.Standard -> (Color.White to "") to event.text
                            is OutputEvent.Success -> (Color.Green to "✓") to event.text
                            is OutputEvent.Warning -> (Color(0xFFFFA500) to "⚠") to event.text
                            is OutputEvent.Error -> (Color.Red to "✗") to event.text
                            is OutputEvent.System -> (Color.Cyan to "ℹ") to event.text
                            is OutputEvent.Debug -> (Color.Gray to "🐛") to event.text
                            is OutputEvent.Progress -> (Color.White to "⏳") to "[${event.percent}%] ${event.message}"
                            OutputEvent.Complete -> (Color.Green to "✓") to "Task Complete"
                        }
                        val (color, prefix) = styleInfo
                        Text(
                            text = if (prefix.isEmpty()) "[$time] $message" else "[$time] $prefix $message",
                            color = color,
                            style = MaterialTheme.typography.body2,
                            modifier = Modifier.padding(vertical = 1.dp)
                        )
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
                    TextField(
                        value = text,
                        onValueChange = { text = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Enter task (e.g., 'Add error handling')", color = Color.Gray) },
                        colors = TextFieldDefaults.textFieldColors(
                            textColor = Color.White,
                            backgroundColor = Color.Transparent,
                            cursorColor = Color.Green,
                            focusedIndicatorColor = Color.Green,
                            unfocusedIndicatorColor = Color(0xFF404040)
                        ),
                        singleLine = true
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
                                if (text.isNotBlank()) {
                                    onSubmit(text, TaskMode.CURRENT_MODEL)
                                    text = ""
                                }
                            },
                            colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF0D7377))
                        ) {
                            Text("PROCESS", color = Color.White)
                        }
                        Button(
                            onClick = { 
                                if (text.isNotBlank()) {
                                    onSubmit(text, TaskMode.SMART_ANALYZE)
                                    text = ""
                                }
                            },
                            colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF1470B8))
                        ) {
                            Text("ANALYZE", color = Color.White)
                        }
                        Button(
                            onClick = { 
                                if (text.isNotBlank()) {
                                    onSubmit(text, TaskMode.DEBUG)
                                    text = ""
                                }
                            },
                            colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFB8860B))
                        ) {
                            Text("DEBUG", color = Color.White)
                        }
                    }
                    
                    TextButton(onClick = onClear) {
                        Text("Clear", color = Color.Gray)
                    }
                }
            }
        }
    }
}
