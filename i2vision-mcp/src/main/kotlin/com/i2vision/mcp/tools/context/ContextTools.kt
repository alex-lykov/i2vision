/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.mcp.tools.context

import com.i2vision.instant.context.ContextProvider
import com.i2vision.instant.proactive.ProactiveContext
import com.i2vision.mcp.server.ToolResult
import org.slf4j.LoggerFactory

/**
 * Context Tools - MCP tools for instant context retrieval.
 */
class ContextTools(
    private val projectRoot: String
) {

    private val log = LoggerFactory.getLogger(ContextTools::class.java)
    private val contextProvider = ContextProvider(projectRoot)
    private val proactiveContext = ProactiveContext(projectRoot)

    /**
     * Get instant context - Get context for a file or task.
     */
    suspend fun getInstantContext(parameters: Map<String, Any>): ToolResult {
        log.debug("[MCP] get_instant_context called with parameters: {}", parameters)

        val filePath = parameters["file"] as? String
        val task = parameters["task"] as? String ?: "general"

        if (filePath == null) {
            return ToolResult.error("Missing required parameter: file")
        }

        val context = contextProvider.getContext(filePath, task)

        return ToolResult.success(
            mapOf(
                "file" to context.filePath,
                "symbols" to context.symbols.size,
                "relatedFiles" to context.relatedFiles,
                "task" to context.taskContext.task,
                "suggestions" to context.taskContext.suggestions
            )
        )
    }

    /**
     * Prefetch context - Pre-fetch related files for faster context retrieval.
     */
    suspend fun prefetchContext(parameters: Map<String, Any>): ToolResult {
        log.debug("[MCP] prefetch_context called with parameters: {}", parameters)

        val filePath = parameters["file"] as? String
        val depth = (parameters["depth"] as? Number)?.toInt() ?: 2

        if (filePath == null) {
            return ToolResult.error("Missing required parameter: file")
        }

        val relatedFiles = proactiveContext.prefetchRelatedFiles(filePath, depth)

        return ToolResult.success(
            mapOf(
                "file" to filePath,
                "relatedFiles" to relatedFiles.size,
                "prefetched" to true
            )
        )
    }
}
