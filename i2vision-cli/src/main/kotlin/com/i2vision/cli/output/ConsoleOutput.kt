package com.i2vision.cli.output

import com.i2vision.discover.api.models.PipelineResult
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.DumperOptions

/**
 * ConsoleOutput - Formatted output for CLI.
 */
class ConsoleOutput {
    
    private val yaml = Yaml(DumperOptions().apply {
        defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
        indicatorIndent = 2
        indent = 4
    })
    
    /**
     * Format discovery result for console output.
     */
    fun formatDiscoveryResult(result: PipelineResult, format: String = "text"): String {
        return when (format.lowercase()) {
            "json" -> formatJson(result)
            "yaml" -> formatYaml(result)
            else -> formatText(result)
        }
    }
    
    private fun formatText(result: PipelineResult): String {
        val sb = StringBuilder()
        sb.appendLine("Discovery Results")
        sb.appendLine("=" .repeat(50))
        sb.appendLine()
        sb.appendLine("Status: ${if (result.success) "✓ SUCCESS" else "✗ FAILED"}")
        sb.appendLine("Artifacts: ${result.artifacts.size}")
        sb.appendLine("Errors: ${result.errors.size}")
        sb.appendLine()
        
        if (result.metadata.isNotEmpty()) {
            sb.appendLine("Metadata:")
            result.metadata.forEach { (key, value) ->
                sb.appendLine("  $key: $value")
            }
            sb.appendLine()
        }
        
        if (result.errors.isNotEmpty()) {
            sb.appendLine("Errors:")
            result.errors.forEach { error ->
                sb.appendLine("  - $error")
            }
            sb.appendLine()
        }
        
        return sb.toString()
    }
    
    private fun formatJson(result: PipelineResult): String {
        // Simple JSON representation
        val sb = StringBuilder()
        sb.appendLine("{")
        sb.appendLine("  \"success\": ${result.success},")
        sb.appendLine("  \"artifacts\": ${result.artifacts.size},")
        sb.appendLine("  \"errors\": ${result.errors.size},")
        sb.appendLine("  \"metadata\": {")
        result.metadata.entries.forEachIndexed { index, (key, value) ->
            val comma = if (index < result.metadata.size - 1) "," else ""
            sb.appendLine("    \"$key\": \"$value\"$comma")
        }
        sb.appendLine("  }")
        sb.appendLine("}")
        return sb.toString()
    }
    
    private fun formatYaml(result: PipelineResult): String {
        val data = mapOf(
            "success" to result.success,
            "artifacts" to result.artifacts.size,
            "errors" to result.errors.size,
            "metadata" to result.metadata
        )
        return yaml.dump(data)
    }
    
    /**
     * Format error message for console output.
     */
    fun formatError(message: String, cause: Throwable? = null): String {
        val sb = StringBuilder()
        sb.appendLine("Error: $message")
        if (cause != null) {
            sb.appendLine("Cause: ${cause.message}")
        }
        return sb.toString()
    }
    
    /**
     * Format success message for console output.
     */
    fun formatSuccess(message: String, details: Map<String, Any> = emptyMap()): String {
        val sb = StringBuilder()
        sb.appendLine("✓ $message")
        if (details.isNotEmpty()) {
            sb.appendLine()
            details.forEach { (key, value) ->
                sb.appendLine("  $key: $value")
            }
        }
        return sb.toString()
    }
}
