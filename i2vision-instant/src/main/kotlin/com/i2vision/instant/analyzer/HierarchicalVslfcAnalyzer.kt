package com.i2vision.instant.analyzer

import com.i2vision.instant.artifact.GenericArtifactLoader
import com.i2vision.storage.model.StorageConstants
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Detects and analyzes subfolder clusters within modules
 */
data class ClusterInfo(
    val name: String,
    val path: String,
    val type: ClusterType,
    val metrics: VslfcMetrics? = null,
    val subClusters: List<ClusterInfo> = emptyList(),
    val entryPoints: List<EntryPoint> = emptyList(),
    val parent: String? = null
)

enum class ClusterType {
    CORE,           // Core business logic
    AGENT,          // Agent implementations  
    MCP,            // MCP integrations
    API,            // API/Contract definitions
    UTIL,           // Utilities/Helpers
    CONFIG,         // Configuration
    WORKFLOW,       // Workflow/Orchestration
    DATA,           // Data access layer
    UNKNOWN
}

data class EntryPoint(
    val name: String,
    val file: String,
    val type: EntryPointType,
    val exposedOperations: List<String> = emptyList(),
    val dependencies: List<String> = emptyList()
)

enum class EntryPointType {
    REST_ENDPOINT,      // @RestController, @GetMapping, etc.
    SERVICE_METHOD,     // Public service methods
    SCHEDULED_TASK,     // @Scheduled methods
    MESSAGE_HANDLER,    // Kafka/RabbitMQ listeners
    COMMAND,            // CLI commands
    API_CONTRACT,       // Interface/API definitions
    WORKFLOW_TRIGGER    // Workflow start points
}

/**
 * Enhanced analyzer with hierarchical support.
 * Migrated to use storage-core API.
 */
class HierarchicalVslfcAnalyzer(
    private val artifactLoader: GenericArtifactLoader,
    private val projectRoot: String,
    private val cacheStore: com.i2vision.storage.api.CacheStore = com.i2vision.storage.impl.FileCacheStore(File(projectRoot))
) {
    private val log = LoggerFactory.getLogger(HierarchicalVslfcAnalyzer::class.java)
    private val metricsAggregator = ClusterMetricsAggregator(
        File(projectRoot, StorageConstants.SEMANTIC_CACHE_DIR).absolutePath
    )
    
    /**
     * Analyze a module with hierarchical cluster detection
     */
    fun analyzeModuleWithClusters(modulePath: String): HierarchicalAnalysisResult {
        log.debug("[HIERARCHICAL] Analyzing module: $modulePath")
        
        val moduleDir = File(projectRoot, modulePath)
        val clusters = detectClusters(moduleDir, modulePath)
        
        // Analyze each cluster
        val analyzedClusters = clusters.map { cluster ->
            val clusterMetrics = try {
                analyzeCluster(modulePath, cluster.name)
            } catch (e: Exception) {
                log.warn("[HIERARCHICAL] Failed to analyze cluster ${cluster.name}: ${e.message}")
                null
            }
            cluster.copy(metrics = clusterMetrics)
        }
        
        // Analyze overall module
        val moduleMetrics = try {
            analyzeCluster(modulePath, "")
        } catch (e: Exception) {
            log.warn("[HIERARCHICAL] Failed to analyze module: ${e.message}")
            VslfcMetrics(
                moduleName = modulePath,
                healthScore = 0.0,
                complexityScore = 0.0,
                maintainabilityScore = 0.0,
                riskLevel = "UNKNOWN"
            )
        }
        
        // Identify hotspots (clusters with poor metrics)
        val hotspots = identifyHotspots(analyzedClusters)
        
        // Generate cluster-specific suggestions
        val clusterSuggestions = generateClusterSuggestions(analyzedClusters, hotspots)
        
        return HierarchicalAnalysisResult(
            modulePath = modulePath,
            moduleMetrics = moduleMetrics,
            clusters = analyzedClusters,
            hotspots = hotspots,
            clusterSuggestions = clusterSuggestions,
            entryPoints = findAllEntryPoints(analyzedClusters),
            overallReport = generateHierarchicalReport(moduleMetrics, analyzedClusters, hotspots, clusterSuggestions)
        )
    }
    
    /**
     * Analyze a specific cluster within a module
     */
    fun analyzeCluster(modulePath: String, clusterName: String): VslfcMetrics {
        return try {
            val metrics = metricsAggregator.aggregateClusterMetrics(modulePath, clusterName)
            VslfcMetrics(
                moduleName = clusterName.ifEmpty { modulePath },
                healthScore = metrics.healthScore,
                complexityScore = metrics.complexityScore,
                maintainabilityScore = metrics.maintainabilityScore,
                riskLevel = calculateRiskLevel(metrics)
            )
        } catch (e: Exception) {
            log.error("[HIERARCHICAL] Failed to aggregate cluster metrics: ${e.message}")
            VslfcMetrics(
                moduleName = clusterName.ifEmpty { modulePath },
                healthScore = 0.0,
                complexityScore = 0.0,
                maintainabilityScore = 0.0,
                riskLevel = "ERROR"
            )
        }
    }
    
    /**
     * Get cluster context for display
     */
    fun getClusterContext(modulePath: String, clusterName: String): String {
        val clusterMetrics = metricsAggregator.aggregateClusterMetrics(modulePath, clusterName)
        return clusterMetrics.toContextString()
    }
    
    /**
     * Detect clusters by analyzing subfolder structure and content
     */
    private fun detectClusters(directory: File, basePath: String): List<ClusterInfo> {
        val clusters = mutableListOf<ClusterInfo>()
        
        // First, try to load structure artifacts from semantic cache
        val artifacts = artifactLoader.loadFromDirectory(basePath)
        val structure = artifacts.getLayer("structure")
        
        if (structure.isNotEmpty()) {
            // Extract components from structure layer as clusters
            structure.forEach { artifact ->
                val components = artifact["components"] as? List<*> ?: emptyList<Any>()
                components.forEach { component ->
                    @Suppress("UNCHECKED_CAST")
                    val compMap = component as? Map<String, Any>
                    val name = compMap?.get("name")?.toString()
                    val path = compMap?.get("path")?.toString()
                    
                    if (name != null) {
                        clusters.add(ClusterInfo(
                            name = name,
                            path = path ?: "$basePath/$name",
                            type = detectClusterType(name, compMap)
                        ))
                    }
                }
            }
        }
        
        // Fallback: scan actual source code structure
        if (clusters.isEmpty()) {
            val srcDir = File(directory, "src/main/kotlin")
            val scanDir = if (srcDir.exists()) srcDir else directory
            
            val subdirs = scanDir.listFiles()?.filter { 
                it.isDirectory && 
                !it.name.startsWith(".") &&
                !it.name.equals("build", ignoreCase = true) &&
                !it.name.equals("bin", ignoreCase = true)
            } ?: emptyList()
            
            subdirs.forEach { subdir ->
                val clusterType = detectClusterTypeFromPath(subdir.name)
                val subClusters = if (hasSignificantStructure(subdir)) {
                    detectClusters(subdir, "$basePath/${subdir.name}")
                } else emptyList()
                
                clusters.add(ClusterInfo(
                    name = subdir.name,
                    path = "$basePath/${subdir.name}",
                    type = clusterType,
                    subClusters = subClusters
                ))
            }
        }
        
        return clusters.sortedByDescending { it.subClusters.size }
    }
    
    /**
     * Detect cluster type based on naming and content
     */
    private fun detectClusterType(name: String, metadata: Map<String, Any>? = null): ClusterType {
        val lowerName = name.lowercase()
        
        return when {
            lowerName.contains("agent") -> ClusterType.AGENT
            lowerName.contains("mcp") -> ClusterType.MCP
            lowerName.contains("api") || lowerName.contains("contract") -> ClusterType.API
            lowerName.contains("util") || lowerName.contains("helper") -> ClusterType.UTIL
            lowerName.contains("config") -> ClusterType.CONFIG
            lowerName.contains("workflow") || lowerName.contains("orchestrator") -> ClusterType.WORKFLOW
            lowerName.contains("data") || lowerName.contains("repository") -> ClusterType.DATA
            lowerName.contains("core") -> ClusterType.CORE
            else -> ClusterType.UNKNOWN
        }
    }
    
    private fun detectClusterTypeFromPath(name: String): ClusterType = detectClusterType(name)
    
    private fun hasSignificantStructure(directory: File): Boolean {
        // Check if directory has enough content to warrant sub-clusters
        val fileCount = directory.walk()
            .maxDepth(2)
            .filter { it.isFile && it.extension in listOf("kt", "java", "yaml", "yml") }
            .count()
        
        return fileCount > 5
    }
    
    /**
     * Find all entry points across clusters
     */
    private fun findAllEntryPoints(clusters: List<ClusterInfo>): List<EntryPoint> {
        val entryPoints = mutableListOf<EntryPoint>()
        
        clusters.forEach { cluster ->
            entryPoints.addAll(findEntryPointsInCluster(cluster))
            entryPoints.addAll(findAllEntryPoints(cluster.subClusters))
        }
        
        return entryPoints
    }
    
    private fun findEntryPointsInCluster(cluster: ClusterInfo): List<EntryPoint> {
        val entryPoints = mutableListOf<EntryPoint>()
        
        // Load artifacts for this cluster
        val artifacts = artifactLoader.loadFromDirectory(cluster.path)
        
        // Check flow layer for entry points
        val flow = artifacts.getLayer("flow")
        flow.forEach { artifact ->
            val entryPoints_raw = artifact["entry_points"] as? List<*> ?: emptyList<Any>()
            entryPoints_raw.forEach { ep ->
                @Suppress("UNCHECKED_CAST")
                val epMap = ep as? Map<String, Any>
                if (epMap != null) {
                    entryPoints.add(EntryPoint(
                        name = epMap["name"]?.toString() ?: "unknown",
                        file = epMap["file"]?.toString() ?: "",
                        type = detectEntryPointType(epMap),
                        exposedOperations = (epMap["operations"] as? List<*>)?.mapNotNull { it.toString() } ?: emptyList(),
                        dependencies = (epMap["dependencies"] as? List<*>)?.mapNotNull { it.toString() } ?: emptyList()
                    ))
                }
            }
        }
        
        // Also check code layer for public APIs
        val code = artifacts.getLayer("code")
        code.forEach { artifact ->
            val symbols = artifact["symbols"] as? List<*> ?: emptyList<Any>()
            symbols.forEach { symbol ->
                @Suppress("UNCHECKED_CAST")
                val symMap = symbol as? Map<String, Any>
                val visibility = symMap?.get("visibility")?.toString() ?: ""
                val kind = symMap?.get("kind")?.toString() ?: ""
                
                if (visibility == "public" && (kind.contains("class") || kind.contains("interface"))) {
                    entryPoints.add(EntryPoint(
                        name = symMap?.get("name")?.toString() ?: "",
                        file = symMap?.get("file")?.toString() ?: "",
                        type = EntryPointType.API_CONTRACT,
                        exposedOperations = (symMap?.get("methods") as? List<*>)?.mapNotNull { it.toString() } ?: emptyList()
                    ))
                }
            }
        }
        
        return entryPoints
    }
    
    private fun detectEntryPointType(epMap: Map<String, Any>): EntryPointType {
        val annotations = (epMap["annotations"] as? List<*>)?.mapNotNull { it.toString() } ?: emptyList()
        val kind = epMap["kind"]?.toString()?.lowercase() ?: ""
        
        return when {
            annotations.any { it.contains("RestController") || it.contains("GetMapping") } -> EntryPointType.REST_ENDPOINT
            annotations.any { it.contains("Scheduled") } -> EntryPointType.SCHEDULED_TASK
            annotations.any { it.contains("KafkaListener") || it.contains("RabbitListener") } -> EntryPointType.MESSAGE_HANDLER
            kind.contains("command") -> EntryPointType.COMMAND
            kind.contains("workflow") || kind.contains("orchestration") -> EntryPointType.WORKFLOW_TRIGGER
            else -> EntryPointType.SERVICE_METHOD
        }
    }
    
    /**
     * Identify hotspots - clusters with concerning metrics
     */
    private fun identifyHotspots(clusters: List<ClusterInfo>): List<ClusterHotspot> {
        val hotspots = mutableListOf<ClusterHotspot>()
        
        clusters.forEach { cluster ->
            val metrics = cluster.metrics
            if (metrics != null) {
                val issues = mutableListOf<String>()
                var severity = HotspotSeverity.LOW
                
                // Check various metrics
                if (metrics.complexityScore > 0.7) {
                    issues.add("High complexity (${(metrics.complexityScore * 100).toInt()}%)")
                    severity = HotspotSeverity.HIGH
                }
                
                if (metrics.maintainabilityScore < 0.4) {
                    issues.add("Poor maintainability (${(metrics.maintainabilityScore * 100).toInt()}%)")
                    if (severity.ordinal < HotspotSeverity.HIGH.ordinal) severity = HotspotSeverity.HIGH
                }
                
                if (metrics.riskLevel == "HIGH" || metrics.riskLevel == "CRITICAL") {
                    issues.add("High risk level (${metrics.riskLevel})")
                    severity = HotspotSeverity.CRITICAL
                }
                
                if (issues.isNotEmpty()) {
                    hotspots.add(ClusterHotspot(
                        cluster = cluster,
                        issues = issues,
                        severity = severity,
                        priorityScore = calculatePriorityScore(metrics)
                    ))
                }
            }
            
            // Check subclusters recursively
            hotspots.addAll(identifyHotspots(cluster.subClusters))
        }
        
        return hotspots.sortedByDescending { it.priorityScore }
    }
    
    private fun calculatePriorityScore(metrics: VslfcMetrics): Double {
        return (metrics.complexityScore * 0.4 + 
               (1 - metrics.maintainabilityScore) * 0.4 + 
               (1 - metrics.healthScore) * 0.2)
    }
    
    /**
     * Generate cluster-specific refactoring suggestions
     */
    private fun generateClusterSuggestions(
        clusters: List<ClusterInfo>,
        hotspots: List<ClusterHotspot>
    ): List<ClusterSuggestion> {
        val suggestions = mutableListOf<ClusterSuggestion>()
        
        hotspots.forEach { hotspot ->
            when (hotspot.cluster.type) {
                ClusterType.AGENT -> {
                    suggestions.add(ClusterSuggestion(
                        clusterName = hotspot.cluster.name,
                        title = "Refactor Agent Implementation",
                        description = "Agent cluster has ${hotspot.issues.joinToString(", ")}",
                        actions = listOf(
                            "Extract common agent behavior to base classes",
                            "Implement strategy pattern for agent variations",
                            "Add agent lifecycle hooks for better control",
                            "Consider using actor model for agent isolation"
                        ),
                        estimatedGain = "Improves agent reusability by 40%"
                    ))
                }
                
                ClusterType.MCP -> {
                    suggestions.add(ClusterSuggestion(
                        clusterName = hotspot.cluster.name,
                        title = "Optimize MCP Integration",
                        description = "MCP cluster shows integration complexity",
                        actions = listOf(
                            "Standardize MCP tool interfaces",
                            "Implement connection pooling",
                            "Add circuit breakers for external calls",
                            "Create MCP tool registry with validation"
                        ),
                        estimatedGain = "Reduces integration errors by 50%"
                    ))
                }
                
                ClusterType.WORKFLOW -> {
                    suggestions.add(ClusterSuggestion(
                        clusterName = hotspot.cluster.name,
                        title = "Simplify Workflow Orchestration",
                        description = "Workflow cluster has high complexity",
                        actions = listOf(
                            "Break workflow into smaller steps",
                            "Implement saga pattern for distributed transactions",
                            "Add workflow versioning",
                            "Use state machine for clear transitions"
                        ),
                        estimatedGain = "Improves workflow reliability by 35%"
                    ))
                }
                
                ClusterType.CORE -> {
                    suggestions.add(ClusterSuggestion(
                        clusterName = hotspot.cluster.name,
                        title = "Stabilize Core Module",
                        description = "Core cluster requires architectural attention",
                        actions = listOf(
                            "Apply dependency inversion for core abstractions",
                            "Extract stable interfaces",
                            "Move volatile dependencies to plugins",
                            "Implement strict API boundaries"
                        ),
                        estimatedGain = "Reduces change impact by 60%"
                    ))
                }
                
                else -> {
                    suggestions.add(ClusterSuggestion(
                        clusterName = hotspot.cluster.name,
                        title = "Refactor ${hotspot.cluster.name} Cluster",
                        description = "Issues: ${hotspot.issues.joinToString(", ")}",
                        actions = listOf(
                            "Review cluster boundaries",
                            "Apply Single Responsibility Principle",
                            "Improve test coverage",
                            "Document cluster contract"
                        ),
                        estimatedGain = "Improves overall maintainability"
                    ))
                }
            }
        }
        
        return suggestions
    }
    
    /**
     * Generate comprehensive hierarchical report
     */
    private fun generateHierarchicalReport(
        moduleMetrics: VslfcMetrics,
        clusters: List<ClusterInfo>,
        hotspots: List<ClusterHotspot>,
        clusterSuggestions: List<ClusterSuggestion>
    ): String {
        return buildString {
            appendLine("============================================================")
            appendLine("HIERARCHICAL VSLFC ANALYSIS REPORT")
            appendLine("============================================================")
            appendLine()
            
            // Module overview
            appendLine("MODULE: ${moduleMetrics.moduleName}")
            appendLine("------------------------------------------------------------")
            appendLine("Overall Health: ${(moduleMetrics.healthScore * 100).toInt()}% ${getHealthIcon(moduleMetrics.healthScore)}")
            appendLine("Complexity: ${(moduleMetrics.complexityScore * 100).toInt()}%")
            appendLine("Maintainability: ${(moduleMetrics.maintainabilityScore * 100).toInt()}%")
            appendLine("Risk Level: ${moduleMetrics.riskLevel}")
            appendLine()
            
            // Cluster breakdown
            if (clusters.isNotEmpty()) {
                appendLine("CLUSTER BREAKDOWN")
                appendLine("------------------------------------------------------------")
                append(renderClusterTree(clusters, 0))
                appendLine()
            }
            
            // Hotspots
            if (hotspots.isNotEmpty()) {
                appendLine("HOTSPOTS (Priority Order)")
                appendLine("------------------------------------------------------------")
                hotspots.forEachIndexed { index, hotspot ->
                    val icon = when (hotspot.severity) {
                        HotspotSeverity.CRITICAL -> "CRITICAL"
                        HotspotSeverity.HIGH -> "HIGH"
                        HotspotSeverity.MEDIUM -> "MEDIUM"
                        HotspotSeverity.LOW -> "LOW"
                    }
                    appendLine("${index + 1}. [${icon}] ${hotspot.cluster.name}")
                    hotspot.issues.forEach { issue ->
                        appendLine("   - $issue")
                    }
                    appendLine()
                }
            }
            
            // Cluster suggestions
            if (clusterSuggestions.isNotEmpty()) {
                appendLine("CLUSTER-SPECIFIC SUGGESTIONS")
                appendLine("------------------------------------------------------------")
                clusterSuggestions.forEachIndexed { index, suggestion ->
                    appendLine("${index + 1}. ${suggestion.title}")
                    appendLine("   ${suggestion.description}")
                    appendLine("   Actions:")
                    suggestion.actions.take(3).forEach { action ->
                        appendLine("     -> $action")
                    }
                    appendLine("   Expected Gain: ${suggestion.estimatedGain}")
                    appendLine()
                }
            }
            
            // Entry points summary
            val allEntryPoints = clusters.flatMap { findAllEntryPoints(listOf(it)) }
            if (allEntryPoints.isNotEmpty()) {
                appendLine("ENTRY POINTS (${allEntryPoints.size} total)")
                appendLine("------------------------------------------------------------")
                allEntryPoints.groupBy { it.type }.forEach { (type, eps) ->
                    appendLine("${type.name}: ${eps.size}")
                    eps.take(5).forEach { ep ->
                        appendLine("  - ${ep.name} (${ep.file.substringAfterLast('/')})")
                    }
                    if (eps.size > 5) appendLine("  ... and ${eps.size - 5} more")
                    appendLine()
                }
            }
        }
    }
    
    private fun renderClusterTree(clusters: List<ClusterInfo>, depth: Int): String {
        val indent = "  ".repeat(depth)
        val result = StringBuilder()
        clusters.forEach { cluster ->
            val healthIcon = cluster.metrics?.let { getHealthIcon(it.healthScore) } ?: "?"
            val metricsStr = cluster.metrics?.let { 
                "C:${(it.complexityScore * 100).toInt()}% M:${(it.maintainabilityScore * 100).toInt()}%"
            } ?: "No metrics"
            
            result.appendLine("$indent|- $healthIcon ${cluster.name} [$metricsStr]")
            
            if (cluster.subClusters.isNotEmpty()) {
                result.append(renderClusterTree(cluster.subClusters, depth + 1))
            }
        }
        return result.toString()
    }
    
    private fun getHealthIcon(score: Double): String = when {
        score > 0.7 -> "OK"
        score > 0.4 -> "WARN"
        else -> "BAD"
    }
    
    private fun calculateRiskLevel(metrics: ClusterMetrics): String {
        return when {
            metrics.healthScore < 0.3 -> "CRITICAL"
            metrics.healthScore < 0.5 -> "HIGH"
            metrics.healthScore < 0.7 -> "MEDIUM"
            else -> "LOW"
        }
    }
}

// Supporting data classes
data class ClusterHotspot(
    val cluster: ClusterInfo,
    val issues: List<String>,
    val severity: HotspotSeverity,
    val priorityScore: Double
)

enum class HotspotSeverity {
    LOW, MEDIUM, HIGH, CRITICAL
}

data class ClusterSuggestion(
    val clusterName: String,
    val title: String,
    val description: String,
    val actions: List<String>,
    val estimatedGain: String
)

data class HierarchicalAnalysisResult(
    val modulePath: String,
    val moduleMetrics: VslfcMetrics,
    val clusters: List<ClusterInfo>,
    val hotspots: List<ClusterHotspot>,
    val clusterSuggestions: List<ClusterSuggestion>,
    val entryPoints: List<EntryPoint>,
    val overallReport: String
)

data class VslfcMetrics(
    val moduleName: String,
    val healthScore: Double,
    val complexityScore: Double,
    val maintainabilityScore: Double,
    val riskLevel: String
)
