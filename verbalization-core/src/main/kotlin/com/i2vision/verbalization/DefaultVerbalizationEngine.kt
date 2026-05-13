/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.verbalization

import com.i2vision.intent.DiscoveryIntent
import com.i2vision.verbalization.feedback.FeedbackStore
import com.i2vision.verbalization.llm.DefaultLlmVerbalizationClient
import com.i2vision.verbalization.llm.LlmVerbalizationClient
import com.i2vision.verbalization.strategy.*
import com.i2vision.vslfc.*
import java.io.File

/**
 * Default implementation of VerbalizationEngine.
 * Orchestrates the verbalization process using configurable strategies.
 */
class DefaultVerbalizationEngine(
    private val verbalizationStore: VerbalizationStore,
    private val patternMatcher: PatternMatcher = PatternMatcher(),
    private val hashManager: HashManager = HashManager(),
    private val feedbackStore: FeedbackStore = FeedbackStore(),
    private val llmClient: LlmVerbalizationClient = DefaultLlmVerbalizationClient()
) : VerbalizationEngine {

    // Lazy initialization of strategies to allow proper constructor injection
    private val strategies by lazy {
        mapOf(
            VerbalizationStrategy.INCREMENTAL to IncrementalVerbalizationStrategy(patternMatcher, hashManager),
            VerbalizationStrategy.MULTI_PASS to MultiPassVerbalizationStrategy(
                patternMatcher,
                SimpleContextProvider(),
                hashManager
            ),
            VerbalizationStrategy.LEARNING to LearningVerbalizationStrategy(
                patternMatcher,
                llmClient,
                feedbackStore,
                hashManager
            )
        )
    }

    override suspend fun verbalize(
        clusterId: String,
        symbols: List<Symbol>,
        strategy: VerbalizationStrategy,
        intent: DiscoveryIntent
    ): List<VerbalizationResult> {

        // Load existing hashes for incremental checking
        loadHashesForCluster(clusterId)

        val strategyImpl = strategies[strategy]
            ?: throw IllegalArgumentException("Unsupported strategy: $strategy")

        val results = strategyImpl.verbalize(symbols, intent)

        // Store results
        storeResults(clusterId, results)

        // Save updated hashes
        saveHashesForCluster(clusterId)

        return results
    }

    override fun needsReverbalization(symbol: Symbol, currentHash: String): Boolean {
        return hashManager.hasChanged(symbol, currentHash)
    }

    override suspend fun getCachedVerbalization(symbol: Symbol): VerbalizationResult? {
        return verbalizationStore.getVerbalization(symbol)
    }

    override fun registerPattern(pattern: VerbalizationPattern) {
        patternMatcher.registerPattern(pattern)
    }

    override fun loadCustomPatterns(configFile: File) {
        if (!configFile.exists()) return

        try {
            patternMatcher.loadCustomPatterns(emptyList())
        } catch (e: Exception) {
            // Log error but don't fail
            println("Warning: Failed to load custom patterns from ${configFile.absolutePath}: ${e.message}")
        }
    }

    /**
     * Load hashes for a cluster from persistent storage.
     */
    private suspend fun loadHashesForCluster(clusterId: String) {
        val hashes = verbalizationStore.getHashes(clusterId)
        if (hashes != null) {
            hashManager.loadHashes(hashes)
        }
    }

    /**
     * Save current hashes for a cluster to persistent storage.
     */
    private suspend fun saveHashesForCluster(clusterId: String) {
        val hashes = hashManager.getAllHashes()
        verbalizationStore.putHashes(clusterId, hashes)
    }

    /**
     * Store verbalization results for a cluster.
     */
    private suspend fun storeResults(clusterId: String, results: List<VerbalizationResult>) {
        verbalizationStore.putVerbalizations(clusterId, results)
    }

    /**
     * Extract cluster ID from file path.
     */
    private fun extractClusterId(filePath: String): String {
        // Simple extraction - in real implementation this would be more sophisticated
        val parts = filePath.split("/")
        return if (parts.size >= 2) "${parts[0]}/${parts[1]}" else "unknown"
    }
}

/**
 * Simple context provider for multi-pass strategy.
 * In a real implementation, this would integrate with discovery results.
 */
class SimpleContextProvider : ContextProvider {
    override fun getContext(symbol: Symbol): SymbolContext {
        // Placeholder implementation
        return SymbolContext(
            relatedSymbols = emptyList(),
            moduleDependencies = emptyList(),
            hasDatabaseAccess = symbol.content.contains("database") || symbol.content.contains("repository"),
            hasExternalCalls = symbol.content.contains("http") || symbol.content.contains("client")
        )
    }
}