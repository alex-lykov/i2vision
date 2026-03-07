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
        // Model Indicator with Alert Badge
        Row(verticalAlignment = Alignment.CenterVertically) {
            val color = when (status.currentModel) {
                ModelType.LOCAL -> if (status.hasPerformanceAlert) Color.Red else Color.Green
                ModelType.CLOUD -> Color.Blue
                ModelType.SWITCHING -> Color.Yellow
                ModelType.ERROR -> Color.Red
            }
            Box(modifier = Modifier.size(12.dp).clip(CircleShape).background(color))
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = status.currentModel.name)
            if (status.hasPerformanceAlert) {
                Spacer(modifier = Modifier.width(4.dp))
                Text(text = "⚠️", color = Color.Red)
            }
        }

        // Response Time
        Text(
            text = "${status.avgResponseTimeMs}ms",
            color = when {
                status.avgResponseTimeMs > 15000 -> Color.Red
                status.avgResponseTimeMs > 5000 -> Color(0xFFFFA500) // Orange
                else -> Color.DarkGray
            }
        )

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
        val minutes = status.sessionTime.toMinutes()
        val seconds = status.sessionTime.seconds % 60
        Text(text = "⏱️ %02d:%02d".format(minutes, seconds))
    }

    // Performance Warnings (if any)
    if (status.performanceWarnings.isNotEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFFFFF3E0)) // Light orange background
                .padding(horizontal = 16.dp, vertical = 4.dp)
        ) {
            status.performanceWarnings.take(2).forEach { warning ->
                Text(
                    text = "⚠️ $warning",
                    color = Color(0xFFEF6C00), // Dark orange
                    style = androidx.compose.material.MaterialTheme.typography.caption
                )
            }
        }
    }
}
