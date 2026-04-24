/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.discover.artifact

import com.i2vision.discover.pipeline.Priority
import com.i2vision.discover.pipeline.Source
import com.i2vision.discover.pipeline.VisionRequirement
import com.i2vision.storage.impl.FileCacheStore
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertTrue
import java.io.File

/**
 * Unit tests for ArtifactWriter
 */
class ArtifactWriterTest {

    @Test
    fun `should handle vision requirements with null data`() = runBlocking {
        // Given: A temporary cache directory
        val tempDir = java.nio.file.Files.createTempDirectory("artifact-writer-test").toFile()
        try {
            val cacheStore = FileCacheStore(tempDir)
            val artifactWriter = ArtifactWriter(tempDir.absolutePath, cacheStore)

            // When: Writing a vision requirement with null data
            val requirement = VisionRequirement(
                id = "test-requirement",
                title = "Test Requirement",
                docRef = "",
                rationaleRef = null,
                acceptanceCriteriaRefs = emptyList(),
                priority = Priority.P2,
                source = Source.USER_INPUT,
                confidence = 0.0,
                evidence = emptyList()
            )

            // Then: Should not throw NullPointerException
            artifactWriter.writeVisionArtifacts(
                moduleName = "test-module",
                requirements = listOf(requirement),
                constraints = emptyList()
            )

            // Should complete without error
            assertTrue(true, "Should handle null data without throwing")
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `should handle vision requirements with empty nested structures`() = runBlocking {
        // Given: A temporary cache directory
        val tempDir = java.nio.file.Files.createTempDirectory("artifact-writer-test").toFile()
        try {
            val cacheStore = FileCacheStore(tempDir)
            val artifactWriter = ArtifactWriter(tempDir.absolutePath, cacheStore)

            // When: Writing a vision requirement with empty nested data
            val requirement = VisionRequirement(
                id = "test-requirement",
                title = "Test Requirement",
                docRef = "",
                rationaleRef = "",
                acceptanceCriteriaRefs = emptyList(),
                priority = Priority.P2,
                source = Source.USER_INPUT,
                confidence = 0.0,
                evidence = emptyList()
            )

            // Then: Should not throw NullPointerException
            artifactWriter.writeVisionArtifacts(
                moduleName = "test-module",
                requirements = listOf(requirement),
                constraints = emptyList()
            )

            // Should complete without error
            assertTrue(true, "Should handle empty nested structures without throwing")
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
