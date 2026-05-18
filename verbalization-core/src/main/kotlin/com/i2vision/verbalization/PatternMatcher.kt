/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.verbalization

import com.i2vision.vslfc.Symbol
import com.i2vision.vslfc.VerbalizationPattern

/**
 * Matches code patterns and generates descriptions.
 * Supports both built-in patterns and custom user-defined patterns.
 */
class PatternMatcher {

    private val patterns = mutableListOf<VerbalizationPattern>()

    init {
        // Initialize with built-in patterns
        loadBuiltInPatterns()
    }

    /**
     * Match a symbol against registered patterns and generate description.
     * Falls back to a basic kind-based description if no pattern matches.
     */
    fun matchAndDescribe(symbol: Symbol): String? {
        for (pattern in patterns.sortedByDescending { it.priority }) {
            val regex = Regex(pattern.codePattern)
            val match = regex.find(symbol.content) ?: regex.find(symbol.name)

            if (match != null) {
                return applyTemplate(pattern.description, match.groupValues.drop(1))
            }
        }
        // Fallback: generate a basic description from symbol kind and name
        return generateFallbackDescription(symbol)
    }

    /**
     * Generate a basic fallback description when no pattern matches.
     */
    private fun generateFallbackDescription(symbol: Symbol): String {
        return when (symbol.kind) {
            com.i2vision.vslfc.SymbolKind.CLASS -> "Class '${symbol.name}'"
            com.i2vision.vslfc.SymbolKind.INTERFACE -> "Interface '${symbol.name}'"
            com.i2vision.vslfc.SymbolKind.FUNCTION -> "Function '${symbol.name}'"
            com.i2vision.vslfc.SymbolKind.PROPERTY -> "Property '${symbol.name}'"
            com.i2vision.vslfc.SymbolKind.VARIABLE -> "Variable '${symbol.name}'"
            com.i2vision.vslfc.SymbolKind.ANNOTATION -> "Annotation '${symbol.name}'"
            com.i2vision.vslfc.SymbolKind.ENUM -> "Enum '${symbol.name}'"
            com.i2vision.vslfc.SymbolKind.OBJECT -> "Object '${symbol.name}'"
            com.i2vision.vslfc.SymbolKind.TYPE_ALIAS -> "Type alias '${symbol.name}'"
            else -> "Symbol '${symbol.name}'"
        }
    }

    /**
     * Register a custom pattern.
     */
    fun registerPattern(pattern: VerbalizationPattern) {
        patterns.add(pattern)
    }

    /**
     * Load custom patterns from configuration.
     */
    fun loadCustomPatterns(customPatterns: List<VerbalizationPattern>) {
        patterns.addAll(customPatterns)
    }

    /**
     * Apply template with captured groups.
     */
    private fun applyTemplate(template: String, captures: List<String>): String {
        var result = template
        captures.forEachIndexed { index, capture ->
            result = result.replace("\$${index + 1}", capture)
        }
        return result
    }

    /**
     * Load built-in verbalization patterns.
     */
    private fun loadBuiltInPatterns() {
        val builtInPatterns = listOf(
            // Class patterns
            VerbalizationPattern(
                codePattern = "class\\s+(\\w+)Service",
                description = "Service class for $1 operations",
                confidence = 0.9,
                priority = 10
            ),
            VerbalizationPattern(
                codePattern = "class\\s+(\\w+)Controller",
                description = "REST controller for $1 endpoints",
                confidence = 0.9,
                priority = 10
            ),
            VerbalizationPattern(
                codePattern = "class\\s+(\\w+)Repository",
                description = "Data repository for $1 entities",
                confidence = 0.9,
                priority = 10
            ),

            // Function patterns
            VerbalizationPattern(
                codePattern = "fun\\s+(\\w+)\\(\\)",
                description = "Executes $1 operation",
                confidence = 0.7,
                priority = 5
            ),
            VerbalizationPattern(
                codePattern = "suspend\\s+fun\\s+(\\w+)\\(",
                description = "Asynchronously executes $1 operation",
                confidence = 0.8,
                priority = 5
            ),

            // Annotation patterns
            VerbalizationPattern(
                codePattern = "@RestController",
                description = "REST API endpoint",
                confidence = 0.95,
                priority = 15
            ),
            VerbalizationPattern(
                codePattern = "@Service",
                description = "Spring service component",
                confidence = 0.9,
                priority = 15
            ),
            VerbalizationPattern(
                codePattern = "@Repository",
                description = "Data access component",
                confidence = 0.9,
                priority = 15
            ),

            // Validation patterns
            VerbalizationPattern(
                codePattern = "require\\((.+)\\)",
                description = "Validates that $1",
                confidence = 0.85,
                priority = 8
            ),
            VerbalizationPattern(
                codePattern = "check\\((.+)\\)",
                description = "Ensures that $1",
                confidence = 0.8,
                priority = 8
            ),

            // Kotlin-specific patterns
            VerbalizationPattern(
                codePattern = "data\\s+class",
                description = "Immutable data container",
                confidence = 0.9,
                priority = 12
            ),
            VerbalizationPattern(
                codePattern = "sealed\\s+class",
                description = "Sealed class for restricted inheritance",
                confidence = 0.9,
                priority = 12
            )
        )

        patterns.addAll(builtInPatterns)
    }
}
