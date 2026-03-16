package com.alyk.ai.koog.core.orchestrator.mcp.learning

import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap

/**
 * Learning and feedback system for adaptive tool selection
 */

@Serializable
data class UsageMetrics(
    val toolName: String,
    val totalUses: Int,
    val successfulUses: Int,
    val averageExecutionTime: Double,
    val averageUserRating: Double,
    val lastUsed: Long,
    val successRate: Double
)

@Serializable
data class FeedbackEntry(
    val toolName: String,
    val prompt: String,
    val userRating: Int, // 1-5
    val wasSuccessful: Boolean,
    val executionTime: Long,
    val timestamp: Long,
    val userComments: String? = null
)

class AdaptiveLearningSystem {
    private val usageMetrics = ConcurrentHashMap<String, UsageMetrics>()
    private val feedbackHistory = mutableListOf<FeedbackEntry>()
    private val performanceWindow = 100 // Keep last 100 feedback entries
    
    /**
     * Record tool usage for learning
     */
    fun recordUsage(toolName: String, wasSuccessful: Boolean, executionTime: Long) {
        val current = usageMetrics.getOrPut(toolName) {
            UsageMetrics(toolName, 0, 0, 0.0, 0.0, System.currentTimeMillis(), 0.0)
        }
        
        val newMetrics = current.copy(
            totalUses = current.totalUses + 1,
            successfulUses = current.successfulUses + if (wasSuccessful) 1 else 0,
            averageExecutionTime = (current.averageExecutionTime * current.totalUses + executionTime) / (current.totalUses + 1),
            lastUsed = System.currentTimeMillis(),
            successRate = (current.successfulUses + if (wasSuccessful) 1 else 0).toDouble() / (current.totalUses + 1)
        )
        
        usageMetrics[toolName] = newMetrics
        
        println("[LEARNING] Recorded usage for $toolName: success=$wasSuccessful, time=${executionTime}ms")
    }
    
    /**
     * Record user feedback for learning
     */
    fun recordFeedback(feedback: FeedbackEntry) {
        feedbackHistory.add(feedback)
        
        // Keep only recent feedback
        if (feedbackHistory.size > performanceWindow) {
            feedbackHistory.removeAt(0)
        }
        
        // Update usage metrics with rating
        val current = usageMetrics.getOrPut(feedback.toolName) {
            UsageMetrics(feedback.toolName, 0, 0, 0.0, 0.0, System.currentTimeMillis(), 0.0)
        }
        
        val totalRating = current.averageUserRating * (feedbackHistory.filter { it.toolName == feedback.toolName }.size - 1) + feedback.userRating
        val count = feedbackHistory.count { it.toolName == feedback.toolName }
        
        usageMetrics[feedback.toolName] = current.copy(
            averageUserRating = totalRating / count
        )
        
        println("[LEARNING] Recorded feedback for ${feedback.toolName}: rating=${feedback.userRating}")
    }
    
    /**
     * Calculate dynamic threshold based on learning
     */
    fun calculateDynamicThreshold(toolName: String, baseThreshold: Double = 0.2): Double {
        val metrics = usageMetrics[toolName] ?: return baseThreshold
        
        // Adjust threshold based on success rate
        val successAdjustment = when {
            metrics.successRate > 0.9 -> -0.1 // Lower threshold for highly successful tools
            metrics.successRate > 0.7 -> -0.05
            metrics.successRate < 0.3 -> 0.2 // Raise threshold for unreliable tools
            metrics.successRate < 0.5 -> 0.1
            else -> 0.0
        }
        
        // Adjust based on user rating
        val ratingAdjustment = when {
            metrics.averageUserRating > 4.5 -> -0.05
            metrics.averageUserRating > 4.0 -> -0.02
            metrics.averageUserRating < 2.0 -> 0.15
            metrics.averageUserRating < 3.0 -> 0.05
            else -> 0.0
        }
        
        // Adjust based on execution time (penalize slow tools)
        val performanceAdjustment = when {
            metrics.averageExecutionTime < 1000 -> -0.02 // Fast tools get lower threshold
            metrics.averageExecutionTime > 10000 -> 0.1 // Slow tools get higher threshold
            metrics.averageExecutionTime > 5000 -> 0.05
            else -> 0.0
        }
        
        val adjustedThreshold = baseThreshold + successAdjustment + ratingAdjustment + performanceAdjustment
        
        return adjustedThreshold.coerceIn(0.05, 0.5) // Keep within reasonable bounds
    }
    
    /**
     * Get tool performance score for selection
     */
    fun getPerformanceScore(toolName: String): Double {
        val metrics = usageMetrics[toolName] ?: return 0.5 // Neutral score for new tools
        
        // Weighted combination of factors
        val successWeight = 0.4
        val ratingWeight = 0.3
        val speedWeight = 0.2
        val frequencyWeight = 0.1
        
        // Normalize execution time (lower is better, max 30 seconds)
        val speedScore = maxOf(0.0, 1.0 - (metrics.averageExecutionTime / 30000.0))
        
        // Normalize frequency (more usage = higher confidence)
        val frequencyScore = minOf(1.0, metrics.totalUses.toDouble() / 50.0)
        
        val performanceScore = (
            metrics.successRate * successWeight +
            (metrics.averageUserRating / 5.0) * ratingWeight +
            speedScore * speedWeight +
            frequencyScore * frequencyWeight
        )
        
        return performanceScore.coerceIn(0.0, 1.0)
    }
    
    /**
     * Get recommendations for tool improvement
     */
    fun getRecommendations(toolName: String): List<String> {
        val recommendations = mutableListOf<String>()
        val metrics = usageMetrics[toolName] ?: return recommendations
        
        when {
            metrics.successRate < 0.5 -> {
                recommendations.add("Low success rate (${(metrics.successRate * 100).toInt()}%). Consider improving tool reliability.")
            }
            metrics.averageUserRating < 3.0 -> {
                recommendations.add("Low user rating (${metrics.averageUserRating}/5.0). Consider improving user experience.")
            }
            metrics.averageExecutionTime > 10000 -> {
                recommendations.add("Slow execution (${metrics.averageExecutionTime.toInt()}ms). Consider optimization.")
            }
            metrics.totalUses < 5 -> {
                recommendations.add("Low usage count (${metrics.totalUses}). Tool may need better discovery.")
            }
        }
        
        return recommendations
    }
    
    /**
     * Get learning analytics
     */
    fun getAnalytics(): Map<String, Any> {
        return mapOf(
            "totalToolsTracked" to usageMetrics.size,
            "totalFeedbackEntries" to feedbackHistory.size,
            "averageSuccessRate" to usageMetrics.values.map { it.successRate }.average(),
            "averageUserRating" to usageMetrics.values.map { it.averageUserRating }.average(),
            "topPerformingTools" to usageMetrics.values
                .sortedByDescending { getPerformanceScore(it.toolName) }
                .take(5)
                .map { it.toolName },
            "toolsNeedingImprovement" to usageMetrics.values
                .filter { getPerformanceScore(it.toolName) < 0.5 }
                .map { it.toolName }
        )
    }
    
    /**
     * Export learning data for model training
     */
    fun exportTrainingData(): List<Map<String, Any>> {
        return feedbackHistory.map { feedback ->
            mapOf(
                "toolName" to feedback.toolName,
                "prompt" to feedback.prompt,
                "userRating" to feedback.userRating,
                "wasSuccessful" to feedback.wasSuccessful,
                "executionTime" to feedback.executionTime,
                "timestamp" to feedback.timestamp
            )
        }
    }
    
    /**
     * Import training data for model initialization
     */
    fun importTrainingData(data: List<Map<String, Any>>) {
        data.forEach { entry ->
            try {
                val feedback = FeedbackEntry(
                    toolName = entry["toolName"] as String,
                    prompt = entry["prompt"] as String,
                    userRating = (entry["userRating"] as Number).toInt(),
                    wasSuccessful = entry["wasSuccessful"] as Boolean,
                    executionTime = (entry["executionTime"] as Number).toLong(),
                    timestamp = (entry["timestamp"] as Number).toLong()
                )
                feedbackHistory.add(feedback)
            } catch (e: Exception) {
                println("[LEARNING] Failed to import training data: ${e.message}")
            }
        }
        
        // Recalculate metrics
        feedbackHistory.groupBy { it.toolName }.forEach { (toolName, entries) ->
            val successful = entries.count { it.wasSuccessful }
            val total = entries.size
            val avgRating = entries.map { it.userRating }.average()
            val avgTime = entries.map { it.executionTime }.average()
            
            usageMetrics[toolName] = UsageMetrics(
                toolName = toolName,
                totalUses = total,
                successfulUses = successful,
                averageExecutionTime = avgTime,
                averageUserRating = avgRating,
                lastUsed = entries.maxOfOrNull { it.timestamp } ?: System.currentTimeMillis(),
                successRate = successful.toDouble() / total
            )
        }
        
        println("[LEARNING] Imported ${data.size} training entries")
    }
}
