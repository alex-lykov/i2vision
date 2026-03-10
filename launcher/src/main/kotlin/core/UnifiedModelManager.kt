package core

import com.alyk.ai.koog.config.CloudConfig
import com.alyk.ai.koog.models.cloud.CloudModelInfo
import com.alyk.ai.koog.models.cloud.CloudModelRegistry
import com.alyk.ai.koog.models.cloud.CloudProvider
import com.alyk.ai.koog.models.cloud.OllamaCloudConfig
import com.alyk.ai.koog.models.common.*
import com.alyk.ai.koog.models.wrappers.*
import com.alyk.ai.koog.switching.monitor.PerformanceMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * Unified model manager that handles both local and cloud models
 * Refactored to use the new UnifiedModelService architecture
 */
class UnifiedModelManager(
    private val localRegistry: ModelRegistry,
    private val cloudConfigs: List<CloudConfig> = emptyList()
) {
    // Legacy support
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
    
    // New unified service
    private val unifiedService: UnifiedModelService = run {
        val cloudRepoConfigs = cloudConfigs.map { config ->
            CloudRepositoryConfig(
                name = config.provider,
                url = config.apiUrl,
                apiKey = config.apiKey,
                provider = RepositoryType.valueOf(config.provider.uppercase()),
                region = config.region,
                maxOutputTokens = config.maxOutputTokens,
                timeoutMs = config.timeoutMs,
                retryAttempts = config.retryAttempts
            )
        }
        
        // Only add cloud repositories if we have valid configurations
        val allCloudConfigs = if (cloudRepoConfigs.isEmpty()) {
            println("[UNIFIED] No cloud configs provided, skipping cloud repositories")
            emptyList()
        } else {
            println("[UNIFIED] Using ${cloudRepoConfigs.size} configured cloud repositories")
            cloudRepoConfigs
        }
        
        UnifiedModelServiceFactory.createWithLocalAndCloud(
            ollamaUrl = "http://localhost:11434",
            cloudConfigs = allCloudConfigs
        )
    }
    
    /**
     * Unified model classification based on capabilities and naming patterns
     */
    private fun classifyModel(model: OllamaModelMetadata): ModelType {
        // Priority 1: Explicit cloud indicators in name or tag (user wants cloud view)
        val modelName = model.name.lowercase()
        val tag = model.tag.lowercase()
        val id = model.id.lowercase()
        
        when {
            // Explicit cloud indicators - always treat as cloud for UI purposes
            id.contains("-cloud") || tag.contains("-cloud") -> return ModelType.CLOUD
            modelName.contains("cloud") -> return ModelType.CLOUD
            
            // Cloud provider prefixes - always treat as cloud
            modelName.startsWith("generic:") || 
            modelName.startsWith("ollama_cloud:") ||
            modelName.startsWith("hugging_face:") ||
            modelName.startsWith("replicate:") ||
            modelName.startsWith("anyscale:") -> return ModelType.CLOUD
        }
        
        // Priority 2: Repository type (for models without explicit cloud indicators)
        when (model.repositoryType) {
            RepositoryType.LOCAL_OLLAMA -> return ModelType.LOCAL
            RepositoryType.CLOUD_OLLAMA,
            RepositoryType.HUGGING_FACE,
            RepositoryType.REPLICATE,
            RepositoryType.ANYSCALE,
            RepositoryType.GENERIC -> return ModelType.CLOUD
        }
        
        // Default to local
        return ModelType.LOCAL
    }
    
    /**
     * Get all models with unified classification
     */
    suspend fun getAllModelsUnified(): List<ModelInfo> {
        return withContext(Dispatchers.IO) {
            try {
                println("[UNIFIED] Getting all models with unified classification...")
                val result = unifiedService.refreshAllModels()
                val models: List<ModelInfo> = result.fold(
                    onSuccess = { models ->
                        println("[UNIFIED] Successfully scanned ${models.size} models:")
                        val classifiedModels = models.map { model ->
                            val modelType = classifyModel(model)
                            println("[UNIFIED]   - ${model.id} (repo: ${model.repositoryType}, classified: $modelType)")
                            
                            ModelInfo(
                                id = model.id,
                                name = model.name,
                                tag = model.tag,
                                size = model.size,
                                contextLength = model.contextLength,
                                digest = model.digest,
                                layers = model.layers,
                                path = model.baseUrl,
                                error = model.error
                            )
                        }
                        classifiedModels
                    },
                    onFailure = { error ->
                        println("[UNIFIED] Failed to scan models: ${error.message}")
                        emptyList<ModelInfo>()
                    }
                )
                models
            } catch (e: Exception) {
                println("[UNIFIED] Exception during model scanning: ${e.message}")
                e.printStackTrace()
                emptyList<ModelInfo>()
            }
        }
    }
    suspend fun scanAllModels(): List<ModelInfo> = withContext(Dispatchers.IO) {
        try {
            println("[SCAN] Scanning all models using unified service...")
            val result = unifiedService.refreshAllModels()
            result.fold(
                onSuccess = { models ->
                    println("[SCAN] Successfully scanned ${models.size} models:")
                    models.forEach { model ->
                        println("[SCAN]   - ${model.id} (type: ${model.repositoryType}, size: ${model.size})")
                    }
                    
                    // Convert unified models to legacy ModelInfo format
                    models.map { model ->
                        if (model is OllamaModelMetadata) {
                            ModelInfo(
                                id = model.id,
                                name = model.name,
                                tag = model.tag,
                                size = model.size,
                                contextLength = model.contextLength,
                                digest = model.digest,
                                layers = model.layers,
                                path = model.baseUrl,
                                error = model.error
                            )
                        } else {
                            // Handle other model types if needed
                            ModelInfo(
                                id = model.id,
                                name = model.name,
                                tag = model.tag,
                                size = model.size,
                                contextLength = model.contextLength,
                                digest = model.digest,
                                layers = 0,
                                path = "unknown",
                                error = null
                            )
                        }
                    }
                },
                onFailure = { error ->
                    println("Failed to scan models using unified service: ${error.message}")
                    // Fallback to legacy approach
                    scanAllModelsLegacy()
                }
            )
        } catch (e: Exception) {
            println("Exception using unified service: ${e.message}")
            scanAllModelsLegacy()
        }
    }
    
    /**
     * Legacy fallback method for scanning models
     */
    private suspend fun scanAllModelsLegacy(): List<ModelInfo> {
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
        
        return allModels
    }
    
    /**
     * Create a model wrapper for the specified model ID
     * Supports both local and cloud models with consistent behavior
     */
    fun createModelWrapper(
        modelId: String,
        performanceMonitor: PerformanceMonitor? = null
    ): ModelWrapper? {
        // Check if this is a cloud model by ID pattern or tag suffix
        val isCloudModel = try {
            val parts = modelId.split(":")
            val modelName = parts.getOrNull(0) ?: ""
            val tag = parts.getOrNull(1) ?: ""
            
            // Check for cloud prefixes in model name OR -cloud suffix in tag
            modelName.lowercase() in listOf("generic", "ollama_cloud", "hugging_face", "replicate", "anyscale") ||
            tag.lowercase().contains("-cloud")
        } catch (e: Exception) {
            false
        }
        
        println("[WRAPPER] Creating model wrapper for: $modelId (isCloudModel=$isCloudModel)")
        
        return if (isCloudModel) {
            println("[WRAPPER] Attempting cloud model wrapper creation...")
            // Create cloud model wrapper
            cloudRegistry?.createModelWrapper(modelId, performanceMonitor)?.also {
                println("[WRAPPER] Cloud model wrapper created successfully")
            } ?: run {
                println("[WRAPPER] Cloud registry is null or failed to create wrapper, falling back to local...")
                // Fallback to local registry if cloud fails
                try {
                    val wrapper = localRegistry.createModelWrapper(modelId, performanceMonitor)
                    if (wrapper != null) {
                        println("[WRAPPER] Fallback local model wrapper created successfully")
                        wrapper
                    } else {
                        println("[WRAPPER] Fallback local registry returned null, trying unified service...")
                        createModelWrapperFromUnifiedService(modelId, performanceMonitor)
                    }
                } catch (e: Exception) {
                    println("[WRAPPER] Fallback local model wrapper creation failed: ${e.message}")
                    e.printStackTrace()
                    createModelWrapperFromUnifiedService(modelId, performanceMonitor)
                }
            }
        } else {
            println("[WRAPPER] Attempting local model wrapper creation...")
            // Try local registry first
            try {
                val wrapper = localRegistry.createModelWrapper(modelId, performanceMonitor)
                if (wrapper != null) {
                    println("[WRAPPER] Local model wrapper created successfully")
                    wrapper
                } else {
                    println("[WRAPPER] Local registry returned null, trying unified service...")
                    // Fallback: create wrapper using unified service data
                    createModelWrapperFromUnifiedService(modelId, performanceMonitor)
                }
            } catch (e: Exception) {
                println("[WRAPPER] Failed to create local model wrapper: ${e.message}")
                e.printStackTrace()
                // Fallback: create wrapper using unified service data
                createModelWrapperFromUnifiedService(modelId, performanceMonitor)
            }
        }
    }
    
    /**
     * Create model wrapper using unified service data as fallback
     */
    private fun createModelWrapperFromUnifiedService(
        modelId: String,
        performanceMonitor: PerformanceMonitor?
    ): ModelWrapper? {
        return try {
            // Get model from unified service
            val result = runBlocking {
                unifiedService.getModel(modelId)
            }
            result.fold(
                onSuccess = { model: OllamaModelMetadata? ->
                    if (model != null) {
                        println("[WRAPPER] Creating wrapper from unified service data for: $modelId")
                        LocalModelWrapper(
                            modelName = model.id,
                            maxContextLength = model.contextLength,
                            performanceMonitor = performanceMonitor
                        ).also {
                            println("[WRAPPER] Unified service model wrapper created successfully")
                        }
                    } else {
                        println("[WRAPPER] Model not found in unified service: $modelId")
                        null
                    }
                },
                onFailure = { error: Throwable ->
                    println("[WRAPPER] Failed to get model from unified service: ${error.message}")
                    null
                }
            )
        } catch (e: Exception) {
            println("[WRAPPER] Exception creating wrapper from unified service: ${e.message}")
            e.printStackTrace()
            null
        }
    }
    
    /**
     * Estimate tokens consistently across all model types
     */
    fun estimateTokens(text: String): Int {
        return TokenEstimator.estimateTokens(text)
    }
    
    /**
     * Estimate tokens for code specifically
     */
    fun estimateTokensForCode(text: String): Int {
        return TokenEstimator.estimateTokensForCode(text)
    }
    
    /**
     * Get local models using unified classification
     */
    suspend fun getLocalModels(): List<ModelInfo> {
        try {
            println("[LOCAL] Getting local models using unified classification...")
            val allModels = getAllModelsUnified()
            val localModels = mutableListOf<ModelInfo>()
            
            for (modelInfo in allModels) {
                // Re-classify the model to determine if it's local
                val result = unifiedService.getModel(modelInfo.id)
                val isLocal = result.fold(
                    onSuccess = { model ->
                        if (model != null) {
                            val classifiedAsLocal = classifyModel(model) == ModelType.LOCAL
                            if (classifiedAsLocal) {
                                println("[LOCAL]   - ${modelInfo.id} (classified as local)")
                            }
                            classifiedAsLocal
                        } else {
                            false
                        }
                    },
                    onFailure = { false }
                )
                
                if (isLocal) {
                    localModels.add(modelInfo)
                }
            }
            
            println("[LOCAL] Found ${localModels.size} local models")
            return localModels
        } catch (e: Exception) {
            println("Failed to get local models using unified service: ${e.message}")
            // Fallback to legacy approach
            try {
                val legacyModels = localRegistry.scanModels()
                return legacyModels
            } catch (ex: Exception) {
                return emptyList<ModelInfo>()
            }
        }
    }
    
    /**
     * Get cloud models using unified classification
     */
    suspend fun getCloudModels(): List<ModelInfo> {
        try {
            println("[CLOUD] Getting cloud models using unified classification...")
            val allModels = getAllModelsUnified()
            val cloudModels = mutableListOf<ModelInfo>()
            
            for (modelInfo in allModels) {
                // Re-classify the model to determine if it's cloud
                val result = unifiedService.getModel(modelInfo.id)
                val isCloud = result.fold(
                    onSuccess = { model ->
                        if (model != null) {
                            val classifiedAsCloud = classifyModel(model) == ModelType.CLOUD
                            if (classifiedAsCloud) {
                                println("[CLOUD]   - ${modelInfo.id} (classified as cloud)")
                            }
                            classifiedAsCloud
                        } else {
                            false
                        }
                    },
                    onFailure = { false }
                )
                
                if (isCloud) {
                    cloudModels.add(modelInfo)
                }
            }
            
            println("[CLOUD] Found ${cloudModels.size} cloud models")
            return cloudModels
        } catch (e: Exception) {
            println("Failed to get cloud models using unified service: ${e.message}")
            // Fallback to legacy approach
            try {
                val cloudModelInfos = cloudRegistry?.scanModels() ?: emptyList<CloudModelInfo>()
                val legacyModels = cloudModelInfos.map { cloudModel ->
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
                return legacyModels
            } catch (ex: Exception) {
                return emptyList<ModelInfo>()
            }
        }
    }
    
    /**
     * Check if a model exists in either local or cloud using the new unified service
     */
    suspend fun hasModel(modelId: String): Boolean {
        return try {
            val result = unifiedService.getModel(modelId)
            result.fold(
                onSuccess = { model -> model != null },
                onFailure = { 
                    // Fallback to legacy approach
                    hasModelLegacy(modelId)
                }
            )
        } catch (e: Exception) {
            hasModelLegacy(modelId)
        }
    }
    
    /**
     * Legacy fallback for checking model existence
     */
    private suspend fun hasModelLegacy(modelId: String): Boolean {
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
     * Close all registries and the unified service
     */
    fun close() {
        try {
            unifiedService.close()
        } catch (e: Exception) {
            println("Error closing unified service: ${e.message}")
        }
        
        try {
            localRegistry.close()
        } catch (e: Exception) {
            println("Error closing local registry: ${e.message}")
        }
        
        try {
            cloudRegistry?.close()
        } catch (e: Exception) {
            println("Error closing cloud registry: ${e.message}")
        }
    }
}
