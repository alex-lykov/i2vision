/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.agent

import com.i2vision.agent.tools.Tool

/**
 * Runtime configuration for an agent instance.
 * 
 * These settings control agent behavior during task execution:
 * - Iteration limits prevent infinite loops
 * - Tool timeouts prevent hanging on slow operations
 * - Build verification ensures code compiles after modifications
 * - Discovery cache integration provides context awareness
 * 
 * Configuration can be:
 * - Set at agent creation time
 * - Overridden per-request via AgentConfigOverrides
 * - Updated at runtime via updateConfig()
 * 
 * @property id Unique agent identifier
 * @property displayName Human-readable name
 * @property description Configuration description
 * @property layer VSLFC layer this agent specializes in
 * @property maxIterations Maximum iterations per task (think → act cycles)
 * @property maxConsecutiveToolCalls Maximum consecutive tool calls before pausing
 * @property toolTimeoutSeconds Tool execution timeout in seconds
 * @property enableBuildVerification Whether build verification is enabled after file modifications
 * @property buildCommand Build command for verification (e.g., "./gradlew compileKotlin")
 * @property fileOperationMode How file operations are performed (DIRECT via JVM API, SHELL via subprocess)
 * @property enableKickstart Whether kickstart is enabled for malformed LLM output
 * @property kickstartMinInvalidOutputs Minimum invalid outputs before kickstart activates
 * @property maxToolRetries Maximum retries for failed tool executions
 * @property maxKickstarts Maximum kickstart attempts
 * @property injectClusterContext Whether to inject cluster context from discovery
 * @property useDiscoveryCache Whether to use discovery cache for context enrichment
 * @property maxContextTokens Maximum context tokens for the model
 * @property availableTools List of tools available to the agent
 * @property modelProvider Model provider identifier
 * @property modelId Model identifier
 */
data class AgentConfig(
    val id: String,
    val displayName: String,
    val description: String? = null,
    val layer: VslfcLayer,
    val maxIterations: Int = 10,
    val maxConsecutiveToolCalls: Int = 12,
    val toolTimeoutSeconds: Long = 30,
    val enableBuildVerification: Boolean = false,
    val buildCommand: String = "./gradlew compileKotlin",
    val fileOperationMode: FileOperationMode = FileOperationMode.DIRECT,
    val enableKickstart: Boolean = true,
    val kickstartMinInvalidOutputs: Int = 2,
    val maxToolRetries: Int? = null,
    val maxKickstarts: Int? = null,
    val injectClusterContext: Boolean = true,
    val useDiscoveryCache: Boolean = true,
    val maxContextTokens: Int = 8192,
    val availableTools: List<Tool> = emptyList(),
    val modelProvider: String = "openai",
    val modelId: String = "gpt-4"
) {
    companion object {
        /**
         * Default configuration suitable for most tasks.
         */
        fun default(
            id: String = "default",
            displayName: String = "Default Agent",
            layer: VslfcLayer = VslfcLayer.CODE
        ) = AgentConfig(
            id = id,
            displayName = displayName,
            layer = layer
        )
        
        /**
         * Conservative configuration for production use.
         * Lower iteration limits, build verification enabled.
         */
        fun conservative(
            id: String = "conservative",
            displayName: String = "Conservative Agent",
            layer: VslfcLayer = VslfcLayer.CODE
        ) = AgentConfig(
            id = id,
            displayName = displayName,
            layer = layer,
            maxIterations = 5,
            maxConsecutiveToolCalls = 5,
            enableBuildVerification = true,
            enableKickstart = true
        )
        
        /**
         * Permissive configuration for exploration and debugging.
         * Higher iteration limits, build verification disabled.
         */
        fun permissive(
            id: String = "permissive",
            displayName: String = "Permissive Agent",
            layer: VslfcLayer = VslfcLayer.CODE
        ) = AgentConfig(
            id = id,
            displayName = displayName,
            layer = layer,
            maxIterations = 20,
            maxConsecutiveToolCalls = 20,
            enableBuildVerification = false,
            enableKickstart = false
        )
    }
    
    /**
     * Create a new config with the specified overrides applied.
     */
    fun withOverrides(overrides: AgentConfigOverrides): AgentConfig = AgentConfig(
        id = id,
        displayName = displayName,
        description = description,
        layer = layer,
        maxIterations = overrides.maxIterations ?: maxIterations,
        maxConsecutiveToolCalls = overrides.maxConsecutiveToolCalls ?: maxConsecutiveToolCalls,
        toolTimeoutSeconds = overrides.toolTimeoutSeconds ?: toolTimeoutSeconds,
        enableBuildVerification = overrides.enableBuildVerification ?: enableBuildVerification,
        buildCommand = buildCommand, // Not overridable
        fileOperationMode = fileOperationMode, // Not overridable
        enableKickstart = overrides.enableKickstart ?: enableKickstart,
        kickstartMinInvalidOutputs = kickstartMinInvalidOutputs, // Not overridable
        maxToolRetries = maxToolRetries, // Not overridable
        maxKickstarts = maxKickstarts, // Not overridable
        injectClusterContext = overrides.injectClusterContext ?: injectClusterContext,
        useDiscoveryCache = useDiscoveryCache, // Not overridable
        maxContextTokens = maxContextTokens, // Not overridable
        availableTools = availableTools, // Not overridable
        modelProvider = modelProvider, // Not overridable
        modelId = modelId // Not overridable
    )
}

/**
 * Configuration overrides for a specific request.
 * 
 * Only non-null fields override the agent's base configuration.
 * This allows partial overrides without specifying all fields.
 * 
 * @property maxIterations Override maximum iterations
 * @property maxConsecutiveToolCalls Override maximum consecutive tool calls
 * @property toolTimeoutSeconds Override tool timeout
 * @property enableBuildVerification Override build verification setting
 * @property enableKickstart Override kickstart setting
 * @property injectClusterContext Override cluster context injection
 */
data class AgentConfigOverrides(
    val maxIterations: Int? = null,
    val maxConsecutiveToolCalls: Int? = null,
    val toolTimeoutSeconds: Long? = null,
    val enableBuildVerification: Boolean? = null,
    val enableKickstart: Boolean? = null,
    val injectClusterContext: Boolean? = null
) {
    companion object {
        /**
         * Empty overrides (no changes to base config).
         */
        fun empty() = AgentConfigOverrides()
    }
}

/**
 * File operation mode determines how the agent modifies files.
 */
enum class FileOperationMode {
    /**
     * Direct file operations using JVM file API.
     * Fast, but requires the agent to run in the same environment as the workspace.
     */
    DIRECT,
    
    /**
     * File operations via shell subprocess.
     * Slower, but works across environments (e.g., remote agent, containerized).
     */
    SHELL
}
