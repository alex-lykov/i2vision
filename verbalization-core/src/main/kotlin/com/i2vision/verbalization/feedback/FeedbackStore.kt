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
    customBaseDir: String? = null,
    private val guardrailConfig: FeedbackGuardrailConfig = FeedbackGuardrailConfig()
) : IFeedbackStore {
    private val baseDir: String = customBaseDir ?: I2VisionPaths.getProjectCacheDir(projectPath).absolutePath

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
    }

    // In-memory cache for fast lookups
    private val feedbackCache = ConcurrentHashMap<String, MutableList<Feedback>>()

    // Track code changes for decay
    private val codeChangeCounts = ConcurrentHashMap<String, Int>()

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
        val scope = determineScope(symbol)
        val expiresAt = if (guardrailConfig.enableExpiry) {
            System.currentTimeMillis() + (guardrailConfig.defaultExpiryDays * 24 * 60 * 60 * 1000L)
        } else null

        val feedback = Feedback(
            id = generateId(),
            symbolId = getSymbolId(symbol),
            symbolPath = symbol.filePath,
            symbolName = symbol.name,
            originalDescription = originalDescription,
            correction = correction,
            rating = rating,
            reason = reason,
            timestamp = System.currentTimeMillis(),
            scope = scope,
            expiresAt = expiresAt,
            confidenceScore = 1.0,
            codeChangeCount = 0
        )

        recordFeedback(feedback)
        return feedback
    }

    // ============ GUARDRAIL METHODS ============

    /**
     * Determine feedback scope based on symbol location.
     */
    private fun determineScope(symbol: Symbol): FeedbackScope {
        val path = symbol.filePath.lowercase()
        // Add leading slash if not present for consistent matching
        val normalizedPath = if (path.startsWith("/")) path else "/$path"
        return when {
            normalizedPath.contains("/core/") || normalizedPath.contains("/domain/") -> FeedbackScope.PROJECT
            normalizedPath.contains("/shared/") || normalizedPath.contains("/common/") -> FeedbackScope.GLOBAL
            else -> FeedbackScope.CLUSTER
        }
    }

    /**
     * Validate feedback before recording.
     */
    fun validateFeedback(
        symbol: Symbol,
        originalDescription: String,
        correction: String,
        rating: Int
    ): FeedbackValidationResult {
        if (!guardrailConfig.enableValidation) {
            return FeedbackValidationResult.Valid
        }

        val validator = guardrailConfig.validator ?: DefaultFeedbackValidator()
        return validator.validate(symbol, originalDescription, correction, rating)
    }

    /**
     * Check if feedback has expired.
     */
    fun isExpired(feedback: Feedback): Boolean {
        if (!guardrailConfig.enableExpiry) return false

        // Check time-based expiry
        feedback.expiresAt?.let { expiresAt ->
            if (System.currentTimeMillis() > expiresAt) {
                return true
            }
        }

        // Check code change-based expiry
        if (feedback.codeChangeCount >= guardrailConfig.maxCodeChangesBeforeExpiry) {
            return true
        }

        return false
    }

    /**
     * Calculate current confidence score with decay.
     */
    fun calculateDecayedConfidence(feedback: Feedback): Double {
        if (!guardrailConfig.enableConfidenceDecay) return feedback.confidenceScore

        var confidence = feedback.confidenceScore

        // Time-based decay
        feedback.expiresAt?.let { expiresAt ->
            val daysRemaining = (expiresAt - System.currentTimeMillis()) / (24 * 60 * 60 * 1000.0)
            val daysElapsed = guardrailConfig.defaultExpiryDays - daysRemaining
            confidence -= daysElapsed * guardrailConfig.decayPerDay
        }

        // Code change decay
        confidence -= feedback.codeChangeCount * guardrailConfig.decayPerCodeChange

        return confidence.coerceIn(0.0, 1.0)
    }

    /**
     * Get active (non-expired) feedback for a symbol.
     */
    fun getActiveFeedbackForSymbol(symbol: Symbol): List<Feedback> {
        return getAllFeedbackForSymbol(symbol).filter { feedback ->
            !isExpired(feedback) && calculateDecayedConfidence(feedback) >= guardrailConfig.minConfidenceThreshold
        }
    }

    /**
     * Mark feedback as reviewed.
     */
    fun markAsReviewed(feedbackId: String, reviewedBy: String): Boolean {
        val feedback = findFeedbackById(feedbackId) ?: return false

        if (feedback.scope == FeedbackScope.GLOBAL && guardrailConfig.requireReviewForGlobal) {
            // Update the feedback
            val updated = feedback.copy(
                reviewedBy = reviewedBy,
                confidenceScore = (feedback.confidenceScore + 1.0) / 2 // Boost confidence
            )
            replaceFeedback(feedback, updated)
            return true
        }

        return false
    }

    /**
     * Increment code change counter for feedback decay.
     */
    fun onCodeChanged(filePath: String) {
        val clusterId = getClusterId(filePath)
        val currentCount = codeChangeCounts.getOrPut(clusterId) { 0 }
        codeChangeCounts[clusterId] = currentCount + 1

        // Update feedback in cache
        feedbackCache.keys.filter { it.startsWith("$clusterId/") }.forEach { key ->
            feedbackCache[key]?.replaceAll { fb ->
                fb.copy(codeChangeCount = fb.codeChangeCount + 1)
            }
        }
    }

    /**
     * Get feedback statistics including scope breakdown.
     */
    fun getDetailedFeedbackStats(clusterId: String): DetailedFeedbackStats {
        val feedback = getClusterFeedback(clusterId)
        val activeFeedback = feedback.filter { !isExpired(it) }

        return DetailedFeedbackStats(
            clusterId = clusterId,
            totalEntries = feedback.size,
            activeEntries = activeFeedback.size,
            expiredEntries = feedback.size - activeFeedback.size,
            scopeBreakdown = mapOf(
                FeedbackScope.CLUSTER to activeFeedback.count { it.scope == FeedbackScope.CLUSTER },
                FeedbackScope.PROJECT to activeFeedback.count { it.scope == FeedbackScope.PROJECT },
                FeedbackScope.GLOBAL to activeFeedback.count { it.scope == FeedbackScope.GLOBAL }
            ),
            reviewedCount = activeFeedback.count { it.reviewedBy != null },
            averageConfidence = if (activeFeedback.isNotEmpty()) {
                activeFeedback.map { calculateDecayedConfidence(it) }.average()
            } else 0.0,
            lastUpdated = feedback.maxOfOrNull { it.timestamp } ?: 0L
        )
    }

    /**
     * Clear expired feedback from storage.
     */
    fun pruneExpiredFeedback(clusterId: String): Int {
        val feedback = getClusterFeedback(clusterId)
        val expired = feedback.filter { isExpired(it) }

        if (expired.isEmpty()) return 0

        // Clear and rewrite non-expired feedback
        clearClusterFeedback(clusterId)
        val active = feedback.filter { !isExpired(it) }
        active.forEach { recordFeedback(it) }

        return expired.size
    }

    private fun findFeedbackById(feedbackId: String): Feedback? {
        return feedbackCache.values.flatten().find { it.id == feedbackId }
    }

    private fun replaceFeedback(old: Feedback, new: Feedback) {
        val key = "${getClusterId(old.symbolPath)}/${old.symbolName}"
        val list = feedbackCache[key] ?: return
        val index = list.indexOfFirst { it.id == old.id }
        if (index >= 0) {
            list[index] = new
        }
    }

    /**
     * Get feedback within scope constraints.
     */
    fun getScopedFeedback(
        symbol: Symbol,
        requestingScope: FeedbackScope
    ): List<Feedback> {
        val symbolFeedback = getAllFeedbackForSymbol(symbol)

        return symbolFeedback.filter { feedback ->
            val passesScopeCheck = when (feedback.scope) {
                // CLUSTER feedback only visible when requesting at CLUSTER scope
                FeedbackScope.CLUSTER -> requestingScope == FeedbackScope.CLUSTER
                // PROJECT feedback visible when requesting at PROJECT or GLOBAL scope
                FeedbackScope.PROJECT -> requestingScope != FeedbackScope.CLUSTER
                // GLOBAL feedback visible only when requesting at GLOBAL scope AND reviewed
                FeedbackScope.GLOBAL -> requestingScope == FeedbackScope.GLOBAL && feedback.reviewedBy != null
            }
            passesScopeCheck && !isExpired(feedback)
        }
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
    val timestamp: Long,
    // Guardrail metadata
    val scope: FeedbackScope = FeedbackScope.CLUSTER,
    val reviewedBy: String? = null,
    val expiresAt: Long? = null,
    val confidenceScore: Double = 1.0,
    val codeChangeCount: Int = 0
)

/**
 * Feedback scope determines where the correction applies.
 */
@Serializable
enum class FeedbackScope {
    CLUSTER,    // Only affects symbols in the same cluster
    PROJECT,    // Affects same-named symbols across the project
    GLOBAL      // Affects same-named symbols across all projects (requires review)
}

/**
 * Feedback validation result.
 */
sealed class FeedbackValidationResult {
    data object Valid : FeedbackValidationResult()
    data class Invalid(val reason: String) : FeedbackValidationResult()
    data class Warning(val message: String, val feedback: Feedback?) : FeedbackValidationResult()
}

/**
 * Configuration for feedback guardrails.
 */
data class FeedbackGuardrailConfig(
    val enableScopeEnforcement: Boolean = true,
    val enableExpiry: Boolean = true,
    val enableReviewWorkflow: Boolean = false,
    val enableConfidenceDecay: Boolean = true,
    val enableValidation: Boolean = true,
    val defaultExpiryDays: Int = 90,
    val maxCodeChangesBeforeExpiry: Int = 10,
    val decayPerDay: Double = 0.01,
    val decayPerCodeChange: Double = 0.05,
    val minConfidenceThreshold: Double = 0.3,
    val requireReviewForGlobal: Boolean = true,
    val validator: IFeedbackValidator? = null
)

/**
 * Interface for custom feedback validation.
 */
interface IFeedbackValidator {
    fun validate(
        symbol: Symbol,
        originalDescription: String,
        correction: String,
        rating: Int
    ): FeedbackValidationResult
}

/**
 * Default feedback validator that checks for improvement.
 */
class DefaultFeedbackValidator : IFeedbackValidator {
    override fun validate(
        symbol: Symbol,
        originalDescription: String,
        correction: String,
        rating: Int
    ): FeedbackValidationResult {
        // Reject low ratings with corrections (contradictory)
        if (rating <= 2 && correction.isNotBlank()) {
            return FeedbackValidationResult.Invalid(
                "Low rating ($rating) with correction is contradictory"
            )
        }

        // Warn if correction is shorter than original (unlikely to improve)
        if (correction.length < originalDescription.length * 0.5) {
            return FeedbackValidationResult.Warning(
                "Correction is significantly shorter than original",
                null
            )
        }

        // Warn if correction is identical to original
        if (correction == originalDescription) {
            return FeedbackValidationResult.Invalid("Correction is identical to original")
        }

        // Warn if rating and correction disagree
        if (rating >= 4 && correction.isBlank()) {
            return FeedbackValidationResult.Warning(
                "High rating ($rating) with no correction",
                null
            )
        }

        return FeedbackValidationResult.Valid
    }
}

/**
 * Statistics for feedback in a cluster.
 */
data class FeedbackStats(
    val clusterId: String,
    val totalEntries: Int,
    val averageRating: Double,
    val lastUpdated: Long
)

/**
 * Detailed feedback statistics including guardrail metrics.
 */
data class DetailedFeedbackStats(
    val clusterId: String,
    val totalEntries: Int,
    val activeEntries: Int,
    val expiredEntries: Int,
    val scopeBreakdown: Map<FeedbackScope, Int>,
    val reviewedCount: Int,
    val averageConfidence: Double,
    val lastUpdated: Long
)

/**
 * Extension function to record feedback with guardrails.
 */
fun IFeedbackStore.recordWithGuardrails(
    symbol: Symbol,
    originalDescription: String,
    correction: String,
    rating: Int,
    reason: String?,
    config: FeedbackGuardrailConfig = FeedbackGuardrailConfig()
): Result<Feedback> {
    if (this !is FeedbackStore) {
        return Result.failure(IllegalStateException("Guardrails require FeedbackStore implementation"))
    }

    // Validate
    val validationResult = validateFeedback(symbol, originalDescription, correction, rating)
    when (validationResult) {
        is FeedbackValidationResult.Invalid -> {
            return Result.failure(IllegalArgumentException(validationResult.reason))
        }
        is FeedbackValidationResult.Warning -> {
            // Log warning but continue
            println("Feedback warning: ${validationResult.message}")
        }
        is FeedbackValidationResult.Valid -> { /* Continue */ }
    }

    // Check scope requirements
    val feedback = recordFeedback(symbol, originalDescription, correction, rating, reason)

    if (feedback.scope == FeedbackScope.GLOBAL && config.requireReviewForGlobal) {
        return Result.success(feedback.copy(
            // Mark as pending review
            confidenceScore = 0.5
        ))
    }

    return Result.success(feedback)
}