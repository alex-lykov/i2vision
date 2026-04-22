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
import com.i2vision.intent.IntentGoal
import com.i2vision.intent.IntentDepth as ParserIntentDepth
import com.i2vision.intent.QualityFocus
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
    
    private val consoleOutput = ConsoleOutput()
    
    override fun run() {
        log.info("[CLI] Discover command started for project: $projectPath")
        echo("Running discovery on: $projectPath")
        
        val projectFile = File(projectPath)
        if (!projectFile.exists()) {
            echo("Error: Project path does not exist: $projectPath", err = true)
            throw IllegalArgumentException("Project path does not exist: $projectPath")
        }
        
        // Create IntentResolver and DiscoveryPipeline
        val intentResolver = IntentResolverImpl()
        val projectCacheDir = I2VisionPaths.getProjectCacheDir(projectPath)
        val cacheStore = FileCacheStore(projectCacheDir)
        val pipeline = DiscoveryPipelineImpl(projectPath, intentResolver, cacheStore)
        
        // Wrap suspend functions in runBlocking
        runBlocking {
            when {
                // PATH 1: Preset-based (placeholder)
                preset != null -> {
                    echo("Using preset: $preset")
                    echo("[INFO] Preset-based discovery not yet implemented, falling back to intent-based")
                    // TODO: Implement PresetManager when available
                    // For now, treat preset as intent
                    val discoveryIntent = convertPresetToIntent(preset!!)
                    runWithIntent(pipeline, discoveryIntent, cluster)
                }
                
                // PATH 2: Intent-based (PRIMARY)
                intent != null -> {
                    echo("Intent: $intent")
                    val discoveryIntent = parseIntent(intent!!)
                    runWithIntent(pipeline, discoveryIntent, cluster)
                }
                
                // PATH 3: Legacy depth-based
                depth != null -> {
                    echo("[WARNING] --depth is deprecated. Use --intent instead.")
                    val discoveryDepth = parseDepth(depth!!)
                    runWithDepth(pipeline, discoveryDepth, cluster)
                }
                
                // DEFAULT: Full discovery intent
                else -> {
                    echo("Defaulting to --intent=full_discovery")
                    val discoveryIntent = createDefaultIntent()
                    runWithIntent(pipeline, discoveryIntent, cluster)
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
        clusterId: String?
    ) {
        echo("Goal: ${intent.goal}")
        echo("Depth: ${intent.depth}")
        echo("Quality: ${intent.quality}")
        echo("Layer Focus: ${intent.layerFocus.joinToString(", ")}")
        
        // Auto-detect clusters if not specified
        val clusters = if (clusterId != null) {
            listOf(clusterId)
        } else {
            detectClusters(projectPath)
        }
        
        echo("")
        echo("Clusters: ${clusters.size}")
        if (clusters.isEmpty()) {
            echo("[WARNING] No clusters detected, running single-cluster discovery")
            val result = runSingleDiscovery(pipeline, intent, null)
            displayResults(result)
        } else {
            echo("Detected clusters: ${clusters.joinToString(", ")}")
            echo("")
            echo("Running parallel cluster discovery...")
            
            val startTime = System.currentTimeMillis()
            val results = coroutineScope {
                clusters.map { cluster ->
                    async {
                        echo("  Discovering cluster: $cluster")
                        runSingleDiscovery(pipeline, intent, cluster)
                    }
                }.awaitAll()
            }
            val duration = System.currentTimeMillis() - startTime
            
            echo("")
            echo("Parallel discovery completed in ${duration}ms")
            
            // Aggregate results
            val allArtifacts = results.flatMap { result -> result.artifacts }
            val allErrors = results.flatMap { result -> result.errors }
            val success = results.all { result -> result.success }
            
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
        clusterId: String?
    ) {
        echo("Depth: $depth")
        
        // Auto-detect clusters if not specified
        val clusters = if (clusterId != null) {
            listOf(clusterId)
        } else {
            detectClusters(projectPath)
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
            val results = coroutineScope {
                clusters.map { cluster ->
                    async {
                        echo("  Discovering cluster: $cluster")
                        pipeline.discover(depth = depth, clusterId = cluster, contracts = emptyList())
                    }
                }.awaitAll()
            }
            val duration = System.currentTimeMillis() - startTime
            
            echo("")
            echo("Parallel discovery completed in ${duration}ms")
            
            // Aggregate results
            val allArtifacts = results.flatMap { result -> result.artifacts }
            val allErrors = results.flatMap { result -> result.errors }
            val success = results.all { result -> result.success }
            
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
     * Run single cluster discovery
     */
    private suspend fun runSingleDiscovery(
        pipeline: DiscoveryPipelineImpl,
        intent: ApiDiscoveryIntent,
        clusterId: String?
    ): PipelineResult {
        return pipeline.discover(intent = intent, clusterId = clusterId, contracts = emptyList())
    }
    
    /**
     * Detect clusters using directory structure and build files
     * Simplified version that doesn't require architecture-types module
     */
    private fun detectClusters(projectPath: String): List<String> {
        return try {
            val root = File(projectPath)
            val clusters = mutableListOf<String>()
            
            // Check for Gradle multi-module project
            val settingsFile = File(root, "settings.gradle.kts").takeIf { it.exists() }
                ?: File(root, "settings.gradle").takeIf { it.exists() }
            
            if (settingsFile != null) {
                val content = settingsFile.readText()
                // Extract module names from settings.gradle.kts
                val modulePattern = Regex("""include\("([^"]+)"\)""")
                modulePattern.findAll(content).forEach { match ->
                    val moduleName = match.groupValues[1].removePrefix(":")
                    // Convert Gradle module path (with colons) to directory path (with slashes)
                    val directoryPath = moduleName.replace(":", "/")
                    clusters.add(directoryPath)
                }
            } else {
                // Fallback: detect top-level directories with source files
                root.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }?.forEach { dir ->
                    val hasSourceFiles = dir.walkTopDown()
                        .any { it.isFile && it.extension in setOf("kt", "java", "scala", "groovy", "py", "js", "ts", "go", "rs") }
                    if (hasSourceFiles) {
                        clusters.add(dir.name)
                    }
                }
            }
            
            log.info("[CLI] Detected ${clusters.size} clusters: ${clusters.joinToString(", ")}")
            clusters
        } catch (e: Exception) {
            log.warn("[CLI] Failed to detect clusters: ${e.message}")
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
                echo("    Confidence: ${"%.2f".format(artifact.confidence * 100)}%")
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
