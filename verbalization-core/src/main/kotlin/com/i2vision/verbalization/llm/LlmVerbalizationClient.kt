/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.verbalization.llm

import com.i2vision.arch.signature.EnrichedSymbol
import com.i2vision.vslfc.Symbol
import com.i2vision.vslfc.SymbolKind

/**
 * Client for generating verbalization descriptions using LLM.
 * Provides context-aware description generation with feedback integration.
 * 
 * REFACTORED: Now supports EnrichedSymbol for structured detection.
 */
interface LlmVerbalizationClient {
    /**
     * Generate a description for a symbol.
     *
     * @param request The generation request containing symbol and context
     * @return Response with generated description or null if generation failed
     */
    suspend fun generate(request: LlmVerbalizationRequest): LlmVerbalizationResponse?

    /**
     * Generate descriptions for multiple symbols in batch.
     */
    suspend fun generateBatch(requests: List<LlmVerbalizationRequest>): List<LlmVerbalizationResponse>

    /**
     * Check if the LLM client is available and configured.
     */
    fun isAvailable(): Boolean
}

/**
 * Request for LLM verbalization generation.
 * 
 * @param symbol The base symbol to verbalize
 * @param enrichedSymbol Optional enriched symbol with structured facts (NEW)
 * @param heuristicDescription Existing heuristic description
 * @param context Context about the symbol
 * @param feedbackHistory User feedback history
 * @param promptTemplate Template for LLM prompt
 */
data class LlmVerbalizationRequest(
    val symbol: Symbol,
    val enrichedSymbol: EnrichedSymbol? = null,  // NEW: Structured enrichment data
    val heuristicDescription: String?,
    val context: SymbolVerbalizationContext,
    val feedbackHistory: List<FeedbackHistoryEntry> = emptyList(),
    val promptTemplate: String = DEFAULT_TEMPLATE
)

/**
 * Context about the symbol for verbalization.
 */
data class SymbolVerbalizationContext(
    val clusterId: String,
    val moduleName: String,
    val dependencies: List<String> = emptyList(),
    val relatedSymbols: List<String> = emptyList(),
    val architecturalLayer: String? = null
)

/**
 * Entry from feedback history to include in LLM prompt.
 */
data class FeedbackHistoryEntry(
    val originalDescription: String,
    val correctedDescription: String,
    val rating: Int,
    val reason: String?
)

/**
 * Response from LLM verbalization generation.
 */
data class LlmVerbalizationResponse(
    val description: String,
    val confidence: Double,
    val tokensUsed: Int,
    val model: String,
    val generationTimeMs: Long
)

/**
 * Default prompt template for LLM verbalization.
 */
const val DEFAULT_TEMPLATE = """
You are an expert software architect creating documentation for code symbols.
Given the following information, produce a concise, accurate description.

## Symbol Information
- Name: {symbolName}
- Kind: {symbolKind}
- File: {symbolFile}
- Code:
```
{symbolContent}
```

## Current Heuristic Description
{heuristicDescription}

## Architectural Context
- Module: {moduleName}
- Cluster: {clusterId}
- Dependencies: {dependencies}
- Related symbols: {relatedSymbols}

## User Feedback History
{feedbackHistory}

## Task
Write a description that:
1. Explains what the symbol DOES, not just what it IS
2. Includes business-relevant details (validation rules, side effects)
3. Mentions architectural role if relevant
4. Is 1-2 sentences maximum

Output only the description, no markdown.
"""

/**
 * Kotlin-specific prompt template with enhanced context.
 */
const val KOTLIN_TEMPLATE = """
You are a Kotlin expert and software architect creating documentation.
Given the Kotlin code symbol below, produce a concise, accurate description.

## Symbol Details
- Name: {symbolName}
- Kind: {symbolKind} (Kotlin)
- File: {symbolFile}
- Code:
```
{symbolContent}
```

## Kotlin-Specific Features Detected
{kotlinFeatures}

## Heuristic Description
{heuristicDescription}

## Context
- Module: {moduleName}
- Cluster: {clusterId}

## User Feedback
{feedbackHistory}

## Requirements
1. Use Kotlin terminology (e.g., "suspend function" instead of "async method")
2. Mention data classes, sealed classes, coroutines, etc. if present
3. Include validation logic and side effects
4. Keep to 1-2 sentences

Output only the description:
"""

/**
 * Extended prompt template with structured enrichment data.
 */
const val ENRICHED_TEMPLATE = """
You are an expert software architect creating documentation for code symbols.
Given the following structured information, produce a concise, accurate description.

## Symbol Information
- Name: {symbolName}
- Kind: {symbolKind}
- File: {symbolFile}

## Structured Modifiers Detected
{modifiers}

## Architectural Role
{structuralRole}

## Technical Context
{technicalContext}

## Heuristic Description
{heuristicDescription}

## Task
Write a description that:
1. Incorporates the structured modifiers naturally
2. Mentions architectural role if relevant
3. Includes technical context (database, external calls, etc.)
4. Is 1-2 sentences maximum

Output only the description, no markdown.
"""
