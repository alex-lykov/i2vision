/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets

/**
 * 3D LLM client that implements the ModelProvider interface.
 *
 * Communicates with the 3D LLM proxy (FreeDeepseekAPI / host2.onldigital.com)
 * which exposes an OpenAI-compatible endpoint for DeepSeek Web V3.
 *
 * @param baseUrl Proxy base URL (default: http://host2.onldigital.com:9654)
 * @param defaultModel Default model to use (default: deepseek-web-v3)
 */
class ThreeDLlmClient(
    private val baseUrl: String = "http://host2.onldigital.com:9654",
    private val defaultModel: String = "deepseek-web-v3"
) : ModelProvider {

    private val json = Json { ignoreUnknownKeys = true }
    private val httpClient = HttpClient.newBuilder().build()

    /**
     * Generate text from a prompt using 3D LLM proxy.
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
                .timeout(java.time.Duration.ofSeconds(timeoutSeconds))
                .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString(), StandardCharsets.UTF_8))
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

            if (response.statusCode() != 200) {
                val errorBody = try {
                    response.body()
                } catch (e: Exception) {
                    "Unknown error"
                }

                // Special handling for proxy connectivity issues
                if (response.statusCode() == 500 && errorBody.contains("fetch failed")) {
                    throw ThreeDLlmApiException(
                        "FreeDeepseekAPI proxy cannot connect to DeepSeek servers. Please check proxy connectivity and authentication.",
                        response.statusCode()
                    )
                }

                throw ThreeDLlmApiException(
                    "3D LLM API returned status ${response.statusCode()}: $errorBody",
                    response.statusCode()
                )
            }

            val responseBody = response.body()
            val responseJson = json.parseToJsonElement(responseBody).jsonObject

            val choices = responseJson["choices"]?.jsonArray
                ?: throw ThreeDLlmApiException("No choices field in 3D LLM response", 200)

            val firstChoice = choices.firstOrNull()
                ?: throw ThreeDLlmApiException("Empty choices array in 3D LLM response", 200)

            val message = firstChoice.jsonObject["message"]?.jsonObject
                ?: throw ThreeDLlmApiException("No message field in 3D LLM response", 200)

            message["content"]?.jsonPrimitive?.content
                ?: throw ThreeDLlmApiException("No content field in 3D LLM response", 200)

        } catch (e: ThreeDLlmApiException) {
            throw e
        } catch (e: Exception) {
            throw ThreeDLlmApiException("Failed to generate text: ${e.message}", -1, e)
        }
    }

    /**
     * Generate text with chat completions API (supports conversation history).
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
                .timeout(java.time.Duration.ofSeconds(timeoutSeconds))
                .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString(), StandardCharsets.UTF_8))
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

            if (response.statusCode() != 200) {
                val errorBody = try {
                    response.body()
                } catch (e: Exception) {
                    "Unknown error"
                }
                
                // Special handling for proxy connectivity issues
                if (response.statusCode() == 500 && errorBody.contains("fetch failed")) {
                    throw ThreeDLlmApiException(
                        "FreeDeepseekAPI proxy cannot connect to DeepSeek servers. Please check proxy connectivity and authentication.",
                        response.statusCode()
                    )
                }
                
                throw ThreeDLlmApiException(
                    "3D LLM API returned status ${response.statusCode()}: $errorBody",
                    response.statusCode()
                )
            }

            val responseBody = response.body()
            val responseJson = json.parseToJsonElement(responseBody).jsonObject

            val choices = responseJson["choices"]?.jsonArray
                ?: throw ThreeDLlmApiException("No choices field in 3D LLM response", 200)

            val firstChoice = choices.firstOrNull()
                ?: throw ThreeDLlmApiException("Empty choices array in 3D LLM response", 200)

            val message = firstChoice.jsonObject["message"]?.jsonObject
                ?: throw ThreeDLlmApiException("No message field in 3D LLM response", 200)

            message["content"]?.jsonPrimitive?.content
                ?: throw ThreeDLlmApiException("No content field in 3D LLM response", 200)

        } catch (e: ThreeDLlmApiException) {
            throw e
        } catch (e: Exception) {
            throw ThreeDLlmApiException("Failed to generate text: ${e.message}", -1, e)
        }
    }

    /**
     * Check if 3D LLM proxy is available.
     */
    suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = HttpRequest.newBuilder()
                .uri(URI.create("$baseUrl/health"))
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
                .uri(URI.create("$baseUrl/v1/models"))
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
 * Exception thrown when 3D LLM API encounters an error.
 */
class ThreeDLlmApiException(
    message: String,
    val statusCode: Int,
    cause: Throwable? = null
) : Exception(message, cause)
