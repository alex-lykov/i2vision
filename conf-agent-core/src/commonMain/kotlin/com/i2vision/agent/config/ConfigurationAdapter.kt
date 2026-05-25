/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.agent.config

import com.i2vision.agent.AgentCapabilities
import com.i2vision.agent.AgentConfig
import com.i2vision.agent.FileOperationMode
import com.i2vision.agent.ToolCategory
import com.i2vision.agent.ToolInfo
import com.i2vision.agent.VslfcLayer

/**
 * Bridges legacy AgentPromptConfiguration to new I2VisionAgent interfaces.
 * 
 * This adapter converts the 15-section YAML configuration format to:
 * - AgentConfig (runtime settings)
 * - AgentCapabilities (capability declaration)
 * - Koog-specific configurations (for task-14, task-15, task-16)
 * 
 * Example usage:
 * ```kotlin
 * val yamlConfig = YamlConfigLoader.load("coding-agent.yaml")
 * val adapter = ConfigurationAdapter()
 * 
 * // Convert to new AgentConfig
 * val agentConfig = adapter.toAgentConfig(yamlConfig)
 * 
 * // Convert to capabilities
 * val capabilities = adapter.toCapabilities(yamlConfig)
 * 
 * // Convert to Koog prompt config
 * val koogPromptConfig = adapter.toKoogPromptConfig(yamlConfig)
 * ```
 */
class ConfigurationAdapter {
    
    /**
     * Convert YAML configuration to AgentConfig.
     * 
     * Maps iteration settings, safety settings, and execution settings
     * to the new AgentConfig data class.
     */
    fun toAgentConfig(yaml: AgentPromptConfiguration): AgentConfig {
        val fileOpMode = when (yaml.execution.fileOperationMode.uppercase()) {
            "SHELL" -> FileOperationMode.SHELL
            else -> FileOperationMode.DIRECT
        }
        
        val layer = parseAgentType(yaml.agentType)
        val displayName = createAgentDisplayName(layer, yaml.model.id, yaml.model.provider)
        
        return AgentConfig(
            id = yaml.key,
            displayName = displayName,
            description = null,
            layer = layer,
            maxIterations = yaml.iterationSettings.maxIterations,
            maxConsecutiveToolCalls = yaml.iterationSettings.maxConsecutiveToolCalls,
            toolTimeoutSeconds = yaml.toolSelection.toolTimeoutSeconds,
            enableBuildVerification = yaml.safety.enableBuildVerification,
            buildCommand = yaml.execution.buildCommand,
            fileOperationMode = fileOpMode,
            enableKickstart = yaml.iterationSettings.enableKickstart,
            kickstartMinInvalidOutputs = yaml.iterationSettings.kickstartMinInvalidOutputs,
            injectClusterContext = yaml.mcp.injectClusterContext,
            useDiscoveryCache = yaml.discovery.enableClusterContext,
            maxContextTokens = yaml.model.contextLength,
            modelProvider = yaml.model.provider,
            modelId = yaml.model.id
        )
    }
    
    /**
     * Convert YAML configuration to AgentCapabilities.
     * 
     * Builds capability declaration from model config, tool selection,
     * and streaming settings.
     */
    fun toCapabilities(yaml: AgentPromptConfiguration): AgentCapabilities {
        val layer = parseAgentType(yaml.agentType)
        
        val tools = yaml.toolSelection.enabledTools.map { toolName ->
            ToolInfo(
                name = toolName,
                description = getToolDescription(toolName),
                category = categorizeTool(toolName),
                isReadOnly = isReadOnlyTool(toolName)
            )
        }
        
        return AgentCapabilities(
            supportsStreaming = yaml.streaming.enabled,
            supportsCancellation = true,
            supportsRuntimeConfig = true,
            maxContextTokens = yaml.model.contextLength.toLong(),
            availableTools = tools,
            supportedLayers = listOf(layer),
            modelProvider = yaml.model.provider,
            modelId = yaml.model.id
        )
    }
    
    /**
     * Convert YAML configuration to KoogPromptConfig.
     * 
     * Creates Koog-specific prompt template configuration from
     * system prompt template, variables, and formatting rules.
     * 
     * This is used by task-15 (Koog PromptTemplate Integration).
     * 
     * Note: systemPrompt is stored as a TEMPLATE (unrendered) so that
     * dynamic variables like {{$workspaceRoot}} and {{$currentFile}}
     * can be rendered at runtime with actual values from the request context.
     */
    fun toKoogPromptConfig(yaml: AgentPromptConfiguration): KoogPromptConfig {
        return KoogPromptConfig(
            systemPromptTemplate = yaml.systemPromptTemplate,
            staticTemplateVariables = yaml.templateVariables,
            ruleSetKeys = yaml.ruleSetKeys,
            temperature = yaml.model.temperature,
            topP = yaml.model.topP,
            topK = yaml.model.topK,
            maxTokens = yaml.model.maxTokens,
            formattingRules = KoogFormattingRules(
                indentSize = yaml.formattingRules.indentSize,
                useTabs = yaml.formattingRules.useTabs,
                maxLineLength = yaml.formattingRules.maxLineLength,
                trimTrailingWhitespace = yaml.formattingRules.trimTrailingWhitespace,
                insertFinalNewline = yaml.formattingRules.insertFinalNewline
            ),
            parsingConfig = KoogParsingConfig(
                strictJsonParsing = yaml.parsing.strictJsonParsing,
                allowMarkdownCodeBlocks = yaml.parsing.allowMarkdownCodeBlocks,
                fallbackToPlainText = yaml.parsing.fallbackToPlainText,
                maxParseAttempts = yaml.parsing.maxParseAttempts
            )
        )
    }
    
    /**
     * Convert YAML configuration to KoogStrategyConfig.
     * 
     * Creates Koog GraphStrategy configuration from iteration settings
     * and LLM configuration.
     * 
     * This is used by task-14 (Koog GraphStrategy).
     */
    fun toKoogStrategyConfig(yaml: AgentPromptConfiguration): KoogStrategyConfig {
        return KoogStrategyConfig(
            maxIterations = yaml.iterationSettings.maxIterations,
            maxConsecutiveToolCalls = yaml.iterationSettings.maxConsecutiveToolCalls,
            reflectionEnabled = yaml.iterationSettings.reflectionEnabled,
            selfCorrectionEnabled = yaml.iterationSettings.selfCorrectionEnabled,
            llmRetries = yaml.llm.retries,
            llmTimeoutSeconds = yaml.llm.timeoutSeconds,
            streamingEnabled = yaml.llm.streaming
        )
    }
    
    /**
     * Convert YAML configuration to KoogToolRegistryConfig.
     * 
     * Creates Koog ToolRegistry configuration from tool selection
     * and safety settings.
     * 
     * This is used by task-16 (ToolRegistry).
     */
    fun toKoogToolRegistryConfig(yaml: AgentPromptConfiguration): KoogToolRegistryConfig {
        return KoogToolRegistryConfig(
            enabledTools = yaml.toolSelection.enabledTools,
            disabledTools = yaml.toolSelection.disabledTools,
            toolTimeoutSeconds = yaml.toolSelection.toolTimeoutSeconds,
            requireConfirmationFor = yaml.toolSelection.requireConfirmationFor,
            readOnlyMode = yaml.toolSelection.readOnlyMode,
            allowFileWrites = yaml.safety.allowFileWrites,
            allowedDirectories = yaml.safety.allowedDirectories,
            forbiddenDirectories = yaml.safety.forbiddenDirectories,
            maxFileSize = yaml.safety.maxFileSize,
            requireBackupBeforeWrite = yaml.safety.requireBackupBeforeWrite
        )
    }
    
    /**
     * Parse agent type string to VslfcLayer.
     */
    private fun parseAgentType(agentType: String): VslfcLayer =
        VslfcLayer.fromStringOrNull(agentType) ?: VslfcLayer.CODE
    
    /**
     * Create a display name for an agent configuration.
     */
    private fun createAgentDisplayName(layer: VslfcLayer, modelId: String, provider: String): String =
        "${layer.name} Agent ($provider $modelId)"
    
    /**
     * Get description for a tool by name.
     */
    private fun getToolDescription(toolName: String): String = when (toolName) {
        "read_file" -> "Read contents of a file"
        "write_file" -> "Write content to a file"
        "list_directory" -> "List files in a directory"
        "search_files" -> "Search for a pattern in files"
        "run_command" -> "Execute a terminal command"
        "i2vision_discover" -> "Discover code structure"
        "i2vision_get_context" -> "Get VSLFC context"
        "i2vision_validate_contracts" -> "Validate VSLFC contracts"
        "i2vision_search_symbols" -> "Search for symbol definitions"
        else -> "Tool: $toolName"
    }
    
    /**
     * Categorize a tool by name.
     */
    private fun categorizeTool(toolName: String): ToolCategory = when {
        toolName in listOf("read_file", "write_file", "list_directory") -> ToolCategory.FILE_SYSTEM
        toolName in listOf("i2vision_discover", "i2vision_get_context") -> ToolCategory.DISCOVERY
        toolName == "i2vision_validate_contracts" -> ToolCategory.CONTRACT
        toolName in listOf("i2vision_search_symbols", "i2vision_analyze_dependencies") -> ToolCategory.CODE_ANALYSIS
        else -> ToolCategory.CONTROL
    }
    
    /**
     * Check if a tool is read-only.
     */
    private fun isReadOnlyTool(toolName: String): Boolean = when (toolName) {
        "read_file", "list_directory", "search_files",
        "i2vision_discover", "i2vision_get_context",
        "i2vision_validate_contracts", "i2vision_search_symbols" -> true
        else -> false
    }
}

/**
 * Koog-specific prompt template configuration.
 * 
 * @property systemPromptTemplate System prompt TEMPLATE with variable placeholders (e.g., {{$workspaceRoot}})
 * @property staticTemplateVariables Static variable substitutions from config (rendered at load time)
 * @property ruleSetKeys References to rule sets
 * @property temperature LLM temperature
 * @property topP Top-p sampling parameter
 * @property topK Top-k sampling parameter
 * @property maxTokens Maximum tokens to generate
 * @property formattingRules Code formatting rules
 * @property parsingConfig Response parsing configuration
 */
data class KoogPromptConfig(
    val systemPromptTemplate: String,
    val staticTemplateVariables: Map<String, String>,
    val ruleSetKeys: List<String>,
    val temperature: Double,
    val topP: Double,
    val topK: Int,
    val maxTokens: Int,
    val formattingRules: KoogFormattingRules,
    val parsingConfig: KoogParsingConfig
) {
    /**
     * Render the system prompt with dynamic variables.
     * 
     * @param dynamicVariables Runtime variables (workspaceRoot, currentFile, etc.)
     * @return Fully rendered system prompt
     */
    fun renderSystemPrompt(dynamicVariables: Map<String, String> = emptyMap()): String {
        // Combine static and dynamic variables (dynamic override static)
        val allVariables = staticTemplateVariables + dynamicVariables
        var rendered = systemPromptTemplate
        allVariables.forEach { (key, value) ->
            rendered = rendered.replace("{{$key}}", value)
        }
        return rendered
    }
}

/**
 * Koog formatting rules.
 */
data class KoogFormattingRules(
    val indentSize: Int,
    val useTabs: Boolean,
    val maxLineLength: Int,
    val trimTrailingWhitespace: Boolean,
    val insertFinalNewline: Boolean
)

/**
 * Koog parsing configuration.
 */
data class KoogParsingConfig(
    val strictJsonParsing: Boolean,
    val allowMarkdownCodeBlocks: Boolean,
    val fallbackToPlainText: Boolean,
    val maxParseAttempts: Int
)

/**
 * Koog GraphStrategy configuration.
 * 
 * @property maxIterations Maximum iterations per task
 * @property maxConsecutiveToolCalls Maximum consecutive tool calls
 * @property reflectionEnabled Whether self-reflection is enabled
 * @property selfCorrectionEnabled Whether self-correction is enabled
 * @property llmRetries LLM retry count
 * @property llmTimeoutSeconds LLM timeout in seconds
 * @property streamingEnabled Whether streaming is enabled
 * @property layer VSLFC layer for the strategy
 * @property agentId Unique agent identifier
 */
data class KoogStrategyConfig(
    val maxIterations: Int = 10,
    val maxConsecutiveToolCalls: Int = 12,
    val reflectionEnabled: Boolean = true,
    val selfCorrectionEnabled: Boolean = true,
    val llmRetries: Int = 3,
    val llmTimeoutSeconds: Long = 60,
    val toolTimeoutSeconds: Long = 30,
    val streamingEnabled: Boolean = true,
    val layer: VslfcLayer = VslfcLayer.CODE,
    val agentId: String = ""
)

/**
 * Koog ToolRegistry configuration.
 */
data class KoogToolRegistryConfig(
    val enabledTools: List<String> = emptyList(),
    val disabledTools: List<String> = emptyList(),
    val toolTimeoutSeconds: Long = 30,
    val requireConfirmationFor: List<String> = emptyList(),
    val readOnlyMode: Boolean = false,
    val allowFileWrites: Boolean = true,
    val allowedDirectories: List<String> = emptyList(),
    val forbiddenDirectories: List<String> = emptyList(),
    val maxFileSize: Long = 1024 * 1024,
    val requireBackupBeforeWrite: Boolean = true
)
