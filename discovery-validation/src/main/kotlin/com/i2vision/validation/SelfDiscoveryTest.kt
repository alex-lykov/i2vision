package com.i2vision.validation

import com.i2vision.arch.signature.SignatureBuilder
import com.i2vision.discover.pipeline.DiscoveryPipelineImpl
import com.i2vision.discover.api.models.PipelineResult
import com.i2vision.discover.api.models.DiscoveryIntent
import com.i2vision.discover.api.models.DiscoveryGoal
import com.i2vision.discover.api.models.IntentDepth
import com.i2vision.discover.api.models.DiscoveryQuality
import com.i2vision.storage.api.CacheStore
import com.i2vision.storage.impl.FileCacheStore
import com.i2vision.storage.I2VisionPaths
import com.i2vision.discover.api.IntentResolver
import com.i2vision.discover.intent.IntentResolverImpl
import com.i2vision.intent.IntentParser
import com.i2vision.storage.impl.RolloutManager
import kotlinx.coroutines.*
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.io.PrintStream
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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
    val deep = args.contains("--deep")  // Internal test flag (not related to deprecated CLI --depth)
    val exportDocs = args.contains("--export-docs")
    val analyzeQuality = args.contains("--analyze-quality")
    val testContext = args.contains("--test-context")
    val testCli = args.contains("--test-cli")
    val validateLayers = args.contains("--validate-layers")
    val validateVision = args.contains("--validate-vision")
    
    // Map --deep flag to intent depth (DEEP for --deep, STANDARD otherwise)
    val intentDepth = if (deep) IntentDepth.DEEP else IntentDepth.STANDARD
    
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
    println("Intent Depth: $intentDepth")
    if (purge) println("Purge: ENABLED")
    if (exportDocs) println("Export Docs: ENABLED")
    if (analyzeQuality) println("Analyze Quality: ENABLED")
    if (testContext) println("Test Context: ENABLED")
    if (testCli) println("Test CLI: ENABLED")
    if (validateLayers) println("Validate Layers: ENABLED")
    if (validateVision) println("Validate Vision: ENABLED")
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
    println("--- Discovery Pipeline Execution (Intent Depth: $intentDepth) ---")
    val intentResolver = IntentResolverImpl()
    val cacheDir = I2VisionPaths.getProjectCacheDir(projectRoot.absolutePath)
    val cacheStore = FileCacheStore(cacheDir)
    val discovery = DiscoveryPipelineImpl(projectRoot.absolutePath, intentResolver, cacheStore)
    
    // Enable batch mode for LinkService to collect links in memory and write once at end
    discovery.enableLinkBatchMode()
    
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
    
    // Create shared discovery context to avoid redundant operations
    // Note: Architecture is already detected once at the start (signature)
    // The discovery pipeline will still scan files per cluster, but we avoid re-detecting architecture
    val sharedContext = SharedDiscoveryContext(
        projectRoot = projectRoot.absolutePath,
        allSourceFiles = emptyList(),  // DiscoveryPipeline handles file scanning internally
        architectureSignature = signature  // Re-use already-detected architecture
    )
    
    val allResults = mutableListOf<ClusterDiscoveryResult>()
    val totalStartTime = System.currentTimeMillis()
    
    // Process clusters in parallel using coroutines
    println("Processing ${validClusters.size} clusters in parallel...")
    runBlocking {
        val jobs = validClusters.map { cluster ->
            async(Dispatchers.Default) {
                val startTime = System.currentTimeMillis()
                print("Discovering ${cluster.name}... ")
                
                // Create intent for this cluster
                val intent = DiscoveryIntent(
                    goal = DiscoveryGoal.UNDERSTAND,
                    depth = intentDepth,
                    quality = DiscoveryQuality.BALANCED,
                    layerFocus = listOf("vision", "structure", "logic", "flow", "code")
                )
                
                val result = discovery.discover(
                    intent = intent,
                    clusterId = cluster.name,
                    contracts = emptyList()
                )
                
                val duration = System.currentTimeMillis() - startTime
                val status = if (result.success) "✅" else "❌"
                println("$status (${duration}ms, ${result.artifacts.size} artifacts)")
                
                ClusterDiscoveryResult(cluster.name, result, duration)
            }
        }
        
        // Wait for all jobs to complete
        jobs.awaitAll().forEach { allResults.add(it) }
    }
    
    val totalDuration = System.currentTimeMillis() - totalStartTime
    
    // Flush all buffered links to disk in a single operation
    discovery.flushLinkBatch()
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
    
    // Step 6: Artifact Quality Analysis
    if (analyzeQuality) {
        println("--- Artifact Quality Analysis ---")
        analyzeArtifactQuality(projectRoot, allResults)
        println()
    }
    
    // Step 7: Instant Context Test
    if (exportDocs || analyzeQuality || testContext) {
        println("--- Instant Context Test ---")
        testInstantContext(projectRoot)
        println()
    }
    
    // Step 8: CLI Context Command Validation
    if (testCli) {
        testCliContextCommands(projectRoot)
        println()
    }
    
    // Step 9: Documentation Export
    if (exportDocs) {
        println("--- Documentation Export ---")
        exportDocumentation(projectRoot, allResults)
        println()
    }
    
    // Step 10: Key Findings
    println("--- Key Findings ---")
    val successfulClusters = allResults.count { it.result.success }
    val successRate = (successfulClusters * 100.0) / allResults.size

    println("✅ Architecture detection: ${signature.deploymentPattern.name} (${clusters.size} clusters)")
    println("✅ Discovery pipeline: $successfulClusters/${allResults.size} clusters (${String.format("%.1f", successRate)}%)")
    println("✅ Semantic cache: .semantic-cache/{clusterId}/{vision|structure|logic|flow|code}/")

    if (analyzeQuality) {
        val avgArtifacts = allResults.map { it.result.artifacts.size }.average()
        println("📊 Average artifacts per cluster: ${String.format("%.1f", avgArtifacts)}")
    }

    // Report any failures
    val failedClusters = allResults.filter { !it.result.success }
    if (failedClusters.isNotEmpty()) {
        println()
        println("⚠️ Failed clusters:")
        failedClusters.forEach { cluster ->
            println("  - ${cluster.name}: ${cluster.result.errors.joinToString()}")
        }
    }

    println()

    // Step 11: Layer completeness validation
    if (validateLayers) {
        println("--- Layer Completeness Validation ---")
        validateLayerCompleteness(projectRoot, allResults)
        println()
    }

    // Step 12: Vision layer validation
    if (validateVision) {
        println("--- Vision Layer Validation ---")
        validateVisionLayer(projectRoot, allResults)
        println()
    }

    println()
    println("=== Self-Discovery Complete ===")
    
    // Restore original System.out and close log stream
    System.setOut(originalOut)
    logStream.close()
    println("Discovery log saved to: ${logFile.absolutePath}")
}

// ========== HELPER FUNCTIONS ==========

data class ClusterDiscoveryResult(
    val name: String,
    val result: PipelineResult,
    val durationMs: Long
)

/**
 * Shared discovery context to avoid redundant operations across clusters.
 * Pre-scans files and architecture once, then reuses for all clusters.
 */
data class SharedDiscoveryContext(
    val projectRoot: String,
    val allSourceFiles: List<java.io.File>,
    val architectureSignature: com.i2vision.arch.signature.ArchitectureSignature
)

data class ArtifactQuality(
    val clusterName: String,
    val hasSymbols: Boolean,
    val symbolCount: Int,
    val hasFlows: Boolean,
    val flowCount: Int,
    val hasRules: Boolean,
    val ruleCount: Int,
    val hasComponents: Boolean,
    val componentCount: Int,
    val hasDocs: Boolean,
    val hasEnhancedContext: Boolean = false
)

fun purgeSemanticCache(root: File) {
    val semanticCacheDir = I2VisionPaths.getProjectCacheDir(root.absolutePath)
    if (semanticCacheDir.exists()) {
        val deleted = semanticCacheDir.deleteRecursively()
        println("Deleted: ${semanticCacheDir.absolutePath}")
    }
    
    val wrongDirs = listOf("code", "flow", "logic", "structure", "project")
    wrongDirs.forEach { dirName ->
        val dir = File(semanticCacheDir, dirName)
        if (dir.exists()) {
            dir.deleteRecursively()
            println("Deleted wrong directory: ${dir.absolutePath}")
        }
    }
}

fun analyzeArtifactQuality(root: File, results: List<ClusterDiscoveryResult>) {
    val yaml = Yaml()
    val qualities = mutableListOf<ArtifactQuality>()
    val contextProvider = com.i2vision.instant.context.ContextProvider(
        projectRoot = root.absolutePath,
        cacheStore = FileCacheStore(I2VisionPaths.getProjectCacheDir(root.absolutePath))
    )
    
    results.filter { it.result.success }.forEach { clusterResult ->
        val clusterName = clusterResult.name
        val semanticCacheDir = I2VisionPaths.getProjectCacheDir(root.absolutePath)
        val clusterCache = File(semanticCacheDir, clusterName)
        
        // Check code layer - count actual symbols from symbols.yaml
        val symbolsFile = File(clusterCache, "code/symbols.yaml")
        var symbolCount = 0
        if (symbolsFile.exists()) {
            try {
                val data = yaml.load<Map<String, Any>>(symbolsFile.readText())
                val symbols = data["symbols"] as? List<*>
                symbolCount = symbols?.size ?: 0
            } catch (e: Exception) {
                symbolCount = 0
            }
        }
        
        // Check flow layer - count individual YAML files
        val flowDir = File(clusterCache, "flow")
        var flowCount = 0
        if (flowDir.exists()) {
            flowCount = flowDir.walkTopDown().filter { it.isFile && it.name.endsWith(".yaml") }.count()
        }
        
        // Check logic layer - count individual YAML files
        val logicDir = File(clusterCache, "logic")
        var ruleCount = 0
        if (logicDir.exists()) {
            ruleCount = logicDir.walkTopDown().filter { it.isFile && it.name.endsWith(".yaml") }.count()
        }
        
        // Check structure layer - count individual YAML files
        val structureDir = File(clusterCache, "structure")
        var componentCount = 0
        if (structureDir.exists()) {
            componentCount = structureDir.walkTopDown().filter { it.isFile && it.name.endsWith(".yaml") }.count()
        }
        
        // Check docs - count individual YAML files
        val docsDir = File(clusterCache, "docs")
        var docCount = 0
        if (docsDir.exists()) {
            docCount = docsDir.walkTopDown().filter { it.isFile && it.name.endsWith(".yaml") }.count()
        }
        
        // Check enhanced context availability
        val hasEnhancedContext = contextProvider.hasDiscoveryCache(clusterName)
        
        qualities.add(
            ArtifactQuality(
                clusterName = clusterName,
                hasSymbols = symbolCount > 0,
                symbolCount = symbolCount,
                hasFlows = flowCount > 0,
                flowCount = flowCount,
                hasRules = ruleCount > 0,
                ruleCount = ruleCount,
                hasComponents = componentCount > 0,
                componentCount = componentCount,
                hasDocs = docCount > 0,
                hasEnhancedContext = hasEnhancedContext
            )
        )
    }
    
    // Print summary table with enhanced context column
    println()
    println("Cluster                                    Symbols  Flows  Rules  Comps  Docs  Enhanced")
    println("-----------------------------------------  -------  -----  -----  -----  ----  --------")
    qualities.sortedBy { it.clusterName }.forEach { q ->
        val shortName = if (q.clusterName.length > 40) q.clusterName.take(37) + "..." else q.clusterName.padEnd(40)
        val symbols = if (q.hasSymbols) q.symbolCount.toString().padEnd(7) else "-".padEnd(7)
        val flows = if (q.hasFlows) q.flowCount.toString().padEnd(5) else "-".padEnd(5)
        val rules = if (q.hasRules) q.ruleCount.toString().padEnd(5) else "-".padEnd(5)
        val comps = if (q.hasComponents) q.componentCount.toString().padEnd(5) else "-".padEnd(5)
        val docs = if (q.hasDocs) "✅".padEnd(4) else "-".padEnd(4)
        val enhanced = if (q.hasEnhancedContext) "✅" else "-"
        println("$shortName  $symbols  $flows  $rules  $comps  $docs  $enhanced")
    }
    println()
    
    // Statistics
    val clustersWithSymbols = qualities.count { it.hasSymbols }
    val clustersWithFlows = qualities.count { it.hasFlows }
    val clustersWithRules = qualities.count { it.hasRules }
    val clustersWithComponents = qualities.count { it.hasComponents }
    val clustersWithDocs = qualities.count { it.hasDocs }
    val clustersWithEnhanced = qualities.count { it.hasEnhancedContext }
    val totalClusters = qualities.size
    
    println("Statistics:")
    println("  Clusters with symbols:    $clustersWithSymbols/$totalClusters (${clustersWithSymbols * 100 / totalClusters}%)")
    println("  Clusters with flows:      $clustersWithFlows/$totalClusters (${clustersWithFlows * 100 / totalClusters}%)")
    println("  Clusters with rules:      $clustersWithRules/$totalClusters (${clustersWithRules * 100 / totalClusters}%)")
    println("  Clusters with components: $clustersWithComponents/$totalClusters (${clustersWithComponents * 100 / totalClusters}%)")
    println("  Clusters with docs:       $clustersWithDocs/$totalClusters (${clustersWithDocs * 100 / totalClusters}%)")
    println("  Clusters with enhanced:   $clustersWithEnhanced/$totalClusters (${clustersWithEnhanced * 100 / totalClusters}%)")
    
    // Total counts
    val totalSymbols = qualities.sumOf { it.symbolCount }
    val totalFlows = qualities.sumOf { it.flowCount }
    val totalRules = qualities.sumOf { it.ruleCount }
    val totalComponents = qualities.sumOf { it.componentCount }
    
    println()
    println("Totals:")
    println("  Symbols:    $totalSymbols")
    println("  Flows:      $totalFlows")
    println("  Rules:      $totalRules")
    println("  Components: $totalComponents")
}

fun testInstantContext(root: File) {
    println("=== Instant Context Validation ===")
    
    val contextProvider = com.i2vision.instant.context.ContextProvider(
        projectRoot = root.absolutePath,
        cacheStore = FileCacheStore(I2VisionPaths.getProjectCacheDir(root.absolutePath))
    )
    
    // Test 1: Basic context (should always work)
    println("\n--- Test 1: Basic Context ---")
    val testFile = findTestFile(root) ?: return
    println("Test file: ${testFile.relativeTo(root).path}")
    
    runBlocking {
        val basicContext = contextProvider.getContext(
            testFile.relativeTo(root).path,
            "discovery"
        )
        
        if (basicContext.success) {
            println("✅ Basic context: ${basicContext.symbols.size} symbols")
        } else {
            println("❌ Basic context failed: ${basicContext.error}")
        }
    }
    
    // Test 2: Cache detection
    println("\n--- Test 2: Cache Detection ---")
    val clusters = findDiscoveredClusters(root)
    clusters.take(5).forEach { cluster ->
        val hasCache = contextProvider.hasDiscoveryCache(cluster)
        val status = if (hasCache) "✅" else "❌"
        println("$status $cluster")
    }
    
    // Test 3: Enhanced context (should work for clusters with cache)
    println("\n--- Test 3: Enhanced Context ---")
    val clusterWithCache = clusters.firstOrNull { contextProvider.hasDiscoveryCache(it) }
    
    if (clusterWithCache != null) {
        val clusterFile = findFileInCluster(root, clusterWithCache)
        if (clusterFile != null) {
            runBlocking {
                val enhancedContext = contextProvider.getEnhancedContext(
                    clusterFile.relativeTo(root).path,
                    "discovery"
                )
                
                if (enhancedContext.success) {
                    println("✅ Enhanced context for $clusterWithCache:")
                    println("   - Enhanced: ${enhancedContext.enhanced}")
                    println("   - Flows: ${enhancedContext.flows.size}")
                    println("   - Business rules: ${enhancedContext.businessRules.size}")
                    println("   - Component: ${enhancedContext.component?.name ?: "none"}")
                    println("   - Related components: ${enhancedContext.relatedComponents.size}")
                } else {
                    println("❌ Enhanced context failed: ${enhancedContext.error}")
                }
            }
        }
    } else {
        println("⚠️ No clusters with discovery cache found")
        println("   Run discovery first to populate cache")
    }
    
    // Test 4: Cache statistics
    println("\n--- Test 4: Cache Statistics ---")
    val stats = contextProvider.getCacheStats()
    println("Total entries: ${stats.totalEntries}")
    println("Valid entries: ${stats.validEntries}")
    println("Expired entries: ${stats.expiredEntries}")
}

fun findTestFile(root: File): File? {
    val candidates = listOf(
        "i2vision-instant/src/main/kotlin/com/i2vision/instant/context/ContextProvider.kt",
        "i2vision-discover/src/main/kotlin/com/i2vision/discover/pipeline/DiscoveryPipelineImpl.kt",
        "vslfc-core/src/main/kotlin/com/i2vision/vslfc/contracts/ContractModels.kt"
    )
    
    return candidates.map { File(root, it) }.firstOrNull { it.exists() }
}

fun findDiscoveredClusters(root: File): List<String> {
    val cacheDir = I2VisionPaths.getProjectCacheDir(root.absolutePath)
    if (!cacheDir.exists()) return emptyList()
    
    return cacheDir.listFiles()
        ?.filter { it.isDirectory && !it.name.startsWith(".") }
        ?.map { it.name }
        ?: emptyList()
}

fun findFileInCluster(root: File, cluster: String): File? {
    val clusterDir = File(root, cluster)
    if (!clusterDir.exists()) return null
    
    return clusterDir.walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .firstOrNull()
}

fun testCliContextCommands(root: File) {
    println("\n--- CLI Context Command Validation ---")
    
    val testFile = findTestFile(root) ?: return
    val relativePath = testFile.relativeTo(root).path
    
    // Test 1: context file
    println("\nTest: context file --path=$relativePath")
    runCommand(root, "context", "file", "--path=$relativePath")
    
    // Test 2: context enhanced
    println("\nTest: context enhanced --path=$relativePath")
    runCommand(root, "context", "enhanced", "--path=$relativePath")
    
    // Test 3: context cache stats
    println("\nTest: context cache stats")
    runCommand(root, "context", "cache", "stats")
}

fun runCommand(root: File, vararg args: String) {
    val process = ProcessBuilder()
        .directory(root)
        .command(
            if (System.getProperty("os.name").lowercase().contains("win")) {
                listOf("gradlew.bat", ":i2vision-cli:run", "--args=" + args.joinToString(" "))
            } else {
                listOf("./gradlew", ":i2vision-cli:run", "--args=" + args.joinToString(" "))
            }
        )
        .redirectErrorStream(true)
        .start()
    
    val output = process.inputStream.bufferedReader().readText()
    val exitCode = process.waitFor()
    
    if (exitCode == 0) {
        // Show first 20 lines of output
        output.lines().take(20).forEach { println("  $it") }
        if (output.lines().size > 20) {
            println("  ... and ${output.lines().size - 20} more lines")
        }
    } else {
        println("❌ Command failed (exit code: $exitCode)")
        output.lines().take(5).forEach { println("  $it") }
    }
}

fun exportDocumentation(root: File, results: List<ClusterDiscoveryResult>) {
    println("Documentation export not yet implemented - requires doc exporter")
    // TODO: Implement when doc exporter is available
}

fun generateQualityReport(root: File, results: List<ClusterDiscoveryResult>, signature: com.i2vision.arch.signature.ArchitectureSignature): String {
    val report = StringBuilder()
    val timestamp = Instant.now()
    
    report.appendLine("# i2vision Self-Discovery Quality Report")
    report.appendLine()
    report.appendLine("Generated: $timestamp")
    report.appendLine()
    
    report.appendLine("## Project Overview")
    report.appendLine("- **Build System:** ${signature.buildSystem?.name ?: "unknown"}")
    report.appendLine("- **Deployment Pattern:** ${signature.deploymentPattern.name}")
    report.appendLine("- **Clusters:** ${signature.clusters.size}")
    report.appendLine()
    
    report.appendLine("## Discovery Results")
    val successful = results.count { it.result.success }
    report.appendLine("- **Total Clusters:** ${results.size}")
    report.appendLine("- **Successful:** $successful")
    report.appendLine("- **Failed:** ${results.size - successful}")
    report.appendLine()
    
    if (successful < results.size) {
        report.appendLine("### Failed Clusters")
        results.filter { !it.result.success }.forEach { cluster ->
            report.appendLine("- **${cluster.name}:** ${cluster.result.errors.joinToString()}")
        }
        report.appendLine()
    }
    
    report.appendLine("## Artifact Quality")
    report.appendLine()
    report.appendLine("| Cluster | Symbols | Flows | Rules | Components | Docs |")
    report.appendLine("|---------|---------|-------|-------|------------|------|")
    
    results.filter { it.result.success }.forEach { clusterResult ->
        val clusterName = clusterResult.name
        val semanticCacheDir = I2VisionPaths.getProjectCacheDir(root.absolutePath)
        val clusterCache = File(semanticCacheDir, clusterName)
        
        val symbols = countArtifactItems(clusterCache, "code", "symbols.yaml", "symbols")
        val flows = countArtifactItems(clusterCache, "flow", "sequences.yaml", "flows")
        val rules = countArtifactItems(clusterCache, "logic", "business-rules.yaml", "business_rules")
        val components = countArtifactItems(clusterCache, "structure", "components.yaml", "components")
        val docs = if (File(clusterCache, "docs").exists()) "✅" else "-"
        
        report.appendLine("| $clusterName | $symbols | $flows | $rules | $components | $docs |")
    }
    
    report.appendLine()
    report.appendLine("---")
    report.appendLine("*Report generated by i2vision Self-Discovery Test*")
    
    return report.toString()
}

fun countArtifactItems(cacheDir: File, layer: String, fileName: String, key: String): Int {
    val layerDir = File(cacheDir, layer)
    val file = File(layerDir, fileName)
    if (!file.exists()) return 0
    
    return try {
        val yaml = Yaml()
        val data = yaml.load<Map<String, Any>>(file.readText())
        (data[key] as? List<*>)?.size ?: 0
    } catch (e: Exception) {
        0
    }
}

/**
 * PrintStream that writes to multiple output streams.
 * Used to tee output to both console and file.
 */
class TeePrintStream(vararg streams: PrintStream) : PrintStream(TeeOutputStream(*streams)) {
    private class TeeOutputStream(vararg streams: PrintStream) : java.io.OutputStream() {
        private val streams = streams.toList()
        
        override fun write(b: Int) {
            streams.forEach { it.write(b) }
        }
        
        override fun write(b: ByteArray) {
            streams.forEach { it.write(b) }
        }
        
        override fun write(b: ByteArray, off: Int, len: Int) {
            streams.forEach { it.write(b, off, len) }
        }
        
        override fun flush() {
            streams.forEach { it.flush() }
        }
        
        override fun close() {
            streams.forEach { it.close() }
        }
    }
}

fun validateLayerCompleteness(root: File, results: List<ClusterDiscoveryResult>) {
    val semanticCacheDir = I2VisionPaths.getProjectCacheDir(root.absolutePath)
    
    println("Layer completeness per cluster:")
    println("Cluster                                    Code  Flow  Logic  Struct  Vision")
    println("-----------------------------------------  ----  ----  -----  ------  ------")
    
    val layers = listOf("code", "flow", "logic", "structure", "vision")
    val completeness = mutableMapOf<String, MutableMap<String, Boolean>>()
    
    results.filter { it.result.success }.forEach { clusterResult ->
        val clusterName = clusterResult.name
        val clusterCache = File(semanticCacheDir, clusterName)
        val layerStatus = mutableMapOf<String, Boolean>()
        
        layers.forEach { layer ->
            val layerDir = File(clusterCache, layer)
            val hasLayer = layerDir.exists() && layerDir.listFiles()?.isNotEmpty() == true
            layerStatus[layer] = hasLayer
        }
        
        completeness[clusterName] = layerStatus
        
        val shortName = if (clusterName.length > 40) clusterName.take(37) + "..." else clusterName.padEnd(40)
        val code = if (layerStatus["code"] == true) "✅".padEnd(4) else "❌".padEnd(4)
        val flow = if (layerStatus["flow"] == true) "✅".padEnd(4) else "❌".padEnd(4)
        val logic = if (layerStatus["logic"] == true) "✅".padEnd(5) else "❌".padEnd(5)
        val struct = if (layerStatus["structure"] == true) "✅".padEnd(6) else "❌".padEnd(6)
        val vision = if (layerStatus["vision"] == true) "✅" else "❌"
        println("$shortName  $code  $flow  $logic  $struct  $vision")
    }
    
    // Summary
    val totalClusters = completeness.size
    val clustersWithAllLayers = completeness.count { it.value.all { layer -> layer.value } }
    val clustersWithVision = completeness.count { it.value["vision"] == true }
    val clustersWithCode = completeness.count { it.value["code"] == true }
    
    println()
    println("Summary:")
    println("  Clusters with ALL 5 layers: $clustersWithAllLayers/$totalClusters")
    println("  Clusters with Vision layer: $clustersWithVision/$totalClusters")
    println("  Clusters with Code layer:  $clustersWithCode/$totalClusters")
    
    // Show missing layers
    completeness.forEach { (cluster, layers) ->
        val missing = layers.filter { !it.value }.keys
        if (missing.isNotEmpty()) {
            println("  $cluster: missing ${missing.joinToString(", ")}")
        }
    }
}

fun validateVisionLayer(root: File, results: List<ClusterDiscoveryResult>) {
    val semanticCacheDir = I2VisionPaths.getProjectCacheDir(root.absolutePath)
    
    println("Vision layer artifacts:")
    println()
    
    var totalRequirements = 0
    var totalConstraints = 0
    
    results.filter { it.result.success }.forEach { clusterResult ->
        val clusterName = clusterResult.name
        val visionDir = File(File(semanticCacheDir, clusterName), "vision")
        
        if (visionDir.exists()) {
            val reqFiles = visionDir.listFiles()?.filter { it.name.endsWith(".yaml") } ?: emptyList()
            
            if (reqFiles.isNotEmpty()) {
                println("  $clusterName:")
                reqFiles.forEach { file ->
                    try {
                        val yaml = Yaml()
                        val data = yaml.load<Map<String, Any>>(file.readText())
                        val id = data["id"] as? String ?: file.nameWithoutExtension
                        val title = data["title"] as? String ?: "(no title)"
                        val source = data["source"] as? String ?: "unknown"
                        val confidence = (data["confidence"] as? Number)?.toDouble() ?: 0.0
                        
                        println("    - $id: ${title.take(60)}")
                        println("      source: $source, confidence: ${"%.2f".format(confidence)}")
                        
                        if (data.containsKey("evidence")) {
                            val evidence = data["evidence"] as? List<*> ?: emptyList<Any>()
                            if (evidence.isNotEmpty()) {
                                println("      evidence: ${evidence.size} code references")
                            } else {
                                println("      evidence: none (orphaned)")
                            }
                        }
                        
                        totalRequirements++
                    } catch (e: Exception) {
                        println("    - ${file.name}: (parse error)")
                    }
                }
            } else {
                println("  $clusterName: (no vision artifacts)")
            }
        } else {
            println("  $clusterName: (no vision directory)")
        }
    }
    
    println()
    println("Vision layer summary:")
    println("  Total requirements: $totalRequirements")
    println("  Total constraints: $totalConstraints")
    
    // Check if .vision-ai/ contracts exist
    val visionContractFile = File(root, ".vision-ai/.vision/contracts/with-docs.yaml")
    if (visionContractFile.exists()) {
        println("  ✅ Vision contract exists: ${visionContractFile.relativeTo(root).path}")
    } else {
        println("  ❌ Vision contract missing: .vision-ai/.vision/contracts/with-docs.yaml")
        println("     Run 'i2vision init' to create contract files")
    }
}
