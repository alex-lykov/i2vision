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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets

/**
 * Fetches model metadata from Ollama /api/show endpoint.
 * Returns actual context_length from model feedback instead of inference.
 */
object OllamaApiClient {

    private val json = Json { ignoreUnknownKeys = true }
    private val httpClient = HttpClient.newBuilder().build()

    /**
     * Fetch context length from Ollama /api/show for a given model.
     * @param baseUrl e.g. "http://localhost:11434"
     * @param modelName e.g. "qwen3-coder:480b-cloud" or "gemma3"
     * @return context_length from model_info, or null if not available
     */
    suspend fun fetchContextLength(baseUrl: String, modelName: String): Int? = withContext(Dispatchers.IO) {
        try {
            val body = """{"name":"${modelName.replace("\\", "\\\\").replace("\"", "\\\"")}"}"""
            val request = HttpRequest.newBuilder()
                .uri(URI.create("$baseUrl/api/show"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() != 200) return@withContext null

            val root = json.decodeFromString<JsonObject>(response.body())
            val modelInfo = root["model_info"]?.jsonObject ?: return@withContext null

            for ((key, value) in modelInfo) {
                if (key.endsWith(".context_length") || key == "context_length") {
                    val n = value.jsonPrimitive.content.toIntOrNull()
                    if (n != null && n > 0) return@withContext n
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }
}
