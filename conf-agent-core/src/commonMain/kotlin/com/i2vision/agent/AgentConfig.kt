/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.agent

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
 * @property maxIterations Maximum iterations per task (think → act cycles)
 * @property maxConsecutiveToolCalls Maximum consecutive tool calls before pausing
 * @property toolTimeoutSeconds Tool execution timeout in seconds
 * @property enableBuildVerification Whether build verification is enabled after file modifications
 * @property buildCommand Build command for verification (e.g., "./gradlew compileKotlin")
 * @property fileOperationMode How file operations are performed (DIRECT via JVM API, SHELL via subprocess)
 * @property enableKickstart Whether kickstart is enabled for malformed LLM output
 * @property kickstartMinInvalidOutputs Minimum invalid outputs before kickstart activates
 * @property injectClusterContext Whether to inject cluster context from discovery
 * @property useDiscoveryCache Whether to use discovery cache for context enrichment
 */
data class AgentConfig(
    val maxIterations: Int = 10,
    val maxConsecutiveToolCalls: Int = 12,
    val toolTimeoutSeconds: Long = 30,
    val enableBuildVerification: Boolean = false,
    val buildCommand: String = "./gradlew compileKotlin",
    val fileOperationMode: FileOperationMode = FileOperationMode.DIRECT,
    val enableKickstart: Boolean = true,
    val kickstartMinInvalidOutputs: Int = 2,
    val injectClusterContext: Boolean = true,
    val useDiscoveryCache: Boolean = true
) {
    companion object {
        /**
         * Default configuration suitable for most tasks.
         */
        fun default() = AgentConfig()
        
        /**
         * Conservative configuration for production use.
         * Lower iteration limits, build verification enabled.
         */
        fun conservative() = AgentConfig(
            maxIterations = 5,
            maxConsecutiveToolCalls = 5,
            enableBuildVerification = true,
            enableKickstart = true
        )
        
        /**
         * Permissive configuration for exploration and debugging.
         * Higher iteration limits, build verification disabled.
         */
        fun permissive() = AgentConfig(
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
        maxIterations = overrides.maxIterations ?: maxIterations,
        maxConsecutiveToolCalls = overrides.maxConsecutiveToolCalls ?: maxConsecutiveToolCalls,
        toolTimeoutSeconds = overrides.toolTimeoutSeconds ?: toolTimeoutSeconds,
        enableBuildVerification = overrides.enableBuildVerification ?: enableBuildVerification,
        buildCommand = buildCommand, // Not overridable
        fileOperationMode = fileOperationMode, // Not overridable
        enableKickstart = overrides.enableKickstart ?: enableKickstart,
        kickstartMinInvalidOutputs = kickstartMinInvalidOutputs, // Not overridable
        injectClusterContext = overrides.injectClusterContext ?: injectClusterContext,
        useDiscoveryCache = useDiscoveryCache // Not overridable
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
