/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.agent

/**
 * Context provided by the client environment (e.g., VS Code extension).
 * 
 * This context enriches agent execution with:
 * - Workspace awareness (root path, active files)
 * - User interaction context (selections, cursor position)
 * - Session continuity (previous turns, session ID)
 * - VSLFC layer context (if pre-computed)
 * - Discovery cache integration
 * 
 * @property workspaceRoot Absolute path to the workspace root
 * @property currentFile Currently active file in the editor (if any)
 * @property selectedFiles Files currently selected by the user
 * @property cursorPosition Cursor position in the active editor
 * @property sessionId Session identifier for conversation continuity
 * @property previousTurns Summary of previous turns in this session
 * @property vslfcContext Pre-loaded VSLFC context if available
 * @property discoveryCachePath Path to discovery cache if available
 * @property taskType Task type hint for context optimization
 * @property extra Arbitrary additional context (client-specific)
 */
data class AgentContext(
    val workspaceRoot: String,
    val currentFile: String? = null,
    val selectedFiles: List<String> = emptyList(),
    val cursorPosition: CursorPosition? = null,
    val sessionId: String,
    val previousTurns: List<TurnSummary> = emptyList(),
    val vslfcContext: VslfcContext? = null,
    val discoveryCachePath: String? = null,
    val taskType: TaskType? = null,
    val extra: Map<String, String> = emptyMap()
) {
    /**
     * Check if the context includes a specific file.
     */
    fun hasFile(filePath: String): Boolean =
        filePath == currentFile || filePath in selectedFiles
    
    /**
     * Get all files mentioned in this context.
     */
    fun getAllFiles(): List<String> =
        listOfNotNull(currentFile) + selectedFiles
}

/**
 * Cursor position in an editor.
 * 
 * @property line 0-based line number
 * @property character 0-based character position on the line
 */
data class CursorPosition(
    val line: Int,
    val character: Int
)

/**
 * Summary of a previous turn in a conversation session.
 * 
 * Used to maintain context across multiple interactions with the same agent.
 * 
 * @property turnId Unique identifier for this turn
 * @property userInput The user's input for this turn
 * @property agentResponse The agent's response (if completed)
 * @property toolCallsUsed List of tool names used during this turn
 * @property timestamp When this turn occurred
 */
data class TurnSummary(
    val turnId: String,
    val userInput: String,
    val agentResponse: String?,
    val toolCallsUsed: List<String>,
    val timestamp: Long
)

/**
 * VSLFC context providing layer-specific information.
 * 
 * Not all fields will be populated for every request. The agent
 * uses available context to enrich its understanding.
 * 
 * @property layer The VSLFC layer this context is for
 * @property vision Requirements, goals, constraints (for VISION layer)
 * @property structure Components, modules, dependencies (for STRUCTURE layer)
 * @property logic Business rules, invariants (for LOGIC layer)
 * @property flow Sequences, API calls, interactions (for FLOW layer)
 * @property code Implementation details (for CODE layer)
 */
data class VslfcContext(
    val layer: VslfcLayer,
    val vision: String? = null,
    val structure: String? = null,
    val logic: String? = null,
    val flow: String? = null,
    val code: String? = null
)

/**
 * Task type hints for context optimization.
 * 
 * Different task types benefit from different context enrichment strategies:
 * - EXPLORE: Prioritize discovery cache and symbol search
 * - REFACTOR: Focus on current file and dependencies
 * - ADD_FEATURE: Look at similar implementations in the codebase
 * - FIX_BUG: Examine error logs and recent changes
 * - OPTIMIZE: Analyze performance-critical paths
 * - DOCUMENT: Extract KDoc, comments, and type signatures
 * - VALIDATE: Check contracts and layer boundaries
 * - GENERAL: Use default context enrichment
 */
enum class TaskType {
    /** Understanding code structure and behavior */
    EXPLORE,
    
    /** Modifying existing code without changing behavior */
    REFACTOR,
    
    /** Adding new functionality */
    ADD_FEATURE,
    
    /** Fixing bugs or incorrect behavior */
    FIX_BUG,
    
    /** Improving performance or resource usage */
    OPTIMIZE,
    
    /** Generating or updating documentation */
    DOCUMENT,
    
    /** Validating VSLFC contracts */
    VALIDATE,
    
    /** Catch-all for tasks that don't fit other categories */
    GENERAL;
    
    companion object {
        /**
         * Parse TaskType from string.
         */
        fun fromString(value: String): TaskType =
            entries.find { it.name.equals(value, ignoreCase = true) } ?: GENERAL
    }
}

/**
 * Relation type for finding related files.
 */
enum class RelationType {
    /** Files that this file imports */
    IMPORTS,
    
    /** Files that import this file */
    IMPORTED_BY,
    
    /** Files that this file calls */
    CALLS,
    
    /** Files that call this file */
    CALLED_BY,
    
    /** All relation types */
    ALL;
    
    companion object {
        /**
         * Parse RelationType from string.
         */
        fun fromString(value: String): RelationType =
            entries.find { it.name.equals(value, ignoreCase = true) } ?: ALL
    }
}

