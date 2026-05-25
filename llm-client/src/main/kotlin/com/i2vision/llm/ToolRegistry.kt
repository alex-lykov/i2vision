/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.llm

/**
 * Interface for tool registry.
 * 
 * Abstracts tool execution functionality to allow
 * different implementations.
 */
interface ToolRegistry {
    /**
     * Execute a tool with arguments.
     * 
     * @param toolName Name of the tool to execute
     * @param args Map of argument names to values
     * @return Result of tool execution
     */
    suspend fun execute(toolName: String, args: Map<String, Any>): Any
}
