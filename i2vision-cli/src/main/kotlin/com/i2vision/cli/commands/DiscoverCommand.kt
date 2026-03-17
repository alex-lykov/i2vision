package com.i2vision.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.arguments.argument
import com.i2vision.discover.api.models.DiscoveryDepth
import com.i2vision.discover.pipeline.DiscoveryPipelineImpl
import com.i2vision.storage.api.CacheStore
import com.i2vision.storage.impl.FileCacheStore
import com.i2vision.discover.intent.IntentResolverImpl
import com.i2vision.cli.output.ConsoleOutput
import kotlinx.coroutines.runBlocking
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
    private val depth by option("-d", "--depth", help = "Discovery depth (BROWSE, STANDARD, DEEP)")
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
        
        val depthValue = depth ?: "STANDARD"
        val discoveryDepth = try {
            DiscoveryDepth.valueOf(depthValue.uppercase())
        } catch (e: IllegalArgumentException) {
            echo("Error: Invalid depth '$depthValue'. Valid options: BROWSE, STANDARD, DEEP", err = true)
            throw IllegalArgumentException("Invalid depth: $depthValue")
        }
        
        echo("Depth: $discoveryDepth")
        
        // Create IntentResolver and DiscoveryPipeline
        val intentResolver = IntentResolverImpl()
        val cacheStore = FileCacheStore(File(projectPath))
        val pipeline = DiscoveryPipelineImpl(projectPath, intentResolver, cacheStore)
        
        echo("")
        echo("Running discovery pipeline...")
        
        val startTime = System.currentTimeMillis()
        val result = runBlocking {
            pipeline.discover(
                depth = discoveryDepth,
                clusterId = cluster,
                contracts = emptyList()
            )
        }
        val duration = System.currentTimeMillis() - startTime
        
        echo("")
        echo("Discovery Results:")
        echo("  Success: ${result.success}")
        echo("  Artifacts: ${result.artifacts.size}")
        echo("  Errors: ${result.errors.size}")
        echo("  Duration: ${duration}ms")
        
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
            
            val outputDir = output ?: ".semantic-cache"
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
