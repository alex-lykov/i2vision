package com.alyk.ai.koog.models.wrappers

import ai.koog.agents.core.agent.AIAgent
import ai.koog.prompt.executor.llms.all.simpleOllamaAIExecutor
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import com.alyk.ai.koog.switching.monitor.PerformanceMonitor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.system.measureTimeMillis

/**
 * Abstract model-specific implementations into unified interface
 * with consistent request/response handling.
 */
interface ModelWrapper {
    suspend fun generate(prompt: String): String
    suspend fun generateStreaming(prompt: String): Flow<String>
    fun estimateTokens(text: String): Int
    val modelName: String
    val maxContextLength: Int
}

/**
 * LocalModelWrapper: HTTP client for ollama/llama.cpp REST APIs
 */
class LocalModelWrapper(
    override val modelName: String,
    override val maxContextLength: Int,
    private val baseUrl: String = "http://localhost:11434",
    private val performanceMonitor: PerformanceMonitor? = null
) : ModelWrapper {
    private val systemPrompt = """
        You are a Kotlin coding assistant.
        Keep answers concise and practical.
    """.trimIndent()

    override suspend fun generate(prompt: String): String {
        val agent = AIAgent(
            promptExecutor = simpleOllamaAIExecutor(),
            llmModel = LLModel(
                id = modelName,
                provider = LLMProvider.Ollama,
                contextLength = maxContextLength.toLong(),
                maxOutputTokens = 512L,
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
        performanceMonitor?.recordMetric(responseTime, tokensUsed, tokensGenerated)

        return response
    }

    override suspend fun generateStreaming(prompt: String): Flow<String> {
        return flow {
            emit(generate(prompt))
        }
    }

    override fun estimateTokens(text: String): Int {
        // TODO: Implement token counting
        return text.length / 4 // Rough estimate
    }
}

/**
 * CloudModelWrapper: HTTP client for OpenAI/Anthropic with API key management
 */
class CloudModelWrapper(
    override val modelName: String,
    override val maxContextLength: Int,
    private val apiKey: String
) : ModelWrapper {
    override suspend fun generate(prompt: String): String {
        // TODO: Implement cloud API client
        return "Cloud model response (stub)"
    }

    override suspend fun generateStreaming(prompt: String): Flow<String> {
        // TODO: Implement streaming
        return kotlinx.coroutines.flow.flowOf("Streaming (stub)")
    }

    override fun estimateTokens(text: String): Int {
        // TODO: Implement token counting
        return text.length / 4
    }
}
