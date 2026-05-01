/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.verbalization

import com.i2vision.vslfc.Symbol
import com.i2vision.vslfc.VerbalizationPattern
import java.security.MessageDigest

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
     */
    fun matchAndDescribe(symbol: Symbol): String? {
        for (pattern in patterns.sortedByDescending { it.priority }) {
            val regex = Regex(pattern.codePattern)
            val match = regex.find(symbol.content) ?: regex.find(symbol.name)

            if (match != null) {
                return applyTemplate(pattern.description, match.groupValues.drop(1))
            }
        }
        return null
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

/**
 * Manages content hashes for incremental verbalization.
 * Tracks which symbols have changed since last verbalization.
 */
class HashManager {

    private val hashes = mutableMapOf<String, String>()

    /**
     * Compute hash for a symbol based on its content.
     */
    fun computeHash(symbol: Symbol): String {
        val content = "${symbol.name}:${symbol.content}:${symbol.metadata}"
        return MessageDigest.getInstance("SHA-256")
            .digest(content.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    /**
     * Check if a symbol has changed since last verbalization.
     */
    fun hasChanged(symbol: Symbol, currentHash: String): Boolean {
        val key = getSymbolKey(symbol)
        val previousHash = hashes[key]
        return previousHash != currentHash
    }

    /**
     * Update the hash for a symbol after verbalization.
     */
    fun updateHash(symbol: Symbol, hash: String) {
        val key = getSymbolKey(symbol)
        hashes[key] = hash
    }

    /**
     * Load hashes from persistent storage.
     */
    fun loadHashes(storedHashes: Map<String, String>) {
        hashes.putAll(storedHashes)
    }

    /**
     * Get all current hashes for persistence.
     */
    fun getAllHashes(): Map<String, String> = hashes.toMap()

    /**
     * Generate unique key for a symbol.
     */
    private fun getSymbolKey(symbol: Symbol): String {
        return "${symbol.filePath}:${symbol.lineNumber}:${symbol.name}"
    }
}
