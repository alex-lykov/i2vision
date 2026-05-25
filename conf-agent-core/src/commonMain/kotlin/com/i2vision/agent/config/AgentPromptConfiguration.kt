/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.agent.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Complete agent configuration from YAML file.
 * 
 * This is the 15-section configuration format used by i2-vision agents.
 * It bridges legacy configuration to the new I2VisionAgent interfaces.
 * 
 * Configuration sections:
 * 1. **Metadata** - key, agentType, version, isActive
 * 2. **System Prompt** - systemPromptTemplate, templateVariables
 * 3. **Rules** - ruleSetKeys
 * 4. **Model** - provider, id, contextLength, temperature
 * 5. **LLM** - retries, timeout, streaming
 * 6. **Formatting Rules** - code block formatting, indentation
 * 7. **Iteration Settings** - maxIterations, maxConsecutiveToolCalls
 * 8. **Tool Selection** - available tools, timeouts
 * 9. **Safety** - file operation restrictions, build verification
 * 10. **Parsing** - response parsing rules
 * 11. **Discovery** - cluster context, cache usage
 * 12. **Execution** - build commands, file operation mode
 * 13. **Formatting** - output formatting preferences
 * 14. **Streaming** - streaming behavior configuration
 * 15. **MCP** - MCP server configuration
 * 
 * @property key Unique configuration identifier
 * @property agentType VSLFC layer type (CODE, FLOW, LOGIC, STRUCTURE, VISION)
 * @property version Configuration version
 * @property isActive Whether this configuration is active
 * @property systemPromptTemplate System prompt with variable placeholders
 * @property templateVariables Variable substitutions for the prompt
 * @property ruleSetKeys References to rule sets
 * @property model Model configuration
 * @property llm LLM connection settings
 * @property formattingRules Code formatting rules
 * @property iterationSettings Iteration limits and behavior
 * @property toolSelection Tool configuration
 * @property safety Safety and security settings
 * @property parsing Response parsing configuration
 * @property discovery Discovery cache settings
 * @property execution Execution environment settings
 * @property formatting Output formatting preferences
 * @property streaming Streaming behavior
 * @property mcp MCP server configuration
 */
@Serializable
data class AgentPromptConfiguration(
    @SerialName("key")
    val key: String,
    
    @SerialName("agentType")
    val agentType: String,
    
    @SerialName("version")
    val version: String,
    
    @SerialName("isActive")
    val isActive: Boolean = true,
    
    @SerialName("systemPromptTemplate")
    val systemPromptTemplate: String,
    
    @SerialName("templateVariables")
    val templateVariables: Map<String, String> = emptyMap(),
    
    @SerialName("ruleSetKeys")
    val ruleSetKeys: List<String> = emptyList(),
    
    @SerialName("model")
    val model: ModelConfig,
    
    @SerialName("llm")
    val llm: LlmConfig,
    
    @SerialName("formattingRules")
    val formattingRules: FormattingRulesConfig,
    
    @SerialName("iterationSettings")
    val iterationSettings: IterationConfig,
    
    @SerialName("toolSelection")
    val toolSelection: ToolSelectionConfig,
    
    @SerialName("safety")
    val safety: SafetyConfig,
    
    @SerialName("parsing")
    val parsing: ParsingConfig,
    
    @SerialName("discovery")
    val discovery: DiscoveryConfig,
    
    @SerialName("execution")
    val execution: ExecutionConfig,
    
    @SerialName("formatting")
    val formatting: FormattingConfig,
    
    @SerialName("streaming")
    val streaming: StreamingConfig,
    
    @SerialName("mcp")
    val mcp: McpConfig
) {
    /**
     * Check if this configuration is for a specific agent type.
     */
    fun isForAgentType(type: String): Boolean =
        agentType.equals(type, ignoreCase = true)
    
    /**
     * Get the system prompt with variables substituted.
     */
    fun getRenderedPrompt(customVariables: Map<String, String> = emptyMap()): String {
        val allVariables = templateVariables + customVariables
        var rendered = systemPromptTemplate
        allVariables.forEach { (key, value) ->
            rendered = rendered.replace("{{$key}}", value)
        }
        return rendered
    }
    
    companion object {
        /**
         * Validate that required fields are present.
         */
        fun validate(config: AgentPromptConfiguration): List<String> {
            val errors = mutableListOf<String>()
            
            if (config.key.isBlank()) errors.add("key is required")
            if (config.agentType.isBlank()) errors.add("agentType is required")
            if (config.version.isBlank()) errors.add("version is required")
            if (config.systemPromptTemplate.isBlank()) errors.add("systemPromptTemplate is required")
            if (config.model.provider.isBlank()) errors.add("model.provider is required")
            if (config.model.id.isBlank()) errors.add("model.id is required")
            
            return errors
        }
    }
}

/**
 * Model configuration (LLM provider and settings).
 */
@Serializable
data class ModelConfig(
    @SerialName("provider")
    val provider: String,
    
    @SerialName("id")
    val id: String,
    
    @SerialName("contextLength")
    val contextLength: Int = 8192,
    
    @SerialName("temperature")
    val temperature: Double = 0.7,
    
    @SerialName("topP")
    val topP: Double = 0.9,
    
    @SerialName("topK")
    val topK: Int = 40,
    
    @SerialName("maxTokens")
    val maxTokens: Int = 4096
)

/**
 * LLM connection and behavior settings.
 */
@Serializable
data class LlmConfig(
    @SerialName("retries")
    val retries: Int = 3,
    
    @SerialName("timeoutSeconds")
    val timeoutSeconds: Long = 60,
    
    @SerialName("streaming")
    val streaming: Boolean = true,
    
    @SerialName("baseUrl")
    val baseUrl: String? = null,
    
    @SerialName("apiKey")
    val apiKey: String? = null
)

/**
 * Formatting rules configuration.
 * 
 * Combines two types of formatting:
 * 1. **Code formatting** - Indentation, line length, whitespace (for generated code)
 * 2. **Agent response formatting** - Reasoning headers, tool call markers, EOS markers (for agent output)
 */
@Serializable
data class FormattingRulesConfig(
    // === Code formatting (for generated code) ===
    @SerialName("indentSize")
    val indentSize: Int = 4,
    
    @SerialName("useTabs")
    val useTabs: Boolean = false,
    
    @SerialName("maxLineLength")
    val maxLineLength: Int = 120,
    
    @SerialName("trimTrailingWhitespace")
    val trimTrailingWhitespace: Boolean = true,
    
    @SerialName("insertFinalNewline")
    val insertFinalNewline: Boolean = true,
    
    // === Agent response formatting (for agent output) ===
    @SerialName("rules")
    val rules: String = "OUTPUT FORMAT (STRICT): Use header lines: reasoning: <text> and tool_call: <json>",
    
    @SerialName("brief")
    val brief: String = "reasoning: <text> | tool_call: {\"tool\":\"...\",\"args\":{...}} | EOS",
    
    @SerialName("reasoningHeader")
    val reasoningHeader: String = "reasoning:",
    
    @SerialName("toolCallHeader")
    val toolCallHeader: String = "tool_call:",
    
    @SerialName("eosMarker")
    val eosMarker: String = "EOS",
    
    @SerialName("maxResponseSize")
    val maxResponseSize: Int = 200000,
    
    @SerialName("maxProseChars")
    val maxProseChars: Int = 400
)

/**
 * Iteration limits and behavior.
 */
@Serializable
data class IterationConfig(
    @SerialName("maxIterations")
    val maxIterations: Int = 10,
    
    @SerialName("maxConsecutiveToolCalls")
    val maxConsecutiveToolCalls: Int = 12,
    
    @SerialName("enableKickstart")
    val enableKickstart: Boolean = true,
    
    @SerialName("kickstartMinInvalidOutputs")
    val kickstartMinInvalidOutputs: Int = 2,
    
    @SerialName("reflectionEnabled")
    val reflectionEnabled: Boolean = true,
    
    @SerialName("selfCorrectionEnabled")
    val selfCorrectionEnabled: Boolean = true
)

/**
 * Tool selection and configuration.
 */
@Serializable
data class ToolSelectionConfig(
    @SerialName("enabledTools")
    val enabledTools: List<String> = emptyList(),
    
    @SerialName("disabledTools")
    val disabledTools: List<String> = emptyList(),
    
    @SerialName("toolTimeoutSeconds")
    val toolTimeoutSeconds: Long = 30,
    
    @SerialName("requireConfirmationFor")
    val requireConfirmationFor: List<String> = emptyList(),
    
    @SerialName("readOnlyMode")
    val readOnlyMode: Boolean = false
)

/**
 * Safety and security settings.
 */
@Serializable
data class SafetyConfig(
    @SerialName("allowFileWrites")
    val allowFileWrites: Boolean = true,
    
    @SerialName("allowedDirectories")
    val allowedDirectories: List<String> = emptyList(),
    
    @SerialName("forbiddenDirectories")
    val forbiddenDirectories: List<String> = emptyList(),
    
    @SerialName("enableBuildVerification")
    val enableBuildVerification: Boolean = false,
    
    @SerialName("maxFileSize")
    val maxFileSize: Long = 1024 * 1024, // 1MB
    
    @SerialName("requireBackupBeforeWrite")
    val requireBackupBeforeWrite: Boolean = true
)

/**
 * Discovery cache and cluster context settings.
 */
@Serializable
data class DiscoveryConfig(
    @SerialName("enableClusterContext")
    val enableClusterContext: Boolean = true,
    
    @SerialName("cachePath")
    val cachePath: String? = null,
    
    @SerialName("autoRefresh")
    val autoRefresh: Boolean = false,
    
    @SerialName("refreshIntervalMinutes")
    val refreshIntervalMinutes: Int = 60,
    
    @SerialName("maxCacheAgeHours")
    val maxCacheAgeHours: Int = 24
)

/**
 * Execution environment settings.
 */
@Serializable
data class ExecutionConfig(
    @SerialName("buildCommand")
    val buildCommand: String = "./gradlew compileKotlin",
    
    @SerialName("fileOperationMode")
    val fileOperationMode: String = "DIRECT", // DIRECT or SHELL
    
    @SerialName("workingDirectory")
    val workingDirectory: String? = null,
    
    @SerialName("environmentVariables")
    val environmentVariables: Map<String, String> = emptyMap(),
    
    @SerialName("shellPath")
    val shellPath: String? = null
)

/**
 * Output formatting preferences.
 */
@Serializable
data class FormattingConfig(
    @SerialName("includeReasoningTrace")
    val includeReasoningTrace: Boolean = true,
    
    @SerialName("includeToolCallDetails")
    val includeToolCallDetails: Boolean = true,
    
    @SerialName("compactMode")
    val compactMode: Boolean = false,
    
    @SerialName("syntaxHighlighting")
    val syntaxHighlighting: Boolean = true
)

/**
 * Streaming behavior configuration.
 */
@Serializable
data class StreamingConfig(
    @SerialName("enabled")
    val enabled: Boolean = true,
    
    @SerialName("chunkSize")
    val chunkSize: Int = 100,
    
    @SerialName("flushIntervalMs")
    val flushIntervalMs: Long = 50,
    
    @SerialName("emitReasoning")
    val emitReasoning: Boolean = true,
    
    @SerialName("emitToolCalls")
    val emitToolCalls: Boolean = true,
    
    @SerialName("emitProgress")
    val emitProgress: Boolean = true
)

/**
 * MCP server configuration.
 */
@Serializable
data class McpConfig(
    @SerialName("enabled")
    val enabled: Boolean = false,
    
    @SerialName("serverUrl")
    val serverUrl: String? = null,
    
    @SerialName("apiKey")
    val apiKey: String? = null,
    
    @SerialName("injectClusterContext")
    val injectClusterContext: Boolean = true,
    
    @SerialName("timeoutSeconds")
    val timeoutSeconds: Long = 30,
    
    @SerialName("servers")
    val servers: List<String> = emptyList()
)
