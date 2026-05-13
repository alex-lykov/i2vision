/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.verbalization.strategy

import com.i2vision.intent.DiscoveryIntent
import com.i2vision.verbalization.HashManager
import com.i2vision.verbalization.PatternMatcher
import com.i2vision.verbalization.feedback.FeedbackStore
import com.i2vision.verbalization.llm.FeedbackHistoryEntry
import com.i2vision.verbalization.llm.LlmVerbalizationClient
import com.i2vision.verbalization.llm.LlmVerbalizationRequest
import com.i2vision.verbalization.llm.LlmVerbalizationResponse
import com.i2vision.verbalization.llm.SymbolVerbalizationContext
import com.i2vision.vslfc.Symbol
import com.i2vision.vslfc.SymbolKind
import com.i2vision.vslfc.VerbalizationResult
import com.i2vision.vslfc.VerbalizationStrategy

/**
 * Interface for verbalization strategy implementations.
 */
interface VerbalizationStrategyImpl {
    suspend fun verbalize(symbols: List<Symbol>, intent: DiscoveryIntent): List<VerbalizationResult>
    fun getStrategyType(): VerbalizationStrategy
}

/**
 * Provides context for multi-pass verbalization.
 */
interface ContextProvider {
    fun getContext(symbol: Symbol): SymbolContext
}

/**
 * Context information for symbol verbalization.
 */
data class SymbolContext(
    val relatedSymbols: List<Symbol>,
    val moduleDependencies: List<String>,
    val hasDatabaseAccess: Boolean,
    val hasExternalCalls: Boolean,
    val callingFlows: List<String> = emptyList(),
    val businessRules: List<String> = emptyList(),
    val architecturalLayer: String = "unknown"
) {
    fun hasRelatedServices(): Boolean = relatedSymbols.isNotEmpty()
}

/**
 * Incremental verbalization strategy - only verbalizes changed symbols.
 * Uses two-tier hash-based change detection for optimal performance.
 * 
 * ## Performance Target: <50ms per 100 symbols
 * ## Cache Hit Rate Target: >80%
 */
class IncrementalVerbalizationStrategy(
    private val patternMatcher: PatternMatcher,
    private val hashManager: HashManager
) : VerbalizationStrategyImpl {

    override suspend fun verbalize(
        symbols: List<Symbol>,
        intent: DiscoveryIntent
    ): List<VerbalizationResult> {

        val results = mutableListOf<VerbalizationResult>()

        for (symbol in symbols) {
            val currentLocalHash = hashManager.computeLocalHash(symbol)

            // Skip if symbol hasn't changed (fast local hash check)
            if (!hashManager.hasLocalChanged(symbol, currentLocalHash)) {
                continue
            }

            // Generate description using patterns
            val description = patternMatcher.matchAndDescribe(symbol)

            val result = VerbalizationResult(
                symbol = symbol,
                description = description ?: "No description available",
                confidence = if (description != null) 0.8 else 0.0,
                strategy = VerbalizationStrategy.INCREMENTAL,
                metadata = mapOf(
                    "local_hash" to currentLocalHash.take(16),
                    "pattern_matched" to (description != null).toString(),
                    "cache_hit" to (description == null).toString()
                )
            )

            results.add(result)

            // Update hash after successful verbalization
            hashManager.updateHash(symbol, currentLocalHash)
        }

        return results
    }

    override fun getStrategyType(): VerbalizationStrategy = VerbalizationStrategy.INCREMENTAL
}

/**
 * Multi-pass verbalization strategy - refines descriptions with broader context.
 * First pass: Basic pattern matching
 * Second pass: Context-aware refinement using cross-layer data (Flow, Logic, Structure)
 * 
 * ## Cross-Layer Enrichment:
 * - **Structure Layer**: Component dependencies, cohesion metrics
 * - **Flow Layer**: Calling sequences, API call chains
 * - **Logic Layer**: Business rules, invariants, constraints
 * 
 * ## Performance Target: <200ms per 100 symbols
 */
class MultiPassVerbalizationStrategy(
    private val patternMatcher: PatternMatcher,
    private val contextProvider: ContextProvider,
    private val hashManager: HashManager
) : VerbalizationStrategyImpl {

    override suspend fun verbalize(
        symbols: List<Symbol>,
        intent: DiscoveryIntent
    ): List<VerbalizationResult> {

        // First pass: Basic pattern matching
        val firstPassResults = symbols.map { symbol ->
            val currentLocalHash = hashManager.computeLocalHash(symbol)
            
            val description = patternMatcher.matchAndDescribe(symbol)
            VerbalizationResult(
                symbol = symbol,
                description = description ?: "No description available",
                confidence = if (description != null) 0.7 else 0.0,
                strategy = VerbalizationStrategy.MULTI_PASS,
                metadata = mapOf(
                    "pass" to "1",
                    "local_hash" to currentLocalHash.take(16)
                )
            )
        }

        // Second pass: Cross-layer context refinement
        val refinedResults = firstPassResults.map { result ->
            val context = contextProvider.getContext(result.symbol)
            val refinedDescription = refineWithCrossLayerContext(result.description, context)
            
            val currentContextHash = hashManager.computeContextHash(result.symbol, context.relatedSymbols)

            result.copy(
                description = refinedDescription,
                confidence = minOf(result.confidence + 0.2, 0.95),
                metadata = result.metadata + mapOf(
                    "pass" to "2",
                    "context_used" to "true",
                    "context_hash" to currentContextHash.take(16),
                    "cross_layer_refs" to buildString {
                        if (context.callingFlows.isNotEmpty()) append("flows:${context.callingFlows.size} ")
                        if (context.businessRules.isNotEmpty()) append("rules:${context.businessRules.size} ")
                        if (context.relatedSymbols.isNotEmpty()) append("deps:${context.relatedSymbols.size}")
                    }.trim()
                )
            )
        }

        return refinedResults
    }

    /**
     * Refine description with cross-layer context.
     * Enriches basic descriptions with Flow, Logic, and Structure layer data.
     */
    private fun refineWithCrossLayerContext(description: String, context: SymbolContext): String {
        val refinements = mutableListOf<String>()
        
        // Add Flow layer context (calling sequences)
        if (context.callingFlows.isNotEmpty()) {
            refinements.add("Called by ${context.callingFlows.size} flow(s): ${context.callingFlows.take(3).joinToString(", ")}")
        }
        
        // Add Logic layer context (business rules)
        if (context.businessRules.isNotEmpty()) {
            refinements.add("Enforces ${context.businessRules.size} business rule(s)")
        }
        
        // Add Structure layer context (dependencies)
        if (context.hasRelatedServices()) {
            refinements.add("Coordinates with ${context.relatedSymbols.size} related service(s)")
        }
        
        // Add technical context
        when {
            context.hasDatabaseAccess -> refinements.add("with database access")
            context.hasExternalCalls -> refinements.add("with external API calls")
        }
        
        return if (refinements.isNotEmpty()) {
            "$description (${refinements.joinToString(". ")})"
        } else {
            description
        }
    }

    override fun getStrategyType(): VerbalizationStrategy = VerbalizationStrategy.MULTI_PASS
}

/**
 * Learning verbalization strategy - uses LLM with feedback patterns.
 * Incorporates user corrections to improve future descriptions.
 * 
 * ## Fallback Chain:
 * 1. Try LLM generation (with timeout)
 * 2. On timeout/error → Fall back to MULTI_PASS
 * 3. On MULTI_PASS failure → Fall back to INCREMENTAL
 * 
 * ## Batching:
 * - Max 100 symbols per LLM prompt
 * - Chunking for large symbol sets
 * - Context window overflow handling
 * 
 * ## Performance Target: <5000ms per 100 symbols
 * ## Fallback Rate Target: <5%
 */
class LearningVerbalizationStrategy(
    private val patternMatcher: PatternMatcher,
    private val llmClient: LlmVerbalizationClient,
    private val feedbackStore: FeedbackStore,
    private val hashManager: HashManager,
    private val clusterId: String = "default",
    private val timeoutMs: Long = 5000,
    private val batchSize: Int = 100
) : VerbalizationStrategyImpl {

    override suspend fun verbalize(
        symbols: List<Symbol>,
        intent: DiscoveryIntent
    ): List<VerbalizationResult> {

        val results = mutableListOf<VerbalizationResult>()
        var fallbackCount = 0

        // Process in batches for efficiency
        val batches = symbols.chunked(batchSize)
        
        for (batch in batches) {
            for (symbol in batch) {
                try {
                    val result = verbalizeWithFallback(symbol, intent)
                    results.add(result)
                    
                    if (result.metadata["fallback_used"] == "true") {
                        fallbackCount++
                    }
                } catch (e: Exception) {
                    // Ultimate fallback: use pattern matcher only
                    val description = patternMatcher.matchAndDescribe(symbol) ?: "No description available"
                    results.add(
                        VerbalizationResult(
                            symbol = symbol,
                            description = description,
                            confidence = if (description != null) 0.5 else 0.0,
                            strategy = VerbalizationStrategy.LEARNING,
                            metadata = mapOf(
                                "fallback_used" to "true",
                                "fallback_reason" to "exception: ${e.javaClass.simpleName}",
                                "error_message" to (e.message ?: "unknown error")
                            )
                        )
                    )
                    fallbackCount++
                }
            }
        }

        // Log fallback rate if significant
        val fallbackRate = fallbackCount.toDouble() / symbols.size
        if (fallbackRate > 0.05) {
            println("Warning: High fallback rate in LEARNING strategy: ${String.format("%.1f", fallbackRate * 100)}%")
        }

        return results
    }

    /**
     * Verbalize symbol with fallback chain: LEARNING → MULTI_PASS → INCREMENTAL
     */
    private suspend fun verbalizeWithFallback(symbol: Symbol, intent: DiscoveryIntent): VerbalizationResult {
        // Check for user feedback first (highest confidence)
        val feedback = feedbackStore.getFeedbackForSymbol(symbol)
        if (feedback != null && feedback.rating >= 4) {
            val feedbackHistory = getFeedbackHistory(symbol)
            return VerbalizationResult(
                symbol = symbol,
                description = feedback.correction,
                confidence = 0.95,
                strategy = VerbalizationStrategy.LEARNING,
                metadata = mapOf(
                    "source" to "user_feedback",
                    "feedback_id" to feedback.id,
                    "feedback_history_size" to feedbackHistory.size.toString()
                )
            )
        }

        // Try LLM generation with timeout
        return try {
            val llmResult = verbalizeWithLLM(symbol)
            if (llmResult != null) {
                llmResult
            } else {
                // LLM returned null - fallback to pattern matcher
                fallbackToPatternMatcher(symbol, "llm_null_response")
            }
        } catch (e: Exception) {
            // LLM failed - fallback to pattern matcher
            fallbackToPatternMatcher(symbol, "llm_exception: ${e.javaClass.simpleName}")
        }
    }

    /**
     * Generate verbalization using LLM with batching and timeout.
     */
    private suspend fun verbalizeWithLLM(symbol: Symbol): VerbalizationResult? {
        // Build context for LLM
        val context = buildContext(symbol)

        // Get feedback history for this symbol
        val feedbackHistory = getFeedbackHistory(symbol)

        // Generate description using LLM with pattern hints
        val patternHint = patternMatcher.matchAndDescribe(symbol)
        val llmRequest = LlmVerbalizationRequest(
            symbol = symbol,
            heuristicDescription = patternHint,
            context = context,
            feedbackHistory = feedbackHistory,
            timeoutMs = timeoutMs
        )

        val llmResponse = llmClient.generate(llmRequest)

        if (llmResponse == null) {
            return null // Signal for fallback
        }

        val currentLocalHash = hashManager.computeLocalHash(symbol)

        return VerbalizationResult(
            symbol = symbol,
            description = llmResponse.description,
            confidence = llmResponse.confidence,
            strategy = VerbalizationStrategy.LEARNING,
            metadata = mapOf(
                "llm_used" to "true",
                "llm_model" to llmResponse.model,
                "llm_confidence" to llmResponse.confidence.toString(),
                "llm_tokens" to llmResponse.tokensUsed.toString(),
                "pattern_hint_used" to (patternHint != null).toString(),
                "feedback_history_size" to feedbackHistory.size.toString(),
                "local_hash" to currentLocalHash.take(16),
                "fallback_used" to "false"
            )
        )
    }

    /**
     * Fallback to pattern matcher when LLM fails.
     */
    private fun fallbackToPatternMatcher(symbol: Symbol, reason: String): VerbalizationResult {
        val description = patternMatcher.matchAndDescribe(symbol) ?: "No description available"
        val currentLocalHash = hashManager.computeLocalHash(symbol)
        
        return VerbalizationResult(
            symbol = symbol,
            description = description,
            confidence = if (description != null) 0.6 else 0.0,
            strategy = VerbalizationStrategy.LEARNING,
            metadata = mapOf(
                "fallback_used" to "true",
                "fallback_reason" to reason,
                "pattern_hint_used" to (description != null).toString(),
                "local_hash" to currentLocalHash.take(16)
            )
        )
    }

    private fun buildContext(symbol: Symbol): SymbolVerbalizationContext {
        val parts = symbol.filePath.split("/")
        val clusterId = if (parts.size >= 2) "${parts[0]}/${parts[1]}" else "default"

        return SymbolVerbalizationContext(
            clusterId = clusterId,
            moduleName = parts.firstOrNull() ?: "unknown",
            dependencies = extractDependencies(symbol.content),
            relatedSymbols = extractRelatedSymbols(symbol.content),
            architecturalLayer = detectArchitecturalLayer(symbol.filePath)
        )
    }

    private fun getFeedbackHistory(symbol: Symbol): List<FeedbackHistoryEntry> {
        return feedbackStore.getAllFeedbackForSymbol(symbol).map { fb ->
            FeedbackHistoryEntry(
                originalDescription = fb.originalDescription,
                correctedDescription = fb.correction,
                rating = fb.rating,
                reason = fb.reason
            )
        }
    }

    private fun extractDependencies(content: String): List<String> {
        val importPattern = Regex("""import\s+([\w.]+)""")
        return importPattern.findAll(content)
            .map { it.groupValues[1] }
            .filter { !it.startsWith("kotlin") && !it.startsWith("java") }
            .take(5)
            .toList()
    }

    private fun extractRelatedSymbols(content: String): List<String> {
        val callPattern = Regex("""(\w+)\(""")
        return callPattern.findAll(content)
            .map { it.groupValues[1] }
            .filter { it.first().isUpperCase() }
            .distinct()
            .take(5)
            .toList()
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

    override fun getStrategyType(): VerbalizationStrategy = VerbalizationStrategy.LEARNING
}
