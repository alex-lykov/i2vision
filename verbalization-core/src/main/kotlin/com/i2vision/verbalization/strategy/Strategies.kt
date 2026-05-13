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
import com.i2vision.verbalization.llm.SymbolVerbalizationContext
import com.i2vision.vslfc.Symbol
import com.i2vision.vslfc.SymbolKind
import com.i2vision.vslfc.VerbalizationResult
import com.i2vision.vslfc.VerbalizationStrategy

/**
 * Incremental verbalization strategy - only verbalizes changed symbols.
 * Uses hash-based change detection for optimal performance.
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
            val currentHash = hashManager.computeHash(symbol)

            // Skip if symbol hasn't changed
            if (!hashManager.hasChanged(symbol, currentHash)) {
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
                    "hash" to currentHash,
                    "pattern_matched" to (description != null).toString()
                )
            )

            results.add(result)

            // Update hash after successful verbalization
            hashManager.updateHash(symbol, currentHash)
        }

        return results
    }

    override fun getStrategyType(): VerbalizationStrategy = VerbalizationStrategy.INCREMENTAL
}

/**
 * Multi-pass verbalization strategy - refines descriptions with broader context.
 * First pass: Basic pattern matching
 * Second pass: Context-aware refinement using module relationships
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
            val description = patternMatcher.matchAndDescribe(symbol)
            VerbalizationResult(
                symbol = symbol,
                description = description ?: "No description available",
                confidence = if (description != null) 0.7 else 0.0,
                strategy = VerbalizationStrategy.MULTI_PASS,
                metadata = mapOf("pass" to "1")
            )
        }

        // Second pass: Context refinement
        val refinedResults = firstPassResults.map { result ->
            val context = contextProvider.getContext(result.symbol)
            val refinedDescription = refineWithContext(result.description, context)

            result.copy(
                description = refinedDescription,
                confidence = minOf(result.confidence + 0.2, 0.95),
                metadata = result.metadata + mapOf("pass" to "2", "context_used" to "true")
            )
        }

        return refinedResults
    }

    private fun refineWithContext(description: String, context: SymbolContext): String {
        // Simple refinement logic - in real implementation this would be more sophisticated
        return when {
            context.hasRelatedServices() && description.contains("service") ->
                "$description (coordinates with ${context.relatedSymbols.size} related services)"
            context.hasDatabaseAccess && !description.contains("data") ->
                "$description (with database access)"
            else -> description
        }
    }

    override fun getStrategyType(): VerbalizationStrategy = VerbalizationStrategy.MULTI_PASS
}

/**
 * Learning verbalization strategy - uses LLM with feedback patterns.
 * Incorporates user corrections to improve future descriptions.
 */
class LearningVerbalizationStrategy(
    private val patternMatcher: PatternMatcher,
    private val llmClient: LlmVerbalizationClient,
    private val feedbackStore: FeedbackStore,
    private val hashManager: HashManager,
    private val clusterId: String = "default"
) : VerbalizationStrategyImpl {

    override suspend fun verbalize(
        symbols: List<Symbol>,
        intent: DiscoveryIntent
    ): List<VerbalizationResult> {

        val results = mutableListOf<VerbalizationResult>()

        for (symbol in symbols) {
            // Check for user feedback first
            val feedback = feedbackStore.getFeedbackForSymbol(symbol)
            if (feedback != null && feedback.rating >= 4) {
                results.add(
                    VerbalizationResult(
                        symbol = symbol,
                        description = feedback.correction,
                        confidence = 0.95,
                        strategy = VerbalizationStrategy.LEARNING,
                        metadata = mapOf(
                            "source" to "user_feedback",
                            "feedback_id" to feedback.id
                        )
                    )
                )
                continue
            }

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
                feedbackHistory = feedbackHistory
            )

            val llmResponse = llmClient.generate(llmRequest)

            results.add(
                VerbalizationResult(
                    symbol = symbol,
                    description = llmResponse?.description ?: (patternHint ?: "No description available"),
                    confidence = llmResponse?.confidence ?: (if (patternHint != null) 0.6 else 0.0),
                    strategy = VerbalizationStrategy.LEARNING,
                    metadata = mapOf(
                        "llm_used" to (llmResponse != null).toString(),
                        "llm_model" to (llmResponse?.model ?: "none"),
                        "llm_confidence" to (llmResponse?.confidence?.toString() ?: "0.0"),
                        "llm_tokens" to (llmResponse?.tokensUsed?.toString() ?: "0"),
                        "pattern_hint_used" to (patternHint != null).toString(),
                        "feedback_history_size" to feedbackHistory.size.toString()
                    )
                )
            )
        }

        return results
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
        // Extract method/class calls from content
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
            filePath.contains("/util") || filePath.contains("/helper") -> "utility"
            else -> "unknown"
        }
    }

    override fun getStrategyType(): VerbalizationStrategy = VerbalizationStrategy.LEARNING
}

/**
 * Base interface for verbalization strategy implementations.
 */
interface VerbalizationStrategyImpl {
    suspend fun verbalize(symbols: List<Symbol>, intent: DiscoveryIntent): List<VerbalizationResult>
    fun getStrategyType(): VerbalizationStrategy
}

/**
 * Context information for a symbol during multi-pass verbalization.
 */
data class SymbolContext(
    val relatedSymbols: List<Symbol>,
    val moduleDependencies: List<String>,
    val hasDatabaseAccess: Boolean = false,
    val hasExternalCalls: Boolean = false
) {
    fun hasRelatedServices(): Boolean = relatedSymbols.any { it.kind == SymbolKind.CLASS && it.name.contains("Service") }
}

/**
 * Provider for symbol context during multi-pass verbalization.
 */
interface ContextProvider {
    fun getContext(symbol: Symbol): SymbolContext
}