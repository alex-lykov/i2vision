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
        // Extract only the declaration line from symbol content.
        // ScannerService.extractBodyLines() grabs 60 lines forward, so symbols
        // in the same file share overlapping content. Matching against the full
        // content causes false positives (e.g. a property matching a later
        // function declaration in the same file).
        val declarationLine = symbol.content.lineSequence().firstOrNull()?.trim() ?: ""

        for (pattern in patterns.sortedByDescending { it.priority }) {
            val regex = Regex(pattern.codePattern)
            // Match against declaration line first, then fall back to symbol name
            val match = regex.find(declarationLine) ?: regex.find(symbol.name)

            if (match != null) {
                return applyTemplate(pattern.description, match.groupValues.drop(1))
            }
        }
        // Fallback: generate a basic description from symbol kind and name
        return generateFallbackDescription(symbol)
    }

    /**
     * Generate an enhanced fallback description when no pattern matches.
     * Converts camelCase/PascalCase names into readable sentences with
     * appropriate verbs to avoid tautological "Function 'X'" outputs.
     */
    private fun generateFallbackDescription(symbol: Symbol): String {
        return when (symbol.kind) {
            com.i2vision.vslfc.SymbolKind.CLASS -> {
                val words = symbol.name.camelCaseToWords()
                if (words.size > 1 && words.last().lowercase() in setOf("class", "object", "enum", "interface")) {
                    "${words.joinToString(" ")} for ${symbol.name}"
                } else {
                    "Class representing ${words.joinToString(" ")}"
                }
            }
            com.i2vision.vslfc.SymbolKind.INTERFACE -> "Interface defining ${symbol.name.camelCaseToWords().joinToString(" ")}"
            com.i2vision.vslfc.SymbolKind.FUNCTION -> generateFunctionDescription(symbol.name)
            com.i2vision.vslfc.SymbolKind.PROPERTY -> "Property holding ${symbol.name.camelCaseToWords().joinToString(" ")}"
            com.i2vision.vslfc.SymbolKind.VARIABLE -> "Variable holding ${symbol.name.camelCaseToWords().joinToString(" ")}"
            com.i2vision.vslfc.SymbolKind.ANNOTATION -> "Annotation '${symbol.name}'"
            com.i2vision.vslfc.SymbolKind.ENUM -> "Enum '${symbol.name.camelCaseToWords().joinToString(" ")}'"
            com.i2vision.vslfc.SymbolKind.OBJECT -> "Singleton object '${symbol.name.camelCaseToWords().joinToString(" ")}'"
            com.i2vision.vslfc.SymbolKind.TYPE_ALIAS -> "Type alias '${symbol.name.camelCaseToWords().joinToString(" ")}'"
            com.i2vision.vslfc.SymbolKind.UNKNOWN -> inferUnknownDescription(symbol.name)
        }
    }

    /**
     * Generate a function description from its name by converting camelCase
     * to words and prepending an appropriate action verb.
     */
    private fun generateFunctionDescription(name: String): String {
        val words = name.camelCaseToWords()
        if (words.isEmpty()) return "Function '$name'"

        val firstWord = words.first().lowercase()
        val remaining = words.drop(1).joinToString(" ")

        // If the name already starts with an action verb, just capitalize it
        val actionVerb = when (firstWord) {
            "get" -> "Gets"
            "set" -> "Sets"
            "is", "has", "can", "should", "will" -> "Checks"
            "create", "make", "build", "generate" -> "Creates"
            "delete", "remove", "clear", "purge" -> "Removes"
            "update", "modify", "edit", "change" -> "Updates"
            "find", "search", "lookup", "locate" -> "Finds"
            "parse", "deserialize", "extract" -> "Parses"
            "validate", "check", "ensure", "verify" -> "Validates"
            "convert", "transform", "map", "to" -> "Converts"
            "load", "fetch", "read", "retrieve" -> "Loads"
            "save", "store", "write", "persist" -> "Saves"
            "send", "publish", "emit", "dispatch" -> "Sends"
            "receive", "accept", "consume", "handle" -> "Receives"
            "process", "execute", "run", "invoke", "call", "perform" -> "Executes"
            "show", "display", "print", "render", "draw" -> "Displays"
            "clean", "sanitize", "normalize", "format" -> "Cleans"
            "fallback" -> "Falls back to"
            "bridge" -> "Bridges"
            "map" -> "Maps"
            "main" -> "Main entry point"
            "println" -> "Prints line"
            else -> null
        }

        return when {
            actionVerb == "Main entry point" -> actionVerb
            actionVerb == "Prints line" -> "$actionVerb to output"
            actionVerb != null && remaining.isNotEmpty() -> "$actionVerb $remaining"
            actionVerb != null -> actionVerb
            // No recognized verb — infer from naming convention
            name.startsWith("is") && name.length > 2 && name[2].isUpperCase() ->
                "Checks ${name.substring(2).camelCaseToWords().joinToString(" ")}"
            name.startsWith("has") && name.length > 3 && name[3].isUpperCase() ->
                "Checks ${name.substring(3).camelCaseToWords().joinToString(" ")}"
            name.startsWith("to") && name.length > 2 && name[2].isUpperCase() ->
                "Converts to ${name.substring(2).camelCaseToWords().joinToString(" ")}"
            else -> "Executes ${words.joinToString(" ")}"
        }
    }

    /**
     * Infer a description for an UNKNOWN kind symbol based on its name.
     */
    private fun inferUnknownDescription(name: String): String {
        return when {
            name.first().isUpperCase() && name.all { it.isUpperCase() || it == '_' } ->
                "Constant '$name'"
            name.first().isUpperCase() ->
                "Type '$name'"
            name.contains("(") ->
                "Function '$name'"
            else ->
                "Symbol '$name'"
        }
    }

    /**
     * Convert camelCase or PascalCase to space-separated lowercase words.
     * Handles acronyms like "URL" or "HTTP" gracefully.
     */
    private fun String.camelCaseToWords(): List<String> {
        if (isEmpty()) return emptyList()

        val result = mutableListOf<String>()
        val currentWord = StringBuilder()

        for (i in indices) {
            val ch = this[i]
            val prev = if (i > 0) this[i - 1] else null
            val next = if (i + 1 < length) this[i + 1] else null

            // Start of new word: uppercase letter preceded by lowercase, or
            // uppercase letter followed by lowercase (end of acronym)
            val isNewWord = when {
                i == 0 -> false
                ch.isUpperCase() && prev?.isLowerCase() == true -> true
                ch.isUpperCase() && next?.isLowerCase() == true && prev?.isUpperCase() == true -> true
                ch == '_' || ch == '-' -> true
                else -> false
            }

            if (isNewWord && currentWord.isNotEmpty()) {
                result.add(currentWord.toString().lowercase())
                currentWord.clear()
            }

            if (ch.isLetterOrDigit()) {
                currentWord.append(ch)
            }
        }

        if (currentWord.isNotEmpty()) {
            result.add(currentWord.toString().lowercase())
        }

        return result
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
            VerbalizationPattern(
                codePattern = "@Component",
                description = "Spring-managed component",
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
