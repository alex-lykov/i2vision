/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.cli.integration

import com.i2vision.cli.commands.CacheContext
import com.i2vision.cli.commands.EnhancedContext
import com.i2vision.cli.commands.FileContext
import com.i2vision.cli.commands.FilesContext
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Integration tests for CLI Context command.
 * Tests actual CLI execution using architecture sketches.
 */
class ContextIntegrationTest {

    /**
     * Get sketch project root for testing
     */
    private fun getSketchRoot(sketchName: String = "aggregator-pure"): File {
        val sketchPath = javaClass.classLoader.getResource("sketches/$sketchName")
            ?: throw IllegalArgumentException("Sketch not found: $sketchName")
        return File(sketchPath.toURI()).canonicalFile
    }

    @Test
    fun `should return error for enhanced context without cache`() {
        // Given: A sketch project without discovery cache
        val projectRoot = getSketchRoot()

        // When: Running enhanced context command on a file
        val command = EnhancedContext()
        val args = arrayOf(
            "--path=module-a/src/Main.kt",
            "--project=${projectRoot.absolutePath}"
        )
        
        // Then: Should throw error about missing cache
        var errorThrown = false
        try {
            command.parse(args)
        } catch (e: Exception) {
            errorThrown = true
            assertTrue(
                e.message?.contains("No discovery cache found") == true || 
                e.message?.contains("cache") == true,
                "Should mention missing cache: ${e.message}"
            )
        }
        assertTrue(errorThrown, "Should throw error about missing cache")
    }

    @Test
    fun `should return basic context without discovery cache`() {
        // Given: A sketch project without discovery cache
        val projectRoot = getSketchRoot()

        // When: Running basic context command on a file
        val command = FileContext()
        val args = arrayOf(
            "--path=module-a/src/Main.kt",
            "--project=${projectRoot.absolutePath}"
        )
        
        // Then: Should return basic context (symbols, complexity)
        command.parse(args)
        // If we get here without exception, the command executed
    }

    @Test
    fun `should handle multiple files context command`() {
        // Given: A sketch project with multiple files
        val projectRoot = getSketchRoot()

        // When: Running files context command
        val command = FilesContext()
        val args = arrayOf(
            "module-a/src/Main.kt",
            "module-b/src/Main.kt",
            "--project=${projectRoot.absolutePath}"
        )
        
        // Then: Should return context for multiple files
        command.parse(args)
    }

    @Test
    fun `should show cache stats`() {
        // Given: A sketch project
        val projectRoot = getSketchRoot()

        // When: Running cache stats command
        val command = CacheContext()
        val args = arrayOf(
            "stats",
            "--project=${projectRoot.absolutePath}"
        )
        
        // Then: Should show cache statistics
        command.parse(args)
    }

    @Test
    fun `should handle cache clean command`() {
        // Given: A sketch project
        val projectRoot = getSketchRoot()

        // When: Running cache clean command
        val command = CacheContext()
        val args = arrayOf(
            "clean",
            "--project=${projectRoot.absolutePath}"
        )
        
        // Then: Should complete successfully
        command.parse(args)
    }

    @Test
    fun `should require pattern for cache invalidate`() {
        // Given: A sketch project
        val projectRoot = getSketchRoot()

        // When: Running cache invalidate without pattern
        val command = CacheContext()
        val args = arrayOf(
            "invalidate",
            "--project=${projectRoot.absolutePath}"
        )
        
        // Then: Should return error about missing pattern
        var errorThrown = false
        try {
            command.parse(args)
        } catch (e: Exception) {
            errorThrown = true
            assertTrue(
                e.message?.contains("pattern") == true || 
                e.message?.contains("required") == true,
                "Should mention pattern requirement: ${e.message}"
            )
        }
        assertTrue(errorThrown, "Should throw error about missing pattern")
    }
}
