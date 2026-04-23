/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * Legacy registry for managing local Ollama models
 * @deprecated Use UnifiedModelService with LocalOllamaRepository instead
 */
class ModelRegistry(private val ollamaApiUrl: String = "http://localhost:11434") {

    private val repository: LocalOllamaRepository = LocalOllamaRepository(ollamaApiUrl)
    private var cachedModels = mutableMapOf<String, ModelInfo>()

    /**
     * Fetch available models from local Ollama API
     */
    suspend fun scanModels(): List<ModelInfo> = withContext(Dispatchers.IO) {
        try {
            val result = repository.scanModels()
            result.fold(
                onSuccess = { ollamaModels: List<OllamaModelMetadata> ->
                    cachedModels.clear()
                    val modelInfos = ollamaModels.map { ollamaModel: OllamaModelMetadata ->
                        val modelInfo = ModelInfo(
                            id = ollamaModel.id,
                            name = ollamaModel.name,
                            tag = ollamaModel.tag,
                            size = ollamaModel.size,
                            contextLength = ollamaModel.contextLength,
                            digest = ollamaModel.digest,
                            layers = ollamaModel.layers,
                            path = ollamaModel.baseUrl,
                            error = ollamaModel.error
                        )
                        cachedModels[ollamaModel.id] = modelInfo
                        modelInfo
                    }
                    modelInfos
                },
                onFailure = { error ->
                    println("Failed to fetch models from API: ${error.message}")
                    emptyList()
                }
            )
        } catch (e: Exception) {
            println("Failed to fetch models from API: ${e.message}")
            emptyList()
        }
    }

    /**
     * Get running models from Ollama API
     */
    suspend fun getRunningModels(): List<ModelInfo> = withContext(Dispatchers.IO) {
        try {
            if (repository is LocalOllamaRepository) {
                val result = (repository as LocalOllamaRepository).getRunningModels()
                result.fold(
                    onSuccess = { ollamaModels: List<OllamaModelMetadata> ->
                        ollamaModels.map { ollamaModel: OllamaModelMetadata ->
                            ModelInfo(
                                id = ollamaModel.id,
                                name = ollamaModel.name,
                                tag = ollamaModel.tag,
                                size = ollamaModel.size,
                                contextLength = ollamaModel.contextLength,
                                digest = ollamaModel.digest,
                                layers = ollamaModel.layers,
                                path = ollamaModel.baseUrl,
                                error = ollamaModel.error
                            )
                        }
                    },
                    onFailure = { error ->
                        println("Failed to get running models: ${error.message}")
                        emptyList()
                    }
                )
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Get a specific model by ID
     */
    fun getModel(modelId: String): ModelInfo? = cachedModels[modelId]

    /**
     * Get all available models
     */
    fun getAllModels(): List<ModelInfo> = cachedModels.values.toList()

    /**
     * Create a ModelWrapper for the specified local model.
     * Fetches actual context_length from Ollama /api/show when available.
     */
    fun createModelWrapper(
        modelId: String,
        performanceMonitor: PerformanceMonitor? = null
    ): ModelWrapper? {
        val model = cachedModels[modelId] ?: return null
        val baseUrl = model.path.ifBlank { ollamaApiUrl }
        val maxContext = runBlocking {
            OllamaApiClient.fetchContextLength(baseUrl, model.id)
        } ?: model.contextLength

        return LocalModelWrapper(
            modelName = model.id,
            maxContextLength = maxContext,
            performanceMonitor = performanceMonitor
        )
    }

    /**
     * Check if a model exists
     */
    fun hasModel(modelId: String): Boolean = cachedModels.containsKey(modelId)

    /**
     * Close HTTP client
     */
    fun close() {
        repository.close()
    }
}

/**
 * Information about an available model
 */
data class ModelInfo(
    val id: String,
    val name: String,
    val tag: String,
    val size: Long,
    val contextLength: Int,
    val digest: String,
    val layers: Int,
    val path: String,
    val error: String? = null
) {
    val formattedSize: String
        get() = when {
            size > 1_000_000_000 -> "${size / 1_000_000_000} GB"
            size > 1_000_000 -> "${size / 1_000_000} MB"
            size > 1_000 -> "${size / 1_000} KB"
            else -> "$size B"
        }
}
