/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.verbalization.feedback

import com.i2vision.storage.I2VisionPaths
import com.i2vision.vslfc.Symbol
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap

/**
 * Interface for feedback storage operations.
 * Allows for different implementations (file-based, database, etc.).
 */
interface IFeedbackStore {
    fun getClusterFeedback(clusterId: String): List<Feedback>
    fun getFeedbackForSymbol(symbol: Symbol): Feedback?
    fun getAllFeedbackForSymbol(symbol: Symbol): List<Feedback>
    fun recordFeedback(feedback: Feedback)
    fun recordFeedback(
        symbol: Symbol,
        originalDescription: String,
        correction: String,
        rating: Int,
        reason: String?
    ): Feedback
    fun getClusterFeedbackStats(clusterId: String): FeedbackStats
    fun clearClusterFeedback(clusterId: String)
    fun getRecentFeedback(limit: Int): List<Feedback>
}

/**
 * Storage for user feedback on verbalization quality.
 * Persists feedback in JSONL format for easy appending and parsing.
 */
class FeedbackStore(
    projectPath: String = I2VisionPaths.rootDir.absolutePath,
    customBaseDir: String? = null
) : IFeedbackStore {
    private val baseDir: String = customBaseDir ?: I2VisionPaths.getProjectCacheDir(projectPath).absolutePath

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
    }

    // In-memory cache for fast lookups
    private val feedbackCache = ConcurrentHashMap<String, MutableList<Feedback>>()

    /**
     * Get all feedback for a cluster.
     */
    override fun getClusterFeedback(clusterId: String): List<Feedback> {
        val feedback = mutableListOf<Feedback>()
        val feedbackFile = getFeedbackFile(clusterId)

        if (feedbackFile.exists()) {
            feedbackFile.readLines()
                .filter { it.isNotBlank() }
                .forEach { line ->
                    try {
                        val fb = json.decodeFromString<Feedback>(line)
                        feedback.add(fb)
                        cacheFeedback(fb)
                    } catch (e: Exception) {
                        // Skip malformed lines
                    }
                }
        }

        return feedback
    }

    /**
     * Get feedback for a specific symbol.
     */
    override fun getFeedbackForSymbol(symbol: Symbol): Feedback? {
        val key = getSymbolKey(symbol)
        // First check cache
        val cached = feedbackCache[key]?.lastOrNull()
        if (cached != null) return cached

        // Fall back to reading from file and populating cache
        val clusterId = getClusterId(symbol.filePath)
        getClusterFeedback(clusterId)
        return feedbackCache[key]?.lastOrNull()
    }

    /**
     * Get all feedback entries for a symbol.
     */
    override fun getAllFeedbackForSymbol(symbol: Symbol): List<Feedback> {
        return getClusterFeedback(getClusterId(symbol.filePath))
            .filter { it.symbolId == getSymbolId(symbol) }
    }

    /**
     * Record new feedback.
     */
    override fun recordFeedback(feedback: Feedback) {
        // Cache it
        cacheFeedback(feedback)

        // Persist to JSONL file
        val clusterId = getClusterId(feedback.symbolPath)
        val feedbackFile = getFeedbackFile(clusterId)

        // Ensure directory exists
        val dir = getClusterDir(clusterId)
        if (!dir.exists()) {
            dir.mkdirs()
        }

        val line = json.encodeToString(feedback) + "\n"
        Files.writeString(
            feedbackFile.toPath(),
            line,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND
        )
    }

    /**
     * Record feedback from user correction.
     */
    override fun recordFeedback(
        symbol: Symbol,
        originalDescription: String,
        correction: String,
        rating: Int,
        reason: String?
    ): Feedback {
        val feedback = Feedback(
            id = generateId(),
            symbolId = getSymbolId(symbol),
            symbolPath = symbol.filePath,
            symbolName = symbol.name,
            originalDescription = originalDescription,
            correction = correction,
            rating = rating,
            reason = reason,
            timestamp = System.currentTimeMillis()
        )

        recordFeedback(feedback)
        return feedback
    }

    /**
     * Get feedback statistics for a cluster.
     */
    override fun getClusterFeedbackStats(clusterId: String): FeedbackStats {
        val feedback = getClusterFeedback(clusterId)
        val avgRating = if (feedback.isNotEmpty()) {
            feedback.map { it.rating }.average()
        } else 0.0

        return FeedbackStats(
            clusterId = clusterId,
            totalEntries = feedback.size,
            averageRating = avgRating,
            lastUpdated = feedback.maxOfOrNull { it.timestamp } ?: 0L
        )
    }

    /**
     * Clear all feedback for a cluster.
     */
    override fun clearClusterFeedback(clusterId: String) {
        val feedbackFile = getFeedbackFile(clusterId)
        if (feedbackFile.exists()) {
            feedbackFile.delete()
        }
        // Clear cache for this cluster
        feedbackCache.keys.removeIf { it.startsWith("$clusterId/") }
    }

    /**
     * Get recent feedback entries.
     */
    override fun getRecentFeedback(limit: Int): List<Feedback> {
        val allFeedback = mutableListOf<Feedback>()

        // Recursively find all learning directories containing feedback.jsonl
        val baseDirFile = File(baseDir)
        if (baseDirFile.exists()) {
            findLearningDirsAndReadFeedback(baseDirFile, allFeedback)
        }

        return allFeedback
            .sortedByDescending { it.timestamp }
            .take(limit)
    }

    private fun findLearningDirsAndReadFeedback(dir: File, feedbackList: MutableList<Feedback>) {
        val learningDir = File(dir, "learning")
        if (learningDir.exists() && learningDir.isDirectory) {
            val feedbackFile = File(learningDir, "feedback.jsonl")
            if (feedbackFile.exists()) {
                // Extract clusterId from the directory path relative to baseDir
                val baseDirFile = File(baseDir)
                val clusterId = if (dir == baseDirFile) {
                    "" // Empty cluster ID for feedback at root
                } else {
                    dir.relativeTo(baseDirFile).path.replace("\\", "/")
                }
                feedbackList.addAll(getClusterFeedback(clusterId))
            }
        } else {
            // Recursively search subdirectories
            dir.listFiles()?.filter { it.isDirectory }?.forEach { subDir ->
                findLearningDirsAndReadFeedback(subDir, feedbackList)
            }
        }
    }

    private fun getClusterDir(clusterId: String): File {
        return Paths.get(baseDir, clusterId, "learning").toFile()
    }

    private fun getFeedbackFile(clusterId: String): File {
        return Paths.get(baseDir, clusterId, "learning", "feedback.jsonl").toFile()
    }

    private fun getClusterId(filePath: String): String {
        val parts = filePath.split("/")
        return when {
            parts.size >= 3 -> "${parts[0]}/${parts[1]}"
            parts.size == 2 -> parts[0]
            parts.size == 1 -> parts[0]
            else -> filePath
        }
    }

    private fun getSymbolId(symbol: Symbol): String {
        return "${getClusterId(symbol.filePath)}/${symbol.name}"
    }

    private fun getSymbolKey(symbol: Symbol): String {
        return "${getClusterId(symbol.filePath)}/${symbol.name}"
    }

    private fun cacheFeedback(feedback: Feedback) {
        val key = "${getClusterId(feedback.symbolPath)}/${feedback.symbolName}"
        feedbackCache.getOrPut(key) { mutableListOf() }.add(feedback)
    }

    private fun generateId(): String {
        return "fb_${System.currentTimeMillis()}_${(Math.random() * 10000).toInt()}"
    }
}

/**
 * User feedback on a verbalization result.
 * Stored in JSONL format.
 */
@Serializable
data class Feedback(
    val id: String,
    val symbolId: String,
    val symbolPath: String,
    val symbolName: String,
    val originalDescription: String,
    val correction: String,
    val rating: Int, // 1-5 star rating
    val reason: String? = null,
    val timestamp: Long
)

/**
 * Statistics for feedback in a cluster.
 */
data class FeedbackStats(
    val clusterId: String,
    val totalEntries: Int,
    val averageRating: Double,
    val lastUpdated: Long
)