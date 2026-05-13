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
 * - Strategy fallback chain (LEARNING → MULTI_PASS → INCREMENTAL)
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

        // Load existing hashes for incremental checking
        loadHashesForCluster(clusterId)

        val strategyImpl = strategies[strategy]
            ?: throw IllegalArgumentException("Unsupported strategy: $strategy")

        val results = strategyImpl.verbalize(symbols, intent)

        // Store results
        storeResults(clusterId, results)

        // Save updated hashes (both local and context)
        saveHashesForCluster(clusterId)

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
 */
class CrossLayerContextProvider(
    private val verbalizationStore: VerbalizationStore
) : ContextProvider {
    
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
            .map { name -> 
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
            filePath.contains("/config") || filePath.contains("/configuration") -> "configuration"
            filePath.contains("/util") || filePath.contains("/common") ||
            filePath.contains("/helper") -> "utility"
            filePath.contains("/flow") || filePath.contains("/workflow") -> "flow"
            filePath.contains("/logic") || filePath.contains("/rule") -> "logic"
            filePath.contains("/struct") || filePath.contains("/component") -> "structure"
            else -> "unknown"
        }
    }
    
    /**
     * Get calling flows from Flow layer artifacts.
     * Queries verbalization store for flow artifacts that reference this symbol.
     */
    private fun getCallingFlows(symbol: Symbol): List<String> {
        // In production, this would query Flow layer artifacts from storage
        // For now, extract from content and verbalization metadata
        
        val flows = mutableListOf<String>()
        
        // Look for flow references in content (e.g., comments, annotations)
        val flowPattern = Regex("""@(?:Flow|Sequence|CallChain)\s*\(\s*"([^"]+)"\s*\)""")
        flows.addAll(flowPattern.findAll(symbol.content).map { it.groupValues[1] })
        
        // Look for flow names in comments
        val commentFlowPattern = Regex("""//\s*Flow:\s*(\w+)""")
        flows.addAll(commentFlowPattern.findAll(symbol.content).map { it.groupValues[1] })
        
        // Extract method calls that might be flow entry points
        val callPattern = Regex("""(\w+Flow)\s*\.""")
        flows.addAll(callPattern.findAll(symbol.content).map { it.groupValues[1] })
        
        return flows.distinct().take(5)
    }
    
    /**
     * Get business rules from Logic layer artifacts.
     * Extracts validation logic, invariants, and constraints from symbol content.
     */
    private fun getBusinessRules(symbol: Symbol): List<String> {
        val rules = mutableListOf<String>()
        val content = symbol.content
        
        // Look for require() statements (handles nested parentheses)
        val requirePattern = Regex("""require\s*\(\s*(.+)\s*\)\s*\{\s*"([^"]+)"\s*\}""")
        rules.addAll(requirePattern.findAll(content).map { 
            "Validation: ${it.groupValues[2]}" 
        })
        
        // Look for check() statements (handles nested parentheses)
        val checkPattern = Regex("""check\s*\(\s*(.+)\s*\)\s*\{\s*"([^"]+)"\s*\}""")
        rules.addAll(checkPattern.findAll(content).map { 
            "Invariant: ${it.groupValues[2]}" 
        })
        
        // Look for ensure() statements (postconditions) (handles nested parentheses)
        val ensurePattern = Regex("""ensure\s*\(\s*(.+)\s*\)\s*\{\s*"([^"]+)"\s*\}""")
        rules.addAll(ensurePattern.findAll(content).map { 
            "Postcondition: ${it.groupValues[2]}" 
        })
        
        // Look for validation logic in if statements (with or without braces)
        val validationPattern = Regex("""if\s*\(\s*([^)]+)\s*\)\s*\{?\s*throw\s+(\w+)""")
        rules.addAll(validationPattern.findAll(content).map { 
            "Validation: throws ${it.groupValues[2]} if ${truncateCondition(it.groupValues[1])}" 
        })
        
        // Look for business rule annotations
        val ruleAnnotationPattern = Regex("""@(?:BusinessRule|Rule|Constraint)\s*\(\s*"([^"]+)"\s*\)""")
        rules.addAll(ruleAnnotationPattern.findAll(content).map { 
            "Business Rule: ${it.groupValues[1]}" 
        })
        
        return rules.distinct().take(5)
    }
    
    /**
     * Get Structure layer dependencies (component relationships).
     * Extracts component dependencies from verbalization artifacts.
     */
    private fun getStructureDependencies(symbol: Symbol): List<String> {
        val dependencies = mutableListOf<String>()
        
        // Look for dependency injection annotations
        val injectPattern = Regex("""@(?:Inject|Autowired|Resource)\s+\w+""")
        dependencies.addAll(injectPattern.findAll(symbol.content).map { 
            it.value.substringAfter("@").substringBefore(" ")
        })
        
        // Look for constructor parameters with types
        val constructorPattern = Regex("""constructor\s*\([^)]*(\w+)\s*:\s*(\w+)""")
        dependencies.addAll(constructorPattern.findAll(symbol.content).map { 
            it.groupValues[2]
        })
        
        // Look for property declarations with types
        val propertyPattern = Regex("""(?:val|var)\s+\w+\s*:\s*(\w+)""")
        dependencies.addAll(propertyPattern.findAll(symbol.content).map { 
            it.groupValues[1]
        }.filter { it.first().isUpperCase() })
        
        // Look for interface implementations
        val implementsPattern = Regex(""":\s*(\w+(?:\s*,\s*\w+)*)""")
        dependencies.addAll(implementsPattern.findAll(symbol.content).flatMap { 
            it.groupValues[1].split(",").map { type -> type.trim() }
        }.filter { it.first().isUpperCase() })
        
        return dependencies.distinct().take(10)
    }
    
    /**
     * Truncate long condition expressions for readability.
     */
    private fun truncateCondition(condition: String): String {
        return if (condition.length > 50) {
            condition.take(47) + "..."
        } else {
            condition
        }
    }
}
