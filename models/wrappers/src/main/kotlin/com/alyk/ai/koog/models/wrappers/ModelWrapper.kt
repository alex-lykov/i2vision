package com.alyk.ai.koog.models.wrappers

import kotlinx.coroutines.flow.Flow

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
    private val baseUrl: String = "http://localhost:11434"
) : ModelWrapper {
    override suspend fun generate(prompt: String): String {
        // TODO: Implement Ollama HTTP client
        return "Local model response (stub)"
    }

    override suspend fun generateStreaming(prompt: String): Flow<String> {
        // TODO: Implement streaming
        return kotlinx.coroutines.flow.flowOf("Streaming (stub)")
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
