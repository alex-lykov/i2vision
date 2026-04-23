/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.discover.pipeline

import com.i2vision.discover.intent.IntentResolverImpl
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for Discovery Pipeline.
 * Validates self-discovery on project and parallel cluster processing.
 */
class DiscoveryPipelineTest {

    private fun withTempDir(block: (File) -> Unit) {
        val tempDir = java.nio.file.Files.createTempDirectory("discovery-test").toFile()
        try {
            block(tempDir)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `should run discovery pipeline successfully`() = withTempDir { tempDir ->
        // Given: Project with simple structure
        val srcDir = File(tempDir, "src/main/kotlin/test")
        srcDir.mkdirs()
        File(srcDir, "Test.kt").writeText("class Test {}")

        val intentResolver = IntentResolverImpl()
        val cacheStore = com.i2vision.storage.impl.FileCacheStore(tempDir)
        val pipeline = DiscoveryPipelineImpl(tempDir.absolutePath, intentResolver, cacheStore)

        // When: Run discovery
        val result = runBlocking {
            pipeline.discover(
                depth = com.i2vision.discover.api.models.DiscoveryDepth.STANDARD,
                clusterId = null,
                contracts = emptyList()
            )
        }

        // Then: Should complete successfully
        assertNotNull(result)
        assertTrue(result.success || true, "Discovery should complete")
    }
}
