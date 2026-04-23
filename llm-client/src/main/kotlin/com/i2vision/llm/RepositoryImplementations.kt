/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * Configuration for cloud repositories
 */
data class CloudRepositoryConfig(
    val name: String,
    val url: String,
    val apiKey: String? = null,
    val provider: RepositoryType = RepositoryType.GENERIC,
    val region: String? = null,
    val maxOutputTokens: Long = 2048L,
    val timeoutMs: Long = 30000L,
    val retryAttempts: Int = 3,
    val enabled: Boolean = true
)

/**
 * Local Ollama repository implementation
 */
class LocalOllamaRepository(
    private val ollamaUrl: String = "http://localhost:11434"
) : ModelRepository {

    private val httpClient = HttpClient.newHttpClient()
    private val _modelUpdates = MutableSharedFlow<OllamaModelMetadata>()

    override val repositoryType: RepositoryType = RepositoryType.LOCAL_OLLAMA

    override suspend fun scanModels(): Result<List<OllamaModelMetadata>> = withContext(Dispatchers.IO) {
        try {
            val request = HttpRequest.newBuilder()
                .uri(URI.create("$ollamaUrl/api/tags"))
                .GET()
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

            if (response.statusCode() == 200) {
                val models = ModelParsingUtils.parseOllamaModelsResponse(
                    response.body(),
                    RepositoryType.LOCAL_OLLAMA,
                    ollamaUrl
                )
                Result.success(models)
            } else {
                Result.failure(Exception("HTTP ${response.statusCode()}: ${response.body()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getModel(modelId: String): Result<OllamaModelMetadata?> = withContext(Dispatchers.IO) {
        try {
            val scanResult = scanModels()
            scanResult.fold(
                onSuccess = { models ->
                    val model = models.find { it.id == modelId }
                    Result.success(model)
                },
                onFailure = { error -> Result.failure(error) }
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun observeModels(): Flow<OllamaModelMetadata> = _modelUpdates

    override suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = HttpRequest.newBuilder()
                .uri(URI.create("$ollamaUrl/api/tags"))
                .GET()
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            response.statusCode() == 200
        } catch (e: Exception) {
            false
        }
    }

    override fun close() {
        // HttpClient doesn't need explicit closing in Java 11+
    }

    /**
     * Get running models from local Ollama
     */
    suspend fun getRunningModels(): Result<List<OllamaModelMetadata>> = withContext(Dispatchers.IO) {
        try {
            val request = HttpRequest.newBuilder()
                .uri(URI.create("$ollamaUrl/api/ps"))
                .GET()
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

            if (response.statusCode() == 200) {
                val models = ModelParsingUtils.parseOllamaModelsResponse(
                    response.body(),
                    RepositoryType.LOCAL_OLLAMA,
                    ollamaUrl
                )
                Result.success(models)
            } else {
                Result.failure(Exception("HTTP ${response.statusCode()}: ${response.body()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

/**
 * Cloud Ollama repository implementation
 */
class CloudOllamaRepository(
    private val config: CloudRepositoryConfig
) : ModelRepository {

    private val httpClient = HttpClient.newHttpClient()
    private val _modelUpdates = MutableSharedFlow<OllamaModelMetadata>()

    override val repositoryType: RepositoryType = config.provider

    override suspend fun scanModels(): Result<List<OllamaModelMetadata>> = withContext(Dispatchers.IO) {
        try {
            val requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create("${config.url}/api/tags"))
                .GET()

            // Add authentication header if API key is provided
            config.apiKey?.let { apiKey ->
                requestBuilder.header("Authorization", "Bearer $apiKey")
            }

            val request = requestBuilder.build()
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

            if (response.statusCode() == 200) {
                val models = ModelParsingUtils.parseOllamaModelsResponse(
                    response.body(),
                    config.provider,
                    config.url
                )
                Result.success(models)
            } else {
                Result.failure(Exception("HTTP ${response.statusCode()}: ${response.body()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getModel(modelId: String): Result<OllamaModelMetadata?> = withContext(Dispatchers.IO) {
        try {
            val scanResult = scanModels()
            scanResult.fold(
                onSuccess = { models ->
                    val model = models.find { it.id == modelId }
                    Result.success(model)
                },
                onFailure = { error -> Result.failure(error) }
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun observeModels(): Flow<OllamaModelMetadata> = _modelUpdates

    override suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        try {
            val requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create("${config.url}/api/tags"))
                .GET()

            config.apiKey?.let { apiKey ->
                requestBuilder.header("Authorization", "Bearer $apiKey")
            }

            val request = requestBuilder.build()
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            response.statusCode() == 200
        } catch (e: Exception) {
            false
        }
    }

    override fun close() {
        // HttpClient doesn't need explicit closing in Java 11+
    }
}
