package com.i2vision.validation

import com.i2vision.arch.signature.SignatureBuilder
import com.i2vision.discover.pipeline.DiscoveryPipelineImpl
import com.i2vision.storage.api.CacheStore
import com.i2vision.storage.impl.FileCacheStore
import com.i2vision.storage.I2VisionPaths
import com.i2vision.discover.api.IntentResolver
import com.i2vision.discover.intent.IntentResolverImpl
import com.i2vision.discover.api.models.DiscoveryDepth
import com.i2vision.intent.IntentParser
import kotlinx.coroutines.runBlocking
import java.io.File

fun main() = runBlocking {
    val projectRoot = File(".").absoluteFile
    println("=== i2vision Self-Discovery Test ===")
    println("Project: ${projectRoot.canonicalPath}")
    println()
    
    // Step 1: Architecture Detection
    println("--- Architecture Detection ---")
    val signatureBuilder = SignatureBuilder(projectRoot.absolutePath)
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
    println("Overall Confidence: ${signature.confidence}")
    println()
    
    // Step 2: Intent Resolution
    println("--- Intent Resolution ---")
    val intentParser = IntentParser
    val intent = intentParser.parse(mapOf("intent" to "full_discovery")) 
        ?: error("Invalid intent 'full_discovery'")
    println("Intent: ${intent.goal}")
    println("Focus: ${intent.focus}")
    println("Depth: ${intent.depth}")
    println()
    
    // Step 3: Discovery Pipeline
    println("--- Discovery Pipeline Execution ---")
    val intentResolver = IntentResolverImpl()
    val cacheDir = I2VisionPaths.getProjectCacheDir(projectRoot.absolutePath)
    val cacheStore = FileCacheStore(cacheDir)
    val discovery = DiscoveryPipelineImpl(projectRoot.absolutePath, intentResolver, cacheStore)
    
    // Use the first detected cluster from architecture detection
    val targetClusterId = signature.clusters.firstOrNull()?.name ?: "project"
    println("Target Cluster: $targetClusterId")
    
    val startTime = System.currentTimeMillis()
    val result = discovery.discover(
        depth = DiscoveryDepth.STANDARD,
        clusterId = targetClusterId,
        contracts = emptyList()
    )
    val duration = System.currentTimeMillis() - startTime
    
    println("Duration: ${duration}ms")
    println("Success: ${result.success}")
    println("Artifacts: ${result.artifacts.size}")
    println("Errors: ${result.errors.size}")
    println()
    
    // Step 4: Results Summary
    println("--- Discovery Results ---")
    println("Success: ${result.success}")
    println("Artifacts: ${result.artifacts.size}")
    if (result.errors.isNotEmpty()) {
        println("Errors:")
        result.errors.forEach { println("  - $it") }
    }
    println("Metadata: ${result.metadata}")
    println()
    
    // Step 5: Key Findings
    println("--- Key Findings ---")
    println("✅ Architecture detection completed")
    println("✅ Intent resolution completed")
    println("✅ Discovery pipeline executed")
    println("Note: Current discovery implementation is minimal - full artifact generation pending orchestrator internals integration")
    println()
    println("=== Self-Discovery Complete ===")
}
