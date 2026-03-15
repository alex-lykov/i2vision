package gui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import gui.data.TerminalDisplayOptionsDto
import gui.data.TerminalEventDto
import gui.data.TerminalEventPayload
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val cardBg = Color(0xFF2D2D2D)
private val borderColor = Color(0xFF404040)
private val monoFamily = FontFamily.Monospace

@Composable
fun TerminalEventCard(
    event: TerminalEventDto,
    showTimestamp: Boolean,
    compactMode: Boolean,
    modifier: Modifier = Modifier,
    displayOptions: TerminalDisplayOptionsDto? = null
) {
    val timeStr = if (showTimestamp && event.payload != null) {
        event.timestamp.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm:ss"))
    } else null
    val (color, icon) = eventColorAndIcon(event.type)
    val hasStructuredPayload = event.payload != null
    val padding = if (compactMode) 4.dp else 8.dp
    
    // Check local render options or global display options
    val renderRich = displayOptions?.renderRichToolCards != false
    val highlightParams = displayOptions?.highlightToolParams != false

    SelectionContainer {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = padding)
                .then(
                    if (hasStructuredPayload) Modifier
                        .background(cardBg, MaterialTheme.shapes.small)
                        .border(1.dp, borderColor, MaterialTheme.shapes.small)
                    else Modifier
                )
                .padding(padding)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = androidx.compose.ui.Alignment.Top
            ) {
                if (timeStr != null) {
                    Text(
                        text = "[$timeStr]",
                        color = Color.Gray,
                        style = MaterialTheme.typography.caption,
                        fontFamily = monoFamily
                    )
                }
                if (icon.isNotEmpty()) Text(text = icon, color = color, style = MaterialTheme.typography.caption)
                Column(modifier = Modifier.weight(1f)) {
                    when (val p = event.payload) {
                        is TerminalEventPayload.ToolCall -> ToolCallContent(p, renderRich, highlightParams)
                        is TerminalEventPayload.FileOp -> FileOpContent(p)
                        is TerminalEventPayload.FileDiff -> FileDiffContent(p)
                        is TerminalEventPayload.Decision -> DecisionContent(p)
                        is TerminalEventPayload.Thinking -> ThinkingContent(p)
                        null -> SimpleContent(event.type, event.message, event.progressPercent)
                    }
                }
                if (event.outputSettingKey != null) {
                    Text(
                        text = "⚙ ${event.outputSettingKey}",
                        color = Color.Gray,
                        style = MaterialTheme.typography.caption,
                        fontFamily = monoFamily
                    )
                }
            }
        }
    }
}

@Composable
private fun SimpleContent(type: TerminalEventDto.EventType, message: String, progressPercent: Int?) {
    val displayText = if (type == TerminalEventDto.EventType.PROGRESS && progressPercent != null) {
        "[$progressPercent%] $message"
    } else message
    Text(
        text = displayText,
        color = eventColorAndIcon(type).first,
        style = MaterialTheme.typography.body2
    )
}

@Composable
private fun ToolCallContent(
    p: TerminalEventPayload.ToolCall, 
    renderRich: Boolean, 
    highlightParams: Boolean
) {
    // The agent's JSON output for tool calls has changed.
    // It now wraps parameters in an "arguments" or "args" object.
    // We need to handle all formats for backward compatibility.
    val params = (p.params["arguments"] as? Map<String, Any>) 
        ?: (p.params["args"] as? Map<String, Any>)
        ?: p.params

    if (renderRich) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // Header with tool name
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(
                    text = "Tool: ${p.toolName}",
                    color = Color(0xFF4EC9B0),
                    style = MaterialTheme.typography.body2,
                    fontWeight = FontWeight.Bold,
                    fontFamily = monoFamily
                )
                Spacer(modifier = Modifier.weight(1f))
                p.durationMs?.let { 
                    Text(text = "${it}ms", color = Color.Gray, style = MaterialTheme.typography.caption) 
                }
            }
            
            // Parameters section
            if (params.isNotEmpty()) {
                if (highlightParams) {
                    val annotatedString = buildAnnotatedString {
                        params.entries.forEachIndexed { index, (k, v) ->
                            if (index > 0) append("\n")
                            withStyle(SpanStyle(color = Color(0xFF9CDCFE))) { append("$k: ") }
                            val vStr = v.toString().take(200) + if (v.toString().length > 200) "…" else ""
                            withStyle(SpanStyle(color = Color(0xFFCE9178))) { append(vStr) }
                        }
                    }
                    Text(text = annotatedString, style = MaterialTheme.typography.caption, fontFamily = monoFamily)
                } else {
                    val paramsStr = params.entries.joinToString("\n") { (k, v) ->
                        val vStr = v.toString().take(200) + if (v.toString().length > 200) "…" else ""
                        "$k: $vStr"
                    }
                    Text(text = paramsStr, color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.caption, fontFamily = monoFamily)
                }
            }
            
            // Result section
            p.result?.let { r ->
                val truncated = if (r.length > 500) r.take(500) + "…" else r
                Text(
                    text = "Result: $truncated", 
                    color = if (p.success == true) Color(0xFF4EC9B0) else Color.White, 
                    style = MaterialTheme.typography.caption,
                    fontFamily = monoFamily
                )
            }
        }
    } else {
        // Simple/compact rendering
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = "Tool: ${p.toolName}", color = Color(0xFF4EC9B0), style = MaterialTheme.typography.body2, fontFamily = monoFamily)
            if (params.isNotEmpty()) {
                val paramsStr = params.entries.joinToString(", ") { (k, v) ->
                    val vStr = v.toString().take(80) + if (v.toString().length > 80) "…" else ""
                    "$k: $vStr"
                }
                Text(text = "Params: $paramsStr", color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.caption)
            }
            p.result?.let { r ->
                val truncated = if (r.length > 300) r.take(300) + "…" else r
                Text(text = "Result: $truncated", color = if (p.success == true) Color(0xFF4EC9B0) else Color.White, style = MaterialTheme.typography.caption)
            }
            p.durationMs?.let { Text(text = "${it}ms", color = Color.Gray, style = MaterialTheme.typography.caption) }
        }
    }
}

@Composable
private fun FileOpContent(p: TerminalEventPayload.FileOp) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = "${p.operation.uppercase()}: ${p.path}", color = Color(0xFF569CD6), style = MaterialTheme.typography.body2, fontFamily = monoFamily)
        p.contentPreview?.let { Text(text = it.take(200) + if (it.length > 200) "…" else "", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.caption) }
        p.bytesWritten?.let { Text(text = "$it bytes", color = Color.Gray, style = MaterialTheme.typography.caption) }
        p.success?.let { s -> Text(text = if (s) "✓ Success" else "✗ Failed", color = if (s) Color.Green else Color.Red, style = MaterialTheme.typography.caption) }
    }
}

@Composable
private fun FileDiffContent(p: TerminalEventPayload.FileDiff) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = "Diff: ${p.path}", color = Color(0xFF569CD6), style = MaterialTheme.typography.body2, fontFamily = monoFamily)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (p.addedLines > 0) Text(text = "+${p.addedLines}", color = Color.Green, style = MaterialTheme.typography.caption)
            if (p.removedLines > 0) Text(text = "-${p.removedLines}", color = Color.Red, style = MaterialTheme.typography.caption)
        }
        p.oldPreview?.let { Text(text = "Before: $it", color = Color.Red.copy(alpha = 0.8f), style = MaterialTheme.typography.caption, fontFamily = monoFamily) }
        p.newPreview?.let { Text(text = "After: $it", color = Color.Green.copy(alpha = 0.8f), style = MaterialTheme.typography.caption, fontFamily = monoFamily) }
    }
}

@Composable
private fun DecisionContent(p: TerminalEventPayload.Decision) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = "${p.phase}: ${p.message}", color = Color(0xFFDCDCAA), style = MaterialTheme.typography.body2)
        p.details?.entries?.take(5)?.forEach { (k, v) ->
            Text(text = "  $k: $v", color = Color.Gray, style = MaterialTheme.typography.caption)
        }
    }
}

@Composable
private fun ThinkingContent(p: TerminalEventPayload.Thinking) {
    Text(text = p.text.take(500) + if (p.text.length > 500) "…" else "", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.body2)
}

private fun eventColorAndIcon(type: TerminalEventDto.EventType): Pair<Color, String> = when (type) {
    TerminalEventDto.EventType.STANDARD -> Color.White to ""
    TerminalEventDto.EventType.SUCCESS -> Color.Green to "✓"
    TerminalEventDto.EventType.WARNING -> Color(0xFFFFA500) to "⚠"
    TerminalEventDto.EventType.ERROR -> Color.Red to "✗"
    TerminalEventDto.EventType.SYSTEM -> Color.Cyan to "ℹ"
    TerminalEventDto.EventType.DEBUG -> Color.Gray to "🐛"
    TerminalEventDto.EventType.PROGRESS -> Color.White to "⏳"
    TerminalEventDto.EventType.COMPLETE -> Color.Green to "✓"
    TerminalEventDto.EventType.TOOL_CALL -> Color(0xFF4EC9B0) to "🔧"
    TerminalEventDto.EventType.FILE_OP -> Color(0xFF569CD6) to "📄"
    TerminalEventDto.EventType.FILE_DIFF -> Color(0xFF569CD6) to "📝"
    TerminalEventDto.EventType.DECISION -> Color(0xFFDCDCAA) to "📋"
    TerminalEventDto.EventType.THINKING -> Color.White.copy(alpha = 0.7f) to "💭"
}
