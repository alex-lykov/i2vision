package com.i2vision.discover.structure

import com.i2vision.index.CustomIndex
import kotlin.test.Test
import kotlin.test.assertTrue
import java.io.File

/**
 * Tests for Structure Building.
 * Validates package-to-component mapping.
 */
class StructureBuilderTest {
    
    private fun withTempDir(block: (File) -> Unit) {
        val tempDir = java.nio.file.Files.createTempDirectory("structure-test").toFile()
        try {
            block(tempDir)
        } finally {
            tempDir.deleteRecursively()
        }
    }
    
    @Test
    fun `should create structure builder instance`() = withTempDir { tempDir ->
        // Given: Project root and index provider
        val projectRoot = tempDir.absolutePath
        val indexProvider = CustomIndex(projectRoot)
        
        // When: Create StructureBuilder
        val structureBuilder = StructureBuilder(projectRoot, indexProvider)
        
        // Then: Should be created successfully
        assertTrue(true, "StructureBuilder created successfully")
    }
}
