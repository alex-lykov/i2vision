/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.verbalization.llm

import com.i2vision.arch.signature.EnrichedSymbol
import com.i2vision.llm.*
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
 * Multi-provider LLM verbalization client.
 * Supports Ollama (local) and DeepSeek (cloud) backends.
 * 
 * ## Features:
 * - Provider selection via configuration
 * - Automatic fallback to mock mode if no provider configured
 * - Unified interface for all LLM providers
 * - Support for both simple and chat-based generation
 * 
 * ## Configuration:
 * Create `.i2vision/llm.config` with:
 * ```properties
 * provider=deepseek  # or ollama
 * deepseek.api_key=sk-xxx
 * deepseek.model=deepseek-chat
 * ollama.base_url=http://localhost:11434
 * ollama.model=llama3.2:3b
 * ```
 */
class DefaultLlmVerbalizationClient(
    private val config: LlmClientConfig = LlmClientConfig(),
    private val modifierVerbalizer: ModifierVerbalizer = KotlinModifierVerbalizer()
) : LlmVerbalizationClient {

    private val llmClient: ModelProvider?
    private val providerType: LLMProvider?
    
    init {
        // Initialize LLM client based on configuration
        val (client, provider) = initializeClient(config)
        this.llmClient = client
        this.providerType = provider
    }

    /**
     * Initialize LLM client from configuration.
     */
    private fun initializeClient(config: LlmClientConfig): Pair<ModelProvider?, LLMProvider?> {
        return try {
            val configFile = File(config.configPath)
            if (!configFile.exists()) {
                println("[LLM] No config file found at ${config.configPath}, using mock mode")
                return Pair(null, null)
            }

            val props = Properties()
            configFile.inputStream().use { props.load(it) }
            
            val provider = props.getProperty("provider", config.provider).lowercase()
            
            when (provider) {
                "deepseek" -> {
                    val apiKey = props.getProperty("deepseek.api_key") 
                        ?: System.getenv("DEEPSEEK_API_KEY")
                        ?: run {
                            println("[LLM] DeepSeek API key not found, using mock mode")
                            return Pair(null, null)
                        }
                    
                    val model = props.getProperty("deepseek.model", "deepseek-chat")
                    val baseUrl = props.getProperty("deepseek.base_url", "https://api.deepseek.com")
                    
                    println("[LLM] Using DeepSeek provider with model: $model")
                    Pair(
                        LLMClientFactory.createDeepSeekClient(apiKey, baseUrl, model),
                        LLMProvider.DEEPSEEK
                    )
                }
                
                "ollama" -> {
                    val baseUrl = props.getProperty("ollama.base_url", "http://localhost:11434")
                    val model = props.getProperty("ollama.model", "llama3.2:3b")
                    
                    println("[LLM] Using Ollama provider with model: $model")
                    Pair(
                        LLMClientFactory.createOllamaClient(baseUrl, model),
                        LLMProvider.OLLAMA
                    )
                }
                
                else -> {
                    println("[LLM] Unknown provider '$provider', using mock mode")
                    Pair(null, null)
                }
            }
        } catch (e: Exception) {
            println("[LLM] Failed to initialize LLM client: ${e.message}, using mock mode")
            Pair(null, null)
        }
    }

    override suspend fun generate(request: LlmVerbalizationRequest): LlmVerbalizationResponse? {
        return withContext(Dispatchers.IO) {
            val client = llmClient
            if (client == null) {
                generateMockResponse(request)
            } else {
                generateLlmResponse(request, client)
            }
        }
    }

    override suspend fun generateBatch(requests: List<LlmVerbalizationRequest>): List<LlmVerbalizationResponse> {
        return requests.mapNotNull { generate(it) }
    }

    override fun isAvailable(): Boolean {
        return llmClient != null || config.allowMockFallback
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

    private suspend fun generateLlmResponse(
        request: LlmVerbalizationRequest,
        client: ModelProvider
    ): LlmVerbalizationResponse {
        val startTime = System.currentTimeMillis()
        
        try {
            // Build prompt from template
            val prompt = buildPrompt(request)
            
            // Generate response using the unified ModelProvider interface
            val response = client.generate(
                prompt = prompt,
                temperature = config.temperature,
                topP = 0.9,
                topK = 40,
                maxTokens = config.maxTokens,
                timeoutSeconds = (config.timeoutMs / 1000).toLong()
            )
            
            val generationTime = System.currentTimeMillis() - startTime
            
            println("[LLM] Generated response in ${generationTime}ms using ${providerType ?: "unknown"}")
            
            return LlmVerbalizationResponse(
                description = response.trim(),
                confidence = 0.9,
                tokensUsed = estimateTokens(response),
                model = config.model,
                generationTimeMs = generationTime
            )
            
        } catch (e: Exception) {
            println("[LLM] Generation failed: ${e.message}, falling back to mock mode")
            
            // Fallback to mock response on error
            return generateMockResponse(request).copy(
                confidence = 0.7,
                model = "${config.model} (fallback)"
            )
        }
    }

    /**
     * Build prompt from template and request data.
     */
    private fun buildPrompt(request: LlmVerbalizationRequest): String {
        val template = request.promptTemplate
        
        return template
            .replace("{symbolName}", request.symbol.name)
            .replace("{symbolKind}", request.symbol.kind.name)
            .replace("{symbolFile}", request.symbol.filePath)
            .replace("{symbolContent}", request.symbol.content.take(2000))
            .replace("{heuristicDescription}", request.heuristicDescription ?: "None")
            .replace("{moduleName}", request.context.moduleName)
            .replace("{clusterId}", request.context.clusterId)
            .replace("{dependencies}", request.context.dependencies.joinToString(", "))
            .replace("{relatedSymbols}", request.context.relatedSymbols.joinToString(", "))
            .replace("{feedbackHistory}", formatFeedbackHistory(request.feedbackHistory))
            .replace("{kotlinFeatures}", extractKotlinFeatures(request.symbol.content))
            .replace("{modifiers}", formatModifiers(request.enrichedSymbol))
            .replace("{structuralRole}", request.enrichedSymbol?.structuralRole?.name ?: "Unknown")
            .replace("{technicalContext}", formatTechnicalContext(request.enrichedSymbol))
    }

    private fun formatFeedbackHistory(feedback: List<FeedbackHistoryEntry>): String {
        if (feedback.isEmpty()) return "No feedback history"
        
        return feedback.joinToString("\n") { entry ->
            "- Original: ${entry.originalDescription}\n  Corrected: ${entry.correctedDescription}\n  Rating: ${entry.rating}/5"
        }
    }

    private fun extractKotlinFeatures(content: String): String {
        val features = mutableListOf<String>()
        
        if (content.contains("suspend ")) features.add("Suspend function (coroutine)")
        if (content.contains("data class")) features.add("Data class")
        if (content.contains("sealed class")) features.add("Sealed class")
        if (content.contains("companion object")) features.add("Companion object")
        if (content.contains(" by ")) features.add("Delegation")
        if (content.contains("inline ")) features.add("Inline function")
        if (content.contains("reified ")) features.add("Reified type parameter")
        if (content.contains("extension") || Regex("""fun \w+\.\w+""").containsMatchIn(content)) {
            features.add("Extension function")
        }
        
        return if (features.isEmpty()) "None detected" else features.joinToString(", ")
    }

    private fun formatModifiers(enriched: EnrichedSymbol?): String {
        if (enriched == null) return "No structured modifiers available"
        
        val modifiers = mutableListOf<String>()
        
        // Add modifiers from enriched symbol
        enriched.modifiers.forEach { modifier ->
            modifiers.add(modifier.kind.name)
        }
        
        return if (modifiers.isEmpty()) "None detected" else modifiers.joinToString(", ")
    }

    private fun formatTechnicalContext(enriched: EnrichedSymbol?): String {
        if (enriched == null) return "No technical context available"
        
        val contexts = mutableListOf<String>()
        
        if (enriched.hasDatabaseAccess()) contexts.add("Database access")
        if (enriched.hasExternalCalls()) contexts.add("External API calls")
        if (enriched.technicalContext.isTransactional) contexts.add("Transactional")
        if (enriched.technicalContext.hasCacheAccess) contexts.add("Cacheable")
        
        return if (contexts.isEmpty()) "None detected" else contexts.joinToString(", ")
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
            // Legacy fallback: simple keyword-based enhancement
            enhanceWithKeywords(fromFeedback, symbol)
        }
    }

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
