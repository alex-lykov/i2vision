package core

import com.alyk.ai.koog.config.CloudConfig
import com.alyk.ai.koog.models.cloud.CloudModelRegistry
import com.alyk.ai.koog.models.cloud.CloudProvider
import com.alyk.ai.koog.models.cloud.OllamaCloudConfig
import com.alyk.ai.koog.models.wrappers.ModelInfo
import com.alyk.ai.koog.models.wrappers.ModelRegistry
import com.alyk.ai.koog.models.wrappers.ModelWrapper
import com.alyk.ai.koog.switching.monitor.PerformanceMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Unified model manager that handles both local and cloud models
 * Avoids circular dependencies by coordinating between registries
 */
class UnifiedModelManager(
    private val localRegistry: ModelRegistry,
    private val cloudConfigs: List<CloudConfig> = emptyList()
) {
    private val cloudRegistry: CloudModelRegistry? = 
        if (cloudConfigs.isNotEmpty()) {
            val ollamaCloudConfigs = cloudConfigs.map { config ->
                OllamaCloudConfig(
                    apiUrl = config.apiUrl,
                    apiKey = config.apiKey,
                    maxOutputTokens = config.maxOutputTokens,
                    timeoutMs = config.timeoutMs,
                    retryAttempts = config.retryAttempts,
                    region = config.region,
                    provider = CloudProvider.valueOf(config.provider.uppercase())
                )
            }
            CloudModelRegistry(ollamaCloudConfigs)
        } else null
    
    /**
     * Scan both local and cloud models
     */
    suspend fun scanAllModels(): List<ModelInfo> = withContext(Dispatchers.IO) {
        val allModels = mutableListOf<ModelInfo>()
        
        // Scan local models
        try {
            val localModels = localRegistry.scanModels()
            allModels.addAll(localModels)
        } catch (e: Exception) {
            println("Failed to scan local models: ${e.message}")
        }
        
        // Scan cloud models
        cloudRegistry?.let { registry ->
            try {
                val cloudModels = registry.scanModels()
                // Convert cloud models to ModelInfo format
                allModels.addAll(cloudModels.map { cloudModel ->
                    ModelInfo(
                        id = cloudModel.id,
                        name = cloudModel.name,
                        tag = cloudModel.tag,
                        size = cloudModel.size,
                        contextLength = cloudModel.contextLength,
                        digest = cloudModel.digest,
                        layers = cloudModel.layers,
                        path = cloudModel.path,
                        error = cloudModel.error
                    )
                })
            } catch (e: Exception) {
                println("Failed to scan cloud models: ${e.message}")
            }
        }
        
        allModels
    }
    
    /**
     * Create a model wrapper for the specified model ID
     * Supports both local and cloud models
     */
    fun createModelWrapper(
        modelId: String,
        performanceMonitor: PerformanceMonitor? = null
    ): ModelWrapper? {
        // Check if this is a cloud model by ID pattern
        return if (modelId.contains(":") && modelId.split(":").first().lowercase() in 
            listOf("generic", "ollama_cloud", "hugging_face", "replicate", "anyscale")) {
            // Create cloud model wrapper
            cloudRegistry?.createModelWrapper(modelId, performanceMonitor)
        } else {
            // Create local model wrapper
            localRegistry.createModelWrapper(modelId, performanceMonitor)
        }
    }
    
    /**
     * Get only local models
     */
    suspend fun getLocalModels(): List<ModelInfo> {
        return try {
            localRegistry.scanModels()
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    /**
     * Get only cloud models
     */
    suspend fun getCloudModels(): List<ModelInfo> {
        return cloudRegistry?.let { registry ->
            try {
                registry.scanModels().map { cloudModel ->
                    ModelInfo(
                        id = cloudModel.id,
                        name = cloudModel.name,
                        tag = cloudModel.tag,
                        size = cloudModel.size,
                        contextLength = cloudModel.contextLength,
                        digest = cloudModel.digest,
                        layers = cloudModel.layers,
                        path = cloudModel.path,
                        error = cloudModel.error
                    )
                }
            } catch (e: Exception) {
                emptyList()
            }
        } ?: emptyList()
    }
    
    /**
     * Check if a model exists in either local or cloud
     */
    suspend fun hasModel(modelId: String): Boolean {
        // Check local first
        if (localRegistry.hasModel(modelId)) return true
        
        // Check cloud
        return cloudRegistry?.let { registry ->
            try {
                registry.scanModels().any { it.id == modelId }
            } catch (e: Exception) {
                false
            }
        } ?: false
    }
    
    /**
     * Get running models from local registry
     */
    suspend fun getRunningModels(): List<ModelInfo> {
        return try {
            localRegistry.getRunningModels()
        } catch (e: Exception) {
            println("Failed to get running models: ${e.message}")
            emptyList()
        }
    }
    
    /**
     * Close all registries
     */
    fun close() {
        localRegistry.close()
        cloudRegistry?.close()
    }
}
