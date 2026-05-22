/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.agent.config

import com.i2vision.agent.VslfcLayer

/**
 * Validates agent configurations for correctness and completeness.
 * 
 * Validation checks:
 * - Required fields are present
 * - Values are within acceptable ranges
 * - Cross-field consistency
 * - Tool availability
 * - Model configuration
 * - Safety constraints
 * 
 * Example usage:
 * ```kotlin
 * val config = YamlConfigLoader.load("agent.yaml")
 * val errors = ConfigValidation.validate(config)
 * 
 * if (errors.isNotEmpty()) {
 *     throw ConfigValidationException("Invalid configuration: ${errors.joinToString(", ")}")
 * }
 * ```
 */
object ConfigValidation {
    
    /**
     * Validate a configuration and return a list of errors.
     * 
     * @param config Configuration to validate
     * @return List of validation errors (empty if valid)
     */
    fun validate(config: AgentPromptConfiguration): List<String> {
        val errors = mutableListOf<String>()
        
        // Validate metadata
        errors.addAll(validateMetadata(config))
        
        // Validate model configuration
        errors.addAll(validateModel(config.model))
        
        // Validate iteration settings
        errors.addAll(validateIterationSettings(config.iterationSettings))
        
        // Validate tool selection
        errors.addAll(validateToolSelection(config.toolSelection))
        
        // Validate safety settings
        errors.addAll(validateSafety(config.safety))
        
        // Validate execution settings
        errors.addAll(validateExecution(config.execution))
        
        // Validate streaming settings
        errors.addAll(validateStreaming(config.streaming))
        
        // Validate cross-field consistency
        errors.addAll(validateConsistency(config))
        
        return errors
    }
    
    /**
     * Validate that the configuration is safe to use.
     * 
     * @param config Configuration to validate
     * @throws ConfigValidationException if validation fails
     */
    fun validateOrThrow(config: AgentPromptConfiguration) {
        val errors = validate(config)
        if (errors.isNotEmpty()) {
            throw ConfigValidationException(
                "Configuration validation failed: ${errors.joinToString(", ")}"
            )
        }
    }
    
    /**
     * Validate configuration metadata.
     */
    private fun validateMetadata(config: AgentPromptConfiguration): List<String> {
        val errors = mutableListOf<String>()
        
        if (config.key.isBlank()) {
            errors.add("key is required and cannot be blank")
        }
        
        if (config.agentType.isBlank()) {
            errors.add("agentType is required and cannot be blank")
        } else {
            // Validate agent type is a valid VSLFC layer
            val layer = VslfcLayer.fromStringOrNull(config.agentType)
            if (layer == null) {
                errors.add("agentType must be one of: ${VslfcLayer.entries.joinToString(", ")}")
            }
        }
        
        if (config.version.isBlank()) {
            errors.add("version is required and cannot be blank")
        }
        
        if (config.systemPromptTemplate.isBlank()) {
            errors.add("systemPromptTemplate is required and cannot be blank")
        }
        
        return errors
    }
    
    /**
     * Validate model configuration.
     */
    private fun validateModel(model: ModelConfig): List<String> {
        val errors = mutableListOf<String>()
        
        if (model.provider.isBlank()) {
            errors.add("model.provider is required and cannot be blank")
        }
        
        if (model.id.isBlank()) {
            errors.add("model.id is required and cannot be blank")
        }
        
        if (model.contextLength <= 0) {
            errors.add("model.contextLength must be positive (got ${model.contextLength})")
        }
        
        if (model.temperature < 0.0 || model.temperature > 2.0) {
            errors.add("model.temperature must be between 0.0 and 2.0 (got ${model.temperature})")
        }
        
        if (model.topP < 0.0 || model.topP > 1.0) {
            errors.add("model.topP must be between 0.0 and 1.0 (got ${model.topP})")
        }
        
        if (model.topK < 1) {
            errors.add("model.topK must be at least 1 (got ${model.topK})")
        }
        
        if (model.maxTokens <= 0) {
            errors.add("model.maxTokens must be positive (got ${model.maxTokens})")
        }
        
        return errors
    }
    
    /**
     * Validate iteration settings.
     */
    private fun validateIterationSettings(iteration: IterationConfig): List<String> {
        val errors = mutableListOf<String>()
        
        if (iteration.maxIterations <= 0) {
            errors.add("iterationSettings.maxIterations must be positive (got ${iteration.maxIterations})")
        }
        
        if (iteration.maxIterations > 100) {
            errors.add("iterationSettings.maxIterations should not exceed 100 (got ${iteration.maxIterations})")
        }
        
        if (iteration.maxConsecutiveToolCalls <= 0) {
            errors.add("iterationSettings.maxConsecutiveToolCalls must be positive (got ${iteration.maxConsecutiveToolCalls})")
        }
        
        if (iteration.kickstartMinInvalidOutputs <= 0) {
            errors.add("iterationSettings.kickstartMinInvalidOutputs must be positive (got ${iteration.kickstartMinInvalidOutputs})")
        }
        
        return errors
    }
    
    /**
     * Validate tool selection.
     */
    private fun validateToolSelection(toolSelection: ToolSelectionConfig): List<String> {
        val errors = mutableListOf<String>()
        
        if (toolSelection.toolTimeoutSeconds <= 0) {
            errors.add("toolSelection.toolTimeoutSeconds must be positive (got ${toolSelection.toolTimeoutSeconds})")
        }
        
        // Check for conflicts between enabled and disabled tools
        val enabled = toolSelection.enabledTools.toSet()
        val disabled = toolSelection.disabledTools.toSet()
        val conflicts = enabled intersect disabled
        
        if (conflicts.isNotEmpty()) {
            errors.add("Tools cannot be both enabled and disabled: ${conflicts.joinToString(", ")}")
        }
        
        return errors
    }
    
    /**
     * Validate safety settings.
     */
    private fun validateSafety(safety: SafetyConfig): List<String> {
        val errors = mutableListOf<String>()
        
        if (safety.maxFileSize <= 0) {
            errors.add("safety.maxFileSize must be positive (got ${safety.maxFileSize})")
        }
        
        // Check for conflicts between allowed and forbidden directories
        val allowed = safety.allowedDirectories.map { it.lowercase() }.toSet()
        val forbidden = safety.forbiddenDirectories.map { it.lowercase() }.toSet()
        val conflicts = allowed intersect forbidden
        
        if (conflicts.isNotEmpty()) {
            errors.add("Directories cannot be both allowed and forbidden: ${conflicts.joinToString(", ")}")
        }
        
        return errors
    }
    
    /**
     * Validate execution settings.
     */
    private fun validateExecution(execution: ExecutionConfig): List<String> {
        val errors = mutableListOf<String>()
        
        if (execution.buildCommand.isBlank()) {
            errors.add("execution.buildCommand is required and cannot be blank")
        }
        
        if (execution.fileOperationMode !in listOf("DIRECT", "SHELL")) {
            errors.add("execution.fileOperationMode must be 'DIRECT' or 'SHELL' (got '${execution.fileOperationMode}')")
        }
        
        return errors
    }
    
    /**
     * Validate streaming settings.
     */
    private fun validateStreaming(streaming: StreamingConfig): List<String> {
        val errors = mutableListOf<String>()
        
        if (streaming.chunkSize <= 0) {
            errors.add("streaming.chunkSize must be positive (got ${streaming.chunkSize})")
        }
        
        return errors
    }
    
    /**
     * Validate cross-field consistency.
     */
    private fun validateConsistency(config: AgentPromptConfiguration): List<String> {
        val errors = mutableListOf<String>()
        
        // Check that streaming config is consistent with LLM streaming
        if (config.streaming.enabled && !config.llm.streaming) {
            errors.add("streaming.enabled is true but llm.streaming is false")
        }
        
        // Check that build verification is only enabled if build command is set
        if (config.safety.enableBuildVerification && config.execution.buildCommand.isBlank()) {
            errors.add("safety.enableBuildVerification is true but execution.buildCommand is empty")
        }
        
        // Check that read-only mode is consistent with allowed file writes
        if (config.toolSelection.readOnlyMode && config.safety.allowFileWrites) {
            errors.add("toolSelection.readOnlyMode is true but safety.allowFileWrites is also true")
        }
        
        return errors
    }
    
    /**
     * Validate a specific tool name.
     */
    fun validateToolName(toolName: String): List<String> {
        val errors = mutableListOf<String>()
        
        if (toolName.isBlank()) {
            errors.add("Tool name cannot be blank")
        }
        
        if (!toolName.matches(Regex("^[a-zA-Z][a-zA-Z0-9_]*$"))) {
            errors.add("Tool name must start with a letter and contain only letters, numbers, and underscores")
        }
        
        return errors
    }
    
    /**
     * Validate a directory path.
     */
    fun validateDirectoryPath(path: String, allowRelative: Boolean = true): List<String> {
        val errors = mutableListOf<String>()
        
        if (path.isBlank()) {
            errors.add("Directory path cannot be blank")
        }
        
        if (!allowRelative && !path.startsWith("/")) {
            errors.add("Directory path must be absolute")
        }
        
        if (path.contains("..")) {
            errors.add("Directory path cannot contain '..'")
        }
        
        return errors
    }
}

/**
 * Exception thrown when configuration validation fails.
 * 
 * @param message Error message
 * @param errors List of validation errors
 */
class ConfigValidationException(
    message: String,
    val errors: List<String> = emptyList()
) : Exception(message) {
    constructor(message: String, error: String) : this(message, listOf(error))
}
