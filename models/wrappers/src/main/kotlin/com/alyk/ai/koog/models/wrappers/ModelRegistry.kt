package com.alyk.ai.koog.models.wrappers

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Registry for managing locally available Ollama models.
 * Scans the Ollama models directory and provides model metadata.
 */
class ModelRegistry(private val ollamaModelsPath: String = "D:\\dev\\AI\\ollama\\models") {
    
    private val models = mutableMapOf<String, ModelInfo>()
    
    /**
     * Scan the Ollama models directory and discover available models
     */
    fun scanModels(): List<ModelInfo> {
        models.clear()
        
        val manifestsPath = Paths.get(ollamaModelsPath, "manifests", "registry.ollama.ai", "library")
        if (!Files.exists(manifestsPath)) {
            return emptyList()
        }
        
        Files.list(manifestsPath).forEach { modelDir ->
            if (Files.isDirectory(modelDir)) {
                scanModelVersions(modelDir)
            }
        }
        
        return models.values.toList()
    }
    
    private fun scanModelVersions(modelDir: Path) {
        val modelName = modelDir.fileName.toString()
        
        Files.list(modelDir).forEach { versionFile ->
            if (Files.isRegularFile(versionFile)) {
                val tag = versionFile.fileName.toString()
                val modelId = "$modelName:$tag"
                
                val info = parseModelManifest(versionFile, modelId)
                models[modelId] = info
            }
        }
    }
    
    private fun parseModelManifest(manifestPath: Path, modelId: String): ModelInfo {
        return try {
            val content = Files.readString(manifestPath)
            val json = Json.parseToJsonElement(content) as? JsonObject
            
            val config = json?.get("config")?.jsonObject
            val digest = config?.get("digest")?.jsonPrimitive?.content
            val size = config?.get("size")?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
            
            // Parse layers to find model parameters info
            val layers = json?.get("layers")?.let { 
                (it as? kotlinx.serialization.json.JsonArray)?.size ?: 0 
            } ?: 0
            
            // Infer context length from model name or use default
            val contextLength = inferContextLength(modelId)
            
            ModelInfo(
                id = modelId,
                name = modelId.split(":")[0],
                tag = modelId.split(":").getOrElse(1) { "latest" },
                size = size,
                contextLength = contextLength,
                digest = digest ?: "unknown",
                layers = layers,
                path = manifestPath.toString()
            )
        } catch (e: Exception) {
            ModelInfo(
                id = modelId,
                name = modelId.split(":")[0],
                tag = modelId.split(":").getOrElse(1) { "latest" },
                size = 0,
                contextLength = 4096,
                digest = "error",
                layers = 0,
                path = manifestPath.toString(),
                error = e.message
            )
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
     * Create a ModelWrapper for the specified model
     */
    fun createModelWrapper(
        modelId: String,
        performanceMonitor: com.alyk.ai.koog.switching.monitor.PerformanceMonitor? = null
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
