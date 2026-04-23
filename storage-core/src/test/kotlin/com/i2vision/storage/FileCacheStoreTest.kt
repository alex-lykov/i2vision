package com.i2vision.storage.impl

import com.i2vision.storage.model.ArtifactRef
import com.i2vision.storage.model.Layer
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import java.io.File

/**
 * Tests for FileCacheStore.
 * Tests file hash change detection and cache operations.
 */
class FileCacheStoreTest {

    private fun withTempDir(block: (File) -> Unit) {
        val tempDir = java.nio.file.Files.createTempDirectory("cache-test").toFile()
        try {
            block(tempDir)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `should store and retrieve artifact with hash`() = withTempDir { tempDir ->
        // Given: FileCacheStore and artifact
        val cacheStore = FileCacheStore(tempDir)
        val ref = ArtifactRef("test-module", Layer.STRUCTURE, "components.yaml")
        val content = "test content".toByteArray()

        // When
        runBlocking {
            cacheStore.put(ref, content)
            val retrieved = cacheStore.get(ref)
        }

        // Then
        runBlocking {
            val retrieved = cacheStore.get(ref)
            assertNotNull(retrieved)
            assertEquals(content.toList(), retrieved.content.toList())
            assertNotNull(retrieved.metadata.hash)
            assertTrue(retrieved.metadata.hash.isNotEmpty())
        }
    }

    @Test
    fun `should detect file hash change`() = withTempDir { tempDir ->
        // Given: Stored artifact
        val cacheStore = FileCacheStore(tempDir)
        val ref = ArtifactRef("test-module", Layer.STRUCTURE, "components.yaml")
        val originalContent = "original content".toByteArray()

        runBlocking {
            cacheStore.put(ref, originalContent)
            val original = cacheStore.get(ref)
            val originalHash = original!!.metadata.hash

            // When: Content changes
            val newContent = "modified content".toByteArray()
            cacheStore.put(ref, newContent)

            // Then: Hash should change
            val updated = cacheStore.get(ref)
            assertNotNull(updated)
            assertTrue(updated.metadata.hash != originalHash, "Hash should change when content changes")
        }
    }

    @Test
    fun `should return null for non-existent artifact`() = withTempDir { tempDir ->
        // Given: FileCacheStore
        val cacheStore = FileCacheStore(tempDir)
        val ref = ArtifactRef("test-module", Layer.STRUCTURE, "nonexistent.yaml")

        // When
        val result = runBlocking {
            cacheStore.get(ref)
        }

        // Then
        assertEquals(null, result)
    }

    @Test
    fun `should list artifacts by module and layer`() = withTempDir { tempDir ->
        // Given: Multiple artifacts stored
        val cacheStore = FileCacheStore(tempDir)
        val ref1 = ArtifactRef("test-module", Layer.STRUCTURE, "components.yaml")
        val ref2 = ArtifactRef("test-module", Layer.STRUCTURE, "dependencies.yaml")
        val ref3 = ArtifactRef("test-module", Layer.LOGIC, "business-rules.yaml")

        runBlocking {
            cacheStore.put(ref1, "content1".toByteArray())
            cacheStore.put(ref2, "content2".toByteArray())
            cacheStore.put(ref3, "content3".toByteArray())

            // When: List structure layer artifacts
            val structureArtifacts = cacheStore.list("test-module", Layer.STRUCTURE)

            // Then
            assertTrue(structureArtifacts.size >= 2, "Should have at least 2 structure artifacts")
            assertTrue(structureArtifacts.any { it.name == "components.yaml" })
            assertTrue(structureArtifacts.any { it.name == "dependencies.yaml" })
        }
    }

    @Test
    fun `should clear module cache`() = withTempDir { tempDir ->
        // Given: Stored artifacts in module
        val cacheStore = FileCacheStore(tempDir)
        val ref1 = ArtifactRef("test-module", Layer.STRUCTURE, "components.yaml")
        val ref2 = ArtifactRef("test-module", Layer.LOGIC, "business-rules.yaml")

        runBlocking {
            cacheStore.put(ref1, "content1".toByteArray())
            cacheStore.put(ref2, "content2".toByteArray())

            // When: Clear module
            val count = cacheStore.clear("test-module")

            // Then
            assertTrue(count >= 1, "Should clear at least 1 directory")
            assertEquals(null, cacheStore.get(ref1))
            assertEquals(null, cacheStore.get(ref2))
        }
    }
}
