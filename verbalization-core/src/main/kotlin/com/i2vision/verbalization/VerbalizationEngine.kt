/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.verbalization

import com.i2vision.intent.DiscoveryIntent
import com.i2vision.vslfc.Symbol
import com.i2vision.vslfc.VerbalizationPattern
import com.i2vision.vslfc.VerbalizationResult
import com.i2vision.vslfc.VerbalizationStrategy
import java.io.File

/**
 * Core verbalization engine that transforms code patterns into natural language descriptions.
 * Verbalization is separate from contracts - it describes what code does, contracts validate consistency.
 */
interface VerbalizationEngine {

    /**
     * Verbalize symbols for a cluster using the specified strategy and intent.
     *
     * @param clusterId The cluster identifier (e.g., "core/orchestrator")
     * @param symbols List of symbols to verbalize
     * @param strategy The verbalization strategy to use
     * @param intent The discovery intent containing verbalization configuration
     * @return Verbalization results for each symbol
     */
    suspend fun verbalize(
        clusterId: String,
        symbols: List<Symbol>,
        strategy: VerbalizationStrategy,
        intent: DiscoveryIntent
    ): List<VerbalizationResult>

    /**
     * Check if a symbol needs re-verbalization based on hash changes.
     *
     * @param symbol The symbol to check
     * @param currentHash The current content hash of the symbol
     * @return true if the symbol needs re-verbalization
     */
    fun needsReverbalization(symbol: Symbol, currentHash: String): Boolean

    /**
     * Get cached verbalization for a symbol if available.
     *
     * @param symbol The symbol to get verbalization for
     * @return Cached verbalization result or null if not available
     */
    suspend fun getCachedVerbalization(symbol: Symbol): VerbalizationResult?

    /**
     * Register a custom verbalization pattern.
     *
     * @param pattern The custom pattern to register
     */
    fun registerPattern(pattern: VerbalizationPattern)

    /**
     * Load custom patterns from configuration file.
     *
     * @param configFile The configuration file containing custom patterns
     */
    fun loadCustomPatterns(configFile: File)
}
