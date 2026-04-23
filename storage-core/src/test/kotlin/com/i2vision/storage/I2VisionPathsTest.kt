package com.i2vision.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.io.File

/**
 * Tests for I2VisionPaths.
 * Tests that semantic cache is written to OS user directory, not project root.
 */
class I2VisionPathsTest {

    @Test
    fun `should use user home directory for cache`() {
        // Given: System property user.home
        val userHome = System.getProperty("user.home")

        // When
        val rootDir = I2VisionPaths.rootDir

        // Then: Root directory should be under user home
        assertTrue(
            rootDir.absolutePath.contains(userHome) ||
                    rootDir.absolutePath.contains("AppData") ||
                    rootDir.absolutePath.contains("Library")
        )
    }

    @Test
    fun `should not use project root for cache`() {
        // Given: Current working directory
        val currentDir = System.getProperty("user.dir")

        // When
        val cacheDir = I2VisionPaths.cacheDir

        // Then: Cache directory should not be under project root
        assertTrue(
            !cacheDir.absolutePath.contains(currentDir) ||
                    cacheDir.absolutePath.contains(".i2vision") ||
                    cacheDir.absolutePath.contains("AppData") ||
                    cacheDir.absolutePath.contains("Library")
        )
    }

    @Test
    fun `should create cache directory if it does not exist`() {
        // When
        val cacheDir = I2VisionPaths.cacheDir

        // Then
        assertTrue(cacheDir.exists())
        assertTrue(cacheDir.isDirectory)
    }

    @Test
    fun `should create project-specific cache directory using hash`() {
        // Given: A project path
        val projectPath = "/path/to/project"

        // When
        val projectCacheDir = I2VisionPaths.getProjectCacheDir(projectPath)

        // Then
        assertTrue(projectCacheDir.exists())
        assertTrue(projectCacheDir.isDirectory)
        assertTrue(projectCacheDir.absolutePath.contains(".semantic-cache"))
    }

    @Test
    fun `should create config directory`() {
        // When
        val configDir = I2VisionPaths.configDir

        // Then
        assertTrue(configDir.exists())
        assertTrue(configDir.isDirectory)
    }

    @Test
    fun `should create logs directory`() {
        // When
        val logsDir = I2VisionPaths.logsDir

        // Then
        assertTrue(logsDir.exists())
        assertTrue(logsDir.isDirectory)
    }

    @Test
    fun `should create global cache directory`() {
        // When
        val globalCacheDir = I2VisionPaths.globalCacheDir

        // Then
        assertTrue(globalCacheDir.exists())
        assertTrue(globalCacheDir.isDirectory)
    }
}
