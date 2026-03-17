package com.i2vision.instant.analyzer

import com.i2vision.instant.artifact.GenericArtifactLoader
import org.slf4j.LoggerFactory

/**
 * Aggregates cluster-level metrics from module-level VSLFC artifacts
 * Uses path filtering to extract cluster-specific metrics
 */
class ClusterMetricsAggregator(
    private val semanticCacheRoot: String
) {
    
    private val log = LoggerFactory.getLogger(ClusterMetricsAggregator::class.java)
    
    /**
     * Extract metrics for a specific cluster from module artifacts
     */
    fun aggregateClusterMetrics(modulePath: String, clusterName: String): ClusterMetrics {
        log.debug("[CLUSTER_METRICS] Aggregating metrics for cluster: $clusterName in module: $modulePath")
        
        val artifacts = loadModuleArtifacts(modulePath)
        
        // Filter artifacts by cluster path
        val clusterPath = "$modulePath/$clusterName"
        
        val filteredArtifacts = filterArtifactsByPath(artifacts, clusterPath, clusterName)
        
        return ClusterMetrics(
            clusterName = clusterName,
            modulePath = modulePath,
            visionMetrics = extractVisionMetrics(filteredArtifacts, clusterName),
            structureMetrics = extractStructureMetrics(filteredArtifacts, clusterName),
            logicMetrics = extractLogicMetrics(filteredArtifacts, clusterName),
            flowMetrics = extractFlowMetrics(filteredArtifacts, clusterName),
            codeMetrics = extractCodeMetrics(filteredArtifacts, clusterName),
            healthScore = calculateHealthScore(filteredArtifacts),
            complexityScore = calculateComplexityScore(filteredArtifacts),
            maintainabilityScore = calculateMaintainabilityScore(filteredArtifacts)
        )
    }
    
    private fun loadModuleArtifacts(modulePath: String): GenericArtifactLoader.LoadedArtifacts {
        val loader = GenericArtifactLoader(semanticCacheRoot)
        return loader.loadFromDirectory(modulePath)
    }
    
    private fun filterArtifactsByPath(
        artifacts: GenericArtifactLoader.LoadedArtifacts,
        clusterPath: String,
        clusterName: String
    ): FilteredArtifacts {
        val filtered = mutableMapOf<String, MutableList<Map<String, Any>>>()
        
        artifacts.layers.forEach { (layerName, items) ->
            val filteredItems = items.filter { item ->
                // Check if artifact belongs to this cluster
                val source = item["_source"]?.toString() ?: ""
                val file = item["file"]?.toString() ?: ""
                val path = item["path"]?.toString() ?: ""
                
                source.contains(clusterPath) || 
                file.contains(clusterPath) || 
                path.contains(clusterPath) ||
                isRelatedToCluster(item, clusterName) ||
                // Fallback: check if any value in the map contains the cluster name
                item.values.any { value ->
                    value.toString().contains(clusterName, ignoreCase = true)
                }
            }
            
            if (filteredItems.isNotEmpty()) {
                filtered[layerName] = filteredItems.toMutableList()
            }
        }
        
        // If no artifacts match, return all artifacts with a note
        if (filtered.isEmpty()) {
            log.warn("[CLUSTER_METRICS] No artifacts matched cluster $clusterName, returning all")
            return FilteredArtifacts(artifacts.layers.mapValues { it.value.toMutableList() }, clusterName)
        }
        
        return FilteredArtifacts(filtered, clusterName)
    }
    
    private fun isRelatedToCluster(item: Map<String, Any>, clusterName: String): Boolean {
        // Check component names
        val name = item["name"]?.toString() ?: ""
        if (name.lowercase() == clusterName.lowercase()) return true
        
        // Check for cluster-specific tags
        val tags = item["tags"] as? List<*> ?: emptyList<Any>()
        if (tags.any { it.toString().contains(clusterName, ignoreCase = true) }) return true
        
        // Check for package declarations
        val packageName = item["package"]?.toString() ?: ""
        if (packageName.contains(clusterName.replace('/', '.'), ignoreCase = true)) return true
        
        return false
    }
    
    private fun extractVisionMetrics(artifacts: FilteredArtifacts, clusterName: String): VisionMetrics {
        val vision = artifacts.getLayer("vision")
        var requirementCount = 0
        var clusterSpecificCount = 0
        
        vision.forEach { artifact ->
            val requirements = extractRequirements(artifact)
            requirementCount += requirements.size
            
            // Count requirements related to this cluster
            clusterSpecificCount += requirements.count { req ->
                val title = req["title"]?.toString() ?: ""
                val description = req["description"]?.toString() ?: ""
                title.contains(clusterName, ignoreCase = true) ||
                description.contains(clusterName, ignoreCase = true)
            }
        }
        
        return VisionMetrics(
            requirementCount = clusterSpecificCount,
            ambiguousRequirements = 0,
            missingTestSpecs = clusterSpecificCount > 0 && vision.none { it.containsKey("test-specs") },
            missingConstraints = clusterSpecificCount > 0 && vision.none { it.containsKey("constraints") },
            requirementCoverage = if (clusterSpecificCount > 0) 1.0 else 0.0
        )
    }
    
    private fun extractStructureMetrics(
        artifacts: FilteredArtifacts,
        clusterName: String
    ): StructureMetrics {
        val structure = artifacts.getLayer("structure")
        
        var componentCount = 0
        val dependencies = mutableSetOf<Pair<String, String>>()
        
        structure.forEach { artifact ->
            // Components related to cluster
            val components = artifact["components"] as? List<*> ?: emptyList<Any>()
            val clusterComponents = components.filter { comp ->
                @Suppress("UNCHECKED_CAST")
                val compMap = comp as? Map<String, Any>
                val name = compMap?.get("name")?.toString() ?: ""
                val path = compMap?.get("path")?.toString() ?: ""
                name.lowercase().contains(clusterName.lowercase()) ||
                path.contains(clusterName, ignoreCase = true)
            }
            componentCount += clusterComponents.size
            
            // Dependencies involving cluster components
            val deps = artifact["dependencies"] as? List<*> ?: emptyList<Any>()
            deps.forEach { dep ->
                @Suppress("UNCHECKED_CAST")
                val depMap = dep as? Map<String, Any>
                val from = depMap?.get("from")?.toString() ?: ""
                val to = depMap?.get("to")?.toString() ?: ""
                
                if (from.contains(clusterName, ignoreCase = true) || 
                    to.contains(clusterName, ignoreCase = true)) {
                    dependencies.add(from to to)
                }
            }
        }
        
        // Calculate fan-out/in for cluster
        val fanOut = dependencies.groupBy { it.first }.mapValues { it.value.size }
        val fanIn = dependencies.groupBy { it.second }.mapValues { it.value.size }
        
        return StructureMetrics(
            componentCount = componentCount,
            dependencyCount = dependencies.size,
            circularDependencies = detectCircularDependencies(dependencies),
            avgFanOut = fanOut.values.average().takeIf { !it.isNaN() } ?: 0.0,
            avgFanIn = fanIn.values.average().takeIf { !it.isNaN() } ?: 0.0,
            maxDepth = calculateMaxDepth(dependencies),
            isolatedComponents = componentCount - fanOut.keys.size,
            couplingScore = if (componentCount > 0) dependencies.size.toDouble() / (componentCount * componentCount) else 0.0,
            cohesionScore = 1.0 - (dependencies.size.toDouble() / (componentCount * componentCount).coerceAtLeast(1))
        )
    }
    
    private fun extractLogicMetrics(artifacts: FilteredArtifacts, clusterName: String): LogicMetrics {
        val logic = artifacts.getLayer("logic")
        
        var businessRuleCount = 0
        var entityCount = 0
        var undocumentedRules = 0
        
        logic.forEach { artifact ->
            // Filter rules by cluster
            val rules = artifact["business_rules"] as? List<*> ?: 
                       artifact["rules"] as? List<*> ?: emptyList<Any>()
            
            val clusterRules = rules.filter { rule ->
                @Suppress("UNCHECKED_CAST")
                val ruleMap = rule as? Map<String, Any>
                val name = ruleMap?.get("name")?.toString() ?: ""
                val description = ruleMap?.get("description")?.toString() ?: ""
                name.contains(clusterName, ignoreCase = true) ||
                description.contains(clusterName, ignoreCase = true)
            }
            
            businessRuleCount += clusterRules.size
            
            clusterRules.forEach { rule ->
                @Suppress("UNCHECKED_CAST")
                val ruleMap = rule as? Map<String, Any>
                val description = ruleMap?.get("description")?.toString() ?: ""
                if (description.isEmpty()) undocumentedRules++
            }
            
            // Entities related to cluster
            val entities = artifact["entities"] as? List<*> ?: emptyList<Any>()
            entityCount += entities.count { entity ->
                @Suppress("UNCHECKED_CAST")
                val entityMap = entity as? Map<String, Any>
                val name = entityMap?.get("name")?.toString() ?: ""
                name.contains(clusterName, ignoreCase = true)
            }
        }
        
        return LogicMetrics(
            businessRuleCount = businessRuleCount,
            entityCount = entityCount,
            stateMachineCount = 0,
            avgStateCount = 0.0,
            complexRules = 0,
            undocumentedRules = undocumentedRules,
            logicComplexity = if (businessRuleCount > 0) undocumentedRules.toDouble() / businessRuleCount else 0.0
        )
    }
    
    private fun extractFlowMetrics(
        artifacts: FilteredArtifacts,
        clusterName: String
    ): FlowMetrics {
        val flow = artifacts.getLayer("flow")
        
        var interactionCount = 0
        var entryPointCount = 0
        
        flow.forEach { artifact ->
            // Interactions involving cluster
            val interactions = artifact["interactions"] as? List<*> ?: emptyList<Any>()
            interactionCount += interactions.count { interaction ->
                @Suppress("UNCHECKED_CAST")
                val intMap = interaction as? Map<String, Any>
                val source = intMap?.get("source")?.toString() ?: ""
                val target = intMap?.get("target")?.toString() ?: ""
                source.contains(clusterName, ignoreCase = true) ||
                target.contains(clusterName, ignoreCase = true)
            }
            
            // Entry points in cluster
            val entryPoints = artifact["entry_points"] as? List<*> ?: emptyList<Any>()
            entryPointCount += entryPoints.count { ep ->
                @Suppress("UNCHECKED_CAST")
                val epMap = ep as? Map<String, Any>
                val file = epMap?.get("file")?.toString() ?: ""
                file.contains(clusterName, ignoreCase = true)
            }
        }
        
        return FlowMetrics(
            interactionCount = interactionCount,
            entryPointCount = entryPointCount,
            contractCount = 0,
            uncoveredFlows = if (entryPointCount > 0 && interactionCount == 0) entryPointCount else 0,
            cyclicFlows = 0,
            flowComplexity = if (entryPointCount > 0) interactionCount.toDouble() / entryPointCount / 10.0 else 0.0
        )
    }
    
    private fun extractCodeMetrics(
        artifacts: FilteredArtifacts,
        clusterName: String
    ): CodeMetrics {
        val code = artifacts.getLayer("code")
        
        var symbolCount = 0
        var classCount = 0
        
        code.forEach { artifact ->
            val symbols = artifact["symbols"] as? List<*> ?: emptyList<Any>()
            val clusterSymbols = symbols.filter { symbol ->
                @Suppress("UNCHECKED_CAST")
                val symMap = symbol as? Map<String, Any>
                val name = symMap?.get("name")?.toString() ?: ""
                val file = symMap?.get("file")?.toString() ?: ""
                name.contains(clusterName, ignoreCase = true) ||
                file.contains(clusterName, ignoreCase = true)
            }
            
            symbolCount += clusterSymbols.size
            classCount += clusterSymbols.count { symbol ->
                @Suppress("UNCHECKED_CAST")
                val symMap = symbol as? Map<String, Any>
                val kind = symMap?.get("kind")?.toString() ?: ""
                kind.contains("class", ignoreCase = true)
            }
        }
        
        // Estimate test coverage for cluster
        val testCoverage = estimateClusterTestCoverage(artifacts, clusterName)
        
        return CodeMetrics(
            symbolCount = symbolCount,
            classCount = classCount,
            methodCount = 0,
            avgMethodComplexity = 0.0,
            godClasses = 0,
            longMethods = 0,
            duplicateCode = 0,
            testCoverage = testCoverage
        )
    }
    
    private fun estimateClusterTestCoverage(artifacts: FilteredArtifacts, clusterName: String): Double {
        val code = artifacts.getLayer("code")
        var testSymbols = 0
        var totalSymbols = 0
        
        code.forEach { artifact ->
            val symbols = artifact["symbols"] as? List<*> ?: emptyList<Any>()
            symbols.forEach { symbol ->
                @Suppress("UNCHECKED_CAST")
                val symMap = symbol as? Map<String, Any>
                val name = symMap?.get("name")?.toString() ?: ""
                val file = symMap?.get("file")?.toString() ?: ""
                
                if (file.contains(clusterName, ignoreCase = true)) {
                    totalSymbols++
                    if (name.contains("Test", ignoreCase = true) || 
                        file.contains("test", ignoreCase = true)) {
                        testSymbols++
                    }
                }
            }
        }
        
        return if (totalSymbols > 0) testSymbols.toDouble() / totalSymbols else 0.0
    }
    
    private fun calculateHealthScore(artifacts: FilteredArtifacts): Double {
        // Simplified health score based on artifact presence
        val hasContent = artifacts.layers.isNotEmpty()
        return if (hasContent) 0.6 else 0.0
    }
    
    private fun calculateComplexityScore(artifacts: FilteredArtifacts): Double {
        // Derived from structure and flow complexity
        return 0.0 // Placeholder - would need full implementation
    }
    
    private fun calculateMaintainabilityScore(artifacts: FilteredArtifacts): Double {
        return 0.0 // Placeholder - would need full implementation
    }
    
    // Helper methods
    private fun extractRequirements(artifact: Map<String, Any>): List<Map<String, Any>> {
        val requirements = mutableListOf<Map<String, Any>>()
        listOf("requirements", "vision", "items").forEach { key ->
            when (val value = artifact[key]) {
                is List<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    requirements.addAll(value.filterIsInstance<Map<String, Any>>() as List<Map<String, Any>>)
                }
                is Map<*, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    requirements.add(value as Map<String, Any>)
                }
            }
        }
        return requirements
    }
    
    private fun detectCircularDependencies(dependencies: Set<Pair<String, String>>): Int {
        // Simplified cycle detection
        val graph = dependencies.groupBy({ it.first }, { it.second })
        var cycles = 0
        
        fun hasCycle(node: String, visited: MutableSet<String>, stack: MutableSet<String>): Boolean {
            if (stack.contains(node)) return true
            if (visited.contains(node)) return false
            visited.add(node)
            stack.add(node)
            graph[node]?.forEach { if (hasCycle(it, visited.toMutableSet(), stack.toMutableSet())) return true }
            stack.remove(node)
            return false
        }
        
        graph.keys.forEach { if (hasCycle(it, mutableSetOf(), mutableSetOf())) cycles++ }
        return cycles
    }
    
    private fun calculateMaxDepth(dependencies: Set<Pair<String, String>>): Int {
        val graph = dependencies.groupBy({ it.first }, { it.second })
        
        fun depth(node: String, visited: MutableSet<String>): Int {
            if (visited.contains(node)) return 0
            visited.add(node)
            val depths = graph[node]?.map { 1 + depth(it, visited.toMutableSet()) } ?: emptyList()
            return depths.maxOrNull() ?: 0
        }
        
        return graph.keys.map { depth(it, mutableSetOf()) }.maxOrNull() ?: 0
    }
    
    data class FilteredArtifacts(
        private val layersMap: Map<String, List<Map<String, Any>>>,
        val clusterName: String
    ) {
        fun getLayer(name: String): List<Map<String, Any>> = layersMap[name] ?: emptyList()
        val layers: Set<String> get() = layersMap.keys
    }
}

// Data classes for metrics
data class VisionMetrics(
    val requirementCount: Int,
    val ambiguousRequirements: Int,
    val missingTestSpecs: Boolean,
    val missingConstraints: Boolean,
    val requirementCoverage: Double
)

data class StructureMetrics(
    val componentCount: Int,
    val dependencyCount: Int,
    val circularDependencies: Int,
    val avgFanOut: Double,
    val avgFanIn: Double,
    val maxDepth: Int,
    val isolatedComponents: Int,
    val couplingScore: Double,
    val cohesionScore: Double
)

data class LogicMetrics(
    val businessRuleCount: Int,
    val entityCount: Int,
    val stateMachineCount: Int,
    val avgStateCount: Double,
    val complexRules: Int,
    val undocumentedRules: Int,
    val logicComplexity: Double
)

data class FlowMetrics(
    val interactionCount: Int,
    val entryPointCount: Int,
    val contractCount: Int,
    val uncoveredFlows: Int,
    val cyclicFlows: Int,
    val flowComplexity: Double
)

data class CodeMetrics(
    val symbolCount: Int,
    val classCount: Int,
    val methodCount: Int,
    val avgMethodComplexity: Double,
    val godClasses: Int,
    val longMethods: Int,
    val duplicateCode: Int,
    val testCoverage: Double
)

data class ClusterMetrics(
    val clusterName: String,
    val modulePath: String,
    val visionMetrics: VisionMetrics,
    val structureMetrics: StructureMetrics,
    val logicMetrics: LogicMetrics,
    val flowMetrics: FlowMetrics,
    val codeMetrics: CodeMetrics,
    val healthScore: Double,
    val complexityScore: Double,
    val maintainabilityScore: Double
) {
    fun toContextString(): String = buildString {
        appendLine("# $clusterName Cluster")
        appendLine()
        appendLine("*Type: ${detectClusterType(clusterName)}*")
        appendLine("*Parent Module: ${modulePath.substringBeforeLast('/')}")
        appendLine()
        appendLine("## Cluster Health")
        appendLine("- Health Score: ${"%.1f".format(healthScore * 100)}%")
        appendLine("- Complexity: ${"%.1f".format(complexityScore * 100)}%")
        appendLine("- Maintainability: ${"%.1f".format(maintainabilityScore * 100)}%")
        appendLine()
        
        if (structureMetrics.componentCount > 0) {
            appendLine("## Metrics")
            appendLine("- Components: ${structureMetrics.componentCount}")
            appendLine("- Dependencies: ${structureMetrics.dependencyCount}")
            if (logicMetrics.businessRuleCount > 0) {
                appendLine("- Business Rules: ${logicMetrics.businessRuleCount}")
            }
            if (codeMetrics.symbolCount > 0) {
                appendLine("- Symbols: ${codeMetrics.symbolCount}")
            }
            appendLine("- Test Coverage: ${"%.0f".format(codeMetrics.testCoverage * 100)}%")
            appendLine()
        }
        
        // Generate hotspots
        val hotspots = generateHotspots()
        if (hotspots.isNotEmpty()) {
            appendLine("## Hotspots")
            hotspots.forEach { hotspot ->
                appendLine("- ${hotspot.severity}: ${hotspot.description}")
            }
            appendLine()
        }
        
        // Generate suggestions
        val suggestions = generateSuggestions()
        if (suggestions.isNotEmpty()) {
            appendLine("## Suggestions")
            suggestions.forEach { suggestion ->
                appendLine("- ${suggestion.title}")
                appendLine("  ${suggestion.description}")
                suggestion.actions.take(3).forEach { action ->
                    appendLine("  → $action")
                }
                appendLine()
            }
        }
    }
    
    private fun detectClusterType(name: String): String {
        return when {
            name.contains("mcp", ignoreCase = true) -> "MCP"
            name.contains("agent", ignoreCase = true) -> "AGENT"
            name.contains("api", ignoreCase = true) -> "API"
            name.contains("core", ignoreCase = true) -> "CORE"
            else -> "UNKNOWN"
        }
    }
    
    private fun generateHotspots(): List<ClusterHotspot> {
        val hotspots = mutableListOf<ClusterHotspot>()
        
        if (maintainabilityScore < 0.5) {
            hotspots.add(ClusterHotspot(
                severity = "HIGH",
                description = "Poor maintainability (${"%.0f".format(maintainabilityScore * 100)}%)"
            ))
        }
        
        if (codeMetrics.testCoverage < 0.5) {
            hotspots.add(ClusterHotspot(
                severity = "HIGH",
                description = "Low test coverage (${"%.0f".format(codeMetrics.testCoverage * 100)}%)"
            ))
        }
        
        if (structureMetrics.circularDependencies > 0) {
            hotspots.add(ClusterHotspot(
                severity = "CRITICAL",
                description = "Circular dependencies (${structureMetrics.circularDependencies})"
            ))
        }
        
        if (logicMetrics.undocumentedRules > 0 && logicMetrics.businessRuleCount > 0) {
            val percent = (logicMetrics.undocumentedRules.toDouble() / logicMetrics.businessRuleCount * 100).toInt()
            hotspots.add(ClusterHotspot(
                severity = "MEDIUM",
                description = "Undocumented rules ($percent%)"
            ))
        }
        
        return hotspots
    }
    
    private fun generateSuggestions(): List<ClusterSuggestion> {
        val suggestions = mutableListOf<ClusterSuggestion>()
        
        if (clusterName.contains("mcp", ignoreCase = true)) {
            suggestions.add(ClusterSuggestion(
                title = "Optimize MCP Integration",
                description = "MCP cluster shows integration complexity",
                actions = listOf(
                    "Standardize MCP tool interfaces",
                    "Implement connection pooling",
                    "Add circuit breakers for external calls",
                    "Create MCP tool registry with validation",
                    "Add retry logic with exponential backoff"
                )
            ))
        }
        
        if (codeMetrics.testCoverage < 0.5) {
            suggestions.add(ClusterSuggestion(
                title = "Increase Test Coverage",
                description = "Current coverage is ${"%.0f".format(codeMetrics.testCoverage * 100)}%",
                actions = listOf(
                    "Add unit tests for core business logic",
                    "Implement integration tests for MCP interactions",
                    "Add contract tests for APIs",
                    "Set up coverage reporting"
                )
            ))
        }
        
        if (structureMetrics.circularDependencies > 0) {
            suggestions.add(ClusterSuggestion(
                title = "Break Circular Dependencies",
                description = "Found ${structureMetrics.circularDependencies} circular dependencies",
                actions = listOf(
                    "Extract shared interfaces",
                    "Apply Dependency Inversion",
                    "Use event-driven architecture"
                )
            ))
        }
        
        return suggestions
    }
    
    data class ClusterHotspot(
        val severity: String,
        val description: String
    )
    
    data class ClusterSuggestion(
        val title: String,
        val description: String,
        val actions: List<String>
    )
}
