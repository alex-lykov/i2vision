package com.i2vision.instant.context

import com.i2vision.index.IndexProvider
import com.i2vision.index.CustomIndex
import com.i2vision.index.SymbolInfo as IndexSymbolInfo
import com.i2vision.instant.artifact.GenericArtifactLoader
import com.i2vision.instant.artifact.ArtifactDiscoveryConfig
import com.i2vision.instant.strategy.StrategyLibrary
import com.i2vision.instant.analysis.FileAnalyzer
import com.i2vision.instant.cache.CacheManager
import com.i2vision.storage.api.CacheStore
import com.i2vision.storage.impl.FileCacheStore
import com.i2vision.storage.model.StorageConstants
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Instant Context Provider - Provides immediate context for LLMs.
 * 
 * This API allows LLMs to quickly get relevant context about a file or task
 * without running a full discovery pipeline. It's designed for low-latency
 * context retrieval suitable for real-time LLM interactions.
 * 
 * Migrated to use storage-core API for artifact storage.
 */
class ContextProvider(
    private val projectRoot: String,
    private val artifactConfig: ArtifactDiscoveryConfig = ArtifactDiscoveryConfig.defaultVslfc(),
    private val cacheStore: CacheStore = FileCacheStore(File(projectRoot))
) {
    
    private val log = LoggerFactory.getLogger(ContextProvider::class.java)
    private val indexProvider: IndexProvider by lazy { CustomIndex(projectRoot) }
    private val semanticCacheRoot = File(projectRoot, StorageConstants.SEMANTIC_CACHE_DIR)
    private val artifactLoader = GenericArtifactLoader(semanticCacheRoot.absolutePath, artifactConfig)
    private val fileAnalyzer = FileAnalyzer(projectRoot)
    private val cacheManager = CacheManager()
    
    /**
     * Get instant context for a specific file.
     * 
     * @param filePath Path to the file (relative to project root)
     * @param task The task being performed (e.g., "debug", "refactor", "add feature")
     * @return InstantContext with relevant information
     */
    suspend fun getContext(filePath: String, task: String): InstantContext {
        log.debug("[CONTEXT] Getting context for file: {}, task: {}", filePath, task)
        
        val absolutePath = File(projectRoot, filePath)
        if (!absolutePath.exists()) {
            return InstantContext.error("File not found: $filePath")
        }
        
        // Get symbols in the file
        val indexSymbols = indexProvider.symbolsInFile(absolutePath)
        val symbols = indexSymbols.map { it.toSymbolInfo() }
        
        // Get related files based on imports and references
        val relatedFiles = findRelatedFiles(absolutePath, indexSymbols)
        
        // Load artifacts from semantic cache
        val module = detectModuleFromPath(filePath)
        val artifacts = if (module != null) {
            artifactLoader.loadFromDirectory(module)
        } else {
            GenericArtifactLoader.LoadedArtifacts()
        }
        
        // Get task-specific context
        val taskContext = getTaskContext(task, symbols)
        
        // Calculate confidence and get strategy suggestions
        val confidence = calculateConfidence(filePath, artifacts)
        val fileType = detectFileType(filePath)
        val strategySuggestions = StrategyLibrary.getSuggestedStrategies(confidence, fileType)
        
        // Get complexity details
        val complexityDetails = fileAnalyzer.getComplexityDetails(filePath)
        
        return InstantContext(
            filePath = filePath,
            symbols = symbols,
            relatedFiles = relatedFiles,
            taskContext = taskContext,
            artifacts = artifacts.layerNames(),
            strategySuggestions = strategySuggestions,
            complexityDetails = complexityDetails,
            success = true
        )
    }
    
    /**
     * Get instant context for multiple files.
     * 
     * @param filePaths List of file paths (relative to project root)
     * @param task The task being performed
     * @return InstantContext with aggregated information
     */
    suspend fun getContextForFiles(filePaths: List<String>, task: String): InstantContext {
        log.debug("[CONTEXT] Getting context for {} files, task: {}", filePaths.size, task)
        
        val allSymbols = mutableListOf<SymbolInfo>()
        val allRelatedFiles = mutableSetOf<String>()
        
        filePaths.forEach { filePath ->
            val absolutePath = File(projectRoot, filePath)
            if (absolutePath.exists()) {
                val indexSymbols = indexProvider.symbolsInFile(absolutePath)
                val symbols = indexSymbols.map { it.toSymbolInfo() }
                allSymbols.addAll(symbols)
                
                val related = findRelatedFiles(absolutePath, indexSymbols)
                allRelatedFiles.addAll(related)
            }
        }
        
        val taskContext = getTaskContext(task, allSymbols)
        
        // Load artifacts from first file's module
        val module = if (filePaths.isNotEmpty()) detectModuleFromPath(filePaths[0]) else null
        val loadedArtifacts = if (module != null) {
            artifactLoader.loadFromDirectory(module)
        } else {
            GenericArtifactLoader.LoadedArtifacts()
        }
        
        // Calculate confidence and get strategy suggestions
        val confidence = if (filePaths.isNotEmpty()) calculateConfidence(filePaths[0], loadedArtifacts) else 0.0
        val fileType = if (filePaths.isNotEmpty()) detectFileType(filePaths[0]) else "unknown"
        val strategySuggestions = StrategyLibrary.getSuggestedStrategies(confidence, fileType)
        
        return InstantContext(
            filePath = filePaths.joinToString(", "),
            symbols = allSymbols,
            relatedFiles = allRelatedFiles.toList(),
            taskContext = taskContext,
            artifacts = loadedArtifacts.layerNames(),
            strategySuggestions = strategySuggestions,
            success = true
        )
    }
    
    /**
     * Find related files based on imports and symbol references.
     */
    private fun findRelatedFiles(file: File, symbols: List<IndexSymbolInfo>): List<String> {
        val related = mutableSetOf<String>()
        
        // Use call hierarchy to find related files
        symbols.forEach { symbol ->
            try {
                val hierarchy = indexProvider.getCallHierarchy(symbol)
                hierarchy.callees.forEach { callee ->
                    val calleeFile = callee.file
                    if (calleeFile.exists() && calleeFile.absolutePath != file.absolutePath) {
                        related.add(calleeFile.relativeTo(File(projectRoot)).path)
                    }
                }
                hierarchy.callers.forEach { caller ->
                    val callerFile = caller.file
                    if (callerFile.exists() && callerFile.absolutePath != file.absolutePath) {
                        related.add(callerFile.relativeTo(File(projectRoot)).path)
                    }
                }
            } catch (e: Exception) {
                log.debug("[CONTEXT] Failed to get call hierarchy for ${symbol.name}: ${e.message}")
            }
        }
        
        return related.toList()
    }
    
    /**
     * Detect module from file path
     */
    private fun detectModuleFromPath(filePath: String): String? {
        val path = filePath.replace("\\", "/")
        val parts = path.split("/")
        
        // Try to find a module by checking if the path contains known module directories
        val possibleModules = listOf("core", "models", "server", "launcher", "configurable-agent", "switching", "context", "pipeline", "learning", "ui", "security", "database")
        
        for (module in possibleModules) {
            if (parts.contains(module)) {
                val moduleIndex = parts.indexOf(module)
                if (moduleIndex + 1 < parts.size) {
                    // Return the module path (e.g., "core/orchestrator")
                    return parts.subList(0, moduleIndex + 2).joinToString("/")
                }
                return module
            }
        }
        
        // Default: return first directory if it's a module
        if (parts.size >= 2) {
            return "${parts[0]}/${parts[1]}"
        }
        
        return null
    }
    
    /**
     * Calculate confidence score based on artifacts and file presence
     */
    private fun calculateConfidence(filePath: String, artifacts: GenericArtifactLoader.LoadedArtifacts): Double {
        val module = detectModuleFromPath(filePath)
        if (module == null) return 0.2
        
        // Base confidence from artifact presence
        val layerConfidence = if (artifacts.layers.isEmpty()) 0.1 else 0.5
        
        // Boost for meaningful artifacts
        val hasMeaningfulArtifacts = artifacts.layers.values.any { items ->
            items.any { artifact ->
                artifact.values.any { value ->
                    when (value) {
                        is List<*> -> value.isNotEmpty()
                        is Map<*, *> -> value.isNotEmpty()
                        else -> value != null
                    }
                }
            }
        }
        val artifactBoost = if (hasMeaningfulArtifacts) 0.2 else 0.0
        
        // File-specific boost
        val fileBoost = when {
            filePath.contains("test") -> 0.05
            filePath.endsWith(".kt") -> 0.1
            filePath.endsWith(".md") -> 0.05
            else -> 0.0
        }
        
        return (layerConfidence + artifactBoost + fileBoost).coerceIn(0.0, 1.0)
    }
    
    /**
     * Detect file type from extension
     */
    private fun detectFileType(filePath: String): String {
        val extension = File(filePath).extension
        return when (extension) {
            "kt", "kts" -> "kotlin"
            "yaml", "yml" -> "yaml"
            "gradle" -> "gradle"
            "md" -> "markdown"
            "json" -> "json"
            else -> extension.ifEmpty { "unknown" }
        }
    }
    
    /**
     * Invalidate cache for specific file or pattern
     */
    fun invalidateCache(pattern: String) {
        cacheManager.invalidate(pattern)
    }
    
    /**
     * Clear expired cache entries
     */
    fun cleanCache() {
        cacheManager.cleanExpired()
    }
    
    /**
     * Get cache statistics
     */
    fun getCacheStats(): CacheManager.CacheStats {
        return cacheManager.getStats()
    }
    
    /**
     * Get task-specific context based on the task type.
     */
    private fun getTaskContext(task: String, symbols: List<SymbolInfo>): TaskContext {
        return when (task.lowercase()) {
            "debug" -> TaskContext.debug(symbols)
            "refactor" -> TaskContext.refactor(symbols)
            "add feature" -> TaskContext.addFeature(symbols)
            "fix bug" -> TaskContext.fixBug(symbols)
            "optimize" -> TaskContext.optimize(symbols)
            else -> TaskContext.general(symbols)
        }
    }
}

/**
 * Convert IndexProvider SymbolInfo to instant SymbolInfo.
 */
private fun IndexSymbolInfo.toSymbolInfo(): SymbolInfo {
    return SymbolInfo(
        name = name,
        qualifiedName = qualifiedName,
        kind = kind,
        file = file.path,
        line = line
    )
}

/**
 * Instant context result.
 */
data class InstantContext(
    val filePath: String,
    val symbols: List<SymbolInfo>,
    val relatedFiles: List<String>,
    val taskContext: TaskContext,
    val artifacts: List<String> = emptyList(),
    val strategySuggestions: List<com.i2vision.instant.strategy.StrategyLibrary.StrategySuggestion> = emptyList(),
    val complexityDetails: com.i2vision.instant.analysis.FileAnalyzer.ComplexityDetails? = null,
    val success: Boolean,
    val error: String? = null
) {
    companion object {
        fun error(message: String) = InstantContext(
            filePath = "",
            symbols = emptyList(),
            relatedFiles = emptyList(),
            taskContext = TaskContext.general(emptyList()),
            artifacts = emptyList(),
            strategySuggestions = emptyList(),
            complexityDetails = null,
            success = false,
            error = message
        )
    }
}

/**
 * Symbol information.
 */
data class SymbolInfo(
    val name: String,
    val qualifiedName: String,
    val kind: String,
    val file: String,
    val line: Int
)

/**
 * Task-specific context.
 */
data class TaskContext(
    val task: String,
    val relevantSymbols: List<SymbolInfo>,
    val suggestions: List<String>,
    val patterns: List<String>
) {
    companion object {
        fun debug(symbols: List<SymbolInfo>) = TaskContext(
            task = "debug",
            relevantSymbols = symbols.filter { it.kind in listOf("fun", "class") },
            suggestions = listOf("Check function signatures", "Review error handling", "Inspect variable states"),
            patterns = listOf("assert", "if error", "try-catch", "log.error")
        )
        
        fun refactor(symbols: List<SymbolInfo>) = TaskContext(
            task = "refactor",
            relevantSymbols = symbols.filter { it.kind in listOf("fun", "class", "val") },
            suggestions = listOf("Extract common logic", "Simplify complex functions", "Improve naming"),
            patterns = listOf("TODO", "FIXME", "HACK", "long function")
        )
        
        fun addFeature(symbols: List<SymbolInfo>) = TaskContext(
            task = "add feature",
            relevantSymbols = symbols.filter { it.kind in listOf("class", "interface", "fun") },
            suggestions = listOf("Identify extension points", "Review existing patterns", "Check for similar features"),
            patterns = listOf("interface", "abstract", "override", "implement")
        )
        
        fun fixBug(symbols: List<SymbolInfo>) = TaskContext(
            task = "fix bug",
            relevantSymbols = symbols.filter { it.kind in listOf("fun", "val") },
            suggestions = listOf("Review error conditions", "Check edge cases", "Validate inputs"),
            patterns = listOf("error", "exception", "null", "undefined")
        )
        
        fun optimize(symbols: List<SymbolInfo>) = TaskContext(
            task = "optimize",
            relevantSymbols = symbols.filter { it.kind in listOf("fun") },
            suggestions = listOf("Identify bottlenecks", "Reduce complexity", "Optimize data structures"),
            patterns = listOf("for loop", "while", "nested", "recursive")
        )
        
        fun general(symbols: List<SymbolInfo>) = TaskContext(
            task = "general",
            relevantSymbols = symbols,
            suggestions = listOf("Review code structure", "Check for best practices", "Ensure consistency"),
            patterns = emptyList()
        )
    }
}
