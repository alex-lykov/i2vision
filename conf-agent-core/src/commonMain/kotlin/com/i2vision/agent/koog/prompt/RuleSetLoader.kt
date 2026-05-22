/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.agent.koog.prompt

import com.i2vision.agent.VslfcLayer

/**
 * Loads named rule sets that constrain agent behavior.
 * 
 * Rule sets are reusable collections of rules that can be applied
 * to prompts. They provide consistent behavior across different
 * agents and tasks.
 * 
 * ## Built-in Rule Sets
 * 
 * - **code-style-kotlin** - Kotlin coding conventions
 * - **code-style-typescript** - TypeScript coding conventions
 * - **minimal-changes** - Make smallest possible changes
 * - **security-critical** - Security best practices
 * - **test-aware** - Consider test implications
 * - **performance-conscious** - Performance considerations
 * - **documentation-required** - Documentation standards
 * 
 * ## Layer-Specific Rules
 * 
 * Each VSLFC layer has specific rules:
 * - **CODE** - Read before editing, verify compilation
 * - **FLOW** - Trace complete call chains
 * - **LOGIC** - Identify invariants and edge cases
 * - **STRUCTURE** - Read-only analysis, reference specifics
 * - **VISION** - Map to requirements, identify gaps
 * 
 * ## Usage
 * 
 * ```kotlin
 * val loader = RuleSetLoader()
 * 
 * // Load by name
 * val codeStyle = loader.load("code-style-kotlin")
 * 
 * // Load for layer
 * val layerRules = loader.loadForLayer(VslfcLayer.CODE)
 * 
 * // Load common rules
 * val common = loader.loadCommon()
 * ```
 */
class RuleSetLoader {
    
    /**
     * Built-in rule sets by name.
     */
    private val builtInRules = mapOf(
        "code-style-kotlin" to RuleSet(
            name = "Kotlin Code Style",
            rules = listOf(
                "Use Kotlin idioms: data classes, sealed classes, extension functions",
                "Prefer immutability: val over var, immutable collections",
                "Use null safety: avoid !! operator, use ?. and ?: instead",
                "Follow Kotlin naming conventions: camelCase for functions, PascalCase for classes",
                "Use meaningful names that describe intent",
                "Keep functions small and focused on a single task",
                "Document public APIs with KDoc comments"
            )
        ),
        
        "code-style-typescript" to RuleSet(
            name = "TypeScript Code Style",
            rules = listOf(
                "Use TypeScript types and interfaces for type safety",
                "Prefer const over let, avoid var",
                "Use async/await for asynchronous code",
                "Follow TypeScript naming conventions: camelCase for variables/functions, PascalCase for types",
                "Use explicit return types for functions",
                "Handle errors with try/catch or proper error propagation",
                "Document public APIs with JSDoc comments"
            )
        ),
        
        "minimal-changes" to RuleSet(
            name = "Minimal Changes",
            rules = listOf(
                "Make the smallest possible change to achieve the goal",
                "Do not refactor code unrelated to the task",
                "Preserve existing code style and formatting",
                "Only modify files directly related to the task",
                "Avoid cosmetic changes unless explicitly requested",
                "Respect the existing architecture and patterns"
            )
        ),
        
        "security-critical" to RuleSet(
            name = "Security Critical",
            rules = listOf(
                "Validate all inputs before processing",
                "Do not hardcode secrets, API keys, or credentials",
                "Use parameterized queries, never string concatenation for SQL",
                "Sanitize data before output to prevent injection attacks",
                "Use secure random number generation for tokens and IDs",
                "Implement proper authentication and authorization checks",
                "Log security-relevant events without exposing sensitive data"
            )
        ),
        
        "test-aware" to RuleSet(
            name = "Test Aware",
            rules = listOf(
                "Consider test implications of all changes",
                "Do not break existing test patterns",
                "Suggest test updates when modifying public APIs",
                "Maintain test coverage for modified code",
                "Write tests that verify behavior, not implementation",
                "Use descriptive test names that explain the scenario"
            )
        ),
        
        "performance-conscious" to RuleSet(
            name = "Performance Conscious",
            rules = listOf(
                "Consider algorithmic complexity (Big O) of changes",
                "Avoid unnecessary allocations in hot paths",
                "Use appropriate data structures for the use case",
                "Consider caching for expensive operations",
                "Profile before optimizing premature",
                "Document performance characteristics of public APIs"
            )
        ),
        
        "documentation-required" to RuleSet(
            name = "Documentation Required",
            rules = listOf(
                "Document all public APIs with clear descriptions",
                "Include usage examples for complex functionality",
                "Document edge cases and error conditions",
                "Keep documentation up to date with code changes",
                "Use clear, concise language",
                "Reference related documentation and resources"
            )
        ),
        
        "error-handling" to RuleSet(
            name = "Error Handling",
            rules = listOf(
                "Handle errors gracefully with meaningful messages",
                "Use specific exception types for different error scenarios",
                "Log errors with sufficient context for debugging",
                "Provide recovery options when possible",
                "Fail fast on unrecoverable errors",
                "Validate inputs at API boundaries"
            )
        )
    )
    
    /**
     * Layer-specific rules.
     */
    private val layerRules = mapOf(
        VslfcLayer.CODE to RuleSet(
            name = "Code Layer Rules",
            rules = listOf(
                "Always read a file before editing it",
                "Use i2vision_get_context before making changes",
                "Verify that edits compile before marking task complete",
                "Use edit_file for targeted changes, write_file only for new files",
                "Preserve existing code structure unless refactoring is requested",
                "Test changes locally if possible"
            )
        ),
        
        VslfcLayer.FLOW to RuleSet(
            name = "Flow Layer Rules",
            rules = listOf(
                "Trace complete call chains from entry to exit",
                "Identify all participants in a flow",
                "Map data transformations at each step",
                "Reference specific files and line numbers",
                "Document both happy path and error paths",
                "Use i2vision_discover for flow mapping"
            )
        ),
        
        VslfcLayer.LOGIC to RuleSet(
            name = "Logic Layer Rules",
            rules = listOf(
                "Identify preconditions, postconditions, and invariants",
                "Validate that logic implements the requirements correctly",
                "Consider edge cases and boundary conditions",
                "Document business rules explicitly",
                "Verify that validation logic is comprehensive",
                "Ensure error handling is appropriate"
            )
        ),
        
        VslfcLayer.STRUCTURE to RuleSet(
            name = "Structure Layer Rules",
            rules = listOf(
                "Do not modify files — this is a read-only analysis layer",
                "Use i2vision_discover for full architectural context",
                "Reference specific files and line numbers in analysis",
                "Analyze cohesion and coupling metrics",
                "Identify architectural patterns and anti-patterns",
                "Consider impact of changes on dependent modules"
            )
        ),
        
        VslfcLayer.VISION to RuleSet(
            name = "Vision Layer Rules",
            rules = listOf(
                "Map code back to requirements and identify gaps",
                "Validate that the implementation satisfies the vision",
                "Identify missing features or acceptance criteria",
                "Consider stakeholder needs and priorities",
                "Document assumptions and constraints",
                "Ensure alignment with business goals"
            )
        )
    )
    
    /**
     * Common rules applied to all layers.
     */
    private val commonRules = RuleSet(
        name = "Common Rules",
        rules = listOf(
            "Be helpful, harmless, and honest",
            "Ask for clarification when requirements are ambiguous",
            "Explain your reasoning before making changes",
            "Respect the existing codebase conventions",
            "Use tools appropriately and efficiently",
            "Communicate progress and blockers clearly"
        )
    )
    
    /**
     * Load a rule set by name.
     * 
     * @param key Rule set name (e.g., "code-style-kotlin")
     * @return Rule set or null if not found
     */
    fun load(key: String): RuleSet? = builtInRules[key.lowercase()]
    
    /**
     * Load rules for a specific VSLFC layer.
     * 
     * @param layer VSLFC layer
     * @return Layer-specific rules or null if not found
     */
    fun loadForLayer(layer: VslfcLayer): RuleSet? = layerRules[layer]
    
    /**
     * Load common rules applied to all layers.
     * 
     * @return Common rules
     */
    fun loadCommon(): RuleSet = commonRules
    
    /**
     * Get all available built-in rule set names.
     * 
     * @return List of rule set names
     */
    fun getAvailableRuleSets(): List<String> = builtInRules.keys.toList()
    
    /**
     * Check if a rule set exists.
     * 
     * @param key Rule set name
     * @return true if rule set exists
     */
    fun exists(key: String): Boolean = builtInRules.containsKey(key.lowercase())
    
    /**
     * Create a custom rule set.
     * 
     * @param name Rule set name
     * @param rules List of rules
     * @return Custom rule set
     */
    fun createCustom(name: String, rules: List<String>): RuleSet =
        RuleSet(name = name, rules = rules)
    
    /**
     * Create a custom rule set from varargs.
     */
    fun createCustom(name: String, vararg rules: String): RuleSet =
        RuleSet(name = name, rules = rules.toList())
}
