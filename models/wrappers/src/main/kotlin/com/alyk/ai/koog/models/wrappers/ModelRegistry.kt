package com.alyk.ai.koog.models.wrappers

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
 * Registry for managing local Ollama models
 * Cloud model support is handled through factory pattern
 */
class ModelRegistry(private val ollamaApiUrl: String = "http://localhost:11434") {
    
    private val models = mutableMapOf<String, ModelInfo>()
    private val httpClient = HttpClient.newHttpClient()
    
    /**
     * Fetch available models from local Ollama API
     */
    suspend fun scanModels(): List<ModelInfo> = withContext(Dispatchers.IO) {
        models.clear()
        
        try {
            val request = HttpRequest.newBuilder()
                .uri(URI.create("$ollamaApiUrl/api/tags"))
                .GET()
                .build()
                
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            
            if (response.statusCode() == 200) {
                parseModelsResponse(response.body())
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            println("Failed to fetch models from API: ${e.message}")
            emptyList()
        }
    }
    
    private fun parseModelsResponse(response: String): List<ModelInfo> {
        return try {
            val json = Json { ignoreUnknownKeys = true }
            val jsonObject = json.decodeFromString<JsonObject>(response)
            val modelsArray = jsonObject["models"]?.jsonArray ?: return emptyList()
            
            modelsArray.mapNotNull { modelElement ->
                val modelObj = modelElement as? JsonObject ?: return@mapNotNull null
                val name = modelObj["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                
                // Parse model info from API response
                val size = modelObj["size"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
                val digest = modelObj["digest"]?.jsonPrimitive?.content ?: "unknown"
                val modified = modelObj["modified_at"]?.jsonPrimitive?.content ?: "unknown"
                
                // Extract model name and tag
                val parts = name.split(":")
                val modelName = parts[0]
                val tag = parts.getOrNull(1) ?: "latest"
                val modelId = name
                
                // Infer context length from model name
                val contextLength = inferContextLength(modelId)
                
                val modelInfo = ModelInfo(
                    id = modelId,
                    name = modelName,
                    tag = tag,
                    size = size,
                    contextLength = contextLength,
                    digest = digest,
                    layers = 0, // API doesn't provide layer count
                    path = "api://$ollamaApiUrl"
                )
                
                models[modelId] = modelInfo
                modelInfo
            }
        } catch (e: Exception) {
            println("Error parsing models response: ${e.message}")
            emptyList()
        }
    }
    
    /**
     * Get running models from Ollama API
     */
    suspend fun getRunningModels(): List<ModelInfo> = withContext(Dispatchers.IO) {
        try {
            val request = HttpRequest.newBuilder()
                .uri(URI.create("$ollamaApiUrl/api/ps"))
                .GET()
                .build()
                
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            
            if (response.statusCode() == 200) {
                parseModelsResponse(response.body())
            } else {
                emptyList()
            }
        } catch (e: Exception) {
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
     * Get a specific model by ID
     */
    fun getModel(modelId: String): ModelInfo? = models[modelId]
    
    /**
     * Get all available models
     */
    fun getAllModels(): List<ModelInfo> = models.values.toList()
    
    /**
     * Create a ModelWrapper for the specified local model
     */
    fun createModelWrapper(
        modelId: String,
        performanceMonitor: PerformanceMonitor? = null
    ): ModelWrapper? {
        val model = models[modelId] ?: return null
        
        return LocalModelWrapper(
            modelName = model.id,
            maxContextLength = model.contextLength,
            performanceMonitor = performanceMonitor
        )
    }
    
    /**
     * Check if a model exists
     */
    fun hasModel(modelId: String): Boolean = models.containsKey(modelId)
    
    /**
     * Close HTTP client
     */
    fun close() {
        httpClient.close()
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
