package com.i2vision.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.net.http.HttpClient

/**
 * Unified service that manages multiple model repositories
 * Provides a single interface for accessing both local and cloud models
 */
class UnifiedModelService(
    private val repositories: List<ModelRepository>
) {
    private val httpClient = HttpClient.newHttpClient()
    private val _models = MutableStateFlow<List<OllamaModelMetadata>>(emptyList())
    private val _isLoading = MutableStateFlow(false)
    private val _errors = MutableStateFlow<List<String>>(emptyList())
    
    val models: Flow<List<OllamaModelMetadata>> = _models.asStateFlow()
    val isLoading: Flow<Boolean> = _isLoading.asStateFlow()
    val errors: Flow<List<String>> = _errors.asStateFlow()
    
    /**
     * Scan all repositories and update the model list
     */
    suspend fun refreshAllModels(): Result<List<OllamaModelMetadata>> = withContext(Dispatchers.IO) {
        _isLoading.value = true
        _errors.value = emptyList()
        
        try {
            val allModels = mutableListOf<OllamaModelMetadata>()
            val errors = mutableListOf<String>()
            
            repositories.forEach { repository: ModelRepository ->
                try {
                    val result = repository.scanModels()
                    result.fold(
                        onSuccess = { models: List<OllamaModelMetadata> -> 
                            allModels.addAll(models)
                            println("[UNIFIED] Successfully scanned ${models.size} models from ${repository.repositoryType}")
                        },
                        onFailure = { error: Throwable -> 
                            val errorMsg = "Failed to scan ${repository.repositoryType}: ${error.message ?: "null"}"
                            println("[UNIFIED] $errorMsg")
                            errors.add(errorMsg)
                        }
                    )
                } catch (e: Exception) {
                    val errorMsg = "Exception scanning ${repository.repositoryType}: ${e.message}"
                    println("[UNIFIED] $errorMsg")
                    errors.add(errorMsg)
                }
            }
            
            _models.value = allModels
            _errors.value = errors
            
            // Return success if we found any models, even if some repositories failed
            if (allModels.isNotEmpty()) {
                println("[UNIFIED] Returning ${allModels.size} models (with ${errors.size} repository errors)")
                Result.success(allModels)
            } else if (errors.isEmpty()) {
                // No models and no errors - no repositories configured
                println("[UNIFIED] No models found and no errors")
                Result.success(emptyList())
            } else {
                // No models found but there were errors
                println("[UNIFIED] No models found due to repository failures")
                Result.failure(Exception("All repositories failed: ${errors.joinToString("; ")}"))
            }
        } finally {
            _isLoading.value = false
        }
    }
    
    /**
     * Get a specific model by ID from any repository
     */
    suspend fun getModel(modelId: String): Result<OllamaModelMetadata?> = withContext(Dispatchers.IO) {
        repositories.forEach { repository: ModelRepository ->
            try {
                val result = repository.getModel(modelId)
                result.fold(
                    onSuccess = { model: OllamaModelMetadata? ->
                        if (model != null) {
                            return@withContext Result.success(model)
                        }
                    },
                    onFailure = { /* Continue to next repository */ }
                )
            } catch (e: Exception) {
                // Continue to next repository
            }
        }
        Result.success(null)
    }
    
    /**
     * Get models by repository type
     */
    fun getModelsByType(repositoryType: RepositoryType): List<OllamaModelMetadata> {
        return _models.value.filter { it.repositoryType == repositoryType }
    }
    
    /**
     * Get models by name pattern
     */
    fun searchModels(query: String): List<OllamaModelMetadata> {
        val lowercaseQuery = query.lowercase()
        return _models.value.filter { model ->
            model.name.lowercase().contains(lowercaseQuery) ||
            model.displayName.lowercase().contains(lowercaseQuery)
        }
    }
    
    /**
     * Add a new repository
     */
    fun addRepository(repository: ModelRepository) {
        // This would require mutable list of repositories
        // For now, we'll assume repositories are immutable after creation
        throw UnsupportedOperationException("Repositories are immutable after creation")
    }
    
    /**
     * Check if any repository is available
     */
    suspend fun isAnyRepositoryAvailable(): Boolean {
        return repositories.any { repository: ModelRepository ->
            try {
                repository.isAvailable()
            } catch (e: Exception) {
                false
            }
        }
    }
    
    /**
     * Get current model count
     */
    fun getModelCount(): Int = _models.value.size
    
    /**
     * Get model count by type
     */
    fun getModelCountByType(repositoryType: RepositoryType): Int {
        return getModelsByType(repositoryType).size
    }
    
    /**
     * Close all repositories and HTTP client
     */
    fun close() {
        repositories.forEach { repository: ModelRepository ->
            try {
                repository.close()
            } catch (e: Exception) {
                println("Error closing repository: ${e.message}")
            }
        }
        // HttpClient doesn't need explicit closing in Java 11+
    }
}

/**
 * Factory for creating UnifiedModelService instances
 */
object UnifiedModelServiceFactory {
    
    /**
     * Create a service with local Ollama repository
     */
    fun createWithLocalOllama(ollamaUrl: String = "http://localhost:11434"): UnifiedModelService {
        val localRepo = LocalOllamaRepository(ollamaUrl)
        return UnifiedModelService(listOf(localRepo))
    }
    
    /**
     * Create a service with cloud repositories
     */
    fun createWithCloudRepositories(cloudConfigs: List<CloudRepositoryConfig>): UnifiedModelService {
        val cloudRepos = cloudConfigs.map { config: CloudRepositoryConfig ->
            CloudOllamaRepository(config)
        }
        return UnifiedModelService(cloudRepos)
    }
    
    /**
     * Create a service with both local and cloud repositories
     */
    fun createWithLocalAndCloud(
        ollamaUrl: String = "http://localhost:11434",
        cloudConfigs: List<CloudRepositoryConfig> = emptyList()
    ): UnifiedModelService {
        val repositories = mutableListOf<ModelRepository>()
        repositories.add(LocalOllamaRepository(ollamaUrl))
        repositories.addAll(cloudConfigs.map { config: CloudRepositoryConfig -> CloudOllamaRepository(config) })
        return UnifiedModelService(repositories)
    }
}
