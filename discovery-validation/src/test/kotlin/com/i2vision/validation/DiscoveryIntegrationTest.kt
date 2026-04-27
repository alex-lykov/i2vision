/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.validation

import com.i2vision.arch.signature.SignatureBuilder
import com.i2vision.discover.api.models.DiscoveryDepth
import com.i2vision.discover.intent.IntentResolverImpl
import com.i2vision.discover.pipeline.DiscoveryPipelineImpl
import com.i2vision.intent.IntentParser
import com.i2vision.storage.I2VisionPaths
import com.i2vision.storage.impl.FileCacheStore
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.nio.file.Files
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Modular Discovery Integration Tests
 *
 * Tests the new modular discovery structure (i2vision-discover, i2vision-instant, i2vision-mcp)
 * Adapted from the legacy orchestrator integration tests to work with the new module ecosystem.
 *
 * Test Phases:
 * Phase 0: Architecture detection
 * Phase 1: Intent resolution
 * Phase 2: Discovery pipeline execution
 * Phase 3: Artifact validation
 */
class DiscoveryIntegrationTest {

    // YAML config loader
    private val yaml = Yaml()

    /**
     * Get sketch project root for testing
     */
    private fun getSketchRoot(sketchName: String = "aggregator-pure"): File {
        val sketchPath = javaClass.classLoader.getResource("sketches/$sketchName")
            ?: throw IllegalArgumentException("Sketch not found: $sketchName")
        return File(sketchPath.toURI()).canonicalFile
    }

    /**
     * Get all available sketches for testing
     */
    private fun getAllSketches(): List<String> {
        val sketchesDir = File(javaClass.classLoader.getResource("sketches").toURI())
        return sketchesDir.listFiles()
            ?.filter { it.isDirectory }
            ?.map { it.name }
            ?.sorted() ?: emptyList()
    }

    /**
     * Setup discovery pipeline with standard configuration
     */
    private fun setupDiscovery(projectRoot: File): Triple<DiscoveryPipelineImpl, FileCacheStore, File> {
        val cacheDir = I2VisionPaths.getProjectCacheDir(projectRoot.path)
        val intentResolver = IntentResolverImpl()
        val cacheStore = FileCacheStore(cacheDir)
        val discovery = DiscoveryPipelineImpl(projectRoot.path, intentResolver, cacheStore)
        return Triple(discovery, cacheStore, cacheDir)
    }

    /**
     * Load sketch configuration from YAML file
     */
    private fun loadSketchConfig(configName: String): SketchConfig {
        val configPath = javaClass.classLoader.getResource("sketch-configs/$configName.yaml")
            ?: throw IllegalArgumentException("Config not found: $configName.yaml")
        val config = yaml.load(configPath.openStream()) as Map<String, Any>
        return SketchConfig(
            sketch = config["sketch"] as String,
            description = config["description"] as String,
            expectedClusters = (config["expected_clusters"] as List<*>).map { it as String },
            expectedLayersPerCluster = (config["expected_layers_per_cluster"] as List<*>).map { it as String },
            requiredLayers = (config["required_layers"] as List<*>).map { it as String }
        )
    }

    // Data classes for config
    data class SketchConfig(
        val sketch: String,
        val description: String,
        val expectedClusters: List<String>,
        val expectedLayersPerCluster: List<String>,
        val requiredLayers: List<String>
    )

    // ========== PHASE 0: Architecture Detection ==========

    @Test
    fun `phase0 architecture detection identifies project structure`() = runTest {
        val projectRoot = getSketchRoot()
        val signatureBuilder = SignatureBuilder(projectRoot.path)
        val signature = signatureBuilder.build()

        assertTrue(signature.buildSystem != null, "Build system should be detected")
        assertTrue(signature.clusters.isNotEmpty(), "Clusters should be detected")
        assertTrue(signature.confidence.values.average() > 0, "Confidence should be positive")
    }

    @Test
    fun `phase0 architecture detection provides cluster information`() = runTest {
        val projectRoot = getSketchRoot()
        val signatureBuilder = SignatureBuilder(projectRoot.path)
        val signature = signatureBuilder.build()

        signature.clusters.forEach { cluster ->
            assertTrue(cluster.name.isNotBlank(), "Cluster name should not be blank")
            assertTrue(cluster.fileCount >= 0, "File count should be non-negative")
        }
    }

    // ========== PHASE 1: Intent Resolution ==========

    @Test
    fun `phase1 intent resolution parses full discovery intent`() {
        val intentParser = IntentParser
        val intent = intentParser.parse(mapOf("intent" to "full_discovery"))

        assertNotNull(intent, "Intent should be parsed")
        assertNotNull(intent.goal, "Intent goal should be set")
    }

    @Test
    fun `phase1 intent resolution parses refactoring intent`() {
        val intentParser = IntentParser
        val intent = intentParser.parse(mapOf("intent" to "refactoring_analysis"))

        assertNotNull(intent, "Intent should be parsed")
        assertNotNull(intent.goal, "Intent goal should be set")
    }

    // ========== PHASE 2: Discovery Pipeline ==========

    @Test
    fun `phase2 discovery pipeline executes with standard depth`() = runTest {
        val projectRoot = getSketchRoot()
        val (discovery, _, _) = setupDiscovery(projectRoot)

        val result = discovery.discover(
            depth = DiscoveryDepth.STANDARD,
            clusterId = null,
            contracts = emptyList()
        )

        assertNotNull(result, "Discovery result should not be null")
    }

    @Test
    fun `phase2 discovery pipeline executes with quick depth`() = runTest {
        val projectRoot = getSketchRoot()
        val (discovery, _, _) = setupDiscovery(projectRoot)

        val result = discovery.discover(
            depth = DiscoveryDepth.BROWSE,
            clusterId = null,
            contracts = emptyList()
        )

        assertNotNull(result, "Discovery result should not be null")
    }

    @Test
    fun `phase2 discovery pipeline with specific cluster`() = runTest {
        val projectRoot = getSketchRoot()
        val signatureBuilder = SignatureBuilder(projectRoot.path)
        val signature = signatureBuilder.build()
        val targetCluster = signature.clusters.firstOrNull()?.name

        if (targetCluster != null) {
            val (discovery, _, _) = setupDiscovery(projectRoot)
            val result = discovery.discover(
                depth = DiscoveryDepth.STANDARD,
                clusterId = targetCluster,
                contracts = emptyList()
            )

            assertNotNull(result, "Cluster-specific discovery should complete")
        }
    }

    // ========== PHASE 3: Artifact Validation ==========

    @Test
    fun `phase3 semantic cache structure is created`() = runTest {
        val projectRoot = getSketchRoot()
        val (discovery, _, _) = setupDiscovery(projectRoot)

        val result = discovery.discover(
            depth = DiscoveryDepth.STANDARD,
            clusterId = null,
            contracts = emptyList()
        )

        val semanticCache = I2VisionPaths.getProjectCacheDir(projectRoot.path)
        assertTrue(semanticCache.exists(), "Semantic cache directory should be created")
    }

    @Test
    fun `phase3 artifact files are written to cache`() = runTest {
        val projectRoot = getSketchRoot()
        val signatureBuilder = SignatureBuilder(projectRoot.path)
        val signature = signatureBuilder.build()
        val targetCluster = signature.clusters.firstOrNull()?.name ?: "project"
        val (discovery, _, _) = setupDiscovery(projectRoot)

        val result = discovery.discover(
            depth = DiscoveryDepth.STANDARD,
            clusterId = targetCluster,
            contracts = emptyList()
        )

        val semanticCache = I2VisionPaths.getProjectCacheDir(projectRoot.path)
        val artifactFiles = semanticCache.walkTopDown()
            .filter { it.isFile }
            .filter { it.extension in listOf("yaml", "yml", "json") }
            .toList()

        assertTrue(artifactFiles.isNotEmpty(), "Artifact files should be written")
    }

    // ========== INTEGRATION: End-to-End Flow ==========

    @Test
    fun `end to end discovery flow from architecture to artifacts`() = runTest {
        val projectRoot = getSketchRoot()

        // Step 1: Architecture detection
        val signatureBuilder = SignatureBuilder(projectRoot.path)
        val signature = signatureBuilder.build()
        assertTrue(signature.clusters.isNotEmpty(), "Architecture should be detected")

        // Step 2: Intent resolution
        val intentParser = IntentParser
        val intent = intentParser.parse(mapOf("intent" to "full_discovery"))
        assertNotNull(intent, "Intent should be resolved")

        // Step 3: Discovery pipeline
        val (discovery, _, _) = setupDiscovery(projectRoot)
        val result = discovery.discover(
            depth = DiscoveryDepth.STANDARD,
            clusterId = signature.clusters.firstOrNull()?.name,
            contracts = emptyList()
        )

        // Step 4: Verify results
        assertNotNull(result, "Discovery should complete")
    }

    @Test
    fun `end to end discovery over all sketches`() = runTest {
        val sketches = getAllSketches()
        assertTrue(sketches.isNotEmpty(), "Should have sketches to test")

        sketches.forEach { sketchName ->
            println("[Test] Running discovery for sketch: $sketchName")
            
            try {
                val projectRoot = getSketchRoot(sketchName)

                // Architecture detection
                val signatureBuilder = SignatureBuilder(projectRoot.path)
                val signature = signatureBuilder.build()
                
                // Discovery pipeline
                val (discovery, _, _) = setupDiscovery(projectRoot)
                val result = discovery.discover(
                    depth = DiscoveryDepth.STANDARD,
                    clusterId = signature.clusters.firstOrNull()?.name,
                    contracts = emptyList()
                )

                assertNotNull(result, "Discovery should complete for sketch: $sketchName")
                println("[Test] ✓ Discovery completed for sketch: $sketchName")
            } catch (e: Exception) {
                println("[Test] ✗ Discovery failed for sketch: $sketchName - ${e.message}")
                throw e
            }
        }
    }

    @Test
    fun `end to end discovery with cluster specific target`() = runTest {
        val projectRoot = getSketchRoot()

        // Detect architecture
        val signatureBuilder = SignatureBuilder(projectRoot.path)
        val signature = signatureBuilder.build()

        val targetCluster = signature.clusters.firstOrNull()
        if (targetCluster != null) {
            // Run discovery for specific cluster
            val (discovery, _, _) = setupDiscovery(projectRoot)
            val result = discovery.discover(
                depth = DiscoveryDepth.STANDARD,
                clusterId = targetCluster.name,
                contracts = emptyList()
            )

            assertNotNull(result, "Cluster-specific discovery should complete")
        }
    }

    // ========== TEMPORARY PROJECT STRUCTURE TESTS ==========

    @Test
    fun `temporary test project structure creation`() {
        val tempDir = Files.createTempDirectory("test-project-").toFile()

        try {
            // Create basic project structure
            val srcDir = File(tempDir, "src/main/kotlin/com/example")
            srcDir.mkdirs()

            val serviceFile = File(srcDir, "UserService.kt")
            serviceFile.writeText(
                """
                package com.example
                
                class UserService {
                    fun findUser(id: String): User? {
                        return null
                    }
                }
            """.trimIndent()
            )

            assertTrue(srcDir.exists(), "Source directory should exist")
            assertTrue(serviceFile.exists(), "Service file should exist")
        } finally {
            tempDir.deleteRecursively()
        }
    }

}
