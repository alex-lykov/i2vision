/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.agent.koog.prompt

/**
 * Resolves ${variableName} placeholders in prompt templates.
 * 
 * Supports:
 * - Simple substitution: `${variableName}`
 * - Default values: `${variableName:default value}`
 * - Escaped literals: `$${literal}` outputs `${literal}`
 * 
 * ## Syntax Examples
 * 
 * ```
 * ${workspaceRoot}              → "/path/to/project"
 * ${currentFile:unknown}        → "src/main.kt" or "unknown" if not set
 * $${literal}                   → "${literal}" (escaped)
 * ${agentRole} implements ${layerScope}  → "Code Specialist implements code structure"
 * ```
 * 
 * ## Usage
 * 
 * ```kotlin
 * val resolver = PromptVariableResolver()
 * 
 * val template = "Hello, ${userName:Guest}! You are in ${workspaceRoot}."
 * val variables = mapOf("userName" to "Alice", "workspaceRoot" to "/project")
 * 
 * val result = resolver.resolve(template, variables)
 * // Result: "Hello, Alice! You are in /project."
 * 
 * val unresolved = resolver.findUnresolved(template, emptyMap())
 * // Result: ["userName", "workspaceRoot"]
 * ```
 */
class PromptVariableResolver {
    
    /**
     * Resolve all ${variable} placeholders in the template.
     * 
     * @param template Template string with ${variable} placeholders
     * @param variables Variable name → value mapping
     * @return Template with all variables resolved
     */
    fun resolve(template: String, variables: Map<String, String>): String {
        // First, handle escaped literals ($${...} → ${...})
        val unescaped = template.replace("$${", "\u0000{\$").replace("}$$", "\$}\u0000")
        
        // Pattern: ${variableName} or ${variableName:defaultValue}
        val pattern = Regex("""\$\{([^}:]+)(?::([^}]*))?\}""")
        
        return pattern.replace(unescaped) { match ->
            val varName = match.groupValues[1]
            val defaultValue = match.groupValues[2].ifEmpty { null }
            
            val value = variables[varName] ?: defaultValue
            
            if (value != null) {
                value
            } else {
                // Keep unresolved if no default
                match.value
            }
        }.replace("\u0000{\$", "${").replace("\$}\u0000", "}$")
    }
    
    /**
     * Find all unresolved variables in the template.
     * 
     * @param template Template string with ${variable} placeholders
     * @param variables Variable name → value mapping
     * @return List of variable names that are not resolved
     */
    fun findUnresolved(template: String, variables: Map<String, String>): List<String> {
        // Remove escaped literals first
        val unescaped = template.replace("$${", "ESCAPED_START").replace("}$$", "ESCAPED_END")
        
        val pattern = Regex("""\$\{([^}:]+)(?::[^}]*)?\}""")
        
        return pattern.findAll(unescaped)
            .map { it.groupValues[1] }
            .filter { it !in variables }
            .toList()
    }
    
    /**
     * Extract all variable names from a template.
     * 
     * @param template Template string
     * @return List of all variable names (including those with defaults)
     */
    fun extractVariableNames(template: String): List<String> {
        // Remove escaped literals first
        val unescaped = template.replace("$${", "ESCAPED_START").replace("}$$", "ESCAPED_END")
        
        val pattern = Regex("""\$\{([^}:]+)(?::[^}]*)?\}""")
        
        return pattern.findAll(unescaped)
            .map { it.groupValues[1] }
            .distinct()
    }
    
    /**
     * Check if a template has any unresolved variables.
     * 
     * @param template Template string
     * @param variables Variable name → value mapping
     * @return true if all variables are resolved
     */
    fun isFullyResolved(template: String, variables: Map<String, String>): Boolean =
        findUnresolved(template, variables).isEmpty()
    
    /**
     * Validate that all required variables are present.
     * 
     * @param template Template string
     * @param requiredVariables List of required variable names
     * @param providedVariables Variable name → value mapping
     * @return List of missing required variables
     */
    fun validateRequired(
        template: String,
        requiredVariables: List<String>,
        providedVariables: Map<String, String>
    ): List<String> {
        val allVariables = extractVariableNames(template)
        return requiredVariables.filter { it in allVariables && it !in providedVariables }
    }
}
