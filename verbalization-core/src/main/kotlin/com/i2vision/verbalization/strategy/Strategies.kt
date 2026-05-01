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
    private val feedbackCollector: FeedbackCollector,
    private val hashManager: HashManager
) : VerbalizationStrategyImpl {

    override suspend fun verbalize(
        symbols: List<Symbol>,
        intent: DiscoveryIntent
    ): List<VerbalizationResult> {

        val results = mutableListOf<VerbalizationResult>()

        for (symbol in symbols) {
            // Check for user feedback first
            val feedback = feedbackCollector.getFeedback(symbol)
            if (feedback != null && feedback.accepted) {
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

            // Generate description using LLM with pattern hints
            val patternHint = patternMatcher.matchAndDescribe(symbol)
            val llmDescription = llmClient.generateDescription(symbol, patternHint)

            results.add(
                VerbalizationResult(
                    symbol = symbol,
                    description = llmDescription ?: (patternHint ?: "No description available"),
                    confidence = if (llmDescription != null) 0.9 else 0.6,
                    strategy = VerbalizationStrategy.LEARNING,
                    metadata = mapOf(
                        "llm_used" to (llmDescription != null).toString(),
                        "pattern_hint_used" to (patternHint != null).toString()
                    )
                )
            )
        }

        return results
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

/**
 * LLM client specialized for verbalization tasks.
 */
interface LlmVerbalizationClient {
    suspend fun generateDescription(symbol: Symbol, patternHint: String?): String?
}

/**
 * Collector for user feedback on verbalization quality.
 */
interface FeedbackCollector {
    fun getFeedback(symbol: Symbol): Feedback?
    fun recordFeedback(feedback: Feedback)
}

/**
 * User feedback on a verbalization result.
 */
data class Feedback(
    val id: String,
    val symbol: Symbol,
    val originalDescription: String,
    val correction: String,
    val accepted: Boolean,
    val timestamp: Long
)
