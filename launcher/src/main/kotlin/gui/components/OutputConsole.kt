package gui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.Button
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import core.OutputEvent
import java.time.LocalTime
import java.time.format.DateTimeFormatter

@Composable
fun OutputConsole(
    events: List<OutputEvent>,
    onClear: () -> Unit
) {
    val listState = rememberLazyListState()

    LaunchedEffect(events.size) {
        if (events.isNotEmpty()) {
            listState.animateScrollToItem(events.size - 1)
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color.Black)
                .padding(8.dp)
        ) {
            LazyColumn(state = listState) {
                items(events) { event ->
                    val time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
                    val (color, text) = when (event) {
                        is OutputEvent.Standard -> Color.White to event.text
                        is OutputEvent.Success -> Color.Green to "✓ ${event.text}"
                        is OutputEvent.Warning -> Color.Yellow to "⚠ ${event.text}"
                        is OutputEvent.Error -> Color.Red to "✗ ${event.text}"
                        is OutputEvent.System -> Color.Cyan to "ℹ ${event.text}"
                        is OutputEvent.Debug -> Color.Gray to "🐛 ${event.text}"
                        is OutputEvent.Progress -> Color.White to "⏳ [${event.percent}%] ${event.message}"
                        OutputEvent.Complete -> Color.Green to "✓ Task Complete"
                    }
                    Text(
                        text = "[$time] $text",
                        color = color,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row {
            Button(onClick = onClear) { Text("Clear") }
        }
    }
}
