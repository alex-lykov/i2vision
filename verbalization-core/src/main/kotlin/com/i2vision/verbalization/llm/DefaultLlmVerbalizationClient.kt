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
import com.i2vision.verbalization.modifier.KotlinModifierVerbalizer
import com.i2vision.verbalization.modifier.ModifierVerbalizer
import com.i2vision.verbalization.modifier.verbalizeWithContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Properties

/**
 * Default implementation of LlmVerbalizationClient.
 * Provides configurable LLM integration with fallback to heuristic descriptions.
 * 
 * REFACTORED: Uses structured modifier detection instead of regex-based detection.
 */
class DefaultLlmVerbalizationClient(
    private val config: LlmClientConfig = LlmClientConfig(),
    private val modifierVerbalizer: ModifierVerbalizer = KotlinModifierVerbalizer()
) : LlmVerbalizationClient {

    // Placeholder for actual LLM integration - would use llm-client module
    private var mockMode = true

    init {
        // Check if LLM is configured
        mockMode = !isLlmConfigured()
    }

    override suspend fun generate(request: LlmVerbalizationRequest): LlmVerbalizationResponse? {
        return withContext(Dispatchers.IO) {
            if (mockMode) {
                generateMockResponse(request)
            } else {
                generateLlmResponse(request)
            }
        }
    }

    override suspend fun generateBatch(requests: List<LlmVerbalizationRequest>): List<LlmVerbalizationResponse> {
        return requests.mapNotNull { generate(it) }
    }

    override fun isAvailable(): Boolean {
        return !mockMode || config.allowMockFallback
    }

    private fun isLlmConfigured(): Boolean {
        val configFile = File(config.configPath)
        if (!configFile.exists()) return false

        return try {
            val props = Properties()
            configFile.inputStream().use { props.load(it) }
            props.containsKey("apiKey") || props.containsKey("provider")
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun generateMockResponse(request: LlmVerbalizationRequest): LlmVerbalizationResponse {
        // Simulate LLM response with enhanced description
        val enhancedDescription = enhanceDescription(request)
        val startTime = System.currentTimeMillis()

        // Simulate some processing time
        kotlinx.coroutines.delay(10)

        return LlmVerbalizationResponse(
            description = enhancedDescription,
            confidence = 0.85,
            tokensUsed = estimateTokens(enhancedDescription),
            model = "mock",
            generationTimeMs = System.currentTimeMillis() - startTime
        )
    }

    private suspend fun generateLlmResponse(request: LlmVerbalizationRequest): LlmVerbalizationResponse? {
        // In real implementation, this would:
        // 1. Build the prompt from template
        // 2. Call the LLM API via llm-client module
        // 3. Parse and validate the response
        // 4. Return structured response

        // Placeholder for actual LLM integration
        return generateMockResponse(request)
    }

    private fun enhanceDescription(request: LlmVerbalizationRequest): String {
        val symbol = request.symbol
        val heuristic = request.heuristicDescription ?: describeFromContent(symbol)

        // Apply feedback improvements if available
        val fromFeedback = applyFeedbackImprovements(heuristic, request.feedbackHistory)

        // Use structured enrichment if available, otherwise use simple keyword enhancement
        return if (request.enrichedSymbol != null) {
            // New structured approach with ModifierVerbalizer
            enhanceWithStructuredData(request.enrichedSymbol, fromFeedback)
        } else {
            // Legacy fallback: simple keyword-based enhancement (no regex)
            enhanceWithKeywords(fromFeedback, symbol)
        }
    }

    /**
     * Enhance description using structured data from EnrichedSymbol.
     * Uses ModifierVerbalizer to convert structured facts to natural language.
     */
    private fun enhanceWithStructuredData(
        enriched: EnrichedSymbol,
        baseDescription: String
    ): String {
        // Use the ModifierVerbalizer to convert structured facts to natural language
        return modifierVerbalizer.verbalizeWithContext(
            com.i2vision.verbalization.modifier.VerbalizationContext(
                baseDescription = baseDescription,
                enrichedSymbol = enriched,
                includeModifiers = true,
                includeRole = true,
                includeTechnicalContext = true
            )
        )
    }

    /**
     * LEGACY: Simple keyword-based enhancement without regex.
     * 
     * DEPRECATED: This is a temporary fallback for backward compatibility.
     * It will be removed after all callers migrate to structured detection.
     * 
     * @see enhanceWithStructuredData
     */
    @Deprecated("Use enhanceWithStructuredData() with EnrichedSymbol instead")
    private fun enhanceWithKeywords(description: String, symbol: Symbol): String {
        val content = symbol.content.lowercase()
        var result = description

        // Check for suspend functions - prepend if not already mentioned
        if (content.contains("suspend ") && !result.contains("suspend", ignoreCase = true)) {
            result = "suspend $result"
        }

        // Check for data classes - replace "class" with "data class"
        if ((content.contains("data class") || content.contains("data class ")) &&
            !result.contains("data class", ignoreCase = true)
        ) {
            result = result.replace("class", "data class", ignoreCase = true)
        }

        // Check for sealed classes - replace "hierarchy" with "sealed hierarchy"
        if (content.contains("sealed class") && !result.contains("sealed", ignoreCase = true)) {
            result = result.replace("hierarchy", "sealed hierarchy", ignoreCase = true)
        }

        // Check for companion objects - append marker
        if (content.contains("companion object") && !result.contains("companion", ignoreCase = true)) {
            result = "$result (companion)"
        }

        // Check for delegation - append marker
        if (content.contains(" by ") && !result.contains("delegate", ignoreCase = true)) {
            result = "$result (delegate)"
        }

        return result
    }

    private fun describeFromContent(symbol: Symbol): String {
        val content = symbol.content.lowercase()
        val target = extractTarget(content)

        return when {
            symbol.kind == SymbolKind.FUNCTION -> {
                val action = extractAction(content)
                when {
                    content.contains("suspend") -> "asynchronous $action $target"
                    content.contains("validate") || content.contains("check") -> "Validates $target"
                    content.contains("create") || content.contains("new") -> "Creates a new $target"
                    content.contains("get") || content.contains("fetch") -> "Retrieves $target"
                    content.contains("update") || content.contains("modify") -> "Updates $target"
                    content.contains("delete") || content.contains("remove") -> "Deletes $target"
                    else -> "$action $target"
                }
            }
            symbol.kind == SymbolKind.CLASS -> {
                when {
                    content.contains("data class") -> "Represents $target data container"
                    content.contains("sealed class") -> "Defines restricted type hierarchy for $target"
                    content.contains("interface") -> "Defines contract for $target"
                    else -> "Manages $target"
                }
            }
            else -> symbol.name.replaceCamelCase()
        }
    }

    private fun extractAction(content: String): String {
        return when {
            content.contains("authenticate") -> "authenticates"
            content.contains("authorize") || content.contains("permission") -> "authorizes"
            content.contains("validate") -> "validates"
            content.contains("process") -> "processes"
            content.contains("transform") -> "transforms"
            content.contains("convert") -> "converts"
            content.contains("calculate") -> "calculates"
            content.contains("handle") -> "handles"
            content.contains("execute") -> "executes"
            content.contains("build") -> "builds"
            content.contains("parse") -> "parses"
            content.contains("fetch") || content.contains("get") || content.contains("retrieve") -> "fetches"
            content.contains("create") || content.contains("new") -> "creates"
            content.contains("update") || content.contains("modify") -> "updates"
            content.contains("delete") || content.contains("remove") -> "deletes"
            content.contains("send") || content.contains("publish") -> "sends"
            content.contains("receive") || content.contains("consume") -> "receives"
            content.contains("save") || content.contains("store") || content.contains("persist") -> "saves"
            content.contains("load") || content.contains("read") -> "loads"
            content.contains("transform") || content.contains("map") -> "transforms"
            content.contains("filter") -> "filters"
            content.contains("count") -> "counts"
            content.contains("find") || content.contains("search") -> "finds"
            content.contains("list") || content.contains("all") -> "lists"
            content.contains("convert") || content.contains("cast") -> "converts"
            content.contains("initialize") || content.contains("setup") -> "initializes"
            content.contains("configure") || content.contains("register") -> "configures"
            content.contains("start") || content.contains("begin") -> "starts"
            content.contains("stop") || content.contains("end") -> "stops"
            content.contains("reset") || content.contains("clear") -> "resets"
            content.contains("notify") || content.contains("emit") -> "notifies"
            else -> "processes"
        }
    }

    private fun extractTarget(content: String): String {
        val match = Regex("""(user|data|request|response|payment|order|auth|token|config|file|message|entity|model)""")
            .find(content)
        return match?.groupValues?.get(1) ?: "data"
    }

    private fun applyFeedbackImprovements(
        description: String,
        feedbackHistory: List<FeedbackHistoryEntry>
    ): String {
        if (feedbackHistory.isEmpty()) return description

        // Use the highest-rated correction as improvement
        val bestFeedback = feedbackHistory.maxByOrNull { it.rating }
        return if (bestFeedback != null && bestFeedback.rating >= 4) {
            bestFeedback.correctedDescription
        } else {
            description
        }
    }

    private fun estimateTokens(text: String): Int {
        // Rough estimate: ~4 characters per token
        return (text.length / 4).toInt()
    }

    private fun String.replaceCamelCase(): String {
        return this.replace(Regex("([a-z])([A-Z])"), "$1 $2")
            .replaceFirstChar { it.uppercase() }
    }
}

/**
 * Configuration for LLM client.
 */
data class LlmClientConfig(
    val configPath: String = ".i2vision/llm.config",
    val provider: String = "openai",
    val model: String = "gpt-4",
    val temperature: Double = 0.3,
    val maxTokens: Int = 500,
    val allowMockFallback: Boolean = true,
    val timeoutMs: Int = 30000
) {
    companion object {
        fun fromProperties(props: Properties): LlmClientConfig {
            return LlmClientConfig(
                configPath = props.getProperty("configPath", ".i2vision/llm.config"),
                provider = props.getProperty("provider", "openai"),
                model = props.getProperty("model", "gpt-4"),
                temperature = props.getProperty("temperature", "0.3").toDoubleOrNull() ?: 0.3,
                maxTokens = props.getProperty("maxTokens", "500").toIntOrNull() ?: 500,
                allowMockFallback = props.getProperty("allowMockFallback", "true").toBoolean(),
                timeoutMs = props.getProperty("timeoutMs", "30000").toIntOrNull() ?: 30000
            )
        }
    }
}
