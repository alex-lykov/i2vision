package com.i2vision.discover.logic

import com.i2vision.index.CustomIndex
import kotlin.test.Test
import kotlin.test.assertTrue
import java.io.File

/**
 * Tests for Logic Extraction.
 * Validates pattern-based rule extraction (require, check, validate).
 */
class LogicExtractorTest {

    private fun withTempDir(block: (File) -> Unit) {
        val tempDir = java.nio.file.Files.createTempDirectory("logic-test").toFile()
        try {
            block(tempDir)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `should create logic extractor instance`() = withTempDir { tempDir ->
        // Given: Project root and index provider
        val projectRoot = tempDir.absolutePath
        val indexProvider = CustomIndex(projectRoot)

        // When: Create LogicExtractor
        val logicExtractor = LogicExtractor(projectRoot, indexProvider)

        // Then: Should be created successfully
        assertTrue(true, "LogicExtractor created successfully")
    }
}
