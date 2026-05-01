/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.vslfc

import kotlinx.serialization.Serializable

/**
 * A code symbol that can be verbalized.
 */
@Serializable
data class Symbol(
    val name: String,
    val kind: SymbolKind,
    val filePath: String,
    val lineNumber: Int,
    val content: String,
    val metadata: Map<String, String> = emptyMap()
)

/**
 * Type of code symbol.
 */
@Serializable
enum class SymbolKind {
    CLASS,
    INTERFACE,
    FUNCTION,
    PROPERTY,
    VARIABLE,
    ANNOTATION,
    ENUM,
    OBJECT,
    TYPE_ALIAS,
    UNKNOWN
}

/**
 * Result of verbalizing a symbol.
 */
@Serializable
data class VerbalizationResult(
    val symbol: Symbol,
    val description: String,
    val confidence: Double,
    val strategy: VerbalizationStrategy,
    val metadata: Map<String, String> = emptyMap(),
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Verbalization strategy defines when and how to verbalize symbols.
 */
@Serializable
enum class VerbalizationStrategy {
    /**
     * Only verbalize changed symbols (fastest, default).
     * Uses hash-based change detection.
     */
    INCREMENTAL,

    /**
     * Verbalize all symbols with module/architecture context.
     * Refines descriptions using broader context.
     */
    MULTI_PASS,

    /**
     * Use LLM with feedback patterns for highest quality.
     * Learns from user corrections.
     */
    LEARNING
}

/**
 * Custom verbalization pattern for user-defined mappings.
 */
@Serializable
data class VerbalizationPattern(
    val codePattern: String, // Regex pattern to match code
    val description: String, // Description template (can use $1, $2 for captures)
    val confidence: Double,
    val priority: Int = 0,
    val metadata: Map<String, String> = emptyMap()
)
