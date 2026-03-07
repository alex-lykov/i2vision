package gui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.Button
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun QuickActions(
    onAction: (String) -> Unit
) {
    RightPanelCard(
        title = "Quick Actions",
        modifier = Modifier.padding(8.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onAction("Review code") }, modifier = Modifier.weight(1f)) { Text("Review") }
            Button(onClick = { onAction("Refactor code") }, modifier = Modifier.weight(1f)) { Text("Refactor") }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onAction("Write tests") }, modifier = Modifier.weight(1f)) { Text("Test") }
            Button(onClick = { onAction("Document code") }, modifier = Modifier.weight(1f)) { Text("Document") }
        }
    }
}
