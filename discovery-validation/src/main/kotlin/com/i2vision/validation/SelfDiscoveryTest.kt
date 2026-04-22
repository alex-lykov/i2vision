package com.i2vision.validation

import com.i2vision.arch.signature.SignatureBuilder
import com.i2vision.discover.pipeline.DiscoveryPipelineImpl
import com.i2vision.discover.api.models.PipelineResult
import com.i2vision.discover.api.models.DiscoveryDepth
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
    val projectRoot = File(".").absoluteFile
    val purge = args.contains("--purge")
    val deep = args.contains("--deep")
    val exportDocs = args.contains("--export-docs")
    val analyzeQuality = args.contains("--analyze-quality")
    
    val depth = if (deep) DiscoveryDepth.DEEP else DiscoveryDepth.STANDARD
    
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
    
    println("=== i2vision Self-Discovery Test ===")
    println("Project: ${projectRoot.canonicalPath}")
    println("Depth: $depth")
    if (purge) println("Purge: ENABLED")
    if (exportDocs) println("Export Docs: ENABLED")
    if (analyzeQuality) println("Analyze Quality: ENABLED")
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
    println("--- Discovery Pipeline Execution (Depth: $depth) ---")
    val intentResolver = IntentResolverImpl()
    val cacheStore = FileCacheStore(projectRoot)
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
                
                val result = discovery.discover(
                    depth = depth,
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
    if (exportDocs || analyzeQuality) {
        println("--- Instant Context Test ---")
        testInstantContext(projectRoot)
        println()
    }
    
    // Step 8: Documentation Export
    if (exportDocs) {
        println("--- Documentation Export ---")
        exportDocumentation(projectRoot, allResults)
        println()
    }
    
    // Step 9: Key Findings
    println("--- Key Findings ---")
    val successfulClusters = allResults.count { it.result.success }
    val successRate = (successfulClusters * 100.0) / allResults.size
    
    println("✅ Architecture detection: ${signature.deploymentPattern.name} (${clusters.size} clusters)")
    println("✅ Discovery pipeline: $successfulClusters/${allResults.size} clusters (${String.format("%.1f", successRate)}%)")
    println("✅ Semantic cache: .semantic-cache/{clusterId}/code/")
    
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
    val hasDocs: Boolean
)

fun purgeSemanticCache(root: File) {
    // Migrate legacy cache to user home directory before purging
    val rolloutManager = RolloutManager()
    val migrated = rolloutManager.migrateFromLegacyCache(root)
    if (migrated) {
        println("Migrated legacy cache to user home directory")
    }
    
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
            flowCount = flowDir.listFiles()?.count { it.isFile && it.name.endsWith(".yaml") } ?: 0
        }
        
        // Check logic layer - count individual YAML files
        val logicDir = File(clusterCache, "logic")
        var ruleCount = 0
        if (logicDir.exists()) {
            ruleCount = logicDir.listFiles()?.count { it.isFile && it.name.endsWith(".yaml") } ?: 0
        }
        
        // Check structure layer - count individual YAML files
        val structureDir = File(clusterCache, "structure")
        var componentCount = 0
        if (structureDir.exists()) {
            componentCount = structureDir.listFiles()?.count { it.isFile && it.name.endsWith(".yaml") } ?: 0
        }
        
        // Check docs - count individual YAML files
        val docsDir = File(clusterCache, "docs")
        var docCount = 0
        if (docsDir.exists()) {
            docCount = docsDir.listFiles()?.count { it.isFile && it.name.endsWith(".yaml") } ?: 0
        }
        
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
                hasDocs = docCount > 0
            )
        )
    }
    
    // Print summary table
    println()
    println("Cluster                                    Symbols  Flows  Rules  Comps  Docs")
    println("-----------------------------------------  -------  -----  -----  -----  ----")
    qualities.sortedBy { it.clusterName }.forEach { q ->
        val shortName = if (q.clusterName.length > 40) q.clusterName.take(37) + "..." else q.clusterName.padEnd(40)
        val symbols = if (q.hasSymbols) q.symbolCount.toString().padEnd(7) else "-".padEnd(7)
        val flows = if (q.hasFlows) q.flowCount.toString().padEnd(5) else "-".padEnd(5)
        val rules = if (q.hasRules) q.ruleCount.toString().padEnd(5) else "-".padEnd(5)
        val comps = if (q.hasComponents) q.componentCount.toString().padEnd(5) else "-".padEnd(5)
        val docs = if (q.hasDocs) "✅" else "-"
        println("$shortName  $symbols  $flows  $rules  $comps  $docs")
    }
    println()
    
    // Statistics
    val clustersWithSymbols = qualities.count { it.hasSymbols }
    val clustersWithFlows = qualities.count { it.hasFlows }
    val clustersWithRules = qualities.count { it.hasRules }
    val clustersWithComponents = qualities.count { it.hasComponents }
    val clustersWithDocs = qualities.count { it.hasDocs }
    val totalClusters = qualities.size
    
    println("Statistics:")
    println("  Clusters with symbols:    $clustersWithSymbols/$totalClusters (${clustersWithSymbols * 100 / totalClusters}%)")
    println("  Clusters with flows:      $clustersWithFlows/$totalClusters (${clustersWithFlows * 100 / totalClusters}%)")
    println("  Clusters with rules:      $clustersWithRules/$totalClusters (${clustersWithRules * 100 / totalClusters}%)")
    println("  Clusters with components: $clustersWithComponents/$totalClusters (${clustersWithComponents * 100 / totalClusters}%)")
    println("  Clusters with docs:       $clustersWithDocs/$totalClusters (${clustersWithDocs * 100 / totalClusters}%)")
    
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
    println("Instant context test not yet implemented - requires i2vision-instant module")
    // TODO: Implement when i2vision-instant is available
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
