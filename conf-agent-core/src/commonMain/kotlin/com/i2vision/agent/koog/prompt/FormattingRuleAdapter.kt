/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.agent.koog.prompt

import com.i2vision.agent.config.FormattingRulesConfig

/**
 * Adapts i2vision formatting rules to Koog's output format expectations.
 * 
 * This adapter bridges the i2vision YAML formatting configuration to
 * Koog's output format system, enabling:
 * - Reasoning header format
 * - Tool call prefix format
 * - End-of-stream marker
 * - JSON/XML tool call support
 * - Response size limits
 * 
 * ## Usage
 * 
 * ```kotlin
 * val adapter = FormattingRuleAdapter()
 * val formatting = adapter.adapt(yamlConfig.formattingRules)
 * 
 * println(formatting.reasoningHeader)  // "reasoning"
 * println(formatting.toolCallPrefix)   // "tool_call"
 * println(formatting.allowJson)        // true
 * ```
 */
class FormattingRuleAdapter {
    
    /**
     * Adapt i2vision formatting rules to Koog output format.
     * 
     * @param rules i2vision formatting rules from YAML
     * @return Koog output format configuration
     */
    fun adapt(rules: FormattingRulesConfig): KoogOutputFormat {
        return KoogOutputFormat(
            reasoningHeader = rules.reasoningHeader.removeSuffix(":").trim(),
            toolCallPrefix = rules.toolCallHeader.removeSuffix(":").trim(),
            eosMarker = rules.eosMarker,
            allowJson = true,
            allowXml = true,
            maxResponseSize = rules.maxResponseSize,
            formatDescription = buildFormatDescription(rules),
            formatBrief = buildFormatBrief(rules)
        )
    }
    
    /**
     * Build detailed format description.
     */
    private fun buildFormatDescription(rules: FormattingRulesConfig): String {
        return buildString {
            appendLine("Your response should follow this format:")
            appendLine()
            appendLine("1. **Reasoning** (optional): Start with `${rules.reasoningHeader}` followed by your thinking process.")
            appendLine("2. **Tool Call** (optional): If you need to use a tool, use `${rules.toolCallHeader}` followed by JSON.")
            appendLine("3. **Response**: Provide your final answer or explanation.")
            appendLine("4. **End Marker**: End with `${rules.eosMarker}` when complete.")
            appendLine()
            appendLine("## Examples")
            appendLine()
            appendLine("### Example 1: Reasoning Only")
            appendLine("```")
            appendLine("${rules.reasoningHeader} I need to analyze the code structure first...")
            appendLine("The main entry point is in Main.kt...")
            appendLine("${rules.eosMarker}")
            appendLine("```")
            appendLine()
            appendLine("### Example 2: Tool Call")
            appendLine("```")
            appendLine("${rules.reasoningHeader} I should read the file to understand the current implementation.")
            appendLine("${rules.toolCallHeader} {\"tool\": \"read_file\", \"args\": {\"path\": \"src/main.kt\"}}")
            appendLine("```")
            appendLine()
            appendLine("### Example 3: JSON Tool Call")
            appendLine("```")
            appendLine("{\"tool\": \"write_file\", \"args\": {\"path\": \"src/test.kt\", \"content\": \"...\"}}")
            appendLine("```")
            appendLine()
            appendLine("### Example 4: XML Tool Call")
            appendLine("```")
            appendLine("<invoke name=\"read_file\">")
            appendLine("  <arg name=\"path\">src/main.kt</arg>")
            appendLine("</invoke>")
            appendLine("```")
        }
    }
    
    /**
     * Build brief format description.
     */
    private fun buildFormatBrief(rules: FormattingRulesConfig): String {
        return "Format: `${rules.reasoningHeader}` [reasoning], `${rules.toolCallHeader}` [JSON tool call], end with `${rules.eosMarker}`"
    }
    
    /**
     * Get default formatting rules for a VSLFC layer.
     * 
     * @param layer VSLFC layer
     * @return Default formatting rules
     */
    fun defaultsForLayer(layer: com.i2vision.agent.VslfcLayer): FormattingRulesConfig {
        return when (layer) {
            com.i2vision.agent.VslfcLayer.CODE -> FormattingRulesConfig(
                reasoningHeader = "reasoning:",
                toolCallHeader = "tool_call:",
                eosMarker = "[EOS]",
                maxResponseSize = 200000,
                formatDescription = "Use reasoning and tool_call headers",
                formatBrief = "reasoning: [...], tool_call: {...}"
            )
            com.i2vision.agent.VslfcLayer.FLOW -> FormattingRulesConfig(
                reasoningHeader = "analysis:",
                toolCallHeader = "invoke:",
                eosMarker = "[COMPLETE]",
                maxResponseSize = 150000,
                formatDescription = "Use analysis and invoke headers",
                formatBrief = "analysis: [...], invoke: {...}"
            )
            com.i2vision.agent.VslfcLayer.LOGIC -> FormattingRulesConfig(
                reasoningHeader = "reasoning:",
                toolCallHeader = "tool_call:",
                eosMarker = "[EOS]",
                maxResponseSize = 200000,
                formatDescription = "Use reasoning and tool_call headers",
                formatBrief = "reasoning: [...], tool_call: {...}"
            )
            com.i2vision.agent.VslfcLayer.STRUCTURE -> FormattingRulesConfig(
                reasoningHeader = "architecture:",
                toolCallHeader = "discover:",
                eosMarker = "[ANALYSIS_COMPLETE]",
                maxResponseSize = 100000,
                formatDescription = "Use architecture and discover headers",
                formatBrief = "architecture: [...], discover: {...}"
            )
            com.i2vision.agent.VslfcLayer.VISION -> FormattingRulesConfig(
                reasoningHeader = "vision:",
                toolCallHeader = "query:",
                eosMarker = "[VISION_COMPLETE]",
                maxResponseSize = 100000,
                formatDescription = "Use vision and query headers",
                formatBrief = "vision: [...], query: {...}"
            )
        }
    }
}

/**
 * Koog output format configuration.
 * 
 * @property reasoningHeader Header for reasoning section (e.g., "reasoning:")
 * @property toolCallPrefix Header for tool calls (e.g., "tool_call:")
 * @property eosMarker End-of-stream marker (e.g., "[EOS]")
 * @property allowJson Whether JSON tool calls are allowed
 * @property allowXml Whether XML tool calls are allowed
 * @property maxResponseSize Maximum response size in characters
 * @property formatDescription Detailed format description
 * @property formatBrief Brief format description
 */
data class KoogOutputFormat(
    val reasoningHeader: String,
    val toolCallPrefix: String,
    val eosMarker: String,
    val allowJson: Boolean,
    val allowXml: Boolean,
    val maxResponseSize: Int,
    val formatDescription: String,
    val formatBrief: String
) {
    /**
     * Check if a format is supported.
     */
    fun isFormatSupported(format: String): Boolean =
        format.lowercase() in listOf("json", "xml") &&
        (format.lowercase() == "json" && allowJson || format.lowercase() == "xml" && allowXml)
    
    /**
     * Get the expected format example.
     */
    fun getExample(): String {
        return buildString {
            appendLine("Example response:")
            appendLine()
            appendLine("$reasoningHeader I need to read the file first to understand the current implementation.")
            appendLine()
            appendLine("$toolCallPrefix {\"tool\": \"read_file\", \"args\": {\"path\": \"src/main.kt\"}}")
            appendLine()
            appendLine("Or using XML format:")
            appendLine("<invoke name=\"read_file\">")
            appendLine("  <arg name=\"path\">src/main.kt</arg>")
            appendLine("</invoke>")
            appendLine()
            appendLine(eosMarker)
        }
    }
}
