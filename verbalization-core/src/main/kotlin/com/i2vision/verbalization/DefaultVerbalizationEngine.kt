/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.verbalization

import com.i2vision.intent.DiscoveryIntent
import com.i2vision.verbalization.feedback.FeedbackStore
import com.i2vision.verbalization.llm.DefaultLlmVerbalizationClient
import com.i2vision.verbalization.llm.LlmVerbalizationClient
import com.i2vision.verbalization.strategy.*
import com.i2vision.vslfc.*
import java.io.File

/**
 * Default implementation of VerbalizationEngine.
 * Orchestrates the verbalization process using configurable strategies.
 * 
 * ## Features:
 * - Two-tier hashing (local + context) for accurate cache invalidation
 * - Strategy fallback chain (LEARNING â†’ MULTI_PASS â†’ INCREMENTAL)
 * - Batch processing for LLM efficiency
 * - Persistent hash storage for incremental sync
 */
class DefaultVerbalizationEngine(
    private val verbalizationStore: VerbalizationStore,
    private val patternMatcher: PatternMatcher = PatternMatcher(),
    private val hashManager: HashManager = HashManager(),
    private val feedbackStore: FeedbackStore = FeedbackStore(),
    private val llmClient: LlmVerbalizationClient = DefaultLlmVerbalizationClient()
) : VerbalizationEngine {

    // Lazy initialization of strategies with proper constructor injection
    private val strategies by lazy {
        mapOf(
            VerbalizationStrategy.INCREMENTAL to IncrementalVerbalizationStrategy(
                patternMatcher, 
                hashManager
            ),
            VerbalizationStrategy.MULTI_PASS to MultiPassVerbalizationStrategy(
                patternMatcher,
                CrossLayerContextProvider(verbalizationStore),
                hashManager
            ),
            VerbalizationStrategy.LEARNING to LearningVerbalizationStrategy(
                patternMatcher,
                llmClient,
                feedbackStore,
                hashManager,
                timeoutMs = 5000,
                batchSize = 100
            )
        )
    }

    override suspend fun verbalize(
        clusterId: String,
        symbols: List<Symbol>,
        strategy: VerbalizationStrategy,
        intent: DiscoveryIntent
    ): List<VerbalizationResult> {

        // Skip hash loading when forcing full verbalization
        if (!intent.forceFullVerbalization) {
            loadHashesForCluster(clusterId)
        } else {
            println("Force full verbalization: skipping hash cache for $clusterId")
        }

        val strategyImpl = strategies[strategy]
            ?: throw IllegalArgumentException("Unsupported strategy: $strategy")

        val results = strategyImpl.verbalize(symbols, intent)

        // Store results
        storeResults(clusterId, results)

        // Only save hashes if we loaded them (don't overwrite with forced results)
        if (!intent.forceFullVerbalization) {
            saveHashesForCluster(clusterId)
        }

        return results
    }

    override fun needsReverbalization(symbol: Symbol, currentHash: String): Boolean {
        return hashManager.hasLocalChanged(symbol, currentHash)
    }

    /**
     * Check if symbol needs re-verbalization including context changes.
     */
    fun needsReverbalizationWithContext(symbol: Symbol, currentLocalHash: String, currentContextHash: String): Boolean {
        return hashManager.hasChanged(symbol, currentLocalHash, currentContextHash)
    }

    override suspend fun getCachedVerbalization(symbol: Symbol): VerbalizationResult? {
        return verbalizationStore.getVerbalization(symbol)
    }

    override fun registerPattern(pattern: VerbalizationPattern) {
        patternMatcher.registerPattern(pattern)
    }

    override fun loadCustomPatterns(configFile: File) {
        if (!configFile.exists()) return

        try {
            patternMatcher.loadCustomPatterns(emptyList())
        } catch (e: Exception) {
            // Log error but don't fail
            println("Warning: Failed to load custom patterns from ${configFile.absolutePath}: ${e.message}")
        }
    }

    /**
     * Load hashes for a cluster from persistent storage.
     */
    private suspend fun loadHashesForCluster(clusterId: String) {
        val localHashes = verbalizationStore.getHashes(clusterId)
        if (localHashes != null) {
            hashManager.loadHashes(localHashes)
        }
        
        val contextHashes = verbalizationStore.getContextHashes(clusterId)
        if (contextHashes != null) {
            hashManager.loadContextHashes(contextHashes)
        }
    }

    /**
     * Save current hashes for a cluster to persistent storage.
     */
    private suspend fun saveHashesForCluster(clusterId: String) {
        val localHashes = hashManager.getAllHashes()
        verbalizationStore.putHashes(clusterId, localHashes)
        
        val contextHashes = hashManager.getAllContextHashes()
        verbalizationStore.putContextHashes(clusterId, contextHashes)
    }

    /**
     * Store verbalization results for a cluster.
     */
    private suspend fun storeResults(clusterId: String, results: List<VerbalizationResult>) {
        verbalizationStore.putVerbalizations(clusterId, results)
    }

    /**
     * Extract cluster ID from file path.
     */
    private fun extractClusterId(filePath: String): String {
        // Simple extraction - in real implementation this would be more sophisticated
        val parts = filePath.split("/")
        return if (parts.size >= 2) "${parts[0]}/${parts[1]}" else "unknown"
    }
}

/**
 * Cross-layer context provider for multi-pass strategy.
 * Integrates with discovery results to provide rich context from Flow, Logic, and Structure layers.
 *
 * ## Cross-Layer Data Sources:
 * - **Structure Layer**: Component dependencies, cohesion metrics from verbalization artifacts
 * - **Flow Layer**: Calling sequences, API call chains from flow discovery
 * - **Logic Layer**: Business rules, invariants, constraints from logic extraction
 *
 * ## Enhanced with ExtendedContext Types:
 * - Uses ExtendedStructureContext for dependency graph analysis
 * - Uses ExtendedFlowContext for calling/called sequence tracking
 * - Uses ExtendedLogicContext for business rule reference mapping
 */
class CrossLayerContextProvider(
    private val verbalizationStore: VerbalizationStore
) : ContextProvider {

    // Cache for extended contexts (populated from discovery artifacts)
    private var cachedStructureContext: ExtendedStructureContext? = null
    private var cachedFlowContext: ExtendedFlowContext? = null
    private var cachedLogicContext: ExtendedLogicContext? = null
    private val crossLayerEnrichment = CrossLayerEnrichment()

    override fun getContext(symbol: Symbol): SymbolContext {
        // Extract dependencies from symbol content
        val dependencies = extractDependencies(symbol.content)
        val relatedSymbols = extractRelatedSymbols(symbol.content)

        // Detect architectural layer
        val layer = detectArchitecturalLayer(symbol.filePath)

        // Get flow information (calling sequences) from Flow layer artifacts
        val callingFlows = getCallingFlows(symbol)

        // Get business rules from Logic layer artifacts
        val businessRules = getBusinessRules(symbol)

        // Get Structure layer dependencies (component relationships)
        val structureDependencies = getStructureDependencies(symbol)

        // Combine all dependencies
        val allDependencies = (dependencies + structureDependencies).distinct()

        return SymbolContext(
            relatedSymbols = relatedSymbols,
            moduleDependencies = allDependencies,
            hasDatabaseAccess = symbol.content.contains("database") ||
                               symbol.content.contains("repository") ||
                               symbol.content.contains("EntityManager") ||
                               symbol.content.contains("@Repository") ||
                               symbol.content.contains("Jdbc") ||
                               symbol.content.contains("Mongo") ||
                               symbol.content.contains("Redis"),
            hasExternalCalls = symbol.content.contains("http") ||
                              symbol.content.contains("client") ||
                              symbol.content.contains("HttpClient") ||
                              symbol.content.contains("RestTemplate") ||
                              symbol.content.contains("Feign") ||
                              symbol.content.contains("WebClient"),
            callingFlows = callingFlows,
            businessRules = businessRules,
            architecturalLayer = layer
        )
    }

    /**
     * Get cross-layer enriched description using extended context types.
     * This method is called during the second pass of MULTI_PASS strategy.
     *
     * ## Example:
     * ```
     * Before: "Authenticates user credentials"
     * After: "Authenticates user credentials via bcrypt. Called by 3 flows: LoginFlow, TokenRefreshFlow, AdminImpersonationFlow. Enforces 2 business rules: token expiry, role validation. Depends on: TokenValidator, UserRegistry."
     * ```
     */
    fun getEnrichedDescription(
        symbolName: String,
        baseDescription: String
    ): String {
        // Get or build extended contexts from discovery artifacts
        val structureCtx = getOrBuildStructureContext()
        val flowCtx = getOrBuildFlowContext()
        val logicCtx = getOrBuildLogicContext()

        // Use CrossLayerEnrichment to add cross-layer data
        val enrichment = crossLayerEnrichment.enrich(
            symbolName = symbolName,
            baseDescription = baseDescription,
            structureContext = structureCtx,
            flowContext = flowCtx,
            logicContext = logicCtx
        )

        return enrichment.enrichedDescription
    }

    /**
     * Build or retrieve cached ExtendedStructureContext.
     * Populates dependency graph from discovery artifacts.
     * 
     * Note: In a full implementation, this would integrate with Structure layer
     * discovery artifacts stored in the verbalization store.
     */
    private fun getOrBuildStructureContext(): ExtendedStructureContext {
        cachedStructureContext?.let { return it }

        // Build from verbalization store artifacts
        // In a full implementation, these would come from Structure layer discovery
        val components = emptyList<ComponentInfo>()
        val dependencies = emptyList<DependencyInfo>()
        
        // Build dependency graph from dependencies
        val edges = dependencies.map { dep: DependencyInfo ->
            DependencyEdge(
                from = dep.from,
                to = dep.to,
                type = DependencyType.INTERNAL,
                strength = when (dep.strength) {
                    com.i2vision.verbalization.DependencyStrength.STRONG -> 
                        com.i2vision.verbalization.DependencyStrength.STRONG
                    com.i2vision.verbalization.DependencyStrength.WEAK -> 
                        com.i2vision.verbalization.DependencyStrength.WEAK
                    else -> com.i2vision.verbalization.DependencyStrength.MODERATE
                }
            )
        }

        val graph = DependencyGraph(
            nodes = (components.map { it.name } + dependencies.flatMap { listOf(it.from, it.to) }).distinct(),
            edges = edges
        )

        val context = ExtendedStructureContext(
            components = components,
            dependencies = dependencies,
            dependencyGraph = graph
        )

        cachedStructureContext = context
        return context
    }

    /**
     * Build or retrieve cached ExtendedFlowContext.
     * Populates calling/called sequences from flow discovery artifacts.
     * 
     * Note: In a full implementation, this would integrate with Flow layer
     * discovery artifacts stored in the verbalization store.
     */
    private fun getOrBuildFlowContext(): ExtendedFlowContext {
        cachedFlowContext?.let { return it }

        // Get sequences from verbalization store
        // In a full implementation, these would come from Flow layer discovery
        val sequences = emptyList<ExtendedSequenceInfo>()

        // Build calledBy and callsTo maps
        val calledBy = mutableMapOf<String, List<String>>()
        val callsTo = mutableMapOf<String, List<String>>()

        // Analyze sequence relationships
        sequences.forEach { seq: ExtendedSequenceInfo ->
            // For each sequence, determine what it calls
            val participants = seq.participants
            if (participants.isNotEmpty()) {
                callsTo[seq.name] = participants
                participants.forEach { participant: String ->
                    calledBy[participant] = (calledBy[participant] ?: emptyList()) + seq.name
                }
            }
        }

        val context = sequences.withCallingSequences(calledBy, callsTo)

        cachedFlowContext = context
        return context
    }

    /**
     * Build or retrieve cached ExtendedLogicContext.
     * Populates business rule references from logic discovery artifacts.
     * 
     * Note: In a full implementation, this would integrate with Logic layer
     * discovery artifacts stored in the verbalization store.
     */
    private fun getOrBuildLogicContext(): ExtendedLogicContext {
        cachedLogicContext?.let { return it }

        // Get invariants and rules from verbalization store
        // In a full implementation, these would come from Logic layer discovery
        val invariants = emptyList<InvariantInfo>()
        val rules = emptyList<RuleInfo>()

        // Build rule references map
        val ruleReferences = mutableMapOf<String, List<RuleReference>>()

        // Parse business rules from invariant descriptions
        invariants.forEach { invariant: InvariantInfo ->
            val ref = RuleReference(
                ruleName = invariant.name,
                description = invariant.description,
                enforcedBy = emptyList(),
                severity = when (invariant.severity) {
                    com.i2vision.verbalization.InvariantSeverity.CRITICAL -> RuleSeverity.CRITICAL
                    com.i2vision.verbalization.InvariantSeverity.HIGH -> RuleSeverity.HIGH
                    com.i2vision.verbalization.InvariantSeverity.LOW -> RuleSeverity.LOW
                    else -> RuleSeverity.MEDIUM
                },
                priority = 0
            )
            // Add reference (would be keyed by enforcing symbol in real impl)
            ruleReferences["enforcer_placeholder"] = (ruleReferences["enforcer_placeholder"] ?: emptyList()) + ref
        }

        val context = ExtendedLogicContext(
            invariants = invariants,
            rules = rules,
            ruleReferences = ruleReferences
        )

        cachedLogicContext = context
        return context
    }

    /**
     * Clear cached contexts (call when artifacts change).
     */
    fun clearCache() {
        cachedStructureContext = null
        cachedFlowContext = null
        cachedLogicContext = null
    }

    /**
     * Extract import dependencies from symbol content.
     */
    private fun extractDependencies(content: String): List<String> {
        val importPattern = Regex("""import\s+([\w.]+)""")
        return importPattern.findAll(content)
            .map { it.groupValues[1] }
            .filter { !it.startsWith("kotlin") && !it.startsWith("java") }
            .take(10)
            .toList()
    }

    /**
     * Extract related symbols from method/class calls in content.
     */
    private fun extractRelatedSymbols(content: String): List<Symbol> {
        // Extract method/class calls from content
        val callPattern = Regex("""(\w+)\s*\(""")
        return callPattern.findAll(content)
            .map { it.groupValues[1] }
            .filter { it.length > 2 && (it.first().isUpperCase() || it.contains(".")) }
            .distinct()
            .take(10)
            .toList()
            .map { name: String ->
                Symbol(
                    name = name,
                    kind = if (name.first().isUpperCase()) SymbolKind.CLASS else SymbolKind.FUNCTION,
                    filePath = "unknown",
                    lineNumber = 0,
                    content = "",
                    metadata = emptyMap()
                )
            }
    }

    /**
     * Detect architectural layer from file path.
     */
    private fun detectArchitecturalLayer(filePath: String): String {
        return when {
            filePath.contains("/controller") || filePath.contains("/web") ||
            filePath.contains("/api") || filePath.contains("/rest") -> "presentation"
            filePath.contains("/service") || filePath.contains("/business") ||
            filePath.contains("/domain") -> "domain"
            filePath.contains("/repository") || filePath.contains("/data") ||
            filePath.contains("/persistence") || filePath.contains("/dao") -> "data"
            filePath.contains("/logic") || filePath.contains("/rules") ||
            filePath.contains("/validation") -> "logic"
            filePath.contains("/flow") || filePath.contains("/workflow") ||
            filePath.contains("/process") -> "flow"
            filePath.contains("/config") || filePath.contains("/configuration") -> "configuration"
            filePath.contains("/util") || filePath.contains("/helper") -> "utility"
            else -> "application"
        }
    }

    /**
     * Get calling flows from Flow layer artifacts.
     */
    private fun getCallingFlows(symbol: Symbol): List<String> {
        // TODO: Integrate with Flow layer artifacts from discovery
        // For now, extract from symbol content annotations/comments
        val flows = mutableListOf<String>()

        // Check for @Flow annotations
        val flowPattern = Regex("""@Flow\(['"]([\w]+)['"]\)""")
        flowPattern.findAll(symbol.content).forEach { match ->
            flows.add(match.groupValues[1])
        }

        // Check for @Sequence annotations (also flow-related)
        val sequencePattern = Regex("""@Sequence\(['"]([\w]+)['"]\)""")
        sequencePattern.findAll(symbol.content).forEach { match ->
            flows.add(match.groupValues[1])
        }

        // Check for Flow: prefix in comments
        val commentFlowPattern = Regex("""//\s*Flow:\s*(\w+)""")
        commentFlowPattern.findAll(symbol.content).forEach { match ->
            flows.add(match.groupValues[1])
        }

        // Extract flow references from method calls (e.g., paymentFlow.execute(), notificationFlow.send())
        val methodCallPattern = Regex("""(\w+Flow)\s*\.\s*(?:execute|send|process|run|call|start|trigger)\s*\(""")
        methodCallPattern.findAll(symbol.content).forEach { match ->
            flows.add(match.groupValues[1])
        }

        // Also capture variable names ending with Flow that have method calls
        val flowVariablePattern = Regex("""(\w+Flow)\s*\.\s*\w+\s*\(""")
        flowVariablePattern.findAll(symbol.content).forEach { match ->
            flows.add(match.groupValues[1])
        }

        // Extract flows from method invocations (e.g., startFlow(flowName))
        val flowInvocationPattern = Regex("""(?:startFlow|callFlow|executeFlow)\s*\(\s*['"]?(\w+)['"]?\s*\)""")
        flowInvocationPattern.findAll(symbol.content).forEach { match ->
            flows.add(match.groupValues[1])
        }

        return flows.distinct()
    }

    /**
     * Get business rules from Logic layer artifacts.
     */
    private fun getBusinessRules(symbol: Symbol): List<String> {
        // TODO: Integrate with Logic layer artifacts from discovery
        // For now, extract from symbol content
        val rules = mutableListOf<String>()

        // Check for @BusinessRule annotations
        val rulePattern = Regex("""@BusinessRule\(['"]([^'"]+)['"]\)""")
        rulePattern.findAll(symbol.content).forEach { match ->
            rules.add(match.groupValues[1])
        }

        // Check for @Constraint annotations (extract message)
        val constraintPattern = Regex("""@Constraint\s*\(\s*(?:message\s*=\s*)?["']([^"']+)["']\s*\)""")
        constraintPattern.findAll(symbol.content).forEach { match ->
            rules.add(match.groupValues[1])
        }

        // Check for require statements with lambda message (Kotlin)
        // Pattern: require(condition) { "message" }
        // Need to handle nested parentheses in condition
        val requireWithMessagePattern = Regex("""require\s*\((?:[^()]|\([^)]*\))+\)\s*\{\s*["']([^"']+)["']\s*\}""")
        requireWithMessagePattern.findAll(symbol.content).forEach { match ->
            rules.add(match.groupValues[1])
        }

        // Check for require statements without message
        val requirePattern = Regex("""require\s*\((?:[^()]|\([^)]*\))*\)""")
        requirePattern.findAll(symbol.content).forEach { match ->
            rules.add("require: ${match.value.substringAfter("require(").substringBeforeLast(")").trim()}")
        }

        // Check for check statements with lambda message
        // Pattern: check(condition) { "message" }
        val checkWithMessagePattern = Regex("""check\s*\((?:[^()]|\([^)]*\))+\)\s*\{\s*["']([^"']+)["']\s*\}""")
        checkWithMessagePattern.findAll(symbol.content).forEach { match ->
            rules.add(match.groupValues[1])
        }

        // Check for check statements without message
        val checkPattern = Regex("""check\s*\((?:[^()]|\([^)]*\))*\)""")
        checkPattern.findAll(symbol.content).forEach { match ->
            rules.add("check: ${match.value.substringAfter("check(").substringBeforeLast(")").trim()}")
        }

        // Check for if-throw validation patterns
        // Pattern: if (condition) throw ExceptionType()
        val ifThrowPattern = Regex("""if\s*\((?:[^()]|\([^)]*\))+\)\s+throw\s+(\w+Exception?)""")
        ifThrowPattern.findAll(symbol.content).forEach { match ->
            rules.add(match.groupValues[1])
        }

        // Check for if-else-throw patterns
        val ifElseThrowPattern = Regex("""if\s*\((?:[^()]|\([^)]*\))+\)\s*\{?\s*throw\s+(\w+Exception?)\s*\)?\s*(?:else\s*\{[^}]*throw\s+(\w+Exception?)\s*\})?""")
        ifElseThrowPattern.findAll(symbol.content).forEach { match ->
            rules.add(match.groupValues[1])
            if (match.groupValues[2].isNotEmpty()) {
                rules.add(match.groupValues[2])
            }
        }

        // Check for assert statements
        val assertPattern = Regex("""assert\s*\(([^)]+)\)""")
        assertPattern.findAll(symbol.content).forEach { match ->
            rules.add("assert: ${match.groupValues[1].trim()}")
        }

        return rules.distinct()
    }

    /**
     * Get structure dependencies from Structure layer artifacts.
     */
    private fun getStructureDependencies(symbol: Symbol): List<String> {
        // TODO: Integrate with Structure layer artifacts from discovery
        // For now, extract from symbol content
        val dependencies = mutableListOf<String>()

        // Extract constructor injection dependencies
        val constructorPattern = Regex("""constructor\(([^()]*)\)""")
        constructorPattern.find(symbol.content)?.let { match ->
            val params = match.groupValues[1].split(",")
            params.forEach { param: String ->
                val typeMatch = Regex("""(\w+):\s*(\w+)""").find(param)
                typeMatch?.let {
                    dependencies.add(it.groupValues[2])
                }
            }
        }

        // Extract property types
        val propertyPattern = Regex("""(?:private|public|protected)?\s*(?:val|var)\s+(\w+):\s*(\w+)""")
        propertyPattern.findAll(symbol.content).forEach { match ->
            dependencies.add(match.groupValues[2])
        }

        return dependencies.distinct()
    }
}
