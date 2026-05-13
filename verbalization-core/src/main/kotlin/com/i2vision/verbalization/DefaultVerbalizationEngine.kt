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
 * 
 * ## Features:
 * - Two-tier hashing (local + context) for accurate cache invalidation
 * - Strategy fallback chain (LEARNING → MULTI_PASS → INCREMENTAL)
 * - Batch processing for LLM efficiency
 * - Persistent hash storage for incremental sync
 */
class DefaultVerbalizationEngine(
    private val verbalizationStore: VerbalizationStore,
    private val patternMatcher: PatternMatcher = PatternMatcher(),
    private val hashManager: HashManager = HashManager(),
    private val feedbackStore: FeedbackStore = FeedbackStore(),
    private val llmClient: LlmVerbalizationClient = DefaultLlmVerbalizationClient()
) : VerbalizationEngine {

    // Lazy initialization of strategies with proper constructor injection
    private val strategies by lazy {
        mapOf(
            VerbalizationStrategy.INCREMENTAL to IncrementalVerbalizationStrategy(
                patternMatcher, 
                hashManager
            ),
            VerbalizationStrategy.MULTI_PASS to MultiPassVerbalizationStrategy(
                patternMatcher,
                CrossLayerContextProvider(verbalizationStore),
                hashManager
            ),
            VerbalizationStrategy.LEARNING to LearningVerbalizationStrategy(
                patternMatcher,
                llmClient,
                feedbackStore,
                hashManager,
                timeoutMs = 5000,
                batchSize = 100
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

        // Save updated hashes (both local and context)
        saveHashesForCluster(clusterId)

        return results
    }

    override fun needsReverbalization(symbol: Symbol, currentHash: String): Boolean {
        return hashManager.hasLocalChanged(symbol, currentHash)
    }

    /**
     * Check if symbol needs re-verbalization including context changes.
     */
    fun needsReverbalizationWithContext(symbol: Symbol, currentLocalHash: String, currentContextHash: String): Boolean {
        return hashManager.hasChanged(symbol, currentLocalHash, currentContextHash)
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
        val localHashes = verbalizationStore.getHashes(clusterId)
        if (localHashes != null) {
            hashManager.loadHashes(localHashes)
        }
        
        val contextHashes = verbalizationStore.getContextHashes(clusterId)
        if (contextHashes != null) {
            hashManager.loadContextHashes(contextHashes)
        }
    }

    /**
     * Save current hashes for a cluster to persistent storage.
     */
    private suspend fun saveHashesForCluster(clusterId: String) {
        val localHashes = hashManager.getAllHashes()
        verbalizationStore.putHashes(clusterId, localHashes)
        
        val contextHashes = hashManager.getAllContextHashes()
        verbalizationStore.putContextHashes(clusterId, contextHashes)
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
 * Cross-layer context provider for multi-pass strategy.
 * Integrates with discovery results to provide rich context from Flow, Logic, and Structure layers.
 */
class CrossLayerContextProvider(
    private val verbalizationStore: VerbalizationStore
) : ContextProvider {
    
    override fun getContext(symbol: Symbol): SymbolContext {
        // Extract dependencies from symbol content
        val dependencies = extractDependencies(symbol.content)
        val relatedSymbols = extractRelatedSymbols(symbol.content)
        
        // Detect architectural layer
        val layer = detectArchitecturalLayer(symbol.filePath)
        
        // Get flow information (calling sequences)
        val callingFlows = getCallingFlows(symbol)
        
        // Get business rules from Logic layer
        val businessRules = getBusinessRules(symbol)
        
        return SymbolContext(
            relatedSymbols = relatedSymbols,
            moduleDependencies = dependencies,
            hasDatabaseAccess = symbol.content.contains("database") || 
                               symbol.content.contains("repository") ||
                               symbol.content.contains("EntityManager") ||
                               symbol.content.contains("@Repository"),
            hasExternalCalls = symbol.content.contains("http") || 
                              symbol.content.contains("client") ||
                              symbol.content.contains("HttpClient") ||
                              symbol.content.contains("RestTemplate"),
            callingFlows = callingFlows,
            businessRules = businessRules,
            architecturalLayer = layer
        )
    }
    
    private fun extractDependencies(content: String): List<String> {
        val importPattern = Regex("""import\s+([\w.]+)""")
        return importPattern.findAll(content)
            .map { it.groupValues[1] }
            .filter { !it.startsWith("kotlin") && !it.startsWith("java") }
            .take(10)
            .toList()
    }
    
    private fun extractRelatedSymbols(content: String): List<Symbol> {
        // Extract method/class calls from content
        val callPattern = Regex("""(\w+)\(""")
        return callPattern.findAll(content)
            .map { it.groupValues[1] }
            .filter { it.first().isUpperCase() }
            .distinct()
            .take(10)
            .map { name -> 
                Symbol(
                    name = name,
                    kind = SymbolKind.CLASS,
                    filePath = "unknown",
                    lineNumber = 0,
                    content = "",
                    metadata = emptyMap()
                )
            }
    }
    
    private fun detectArchitecturalLayer(filePath: String): String {
        return when {
            filePath.contains("/controller") || filePath.contains("/web") -> "presentation"
            filePath.contains("/service") || filePath.contains("/business") -> "domain"
            filePath.contains("/repository") || filePath.contains("/data") -> "data"
            filePath.contains("/config") -> "configuration"
            filePath.contains("/util") || filePath.contains("/common") -> "utility"
            else -> "unknown"
        }
    }
    
    private fun getCallingFlows(symbol: Symbol): List<String> {
        // In real implementation, query Flow layer artifacts from storage
        // For now, return empty list - this will be populated when integrated with discovery
        return emptyList()
    }
    
    private fun getBusinessRules(symbol: Symbol): List<String> {
        // In real implementation, extract from Logic layer artifacts
        // Look for require(), check(), if statements with validation logic
        val rulePattern = Regex("""(?:require|check|ensure)\s*\(([^)]+)\)""")
        return rulePattern.findAll(symbol.content)
            .map { it.groupValues[1] }
            .take(5)
            .toList()
    }
}
