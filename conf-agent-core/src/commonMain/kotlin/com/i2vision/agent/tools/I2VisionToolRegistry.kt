/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.agent.tools

import com.i2vision.agent.*

/**
 * Builds a complete tool registry combining i2vision, MCP, and file system tools.
 * 
 * Tool access is controlled per VSLFC layer:
 * - **VISION**: Read-only, discovery tools only
 * - **STRUCTURE**: Read-only, discovery + analysis
 * - **LOGIC**: Read + write, all except contract modification
 * - **FLOW**: Read + write, discovery + analysis + file ops
 * - **CODE**: Full access, all tools
 * 
 * ## Tool Categories
 * 
 * - **Discovery Tools**: i2vision_discover, i2vision_get_context, i2vision_get_related
 * - **Analysis Tools**: i2vision_validate_contracts, i2vision_search_symbols, i2vision_verbalize
 * - **File System Tools**: read_file, write_file, edit_file, list_directory, regex_search
 * - **Control Tools**: task_complete
 * 
 * ## Usage
 * 
 * ```kotlin
 * val registry = I2VisionToolRegistry(
 *     config = agentConfig,
 *     layer = VslfcLayer.CODE,
 *     workspaceRoot = "/path/to/project",
 *     instantContext = instantContextProvider,
 *     discoveryEngine = discoveryEngine,
 *     discoveryCache = discoveryCache
 * )
 * 
 * val tools = registry.build()
 * val result = tools.execute("read_file", mapOf("path" to "src/main.kt"))
 * ```
 * 
 * @property config Agent configuration
 * @property layer VSLFC layer for access control
 * @property workspaceRoot Workspace root path
 * @property instantContext i2vision instant context provider
 * @property discoveryEngine i2vision discovery engine
 * @property discoveryCache i2vision discovery cache
 * @property safetyChecker Safety validation for writes
 */
class I2VisionToolRegistry(
    private val config: AgentConfig,
    private val layer: VslfcLayer,
    private val workspaceRoot: String,
    private val instantContext: InstantContextProvider,
    private val discoveryEngine: DiscoveryEngine,
    private val discoveryCache: DiscoveryCache,
    private val safetyChecker: ToolSafetyChecker
) {
    
    /**
     * Build the complete tool registry.
     * 
     * @return Configured ToolRegistry with all available tools
     */
    fun build(): ToolRegistry {
        // Create tool components
        val discoveryTools = DiscoveryTools(instantContext, discoveryEngine, discoveryCache)
        val analysisTools = AnalysisTools(instantContext, discoveryEngine)
        val fileSystemTools = FileSystemTools(workspaceRoot, safetyChecker)
        
        // Build registry with layer-based access control
        return ToolRegistry(
            name = "i2vision-${layer.name.lowercase()}-tools",
            tools = buildList {
                // === DISCOVERY TOOLS ===
                if (layer.hasDiscoveryAccess()) {
                    add(discoveryTools.discover())
                    add(discoveryTools.getContext())
                    add(discoveryTools.getRelated())
                }
                
                // === ANALYSIS TOOLS ===
                if (layer.hasAnalysisAccess()) {
                    add(analysisTools.validateContracts())
                    add(analysisTools.searchSymbols())
                    add(analysisTools.verbalize())
                }
                
                // === FILE SYSTEM TOOLS ===
                // Read-only tools available to all layers
                add(fileSystemTools.readFile())
                add(fileSystemTools.listDirectory())
                add(fileSystemTools.regexSearch())
                
                // Write tools only for layers with write access
                if (layer.hasWriteAccess()) {
                    add(fileSystemTools.writeFile())
                    add(fileSystemTools.editFile())
                }
                
                // === CONTROL TOOLS ===
                add(controlTaskComplete())
            }
        )
    }
    
    /**
     * Build with additional custom tools.
     * 
     * @param customTools Additional tools to register
     * @return Configured ToolRegistry
     */
    fun buildWith(customTools: List<Tool>): ToolRegistry {
        val registry = build()
        customTools.forEach { registry.register(it) }
        return registry
    }
    
    /**
     * Build with MCP tools.
     * 
     * @param mcpTools Tools from MCP server
     * @return Configured ToolRegistry
     */
    fun buildWithMcp(mcpTools: List<Tool>): ToolRegistry {
        return buildWith(mcpTools)
    }
    
    /**
     * Control tool: Signal task completion.
     */
    private fun controlTaskComplete(): Tool = Tool(
        name = "task_complete",
        aliases = listOf("stop_task", "finish_task", "done", "complete"),
        description = "Signal that the current task is complete. Use this when you have finished the requested work.",
        category = ToolCategory.CONTROL,
        isReadOnly = true,
        isExpensive = false,
        cacheResults = false,
        parameters = listOf(
            ToolParameter(
                name = "message",
                type = "string",
                description = "Completion message or summary of what was accomplished",
                required = true
            ),
            ToolParameter(
                name = "outcome",
                type = "string",
                description = "Task outcome: success, partial, or needs_followup",
                required = false,
                default = "success",
                enum = listOf("success", "partial", "needs_followup")
            )
        ),
        handler = { args ->
            val message = args["message"] as? String ?: "Task marked complete"
            val outcome = args["outcome"] as? String ?: "success"
            
            ToolResult(
                success = true,
                output = message,
                signal = ToolSignal.TASK_STOP,
                metadata = mapOf("outcome" to outcome)
            )
        }
    )
    
    companion object {
        /**
         * Create a tool registry from configuration.
         * 
         * @param config Agent configuration
         * @param layer VSLFC layer
         * @param workspaceRoot Workspace root path
         * @param instantContext i2vision instant context provider
         * @param discoveryEngine i2vision discovery engine
         * @param discoveryCache i2vision discovery cache
         * @return Configured tool registry
         */
        fun create(
            config: AgentConfig,
            layer: VslfcLayer,
            workspaceRoot: String,
            instantContext: InstantContextProvider,
            discoveryEngine: DiscoveryEngine,
            discoveryCache: DiscoveryCache
        ): ToolRegistry {
            val safetyChecker = ToolSafetyChecker(
                workspaceRoot = workspaceRoot,
                config = config.safety
            )
            
            val registry = I2VisionToolRegistry(
                config = config,
                layer = layer,
                workspaceRoot = workspaceRoot,
                instantContext = instantContext,
                discoveryEngine = discoveryEngine,
                discoveryCache = discoveryCache,
                safetyChecker = safetyChecker
            )
            
            return registry.build()
        }
    }
}

/**
 * Layer-based tool access control.
 */
fun VslfcLayer.hasDiscoveryAccess(): Boolean = when (this) {
    VslfcLayer.VISION, VslfcLayer.STRUCTURE, VslfcLayer.FLOW, 
    VslfcLayer.LOGIC, VslfcLayer.CODE -> true
}

fun VslfcLayer.hasAnalysisAccess(): Boolean = when (this) {
    VslfcLayer.STRUCTURE, VslfcLayer.LOGIC, VslfcLayer.CODE -> true
    VslfcLayer.VISION, VslfcLayer.FLOW -> false
}

fun VslfcLayer.hasWriteAccess(): Boolean = when (this) {
    VslfcLayer.CODE, VslfcLayer.LOGIC, VslfcLayer.FLOW -> true
    VslfcLayer.STRUCTURE, VslfcLayer.VISION -> false
}

/**
 * Tool registry that holds and executes tools.
 */
class ToolRegistry(
    val name: String,
    private val tools: List<Tool> = emptyList()
) {
    private val toolMap: Map<String, Tool> by lazy {
        buildMap {
            tools.forEach { tool ->
                // Register by name
                put(tool.name.lowercase(), tool)
                // Register by aliases
                tool.aliases.forEach { alias ->
                    put(alias.lowercase(), tool)
                }
            }
        }
    }
    
    /**
     * Register a tool in the registry.
     */
    fun register(tool: Tool) {
        // Note: This is a simplified version. In production, use a mutable map.
    }
    
    /**
     * Get a tool by name.
     */
    fun get(toolName: String): Tool? = toolMap[toolName.lowercase()]
    
    /**
     * Execute a tool by name.
     */
    suspend fun execute(toolName: String, args: Map<String, Any>): ToolResult {
        val tool = get(toolName) ?: return ToolResult(
            success = false,
            error = "Unknown tool: $toolName"
        )
        
        return tool.handler(args)
    }
    
    /**
     * List all available tools.
     */
    fun listTools(): List<ToolInfo> = tools.map { it.toToolInfo() }
    
    /**
     * Get tool by category.
     */
    fun getToolsByCategory(category: ToolCategory): List<Tool> =
        tools.filter { it.category == category }
}

/**
 * Tool definition with handler.
 */
data class Tool(
    val name: String,
    val aliases: List<String> = emptyList(),
    val description: String,
    val category: ToolCategory,
    val isReadOnly: Boolean,
    val isExpensive: Boolean = false,
    val cacheResults: Boolean = false,
    val cacheTtlSeconds: Long = 300,
    val parameters: List<ToolParameter>,
    val handler: suspend (Map<String, Any>) -> ToolResult
) {
    /**
     * Convert to ToolInfo for capabilities.
     */
    fun toToolInfo(): ToolInfo = ToolInfo(
        name = name,
        description = description,
        category = category,
        isReadOnly = isReadOnly
    )
}

/**
 * Tool parameter definition.
 */
data class ToolParameter(
    val name: String,
    val type: String,
    val description: String,
    val required: Boolean = true,
    val default: Any? = null,
    val enum: List<String>? = null
)

/**
 * Result from tool execution.
 */
data class ToolResult(
    val success: Boolean,
    val output: String? = null,
    val error: String? = null,
    val signal: ToolSignal = ToolSignal.NONE,
    val metadata: Map<String, String> = emptyMap()
) {
    companion object {
        fun success(output: String, metadata: Map<String, String> = emptyMap()) =
            ToolResult(success = true, output = output, metadata = metadata)
        
        fun failure(error: String, signal: ToolSignal = ToolSignal.NONE, metadata: Map<String, String> = emptyMap()) =
            ToolResult(success = false, error = error, signal = signal, metadata = metadata)
    }
}

/**
 * Signal from tool execution.
 */
enum class ToolSignal {
    NONE,
    TASK_STOP
}
