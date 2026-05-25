/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.agent

import com.i2vision.agent.tools.Tool

/**
 * Declares what an agent instance can do.
 * 
 * Used by clients (e.g., VS Code extension) to:
 * - Adapt UI based on capabilities (e.g., show/hide streaming controls)
 * - Validate requests against limits (e.g., context window size)
 * - Display available tools to the user
 * - Select appropriate agents for specific tasks
 * 
 * @property supportsStreaming Whether streaming responses are supported
 * @property supportsCancellation Whether task cancellation is supported
 * @property supportsRuntimeConfig Whether runtime configuration updates are supported
 * @property maxContextTokens Maximum context window in tokens
 * @property availableTools Tools available to this agent
 * @property supportedLayers VSLFC layers this agent can handle
 * @property modelProvider Model provider name (e.g., "Ollama", "OpenAI")
 * @property modelId Model identifier (e.g., "llama3.2:3b", "gpt-4")
 */
data class AgentCapabilities(
    val supportsStreaming: Boolean = true,
    val supportsCancellation: Boolean = true,
    val supportsRuntimeConfig: Boolean = true,
    val maxContextTokens: Long = 8192,
    val availableTools: List<ToolInfo> = emptyList(),
    val supportedLayers: List<VslfcLayer> = VslfcLayer.entries,
    val modelProvider: String = "unknown",
    val modelId: String = "unknown"
) {
    /**
     * Check if this agent supports a specific VSLFC layer.
     */
    fun supportsLayer(layer: VslfcLayer): Boolean = layer in supportedLayers
    
    /**
     * Check if this agent has a specific tool available.
     */
    fun hasTool(toolName: String): Boolean = availableTools.any { it.name == toolName }
    
    /**
     * Get tools by category.
     */
    fun getToolsByCategory(category: ToolCategory): List<ToolInfo> =
        availableTools.filter { it.category == category }
}

/**
 * Information about a tool available to an agent.
 * 
 * @property name Tool identifier
 * @property description Human-readable description of what the tool does
 * @property category Tool category for UI organization
 * @property isReadOnly Whether the tool only reads data (no modifications)
 */
data class ToolInfo(
    val name: String,
    val description: String,
    val category: ToolCategory,
    val isReadOnly: Boolean = true
)

/**
 * Categories for organizing agent tools.
 * 
 * This is the canonical ToolCategory enum used throughout the agent system.
 */
enum class ToolCategory {
    /** File system operations: read_file, write_file, list_directory, etc. */
    FILE_SYSTEM,
    
    /** Discovery operations: i2vision_discover, i2vision_get_context */
    DISCOVERY,
    
    /** Analysis operations: i2vision_search_symbols, i2vision_analyze_dependencies */
    ANALYSIS,
    
    /** Contract validation: i2vision_validate_contracts */
    CONTRACT,
    
    /** Code analysis: i2vision_search_symbols, i2vision_analyze_dependencies */
    CODE_ANALYSIS,
    
    /** Control operations: task_complete, cancel_request */
    CONTROL,
    
    /** MCP operations: Tools from MCP servers */
    MCP
}

/**
 * Convert tools ToolCategory to agent ToolCategory.
 */
fun com.i2vision.agent.tools.ToolCategory.toAgentToolCategory(): ToolCategory = when (this) {
    com.i2vision.agent.tools.ToolCategory.FILE_SYSTEM -> ToolCategory.FILE_SYSTEM
    com.i2vision.agent.tools.ToolCategory.DISCOVERY -> ToolCategory.DISCOVERY
    com.i2vision.agent.tools.ToolCategory.ANALYSIS -> ToolCategory.ANALYSIS
    com.i2vision.agent.tools.ToolCategory.CONTROL -> ToolCategory.CONTROL
    com.i2vision.agent.tools.ToolCategory.MCP -> ToolCategory.MCP
}

/**
 * Convert agent ToolCategory to tools ToolCategory.
 */
fun ToolCategory.toToolsToolCategory(): com.i2vision.agent.tools.ToolCategory = when (this) {
    ToolCategory.FILE_SYSTEM -> com.i2vision.agent.tools.ToolCategory.FILE_SYSTEM
    ToolCategory.DISCOVERY -> com.i2vision.agent.tools.ToolCategory.DISCOVERY
    ToolCategory.ANALYSIS -> com.i2vision.agent.tools.ToolCategory.ANALYSIS
    ToolCategory.CONTROL -> com.i2vision.agent.tools.ToolCategory.CONTROL
    ToolCategory.MCP -> com.i2vision.agent.tools.ToolCategory.MCP
    ToolCategory.CONTRACT -> com.i2vision.agent.tools.ToolCategory.ANALYSIS
    ToolCategory.CODE_ANALYSIS -> com.i2vision.agent.tools.ToolCategory.ANALYSIS
}
