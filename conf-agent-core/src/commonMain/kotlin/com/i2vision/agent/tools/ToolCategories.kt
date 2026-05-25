/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.agent.tools

/**
 * Categories for organizing agent tools.
 * 
 * This enum is used in the tools package to categorize tools by their functionality.
 */
enum class ToolCategory {
    /** File system operations: read_file, write_file, list_directory, etc. */
    FILE_SYSTEM,
    
    /** Discovery operations: i2vision_discover, i2vision_get_context */
    DISCOVERY,
    
    /** Analysis operations: i2vision_search_symbols, i2vision_analyze_dependencies */
    ANALYSIS,
    
    /** Control operations: task_complete, cancel_request */
    CONTROL,
    
    /** MCP operations: Tools from MCP servers */
    MCP
}
