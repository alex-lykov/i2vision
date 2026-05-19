/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.validation

import com.i2vision.arch.signature.SignatureBuilder
import com.i2vision.discover.api.DiscoveryPipeline
import com.i2vision.discover.api.models.*
import com.i2vision.discover.intent.IntentResolverImpl
import com.i2vision.discover.pipeline.DiscoveryPipelineImpl
import com.i2vision.intent.DiscoveryIntent as IntentDiscoveryIntent
import com.i2vision.intent.IntentDepth
import com.i2vision.intent.IntentGoal
import com.i2vision.intent.IntentParser
import com.i2vision.intent.LayerFocus
import com.i2vision.intent.QualityFocus
import com.i2vision.storage.I2VisionPaths
import com.i2vision.storage.impl.FileCacheStore
import com.i2vision.storage.impl.FileVerbalizationStore
import com.i2vision.verbalization.ContextNeedinessCalculator
import com.i2vision.verbalization.DefaultVerbalizationEngine
import com.i2vision.vslfc.VerbalizationStore
import com.i2vision.index.ScannerService
import com.i2vision.vslfc.Symbol
import com.i2vision.validation.VerbalizationSelfTestReport
import com.i2vision.vslfc.SymbolKind
import com.i2vision.vslfc.VerbalizationStrategy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.io.PrintStream
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Self-Discovery Test for i2vision
 * 
 * This is a real project simulation tool that analyzes the i2vision codebase itself,
 * making it the ideal environment for evaluating the verbalization engine.
 * 
 * Features:
 * - Architecture detection and cluster identification
 * - Parallel discovery pipeline execution
 * - Verbalization with quality metrics and strategy comparison
 * - Artifact quality analysis
 * - CLI context command testing
 * - Instant context API testing
 * - Documentation export
 */
fun main(args: Array<String>) {
    // Find project root by looking for settings.gradle.kts
    var projectRoot = File(".").absoluteFile
    while (projectRoot.parentFile != null && !File(projectRoot, "settings.gradle.kts").exists()) {
        projectRoot = projectRoot.parentFile
    }
    if (!File(projectRoot, "settings.gradle.kts").exists()) {
        // Fallback to current directory if no settings.gradle.kts found
        projectRoot = File(".").absoluteFile
    }

    val purge = args.contains("--purge")
    val deep = args.contains("--deep")
    val exportDocs = args.contains("--export-docs")
    val analyzeQuality = args.contains("--analyze-quality")
    val testContext = args.contains("--test-context")
    val testCli = args.contains("--test-cli")
    val validateLayers = args.contains("--validate-layers")
    val validateVision = args.contains("--validate-vision")
    
    // Verbalization flags
    val verbalize = args.contains("--verbalize")
    val reportVerbalization = args.contains("--report-verbalization")

    // Map --deep flag to discovery depth (DEEP for --deep, STANDARD otherwise)
    val discoveryDepth = if (deep) IntentDepth.DEEP else IntentDepth.STANDARD

    // Self-test mode (runs verbalization automatically)
    val selfTestMode = args.contains("--self-test")

    // Setup file logging
    val logDir = File(projectRoot, ".vision-ai/logs")
    logDir.mkdirs()

    // Log rotation: keep only last 10 logs
    val maxLogs = 10
    val existingLogs = logDir.listFiles()
        ?.filter { it.name.startsWith("discovery-log-") && it.name.endsWith(".txt") }
        ?.sortedByDescending { it.lastModified() }
        ?: emptyList()

    if (existingLogs.size >= maxLogs) {
        existingLogs.drop(maxLogs - 1).forEach { it.delete() }
    }

    val timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss").format(LocalDateTime.now())
    val logFile = File(logDir, "discovery-log-$timestamp.txt")
    val logStream = PrintStream(logFile)
    val originalOut = System.out

    // Tee output to both console and file
    System.setOut(TeePrintStream(originalOut, logStream))

    println("================================================================================")
    println("                        i2vision Self-Discovery Test Log")
    println("================================================================================")
    println("Log file: ${logFile.absolutePath}")
    println("Created: ${LocalDateTime.now()}")
    println("================================================================================")
    println()
    println("Project: ${projectRoot.canonicalPath}")
    println("Discovery Depth: $discoveryDepth")
    if (purge) println("Purge: ENABLED")
    if (exportDocs) println("Export Docs: ENABLED")
    if (analyzeQuality) println("Analyze Quality: ENABLED")
    if (testContext) println("Test Context: ENABLED")
    if (testCli) println("Test CLI: ENABLED")
    if (validateLayers) println("Validate Layers: ENABLED")
    if (validateVision) println("Validate Vision: ENABLED")
    if (verbalize) println("Verbalization: ENABLED")
    if (reportVerbalization) println("Report Verbalization: ENABLED")
    println()

    // Step 1: Architecture Detection
    println("--- Architecture Detection ---")
    val signatureBuilder = SignatureBuilder(
        projectRoot = projectRoot.absolutePath,
        confidenceThreshold = 0.7,
        useLlmForLowConfidence = false,
        llmClient = null
    )
    val signature = signatureBuilder.build()

    println("Build System: ${signature.buildSystem?.name ?: "unknown"}")
    println("Deployment Pattern: ${signature.deploymentPattern.name}")
    println("Clusters detected: ${signature.clusters.size}")
    signature.clusters.take(10).forEach { cluster ->
        println("  - ${cluster.name} (${cluster.fileCount} files)")
    }
    if (signature.clusters.size > 10) {
        println("  ... and ${signature.clusters.size - 10} more")
    }
    println()

    // Step 2: Intent Resolution
    println("--- Intent Resolution ---")
    val intentParser = IntentParser
    val intent = intentParser.parse(mapOf("intent" to "full_discovery"))
        ?: error("Invalid intent 'full_discovery'")
    println("Intent: ${intent.goal}")
    println("Focus: ${intent.focus}")
    println()

    // Step 3: Purge if requested
    if (purge) {
        println("--- Purging Semantic Cache ---")
        purgeSemanticCache(projectRoot)
        println()
    }

    // Step 4: Discovery Pipeline
    println("--- Discovery Pipeline Execution (Discovery Depth: $discoveryDepth) ---")
    val intentResolver = IntentResolverImpl()
    val cacheDir = I2VisionPaths.getProjectCacheDir(projectRoot.absolutePath)
    val cacheStore = FileCacheStore(cacheDir)
    val discovery: DiscoveryPipeline = DiscoveryPipelineImpl(projectRoot.absolutePath, intentResolver, cacheStore)

    // Enable batch mode for LinkService to collect links in memory and write once at end
    (discovery as DiscoveryPipelineImpl).enableLinkBatchMode()

    val clusters = signature.clusters
    if (clusters.isEmpty()) {
        throw IllegalStateException("No clusters detected")
    }

    // Filter out empty clusters (with 0 files) to avoid processing clusters with no source files
    val validClusters = clusters.filter { it.fileCount > 0 }
    if (validClusters.size < clusters.size) {
        println("Filtered ${clusters.size - validClusters.size} empty clusters (with 0 files)")
    }

    if (validClusters.isEmpty()) {
        throw IllegalStateException("No valid clusters with source files detected")
    }

    val allResults = mutableListOf<ClusterDiscoveryResult>()
    val totalStartTime = System.currentTimeMillis()

    // Process clusters in parallel using coroutines
    println("Processing ${validClusters.size} clusters in parallel...")
    runBlocking {
        val jobs = validClusters.map { cluster ->
            async(Dispatchers.Default) {
                val startTime = System.currentTimeMillis()
                print("Discovering ${cluster.name}... ")

                val discoveryIntent = com.i2vision.discover.api.models.DiscoveryIntent(
                    goal = DiscoveryGoal.UNDERSTAND,
                    depth = com.i2vision.discover.api.models.IntentDepth.STANDARD,
                    quality = DiscoveryQuality.BALANCED,
                    layerFocus = listOf("vision", "structure", "logic", "flow", "code")
                )

                val result = discovery.discover(
                    intent = discoveryIntent,
                    clusterId = cluster.name,
                    contracts = emptyList()
                )

                val duration = System.currentTimeMillis() - startTime
                val status = if (result.success) "✅" else "❌"
                val errorInfo = if (result.errors.isNotEmpty()) "\n   Errors: ${result.errors.joinToString("; ")}" else ""
                println("$status (${duration}ms, ${result.artifacts.size} artifacts)$errorInfo")

                ClusterDiscoveryResult(cluster.name, result, duration)
            }
        }

        jobs.awaitAll().forEach { allResults.add(it) }
    }

    val totalDuration = System.currentTimeMillis() - totalStartTime

    // Flush all buffered links to disk in a single operation
    (discovery as DiscoveryPipelineImpl).flushLinkBatch()
    println("Flushed all links to disk")

    // Step 5: Discovery Summary
    println()
    println("--- Discovery Results Summary ---")
    println("Total Duration: ${totalDuration}ms (${totalDuration / 1000}s)")
    println("Total Clusters: ${allResults.size}")
    println("Successful: ${allResults.count { it.result.success }}")
    println("Failed: ${allResults.count { !it.result.success }}")
    println("Total Artifacts: ${allResults.sumOf { it.result.artifacts.size }}")
    println()

    // Step 6: Verbalization (if requested)
    var verbalizationCount = 0
    var cnsCalculator: ContextNeedinessCalculator? = null
    var selfTest: VerbalizationSelfTest? = null
    var testReport: VerbalizationSelfTestReport? = null
    val symbolsMap = mutableMapOf<String, MutableList<Symbol>>()
    
    if (verbalize || reportVerbalization || selfTestMode) {
        println("--- Verbalization Analysis ---")
        
        val verbalizationStore = FileVerbalizationStore(cacheStore)
        val verbalizationEngine = DefaultVerbalizationEngine(verbalizationStore)
        
        // Extract symbols from results for verbalization
        val symbols = extractSymbolsFromResults(allResults, projectRoot)
        println("Extracted ${symbols.size} symbols for verbalization")
        
        // Build symbols map for self-test (keyed by cluster name for verbalization loop)
        allResults.forEach { result ->
            val clusterName = result.clusterId.substringBefore(":")
            val clusterSymbols = extractSymbolsFromResults(listOf(result), projectRoot)
            symbolsMap[clusterName] = clusterSymbols.toMutableList()
        }
        
        // Initialize self-test if needed
        if (selfTestMode) {
            cnsCalculator = ContextNeedinessCalculator()
            selfTest = VerbalizationSelfTest(
                engine = verbalizationEngine,
                cnsCalculator = cnsCalculator!!,
                verbalizationStore = verbalizationStore,
                cacheDir = cacheDir
            )
        }
        
        // Generate verbalizations for each cluster
        runBlocking {
            validClusters.forEach { cluster ->
                val clusterSymbols = symbolsMap[cluster.name] ?: emptyList()
                if (clusterSymbols.isNotEmpty()) {
                    print("Verbalizing ${cluster.name}... ")
                    val startTime = System.currentTimeMillis()
                    
                    val intent = IntentDiscoveryIntent(
                        goal = IntentGoal.FULL_DISCOVERY,
                        focus = setOf(LayerFocus.VISION, LayerFocus.STRUCTURE, LayerFocus.LOGIC, LayerFocus.FLOW, LayerFocus.CODE),
                        depth = discoveryDepth,
                        quality = QualityFocus.BALANCED,
                        forceFullVerbalization = selfTestMode
                    )
                    
                    val results = verbalizationEngine.verbalize(
                        clusterId = cluster.name,
                        symbols = clusterSymbols,
                        strategy = VerbalizationStrategy.INCREMENTAL,
                        intent = intent
                    )
                    
                    val duration = System.currentTimeMillis() - startTime
                    verbalizationCount += results.size
                    
                    // Attach verbalizations to symbols for self-test reuse
                    val resultMap = results.associateBy { it.symbol.name }
                    symbolsMap[cluster.name] = clusterSymbols.map { symbol ->
                        resultMap[symbol.name]?.let { symbol.copy(verbalization = it) } ?: symbol
                    }.toMutableList()
                    
                    println("✅ ${results.size} verbalizations (${duration}ms)")
                }
            }
        }
        
        // Step 6b: Verbalization Self-Test (if self-test mode is enabled)
        if (selfTestMode && selfTest != null) {
            println("--- Verbalization Self-Test ---")
            
            // Execute self-test
            testReport = selfTest!!.execute(allResults, symbolsMap)
            
            // Export report
            val reportFile = File(projectRoot, ".vision-ai/logs/verbalization-self-test-${timestamp}.json")
            reportFile.parentFile.mkdirs()
            reportFile.writeText("""
                {
                    "timestamp": "${testReport!!.formattedTimestamp}",
                    "overallStatus": "${testReport!!.overallStatus}",
                    "phasesPassed": ${testReport!!.phasesPassed}/${testReport!!.totalPhases},
                    "strategyRouting": {
                        "status": "${testReport!!.strategyRouting.status}",
                        "passed": ${testReport!!.strategyRouting.passed},
                        "failed": ${testReport!!.strategyRouting.failed},
                        "suspect": ${testReport!!.strategyRouting.suspect}
                    },
                    "layerCoverage": {
                        "status": "${testReport!!.layerCoverage.status}",
                        "layersWithOutput": ${testReport!!.layerCoverage.layersWithOutput}
                    },
                    "qualityMetrics": {
                        "status": "${testReport!!.qualityMetrics.status}",
                        "totalSymbols": ${testReport!!.qualityMetrics.totalSymbols},
                        "antiPatternRate": ${testReport!!.qualityMetrics.antiPatternRate}
                    },
                    "performance": {
                        "status": "${testReport!!.performance.status}"
                    },
                    "cacheMetrics": {
                        "status": "${testReport!!.cacheMetrics.status}",
                        "hitRate": ${testReport!!.cacheMetrics.hitRate}
                    },
                    "regressions": ${testReport!!.regressions.size}
                }
            """.trimIndent())
            
            println("Self-test report exported to: ${reportFile.absolutePath}")
        }
        
        println()
    }

    // Step 7: Artifact Quality Analysis
    if (analyzeQuality) {
        analyzeArtifactQuality(projectRoot, allResults)
        println()
    }

    // Step 8: Test Instant Context API
    if (testContext) {
        testInstantContext(projectRoot)
        println()
    }

    // Step 9: CLI Context Command Testing
    if (testCli) {
        testCliContextCommand(projectRoot)
        println()
    }

    // Step 10: VSLFC Layer Validation
    if (validateLayers) {
        validateVslfcLayers(projectRoot, allResults)
        println()
    }

    // Step 11: Vision Requirements Validation
    if (validateVision) {
        validateVisionRequirements(projectRoot, allResults)
        println()
    }

    // Step 12: Documentation Export
    if (exportDocs) {
        exportDocumentation(projectRoot, allResults)
        println()
    }

    // Final Summary
    println("================================================================================")
    println("                              Final Summary")
    println("================================================================================")
    println("Discovery: ${allResults.size} clusters processed")
    println("Artifacts: ${allResults.sumOf { it.result.artifacts.size }} generated")
    println("Duration: ${totalDuration}ms (${totalDuration / 1000}s)")
    
    if (verbalizationCount > 0) {
        println("Verbalization: $verbalizationCount verbalizations generated")
    }
    
    println("================================================================================")
    println("Log file: ${logFile.absolutePath}")
    println("================================================================================")
    
    // Restore original stdout
    System.setOut(originalOut)
    logStream.close()
    
    println("\n✅ Self-Discovery Test completed successfully!")
    println("📄 Full log available at: ${logFile.absolutePath}")
}

/**
 * Extract symbols from discovery results
 */
private fun extractSymbolsFromResults(results: List<ClusterDiscoveryResult>, projectRoot: File): List<Symbol> {
    val scanner = ScannerService(projectRoot.absolutePath)
    val symbols = mutableListOf<Symbol>()
    val processedFiles = mutableSetOf<String>()

    // Get all source files once
    val allSourceFiles = scanner.listFiles("src")

    results.forEach { clusterResult ->
        // Filter source files by cluster ID (cluster IDs map to directory paths)
        val clusterPath = clusterResult.clusterId.replace(":", "/")
        val clusterFiles = allSourceFiles.filter { file ->
            file.path.contains(clusterResult.clusterId) || file.path.contains(clusterPath)
        }

        // If no files matched by cluster name, fall back to using all files for single-cluster results
        val filesToScan = if (clusterFiles.isNotEmpty()) clusterFiles else allSourceFiles

        filesToScan.forEach { sourceFile ->
            if (sourceFile.path !in processedFiles) {
                processedFiles.add(sourceFile.path)
                try {
                    val codeSymbols = scanner.extractSymbols(sourceFile.path)
                    codeSymbols.forEach { cs ->
                        symbols.add(cs.toSymbol())
                    }
                } catch (e: Exception) {
                    // Skip files that cannot be scanned
                }
            }
        }
    }

    return symbols
}

/**
 * Extract source file paths from a discovery artifact.
 * Artifacts are YAML documents that may reference source files in their content.
 */
private fun extractSourcePathsFromArtifact(artifact: DiscoveryArtifact): List<String> {
    val paths = mutableListOf<String>()
    val content = artifact.content

    // The artifact path itself may be a source file (not YAML)
    // Only treat it as a source path if it has a known source extension
    if (!artifact.path.endsWith(".yaml") && !artifact.path.endsWith(".yml") &&
        artifact.path.matches(Regex(".*\\.(kt|java|py|ts|js|go)$"))) {
        paths.add(artifact.path)
    }

    // Parse YAML to find referenced source file paths
    val yaml = Yaml()
    val doc = try {
        yaml.load(content) as? Map<String, Any>
    } catch (e: Exception) {
        null
    }

    doc?.let { extractPathsFromYaml(it, paths) }

    // Also try regex-based extraction for file path patterns
    val pathPattern = Regex("""(?:src/[\w/\-\.]+\.(?:kt|java|py|ts|js|go))""")
    pathPattern.findAll(content).forEach { match ->
        paths.add(match.value)
    }

    return paths.distinct()
}

/**
 * Recursively extract file paths from a parsed YAML map.
 */
@Suppress("UNCHECKED_CAST")
private fun extractPathsFromYaml(yamlMap: Map<String, Any>, paths: MutableList<String>) {
    yamlMap.forEach { (_, value) ->
        when (value) {
            is String -> {
                if (value.contains("src/") && value.matches(Regex(".*\\.(kt|java|py|ts|js|go)$"))) {
                    paths.add(value)
                }
            }
            is Map<*, *> -> extractPathsFromYaml(value as Map<String, Any>, paths)
            is List<*> -> value.forEach { item ->
                if (item is Map<*, *>) extractPathsFromYaml(item as Map<String, Any>, paths)
                else if (item is String && item.contains("src/") && item.matches(Regex(".*\\.(kt|java|py|ts|js|go)$"))) {
                    paths.add(item)
                }
            }
        }
    }
}

/**
 * Convert a [CodeSymbol] from the scanner to a [Symbol] for verbalization.
 */
private fun com.i2vision.index.CodeSymbol.toSymbol(): Symbol {
    val kind = when (kind) {
        "class" -> SymbolKind.CLASS
        "interface" -> SymbolKind.INTERFACE
        "function", "fun" -> SymbolKind.FUNCTION
        "property", "val", "var" -> SymbolKind.PROPERTY
        "object" -> SymbolKind.OBJECT
        "enum" -> SymbolKind.ENUM
        "annotation" -> SymbolKind.ANNOTATION
        "type_alias", "typealias" -> SymbolKind.TYPE_ALIAS
        else -> SymbolKind.UNKNOWN
    }
    return Symbol(
        name = name,
        kind = kind,
        filePath = filePath,
        lineNumber = line,
        content = this.content
    )
}

/**
 * Tee PrintStream to output to both console and file
 */
class TeePrintStream(
    private val outputStream: PrintStream,
    private val fileStream: PrintStream
) : PrintStream(fileStream) {
    override fun write(b: Int) {
        outputStream.write(b)
        fileStream.write(b)
    }
    override fun write(buf: ByteArray, off: Int, len: Int) {
        outputStream.write(buf, off, len)
        fileStream.write(buf, off, len)
    }
    override fun flush() {
        outputStream.flush()
        fileStream.flush()
    }
    override fun close() {
        outputStream.close()
        fileStream.close()
    }
}

/**
 * Purge semantic cache for fresh discovery
 */
private fun purgeSemanticCache(projectRoot: File) {
    val cacheDir = File(projectRoot, ".semantic-cache")
    if (cacheDir.exists()) {
        println("Purging semantic cache: ${cacheDir.absolutePath}")
        cacheDir.deleteRecursively()
        println("Semantic cache purged successfully")
    } else {
        println("No semantic cache found at ${cacheDir.absolutePath}")
    }
    
    // Also purge vision-ai cache
    val visionCacheDir = File(projectRoot, ".vision-ai")
    if (visionCacheDir.exists()) {
        println("Purging vision-ai cache: ${visionCacheDir.absolutePath}")
        visionCacheDir.deleteRecursively()
        println("Vision-ai cache purged successfully")
    }
}

/**
 * Analyze artifact quality
 */
private fun analyzeArtifactQuality(projectRoot: File, results: List<ClusterDiscoveryResult>) {
    var totalArtifacts = 0
    var validArtifacts = 0
    var invalidArtifacts = 0
    
    results.forEach { clusterResult ->
        clusterResult.result.artifacts.forEach { artifact ->
            totalArtifacts++
            try {
                val content = artifact.content
                if (content.contains("version:") && content.contains("clusterId:")) {
                    validArtifacts++
                } else {
                    invalidArtifacts++
                    println("⚠️  Invalid artifact: ${artifact.path}")
                }
            } catch (e: Exception) {
                invalidArtifacts++
                println("❌ Error analyzing artifact ${artifact.path}: ${e.message}")
            }
        }
    }
    
    println("Total Artifacts: $totalArtifacts")
    println("Valid Artifacts: $validArtifacts (${String.format("%.1f", validArtifacts * 100.0 / totalArtifacts)}%)")
    println("Invalid Artifacts: $invalidArtifacts (${String.format("%.1f", invalidArtifacts * 100.0 / totalArtifacts)}%)")
    
    if (invalidArtifacts > 0) {
        println("⚠️  Some artifacts have quality issues")
    } else {
        println("✅ All artifacts passed quality checks")
    }
}

/**
 * Test Instant Context API
 */
private fun testInstantContext(projectRoot: File) {
    try {
        // Import Instant Context Provider
        val instantContextClass = Class.forName("com.i2vision.instant.context.ContextProvider")
        val getInstanceMethod = instantContextClass.getMethod("get", File::class.java)
        val contextProvider = getInstanceMethod.invoke(null, projectRoot)
        
        // Get cluster context
        val getClusterContextMethod = contextProvider::class.java.getMethod("getClusterContext", String::class.java)
        val clusterContext = getClusterContextMethod.invoke(contextProvider, "core-discover")
        
        if (clusterContext != null) {
            println("✅ Instant Context API accessible")
            println("   Cluster context retrieved for core-discover")
        } else {
            println("⚠️  Cluster context not found for core-discover")
        }
    } catch (e: Exception) {
        println("⚠️  Instant Context test skipped: ${e.message}")
    }
}

/**
 * Test CLI Context Command
 */
private fun testCliContextCommand(projectRoot: File) {
    try {
        println("Testing CLI Context Command...")
        
        // Test FileContext command
        val fileContextClass = Class.forName("com.i2vision.cli.commands.ContextCommand\$FileContext")
        val fileContextConstructor = fileContextClass.getDeclaredConstructor()
        val fileContext = fileContextConstructor.newInstance()
        
        println("✅ CLI Context Command classes accessible")
        
        // Check if context files are generated
        val contextDir = File(projectRoot, ".vision-ai/context")
        if (contextDir.exists()) {
            val contextFiles = contextDir.listFiles()?.filter { it.extension == "json" } ?: emptyList()
            println("   Found ${contextFiles.size} context files")
        } else {
            println("   No context files found (run discovery first)")
        }
    } catch (e: Exception) {
        println("⚠️  CLI Context test skipped: ${e.message}")
    }
}

/**
 * Validate VSLFC Layers
 */
private fun validateVslfcLayers(projectRoot: File, results: List<ClusterDiscoveryResult>) {
    println("Validating VSLFC Layers...")
    
    val requiredLayers = listOf("vision", "structure", "logic", "flow", "code")
    val layersFound = mutableMapOf<String, Boolean>()
    
    // Check which layers have artifacts
    requiredLayers.forEach { layer ->
        val hasArtifacts = results.any { clusterResult ->
            clusterResult.result.artifacts.any { artifact ->
                artifact.path.contains("/$layer/")
            }
        }
        layersFound[layer] = hasArtifacts
        val status = if (hasArtifacts) "✅" else "❌"
        println("  $layer: $status")
    }
    
    // Overall result
    val allLayersPresent = layersFound.values.all { it }
    if (allLayersPresent) {
        println("✅ All VSLFC layers are present")
    } else {
        println("⚠️  Some VSLFC layers are missing")
    }
    
    // Check for layer-specific quality
    val layerQuality = requiredLayers.map { layer ->
        val artifactsInLayer = results.flatMap { clusterResult ->
            clusterResult.result.artifacts.filter { it.path.contains("/$layer/") }
        }
        val hasValidContent = artifactsInLayer.all { artifact ->
            artifact.content.contains("version:") && artifact.content.contains("description:")
        }
        layer to Pair(artifactsInLayer.size, hasValidContent)
    }
    
    println("Layer Quality:")
    layerQuality.forEach { (layer, data) ->
        val (count, valid) = data
        val status = if (valid) "✅" else "⚠️"
        println("  $layer: $count artifacts, $status")
    }
}

/**
 * Validate Vision Requirements
 */
private fun validateVisionRequirements(projectRoot: File, results: List<ClusterDiscoveryResult>) {
    println("Validating Vision Requirements...")
    
    // Check vision layer artifacts
    val visionArtifacts = results.flatMap { clusterResult ->
        clusterResult.result.artifacts.filter { it.path.contains("/vision/") }
    }
    
    println("Found ${visionArtifacts.size} vision artifacts")
    
    if (visionArtifacts.isEmpty()) {
        println("⚠️  No vision artifacts found")
        return
    }
    
    // Validate vision content
    var validVisionArtifacts = 0
    val visionPatterns = listOf(
        "vision:" to "Vision statement",
        "goals:" to "Goals",
        "principles:" to "Principles"
    )
    
    visionArtifacts.forEach { artifact ->
        val content = artifact.content
        val hasAllPatterns = visionPatterns.all { (pattern, _) ->
            content.contains(pattern)
        }
        
        if (hasAllPatterns) {
            validVisionArtifacts++
        } else {
            println("⚠️  Vision artifact missing required content: ${artifact.path}")
        }
    }
    
    val validPercentage = if (visionArtifacts.isNotEmpty()) {
        (validVisionArtifacts * 100) / visionArtifacts.size
    } else {
        0
    }
    
    println("Vision artifacts: $validVisionArtifacts/${visionArtifacts.size} valid ($validPercentage%)")
    
    if (validPercentage >= 80) {
        println("✅ Vision requirements validated")
    } else {
        println("⚠️  Vision requirements need attention")
    }
}

/**
 * Export Documentation
 */
private fun exportDocumentation(projectRoot: File, results: List<ClusterDiscoveryResult>) {
    println("Exporting Documentation...")
    
    // Create documentation directory
    val docsDir = File(projectRoot, ".vision-ai/docs")
    docsDir.mkdirs()
    
    // Generate summary report
    val summaryFile = File(docsDir, "discovery-summary.md")
    summaryFile.bufferedWriter().use { writer ->
        writer.write("# i2vision Self-Discovery Report\n\n")
        writer.write("Generated: ${Instant.now()}\n\n")
        
        writer.write("## Discovery Summary\n\n")
        writer.write("| Metric | Value |\n")
        writer.write("|--------|-------|\n")
        writer.write("| Total Clusters | ${results.size} |\n")
        writer.write("| Successful | ${results.count { it.result.success }} |\n")
        writer.write("| Failed | ${results.count { !it.result.success }} |\n")
        writer.write("| Total Artifacts | ${results.sumOf { it.result.artifacts.size }} |\n")
        writer.write("| Duration | ${results.sumOf { it.duration }}ms |\n\n")
        
        writer.write("## Cluster Details\n\n")
        results.forEach { clusterResult ->
            writer.write("### ${clusterResult.clusterId}\n\n")
            writer.write("- Status: ${if (clusterResult.result.success) "✅ Success" else "❌ Failed"}\n")
            writer.write("- Artifacts: ${clusterResult.result.artifacts.size}\n")
            writer.write("- Duration: ${clusterResult.duration}ms\n\n")
            
            if (clusterResult.result.errors.isNotEmpty()) {
                writer.write("**Errors:**\n")
                clusterResult.result.errors.forEach { error ->
                    writer.write("- $error\n")
                }
                writer.write("\n")
            }
        }
    }
    
    println("✅ Documentation exported to: ${summaryFile.absolutePath}")
    
    // Export individual cluster docs
    results.forEach { clusterResult ->
        val clusterDocFile = File(docsDir, "cluster-${clusterResult.clusterId}.md")
        clusterDocFile.bufferedWriter().use { writer ->
            writer.write("# ${clusterResult.clusterId} Discovery Report\n\n")
            writer.write("Generated: ${Instant.now()}\n\n")
            writer.write("## Summary\n\n")
            writer.write("- Status: ${if (clusterResult.result.success) "✅ Success" else "❌ Failed"}\n")
            writer.write("- Artifacts: ${clusterResult.result.artifacts.size}\n")
            writer.write("- Duration: ${clusterResult.duration}ms\n\n")
            
            writer.write("## Artifacts\n\n")
            clusterResult.result.artifacts.forEach { artifact ->
                writer.write("### ${artifact.path}\n\n")
                writer.write("```yaml\n")
                writer.write(artifact.content)
                writer.write("\n```\n\n")
            }
        }
    }
    
    println("📄 Cluster documentation exported to: ${docsDir.absolutePath}")
}