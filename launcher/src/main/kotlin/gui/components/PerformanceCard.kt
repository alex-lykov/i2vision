package gui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.Divider
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import gui.data.AgentStatusDto
import gui.data.ModelTypeDto

@Composable
fun PerformanceCard(
    status: AgentStatusDto,
    modifier: Modifier = Modifier
) {
    val statusColor = when (status.currentModel) {
        ModelTypeDto.LOCAL -> if (status.hasPerformanceAlert) Color.Red else Color.Green
        ModelTypeDto.CLOUD -> Color.Blue
        ModelTypeDto.SWITCHING -> Color.Yellow
        ModelTypeDto.ERROR -> Color.Red
        ModelTypeDto.CLOUD_OLLAMA -> Color.Cyan
        ModelTypeDto.CLOUD_HF -> Color.Magenta
        ModelTypeDto.CLOUD_REPLICATE -> Color(0xFF9C27B0)
        ModelTypeDto.CLOUD_ANYSCALE -> Color(0xFF3F51B5)
    }

    RightPanelCardWithStatus(
        title = "Performance",
        statusText = status.currentModelId.takeIf { it.isNotBlank() } ?: status.currentModel.name,
        statusColor = statusColor,
        modifier = modifier
    ) {
        Column {
            // Model and Response Time Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Model Indicator
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(statusColor)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = status.currentModelId.takeIf { it.isNotBlank() } ?: status.currentModel.name,
                        style = MaterialTheme.typography.body2
                    )
                    if (status.hasPerformanceAlert) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(text = "⚠️", color = Color.Red, style = MaterialTheme.typography.caption)
                    }
                }

                // Response Time
                Text(
                    text = "${status.avgResponseTimeMs}ms",
                    style = MaterialTheme.typography.body2,
                    color = when {
                        status.avgResponseTimeMs > 15000 -> Color.Red
                        status.avgResponseTimeMs > 5000 -> Color(0xFFFFA500)
                        else -> MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                    }
                )
            }

            Spacer(Modifier.height(6.dp))

            // Context Usage and Files Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Context Usage
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Context: ",
                        style = MaterialTheme.typography.caption,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                    )
                    LinearProgressIndicator(
                        progress = status.contextUsage,
                        modifier = Modifier.width(80.dp).height(4.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "${(status.contextUsage * 100).toInt()}%",
                        style = MaterialTheme.typography.caption
                    )
                }

                // Files and Session Time
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "📁 ${status.filesLoaded}",
                        style = MaterialTheme.typography.caption,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                    )
                    val minutes = status.sessionTime.toMinutes()
                    val seconds = status.sessionTime.seconds % 60
                    Text(
                        text = "⏱️ %02d:%02d".format(minutes, seconds),
                        style = MaterialTheme.typography.caption,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                    )
                }
            }

            // Performance Warnings (if any)
            if (status.performanceWarnings.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFFFF3E0), shape = MaterialTheme.shapes.small)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    status.performanceWarnings.take(2).forEach { warning ->
                        Text(
                            text = "⚠️ $warning",
                            color = Color(0xFFEF6C00),
                            style = MaterialTheme.typography.caption
                        )
                    }
                }
            }
            
            // Enhanced Monitoring Section
            Spacer(Modifier.height(8.dp))
            Divider(color = MaterialTheme.colors.onSurface.copy(alpha = 0.1f))
            Spacer(Modifier.height(8.dp))
            
            // Tokens and Requests
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "Requests: ${status.totalRequests}",
                        style = MaterialTheme.typography.caption,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f)
                    )
                    Text(
                        text = "Tokens: ${status.totalTokensUsed}/${status.totalTokensGenerated}",
                        style = MaterialTheme.typography.caption,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f)
                    )
                }
                if (status.maxResponseTimeMs > 0) {
                    Text(
                        text = "Max: ${status.maxResponseTimeMs}ms",
                        style = MaterialTheme.typography.caption,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f)
                    )
                }
            }
            
            // Tool Usage Stats
            status.toolUsageStats?.let { toolStats ->
                if (toolStats.totalToolCalls > 0) {
                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "🔧 Tools: ${toolStats.totalToolCalls}",
                            style = MaterialTheme.typography.caption,
                            color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f)
                        )
                        Text(
                            text = "${(toolStats.successRate * 100).toInt()}% success",
                            style = MaterialTheme.typography.caption,
                            color = if (toolStats.successRate > 0.9f) Color.Green else Color(0xFFFFA500)
                        )
                    }
                }
            }
            
            // File Access Stats
            status.fileAccessStats?.let { fileStats ->
                if (fileStats.totalFileOperations > 0) {
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "📁 Files: ${fileStats.totalFileOperations}",
                            style = MaterialTheme.typography.caption,
                            color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f)
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (fileStats.readOperations > 0) {
                                Text(
                                    text = "R:${fileStats.readOperations}",
                                    style = MaterialTheme.typography.caption,
                                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                                )
                            }
                            if (fileStats.writeOperations > 0) {
                                Text(
                                    text = "W:${fileStats.writeOperations}",
                                    style = MaterialTheme.typography.caption,
                                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                                )
                            }
                            if (fileStats.searchOperations > 0) {
                                Text(
                                    text = "S:${fileStats.searchOperations}",
                                    style = MaterialTheme.typography.caption,
                                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                                )
                            }
                        }
                    }
                }
            }
            
            // Project and MCP Info
            if (status.projectPath != null || status.mcpToolsCount > 0) {
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    if (status.projectPath != null) {
                        Text(
                            text = "📂 ${status.projectPath.split("/").last()}",
                            style = MaterialTheme.typography.caption,
                            color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                        )
                    }
                    if (status.mcpToolsCount > 0) {
                        Text(
                            text = "🔌 MCP: ${status.mcpToolsCount}",
                            style = MaterialTheme.typography.caption,
                            color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }
            }
        }
    }
}
