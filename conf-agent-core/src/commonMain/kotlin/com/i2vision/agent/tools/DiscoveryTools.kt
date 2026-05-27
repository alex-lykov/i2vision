/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.agent.tools

import com.i2vision.agent.*
import com.i2vision.agent.VslfcLayer
import com.i2vision.agent.koog.ContractInfo

/**
 * i2vision discovery and context tools.
 * 
 * These tools leverage the i2vision-instant and i2vision-discover modules
 * to provide architectural context and project-wide analysis.
 * 
 * ## Tools
 * 
 * - **i2vision_discover**: Full VSLFC discovery of a project
 * - **i2vision_get_context**: Instant context for a specific file
 * - **i2vision_get_related**: Find related files
 * 
 * @property instantContext i2vision instant context provider
 * @property discoveryEngine i2vision discovery engine
 * @property discoveryCache i2vision discovery cache
 */
class DiscoveryTools(
    private val instantContext: InstantContextProvider,
    private val discoveryEngine: DiscoveryEngine,
    private val discoveryCache: DiscoveryCache
) {
    
    /**
     * Full VSLFC discovery of a project or module.
     * 
     * This tool runs comprehensive discovery to extract:
     * - Architecture patterns and components
     * - Data and control flows
     * - Business rules and invariants
     * - VSLFC layer assignments
     * - Symbol relationships
     * 
     * Discovery results are cached for 1 hour to avoid repeated expensive analysis.
     */
    fun discover(): Tool = Tool(
        name = "i2vision_discover",
        aliases = listOf("discover", "analyze_project", "full_discovery"),
        description = "Run full VSLFC discovery on a project or module. " +
                      "Returns architecture patterns, flows, business rules, and components. " +
                      "This is an expensive operation that may take minutes on large projects.",
        category = ToolCategory.DISCOVERY,
        isReadOnly = true,
        isExpensive = true,
        cacheResults = true,
        cacheTtlSeconds = 3600,  // Cache for 1 hour
        parameters = listOf(
            ToolParameter(
                name = "path",
                type = "string",
                description = "Project or module path to discover (absolute or relative to workspace)"
            ),
            ToolParameter(
                name = "intent",
                type = "string",
                description = "Discovery intent: full_discovery, refactoring_analysis, quick_overview, " +
                              "architecture_audit, flow_mapping, or documentation_generation",
                required = false,
                default = "quick_overview",
                enum = listOf(
                    "full_discovery",
                    "refactoring_analysis",
                    "quick_overview",
                    "architecture_audit",
                    "flow_mapping",
                    "documentation_generation"
                )
            ),
            ToolParameter(
                name = "force",
                type = "boolean",
                description = "Force re-discovery even if cache exists",
                required = false,
                default = false
            )
        ),
        handler = { args ->
            val path = args["path"] as? String ?: return@Tool ToolResult.failure("path is required")
            val intent = args["intent"] as? String ?: "quick_overview"
            val force = args["force"] as? Boolean ?: false
            
            // Check cache first (unless force is true)
            if (!force) {
                val cached = discoveryCache.get(path)
                if (cached != null && cached.isFresh()) {
                    return@Tool ToolResult.success(
                        output = cached.toSummary(),
                        metadata = mapOf(
                            "source" to "cache",
                            "cachedAt" to cached.timestamp.toString(),
                            "clusters" to cached.clusters.size.toString(),
                            "symbols" to cached.totalSymbols.toString()
                        )
                    )
                }
            }
            
            // Run discovery
            val result = try {
                discoveryEngine.discover(
                    path = path,
                    intent = DiscoveryIntent.fromString(intent)
                )
            } catch (e: Exception) {
                return@Tool ToolResult.failure(
                    error = "Discovery failed: ${e.message}",
                    metadata = mapOf("path" to path)
                )
            }
            
            // Cache the result
            discoveryCache.put(path, result)
            
            ToolResult.success(
                output = result.toSummary(),
                metadata = mapOf(
                    "source" to "fresh_discovery",
                    "duration" to result.duration.toString(),
                    "clusters" to result.clusters.size.toString(),
                    "symbols" to result.totalSymbols.toString(),
                    "path" to path
                )
            )
        }
    )
    
    /**
     * Get instant context for a specific file.
     * 
     * This tool provides architectural context for a single file, including:
     * - VSLFC layer assignments for symbols
     * - Related files and dependencies
     * - Business rules and flows
     * - Complexity metrics
     * 
     * Results are cached for 5 minutes to balance freshness and performance.
     */
    fun getContext(): Tool = Tool(
        name = "i2vision_get_context",
        aliases = listOf("get_context", "file_context", "analyze_file", "context"),
        description = "Get architectural context for a specific file. " +
                      "Returns VSLFC layers, related files, business rules, flows, and complexity metrics. " +
                      "Use this before making changes to understand the file's role in the architecture.",
        category = ToolCategory.DISCOVERY,
        isReadOnly = true,
        isExpensive = false,
        cacheResults = true,
        cacheTtlSeconds = 300,  // Cache for 5 minutes
        parameters = listOf(
            ToolParameter(
                name = "file",
                type = "string",
                description = "File path relative to workspace root"
            ),
            ToolParameter(
                name = "task",
                type = "string",
                description = "Task type for context optimization: debug, refactor, add_feature, " +
                              "fix_bug, optimize, or discovery",
                required = false,
                default = "discovery",
                enum = listOf("debug", "refactor", "add_feature", "fix_bug", "optimize", "discovery")
            )
        ),
        handler = { args ->
            val file = args["file"] as? String ?: return@Tool ToolResult.failure("file is required")
            val task = args["task"] as? String ?: "discovery"
            
            val context = try {
                instantContext.getContext(
                    file = file,
                    task = TaskType.fromString(task)
                )
            } catch (e: Exception) {
                return@Tool ToolResult.failure(
                    error = "Context retrieval failed: ${e.message}",
                    metadata = mapOf("file" to file)
                )
            }
            
            ToolResult.success(
                output = context.toDetailedDescription(),
                metadata = mapOf(
                    "file" to file,
                    "symbols" to context.symbols.size.toString(),
                    "relatedFiles" to context.relatedFiles.size.toString(),
                    "complexity" to context.complexity.toString(),
                    "primaryLayer" to context.primaryLayer.name,
                    "flows" to context.flows.size.toString(),
                    "businessRules" to context.businessRules.size.toString()
                )
            )
        }
    )
    
    /**
     * Get files related to a given file.
     * 
     * This tool finds files that are related to or dependent on a given file,
     * based on import relationships, function calls, and other dependencies.
     */
    fun getRelated(): Tool = Tool(
        name = "i2vision_get_related",
        aliases = listOf("get_related", "related_files", "find_dependencies", "dependencies"),
        description = "Find files related to or dependent on a given file. " +
                      "Returns files that import this file, are imported by this file, " +
                      "call functions in this file, or are called by this file.",
        category = ToolCategory.DISCOVERY,
        isReadOnly = true,
        isExpensive = false,
        cacheResults = true,
        cacheTtlSeconds = 600,  // Cache for 10 minutes
        parameters = listOf(
            ToolParameter(
                name = "file",
                type = "string",
                description = "File path to find relations for"
            ),
            ToolParameter(
                name = "relationType",
                type = "string",
                description = "Type of relation: imports (files this imports), " +
                              "imported_by (files that import this), calls (files this calls), " +
                              "called_by (files that call this), or all",
                required = false,
                default = "all",
                enum = listOf("imports", "imported_by", "calls", "called_by", "all")
            )
        ),
        handler = { args ->
            val file = args["file"] as? String ?: return@Tool ToolResult.failure("file is required")
            val relationType = args["relationType"] as? String ?: "all"
            
            val related = try {
                instantContext.getRelatedFiles(
                    file = file,
                    relationType = RelationType.fromString(relationType)
                )
            } catch (e: Exception) {
                return@Tool ToolResult.failure(
                    error = "Related files retrieval failed: ${e.message}",
                    metadata = mapOf("file" to file)
                )
            }
            
            ToolResult.success(
                output = related.toSummary(),
                metadata = mapOf(
                    "file" to file,
                    "relationType" to relationType,
                    "count" to related.files.size.toString()
                )
            )
        }
    )
}

/**
 * Discovery result from i2vision-discover.
 */
data class DiscoveryResult(
    val path: String,
    val clusters: List<ClusterInfo>,
    val totalSymbols: Int,
    val duration: Long,
    val timestamp: Long = System.currentTimeMillis(),
    val architecture: ArchitectureInfo? = null,
    val contracts: List<ContractInfo> = emptyList(),
    val flows: List<FlowInfo> = emptyList(),
    val businessRules: List<BusinessRuleInfo> = emptyList()
) {
    /**
     * Check if result is fresh (not expired).
     */
    fun isFresh(): Boolean {
        val age = System.currentTimeMillis() - timestamp
        return age < 3600000  // 1 hour
    }
    
    /**
     * Convert to summary string.
     */
    fun toSummary(): String = buildString {
        appendLine("## Discovery Result for: $path")
        appendLine()
        appendLine("**Duration:** ${duration}ms")
        appendLine("**Total Symbols:** $totalSymbols")
        appendLine("**Clusters:** ${clusters.size}")
        appendLine()
        
        if (architecture != null) {
            appendLine("### Architecture")
            appendLine(architecture.description)
            appendLine()
        }
        
        if (contracts.isNotEmpty()) {
            appendLine("### Contracts")
            contracts.take(5).forEach { contract ->
                appendLine("- ${contract.layer}: ${contract.description.take(100)}")
            }
            if (contracts.size > 5) {
                appendLine("- ... and ${contracts.size - 5} more")
            }
            appendLine()
        }
        
        if (flows.isNotEmpty()) {
            appendLine("### Flows")
            flows.take(5).forEach { flow ->
                appendLine("- ${flow.name}: ${flow.description.take(100)}")
            }
            if (flows.size > 5) {
                appendLine("- ... and ${flows.size - 5} more")
            }
            appendLine()
        }
        
        if (businessRules.isNotEmpty()) {
            appendLine("### Business Rules")
            businessRules.take(5).forEach { rule ->
                appendLine("- ${rule.description.take(100)}")
            }
            if (businessRules.size > 5) {
                appendLine("- ... and ${businessRules.size - 5} more")
            }
            appendLine()
        }
        
        appendLine("### Clusters")
        clusters.take(10).forEach { cluster ->
            appendLine("- ${cluster.name}: ${cluster.symbolCount} symbols")
        }
        if (clusters.size > 10) {
            appendLine("- ... and ${clusters.size - 10} more")
        }
    }
}

/**
 * Architecture information from discovery cache.
 */
data class ArchitectureInfo(
    val description: String,
    val components: List<String> = emptyList(),
    val layers: List<String> = emptyList()
)

/**
 * Cluster information from discovery.
 */
data class ClusterInfo(
    val name: String,
    val symbolCount: Int,
    val primaryLayer: VslfcLayer,
    val files: List<String> = emptyList()
)

/**
 * Discovery cache for storing and retrieving discovery results.
 */
interface DiscoveryCache {
    /**
     * Get cached discovery result.
     */
    fun get(path: String): DiscoveryResult?
    
    /**
     * Put discovery result in cache.
     */
    fun put(path: String, result: DiscoveryResult)
    
    /**
     * Clear cache for a path.
     */
    fun clear(path: String)
    
    /**
     * Clear all cache.
     */
    fun clearAll()
    
    /**
     * Close the cache and release resources.
     */
    fun close()
}

/**
 * Discovery engine interface for running discovery.
 */
interface DiscoveryEngine {
    /**
     * Run discovery on a path.
     */
    suspend fun discover(path: String, intent: DiscoveryIntent): DiscoveryResult
    
    /**
     * Close the engine and release resources.
     */
    fun close()
}

/**
 * Instant context provider interface.
 */
interface InstantContextProvider {
    /**
     * Get context for a file.
     */
    suspend fun getContext(file: String, task: TaskType): FileContext
    
    /**
     * Get related files.
     */
    suspend fun getRelatedFiles(file: String, relationType: RelationType): RelatedFiles
    
    /**
     * Close the provider and release resources.
     */
    fun close()
}

/**
 * File context from i2vision-instant.
 */
data class FileContext(
    val file: String,
    val symbols: List<SymbolInfo>,
    val relatedFiles: List<String>,
    val flows: List<FlowInfo>,
    val businessRules: List<BusinessRuleInfo>,
    val primaryLayer: VslfcLayer,
    val complexity: Int
) {
    /**
     * Convert to detailed description.
     */
    fun toDetailedDescription(): String = buildString {
        appendLine("## Context for: $file")
        appendLine()
        appendLine("**Primary Layer:** ${primaryLayer.displayName}")
        appendLine("**Complexity:** $complexity")
        appendLine("**Symbols:** ${symbols.size}")
        appendLine("**Related Files:** ${relatedFiles.size}")
        appendLine()
        
        if (symbols.isNotEmpty()) {
            appendLine("### Symbols")
            symbols.take(10).forEach { symbol ->
                appendLine("- ${symbol.kind}: ${symbol.name}")
            }
            if (symbols.size > 10) {
                appendLine("- ... and ${symbols.size - 10} more")
            }
            appendLine()
        }
        
        if (flows.isNotEmpty()) {
            appendLine("### Flows")
            flows.forEach { flow ->
                appendLine("- ${flow.name}: ${flow.description}")
            }
            appendLine()
        }
        
        if (businessRules.isNotEmpty()) {
            appendLine("### Business Rules")
            businessRules.forEach { rule ->
                appendLine("- ${rule.description}")
            }
            appendLine()
        }
        
        if (relatedFiles.isNotEmpty()) {
            appendLine("### Related Files")
            relatedFiles.take(10).forEach { file ->
                appendLine("- $file")
            }
            if (relatedFiles.size > 10) {
                appendLine("- ... and ${relatedFiles.size - 10} more")
            }
        }
    }
}

/**
 * Related files result.
 */
data class RelatedFiles(
    val file: String,
    val relationType: RelationType,
    val files: List<String>
) {
    /**
     * Convert to summary string.
     */
    fun toSummary(): String = buildString {
        appendLine("## Files Related to: $file")
        appendLine("**Relation Type:** ${relationType.name}")
        appendLine("**Total:** ${files.size}")
        appendLine()
        
        files.forEach { f ->
            appendLine("- $f")
        }
    }
}
