/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.agent.koog.prompt

import com.i2vision.agent.VslfcLayer
import com.i2vision.agent.config.AgentPromptConfiguration
import com.i2vision.agent.config.FormattingRulesConfig

/**
 * Builds a Koog PromptTemplate from i2vision YAML configuration.
 * 
 * This builder bridges the i2vision YAML prompt configuration to Koog's
 * native PromptTemplate system, enabling:
 * - Variable substitution (${variableName})
 * - Rule set application
 * - Formatting rules
 * - Layer-specific customization
 * 
 * ## Usage
 * 
 * ```kotlin
 * val builder = KoogPromptTemplateBuilder()
 * val template = builder.build(
 *     yamlConfig = yamlConfig,
 *     layer = VslfcLayer.CODE,
 *     runtimeVariables = mapOf("currentFile" to "src/main.kt")
 * )
 * 
 * val prompt = template.render()
 * ```
 * 
 * ## Template Variables
 * 
 * The following variables are automatically available:
 * - `layer` - VSLFC layer name (lowercase)
 * - `layerDisplayName` - Human-readable layer name
 * - `layerScope` - Layer's area of focus
 * - `agentRole` - Agent's role description
 * - `agentType` - Agent type from YAML
 * - `version` - Configuration version
 * - `modelId` - Model identifier
 * - `modelProvider` - Model provider name
 * - `contextLength` - Model context length
 * - `currentFile` - Currently active file (runtime)
 * - `currentTask` - Current task description (runtime)
 * - `workspaceRoot` - Workspace root path (runtime)
 * - `projectName` - Project name (from YAML or default)
 * 
 * @property variableResolver Resolves ${variable} placeholders
 * @property ruleSetLoader Loads named rule sets
 * @property formattingAdapter Adapts formatting rules to Koog format
 */
class KoogPromptTemplateBuilder(
    private val variableResolver: PromptVariableResolver = PromptVariableResolver(),
    private val ruleSetLoader: RuleSetLoader = RuleSetLoader(),
    private val formattingAdapter: FormattingRuleAdapter = FormattingRuleAdapter()
) {
    
    /**
     * Build a complete PromptTemplate from YAML config and runtime context.
     * 
     * @param yamlConfig YAML configuration
     * @param layer VSLFC layer
     * @param runtimeVariables Runtime variable overrides
     * @return Configured PromptTemplate
     */
    fun build(
        yamlConfig: AgentPromptConfiguration,
        layer: VslfcLayer,
        runtimeVariables: Map<String, String> = emptyMap()
    ): PromptTemplate {
        // Build system prompt with variable resolution
        val systemPrompt = buildSystemPrompt(yamlConfig, layer, runtimeVariables)
        
        // Build template variables
        val templateVariables = buildTemplateVariables(yamlConfig, layer, runtimeVariables)
        
        // Load rule sets
        val ruleSets = loadRuleSets(yamlConfig.ruleSetKeys, layer)
        
        // Adapt formatting rules
        val formatting = formattingAdapter.adapt(yamlConfig.formattingRules)
        
        return PromptTemplate(
            systemPrompt = systemPrompt,
            variables = templateVariables,
            ruleSets = ruleSets,
            outputFormat = formatting,
            layerInstructions = getLayerInstructions(layer)
        )
    }
    
    /**
     * Build the system prompt by resolving template variables.
     */
    private fun buildSystemPrompt(
        yaml: AgentPromptConfiguration,
        layer: VslfcLayer,
        runtimeVars: Map<String, String>
    ): String {
        val template = yaml.systemPromptTemplate
        
        // Merge YAML variables with runtime overrides and auto-variables
        val allVariables = yaml.templateVariables + runtimeVars + mapOf(
            "layer" to layer.name.lowercase(),
            "layerDisplayName" to layer.displayName,
            "layerDescription" to layer.description,
            "agentType" to yaml.agentType,
            "version" to yaml.version,
            "agentRole" to getAgentRole(layer),
            "layerScope" to getLayerScope(layer),
            "modelId" to yaml.model.id,
            "modelProvider" to yaml.model.provider,
            "contextLength" to yaml.model.contextLength.toString(),
            "maxTokens" to yaml.model.maxTokens.toString(),
            "temperature" to yaml.model.temperature.toString()
        )
        
        return variableResolver.resolve(template, allVariables)
    }
    
    /**
     * Build template variables for Koog.
     */
    private fun buildTemplateVariables(
        yaml: AgentPromptConfiguration,
        layer: VslfcLayer,
        runtimeVars: Map<String, String>
    ): Map<String, Any> {
        return mapOf(
            // From YAML config
            "projectName" to (yaml.templateVariables["projectName"] ?: "Unknown Project"),
            "agentRole" to getAgentRole(layer),
            "agentType" to yaml.agentType,
            "version" to yaml.version,
            
            // Layer-specific
            "layer" to layer.name.lowercase(),
            "layerDisplayName" to layer.displayName,
            "layerScope" to getLayerScope(layer),
            "layerDescription" to layer.description,
            
            // Model info
            "modelId" to yaml.model.id,
            "modelProvider" to yaml.model.provider,
            "contextLength" to yaml.model.contextLength.toString(),
            "maxTokens" to yaml.model.maxTokens.toString(),
            "temperature" to yaml.model.temperature.toString(),
            
            // Runtime overrides
            "currentFile" to (runtimeVars["currentFile"] ?: ""),
            "currentTask" to (runtimeVars["currentTask"] ?: ""),
            "workspaceRoot" to (runtimeVars["workspaceRoot"] ?: ""),
            "sessionId" to (runtimeVars["sessionId"] ?: ""),
            
            // Formatting
            "formatDescription" to yaml.formattingRules.formatDescription,
            "formatBrief" to yaml.formattingRules.formatBrief
        )
    }
    
    /**
     * Load rule sets from configuration.
     */
    private fun loadRuleSets(
        ruleSetKeys: List<String>,
        layer: VslfcLayer
    ): List<RuleSet> {
        val ruleSets = mutableListOf<RuleSet>()
        
        // Load named rule sets
        for (key in ruleSetKeys) {
            val ruleSet = ruleSetLoader.load(key)
            if (ruleSet != null) {
                ruleSets.add(ruleSet)
            }
        }
        
        // Always add layer-specific rules
        val layerRules = ruleSetLoader.loadForLayer(layer)
        if (layerRules != null) {
            ruleSets.add(layerRules)
        }
        
        // Add common rules
        val commonRules = ruleSetLoader.loadCommon()
        if (commonRules != null) {
            ruleSets.add(commonRules)
        }
        
        return ruleSets
    }
    
    /**
     * Get layer-specific instructions.
     */
    private fun getLayerInstructions(layer: VslfcLayer): List<String> {
        return when (layer) {
            VslfcLayer.CODE -> listOf(
                "Focus on implementation details, code structure, and syntax.",
                "Read files before modifying them.",
                "Use edit_file for targeted changes, write_file only for new files.",
                "Verify that changes compile before marking task complete."
            )
            VslfcLayer.FLOW -> listOf(
                "Focus on API calls, data flow, and interaction sequences.",
                "Trace call chains and identify entry/exit points.",
                "Map dependencies between components.",
                "Document the complete flow from start to finish."
            )
            VslfcLayer.LOGIC -> listOf(
                "Focus on business rules, invariants, and validation logic.",
                "Identify preconditions, postconditions, and invariants.",
                "Validate that logic implements the requirements correctly.",
                "Consider edge cases and error handling."
            )
            VslfcLayer.STRUCTURE -> listOf(
                "Focus on component architecture, module boundaries, and dependencies.",
                "Analyze cohesion and coupling metrics.",
                "Identify architectural patterns and anti-patterns.",
                "Do not modify files — this is a read-only analysis layer."
            )
            VslfcLayer.VISION -> listOf(
                "Focus on requirements, goals, and constraints.",
                "Map code back to requirements and identify gaps.",
                "Validate that the implementation satisfies the vision.",
                "Identify missing features or acceptance criteria."
            )
        }
    }
    
    /**
     * Get the agent role description for a layer.
     */
    private fun getAgentRole(layer: VslfcLayer): String = when (layer) {
        VslfcLayer.CODE -> "Code Implementation Specialist"
        VslfcLayer.FLOW -> "Flow and Integration Specialist"
        VslfcLayer.LOGIC -> "Business Logic Specialist"
        VslfcLayer.STRUCTURE -> "Software Architect"
        VslfcLayer.VISION -> "Requirements Analyst"
    }
    
    /**
     * Get the layer scope description.
     */
    private fun getLayerScope(layer: VslfcLayer): String = when (layer) {
        VslfcLayer.CODE -> "implementation details, code structure, syntax, and formatting"
        VslfcLayer.FLOW -> "API calls, data flow, sequences, and interaction patterns"
        VslfcLayer.LOGIC -> "business rules, invariants, validation, and domain logic"
        VslfcLayer.STRUCTURE -> "components, modules, dependencies, and architectural patterns"
        VslfcLayer.VISION -> "requirements, goals, constraints, and acceptance criteria"
    }
    
    /**
     * Build a prompt template from default layer configuration.
     */
    fun buildDefault(layer: VslfcLayer): PromptTemplate {
        val defaultConfig = com.i2vision.agent.config.DefaultConfigs.forLayer(layer)
        return build(defaultConfig, layer)
    }
}

/**
 * Koog PromptTemplate data class.
 * 
 * @property systemPrompt System prompt text
 * @property variables Template variables
 * @property ruleSets Rule sets to apply
 * @property outputFormat Output format configuration
 * @property layerInstructions Layer-specific instructions
 */
data class PromptTemplate(
    val systemPrompt: String,
    val variables: Map<String, Any> = emptyMap(),
    val ruleSets: List<RuleSet> = emptyList(),
    val outputFormat: KoogOutputFormat,
    val layerInstructions: List<String> = emptyList()
) {
    /**
     * Render the complete prompt with variables substituted.
     */
    fun render(additionalVariables: Map<String, String> = emptyMap()): String {
        val variableResolver = PromptVariableResolver()
        val allVariables = variables.mapValues { it.value.toString() } + additionalVariables
        
        return variableResolver.resolve(systemPrompt, allVariables)
    }
    
    /**
     * Get the complete prompt with all sections.
     */
    fun getCompletePrompt(additionalVariables: Map<String, String> = emptyMap()): String {
        val rendered = render(additionalVariables)
        
        return buildString {
            appendLine(rendered)
            
            if (layerInstructions.isNotEmpty()) {
                appendLine("\n## Instructions")
                layerInstructions.forEach { instruction ->
                    appendLine("- $instruction")
                }
            }
            
            if (ruleSets.isNotEmpty()) {
                appendLine("\n## Rules")
                ruleSets.forEach { ruleSet ->
                    appendLine("### ${ruleSet.name}")
                    ruleSet.rules.forEach { rule ->
                        appendLine("- $rule")
                    }
                }
            }
            
            appendLine("\n## Output Format")
            appendLine(outputFormat.formatDescription)
        }
    }
}

/**
 * Rule set for constraining agent behavior.
 * 
 * @property name Rule set name
 * @property rules List of rules
 */
data class RuleSet(
    val name: String,
    val rules: List<String>
)
