package gui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.Button
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun TaskInput(
    onProcess: (String) -> Unit,
    onAnalyze: (String) -> Unit,
    onDebug: (String) -> Unit
) {
    var text by remember { mutableStateOf("") }

    Column(modifier = Modifier.padding(16.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth().height(100.dp),
            placeholder = { Text("What should I do? (e.g., 'Add error handling to login')") }
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onProcess(text) }) { Text("PROCESS") }
            Button(onClick = { onAnalyze(text) }) { Text("ANALYZE") }
            Button(onClick = { onDebug(text) }) { Text("DEBUG") }
        }
    }
}
