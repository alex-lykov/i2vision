/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.storage

import org.slf4j.LoggerFactory
import java.io.File
import java.security.MessageDigest

/**
 * OS-agnostic path resolution for i2vision user data.
 * 
 * Provides paths for cache, config, and logs directories based on OS conventions:
 * - Windows: %LOCALAPPDATA%\i2vision\
 * - macOS: ~/Library/Application Support/i2vision/
 * - Linux: ~/.i2vision/
 */
object I2VisionPaths {

    private val log = LoggerFactory.getLogger(I2VisionPaths::class.java)
    private val userHome: String = System.getProperty("user.home")
    private val osName: String = System.getProperty("os.name").lowercase()

    /**
     * Root i2vision directory in user's home.
     */
    val rootDir: File by lazy {
        val dir = when {
            osName.contains("win") -> File(System.getenv("LOCALAPPDATA") ?: "$userHome/AppData/Local", "i2vision")
            osName.contains("mac") -> File(userHome, "Library/Application Support/i2vision")
            else -> File(userHome, ".i2vision")
        }
        dir.mkdirs()
        dir
    }

    /**
     * Cache directory for all projects.
     */
    val cacheDir: File by lazy {
        File(rootDir, "cache").apply { mkdirs() }
    }

    /**
     * Get cache directory for a specific project.
     * Uses project path hash to create unique directory.
     */
    fun getProjectCacheDir(projectPath: String): File {
        val canonicalPath = File(projectPath).canonicalPath
        val normalizedPath = canonicalPath
            .trim()
            .trimEnd(File.separatorChar)
            .lowercase()
            .replace("\\", "/")
            .replace("//", "/")
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(normalizedPath.toByteArray(Charsets.UTF_8))
        val projectHash = digest.joinToString("") { "%02x".format(it) }.takeLast(8)
        // log.debug("Project path: '$projectPath' -> Canonical: '$canonicalPath' -> Normalized: '$normalizedPath' -> Hash: '$projectHash'")
        return File(cacheDir, "projects/$projectHash/.semantic-cache").apply { mkdirs() }
    }

    /**
     * Configuration directory.
     */
    val configDir: File by lazy {
        File(rootDir, "config").apply { mkdirs() }
    }

    /**
     * Presets directory.
     */
    val presetsDir: File by lazy {
        File(configDir, "presets").apply { mkdirs() }
    }

    /**
     * Learning data directory.
     */
    val learningDir: File by lazy {
        File(configDir, "learning").apply { mkdirs() }
    }

    /**
     * Logs directory.
     */
    val logsDir: File by lazy {
        File(rootDir, "logs").apply { mkdirs() }
    }

    /**
     * Global shared cache (LLM models, etc.).
     */
    val globalCacheDir: File by lazy {
        File(cacheDir, "global").apply { mkdirs() }
    }
}
