/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.instant.context

import com.i2vision.index.CustomIndex
import com.i2vision.index.IndexProvider
import com.i2vision.instant.analysis.FileAnalyzer
import com.i2vision.instant.artifact.ArtifactDiscoveryConfig
import com.i2vision.instant.artifact.GenericArtifactLoader
import com.i2vision.instant.cache.CacheManager
import com.i2vision.instant.strategy.StrategyLibrary
import com.i2vision.storage.I2VisionPaths
import com.i2vision.storage.api.CacheStore
import com.i2vision.storage.impl.FileCacheStore
import org.slf4j.LoggerFactory
import java.io.File
import com.i2vision.index.SymbolInfo as IndexSymbolInfo

/**
 * Instant Context Provider - Provides immediate context for LLMs.
 * 
 * This API allows LLMs to quickly get relevant context about a file or task
 * without running a full discovery pipeline. It's designed for low-latency
 * context retrieval suitable for real-time LLM interactions.
 */
class ContextProvider(
    private val projectRoot: String,
    private val artifactConfig: ArtifactDiscoveryConfig = ArtifactDiscoveryConfig.defaultVslfc(),
    private val cacheStore: CacheStore = FileCacheStore(File(projectRoot))
) {

    private val log = LoggerFactory.getLogger(ContextProvider::class.java)
    private val indexProvider: IndexProvider by lazy { CustomIndex(projectRoot) }
    
    // FIX: Use I2VisionPaths to get the correct cache location (user home, not project root)
    private val semanticCacheRoot = I2VisionPaths.getProjectCacheDir(projectRoot)
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
        val possibleModules = listOf(
            "core",
            "models",
            "server",
            "launcher",
            "configurable-agent",
            "switching",
            "context",
            "pipeline",
            "learning",
            "ui",
            "security",
            "database"
        )

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
                        else -> true
                    }
                }
            }
        }
        val artifactBoost = if (hasMeaningfulArtifacts) 0.3 else 0.0

        return (layerConfidence + artifactBoost).coerceIn(0.0, 1.0)
    }

    /**
     * Detect file type from path
     */
    private fun detectFileType(filePath: String): String {
        return when {
            filePath.endsWith(".kt") -> "kotlin"
            filePath.endsWith(".java") -> "java"
            filePath.endsWith(".py") -> "python"
            filePath.endsWith(".js") -> "javascript"
            filePath.endsWith(".ts") -> "typescript"
            filePath.endsWith(".yaml") || filePath.endsWith(".yml") -> "yaml"
            filePath.endsWith(".json") -> "json"
            filePath.endsWith(".xml") -> "xml"
            filePath.endsWith(".md") -> "markdown"
            else -> "unknown"
        }
    }

    /**
     * Get enhanced context with discovery cache data.
     * 
     * This requires that discovery has been run previously to populate the semantic cache.
     * 
     * @param filePath Path to the file (relative to project root)
     * @param task The task being performed
     * @return InstantContext with enhanced information from discovery cache
     */
    suspend fun getEnhancedContext(filePath: String, task: String): InstantContext {
        log.info("[CONTEXT] Getting enhanced context for file: {}, task: {}", filePath, task)

        // First get basic context
        val basic = getContext(filePath, task)

        // Extract module path for cache lookup
        val modulePath = extractModulePath(filePath)
        log.debug("[ENHANCED] Module path: {} for file: {}", modulePath, filePath)

        // Load enhanced data from discovery cache
        val flows = loadFlows(modulePath, filePath)
        val rules = loadBusinessRules(modulePath, filePath)
        val component = loadComponent(modulePath, filePath)
        val relatedComponents = loadRelatedComponents(modulePath, component?.name)

        return basic.copy(
            enhanced = true,
            flows = flows,
            businessRules = rules,
            component = component,
            relatedComponents = relatedComponents
        )
    }

    /**
     * Extract module path from file path.
     */
    private fun extractModulePath(filePath: String): String {
        val normalizedPath = filePath.replace("\\", "/")
        return when {
            normalizedPath.startsWith("i2vision-") -> normalizedPath.substringBefore("/src/")
            normalizedPath.startsWith("vslfc-core") -> "vslfc-core"
            normalizedPath.startsWith("llm-client") -> "llm-client"
            normalizedPath.startsWith("storage-core") -> "storage-core"
            normalizedPath.startsWith("discovery-api") -> "discovery-api"
            normalizedPath.startsWith("discovery-engine") -> "discovery-engine"
            normalizedPath.startsWith("intent-parser") -> "intent-parser"
            normalizedPath.startsWith("architecture-types") -> "architecture-types"
            normalizedPath.startsWith("conf-agent-core") -> "conf-agent-core"
            normalizedPath.startsWith("contracts") -> "contracts"
            normalizedPath.startsWith("index-provider") -> "index-provider"
            normalizedPath.startsWith("link-service") -> "link-service"
            else -> normalizedPath.substringBefore("/src/").ifEmpty { "root" }
        }
    }

    /**
     * Load flows from discovery cache.
     */
    @Suppress("UNCHECKED_CAST")
    private fun loadFlows(modulePath: String, filePath: String): List<FlowInfo> {
        val flowsFile = File(semanticCacheRoot, "$modulePath/flow/sequences.yaml")
        if (!flowsFile.exists()) return emptyList()

        try {
            val yaml = org.yaml.snakeyaml.Yaml()
            val data = yaml.load<Map<String, Any>>(flowsFile.readText())
            val flows = data["flows"] as? List<Map<String, Any>> ?: return emptyList()

            return flows.filter { flow ->
                val steps = flow["steps"] as? List<Map<String, Any>> ?: emptyList()
                steps.any { step ->
                    val stepFile = step["file"] as? String ?: ""
                    stepFile.contains(filePath) || filePath.contains(stepFile)
                }
            }.map { flow ->
                FlowInfo(
                    name = flow["entry"] as? String ?: "unnamed",
                    steps = (flow["steps"] as? List<Map<String, Any>>)?.mapNotNull {
                        it["call"] as? String
                    } ?: emptyList(),
                    participants = (flow["steps"] as? List<Map<String, Any>>)?.mapNotNull {
                        it["file"] as? String
                    }?.distinct() ?: emptyList()
                )
            }
        } catch (e: Exception) {
            log.warn("[ENHANCED] Failed to load flows: ${e.message}")
            return emptyList()
        }
    }

    /**
     * Load business rules from discovery cache.
     */
    @Suppress("UNCHECKED_CAST")
    private fun loadBusinessRules(modulePath: String, filePath: String): List<BusinessRuleInfo> {
        val rulesFile =
            File(semanticCacheRoot, "$modulePath/logic/business-rules.yaml")
        if (!rulesFile.exists()) return emptyList()

        try {
            val yaml = org.yaml.snakeyaml.Yaml()
            val data = yaml.load<Map<String, Any>>(rulesFile.readText())
            val rules = data["business_rules"] as? List<Map<String, Any>> ?: return emptyList()

            return rules.filter { rule ->
                val ruleFile = rule["file"] as? String ?: ""
                ruleFile == filePath || filePath.endsWith(ruleFile)
            }.map { rule ->
                BusinessRuleInfo(
                    description = rule["snippet"] as? String ?: rule["description"] as? String ?: "",
                    file = rule["file"] as? String ?: "",
                    line = (rule["line"] as? Number)?.toInt() ?: 0
                )
            }
        } catch (e: Exception) {
            log.warn("[ENHANCED] Failed to load business rules: ${e.message}")
            return emptyList()
        }
    }

    /**
     * Load component information from discovery cache.
     */
    @Suppress("UNCHECKED_CAST")
    private fun loadComponent(modulePath: String, filePath: String): ComponentInfo? {
        val componentsFile =
            File(semanticCacheRoot, "$modulePath/structure/components.yaml")
        if (!componentsFile.exists()) return null

        try {
            val yaml = org.yaml.snakeyaml.Yaml()
            val data = yaml.load<Map<String, Any>>(componentsFile.readText())
            val components = data["components"] as? List<Map<String, Any>> ?: return null

            return components.find { component ->
                val files = component["files"] as? List<String> ?: emptyList()
                files.any { it.contains(filePath) || filePath.endsWith(it) }
            }?.let { component ->
                ComponentInfo(
                    name = component["name"] as? String ?: "unknown",
                    cohesion = (component["cohesion"] as? Number)?.toDouble() ?: 0.0,
                    files = component["files"] as? List<String> ?: emptyList()
                )
            }
        } catch (e: Exception) {
            log.warn("[ENHANCED] Failed to load component: ${e.message}")
            return null
        }
    }

    /**
     * Load related components from discovery cache.
     */
    @Suppress("UNCHECKED_CAST")
    private fun loadRelatedComponents(modulePath: String, componentName: String?): List<ComponentDependency> {
        if (componentName == null) return emptyList()

        val depsFile =
            File(semanticCacheRoot, "$modulePath/structure/dependencies.yaml")
        if (!depsFile.exists()) return emptyList()

        try {
            val yaml = org.yaml.snakeyaml.Yaml()
            val data = yaml.load<Map<String, Any>>(depsFile.readText())
            val deps = data["dependencies"] as? List<Map<String, Any>> ?: return emptyList()

            return deps.filter { dep ->
                val from = dep["from"] as? String ?: ""
                val to = dep["to"] as? String ?: ""
                from == componentName || to == componentName
            }.map { dep ->
                ComponentDependency(
                    from = dep["from"] as? String ?: "",
                    to = dep["to"] as? String ?: "",
                    type = dep["type"] as? String ?: "depends_on"
                )
            }
        } catch (e: Exception) {
            log.warn("[ENHANCED] Failed to load related components: ${e.message}")
            return emptyList()
        }
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

    /**
     * Check if discovery cache exists for a module.
     */
    fun hasDiscoveryCache(modulePath: String): Boolean {
        val cacheDir = File(semanticCacheRoot, modulePath)
        return cacheDir.exists() && cacheDir.isDirectory
    }

    /**
     * Get cache statistics.
     */
    fun getCacheStats(): CacheStats {
        val cacheDir = File(semanticCacheRoot.absolutePath)
        val totalEntries = countYamlFiles(cacheDir)
        val expiredEntries = 0 // TODO: Implement expiration logic
        val validEntries = totalEntries - expiredEntries

        return CacheStats(totalEntries, expiredEntries, validEntries)
    }

    /**
     * Count YAML files in directory recursively.
     */
    private fun countYamlFiles(dir: File): Int {
        if (!dir.exists()) return 0
        return dir.walk().filter { it.isFile && (it.name.endsWith(".yaml") || it.name.endsWith(".yml")) }.count()
    }

    /**
     * Clean cache by removing all files.
     */
    fun cleanCache() {
        val cacheDir = File(semanticCacheRoot.absolutePath)
        if (cacheDir.exists()) {
            cacheDir.deleteRecursively()
            log.info("[CONTEXT] Cache cleaned: {}", cacheDir.absolutePath)
        }
    }

    /**
     * Invalidate cache entries matching a pattern.
     */
    fun invalidateCache(pattern: String) {
        val cacheDir = File(semanticCacheRoot.absolutePath)
        if (!cacheDir.exists()) return

        val regex = pattern.toRegex()
        var count = 0
        cacheDir.walk().filter { it.isFile }.forEach { file ->
            if (file.path.contains(regex)) {
                file.delete()
                count++
            }
        }
        log.info("[CONTEXT] Invalidated {} cache entries matching: {}", count, pattern)
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
        line = line,
        content = content
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
    val strategySuggestions: List<StrategyLibrary.StrategySuggestion> = emptyList(),
    val complexityDetails: FileAnalyzer.ComplexityDetails? = null,
    val success: Boolean,
    val error: String? = null,
    // Enhanced context fields
    val enhanced: Boolean = false,
    val flows: List<FlowInfo> = emptyList(),
    val businessRules: List<BusinessRuleInfo> = emptyList(),
    val component: ComponentInfo? = null,
    val relatedComponents: List<ComponentDependency> = emptyList()
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
 * Flow information from discovery cache.
 */
data class FlowInfo(
    val name: String,
    val steps: List<String>,
    val participants: List<String>
)

/**
 * Business rule information from discovery cache.
 */
data class BusinessRuleInfo(
    val description: String,
    val file: String,
    val line: Int
)

/**
 * Component information from discovery cache.
 */
data class ComponentInfo(
    val name: String,
    val cohesion: Double,
    val files: List<String>
)

/**
 * Component dependency information from discovery cache.
 */
data class ComponentDependency(
    val from: String,
    val to: String,
    val type: String
)

/**
 * Symbol information.
 */
data class SymbolInfo(
    val name: String,
    val qualifiedName: String,
    val kind: String,
    val file: String,
    val line: Int,
    val content: String = ""
)

/**
 * Task-specific context.
 */
data class TaskContext(
    val task: String,
    val suggestions: List<String>
) {
    companion object {
        fun debug(symbols: List<SymbolInfo>) = TaskContext(
            "debug",
            listOf(
                "Check symbol definitions and usages",
                "Review call hierarchy",
                "Examine related files for context"
            )
        )

        fun refactor(symbols: List<SymbolInfo>) = TaskContext(
            "refactor",
            listOf(
                "Identify code smells and complexity hotspots",
                "Check for duplicate code patterns",
                "Review component boundaries"
            )
        )

        fun addFeature(symbols: List<SymbolInfo>) = TaskContext(
            "add feature",
            listOf(
                "Find similar existing features for patterns",
                "Check component cohesion and dependencies",
                "Review business rules that may apply"
            )
        )

        fun fixBug(symbols: List<SymbolInfo>) = TaskContext(
            "fix bug",
            listOf(
                "Trace the bug through call hierarchy",
                "Check business rules for expected behavior",
                "Review related components for side effects"
            )
        )

        fun optimize(symbols: List<SymbolInfo>) = TaskContext(
            "optimize",
            listOf(
                "Identify performance bottlenecks",
                "Check for unnecessary dependencies",
                "Review flow efficiency"
            )
        )

        fun general(symbols: List<SymbolInfo>) = TaskContext(
            "general",
            listOf(
                "Review symbols and their relationships",
                "Check component structure",
                "Examine business rules and flows"
            )
        )
    }
}

/**
 * Cache statistics.
 */
data class CacheStats(
    val totalEntries: Int,
    val expiredEntries: Int,
    val validEntries: Int
)
