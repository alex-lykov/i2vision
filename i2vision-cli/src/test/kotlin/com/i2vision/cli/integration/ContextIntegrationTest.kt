package com.i2vision.cli.integration

import com.i2vision.cli.commands.ContextCommand
import com.i2vision.cli.I2VisionCli
import com.github.ajalt.clikt.testing.*
import com.github.ajalt.clikt.core.CliktCommand
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import java.io.File

/**
 * Integration tests for CLI Context command.
 * Tests actual CLI execution for basic and enhanced context.
 */
class ContextIntegrationTest {
    
    private fun withTempDir(block: (File) -> Unit) {
        val tempDir = java.nio.file.Files.createTempDirectory("context-integration-test").toFile()
        try {
            block(tempDir)
        } finally {
            tempDir.deleteRecursively()
        }
    }
    
    @Test
    fun `should return error for enhanced context without cache`() = withTempDir { tempDir ->
        // Given: A test project without discovery cache
        val srcDir = File(tempDir, "src/main/kotlin")
        srcDir.mkdirs()
        val testFile = File(srcDir, "Test.kt")
        testFile.writeText("""
            class Test {
                fun hello() = "world"
            }
        """.trimIndent())
        
        // When: Running enhanced context command
        val result = I2VisionCli().testing("context", "enhanced", 
            "--path=${srcDir.absolutePath}/Test.kt", 
            "--project=${tempDir.absolutePath}")
        
        // Then: Should return error about missing cache
        assertTrue(result.statusCode != 0, "Should return non-zero exit code")
        assertTrue(result.output.contains("No discovery cache found") || result.output.contains("Error"), 
            "Should mention missing cache")
    }
    
    @Test
    fun `should return basic context without discovery cache`() = withTempDir { tempDir ->
        // Given: A test project without discovery cache
        val srcDir = File(tempDir, "i2vision-instant/src/main/kotlin")
        srcDir.mkdirs()
        val testFile = File(srcDir, "Test.kt")
        testFile.writeText("""
            class Test {
                fun hello() = "world"
            }
        """.trimIndent())
        
        // When: Running basic context command
        val result = I2VisionCli().testing("context", "file",
            "--path=i2vision-instant/src/main/kotlin/Test.kt",
            "--project=${tempDir.absolutePath}")
        
        // Then: Should return basic context (symbols, complexity)
        assertTrue(result.statusCode == 0, "Should return zero exit code")
        assertTrue(result.output.contains("Symbols") || result.output.contains("symbol"), 
            "Should show symbols")
        assertTrue(result.output.contains("[BASIC]") || result.output.contains("Basic context"), 
            "Should indicate basic context")
    }
    
    @Test
    fun `should handle multiple files context command`() = withTempDir { tempDir ->
        // Given: A test project with multiple files
        val srcDir = File(tempDir, "src/main/kotlin")
        srcDir.mkdirs()
        val testFile1 = File(srcDir, "Test1.kt")
        testFile1.writeText("class Test1 {}")
        val testFile2 = File(srcDir, "Test2.kt")
        testFile2.writeText("class Test2 {}")
        
        // When: Running files context command
        val result = I2VisionCli().testing("context", "files",
            "src/main/kotlin/Test1.kt",
            "src/main/kotlin/Test2.kt",
            "--project=${tempDir.absolutePath}")
        
        // Then: Should return context for multiple files
        assertTrue(result.statusCode == 0, "Should return zero exit code")
        assertTrue(result.output.contains("Symbols") || result.output.contains("symbol"), 
            "Should show symbols")
    }
    
    @Test
    fun `should show cache stats`() = withTempDir { tempDir ->
        // Given: A test project
        val srcDir = File(tempDir, "src/main/kotlin")
        srcDir.mkdirs()
        
        // When: Running cache stats command
        val result = I2VisionCli().testing("context", "cache", "stats",
            "--project=${tempDir.absolutePath}")
        
        // Then: Should show cache statistics
        assertTrue(result.statusCode == 0, "Should return zero exit code")
        assertTrue(result.output.contains("Cache") || result.output.contains("entries"), 
            "Should show cache statistics")
    }
    
    @Test
    fun `should handle cache clean command`() = withTempDir { tempDir ->
        // Given: A test project
        val srcDir = File(tempDir, "src/main/kotlin")
        srcDir.mkdirs()
        
        // When: Running cache clean command
        val result = I2VisionCli().testing("context", "cache", "clean",
            "--project=${tempDir.absolutePath}")
        
        // Then: Should complete successfully
        assertTrue(result.statusCode == 0, "Should return zero exit code")
        assertTrue(result.output.contains("cleaned") || result.output.contains("success"), 
            "Should indicate cache was cleaned")
    }
    
    @Test
    fun `should require pattern for cache invalidate`() = withTempDir { tempDir ->
        // Given: A test project
        val srcDir = File(tempDir, "src/main/kotlin")
        srcDir.mkdirs()
        
        // When: Running cache invalidate without pattern
        val result = I2VisionCli().testing("context", "cache", "invalidate",
            "--project=${tempDir.absolutePath}")
        
        // Then: Should return error about missing pattern
        assertTrue(result.statusCode != 0, "Should return non-zero exit code")
        assertTrue(result.output.contains("pattern") || result.output.contains("required"), 
            "Should mention pattern requirement")
    }
}
