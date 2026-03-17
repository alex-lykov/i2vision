package com.alyk.ai.koog.core.orchestrator.mcp

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Dynamic threshold adjustment system
 * Learns optimal thresholds from usage patterns and feedback
 */
class DynamicThresholdManager {
    
    // Performance metrics storage
    private val performanceHistory = ConcurrentHashMap<String, ToolPerformanceMetrics>()
    
    // Threshold learning data
    private val thresholdHistory = mutableListOf<ThresholdAdjustment>()
    
    // Current adaptive thresholds per tool category
    private val currentThresholds = mutableMapOf<McpToolCategory, Double>()
    
    // Learning parameters
    private val learningRate = 0.1
    private val minThreshold = 0.05
    private val maxThreshold = 0.8
    private val performanceWindow = 50 // Last N executions
    
    init {
        initializeDefaultThresholds()
    }
    
    /**
     * Get adaptive threshold for a tool category
     */
    fun getAdaptiveThreshold(category: McpToolCategory): Double {
        return currentThresholds[category] ?: getDefaultThreshold(category)
    }
    
    /**
     * Record tool execution performance for learning
     */
    fun recordToolExecution(
        toolName: String,
        category: McpToolCategory,
        originalScore: Double,
        threshold: Double,
        executionTimeMs: Long,
        success: Boolean,
        userFeedback: ExecutionFeedback? = null
    ) {
        val metrics = performanceHistory.getOrPut(toolName) {
            ToolPerformanceMetrics(toolName, category)
        }
        
        metrics.addExecution(
            originalScore = originalScore,
            threshold = threshold,
            executionTimeMs = executionTimeMs,
            success = success,
            feedback = userFeedback
        )
        
        // Update adaptive threshold based on performance
        updateAdaptiveThreshold(category, metrics)
        
        println("[THRESHOLD] Recorded execution for $toolName: success=$success, score=$originalScore, threshold=$threshold")
    }
    
    /**
     * Update adaptive threshold based on performance metrics
     */
    private fun updateAdaptiveThreshold(category: McpToolCategory, metrics: ToolPerformanceMetrics) {
        val recentExecutions = metrics.getRecentExecutions(performanceWindow)
        if (recentExecutions.size < 5) return // Not enough data
        
        // Calculate performance indicators
        val successRate = recentExecutions.count { it.success }.toDouble() / recentExecutions.size
        val avgExecutionTime: Double = recentExecutions.map { it.executionTimeMs.toDouble() }.average()
        val avgScore: Double = recentExecutions.map { it.originalScore }.average()
        
        // Calculate optimal threshold based on performance
        val optimalThreshold = calculateOptimalThreshold(
            successRate = successRate,
            avgExecutionTime = avgExecutionTime,
            avgScore = avgScore,
            currentThreshold = currentThresholds[category] ?: getDefaultThreshold(category)
        )
        
        // Apply learning rate for gradual adjustment
        val newThreshold = currentThresholds[category]?.let { current ->
            current + (optimalThreshold - current) * learningRate
        } ?: optimalThreshold
        
        // Clamp to valid range
        val clampedThreshold = newThreshold.coerceIn(minThreshold, maxThreshold)
        
        currentThresholds[category] = clampedThreshold
        
        // Record adjustment for analysis
        thresholdHistory.add(
            ThresholdAdjustment(
                category = category,
                oldThreshold = currentThresholds[category] ?: getDefaultThreshold(category),
                newThreshold = clampedThreshold,
                reason = "Performance-based adjustment",
                successRate = successRate,
                avgExecutionTime = avgExecutionTime,
                timestamp = Instant.now()
            )
        )
        
        println("[THRESHOLD] Updated threshold for $category: $clampedThreshold (success rate: ${(successRate * 100).toInt()}%)")
    }
    
    /**
     * Calculate optimal threshold based on performance metrics
     */
    private fun calculateOptimalThreshold(
        successRate: Double,
        avgExecutionTime: Double,
        avgScore: Double,
        currentThreshold: Double
    ): Double {
        var adjustment = 0.0
        
        // Adjust based on success rate
        when {
            successRate > 0.9 -> {
                // High success rate - can be more selective
                adjustment += 0.05
            }
            successRate < 0.6 -> {
                // Low success rate - be more permissive
                adjustment -= 0.1
            }
        }
        
        // Adjust based on execution time
        when {
            avgExecutionTime > 10000 -> { // > 10 seconds
                // Slow execution - be more selective
                adjustment += 0.05
            }
            avgExecutionTime < 1000 -> { // < 1 second
                // Fast execution - can be more permissive
                adjustment -= 0.02
            }
        }
        
        // Adjust based on average score
        when {
            avgScore > 0.7 -> {
                // High scores - can be more selective
                adjustment += 0.03
            }
            avgScore < 0.3 -> {
                // Low scores - be more permissive
                adjustment -= 0.05
            }
        }
        
        return currentThreshold + adjustment
    }
    
    /**
     * Get threshold recommendations for a specific scenario
     */
    fun getThresholdRecommendation(
        promptComplexity: TaskComplexity,
        toolCount: Int,
        hasHighConfidenceTools: Boolean
    ): ThresholdRecommendation {
        val baseThreshold = when {
            hasHighConfidenceTools -> 0.15
            toolCount > 5 -> 0.25
            promptComplexity == TaskComplexity.COMPLEX -> 0.2
            promptComplexity == TaskComplexity.SIMPLE -> 0.1
            else -> 0.15
        }
        
        return ThresholdRecommendation(
            recommendedThreshold = baseThreshold,
            confidence = calculateRecommendationConfidence(promptComplexity, toolCount),
            reasoning = generateRecommendationReasoning(promptComplexity, toolCount, hasHighConfidenceTools)
        )
    }
    
    /**
     * Calculate confidence in threshold recommendation
     */
    private fun calculateRecommendationConfidence(
        complexity: TaskComplexity,
        toolCount: Int
    ): Double {
        var confidence = 0.5
        
        // Higher confidence for extreme cases
        when (complexity) {
            TaskComplexity.SIMPLE -> confidence += 0.3
            TaskComplexity.COMPLEX -> confidence += 0.2
            TaskComplexity.MODERATE -> confidence += 0.1
        }
        
        // Adjust based on tool count
        when {
            toolCount == 0 -> confidence -= 0.2
            toolCount > 10 -> confidence += 0.1
            toolCount > 20 -> confidence += 0.2
        }
        
        return confidence.coerceIn(0.0, 1.0)
    }
    
    /**
     * Generate reasoning for threshold recommendation
     */
    private fun generateRecommendationReasoning(
        complexity: TaskComplexity,
        toolCount: Int,
        hasHighConfidence: Boolean
    ): String {
        val reasons = mutableListOf<String>()
        
        when (complexity) {
            TaskComplexity.SIMPLE -> reasons.add("Simple task - lower threshold to avoid missing relevant tools")
            TaskComplexity.COMPLEX -> reasons.add("Complex task - higher threshold for precision")
            TaskComplexity.MODERATE -> reasons.add("Moderate complexity - balanced threshold")
        }
        
        if (toolCount == 0) {
            reasons.add("No tools selected - consider lowering threshold")
        } else if (toolCount > 10) {
            reasons.add("Many tools available - higher threshold for selectivity")
        }
        
        if (hasHighConfidence) {
            reasons.add("High confidence tools found - can be more selective")
        }
        
        return reasons.joinToString("; ")
    }
    
    /**
     * Initialize default thresholds
     */
    private fun initializeDefaultThresholds() {
        currentThresholds[McpToolCategory.CONTEXT] = 0.15
        currentThresholds[McpToolCategory.FILE_ACCESS] = 0.2
        currentThresholds[McpToolCategory.SEARCH] = 0.15
        currentThresholds[McpToolCategory.ANALYSIS] = 0.25
        currentThresholds[McpToolCategory.EXECUTION] = 0.3
        currentThresholds[McpToolCategory.VERSION_CONTROL] = 0.2
        currentThresholds[McpToolCategory.NAVIGATION] = 0.1
    }
    
    /**
     * Get default threshold for category
     */
    private fun getDefaultThreshold(category: McpToolCategory): Double {
        return when (category) {
            McpToolCategory.CONTEXT -> 0.15
            McpToolCategory.FILE_ACCESS -> 0.2
            McpToolCategory.SEARCH -> 0.15
            McpToolCategory.ANALYSIS -> 0.25
            McpToolCategory.EXECUTION -> 0.3
            McpToolCategory.VERSION_CONTROL -> 0.2
            McpToolCategory.NAVIGATION -> 0.1
        }
    }
    
    /**
     * Get performance analytics
     */
    fun getPerformanceAnalytics(): PerformanceAnalytics {
        val categoryMetrics = currentThresholds.map { (category, threshold) ->
            val toolsInCategory = performanceHistory.values.filter { it.category == category }
            val avgSuccessRate = if (toolsInCategory.isNotEmpty()) {
                toolsInCategory.map { it.getSuccessRate() }.average()
            } else 0.0
            
            CategoryPerformance(
                category = category,
                currentThreshold = threshold,
                avgSuccessRate = avgSuccessRate,
                toolCount = toolsInCategory.size,
                totalExecutions = toolsInCategory.sumOf { it.executionCount }
            )
        }
        
        return PerformanceAnalytics(
            categoryMetrics = categoryMetrics,
            totalAdjustments = thresholdHistory.size,
            recentAdjustments = thresholdHistory.takeLast(10),
            overallSuccessRate = performanceHistory.values.map { it.getSuccessRate() }.average()
        )
    }
}

/**
 * Performance metrics for a specific tool
 */
@Serializable
data class ToolPerformanceMetrics(
    val toolName: String,
    val category: McpToolCategory,
    var executionCount: Int = 0,
    var totalExecutionTime: Long = 0,
    var successCount: Int = 0,
    var failureCount: Int = 0,
    private val executions: MutableList<ThresholdExecutionRecord> = mutableListOf()
) {
    
    fun addExecution(
        originalScore: Double,
        threshold: Double,
        executionTimeMs: Long,
        success: Boolean,
        feedback: ExecutionFeedback?
    ) {
        executionCount++
        totalExecutionTime += executionTimeMs
        
        if (success) {
            successCount++
        } else {
            failureCount++
        }
        
        executions.add(
            ThresholdExecutionRecord(
                timestamp = Instant.now(),
                originalScore = originalScore,
                threshold = threshold,
                executionTimeMs = executionTimeMs,
                success = success,
                feedback = feedback
            )
        )
        
        // Keep only recent executions
        if (executions.size > 100) {
            executions.removeAt(0)
        }
    }
    
    fun getRecentExecutions(limit: Int): List<ThresholdExecutionRecord> {
        return executions.takeLast(limit)
    }
    
    fun getSuccessRate(): Double {
        return if (executionCount > 0) {
            successCount.toDouble() / executionCount
        } else 0.0
    }
    
    fun getAverageExecutionTime(): Double {
        return if (executionCount > 0) {
            totalExecutionTime.toDouble() / executionCount
        } else 0.0
    }
}

/**
 * Individual execution record for threshold management
 */
@Serializable
data class ThresholdExecutionRecord(
    @Contextual val timestamp: Instant,
    val originalScore: Double,
    val threshold: Double,
    val executionTimeMs: Long,
    val success: Boolean,
    val feedback: ExecutionFeedback?
)

/**
 * User feedback on execution
 */
@Serializable
data class ExecutionFeedback(
    val rating: Int, // 1-5 stars
    val comment: String?,
    val wasHelpful: Boolean,
    val suggestedImprovement: String?
)

/**
 * Threshold adjustment record
 */
@Serializable
data class ThresholdAdjustment(
    val category: McpToolCategory,
    val oldThreshold: Double,
    val newThreshold: Double,
    val reason: String,
    val successRate: Double,
    val avgExecutionTime: Double,
    @Contextual val timestamp: Instant
)

/**
 * Threshold recommendation
 */
data class ThresholdRecommendation(
    val recommendedThreshold: Double,
    val confidence: Double,
    val reasoning: String
)

/**
 * Performance analytics data
 */
data class PerformanceAnalytics(
    val categoryMetrics: List<CategoryPerformance>,
    val totalAdjustments: Int,
    val recentAdjustments: List<ThresholdAdjustment>,
    val overallSuccessRate: Double
)

/**
 * Category performance summary
 */
data class CategoryPerformance(
    val category: McpToolCategory,
    val currentThreshold: Double,
    val avgSuccessRate: Double,
    val toolCount: Int,
    val totalExecutions: Int
)
