/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.llm

import ai.koog.agents.core.agent.AIAgent
import ai.koog.prompt.executor.llms.all.simpleOllamaAIExecutor
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.system.measureTimeMillis

@Serializable
private data class OllamaGenerateRequest(val model: String, val prompt: String, val stream: Boolean = true)

/**
 * Simple interface for performance monitoring to avoid circular dependency
 */
interface PerformanceMonitor {
    fun recordMetric(responseTime: Long, tokensUsed: Int, tokensGenerated: Int)
}

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
    override suspend fun generate(prompt: String): String {
        return withContext(Dispatchers.IO) {
            val client = HttpClient.newHttpClient()
            runCatching { generateNonStreaming(client, prompt) }
                .recoverCatching { primary ->
                    val msg = primary.message.orEmpty()
                    if (!shouldRetryWithStreaming(msg)) throw primary
                    generateStreamingAggregate(client, prompt)
                }
                .getOrElse { failure ->
                    throw IllegalStateException(
                        "Ollama /api/generate failed after fallback: ${failure.message}",
                        failure
                    )
                }
        }
    }

    private fun shouldRetryWithStreaming(message: String): Boolean {
        return message.contains("HTTP 5") || message.contains("Invalid Ollama /api/generate response payload")
    }

    private fun generateNonStreaming(client: HttpClient, prompt: String): String {
        val body = Json.encodeToString(OllamaGenerateRequest(model = modelName, prompt = prompt, stream = false))
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/api/generate"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .timeout(java.time.Duration.ofSeconds(300))
            .build()

        var rawResponse = ""
        val responseTime = measureTimeMillis {
            val httpResponse = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (httpResponse.statusCode() != 200) {
                throw IllegalStateException("Ollama /api/generate failed: HTTP ${httpResponse.statusCode()} - ${httpResponse.body()}")
            }
            rawResponse = httpResponse.body()
        }

        val json = Json { ignoreUnknownKeys = true }
        val obj = runCatching { json.parseToJsonElement(rawResponse) as? JsonObject }.getOrNull()
            ?: throw IllegalStateException("Invalid Ollama /api/generate response payload")
        val response = obj["response"]?.jsonPrimitive?.content.orEmpty()

        val (tokensUsed, tokensGenerated) = ModelParsingUtils.parseOllamaGenerateTokenCounts(
            obj,
            estimateTokens(prompt),
            estimateTokens(response)
        )
        performanceMonitor?.recordMetric(responseTime, tokensUsed, tokensGenerated)
        return response
    }

    private fun generateStreamingAggregate(client: HttpClient, prompt: String): String {
        val body = Json.encodeToString(OllamaGenerateRequest(model = modelName, prompt = prompt, stream = true))
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/api/generate"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .timeout(java.time.Duration.ofSeconds(300))
            .build()

        val httpResponse = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        if (httpResponse.statusCode() != 200) {
            val errBody = httpResponse.body().readBytes().decodeToString()
            throw IllegalStateException("Ollama /api/generate streaming fallback failed: HTTP ${httpResponse.statusCode()} - $errBody")
        }

        val json = Json { ignoreUnknownKeys = true }
        val reader = httpResponse.body().bufferedReader()
        val fullResponse = StringBuilder()
        var line: String?
        var lastChunk: JsonObject? = null
        val startTime = System.currentTimeMillis()

        while (reader.readLine().also { line = it } != null) {
            val trimmed = line!!.trim()
            if (trimmed.isEmpty()) continue
            val obj = runCatching { json.parseToJsonElement(trimmed) as? JsonObject }.getOrNull() ?: continue
            lastChunk = obj
            val chunk = obj["response"]?.jsonPrimitive?.content ?: ""
            if (chunk.isNotEmpty()) fullResponse.append(chunk)
            if (obj["done"]?.jsonPrimitive?.content == "true") break
        }

        val response = fullResponse.toString()
        val responseTime = System.currentTimeMillis() - startTime
        val (tokensUsed, tokensGenerated) = ModelParsingUtils.parseOllamaGenerateTokenCounts(
            lastChunk,
            estimateTokens(prompt),
            estimateTokens(response)
        )
        performanceMonitor?.recordMetric(responseTime, tokensUsed, tokensGenerated)

        return response
    }

    override suspend fun generateStreaming(prompt: String): Flow<String> = flow {
        withContext(Dispatchers.IO) {
            val client = HttpClient.newHttpClient()
            val body = Json.encodeToString(OllamaGenerateRequest(model = modelName, prompt = prompt, stream = true))
            val request = HttpRequest.newBuilder()
                .uri(URI.create("$baseUrl/api/generate"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(java.time.Duration.ofSeconds(300))
                .build()

            val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
            if (response.statusCode() != 200) {
                val errBody = response.body().readBytes().decodeToString()
                throw IllegalStateException("Ollama /api/generate failed: HTTP ${response.statusCode()} - $errBody")
            }

            val json = Json { ignoreUnknownKeys = true }
            val reader = response.body().bufferedReader()
            val fullResponse = StringBuilder()
            var line: String?
            var lastChunk: JsonObject? = null
            val startTime = System.currentTimeMillis()

            while (reader.readLine().also { line = it } != null) {
                val trimmed = line!!.trim()
                if (trimmed.isEmpty()) continue
                val obj = runCatching { json.parseToJsonElement(trimmed) as? JsonObject }.getOrNull() ?: continue
                lastChunk = obj
                val chunk = obj["response"]?.jsonPrimitive?.content ?: ""
                if (chunk.isNotEmpty()) {
                    fullResponse.append(chunk)
                    emit(chunk)
                }
                if (obj["done"]?.jsonPrimitive?.content == "true") break
            }

            val responseTime = System.currentTimeMillis() - startTime
            val (tokensUsed, tokensGenerated) = ModelParsingUtils.parseOllamaGenerateTokenCounts(
                lastChunk, estimateTokens(prompt), estimateTokens(fullResponse.toString())
            )
            performanceMonitor?.recordMetric(responseTime, tokensUsed, tokensGenerated)
        }
    }

    override fun estimateTokens(text: String): Int {
        return TokenEstimator.estimateTokens(text)
    }
}

/**
 * CloudModelWrapper: HTTP client for OpenAI/Anthropic with API key management
 */
class CloudModelWrapper(
    override val modelName: String,
    override val maxContextLength: Int,
    private val apiKey: String,
    private val baseUrl: String = "https://api.openai.com/v1",
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
                baseUrl = baseUrl
            ),
            llmModel = LLModel(
                id = modelName,
                provider = LLMProvider.Ollama,
                contextLength = maxContextLength.toLong(),
                maxOutputTokens = 2048L,
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
            val agent = AIAgent(
                promptExecutor = simpleOllamaAIExecutor(
                    baseUrl = baseUrl
                ),
                llmModel = LLModel(
                    id = modelName,
                    provider = LLMProvider.Ollama,
                    contextLength = maxContextLength.toLong(),
                    maxOutputTokens = 2048L,
                    capabilities = emptyList()
                ),
                systemPrompt = systemPrompt
            )

            val startTime = System.currentTimeMillis()
            val response = agent.run(prompt)
            val responseTime = System.currentTimeMillis() - startTime

            // Record performance metrics
            val tokensUsed = estimateTokens(prompt)
            val tokensGenerated = estimateTokens(response)
            performanceMonitor?.recordMetric(responseTime, tokensUsed, tokensGenerated)

            // Emit response in chunks for streaming effect
            val chunkSize = 50
            var offset = 0
            while (offset < response.length) {
                val chunk = response.substring(offset, minOf(offset + chunkSize, response.length))
                emit(chunk)
                offset += chunkSize
                kotlinx.coroutines.delay(10) // Small delay for streaming effect
            }
        }
    }

    override fun estimateTokens(text: String): Int {
        return TokenEstimator.estimateTokens(text)
    }
}
