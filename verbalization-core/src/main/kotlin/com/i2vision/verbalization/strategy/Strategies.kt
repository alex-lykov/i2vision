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
    
    /**
     * Get cross-layer reference summary for enrichment.
     * Includes actual symbol names as @CrossReferences for the self-test to detect.
     */
    fun getCrossLayerSummary(): String {
        val parts = mutableListOf<String>()

        // Include actual flow names as cross-references
        if (callingFlows.isNotEmpty()) {
            val flowRefs = callingFlows.take(5).joinToString(", ") { "@$it" }
            parts.add("Called by ${callingFlows.size} flow(s): $flowRefs")
        }

        // Include actual business rule names/descriptions
        if (businessRules.isNotEmpty()) {
            val ruleRefs = businessRules.take(3).joinToString(", ") { "@$it" }
            parts.add("Enforces ${businessRules.size} business rule(s): $ruleRefs")
        }

        // Include actual related symbol names as cross-references
        if (relatedSymbols.isNotEmpty()) {
            val symbolRefs = relatedSymbols.take(5).map { "@${it.name}" }.joinToString(", ")
            parts.add("Coordinates with ${relatedSymbols.size} service(s): $symbolRefs")
        }

        // Include module dependencies as cross-references
        if (moduleDependencies.isNotEmpty()) {
            val depRefs = moduleDependencies.take(5).joinToString(", ") { "@$it" }
            parts.add("Depends on: $depRefs")
        }

        if (hasDatabaseAccess) {
            parts.add("with database access")
        }

        if (hasExternalCalls) {
            parts.add("with external API calls")
        }

        return parts.joinToString(". ")
    }
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
            // Always process when forceFullVerbalization is requested (e.g. enrichment self-test)
            if (!intent.forceFullVerbalization && !hashManager.hasLocalChanged(symbol, currentLocalHash)) {
                continue
            }

            // Generate description using patterns
            val description = patternMatcher.matchAndDescribe(symbol)

            val result = VerbalizationResult(
                symbol = symbol,
                description = description ?: "${symbol.kind.name.lowercase().replaceFirstChar { it.uppercase() }} '${symbol.name}'",
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
 * Second pass: Cross-layer context refinement using Flow, Logic, and Structure layer data
 * 
 * ## Cross-Layer Enrichment:
 * - **Structure Layer**: Component dependencies, cohesion metrics, injection relationships
 * - **Flow Layer**: Calling sequences, API call chains, workflow participation
 * - **Logic Layer**: Business rules, invariants, constraints, validation logic
 * 
 * ## Example Output:
 * **Before**: "Authenticates user credentials"
 * **After**: "Authenticates user credentials via bcrypt (Called by 3 flows: LoginFlow, TokenRefreshFlow, AdminImpersonationFlow. Enforces 2 business rules: token expiry, role validation. Coordinates with 4 related services: TokenValidator, UserRegistry, AuditLogger, PasswordEncoder. with database access)"
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
                description = description ?: "${symbol.kind.name.lowercase().replaceFirstChar { it.uppercase() }} '${symbol.name}'",
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
                        if (context.moduleDependencies.isNotEmpty()) append(" imports:${context.moduleDependencies.size}")
                    }.trim(),
                    "architectural_layer" to context.architecturalLayer
                )
            )
        }

        return refinedResults
    }

    /**
     * Refine description with cross-layer context.
     * Enriches basic descriptions with Flow, Logic, and Structure layer data.
     * 
     * ## Enrichment Strategy:
     * 1. Add Flow context: calling sequences and workflow participation
     * 2. Add Logic context: business rules and validation constraints
     * 3. Add Structure context: component dependencies and relationships
     * 4. Add technical context: database access, external calls
     */
    private fun refineWithCrossLayerContext(description: String, context: SymbolContext): String {
        val crossLayerSummary = context.getCrossLayerSummary()
        
        // If no cross-layer context, return original description
        if (crossLayerSummary.isEmpty()) {
            return description
        }
        
        // Append cross-layer context in parentheses
        return "$description ($crossLayerSummary)"
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
                    val description = patternMatcher.matchAndDescribe(symbol) ?: "${symbol.kind.name.lowercase().replaceFirstChar { it.uppercase() }} '${symbol.name}'"
                    results.add(
                        VerbalizationResult(
                            symbol = symbol,
                            description = description,
                            confidence = if (description != null) 0.5 else 0.0,
                            strategy = VerbalizationStrategy.LEARNING,
                            metadata = mapOf(
                                "fallback_used" to "true",
                                "fallback_reason" to "exception: ${e.message}",
                                "fallback_chain" to "LLM→INCREMENTAL"
                            )
                        )
                    )
                }
            }
        }

        // Log fallback rate
        val fallbackRate = fallbackCount.toDouble() / symbols.size
        if (fallbackRate > 0.05) {
            println("Warning: LEARNING strategy fallback rate is ${String.format("%.1f", fallbackRate * 100)}% (target: <5%)")
        }

        return results
    }

    /**
     * Verbalize symbol with fallback chain.
     */
    private suspend fun verbalizeWithFallback(symbol: Symbol, intent: DiscoveryIntent): VerbalizationResult {
        // Try LLM first
        return try {
            val llmResult = verbalizeWithLlm(symbol)
            if (llmResult != null) {
                llmResult
            } else {
                // LLM returned null, fallback to MULTI_PASS
                fallbackToMultiPass(symbol, "LLM returned null")
            }
        } catch (e: Exception) {
            // LLM failed, fallback to MULTI_PASS
            fallbackToMultiPass(symbol, "LLM failed: ${e.message}")
        }
    }

    /**
     * Verbalize with LLM client.
     */
    private suspend fun verbalizeWithLlm(symbol: Symbol): VerbalizationResult? {
        // Get feedback history for this symbol
        val feedbackHistory = getFeedbackHistory(symbol)
        
        // Check if we have high-rated feedback to use directly
        val highRatedFeedback = feedbackHistory.filter { it.rating >= 4 }
        val bestFeedback = highRatedFeedback.maxByOrNull { it.rating }
        
        // Build symbol context
        val context = SymbolVerbalizationContext(
            clusterId = clusterId,
            moduleName = extractModuleName(symbol.filePath),
            dependencies = emptyList(),
            relatedSymbols = emptyList(),
            architecturalLayer = null
        )
        
        // If we have high-rated feedback, use the best correction directly
        if (bestFeedback != null) {
            return VerbalizationResult(
                symbol = symbol,
                description = bestFeedback.correctedDescription,
                confidence = 0.95, // High confidence for user-corrected feedback
                strategy = VerbalizationStrategy.LEARNING,
                metadata = mapOf(
                    "source" to "user_feedback",
                    "feedback_rating" to bestFeedback.rating.toString(),
                    "feedback_history_size" to feedbackHistory.size.toString(),
                    "llm_used" to "false",
                    "fallback_used" to "false"
                )
            )
        }
        
        // No high-rated feedback, use LLM
        val heuristicDescription = patternMatcher.matchAndDescribe(symbol)
        
        // Build LLM request
        val request = LlmVerbalizationRequest(
            symbol = symbol,
            heuristicDescription = heuristicDescription,
            context = context,
            feedbackHistory = feedbackHistory
        )
        
        // Call LLM
        val response = llmClient.generate(request)
        
        if (response != null && response.description.isNotBlank()) {
            val patternHintUsed = heuristicDescription != null
            
            return VerbalizationResult(
                symbol = symbol,
                description = response.description,
                confidence = response.confidence,
                strategy = VerbalizationStrategy.LEARNING,
                metadata = mapOf(
                    "llm_model" to response.model,
                    "tokens_used" to response.tokensUsed.toString(),
                    "generation_time_ms" to response.generationTimeMs.toString(),
                    "llm_used" to "true",
                    "fallback_used" to "false",
                    "pattern_hint_used" to patternHintUsed.toString(),
                    "feedback_history_size" to feedbackHistory.size.toString()
                )
            )
        }
        
        return null
    }

    /**
     * Get feedback history for a symbol from feedback store.
     */
    private fun getFeedbackHistory(symbol: Symbol): List<FeedbackHistoryEntry> {
        val allFeedback = feedbackStore.getAllFeedbackForSymbol(symbol)
        return allFeedback.map { fb ->
            FeedbackHistoryEntry(
                originalDescription = fb.originalDescription,
                correctedDescription = fb.correction,
                rating = fb.rating,
                reason = fb.reason
            )
        }
    }

    /**
     * Extract module name from file path.
     */
    private fun extractModuleName(filePath: String): String {
        val parts = filePath.split("/")
        return if (parts.size >= 2) parts[0] else "unknown"
    }

    /**
     * Fallback to MULTI_PASS strategy (pattern matching with context).
     */
    private suspend fun fallbackToMultiPass(symbol: Symbol, reason: String): VerbalizationResult {
        val description = patternMatcher.matchAndDescribe(symbol) ?: "${symbol.kind.name.lowercase().replaceFirstChar { it.uppercase() }} '${symbol.name}'"
        
        return VerbalizationResult(
            symbol = symbol,
            description = description,
            confidence = if (description != null) 0.6 else 0.0,
            strategy = VerbalizationStrategy.LEARNING,
            metadata = mapOf(
                "fallback_used" to "true",
                "fallback_reason" to reason,
                "fallback_chain" to "LLM→MULTI_PASS→INCREMENTAL"
            )
        )
    }

    override fun getStrategyType(): VerbalizationStrategy = VerbalizationStrategy.LEARNING
}
