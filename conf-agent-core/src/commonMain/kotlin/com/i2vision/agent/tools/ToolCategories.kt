/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.agent.tools

import com.i2vision.agent.ToolCategory
import com.i2vision.agent.ToolInfo

/**
 * Tool categories for organization and permission control.
 * 
 * Categories determine:
 * - Which layers have access to the tool
 * - Whether the tool requires safety checks
 * - How the tool is displayed in UI
 * - Caching and performance characteristics
 */
enum class ToolCategory {
    /**
     * File system operations: read_file, write_file, edit_file, list_directory, regex_search
     * 
     * Available to: All layers (write operations restricted by layer)
     * Safety: Write operations require safety checks
     */
    FILE_SYSTEM,
    
    /**
     * Discovery operations: i2vision_discover, i2vision_get_context, i2vision_get_related
     * 
     * Available to: All layers
     * Safety: Read-only, no safety checks required
     * Caching: Results cached for performance
     */
    DISCOVERY,
    
    /**
     * Analysis operations: i2vision_validate_contracts, i2vision_search_symbols, i2vision_verbalize
     * 
     * Available to: STRUCTURE, LOGIC, CODE layers
     * Safety: Read-only, no safety checks required
     * Caching: Some results cached
     */
    ANALYSIS,
    
    /**
     * Control operations: task_complete
     * 
     * Available to: All layers
     * Safety: No safety checks required
     * Special: Signals task completion to the agent loop
     */
    CONTROL,
    
    /**
     * MCP operations: Tools from MCP servers
     * 
     * Available to: Configurable per layer
     * Safety: Depends on tool implementation
     */
    MCP
}

/**
 * Tool metadata for the registry.
 * 
 * @property name Tool name
 * @property description Tool description
 * @property category Tool category
 * @property isReadOnly Whether the tool is read-only
 * @property isExpensive Whether the tool is expensive (time/resource intensive)
 * @property cacheResults Whether results should be cached
 * @property cacheTtlSeconds Cache TTL in seconds
 * @property parameters Tool parameters
 */
data class ToolMetadata(
    val name: String,
    val description: String,
    val category: ToolCategory,
    val isReadOnly: Boolean,
    val isExpensive: Boolean = false,
    val cacheResults: Boolean = false,
    val cacheTtlSeconds: Long = 300,
    val parameters: List<ToolParameterMetadata>
)

/**
 * Tool parameter metadata.
 * 
 * @property name Parameter name
 * @property type Parameter type (string, integer, boolean, etc.)
 * @property description Parameter description
 * @property required Whether the parameter is required
 * @property default Default value
 * @property enum Allowed values (for enum types)
 */
data class ToolParameterMetadata(
    val name: String,
    val type: String,
    val description: String,
    val required: Boolean = true,
    val default: Any? = null,
    val enum: List<String>? = null
)

/**
 * Tool permissions by VSLFC layer.
 * 
 * This object defines which tools are available to each layer.
 */
object ToolPermissions {
    
    /**
     * Get available tool categories for a layer.
     */
    fun getCategoriesForLayer(layer: com.i2vision.agent.VslfcLayer): Set<ToolCategory> =
        when (layer) {
            com.i2vision.agent.VslfcLayer.VISION -> setOf(
                ToolCategory.DISCOVERY,
                ToolCategory.FILE_SYSTEM,  // Read-only ops
                ToolCategory.CONTROL
            )
            com.i2vision.agent.VslfcLayer.STRUCTURE -> setOf(
                ToolCategory.DISCOVERY,
                ToolCategory.ANALYSIS,
                ToolCategory.FILE_SYSTEM,  // Read-only ops
                ToolCategory.CONTROL
            )
            com.i2vision.agent.VslfcLayer.LOGIC -> setOf(
                ToolCategory.DISCOVERY,
                ToolCategory.ANALYSIS,
                ToolCategory.FILE_SYSTEM,
                ToolCategory.CONTROL
            )
            com.i2vision.agent.VslfcLayer.FLOW -> setOf(
                ToolCategory.DISCOVERY,
                ToolCategory.FILE_SYSTEM,
                ToolCategory.CONTROL
            )
            com.i2vision.agent.VslfcLayer.CODE -> setOf(
                ToolCategory.DISCOVERY,
                ToolCategory.ANALYSIS,
                ToolCategory.FILE_SYSTEM,
                ToolCategory.CONTROL,
                ToolCategory.MCP
            )
        }
    
    /**
     * Check if a layer has access to a tool category.
     */
    fun hasAccess(layer: com.i2vision.agent.VslfcLayer, category: ToolCategory): Boolean =
        getCategoriesForLayer(layer).contains(category)
    
    /**
     * Get tool permissions matrix.
     */
    fun getPermissionsMatrix(): Map<com.i2vision.agent.VslfcLayer, Map<ToolCategory, Boolean>> =
        com.i2vision.agent.VslfcLayer.values().associateWith { layer ->
            ToolCategory.values().associateWith { category ->
                hasAccess(layer, category)
            }
        }
}

/**
 * Convert Tool to ToolInfo for capabilities.
 */
fun Tool.toToolInfo(): ToolInfo = ToolInfo(
    name = name,
    description = description,
    category = category.toAgentToolCategory(),
    isReadOnly = isReadOnly
)

/**
 * Convert ToolCategory to Agent ToolCategory.
 */
fun ToolCategory.toAgentToolCategory(): ToolCategory = when (this) {
    ToolCategory.FILE_SYSTEM -> ToolCategory.FILE_SYSTEM
    ToolCategory.DISCOVERY -> ToolCategory.DISCOVERY
    ToolCategory.ANALYSIS -> ToolCategory.ANALYSIS
    ToolCategory.CONTROL -> ToolCategory.CONTROL
    ToolCategory.MCP -> ToolCategory.MCP
}

/**
 * Tool capability flags.
 */
data class ToolCapabilities(
    val supportsStreaming: Boolean = false,
    val supportsCancellation: Boolean = true,
    val requiresNetwork: Boolean = false,
    val requiresDisk: Boolean = true,
    val requiresShell: Boolean = false,
    val maxConcurrentExecutions: Int = 10
)

/**
 * Tool execution statistics.
 */
data class ToolStats(
    val totalExecutions: Long = 0,
    val successfulExecutions: Long = 0,
    val failedExecutions: Long = 0,
    val averageDurationMs: Double = 0.0,
    val lastExecutionTime: Long? = null
) {
    /**
     * Calculate success rate.
     */
    fun getSuccessRate(): Double =
        if (totalExecutions == 0) 0.0
        else successfulExecutions.toDouble() / totalExecutions
}

/**
 * Tool execution context.
 */
data class ToolExecutionContext(
    val requestId: String,
    val workspaceRoot: String,
    val currentFile: String?,
    val layer: com.i2vision.agent.VslfcLayer,
    val iteration: Int,
    val timestamp: Long = System.currentTimeMillis()
)
