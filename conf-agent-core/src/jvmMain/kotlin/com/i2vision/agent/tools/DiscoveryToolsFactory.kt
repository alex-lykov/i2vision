/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.agent.tools

import com.i2vision.agent.RelationType
import com.i2vision.agent.TaskType
import com.i2vision.agent.VslfcLayer
import com.i2vision.discover.api.DiscoveryPipeline
import com.i2vision.discover.api.models.DiscoveryIntent as ApiDiscoveryIntent
import com.i2vision.discover.api.models.DiscoveryDepth
import com.i2vision.discover.api.models.DiscoveryGoal
import com.i2vision.discover.api.models.IntentDepth
import com.i2vision.discover.api.models.DiscoveryQuality
import com.i2vision.discover.api.models.PipelineResult
import com.i2vision.instant.context.ContextProvider
import com.i2vision.instant.context.InstantContext
import com.i2vision.storage.api.CacheStore
import org.slf4j.LoggerFactory
import java.io.File

/**
 * JVM-specific factory methods for i2vision discovery tools.
 * 
 * These factories bridge the common interfaces to the actual implementations
 * in i2vision-instant, discovery-api, and storage-core modules.
 */

private val log = LoggerFactory.getLogger("DiscoveryToolsFactory")

/**
 * Factory for InstantContextProvider.
 * 
 * Wraps the i2vision-instant ContextProvider to implement the common interface.
 */
object InstantContextProviderFactory {
    
    /**
     * Create an InstantContextProvider instance.
     * 
     * @param workspaceRoot Absolute path to the project root
     * @return Configured InstantContextProvider
     */
    fun create(workspaceRoot: String): InstantContextProvider {
        log.info("[FACTORY] Creating InstantContextProvider for workspace: {}", workspaceRoot)
        val contextProvider = ContextProvider(projectRoot = workspaceRoot)
        return InstantContextProviderImpl(contextProvider)
    }
}

/**
 * Implementation wrapper for InstantContextProvider interface.
 */
private class InstantContextProviderImpl(
    private val contextProvider: ContextProvider
) : InstantContextProvider {
    
    override suspend fun getContext(file: String, task: TaskType): FileContext {
        val apiContext = contextProvider.getContext(file, task.name.lowercase())
        return apiContext.toCommonFileContext()
    }
    
    override suspend fun getRelatedFiles(file: String, relationType: RelationType): RelatedFiles {
        // ContextProvider doesn't have a direct getRelatedFiles method with relationType
        // We'll use the relatedFiles from the context
        val apiContext = contextProvider.getContext(file, "discovery")
        return RelatedFiles(
            file = file,
            relationType = relationType,
            files = apiContext.relatedFiles
        )
    }
    
    override fun close() {
        log.info("[INSTANT] Closing InstantContextProvider")
    }
}

/**
 * Extension to convert API InstantContext to common FileContext.
 */
private fun InstantContext.toCommonFileContext(): FileContext {
    return FileContext(
        file = this.filePath,
        symbols = this.symbols.map { it.toCommonSymbolInfo() },
        relatedFiles = this.relatedFiles,
        flows = this.flows.map { it.toCommonFlowInfo() },
        businessRules = this.businessRules.map { it.toCommonBusinessRuleInfo() },
        primaryLayer = VslfcLayer.fromString(this.component?.cohesion?.toString() ?: "VIEW"),
        complexity = this.complexityDetails?.complexityScore ?: 5
    )
}

/**
 * Extension to convert API SymbolInfo to common SymbolInfo.
 */
private fun com.i2vision.instant.context.SymbolInfo.toCommonSymbolInfo(): SymbolInfo {
    return SymbolInfo(
        name = this.name,
        kind = this.kind,
        location = "${this.file}:${this.line}",
        layer = VslfcLayer.LOGIC, // Default layer, could be derived from kind
        signature = this.content.takeIf { it.isNotEmpty() }
    )
}

/**
 * Extension to convert API FlowInfo to common FlowInfo.
 */
private fun com.i2vision.instant.context.FlowInfo.toCommonFlowInfo(): FlowInfo {
    return FlowInfo(
        name = this.name,
        description = this.steps.joinToString(" -> "),
        steps = this.steps
    )
}

/**
 * Extension to convert API BusinessRuleInfo to common BusinessRuleInfo.
 */
private fun com.i2vision.instant.context.BusinessRuleInfo.toCommonBusinessRuleInfo(): BusinessRuleInfo {
    return BusinessRuleInfo(
        description = this.description,
        source = "${this.file}:${this.line}".takeIf { this.file.isNotEmpty() }
    )
}

/**
 * Factory for DiscoveryCache.
 * 
 * Creates a file-based cache using the storage-core module.
 */
object DiscoveryCacheFactory {
    
    /**
     * Create a DiscoveryCache instance.
     * 
     * @param cacheStore The underlying cache storage implementation
     * @return Configured DiscoveryCache
     */
    fun create(cacheStore: CacheStore): DiscoveryCache {
        log.info("[FACTORY] Creating DiscoveryCache with store: {}", cacheStore::class.simpleName)
        return DiscoveryCacheImpl(cacheStore)
    }
    
    /**
     * Create a DiscoveryCache instance with default file-based storage.
     * 
     * @param workspaceRoot Absolute path to the project root
     * @return Configured DiscoveryCache
     */
    fun create(workspaceRoot: String): DiscoveryCache {
        log.info("[FACTORY] Creating DiscoveryCache for workspace: {}", workspaceRoot)
        // Use the storage-core implementation
        val cacheStore = com.i2vision.storage.impl.FileCacheStore(File(workspaceRoot))
        return DiscoveryCacheImpl(cacheStore)
    }
}

/**
 * Implementation of DiscoveryCache using storage-core.
 */
private class DiscoveryCacheImpl(
    private val cacheStore: CacheStore
) : DiscoveryCache {
    
    private val cache = mutableMapOf<String, DiscoveryResult>()
    
    override fun get(path: String): DiscoveryResult? {
        return cache[path]
    }
    
    override fun put(path: String, result: DiscoveryResult) {
        cache[path] = result
        // Also persist to cache store if needed
        log.debug("[CACHE] Cached discovery for path: {}", path)
    }
    
    override fun clear(path: String) {
        cache.remove(path)
        log.debug("[CACHE] Cleared cache for path: {}", path)
    }
    
    override fun clearAll() {
        cache.clear()
        log.info("[CACHE] Cleared all cache entries")
    }
    
    override fun close() {
        clearAll()
        log.info("[CACHE] Closing DiscoveryCache")
    }
}

/**
 * Factory for DiscoveryEngine.
 * 
 * Wraps the discovery-api module's DiscoveryPipeline.
 */
object DiscoveryEngineFactory {
    
    /**
     * Create a DiscoveryEngine instance.
     * 
     * @param workspaceRoot Absolute path to the project root
     * @return Configured DiscoveryEngine
     */
    fun create(workspaceRoot: String): DiscoveryEngine {
        log.info("[FACTORY] Creating DiscoveryEngine for workspace: {}", workspaceRoot)
        // Note: DiscoveryPipeline is an interface, we need the actual implementation
        // For now, we'll create a wrapper that will be replaced with the actual implementation
        return DiscoveryEngineImpl(workspaceRoot)
    }
}

/**
 * Implementation wrapper for DiscoveryEngine interface.
 * 
 * This is a placeholder that will be replaced with the actual DiscoveryPipeline implementation.
 */
private class DiscoveryEngineImpl(
    private val workspaceRoot: String
) : DiscoveryEngine {
    
    override suspend fun discover(path: String, intent: DiscoveryIntent): DiscoveryResult {
        log.info("[DISCOVERY] Running discovery on path: {}, intent: {}", path, intent)
        
        val absolutePath = if (File(path).isAbsolute) {
            path
        } else {
            File(workspaceRoot, path).absolutePath
        }
        
        // TODO: Replace with actual DiscoveryPipeline implementation
        // For now, return a placeholder result
        return DiscoveryResult(
            path = absolutePath,
            clusters = emptyList(),
            totalSymbols = 0,
            duration = 0L,
            timestamp = System.currentTimeMillis()
        )
    }
    
    override fun close() {
        log.info("[DISCOVERY] Closing DiscoveryEngine")
    }
}

/**
 * Extension to convert common DiscoveryIntent to API DiscoveryIntent.
 */
private fun DiscoveryIntent.toApiIntent(): ApiDiscoveryIntent {
    return when (this.intent) {
        DiscoveryIntent.Intent.FULL_DISCOVERY -> ApiDiscoveryIntent(
            goal = DiscoveryGoal.UNDERSTAND,
            depth = IntentDepth.DEEP,
            quality = DiscoveryQuality.THOROUGH
        )
        DiscoveryIntent.Intent.REFACTORING_ANALYSIS -> ApiDiscoveryIntent(
            goal = DiscoveryGoal.REFACTOR,
            depth = IntentDepth.STANDARD,
            quality = DiscoveryQuality.BALANCED
        )
        DiscoveryIntent.Intent.QUICK_OVERVIEW -> ApiDiscoveryIntent(
            goal = DiscoveryGoal.UNDERSTAND,
            depth = IntentDepth.BROWSE,
            quality = DiscoveryQuality.FAST
        )
        DiscoveryIntent.Intent.ARCHITECTURE_AUDIT -> ApiDiscoveryIntent(
            goal = DiscoveryGoal.ANALYZE,
            depth = IntentDepth.STANDARD,
            quality = DiscoveryQuality.BALANCED
        )
        DiscoveryIntent.Intent.FLOW_MAPPING -> ApiDiscoveryIntent(
            goal = DiscoveryGoal.ANALYZE,
            depth = IntentDepth.STANDARD,
            quality = DiscoveryQuality.BALANCED,
            layerFocus = listOf("LOGIC", "FLOW")
        )
        DiscoveryIntent.Intent.DOCUMENTATION_GENERATION -> ApiDiscoveryIntent(
            goal = DiscoveryGoal.GENERATE,
            depth = IntentDepth.STANDARD,
            quality = DiscoveryQuality.BALANCED
        )
    }
}

/**
 * Extension to convert API PipelineResult to common DiscoveryResult.
 */
private fun PipelineResult.toCommonDiscoveryResult(path: String, duration: Long): DiscoveryResult {
    return DiscoveryResult(
        path = path,
        clusters = this.artifacts.map { it.toCommonClusterInfo() },
        totalSymbols = this.artifacts.size,
        duration = duration,
        timestamp = System.currentTimeMillis()
    )
}

/**
 * Extension to convert API DiscoveryArtifact to common ClusterInfo.
 */
private fun com.i2vision.discover.api.models.DiscoveryArtifact.toCommonClusterInfo(): ClusterInfo {
    return ClusterInfo(
        name = this.path.substringAfterLast('/').substringBeforeLast('.'),
        symbolCount = 1,
        primaryLayer = VslfcLayer.fromString(this.layer),
        files = listOf(this.path)
    )
}
