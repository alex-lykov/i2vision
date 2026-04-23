/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Legacy registry for managing cloud-hosted Ollama models
 * @deprecated Use UnifiedModelService with CloudOllamaRepository instead
 */
class CloudModelRegistry(
    private val cloudConfigs: List<OllamaCloudConfig>
) {
    private val repositories = cloudConfigs.map { config ->
        CloudOllamaRepository(
            CloudRepositoryConfig(
                name = config.provider.name,
                url = config.apiUrl,
                apiKey = config.apiKey,
                provider = RepositoryType.valueOf(config.provider.name),
                region = config.region,
                maxOutputTokens = config.maxOutputTokens,
                timeoutMs = config.timeoutMs,
                retryAttempts = config.retryAttempts
            )
        )
    }
    private val models = mutableMapOf<String, CloudModelInfo>()

    /**
     * Scan all configured cloud endpoints for available models
     */
    suspend fun scanModels(): List<CloudModelInfo> = withContext(Dispatchers.IO) {
        models.clear()
        val allModels = mutableListOf<CloudModelInfo>()

        repositories.forEach { repository ->
            try {
                val result = repository.scanModels()
                result.fold(
                    onSuccess = { ollamaModels ->
                        val cloudModels = ollamaModels.map { ollamaModel ->
                            val cloudModelInfo = CloudModelInfo(
                                id = ollamaModel.id,
                                name = ollamaModel.name,
                                tag = ollamaModel.tag,
                                size = ollamaModel.size,
                                contextLength = ollamaModel.contextLength,
                                digest = ollamaModel.digest,
                                layers = ollamaModel.layers,
                                path = ollamaModel.baseUrl,
                                cloudConfig = findConfigForUrl(ollamaModel.baseUrl),
                                provider = CloudProvider.valueOf(ollamaModel.repositoryType.name),
                                region = null, // Could be extracted from config if needed
                                isAvailable = true,
                                error = ollamaModel.error
                            )
                            models[ollamaModel.id] = cloudModelInfo
                            cloudModelInfo
                        }
                        allModels.addAll(cloudModels)
                    },
                    onFailure = { error ->
                        println("Failed to scan cloud repository: ${error.message}")
                    }
                )
            } catch (e: Exception) {
                println("Failed to scan cloud repository: ${e.message}")
            }
        }

        allModels
    }

    private fun findConfigForUrl(url: String): OllamaCloudConfig {
        return cloudConfigs.find { it.apiUrl == url }
            ?: OllamaCloudConfig(url) // Fallback config
    }

    /**
     * Create a ModelWrapper for the specified cloud model
     */
    fun createModelWrapper(
        modelId: String,
        performanceMonitor: PerformanceMonitor? = null
    ): ModelWrapper? {
        val model = models[modelId] ?: return null

        return OllamaCloudModelWrapper(
            modelName = model.name,
            maxContextLength = model.contextLength,
            cloudConfig = model.cloudConfig,
            performanceMonitor = performanceMonitor
        )
    }

    /**
     * Get models by provider
     */
    fun getModelsByProvider(provider: CloudProvider): List<CloudModelInfo> {
        return models.values.filter { it.provider == provider }
    }

    /**
     * Get models by region
     */
    fun getModelsByRegion(region: String): List<CloudModelInfo> {
        return models.values.filter { it.region == region }
    }

    /**
     * Close all repositories
     */
    fun close() {
        repositories.forEach { it.close() }
    }
}

/**
 * Extended model information for cloud models
 */
data class CloudModelInfo(
    val id: String,
    val name: String,
    val tag: String,
    val size: Long,
    val contextLength: Int,
    val digest: String,
    val layers: Int,
    val path: String,
    val cloudConfig: OllamaCloudConfig,
    val provider: CloudProvider,
    val region: String?,
    val isAvailable: Boolean,
    val error: String? = null
) {
    val formattedSize: String
        get() = when {
            size > 1_000_000_000 -> "${size / 1_000_000_000} GB"
            size > 1_000_000 -> "${size / 1_000_000} MB"
            size > 1_000 -> "${size / 1_000} KB"
            else -> "$size B"
        }

    val displayName: String
        get() = "${provider.name.lowercase()}:${name}:${tag}"
}
