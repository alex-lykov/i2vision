/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets

/**
 * Ollama LLM client that implements the ModelProvider interface.
 * 
 * Communicates with Ollama API for text generation.
 * Supports both local and cloud Ollama instances.
 * 
 * @param baseUrl Ollama base URL (default: http://localhost:11434)
 * @param defaultModel Default model to use (default: llama3.2:3b)
 */
class OllamaLlmClient(
    private val baseUrl: String = "http://localhost:11434",
    private val defaultModel: String = "llama3.2:3b"
) : ModelProvider {

    private val json = Json { ignoreUnknownKeys = true }
    private val httpClient = HttpClient.newBuilder().build()

    /**
     * Generate text from a prompt using Ollama API.
     * 
     * @param prompt The prompt to send to the model
     * @param temperature Sampling temperature (0.0-1.0)
     * @param topP Nucleus sampling parameter
     * @param topK Top-K sampling parameter
     * @param maxTokens Maximum tokens to generate
     * @param timeoutSeconds Request timeout in seconds
     * @return Generated text response
     */
    override suspend fun generate(
        prompt: String,
        temperature: Double,
        topP: Double,
        topK: Int,
        maxTokens: Int,
        timeoutSeconds: Long
    ): String = withContext(Dispatchers.IO) {
        try {
            val requestBody = buildJsonObject {
                put("model", defaultModel)
                put("prompt", prompt)
                put("stream", false)
                put("options", buildJsonObject {
                    put("temperature", temperature)
                    put("top_p", topP)
                    put("top_k", topK)
                    put("num_predict", maxTokens)
                })
            }

            val request = HttpRequest.newBuilder()
                .uri(URI.create("$baseUrl/api/generate"))
                .header("Content-Type", "application/json")
                .timeout(java.time.Duration.ofSeconds(timeoutSeconds))
                .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString(), StandardCharsets.UTF_8))
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            
            if (response.statusCode() != 200) {
                throw OllamaApiException(
                    "Ollama API returned status ${response.statusCode()}",
                    response.statusCode()
                )
            }

            val responseBody = response.body()
            val responseJson = json.parseToJsonElement(responseBody).jsonObject
            
            // Extract generated text from response
            responseJson["response"]?.jsonPrimitive?.content
                ?: throw OllamaApiException("No response field in Ollama response", 200)

        } catch (e: OllamaApiException) {
            throw e
        } catch (e: Exception) {
            throw OllamaApiException("Failed to generate text: ${e.message}", -1, e)
        }
    }

    /**
     * Generate text with a specific model (override default).
     */
    suspend fun generate(
        model: String,
        prompt: String,
        temperature: Double = 0.7,
        topP: Double = 0.9,
        topK: Int = 40,
        maxTokens: Int = 2048,
        timeoutSeconds: Long = 60
    ): String = withContext(Dispatchers.IO) {
        try {
            val requestBody = buildJsonObject {
                put("model", model)
                put("prompt", prompt)
                put("stream", false)
                put("options", buildJsonObject {
                    put("temperature", temperature)
                    put("top_p", topP)
                    put("top_k", topK)
                    put("num_predict", maxTokens)
                })
            }

            val request = HttpRequest.newBuilder()
                .uri(URI.create("$baseUrl/api/generate"))
                .header("Content-Type", "application/json")
                .timeout(java.time.Duration.ofSeconds(timeoutSeconds))
                .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString(), StandardCharsets.UTF_8))
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            
            if (response.statusCode() != 200) {
                throw OllamaApiException(
                    "Ollama API returned status ${response.statusCode()}",
                    response.statusCode()
                )
            }

            val responseBody = response.body()
            val responseJson = json.parseToJsonElement(responseBody).jsonObject
            
            responseJson["response"]?.jsonPrimitive?.content
                ?: throw OllamaApiException("No response field in Ollama response", 200)

        } catch (e: OllamaApiException) {
            throw e
        } catch (e: Exception) {
            throw OllamaApiException("Failed to generate text: ${e.message}", -1, e)
        }
    }

    /**
     * Check if Ollama server is available.
     */
    suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = HttpRequest.newBuilder()
                .uri(URI.create("$baseUrl/api/tags"))
                .GET()
                .timeout(java.time.Duration.ofSeconds(5))
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            response.statusCode() == 200
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Get list of available models.
     */
    suspend fun listModels(): List<String> = withContext(Dispatchers.IO) {
        try {
            val request = HttpRequest.newBuilder()
                .uri(URI.create("$baseUrl/api/tags"))
                .GET()
                .timeout(java.time.Duration.ofSeconds(10))
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            
            if (response.statusCode() != 200) {
                return@withContext emptyList()
            }

            val responseJson = json.parseToJsonElement(response.body()).jsonObject
            val models = responseJson["models"]?.jsonArray
            
            models?.map { model ->
                model.jsonObject["name"]?.jsonPrimitive?.content ?: ""
            }?.filter { it.isNotEmpty() } ?: emptyList()

        } catch (e: Exception) {
            emptyList()
        }
    }
}

/**
 * Exception thrown when Ollama API encounters an error.
 */
class OllamaApiException(
    message: String,
    val statusCode: Int,
    cause: Throwable? = null
) : Exception(message, cause)
