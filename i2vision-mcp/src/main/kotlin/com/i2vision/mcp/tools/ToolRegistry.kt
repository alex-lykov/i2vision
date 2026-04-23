/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.mcp.tools

import org.slf4j.LoggerFactory

/**
 * Tool Registry - Manages MCP tool registration and execution.
 */
class ToolRegistry {

    private val log = LoggerFactory.getLogger(ToolRegistry::class.java)
    private val tools = mutableMapOf<String, ToolHandler>()

    /**
     * Register a tool.
     * 
     * @param name Tool name
     * @param handler Tool handler function
     */
    fun register(name: String, handler: ToolHandler) {
        tools[name] = handler
        log.debug("[TOOL_REGISTRY] Registered tool: {}", name)
    }

    /**
     * Get a tool by name.
     * 
     * @param name Tool name
     * @return Tool handler, or null if not found
     */
    fun get(name: String): ToolHandler? {
        return tools[name]
    }

    /**
     * List all registered tools.
     * 
     * @return List of tool names
     */
    fun listTools(): List<String> {
        return tools.keys.toList()
    }

    /**
     * Get the number of registered tools.
     * 
     * @return Number of tools
     */
    fun size(): Int {
        return tools.size
    }
}

/**
 * Tool handler function type.
 */
typealias ToolHandler = suspend (Map<String, Any>) -> com.i2vision.mcp.server.ToolResult
