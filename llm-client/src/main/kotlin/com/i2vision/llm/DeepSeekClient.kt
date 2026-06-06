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
 * DeepSeek LLM client that implements the ModelProvider interface.
 * 
 * Communicates with DeepSeek API for text generation.
 * Supports both deepseek-chat and deepseek-reasoner models.
 * 
 * @param apiKey DeepSeek API key
 * @param baseUrl DeepSeek base URL (default: https://api.deepseek.com)
 * @param defaultModel Default model to use (default: deepseek-chat)
 */
class DeepSeekClient(
    private val apiKey: String,
    private val baseUrl: String = "https://api.deepseek.com",
    private val defaultModel: String = "deepseek-chat"
) : ModelProvider {

    private val json = Json { ignoreUnknownKeys = true }
    private val httpClient = HttpClient.newBuilder().build()

    /**
     * Generate text from a prompt using DeepSeek API.
     * 
     * @param prompt The prompt to send to the model
     * @param temperature Sampling temperature (0.0-1.0)
     * @param topP Nucleus sampling parameter
     * @param topK Top-K sampling parameter (not supported by DeepSeek, ignored)
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
                put("messages", buildJsonObject {
                    put("role", "user")
                    put("content", prompt)
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
                throw DeepSeekApiException(
                    "DeepSeek API returned status ${response.statusCode()}",
                    response.statusCode()
                )
            }

            val responseBody = response.body()
            val responseJson = json.parseToJsonElement(responseBody).jsonObject
            
            // Extract generated text from response
            val choices = responseJson["choices"]?.jsonArray
                ?: throw DeepSeekApiException("No choices field in DeepSeek response", 200)
            
            val firstChoice = choices.firstOrNull()
                ?: throw DeepSeekApiException("Empty choices array in DeepSeek response", 200)
            
            val message = firstChoice.jsonObject["message"]?.jsonObject
                ?: throw DeepSeekApiException("No message field in DeepSeek response", 200)
            
            message["content"]?.jsonPrimitive?.content
                ?: throw DeepSeekApiException("No content field in DeepSeek response", 200)

        } catch (e: DeepSeekApiException) {
            throw e
        } catch (e: Exception) {
            throw DeepSeekApiException("Failed to generate text: ${e.message}", -1, e)
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
                throw DeepSeekApiException(
                    "DeepSeek API returned status ${response.statusCode()}",
                    response.statusCode()
                )
            }

            val responseBody = response.body()
            val responseJson = json.parseToJsonElement(responseBody).jsonObject
            
            val choices = responseJson["choices"]?.jsonArray
                ?: throw DeepSeekApiException("No choices field in DeepSeek response", 200)
            
            val firstChoice = choices.firstOrNull()
                ?: throw DeepSeekApiException("Empty choices array in DeepSeek response", 200)
            
            val message = firstChoice.jsonObject["message"]?.jsonObject
                ?: throw DeepSeekApiException("No message field in DeepSeek response", 200)
            
            message["content"]?.jsonPrimitive?.content
                ?: throw DeepSeekApiException("No content field in DeepSeek response", 200)

        } catch (e: DeepSeekApiException) {
            throw e
        } catch (e: Exception) {
            throw DeepSeekApiException("Failed to generate text: ${e.message}", -1, e)
        }
    }

    /**
     * Check if DeepSeek API is available.
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
     * Get list of available DeepSeek models.
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
 * Chat message structure for DeepSeek API.
 */
data class ChatMessage(
    val role: String,
    val content: String
) {
    companion object {
        fun user(content: String) = ChatMessage("user", content)
        fun assistant(content: String) = ChatMessage("assistant", content)
        fun system(content: String) = ChatMessage("system", content)
    }
}

/**
 * Exception thrown when DeepSeek API encounters an error.
 */
class DeepSeekApiException(
    message: String,
    val statusCode: Int,
    cause: Throwable? = null
) : Exception(message, cause)
