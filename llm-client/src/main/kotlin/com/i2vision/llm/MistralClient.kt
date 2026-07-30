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
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.add
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets

/**
 * Mistral LLM client that implements the ModelProvider interface.
 * 
 * Communicates with Mistral AI API for text generation.
 * Supports various Mistral models like mistral-tiny, mistral-small, mistral-medium, etc.
 * 
 * @param apiKey Mistral API key
 * @param baseUrl Mistral base URL (default: https://api.mistral.ai)
 * @param defaultModel Default model to use (default: mistral-tiny)
 */
class MistralClient(
    private val apiKey: String,
    private val baseUrl: String = "https://api.mistral.ai",
    private val defaultModel: String = "mistral-tiny"
) : ModelProvider {

    private val json = Json { ignoreUnknownKeys = true }
    private val httpClient = HttpClient.newBuilder().build()

    /**
     * Generate text from a prompt using Mistral API.
     * 
     * @param prompt The prompt to send to the model
     * @param temperature Sampling temperature (0.0-1.0)
     * @param topP Nucleus sampling parameter
     * @param topK Top-K sampling parameter (not supported by Mistral, ignored)
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
                put("messages", buildJsonArray {
                    add(buildJsonObject {
                        put("role", "user")
                        put("content", prompt)
                    })
                })
                put("temperature", temperature)
                put("top_p", topP)
                put("max_tokens", maxTokens)
                put("stream", false)
            }

            val request = HttpRequest.newBuilder()
                .uri(URI.create("$baseUrl/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer $apiKey")
                .timeout(java.time.Duration.ofSeconds(timeoutSeconds))
                .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString(), StandardCharsets.UTF_8))
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            
            if (response.statusCode() != 200) {
                throw MistralApiException(
                    "Mistral API returned status ${response.statusCode()}",
                    response.statusCode()
                )
            }

            val responseBody = response.body()
            val responseJson = json.parseToJsonElement(responseBody).jsonObject
            
            // Extract generated text from response
            val choices = responseJson["choices"]?.jsonArray
                ?: throw MistralApiException("No choices field in Mistral response", 200)
            
            val firstChoice = choices.firstOrNull()
                ?: throw MistralApiException("Empty choices array in Mistral response", 200)
            
            val message = firstChoice.jsonObject["message"]?.jsonObject
                ?: throw MistralApiException("No message field in Mistral response", 200)
            
            message["content"]?.jsonPrimitive?.content
                ?: throw MistralApiException("No content field in Mistral response", 200)

        } catch (e: MistralApiException) {
            throw e
        } catch (e: Exception) {
            throw MistralApiException("Failed to generate text: ${e.message}", -1, e)
        }
    }

    /**
     * Generate text with chat completions API (supports conversation history).
     * 
     * @param messages List of chat messages (role + content)
     * @param temperature Sampling temperature (0.0-1.0)
     * @param topP Nucleus sampling parameter
     * @param maxTokens Maximum tokens to generate
     * @param timeoutSeconds Request timeout in seconds
     * @return Generated text response
     */
    suspend fun chat(
        messages: List<ChatMessage>,
        temperature: Double = 0.7,
        topP: Double = 0.9,
        maxTokens: Int = 4096,
        timeoutSeconds: Long = 60
    ): String = withContext(Dispatchers.IO) {
        try {
            val requestBody = buildJsonObject {
                put("model", defaultModel)
                put("messages", buildJsonArray {
                    messages.forEach { message ->
                        add(buildJsonObject {
                            put("role", message.role)
                            put("content", message.content)
                        })
                    }
                })
                put("temperature", temperature)
                put("top_p", topP)
                put("max_tokens", maxTokens)
                put("stream", false)
            }

            val request = HttpRequest.newBuilder()
                .uri(URI.create("$baseUrl/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer $apiKey")
                .timeout(java.time.Duration.ofSeconds(timeoutSeconds))
                .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString(), StandardCharsets.UTF_8))
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            
            if (response.statusCode() != 200) {
                throw MistralApiException(
                    "Mistral API returned status ${response.statusCode()}",
                    response.statusCode()
                )
            }

            val responseBody = response.body()
            val responseJson = json.parseToJsonElement(responseBody).jsonObject
            
            val choices = responseJson["choices"]?.jsonArray
                ?: throw MistralApiException("No choices field in Mistral response", 200)
            
            val firstChoice = choices.firstOrNull()
                ?: throw MistralApiException("Empty choices array in Mistral response", 200)
            
            val message = firstChoice.jsonObject["message"]?.jsonObject
                ?: throw MistralApiException("No message field in Mistral response", 200)
            
            message["content"]?.jsonPrimitive?.content
                ?: throw MistralApiException("No content field in Mistral response", 200)

        } catch (e: MistralApiException) {
            throw e
        } catch (e: Exception) {
            throw MistralApiException("Failed to generate text: ${e.message}", -1, e)
        }
    }

    /**
     * Check if Mistral API is available.
     */
    suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = HttpRequest.newBuilder()
                .uri(URI.create("$baseUrl/v1/models"))
                .header("Authorization", "Bearer $apiKey")
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
     * Get list of available Mistral models.
     */
    suspend fun listModels(): List<String> = withContext(Dispatchers.IO) {
        try {
            val request = HttpRequest.newBuilder()
                .uri(URI.create("$baseUrl/v1/models"))
                .header("Authorization", "Bearer $apiKey")
                .GET()
                .timeout(java.time.Duration.ofSeconds(10))
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            
            if (response.statusCode() != 200) {
                return@withContext emptyList()
            }

            val responseJson = json.parseToJsonElement(response.body()).jsonObject
            val models = responseJson["data"]?.jsonArray
            
            models?.map { model ->
                model.jsonObject["id"]?.jsonPrimitive?.content ?: ""
            }?.filter { it.isNotEmpty() } ?: emptyList()

        } catch (e: Exception) {
            emptyList()
        }
    }
}

/**
 * Chat message structure for Mistral API.
 */
data class MistralChatMessage(
    val role: String,
    val content: String
) {
    companion object {
        fun user(content: String) = MistralChatMessage("user", content)
        fun assistant(content: String) = MistralChatMessage("assistant", content)
        fun system(content: String) = MistralChatMessage("system", content)
    }
}

/**
 * Exception thrown when Mistral API encounters an error.
 */
class MistralApiException(
    message: String,
    val statusCode: Int,
    cause: Throwable? = null
) : Exception(message, cause)