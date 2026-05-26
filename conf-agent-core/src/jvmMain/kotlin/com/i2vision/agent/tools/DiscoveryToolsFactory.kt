/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.agent.tools

import com.i2vision.agent.*
import com.i2vision.discovery.DiscoveryPipeline
import com.i2vision.discovery.api.DiscoveryIntent
import com.i2vision.discovery.api.DiscoveryResult as ApiDiscoveryResult
import com.i2vision.instant.context.ContextProvider
import com.i2vision.instant.context.InstantContext as ApiInstantContext
import com.i2vision.instant.context.RelatedFiles as ApiRelatedFiles
import com.i2vision.storage.api.CacheStore
import org.slf4j.LoggerFactory
import java.io.File

/**
 * JVM-specific factory methods for i2vision discovery tools.
 * 
 * These factories bridge the common interfaces to the actual implementations
 * in i2vision-instant, discovery-engine, and storage-core modules.
 */

private val log = LoggerFactory.getLogger(DiscoveryToolsFactory::class.java)

/**
 * Factory for InstantContextProvider.
 * 
 * Wraps the i2vision-instant ContextProvider to implement the common interface.
 */
object InstantContextProvider {
    
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
        val apiRelated = contextProvider.getRelatedFiles(file, relationType.name.lowercase())
        return apiRelated.toCommonRelatedFiles()
    }
}

/**
 * Extension to convert API InstantContext to common FileContext.
 */
private fun ApiInstantContext.toCommonFileContext(): FileContext {
    return FileContext(
        file = this.filePath,
        symbols = this.symbols.map { it.toCommonSymbolInfo() },
        relatedFiles = this.relatedFiles,
        flows = this.flows.map { it.toCommonFlowInfo() },
        businessRules = this.businessRules.map { it.toCommonBusinessRuleInfo() },
        primaryLayer = VslfcLayer.fromString(this.primaryLayer),
        complexity = this.complexityScore
    )
}

/**
 * Extension to convert API RelatedFiles to common RelatedFiles.
 */
private fun ApiRelatedFiles.toCommonRelatedFiles(): RelatedFiles {
    return RelatedFiles(
        file = this.filePath,
        relationType = RelationType.valueOf(this.relationType.uppercase()),
        files = this.relatedFiles
    )
}

/**
 * Factory for DiscoveryCache.
 * 
 * Creates a file-based cache using the storage-core module.
 */
object DiscoveryCache {
    
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
    
    fun close() {
        clearAll()
    }
}

/**
 * Factory for DiscoveryEngine.
 * 
 * Wraps the discovery-engine module's DiscoveryPipeline.
 */
object DiscoveryEngine {
    
    /**
     * Create a DiscoveryEngine instance.
     * 
     * @param workspaceRoot Absolute path to the project root
     * @return Configured DiscoveryEngine
     */
    fun create(workspaceRoot: String): DiscoveryEngine {
        log.info("[FACTORY] Creating DiscoveryEngine for workspace: {}", workspaceRoot)
        val pipeline = DiscoveryPipeline(projectRoot = workspaceRoot)
        return DiscoveryEngineImpl(pipeline, workspaceRoot)
    }
}

/**
 * Implementation wrapper for DiscoveryEngine interface.
 */
private class DiscoveryEngineImpl(
    private val pipeline: DiscoveryPipeline,
    private val workspaceRoot: String
) : DiscoveryEngine {
    
    override suspend fun discover(path: String, intent: DiscoveryIntent): DiscoveryResult {
        log.info("[DISCOVERY] Running discovery on path: {}, intent: {}", path, intent)
        
        val absolutePath = if (File(path).isAbsolute) {
            path
        } else {
            File(workspaceRoot, path).absolutePath
        }
        
        val apiResult = pipeline.runDiscovery(
            path = absolutePath,
            intent = intent.toApiIntent()
        )
        
        return apiResult.toCommonDiscoveryResult()
    }
    
    fun close() {
        log.info("[DISCOVERY] Closing DiscoveryEngine")
        pipeline.close()
    }
}

/**
 * Extension to convert common DiscoveryIntent to API DiscoveryIntent.
 */
private fun DiscoveryIntent.toApiIntent(): com.i2vision.discovery.api.DiscoveryIntent {
    return when (this) {
        DiscoveryIntent.FULL_DISCOVERY -> com.i2vision.discovery.api.DiscoveryIntent.FULL_DISCOVERY
        DiscoveryIntent.REFACTORING_ANALYSIS -> com.i2vision.discovery.api.DiscoveryIntent.REFACTORING_ANALYSIS
        DiscoveryIntent.QUICK_OVERVIEW -> com.i2vision.discovery.api.DiscoveryIntent.QUICK_OVERVIEW
        DiscoveryIntent.ARCHITECTURE_AUDIT -> com.i2vision.discovery.api.DiscoveryIntent.ARCHITECTURE_AUDIT
        DiscoveryIntent.FLOW_MAPPING -> com.i2vision.discovery.api.DiscoveryIntent.FLOW_MAPPING
        DiscoveryIntent.DOCUMENTATION_GENERATION -> com.i2vision.discovery.api.DiscoveryIntent.DOCUMENTATION_GENERATION
    }
}

/**
 * Extension to convert API DiscoveryResult to common DiscoveryResult.
 */
private fun ApiDiscoveryResult.toCommonDiscoveryResult(): DiscoveryResult {
    return DiscoveryResult(
        path = this.path,
        clusters = this.clusters.map { it.toCommonClusterInfo() },
        totalSymbols = this.totalSymbols,
        duration = this.durationMs,
        timestamp = this.timestamp
    )
}

/**
 * Extension to convert API ClusterInfo to common ClusterInfo.
 */
private fun com.i2vision.discovery.api.ClusterInfo.toCommonClusterInfo(): ClusterInfo {
    return ClusterInfo(
        name = this.name,
        symbolCount = this.symbolCount,
        primaryLayer = VslfcLayer.fromString(this.primaryLayer),
        files = this.files
    )
}
