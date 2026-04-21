package com.i2vision.instant.context

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import java.io.File

/**
 * Tests for ContextProvider.
 * Validates context API returns symbols, flows, and related files.
 */
class ContextProviderTest {
    
    private fun withTempDir(block: (File) -> Unit) {
        val tempDir = java.nio.file.Files.createTempDirectory("context-test").toFile()
        try {
            block(tempDir)
        } finally {
            tempDir.deleteRecursively()
        }
    }
    
    @Test
    fun `should return context for existing file`() = withTempDir { tempDir ->
        // Given: ContextProvider and a test file
        val testFile = File(tempDir, "src/main/kotlin/Test.kt")
        testFile.parentFile?.mkdirs()
        testFile.writeText("""
            package test
            
            class Test {
                fun doSomething() {
                    println("Hello")
                }
            }
        """.trimIndent())
        
        val contextProvider = ContextProvider(tempDir.absolutePath)
        
        // When
        val context = runBlocking {
            contextProvider.getContext("src/main/kotlin/Test.kt", "debug")
        }
        
        // Then: Should return context
        assertNotNull(context)
        assertTrue(context.filePath.isNotEmpty())
    }
    
    @Test
    fun `should return error for non-existent file`() = withTempDir { tempDir ->
        // Given: ContextProvider
        val contextProvider = ContextProvider(tempDir.absolutePath)
        
        // When
        val context = runBlocking {
            contextProvider.getContext("nonexistent/file.kt", "debug")
        }
        
        // Then: Should return error context
        assertNotNull(context)
        assertTrue(!context.success, "Should return error context")
    }
    
    @Test
    fun `should provide file information in context`() = withTempDir { tempDir ->
        // Given: ContextProvider and a test file
        val testFile = File(tempDir, "src/main/kotlin/Test.kt")
        testFile.parentFile?.mkdirs()
        testFile.writeText("class Test {}")
        
        val contextProvider = ContextProvider(tempDir.absolutePath)
        
        // When
        val context = runBlocking {
            contextProvider.getContext("src/main/kotlin/Test.kt", "analyze")
        }
        
        // Then: Should include file information
        assertNotNull(context)
        assertTrue(context.filePath.contains("Test.kt"))
    }
}
