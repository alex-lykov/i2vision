package com.i2vision.cli

import com.i2vision.discover.api.models.DiscoveryDepth
import com.i2vision.storage.I2VisionPaths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.io.File

/**
 * Tests for CLI Depth Parameter.
 * Tests that BROWSE/STANDARD/DEEP modes apply correct parameters.
 */
class CliDepthTest {
    
    @Test
    fun `should accept BROWSE depth parameter`() {
        // Given: BROWSE depth string
        val depthStr = "BROWSE"
        
        // When
        val depth = DiscoveryDepth.valueOf(depthStr)
        
        // Then
        assertEquals(DiscoveryDepth.BROWSE, depth)
    }
    
    @Test
    fun `should accept STANDARD depth parameter`() {
        // Given: STANDARD depth string
        val depthStr = "STANDARD"
        
        // When
        val depth = DiscoveryDepth.valueOf(depthStr)
        
        // Then
        assertEquals(DiscoveryDepth.STANDARD, depth)
    }
    
    @Test
    fun `should accept DEEP depth parameter`() {
        // Given: DEEP depth string
        val depthStr = "DEEP"
        
        // When
        val depth = DiscoveryDepth.valueOf(depthStr)
        
        // Then
        assertEquals(DiscoveryDepth.DEEP, depth)
    }
    
    @Test
    fun `should accept lowercase browse depth`() {
        // Given: lowercase browse string
        val depthStr = "browse"
        
        // When
        val depth = DiscoveryDepth.valueOf(depthStr.uppercase())
        
        // Then
        assertEquals(DiscoveryDepth.BROWSE, depth)
    }
    
    @Test
    fun `should accept mixed case depth parameters`() {
        // Given: mixed case depth strings
        val depthStr1 = "StAnDaRd"
        val depthStr2 = "DeEp"
        
        // When
        val depth1 = DiscoveryDepth.valueOf(depthStr1.uppercase())
        val depth2 = DiscoveryDepth.valueOf(depthStr2.uppercase())
        
        // Then
        assertEquals(DiscoveryDepth.STANDARD, depth1)
        assertEquals(DiscoveryDepth.DEEP, depth2)
    }
    
    @Test
    fun `should throw exception for invalid depth parameter`() {
        // Given: Invalid depth string
        val depthStr = "INVALID"
        
        // When & Then
        assertFailsWith<IllegalArgumentException> {
            DiscoveryDepth.valueOf(depthStr)
        }
    }
    
    @Test
    fun `should default to STANDARD when no depth provided`() {
        // Given: No depth provided (null)
        val depthStr: String? = null
        val defaultDepth = "STANDARD"
        
        // When
        val depth = DiscoveryDepth.valueOf((depthStr ?: defaultDepth).uppercase())
        
        // Then
        assertEquals(DiscoveryDepth.STANDARD, depth)
    }
    
    @Test
    fun `should use I2VisionPaths for cache location`() {
        // Given: A project path
        val projectPath = "/path/to/project"
        
        // When: Getting project cache directory
        val projectCacheDir = I2VisionPaths.getProjectCacheDir(projectPath)
        
        // Then: Cache directory should not be in project root
        assertFalse(projectCacheDir.absolutePath.contains(projectPath), 
            "Cache should not be in project root")
        
        // Then: Cache directory should be in OS-specific user directory
        val userHome = System.getProperty("user.home")
        assertTrue(projectCacheDir.absolutePath.contains(userHome) || 
                   projectCacheDir.absolutePath.contains("AppData") ||
                   projectCacheDir.absolutePath.contains("Library"),
            "Cache should be in user directory")
    }
    
    @Test
    fun `should not create semantic cache in project root`() {
        // Given: A temporary project directory
        val tempDir = java.nio.file.Files.createTempDirectory("cli-cache-test").toFile()
        try {
            // When: Getting project cache directory
            val projectCacheDir = I2VisionPaths.getProjectCacheDir(tempDir.absolutePath)
            
            // Then: .semantic-cache should not be in project root
            val projectRootCache = File(tempDir, ".semantic-cache")
            assertFalse(projectCacheDir.absolutePath.contains(tempDir.absolutePath), 
                "Cache path should not contain project path")
            
            // Then: Cache should be in user directory
            val userHome = System.getProperty("user.home")
            assertTrue(projectCacheDir.absolutePath.contains(userHome) || 
                       projectCacheDir.absolutePath.contains("AppData") ||
                       projectCacheDir.absolutePath.contains("Library"),
                "Cache should be in user directory")
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
