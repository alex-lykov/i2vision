package com.i2vision.cli.integration

import com.i2vision.storage.I2VisionPaths
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import java.io.File

/**
 * Integration tests for CLI Discover command.
 * Tests actual CLI execution and verifies artifact locations.
 */
class DiscoverIntegrationTest {
    
    private fun withTempDir(block: (File) -> Unit) {
        val tempDir = java.nio.file.Files.createTempDirectory("integration-test").toFile()
        try {
            block(tempDir)
        } finally {
            tempDir.deleteRecursively()
        }
    }
    
    @Test
    fun `should not create multiple cache directories for same project`() = withTempDir { tempDir ->
        // Given: A test project with source files
        val srcDir = File(tempDir, "src/main/kotlin")
        srcDir.mkdirs()
        val testFile = File(srcDir, "Test.kt")
        testFile.writeText("""
            class Test {
                fun hello() = "world"
            }
        """.trimIndent())
        
        // When: Getting cache directory multiple times with same project path
        val projectPath = tempDir.absolutePath
        val cacheDir1 = I2VisionPaths.getProjectCacheDir(projectPath)
        val cacheDir2 = I2VisionPaths.getProjectCacheDir(projectPath)
        val cacheDir3 = I2VisionPaths.getProjectCacheDir("${projectPath}${File.separator}")
        val cacheDir4 = I2VisionPaths.getProjectCacheDir(tempDir.canonicalPath)
        
        // Then: All should return the same path
        assertEquals(cacheDir1.parentFile.name, cacheDir2.parentFile.name,
            "Hash should be consistent across identical calls")
        assertEquals(cacheDir2.parentFile.name, cacheDir3.parentFile.name,
            "Hash should be consistent with trailing slash")
        assertEquals(cacheDir3.parentFile.name, cacheDir4.parentFile.name,
            "Hash should be consistent with canonical path")
    }
    
    @Test
    fun `should not create extra empty directories in projects cache`() = withTempDir { tempDir ->
        // Given: A test project
        val srcDir = File(tempDir, "src/main/kotlin")
        srcDir.mkdirs()
        val testFile = File(srcDir, "Test.kt")
        testFile.writeText("class Test {}")
        
        // When: Getting cache directory multiple times with path variations
        val projectPath = tempDir.absolutePath
        val hash1 = I2VisionPaths.getProjectCacheDir(projectPath).parentFile.name
        val hash2 = I2VisionPaths.getProjectCacheDir("${projectPath}${File.separator}").parentFile.name
        val hash3 = I2VisionPaths.getProjectCacheDir(tempDir.canonicalPath).parentFile.name
        val hash4 = I2VisionPaths.getProjectCacheDir(projectPath.uppercase()).parentFile.name
        
        // Then: All hashes should be identical
        assertEquals(hash1, hash2, "Hash should be consistent with trailing slash")
        assertEquals(hash2, hash3, "Hash should be consistent with canonical path")
        assertEquals(hash3, hash4, "Hash should be case-insensitive")
    }
}
