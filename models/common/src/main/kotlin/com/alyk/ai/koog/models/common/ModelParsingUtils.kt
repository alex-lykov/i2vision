package com.alyk.ai.koog.models.common

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * Common utilities for parsing model information from Ollama API responses
 */
object ModelParsingUtils {
    
    private val json = Json { ignoreUnknownKeys = true }
    
    /**
     * Parse models from Ollama API response
     */
    fun parseOllamaModelsResponse(
        response: String,
        repositoryType: RepositoryType,
        baseUrl: String
    ): List<OllamaModelMetadata> {
        return try {
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
                
                // Create model ID based on repository type
                val modelId = when (repositoryType) {
                    RepositoryType.LOCAL_OLLAMA -> name
                    else -> "${repositoryType.name.lowercase()}:$name"
                }
                
                // Infer context length
                val contextLength = inferContextLength(name)
                
                OllamaModelMetadata(
                    id = modelId,
                    name = modelName,
                    tag = tag,
                    size = size,
                    contextLength = contextLength,
                    digest = digest,
                    repositoryType = repositoryType,
                    baseUrl = baseUrl,
                    modifiedAt = modified
                )
            }
        } catch (e: Exception) {
            println("Error parsing Ollama models response: ${e.message}")
            emptyList()
        }
    }
    
    /**
     * Infer context length based on model name patterns
     * This is a heuristic approach since API doesn't always provide this info
     */
    fun inferContextLength(modelId: String): Int {
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
     * Format file size in human-readable format
     */
    fun formatFileSize(size: Long): String {
        return when {
            size > 1_000_000_000 -> "${size / 1_000_000_000} GB"
            size > 1_000_000 -> "${size / 1_000_000} MB"
            size > 1_000 -> "${size / 1_000} KB"
            else -> "$size B"
        }
    }
}

/**
 * Common Ollama model metadata for both local and cloud
 */
data class OllamaModelMetadata(
    override val id: String,
    override val name: String,
    override val tag: String,
    override val size: Long,
    override val contextLength: Int,
    override val digest: String,
    val repositoryType: RepositoryType,
    val baseUrl: String,
    val modifiedAt: String = "unknown",
    val layers: Int = 0,
    val error: String? = null
) : ModelMetadata {
    
    override val formattedSize: String
        get() = ModelParsingUtils.formatFileSize(size)
    
    override val displayName: String
        get() = when (repositoryType) {
            RepositoryType.LOCAL_OLLAMA -> "$name:$tag"
            else -> "${repositoryType.name.lowercase()}:$name:$tag"
        }
}
