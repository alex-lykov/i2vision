/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.verbalization

import com.i2vision.arch.signature.StructuralRole

/**
 * Cross-layer enrichment types for MULTI_PASS verbalization strategy.
 * 
 * These types extend the base context objects (StructureContext, FlowContext, LogicContext)
 * with dependency graph, calling sequences, and business rule references to enable
 * cross-layer enrichment during the second pass of MULTI_PASS verbalization.
 * 
 * ## Usage:
 * ```
 * val structureCtx = StructureContext(...).withDependencyGraph(dependencies)
 * val flowCtx = FlowContext(...).withCallingSequences(calledBy, callsTo)
 * val logicCtx = LogicContext(...).withRuleReferences(rules)
 * 
 * val enriched = crossLayerEnrichment.enrich(symbol, structureCtx, flowCtx, logicCtx)
 * ```
 */

// ============ Extended Structure Types ============

/**
 * Dependency edge in the architecture graph.
 */
data class DependencyEdge(
    val from: String,
    val to: String,
    val type: DependencyType = DependencyType.INTERNAL,
    val strength: DependencyStrength = DependencyStrength.MODERATE
)

enum class DependencyType {
    INTERNAL,      // Within the same module/cluster
    EXTERNAL,      // External service/dependency
    INFRASTRUCTURE // Infrastructure (DB, cache, message queue)
}

/**
 * Extended StructureContext with dependency graph support.
 * Enables cross-layer enrichment by tracking component relationships.
 */
data class ExtendedStructureContext(
    val components: List<ComponentInfo> = emptyList(),
    val dependencies: List<DependencyInfo> = emptyList(),
    val dependencyGraph: DependencyGraph = DependencyGraph(),
    val patterns: List<String> = emptyList(),
    val layers: List<String> = emptyList()
) {
    /**
     * Get all components that depend on the given component.
     */
    fun getDependentsOf(componentName: String): List<String> {
        return dependencyGraph.edges
            .filter { it.to == componentName }
            .map { it.from }
    }
    
    /**
     * Get all components that the given component depends on.
     */
    fun getDependenciesOf(componentName: String): List<String> {
        return dependencyGraph.edges
            .filter { it.from == componentName }
            .map { it.to }
    }
    
    /**
     * Get the architectural layer of a component.
     */
    fun getComponentLayer(componentName: String): String? {
        return components.find { it.name == componentName }?.let { component ->
            when (component.role) {
                StructuralRole.CONTROLLER -> "presentation"
                StructuralRole.SERVICE -> "domain"
                StructuralRole.REPOSITORY -> "data"
                StructuralRole.FACTORY, StructuralRole.BUILDER -> "infrastructure"
                else -> "application"
            }
        }
    }
}

/**
 * Dependency graph representation for cross-layer analysis.
 */
data class DependencyGraph(
    val nodes: List<String> = emptyList(),
    val edges: List<DependencyEdge> = emptyList()
) {
    /**
     * Check if a cycle exists in the dependency graph.
     */
    fun hasCycle(): Boolean {
        val visited = mutableSetOf<String>()
        val recursionStack = mutableSetOf<String>()
        
        fun visit(node: String): Boolean {
            if (node in recursionStack) return true
            if (node in visited) return false
            
            visited.add(node)
            recursionStack.add(node)
            
            val neighbors = edges.filter { it.from == node }.map { it.to }
            for (neighbor in neighbors) {
                if (visit(neighbor)) return true
            }
            
            recursionStack.remove(node)
            return false
        }
        
        return nodes.any { visit(it) }
    }
    
    /**
     * Get all nodes reachable from the given start node (transitive closure).
     */
    fun getTransitiveClosure(startNode: String): Set<String> {
        val visited = mutableSetOf<String>()
        val queue = java.util.ArrayDeque<String>()
        queue.add(startNode)
        
        while (queue.isNotEmpty()) {
            val current = queue.poll()
            if (current !in visited) {
                visited.add(current)
                val neighbors = edges.filter { it.from == current }.map { it.to }
                queue.addAll(neighbors.filter { it !in visited })
            }
        }
        
        return visited.filter { it != startNode }.toSet()
    }
    
    /**
     * Get the depth of a node in the graph (distance from leaf).
     */
    fun getDepth(node: String): Int {
        val reverseEdges = edges.groupBy { it.to }
        var depth = 0
        var currentNodes = listOf(node)
        
        while (true) {
            val predecessors = currentNodes.flatMap { n ->
                reverseEdges[n]?.map { it.from } ?: emptyList()
            }.filter { it !in (1..depth).map { d -> "" } }.distinct()
            
            if (predecessors.isEmpty()) break
            depth++
            currentNodes = predecessors
        }
        
        return depth
    }
}

// ============ Extended Flow Types ============

/**
 * Extended sequence information with calling relationships.
 */
data class ExtendedSequenceInfo(
    val name: String,
    val steps: List<SequenceStep> = emptyList(),
    val participants: List<String> = emptyList(),
    val description: String = "",
    val calledBy: List<String> = emptyList(),
    val callsTo: List<String> = emptyList()
)

/**
 * Extended FlowContext with calling sequence support.
 * Tracks who calls what in the system.
 */
data class ExtendedFlowContext(
    val sequences: List<ExtendedSequenceInfo> = emptyList(),
    val apiEndpoints: List<ApiEndpointInfo> = emptyList(),
    val interactions: List<InteractionInfo> = emptyList()
) {
    /**
     * Get flows that are called by the given flow (i.e., flows the given flow invokes).
     */
    fun getFlowsCalling(flowName: String): List<String> {
        return sequences.filter { it.name == flowName }.flatMap { it.callsTo }
    }

    /**
     * Get flows that call the given flow (i.e., flows that invoke the given flow).
     */
    fun getFlowsCalledBy(flowName: String): List<String> {
        return sequences.filter { it.name == flowName }.flatMap { it.calledBy }
    }
    
    /**
     * Get the call depth of a flow.
     */
    fun getCallDepth(flowName: String): Int {
        var depth = 0
        var currentFlows = listOf(flowName)
        val visited = mutableSetOf(flowName)
        
        while (true) {
            val callers = currentFlows.flatMap { flow ->
                sequences.filter { it.name == flow }.flatMap { it.calledBy }
            }.filter { it !in visited }.distinct()
            
            if (callers.isEmpty()) break
            depth++
            visited.addAll(callers)
            currentFlows = callers
        }
        
        return depth
    }
    
    /**
     * Check if a flow is part of a critical path.
     */
    fun isOnCriticalPath(flowName: String, threshold: Int = 3): Boolean {
        val callDepth = getCallDepth(flowName)
        val cascadeDepth = getCascadeDepth(flowName)
        return callDepth >= threshold || cascadeDepth >= threshold
    }
    
    /**
     * Get cascade depth (how deep the flow calls).
     */
    private fun getCascadeDepth(flowName: String): Int {
        var depth = 0
        var currentFlows = listOf(flowName)
        val visited = mutableSetOf(flowName)
        
        while (true) {
            val callees = currentFlows.flatMap { flow ->
                sequences.filter { it.name == flow }.flatMap { it.callsTo }
            }.filter { it !in visited }.distinct()
            
            if (callees.isEmpty()) break
            depth++
            visited.addAll(callees)
            currentFlows = callees
        }
        
        return depth
    }
}

// ============ Extended Logic Types ============

/**
 * Reference to a business rule or invariant.
 */
data class RuleReference(
    val ruleName: String,
    val description: String,
    val enforcedBy: List<String> = emptyList(),
    val severity: RuleSeverity = RuleSeverity.MEDIUM,
    val priority: Int = 0
)

/**
 * Severity levels for rule violations.
 */
enum class RuleSeverity {
    CRITICAL,
    HIGH,
    MEDIUM,
    LOW
}

/**
 * Extended LogicContext with rule reference support.
 * Tracks which symbols enforce which business rules.
 */
data class ExtendedLogicContext(
    val invariants: List<InvariantInfo> = emptyList(),
    val rules: List<RuleInfo> = emptyList(),
    val ruleReferences: Map<String, List<RuleReference>> = emptyMap()
) {
    /**
     * Get all rules enforced by a specific symbol.
     */
    fun getRulesEnforcedBy(symbolName: String): List<RuleReference> {
        return ruleReferences[symbolName] ?: emptyList()
    }
    
    /**
     * Get all symbols that enforce a specific rule.
     */
    fun getEnforcersOfRule(ruleName: String): List<String> {
        return ruleReferences.filter { (_, rules) ->
            rules.any { it.ruleName == ruleName }
        }.keys.toList()
    }
    
    /**
     * Get all rules with a specific severity.
     */
    fun getRulesBySeverity(severity: RuleSeverity): List<RuleReference> {
        return ruleReferences.values.flatten().filter { it.severity == severity }
    }
    
    /**
     * Check if a symbol enforces any critical rules.
     */
    fun hasCriticalRules(symbolName: String): Boolean {
        return getRulesEnforcedBy(symbolName).any { it.severity == RuleSeverity.CRITICAL }
    }
    
    /**
     * Get the total rule count by severity.
     */
    fun getRuleCountBySeverity(): Map<RuleSeverity, Int> {
        return ruleReferences.values.flatten()
            .groupBy { it.severity }
            .mapValues { it.value.size }
    }
}

// ============ Cross-Layer Enrichment Result ============

/**
 * Result of cross-layer enrichment.
 */
data class CrossLayerEnrichmentResult(
    val enrichedDescription: String,
    val isEnriched: Boolean,
    val addedFlowInfo: List<String> = emptyList(),
    val addedRuleInfo: List<String> = emptyList(),
    val addedDependencyInfo: List<String> = emptyList(),
    val enrichmentCount: Int = 0
)

// ============ Cross-Layer Enrichment Service ============

/**
 * Service that performs cross-layer enrichment during MULTI_PASS verbalization.
 * 
 * Takes base descriptions from first pass and enriches them with:
 * - Calling sequence information from Flow layer
 * - Business rule references from Logic layer
 * - Component dependencies from Structure layer
 */
class CrossLayerEnrichment {
    
    /**
     * Enrich a symbol description with cross-layer information.
     * 
     * @param symbolName Name of the symbol being verbalized
     * @param baseDescription Description from first pass (pattern matching)
     * @param structureContext Optional structure layer context
     * @param flowContext Optional flow layer context  
     * @param logicContext Optional logic layer context
     * @return Enriched description with cross-layer annotations
     */
    fun enrich(
        symbolName: String,
        baseDescription: String,
        structureContext: ExtendedStructureContext? = null,
        flowContext: ExtendedFlowContext? = null,
        logicContext: ExtendedLogicContext? = null
    ): CrossLayerEnrichmentResult {
        val enrichment = mutableListOf<String>()
        val flowInfo = mutableListOf<String>()
        val ruleInfo = mutableListOf<String>()
        val dependencyInfo = mutableListOf<String>()
        
        // Add flow layer information
        flowContext?.let { ctx ->
            // getFlowsCalling returns flows that the given flow calls (callees)
            // getFlowsCalledBy returns flows that call the given flow (callers)
            val callees = ctx.getFlowsCalling(symbolName)
            val callers = ctx.getFlowsCalledBy(symbolName)

            if (callers.isNotEmpty()) {
                val flowEntry = "Called by ${callers.size} flow(s): ${callers.joinToString(", ")}"
                flowInfo.add(flowEntry)
                enrichment.add(flowEntry)
            }

            if (callees.isNotEmpty()) {
                val flowEntry = "Calls ${callees.size} flow(s): ${callees.joinToString(", ")}"
                flowInfo.add(flowEntry)
                enrichment.add(flowEntry)
            }
        }
        
        // Add logic layer information
        logicContext?.let { ctx ->
            val rules = ctx.getRulesEnforcedBy(symbolName)
            if (rules.isNotEmpty()) {
                val ruleEntry = buildString {
                    append("Enforces ${rules.size} business rule(s): ${rules.joinToString(", ") { it.ruleName }}")
                    val criticalRules = rules.filter { it.severity == RuleSeverity.CRITICAL }
                    if (criticalRules.isNotEmpty()) {
                        append(" (Critical: ${criticalRules.joinToString(", ") { it.ruleName }})")
                    }
                }
                ruleInfo.add(ruleEntry)
                enrichment.add(ruleEntry)
            }
        }
        
        // Add structure layer information
        structureContext?.let { ctx ->
            val dependencies = ctx.getDependenciesOf(symbolName)
            val dependents = ctx.getDependentsOf(symbolName)
            
            if (dependencies.isNotEmpty()) {
                val depEntry = "Depends on: ${dependencies.joinToString(", ")}"
                dependencyInfo.add(depEntry)
                enrichment.add(depEntry)
            }
            
            if (dependents.isNotEmpty()) {
                val depEntry = "Used by: ${dependents.joinToString(", ")}"
                dependencyInfo.add(depEntry)
                enrichment.add(depEntry)
            }
        }
        
        val enrichedDescription = if (enrichment.isNotEmpty()) {
            "$baseDescription. ${enrichment.joinToString(" ")}"
        } else {
            baseDescription
        }
        
        return CrossLayerEnrichmentResult(
            enrichedDescription = enrichedDescription,
            isEnriched = enrichment.isNotEmpty(),
            addedFlowInfo = flowInfo,
            addedRuleInfo = ruleInfo,
            addedDependencyInfo = dependencyInfo,
            enrichmentCount = enrichment.size
        )
    }
    
    /**
     * Generate a before/after example for documentation.
     */
    fun generateBeforeAfterExample(
        symbolName: String,
        baseDescription: String,
        structureContext: ExtendedStructureContext? = null,
        flowContext: ExtendedFlowContext? = null,
        logicContext: ExtendedLogicContext? = null
    ): String {
        val enrichment = enrich(symbolName, baseDescription, structureContext, flowContext, logicContext)
        
        return buildString {
            appendLine("## Cross-Layer Enrichment Example for `$symbolName`")
            appendLine()
            appendLine("### Before (first pass)")
            appendLine("```")
            appendLine(baseDescription)
            appendLine("```")
            appendLine()
            appendLine("### After (cross-layer enrichment)")
            appendLine("```")
            appendLine(enrichment.enrichedDescription)
            appendLine("```")
            appendLine()
            if (enrichment.isEnriched) {
                appendLine("### Enrichment Details")
                appendLine("- **Flow Layer**: ${enrichment.addedFlowInfo.size} annotation(s)")
                if (enrichment.addedFlowInfo.isNotEmpty()) {
                    enrichment.addedFlowInfo.forEach { appendLine("  - $it") }
                }
                appendLine("- **Logic Layer**: ${enrichment.addedRuleInfo.size} annotation(s)")
                if (enrichment.addedRuleInfo.isNotEmpty()) {
                    enrichment.addedRuleInfo.forEach { appendLine("  - $it") }
                }
                appendLine("- **Structure Layer**: ${enrichment.addedDependencyInfo.size} annotation(s)")
                if (enrichment.addedDependencyInfo.isNotEmpty()) {
                    enrichment.addedDependencyInfo.forEach { appendLine("  - $it") }
                }
            }
        }
    }
}

// ============ Extension Functions ============

/**
 * Add edges to ExtendedStructureContext.
 */
fun ExtendedStructureContext.withEdges(edges: List<DependencyEdge>): ExtendedStructureContext {
    val newNodes = (dependencyGraph.nodes + edges.flatMap { listOf(it.from, it.to) }).distinct()
    return copy(
        dependencyGraph = dependencyGraph.copy(
            nodes = newNodes,
            edges = dependencyGraph.edges + edges
        )
    )
}

/**
 * Add rule reference to ExtendedLogicContext.
 */
fun ExtendedLogicContext.withRule(symbolName: String, rule: RuleReference): ExtendedLogicContext {
    val existingRules = ruleReferences[symbolName] ?: emptyList()
    return copy(
        ruleReferences = ruleReferences + (symbolName to (existingRules + rule))
    )
}

/**
 * Convert SequenceInfo to ExtendedSequenceInfo with calling relationships.
 */
fun List<ExtendedSequenceInfo>.withCallingSequences(
    calledBy: Map<String, List<String>> = emptyMap(),
    callsTo: Map<String, List<String>> = emptyMap()
): ExtendedFlowContext {
    return ExtendedFlowContext(
        sequences = this.map { seq ->
            seq.copy(
                calledBy = calledBy[seq.name] ?: seq.calledBy,
                callsTo = callsTo[seq.name] ?: seq.callsTo
            )
        }
    )
}

/**
 * Add edges to DependencyGraph.
 */
fun DependencyGraph.withEdge(from: String, to: String, type: DependencyType = DependencyType.INTERNAL): DependencyGraph {
    return copy(
        nodes = (nodes + from + to).distinct(),
        edges = edges + DependencyEdge(from, to, type)
    )
}

/**
 * Add component to ExtendedStructureContext.
 */
fun ExtendedStructureContext.withComponent(component: ComponentInfo): ExtendedStructureContext {
    return copy(components = components + component)
}

/**
 * Add invariant to ExtendedLogicContext.
 */
fun ExtendedLogicContext.withInvariant(invariant: InvariantInfo): ExtendedLogicContext {
    return copy(invariants = invariants + invariant)
}