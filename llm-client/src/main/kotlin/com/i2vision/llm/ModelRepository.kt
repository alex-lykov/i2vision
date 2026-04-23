/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.llm

import kotlinx.coroutines.flow.Flow

/**
 * Common abstraction for model repositories (local and cloud)
 */
interface ModelRepository {
    /**
     * Scan and return all available models from this repository
     */
    suspend fun scanModels(): Result<List<OllamaModelMetadata>>

    /**
     * Get a specific model by ID
     */
    suspend fun getModel(modelId: String): Result<OllamaModelMetadata?>

    /**
     * Stream model updates (if supported)
     */
    fun observeModels(): Flow<OllamaModelMetadata>

    /**
     * Check if repository is available
     */
    suspend fun isAvailable(): Boolean

    /**
     * Get repository type information
     */
    val repositoryType: RepositoryType

    /**
     * Close repository resources
     */
    fun close()
}

/**
 * Base metadata for all models
 */
sealed interface ModelMetadata {
    val id: String
    val name: String
    val tag: String
    val size: Long
    val contextLength: Int
    val digest: String
    val formattedSize: String
    val displayName: String
}

/**
 * Repository types
 */
enum class RepositoryType {
    LOCAL_OLLAMA,
    CLOUD_OLLAMA,
    HUGGING_FACE,
    REPLICATE,
    ANYSCALE,
    GENERIC
}
