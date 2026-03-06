package gui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.Button
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import core.AgentStatus
import core.ModelType

@Composable
fun StatusBar(
    status: AgentStatus,
    onSwitchClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp)
            .background(Color(0xFFE0E0E0))
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Model Indicator
        Row(verticalAlignment = Alignment.CenterVertically) {
            val color = when (status.currentModel) {
                ModelType.LOCAL -> Color.Green
                ModelType.CLOUD -> Color.Blue
                ModelType.SWITCHING -> Color.Yellow
                ModelType.ERROR -> Color.Red
            }
            Box(modifier = Modifier.size(12.dp).clip(CircleShape).background(color))
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = status.currentModel.name)
        }

        Button(onClick = onSwitchClick) {
            Text("SWITCH")
        }

        // Context Usage
        Row(verticalAlignment = Alignment.CenterVertically) {
            LinearProgressIndicator(progress = status.contextUsage, modifier = Modifier.width(100.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = "${(status.contextUsage * 100).toInt()}%")
        }

        // Files Loaded
        Text(text = "📁 ${status.filesLoaded}")

        // Session Time
        // Note: Duration formatting might need a helper or simple logic
        val minutes = status.sessionTime.toMinutes()
        val seconds = status.sessionTime.seconds % 60
        Text(text = "⏱️ %02d:%02d".format(minutes, seconds))
    }
}
