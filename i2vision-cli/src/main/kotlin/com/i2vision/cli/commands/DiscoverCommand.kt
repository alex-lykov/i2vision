package com.i2vision.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.arguments.argument
import com.i2vision.discover.api.models.DiscoveryDepth
import com.i2vision.discover.api.models.DiscoveryIntent as ApiDiscoveryIntent
import com.i2vision.discover.api.models.DiscoveryGoal
import com.i2vision.discover.api.models.IntentDepth as ApiIntentDepth
import com.i2vision.discover.api.models.DiscoveryQuality
import com.i2vision.discover.api.models.PipelineResult
import com.i2vision.discover.pipeline.DiscoveryPipelineImpl
import com.i2vision.storage.api.CacheStore
import com.i2vision.storage.impl.FileCacheStore
import com.i2vision.storage.I2VisionPaths
import com.i2vision.discover.intent.IntentResolverImpl
import com.i2vision.cli.output.ConsoleOutput
import com.i2vision.intent.IntentParser
import com.i2vision.intent.DiscoveryIntent as ParserDiscoveryIntent
import com.i2vision.instant.context.ContextProvider
import java.io.OutputStream
import java.io.PrintStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import com.i2vision.intent.IntentGoal
import com.i2vision.intent.IntentDepth as ParserIntentDepth
import com.i2vision.intent.QualityFocus
import com.i2vision.arch.signature.SignatureBuilder
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Discover Command - Runs discovery on a project.
 * 
 * Usage: i2vision discover <project-path> [options]
 */
class DiscoverCommand : CliktCommand(
    name = "discover",
    help = "Run discovery analysis on a project"
) {
    
    private val log = LoggerFactory.getLogger(DiscoverCommand::class.java)
    
    private val projectPath by argument("project-path", help = "Path to project directory")
    
    // NEW: Intent-based (primary)
    private val intent by option(
        "--intent",
        help = "Discovery intent: full_discovery, refactoring_analysis, quick_overview, architecture_audit, flow_mapping, documentation_generation"
    )
    
    // NEW: Preset-based (placeholder for future implementation)
    private val preset by option(
        "--preset",
        help = "Preset name (future: kotlin-agent, spring-boot, conservative, permissive)"
    )
    
    // LEGACY: Keep for backward compatibility
    private val depth by option("-d", "--depth", help = "[DEPRECATED] Use --intent instead. Discovery depth (BROWSE, STANDARD, DEEP)")
    private val cluster by option("--cluster", help = "Cluster ID for focused discovery")
    private val output by option("-o", "--output", help = "Output directory for artifacts")
    private val json by option("--json", help = "Output results as JSON").flag()
    private val yaml by option("--yaml", help = "Output results as YAML").flag()
    private val validateContext by option("--validate-context", help = "Validate enhanced context availability after discovery").flag()
    
    private val consoleOutput = ConsoleOutput()
    
    override fun run() {
        log.info("[CLI] Discover command started for project: $projectPath")
        echo("Running discovery on: $projectPath")
        
        val projectFile = File(projectPath)
        if (!projectFile.exists()) {
            echo("Error: Project path does not exist: $projectPath", err = true)
            throw IllegalArgumentException("Project path does not exist: $projectPath")
        }
        
        // Find project root by walking up to find settings.gradle.kts (same as self-discovery test)
        var projectRoot = File(projectPath).absoluteFile
        while (projectRoot.parentFile != null && !File(projectRoot, "settings.gradle.kts").exists()) {
            projectRoot = projectRoot.parentFile
        }
        if (!File(projectRoot, "settings.gradle.kts").exists()) {
            log.warn("[CLI] No settings.gradle.kts found in parent directories, using current directory")
            projectRoot = File(projectPath).absoluteFile
        }
        
        log.info("[CLI] Project root: ${projectRoot.path}")
        echo("Project root: ${projectRoot.path}")
        
        // Setup compact file logging
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
        val originalOut = System.out
        
        // Create compact log writer that filters verbose logs
        val compactLogStream = object : PrintStream(logFile) {
            override fun println(x: String?) {
                // Filter out verbose discovery pipeline logs and artifact listings
                val shouldLog = x?.let { 
                    !it.contains("[DISCOVERY] Step") &&
                    !it.contains("[FLOW_DISCOVERY]") &&
                    !it.contains("[LOGIC_EXTRACTOR]") &&
                    !it.contains("[STRUCTURE_BUILDER]") &&
                    !it.contains("[ARTIFACT_WRITER]") &&
                    !it.contains("[CUSTOM_INDEX]") &&
                    !it.contains("[SCANNER]") &&
                    !it.contains("[INTENT]") &&
                    !it.contains("  - Layer:") &&
                    !it.contains("Artifacts written to:")
                } ?: true
                if (shouldLog) {
                    super.println(x)
                }
            }
        }
        
        // Tee output to both console and compact file log
        System.setOut(TeePrintStream(originalOut, compactLogStream))
        
        echo("=== i2vision CLI Discovery ===")
        echo("Project: ${projectRoot.canonicalPath}")
        echo("Log file: ${logFile.absolutePath}")
        echo("Started at: ${LocalDateTime.now()}")
        echo("")
        
        // Create IntentResolver and DiscoveryPipeline using the actual project root
        val intentResolver = IntentResolverImpl()
        val projectCacheDir = I2VisionPaths.getProjectCacheDir(projectRoot.path)
        val cacheStore = FileCacheStore(projectCacheDir)
        val pipeline = DiscoveryPipelineImpl(projectRoot.path, intentResolver, cacheStore)
        
        // Enable batch mode for LinkService to collect links in memory and write once at end (same as SelfDiscoveryTest)
        pipeline.enableLinkBatchMode()
        
        // Wrap suspend functions in runBlocking
        val discoveryStartTime = System.currentTimeMillis()
        runBlocking {
            when {
                // PATH 1: Preset-based (placeholder)
                preset != null -> {
                    echo("Using preset: $preset")
                    echo("[INFO] Preset-based discovery not yet implemented, falling back to intent-based")
                    // TODO: Implement PresetManager when available
                    // For now, treat preset as intent
                    val discoveryIntent = convertPresetToIntent(preset!!)
                    runWithIntent(pipeline, discoveryIntent, cluster, projectRoot.path)
                }
                
                // PATH 2: Intent-based (PRIMARY)
                intent != null -> {
                    echo("Intent: $intent")
                    val discoveryIntent = parseIntent(intent!!)
                    runWithIntent(pipeline, discoveryIntent, cluster, projectRoot.path)
                }
                
                // PATH 3: Legacy depth-based
                depth != null -> {
                    echo("[WARNING] --depth is deprecated. Use --intent instead.")
                    val discoveryDepth = parseDepth(depth!!)
                    runWithDepth(pipeline, discoveryDepth, cluster, projectRoot.path)
                }
                
                // DEFAULT: Full discovery intent
                else -> {
                    echo("Defaulting to --intent=full_discovery")
                    val discoveryIntent = createDefaultIntent()
                    runWithIntent(pipeline, discoveryIntent, cluster, projectRoot.path)
                }
            }
        }
        
        val discoveryDuration = System.currentTimeMillis() - discoveryStartTime
        echo("")
        echo("=== Discovery Complete ===")
        echo("Total discovery time: ${discoveryDuration}ms (${discoveryDuration / 1000}s)")
        echo("Ended at: ${LocalDateTime.now()}")
        
        // Lightweight context validation if flag is set
        if (validateContext) {
            echo("")
            echo("=== Context Availability ===")
            val contextProvider = ContextProvider(
                projectRoot = projectRoot.path,
                cacheStore = FileCacheStore(projectRoot)
            )
            
            // Get cluster list from cache directories
            val cacheDir = File(projectRoot, ".semantic-cache")
            val clusters = cacheDir.listFiles()
                ?.filter { it.isDirectory }
                ?.map { it.name }
                ?: emptyList()
            
            if (clusters.isEmpty()) {
                echo("No clusters found in semantic cache")
            } else {
                echo("Checking enhanced context availability for ${clusters.size} clusters...")
                echo("")
                
                clusters.take(10).forEach { cluster ->
                    val hasCache = contextProvider.hasDiscoveryCache(cluster)
                    val status = if (hasCache) "[OK]" else "[MISSING]"
                    echo("$status $cluster")
                }
                
                if (clusters.size > 10) {
                    echo("... and ${clusters.size - 10} more clusters")
                }
                
                val enhancedClusters = clusters.count { contextProvider.hasDiscoveryCache(it) }
                echo("")
                echo("Enhanced context available for $enhancedClusters/${clusters.size} clusters")
                
                if (enhancedClusters < clusters.size) {
                    echo("Run 'i2vision discover --intent=full_discovery' for missing clusters")
                }
            }
        }
    }
    
    /**
     * Parse intent string and convert to discovery-api DiscoveryIntent
     */
    private fun parseIntent(intentStr: String): ApiDiscoveryIntent {
        // Use IntentParser to parse the intent string
        val args = mapOf("intent" to intentStr)
        val parserIntent = IntentParser.parse(args)
            ?: error("Invalid intent: $intentStr")
        
        // Bridge from intent-parser DiscoveryIntent to discovery-api DiscoveryIntent
        return bridgeIntent(parserIntent)
    }
    
    /**
     * Bridge from intent-parser DiscoveryIntent to discovery-api DiscoveryIntent
     */
    private fun bridgeIntent(parserIntent: ParserDiscoveryIntent): ApiDiscoveryIntent {
        return ApiDiscoveryIntent(
            goal = mapIntentGoal(parserIntent.goal),
            depth = mapIntentDepth(parserIntent.depth),
            quality = mapQualityFocus(parserIntent.quality),
            layerFocus = parserIntent.focus.map { it.name.lowercase() },
            customParameters = parserIntent.constraints.mapKeys { it.key }.mapValues { it.value.toString() }
        )
    }
    
    /**
     * Map IntentGoal to DiscoveryGoal
     */
    private fun mapIntentGoal(goal: IntentGoal): DiscoveryGoal {
        return when (goal) {
            IntentGoal.FULL_DISCOVERY -> DiscoveryGoal.UNDERSTAND
            IntentGoal.REFACTORING_ANALYSIS -> DiscoveryGoal.REFACTOR
            IntentGoal.ARCHITECTURE_AUDIT -> DiscoveryGoal.ANALYZE
            IntentGoal.FLOW_MAPPING -> DiscoveryGoal.ANALYZE
            IntentGoal.DOCUMENTATION_GENERATION -> DiscoveryGoal.GENERATE
            IntentGoal.QUICK_OVERVIEW -> DiscoveryGoal.UNDERSTAND
        }
    }
    
    /**
     * Map IntentDepth to IntentDepth (API)
     */
    private fun mapIntentDepth(depth: ParserIntentDepth): ApiIntentDepth {
        return when (depth) {
            ParserIntentDepth.BROWSE -> ApiIntentDepth.BROWSE
            ParserIntentDepth.STANDARD -> ApiIntentDepth.STANDARD
            ParserIntentDepth.DEEP -> ApiIntentDepth.DEEP
        }
    }
    
    /**
     * Map QualityFocus to DiscoveryQuality
     */
    private fun mapQualityFocus(quality: QualityFocus): DiscoveryQuality {
        return when (quality) {
            QualityFocus.QUALITY -> DiscoveryQuality.THOROUGH
            QualityFocus.BALANCED -> DiscoveryQuality.BALANCED
            QualityFocus.QUANTITY -> DiscoveryQuality.FAST
        }
    }
    
    /**
     * Convert preset name to intent (placeholder)
     */
    private fun convertPresetToIntent(presetName: String): ApiDiscoveryIntent {
        // TODO: Implement proper PresetManager integration
        // For now, map common presets to intents
        return when (presetName.lowercase()) {
            "kotlin-agent", "spring-boot" -> createDefaultIntent()
            "conservative" -> ApiDiscoveryIntent(
                goal = DiscoveryGoal.UNDERSTAND,
                depth = ApiIntentDepth.STANDARD,
                quality = DiscoveryQuality.THOROUGH,
                layerFocus = listOf("structure", "logic", "flow")
            )
            "permissive" -> ApiDiscoveryIntent(
                goal = DiscoveryGoal.UNDERSTAND,
                depth = ApiIntentDepth.STANDARD,
                quality = DiscoveryQuality.FAST,
                layerFocus = listOf("code", "structure", "logic", "flow")
            )
            else -> createDefaultIntent()
        }
    }
    
    /**
     * Create default full discovery intent
     */
    private fun createDefaultIntent(): ApiDiscoveryIntent {
        return ApiDiscoveryIntent(
            goal = DiscoveryGoal.UNDERSTAND,
            depth = ApiIntentDepth.STANDARD,
            quality = DiscoveryQuality.BALANCED,
            layerFocus = listOf("vision", "structure", "logic", "flow", "code")
        )
    }
    
    /**
     * Parse depth string to DiscoveryDepth
     */
    private fun parseDepth(depthStr: String): DiscoveryDepth {
        return try {
            DiscoveryDepth.valueOf(depthStr.uppercase())
        } catch (e: IllegalArgumentException) {
            echo("Error: Invalid depth '$depthStr'. Valid options: BROWSE, STANDARD, DEEP", err = true)
            throw IllegalArgumentException("Invalid depth: $depthStr")
        }
    }
    
    /**
     * Run discovery with intent (includes cluster detection and parallel execution)
     */
    private suspend fun runWithIntent(
        pipeline: DiscoveryPipelineImpl,
        intent: ApiDiscoveryIntent,
        clusterId: String?,
        projectRoot: String
    ) {
        echo("Goal: ${intent.goal}")
        echo("Depth: ${intent.depth}")
        echo("Quality: ${intent.quality}")
        echo("Layer Focus: ${intent.layerFocus.joinToString(", ")}")
        
        // Auto-detect clusters if not specified
        val clusters = if (clusterId != null) {
            listOf(clusterId)
        } else {
            detectClusters(projectRoot)
        }
        
        echo("")
        echo("Clusters: ${clusters.size}")
        if (clusters.isEmpty()) {
            echo("[WARNING] No clusters detected, running single-cluster discovery")
            val (result, clusterDuration) = runSingleDiscovery(pipeline, intent, null)
            echo("  Cluster discovery completed in ${clusterDuration}ms")
            displayResults(result)
        } else {
            echo("Detected clusters: ${clusters.joinToString(", ")}")
            echo("")
            echo("Running parallel cluster discovery...")
            
            val startTime = System.currentTimeMillis()
            val clusterTimings = coroutineScope {
                clusters.map { cluster ->
                    async {
                        echo("  Discovering cluster: $cluster")
                        val (result, clusterDuration) = runSingleDiscovery(pipeline, intent, cluster)
                        echo("  Cluster $cluster completed in ${clusterDuration}ms")
                        Pair(result, clusterDuration)
                    }
                }.awaitAll()
            }
            val duration = System.currentTimeMillis() - startTime
            
            echo("")
            echo("Parallel discovery completed in ${duration}ms")
            
            // Flush all buffered links to disk in a single operation (same as SelfDiscoveryTest)
            pipeline.flushLinkBatch()
            echo("Flushed all links to disk")
            
            // Aggregate results
            val results = clusterTimings.map { it.first }
            val allArtifacts = results.flatMap { result -> result.artifacts }
            val allErrors = results.flatMap { result -> result.errors }
            val success = results.all { result -> result.success }
            
            // Log summary statistics
            echo("")
            echo("=== Discovery Summary ===")
            echo("Total duration: ${duration}ms (${duration / 1000}s)")
            echo("Clusters discovered: ${clusterTimings.size}")
            echo("Cluster timings:")
            clusterTimings.forEach { (result, clusterDuration) ->
                echo("  - ${result.metadata["clusterId"] ?: "unknown"}: ${clusterDuration}ms")
            }
            echo("Total artifacts: ${allArtifacts.size}")
            echo("Total errors: ${allErrors.size}")
            echo("Success: $success")
            echo("")
            
            displayResults(
                PipelineResult(
                    success = success,
                    artifacts = allArtifacts,
                    errors = allErrors,
                    metadata = mapOf("duration_ms" to duration.toString(), "clusters" to clusters.size.toString())
                )
            )
        }
    }
    
    /**
     * Run discovery with depth (legacy path)
     */
    private suspend fun runWithDepth(
        pipeline: DiscoveryPipelineImpl,
        depth: DiscoveryDepth,
        clusterId: String?,
        projectRoot: String
    ) {
        echo("Depth: $depth")
        
        // Auto-detect clusters if not specified
        val clusters = if (clusterId != null) {
            listOf(clusterId)
        } else {
            detectClusters(projectRoot)
        }
        
        echo("")
        echo("Clusters: ${clusters.size}")
        if (clusters.isEmpty()) {
            echo("[WARNING] No clusters detected, running single-cluster discovery")
            val result = pipeline.discover(depth = depth, clusterId = null, contracts = emptyList())
            displayResults(result)
        } else {
            echo("Detected clusters: ${clusters.joinToString(", ")}")
            echo("")
            echo("Running parallel cluster discovery...")
            
            val startTime = System.currentTimeMillis()
            val clusterTimings = coroutineScope {
                clusters.map { cluster ->
                    async {
                        echo("  Discovering cluster: $cluster")
                        val clusterStartTime = System.currentTimeMillis()
                        val result = pipeline.discover(depth = depth, clusterId = cluster, contracts = emptyList())
                        val clusterDuration = System.currentTimeMillis() - clusterStartTime
                        echo("  Cluster $cluster completed in ${clusterDuration}ms")
                        Pair(result, clusterDuration)
                    }
                }.awaitAll()
            }
            val duration = System.currentTimeMillis() - startTime
            
            echo("")
            echo("Parallel discovery completed in ${duration}ms")
            
            // Flush all buffered links to disk in a single operation (same as SelfDiscoveryTest)
            pipeline.flushLinkBatch()
            echo("Flushed all links to disk")
            
            // Aggregate results
            val results = clusterTimings.map { it.first }
            val allArtifacts = results.flatMap { result -> result.artifacts }
            val allErrors = results.flatMap { result -> result.errors }
            val success = results.all { result -> result.success }
            
            // Log summary statistics
            echo("")
            echo("=== Discovery Summary ===")
            echo("Total duration: ${duration}ms (${duration / 1000}s)")
            echo("Clusters discovered: ${clusterTimings.size}")
            echo("Cluster timings:")
            clusterTimings.forEach { (result, clusterDuration) ->
                echo("  - ${result.metadata["clusterId"] ?: "unknown"}: ${clusterDuration}ms")
            }
            echo("Total artifacts: ${allArtifacts.size}")
            echo("Total errors: ${allErrors.size}")
            echo("Success: $success")
            echo("")
            
            displayResults(
                PipelineResult(
                    success = success,
                    artifacts = allArtifacts,
                    errors = allErrors,
                    metadata = mapOf("duration_ms" to duration.toString(), "clusters" to clusters.size.toString())
                )
            )
        }
    }
    
    /**
     * Run single cluster discovery with timing
     * Use depth-based discovery directly to match SelfDiscoveryTest approach
     * Intent-based discovery adds overhead (validation + resolution) per cluster
     */
    private suspend fun runSingleDiscovery(
        pipeline: DiscoveryPipelineImpl,
        intent: ApiDiscoveryIntent,
        clusterId: String?
    ): Pair<PipelineResult, Long> {
        val startTime = System.currentTimeMillis()
        
        // Map intent depth to discovery depth
        val discoveryDepth = when (intent.depth) {
            com.i2vision.discover.api.models.IntentDepth.BROWSE -> com.i2vision.discover.api.models.DiscoveryDepth.BROWSE
            com.i2vision.discover.api.models.IntentDepth.STANDARD -> com.i2vision.discover.api.models.DiscoveryDepth.STANDARD
            com.i2vision.discover.api.models.IntentDepth.DEEP -> com.i2vision.discover.api.models.DiscoveryDepth.DEEP
        }
        // Use depth-based discovery directly (same as SelfDiscoveryTest) to avoid intent resolution overhead
        val result = pipeline.discover(depth = discoveryDepth, clusterId = clusterId, contracts = emptyList())
        
        val duration = System.currentTimeMillis() - startTime
        return Pair(result, duration)
    }
    
    /**
     * Detect clusters using SignatureBuilder (same approach as self-discovery test)
     */
    private fun detectClusters(projectRoot: String): List<String> {
        return try {
            log.info("[CLI] Detecting clusters for project root: $projectRoot")
            
            val signatureBuilder = SignatureBuilder(
                projectRoot = projectRoot,
                confidenceThreshold = 0.7,
                useLlmForLowConfidence = false,
                llmClient = null
            )
            val signature = signatureBuilder.build()
            
            // Filter out empty clusters (with 0 files)
            val validClusters = signature.clusters.filter { it.fileCount > 0 }
            
            log.info("[CLI] Detected ${validClusters.size} clusters from SignatureBuilder")
            validClusters.forEach { cluster ->
                log.info("[CLI]   - ${cluster.name} (${cluster.fileCount} files)")
            }
            
            validClusters.map { it.name }
        } catch (e: Exception) {
            log.warn("[CLI] Failed to detect clusters with SignatureBuilder: ${e.message}")
            emptyList()
        }
    }
    
    /**
     * Display discovery results
     */
    private fun displayResults(result: PipelineResult) {
        echo("")
        echo("Discovery Results:")
        echo("  Success: ${result.success}")
        echo("  Artifacts: ${result.artifacts.size}")
        echo("  Errors: ${result.errors.size}")
        
        if (result.metadata.containsKey("duration_ms")) {
            echo("  Duration: ${result.metadata["duration_ms"]}ms")
        }
        
        if (!result.success) {
            echo("")
            echo("Errors:")
            result.errors.forEach { echo("  - $it", err = true) }
        }
        
        if (result.success) {
            echo("")
            echo("Metadata:")
            result.metadata.forEach { (key, value) ->
                echo("  $key: $value")
            }
            
            echo("")
            echo("Artifacts:")
            result.artifacts.forEach { artifact ->
                echo("  - Layer: ${artifact.layer}")
                echo("    Path: ${artifact.path}")
            }
            
            val outputDir = output ?: I2VisionPaths.getProjectCacheDir(projectPath).absolutePath
            echo("")
            echo("Artifacts written to: $outputDir")
        }
        
        // Format output based on flags
        if (json) {
            echo("")
            echo(consoleOutput.formatDiscoveryResult(result, "json"))
        } else if (yaml) {
            echo("")
            echo(consoleOutput.formatDiscoveryResult(result, "yaml"))
        }
    }
}

/**
 * PrintStream that writes to multiple output streams.
 * Used to tee output to both console and file.
 */
class TeePrintStream(vararg streams: PrintStream) : PrintStream(TeeOutputStream(*streams)) {
    private class TeeOutputStream(vararg streams: PrintStream) : OutputStream() {
        private val streams = streams.toList()
        
        override fun write(b: Int) {
            streams.forEach { it.write(b) }
        }
        
        override fun write(b: ByteArray, off: Int, len: Int) {
            streams.forEach { it.write(b, off, len) }
        }
        
        override fun flush() {
            streams.forEach { it.flush() }
        }
    }
}
