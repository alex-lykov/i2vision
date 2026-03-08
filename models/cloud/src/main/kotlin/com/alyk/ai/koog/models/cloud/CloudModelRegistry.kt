package com.alyk.ai.koog.models.cloud

import com.alyk.ai.koog.models.wrappers.ModelWrapper
import com.alyk.ai.koog.switching.monitor.PerformanceMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * Registry for managing cloud-hosted Ollama models
 * Supports multiple cloud providers and authentication
 */
class CloudModelRegistry(
    private val cloudConfigs: List<OllamaCloudConfig>
) {
    private val models = mutableMapOf<String, CloudModelInfo>()
    private val httpClient = HttpClient.newHttpClient()
    
    /**
     * Scan all configured cloud endpoints for available models
     */
    suspend fun scanModels(): List<CloudModelInfo> = withContext(Dispatchers.IO) {
        models.clear()
        val allModels = mutableListOf<CloudModelInfo>()
        
        cloudConfigs.forEach { config ->
            try {
                val modelsFromEndpoint = fetchModelsFromEndpoint(config)
                allModels.addAll(modelsFromEndpoint)
            } catch (e: Exception) {
                println("Failed to fetch models from ${config.apiUrl}: ${e.message}")
            }
        }
        
        allModels.forEach { model ->
            models[model.id] = model
        }
        
        allModels
    }
    
    private suspend fun fetchModelsFromEndpoint(config: OllamaCloudConfig): List<CloudModelInfo> {
        val requestBuilder = HttpRequest.newBuilder()
            .uri(URI.create("${config.apiUrl}/api/tags"))
            .GET()
            
        // Add authentication header if API key is provided
        config.apiKey?.let { apiKey ->
            requestBuilder.header("Authorization", "Bearer $apiKey")
        }
        
        val request = requestBuilder.build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        
        if (response.statusCode() == 200) {
            return parseCloudModelsResponse(response.body(), config)
        } else {
            throw Exception("HTTP ${response.statusCode()}: ${response.body()}")
        }
    }
    
    private fun parseCloudModelsResponse(response: String, config: OllamaCloudConfig): List<CloudModelInfo> {
        return try {
            val json = Json { ignoreUnknownKeys = true }
            val jsonObject = json.decodeFromString<JsonObject>(response)
            val modelsArray = jsonObject["models"]?.jsonArray ?: return emptyList()
            
            modelsArray.mapNotNull { modelElement ->
                val modelObj = modelElement as? JsonObject ?: return@mapNotNull null
                val name = modelObj["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                
                // Parse model info
                val size = modelObj["size"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
                val digest = modelObj["digest"]?.jsonPrimitive?.content ?: "unknown"
                val modified = modelObj["modified_at"]?.jsonPrimitive?.content ?: "unknown"
                
                // Extract model name and tag
                val parts = name.split(":")
                val modelName = parts[0]
                val tag = parts.getOrNull(1) ?: "latest"
                val modelId = "${config.provider.name.lowercase()}:$name"
                
                // Infer context length
                val contextLength = inferContextLength(name)
                
                CloudModelInfo(
                    id = modelId,
                    name = modelName,
                    tag = tag,
                    size = size,
                    contextLength = contextLength,
                    digest = digest,
                    layers = 0,
                    path = config.apiUrl,
                    cloudConfig = config,
                    provider = config.provider,
                    region = config.region,
                    isAvailable = true
                )
            }
        } catch (e: Exception) {
            println("Error parsing cloud models response: ${e.message}")
            emptyList()
        }
    }
    
    private fun inferContextLength(modelId: String): Int {
        val lower = modelId.lowercase()
        return when {
            lower.contains("70b") -> 8192
            lower.contains("32b") -> 8192
            lower.contains("14b") -> 4096
            lower.contains("8b") -> 4096
            lower.contains("7b") -> 4096
            lower.contains("4b") -> 4096
            lower.contains("3b") -> 2048
            lower.contains("gpt-oss") && lower.contains("20b") -> 16384
            lower.contains("mistral") && lower.contains("7b") -> 32768
            lower.contains("llama3") && lower.contains("70b") -> 8192
            lower.contains("llama3") && lower.contains("8b") -> 8192
            lower.contains("codellama") && lower.contains("70b") -> 16384
            lower.contains("codellama") && lower.contains("34b") -> 16384
            lower.contains("codellama") && lower.contains("13b") -> 16384
            lower.contains("codellama") && lower.contains("7b") -> 16384
            else -> 4096
        }
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
     * Close HTTP client
     */
    fun close() {
        httpClient.close()
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
