package com.alyk.ai.koog.models.cloud

import ai.koog.agents.core.agent.AIAgent
import ai.koog.prompt.executor.llms.all.simpleOllamaAIExecutor
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import com.alyk.ai.koog.models.wrappers.ModelWrapper
import com.alyk.ai.koog.models.wrappers.PerformanceMonitor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.system.measureTimeMillis

/**
 * OllamaCloudModelWrapper: HTTP client for remote Ollama instances
 * Supports cloud-hosted Ollama APIs with authentication and configuration
 */
class OllamaCloudModelWrapper(
    override val modelName: String,
    override val maxContextLength: Int,
    private val cloudConfig: OllamaCloudConfig,
    private val performanceMonitor: PerformanceMonitor? = null
) : ModelWrapper {
    
    private val systemPrompt = """
        You are a Kotlin coding assistant.
        Keep answers concise and practical.
        You have access to cloud resources for enhanced performance.
    """.trimIndent()

    override suspend fun generate(prompt: String): String {
        val agent = AIAgent(
            promptExecutor = simpleOllamaAIExecutor(
                baseUrl = cloudConfig.apiUrl
            ),
            llmModel = LLModel(
                id = modelName,
                provider = LLMProvider.Ollama,
                contextLength = maxContextLength.toLong(),
                maxOutputTokens = cloudConfig.maxOutputTokens,
                capabilities = emptyList()
            ),
            systemPrompt = systemPrompt
        )

        var response: String
        val responseTime = measureTimeMillis {
            response = agent.run(prompt)
        }

        // Record performance metrics
        val tokensUsed = estimateTokens(prompt)
        val tokensGenerated = estimateTokens(response)
        performanceMonitor?.recordMetric(responseTime.toLong(), tokensUsed, tokensGenerated)

        return response
    }

    override suspend fun generateStreaming(prompt: String): Flow<String> {
        return flow {
            emit(generate(prompt))
        }
    }

    override fun estimateTokens(text: String): Int {
        // TODO: Implement proper token counting for cloud models
        return text.length / 4 // Rough estimate
    }
}

/**
 * Configuration for Ollama cloud instances
 */
data class OllamaCloudConfig(
    val apiUrl: String,
    val apiKey: String? = null,
    val maxOutputTokens: Long = 2048L,
    val timeoutMs: Long = 30000L,
    val retryAttempts: Int = 3,
    val region: String? = null,
    val provider: CloudProvider = CloudProvider.GENERIC
)

enum class CloudProvider {
    GENERIC,
    OLLAMA_CLOUD,
    HUGGING_FACE,
    REPLICATE,
    ANYSCALE
}
