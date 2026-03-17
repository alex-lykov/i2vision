package com.alyk.ai.koog.core.orchestrator.mcp

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

/**
 * Performance analytics and feedback collection system
 * Tracks tool performance, user satisfaction, and system metrics
 */
class PerformanceAnalyticsSystem(
    private val feedbackCollector: FeedbackCollector,
    private val metricsStorage: MetricsStorage
) : AnalyticsCollector {
    
    // Real-time performance metrics
    private val realtimeMetrics = ConcurrentHashMap<String, RealtimeMetrics>()
    
    // Historical performance data
    private val historicalData = ConcurrentHashMap<String, HistoricalMetrics>()
    
    // User feedback storage
    private val userFeedback = ConcurrentHashMap<String, MutableList<UserFeedback>>()
    
    // Performance alerts
    private val activeAlerts = ConcurrentHashMap<String, PerformanceAlert>()
    
    /**
     * Record tool execution performance
     */
    fun recordToolExecution(
        sessionId: String,
        toolName: String,
        category: McpToolCategory,
        executionTime: Long,
        success: Boolean,
        parameters: Map<String, Any>,
        resultQuality: Double? = null
    ) {
        val timestamp = Instant.now()
        
        // Update realtime metrics
        val sessionMetrics = realtimeMetrics.getOrPut(sessionId) {
            RealtimeMetrics(sessionId, timestamp)
        }
        
        sessionMetrics.addExecution(
            toolName = toolName,
            category = category,
            executionTime = executionTime,
            success = success,
            resultQuality = resultQuality
        )
        
        // Update historical metrics
        val toolMetrics = historicalData.getOrPut(toolName) {
            HistoricalMetrics(toolName, category)
        }
        
        toolMetrics.addExecution(executionTime, success, resultQuality)
        
        // Check for performance alerts
        checkPerformanceAlerts(toolName, sessionMetrics, toolMetrics)
        
        // Store detailed execution data
        metricsStorage.storeExecutionData(
            sessionId = sessionId,
            toolName = toolName,
            executionData = ExecutionData(
                timestamp = timestamp,
                executionTime = executionTime,
                success = success,
                parameters = parameters.mapValues { (_, v) -> v.toString() },
                resultQuality = resultQuality
            )
        )
        
        println("[ANALYTICS] Recorded execution: $toolName (${executionTime}ms, success=$success)")
    }
    
    /**
     * Collect user feedback
     */
    fun collectUserFeedback(
        sessionId: String,
        toolName: String,
        feedback: UserFeedbackInput
    ) {
        val userFeedbackEntry = UserFeedback(
            sessionId = sessionId,
            toolName = toolName,
            timestamp = Instant.now(),
            rating = feedback.rating,
            helpful = feedback.helpful,
            comment = feedback.comment,
            issues = feedback.issues,
            suggestions = feedback.suggestions
        )
        
        userFeedback.getOrPut(sessionId) { mutableListOf() }.add(userFeedbackEntry)
        
        // Update metrics with feedback
        val sessionMetrics = realtimeMetrics[sessionId]
        if (sessionMetrics != null) {
            sessionMetrics.addFeedback(feedback.rating, feedback.helpful)
        }
        
        // Analyze feedback for patterns
        analyzeFeedbackPatterns(userFeedbackEntry)
        
        // Store feedback
        feedbackCollector.storeFeedback(userFeedbackEntry)
        
        println("[ANALYTICS] Collected user feedback: $toolName (${feedback.rating}/5)")
    }
    
    /**
     * Get comprehensive performance dashboard
     */
    fun getPerformanceDashboard(timeRange: TimeRange = TimeRange.LAST_24_HOURS): PerformanceDashboard {
        val now = Instant.now()
        val cutoff = when (timeRange) {
            TimeRange.LAST_HOUR -> now.minusSeconds(3600)
            TimeRange.LAST_24_HOURS -> now.minusSeconds(86400)
            TimeRange.LAST_WEEK -> now.minusSeconds(604800)
            TimeRange.LAST_MONTH -> now.minusSeconds(2592000)
        }
        
        // Aggregate metrics
        val sessionMetrics = realtimeMetrics.values.filter { it.startTime.isAfter(cutoff) }
        val toolMetrics = historicalData.values.filter { it.hasDataInTimeRange(cutoff) }
        
        // Calculate overall metrics
        val overallMetrics = calculateOverallMetrics(sessionMetrics, toolMetrics)
        
        // Generate insights
        val insights = generatePerformanceInsights(sessionMetrics, toolMetrics, overallMetrics)
        
        // Get top performers and issues
        val topPerformers = getTopPerformers(toolMetrics)
        val performanceIssues = getPerformanceIssues(toolMetrics)
        
        // Trend analysis
        val trends = analyzeTrends(toolMetrics, timeRange)
        
        return PerformanceDashboard(
            timeRange = timeRange,
            overallMetrics = overallMetrics,
            toolPerformance = toolMetrics.map { ToolPerformanceSummary(it) },
            topPerformers = topPerformers,
            performanceIssues = performanceIssues,
            insights = insights,
            trends = trends,
            activeAlerts = activeAlerts.values.toList()
        )
    }
    
    /**
     * Get session-specific analytics
     */
    override fun getSessionAnalytics(sessionId: String): SessionAnalytics? {
        val sessionMetrics = realtimeMetrics[sessionId] ?: return null
        val sessionFeedback = userFeedback[sessionId] ?: emptyList()
        
        return SessionAnalytics(
            sessionId = sessionId,
            totalExecutionTime = sessionMetrics.totalExecutionTime,
            toolSuccessRate = sessionMetrics.getSuccessRate(),
            averageConfidence = sessionMetrics.getAverageConfidence(),
            userSatisfaction = sessionFeedback.map { it.rating }.average().takeIf { it.isFinite() }
        )
    }
    
    /**
     * Check for performance alerts
     */
    private fun checkPerformanceAlerts(
        toolName: String,
        sessionMetrics: RealtimeMetrics,
        toolMetrics: HistoricalMetrics
    ) {
        // Check for slow execution
        if (toolMetrics.getAverageExecutionTime() > 10000) { // > 10 seconds
            createAlert(
                id = "${toolName}_slow_execution",
                type = AlertType.SLOW_EXECUTION,
                severity = AlertSeverity.MEDIUM,
                toolName = toolName,
                message = "Tool $toolName has slow average execution time: ${toolMetrics.getAverageExecutionTime()}ms"
            )
        }
        
        // Check for low success rate
        if (toolMetrics.getSuccessRate() < 0.7) { // < 70%
            createAlert(
                id = "${toolName}_low_success",
                type = AlertType.LOW_SUCCESS_RATE,
                severity = AlertSeverity.HIGH,
                toolName = toolName,
                message = "Tool $toolName has low success rate: ${(toolMetrics.getSuccessRate() * 100).toInt()}%"
            )
        }
        
        // Check for poor user feedback
        val avgRating = toolMetrics.getAverageUserRating()
        if (avgRating != null && avgRating < 3.0) { // < 3/5 stars
            createAlert(
                id = "${toolName}_poor_feedback",
                type = AlertType.POOR_USER_FEEDBACK,
                severity = AlertSeverity.MEDIUM,
                toolName = toolName,
                message = "Tool $toolName has poor user feedback: ${avgRating}/5"
            )
        }
    }
    
    /**
     * Create performance alert
     */
    private fun createAlert(
        id: String,
        type: AlertType,
        severity: AlertSeverity,
        toolName: String,
        message: String
    ) {
        val alert = PerformanceAlert(
            id = id,
            type = type,
            severity = severity,
            toolName = toolName,
            message = message,
            timestamp = Instant.now(),
            resolved = false
        )
        
        activeAlerts[id] = alert
        
        println("[ANALYTICS] ALERT: ${severity.name} - $message")
    }
    
    /**
     * Analyze feedback patterns
     */
    private fun analyzeFeedbackPatterns(feedback: UserFeedback) {
        // Check for recurring issues
        if (feedback.issues.isNotEmpty()) {
            feedback.issues.forEach { issue ->
                val issueCount = userFeedback.values.flatten()
                    .count { it.issues.contains(issue) }
                
                if (issueCount > 5) { // Threshold for pattern detection
                    createAlert(
                        id = "recurring_issue_${issue.hashCode()}",
                        type = AlertType.RECURRING_ISSUE,
                        severity = AlertSeverity.MEDIUM,
                        toolName = feedback.toolName,
                        message = "Recurring issue detected: $issue (${issueCount} occurrences)"
                    )
                }
            }
        }
        
        // Check for improvement suggestions
        if (feedback.suggestions.isNotEmpty()) {
            // Analyze suggestions for common themes
            val suggestions = userFeedback.values.flatten()
                .flatMap { it.suggestions }
                .groupBy { it }
                .mapValues { it.value.size }
            
            suggestions.filter { it.value > 3 }.forEach { (suggestion, count) ->
                createAlert(
                    id = "improvement_suggestion_${suggestion.hashCode()}",
                    type = AlertType.IMPROVEMENT_SUGGESTION,
                    severity = AlertSeverity.LOW,
                    toolName = feedback.toolName,
                    message = "Common improvement suggestion: $suggestion (${count} times)"
                )
            }
        }
    }
    
    /**
     * Calculate overall metrics
     */
    private fun calculateOverallMetrics(
        sessionMetrics: List<RealtimeMetrics>,
        toolMetrics: List<HistoricalMetrics>
    ): OverallMetrics {
        val totalExecutions = sessionMetrics.sumOf { it.executionCount }
        val totalSuccesses = sessionMetrics.sumOf { it.successCount }
        val totalTime = sessionMetrics.sumOf { it.totalExecutionTime }
        
        return OverallMetrics(
            totalExecutions = totalExecutions,
            successRate = if (totalExecutions > 0) totalSuccesses.toDouble() / totalExecutions else 0.0,
            averageExecutionTime = if (totalExecutions > 0) totalTime.toDouble() / totalExecutions else 0.0,
            averageUserRating = toolMetrics.mapNotNull { it.getAverageUserRating() }.average().takeIf { it.isFinite() } ?: 0.0,
            totalSessions = sessionMetrics.size,
            activeAlerts = activeAlerts.values.count { !it.resolved }
        )
    }
    
    /**
     * Generate performance insights
     */
    private fun generatePerformanceInsights(
        sessionMetrics: List<RealtimeMetrics>,
        toolMetrics: List<HistoricalMetrics>,
        overallMetrics: OverallMetrics
    ): List<PerformanceInsight> {
        val insights = mutableListOf<PerformanceInsight>()
        
        // Success rate insight
        if (overallMetrics.successRate > 0.9) {
            insights.add(PerformanceInsight(
                type = InsightType.POSITIVE,
                title = "High Success Rate",
                description = "Overall success rate is ${(overallMetrics.successRate * 100).toInt()}%",
                recommendation = "Maintain current tool selection and execution strategies."
            ))
        } else if (overallMetrics.successRate < 0.7) {
            insights.add(PerformanceInsight(
                type = InsightType.NEGATIVE,
                title = "Low Success Rate",
                description = "Overall success rate is only ${(overallMetrics.successRate * 100).toInt()}%",
                recommendation = "Review tool selection criteria and parameter extraction."
            ))
        }
        
        // Performance insight
        if (overallMetrics.averageExecutionTime > 5000) {
            insights.add(PerformanceInsight(
                type = InsightType.WARNING,
                title = "Slow Execution",
                description = "Average execution time is ${overallMetrics.averageExecutionTime.toInt()}ms",
                recommendation = "Consider optimizing tool execution or parallelizing operations."
            ))
        }
        
        // User satisfaction insight
        if (overallMetrics.averageUserRating > 4.0) {
            insights.add(PerformanceInsight(
                type = InsightType.POSITIVE,
                title = "High User Satisfaction",
                description = "Average user rating is ${overallMetrics.averageUserRating}/5",
                recommendation = "Continue current approach and share best practices."
            ))
        } else if (overallMetrics.averageUserRating < 3.0) {
            insights.add(PerformanceInsight(
                type = InsightType.NEGATIVE,
                title = "Low User Satisfaction",
                description = "Average user rating is ${overallMetrics.averageUserRating}/5",
                recommendation = "Review user feedback and address common issues."
            ))
        }
        
        return insights
    }
    
    /**
     * Get top performing tools
     */
    private fun getTopPerformers(toolMetrics: List<HistoricalMetrics>): List<ToolPerformanceSummary> {
        return toolMetrics
            .map { ToolPerformanceSummary(it) }
            .sortedByDescending { it.overallScore }
            .take(5)
    }
    
    /**
     * Get performance issues
     */
    private fun getPerformanceIssues(toolMetrics: List<HistoricalMetrics>): List<ToolPerformanceIssue> {
        val issues = mutableListOf<ToolPerformanceIssue>()
        
        toolMetrics.forEach { metrics ->
            if (metrics.getSuccessRate() < 0.7) {
                issues.add(ToolPerformanceIssue(
                    toolName = metrics.toolName,
                    issueType = IssueType.LOW_SUCCESS_RATE,
                    severity = if (metrics.getSuccessRate() < 0.5) IssueSeverity.HIGH else IssueSeverity.MEDIUM,
                    description = "Success rate: ${(metrics.getSuccessRate() * 100).toInt()}%"
                ))
            }
            
            if (metrics.getAverageExecutionTime() > 10000) {
                issues.add(ToolPerformanceIssue(
                    toolName = metrics.toolName,
                    issueType = IssueType.SLOW_EXECUTION,
                    severity = if (metrics.getAverageExecutionTime() > 30000) IssueSeverity.HIGH else IssueSeverity.MEDIUM,
                    description = "Average execution time: ${metrics.getAverageExecutionTime().toInt()}ms"
                ))
            }
            
            val avgRating = metrics.getAverageUserRating()
            if (avgRating != null && avgRating < 3.0) {
                issues.add(ToolPerformanceIssue(
                    toolName = metrics.toolName,
                    issueType = IssueType.POOR_USER_FEEDBACK,
                    severity = if (avgRating < 2.0) IssueSeverity.HIGH else IssueSeverity.MEDIUM,
                    description = "User rating: ${avgRating}/5"
                ))
            }
        }
        
        return issues.sortedByDescending { it.severity.ordinal }
    }
    
    /**
     * Analyze trends
     */
    private fun analyzeTrends(toolMetrics: List<HistoricalMetrics>, timeRange: TimeRange): List<PerformanceTrend> {
        val trends = mutableListOf<PerformanceTrend>()
        
        // Success rate trend
        val successRateTrend = calculateTrend(toolMetrics.map { it.getSuccessRate() })
        trends.add(PerformanceTrend(
            metric = "Success Rate",
            trend = successRateTrend,
            description = when {
                successRateTrend > 0.05 -> "Improving"
                successRateTrend < -0.05 -> "Declining"
                else -> "Stable"
            }
        ))
        
        // Execution time trend
        val executionTimeTrend = calculateTrend(toolMetrics.map { it.getAverageExecutionTime() })
        trends.add(PerformanceTrend(
            metric = "Execution Time",
            trend = -executionTimeTrend, // Negative is better for execution time
            description = when {
                executionTimeTrend < -0.1 -> "Getting Faster"
                executionTimeTrend > 0.1 -> "Getting Slower"
                else -> "Stable"
            }
        ))
        
        // User rating trend
        val ratingTrend = calculateTrend(toolMetrics.mapNotNull { it.getAverageUserRating() })
        trends.add(PerformanceTrend(
            metric = "User Rating",
            trend = ratingTrend,
            description = when {
                ratingTrend > 0.1 -> "Improving"
                ratingTrend < -0.1 -> "Declining"
                else -> "Stable"
            }
        ))
        
        return trends
    }
    
    /**
     * Calculate trend from data points
     */
    private fun calculateTrend(values: List<Double>): Double {
        if (values.size < 2) return 0.0
        
        // Simple linear regression slope
        val n = values.size.toDouble()
        val sumX = (0 until values.size).sum()
        val sumY = values.sum()
        val sumXY = values.mapIndexed { index, value -> index * value }.sum()
        val sumX2 = (0 until values.size).sumOf { it * it }
        
        return (n * sumXY - sumX * sumY) / (n * sumX2 - sumX * sumX)
    }
}

/**
 * Real-time metrics for a session
 */
@Serializable
data class RealtimeMetrics(
    val sessionId: String,
    @Contextual val startTime: Instant,
    var executionCount: Int = 0,
    var successCount: Int = 0,
    var totalExecutionTime: Long = 0,
    var totalConfidence: Double = 0.0,
    var feedbackCount: Int = 0,
    var totalRating: Int = 0,
    var helpfulCount: Int = 0
) {
    
    fun addExecution(toolName: String, category: McpToolCategory, executionTime: Long, success: Boolean, resultQuality: Double?) {
        executionCount++
        if (success) successCount++
        totalExecutionTime += executionTime
        if (resultQuality != null) {
            totalConfidence += resultQuality
        }
    }
    
    fun addFeedback(rating: Int, helpful: Boolean) {
        feedbackCount++
        totalRating += rating
        if (helpful) helpfulCount++
    }
    
    fun getSuccessRate(): Double = if (executionCount > 0) successCount.toDouble() / executionCount else 0.0
    fun getAverageExecutionTime(): Double = if (executionCount > 0) totalExecutionTime.toDouble() / executionCount else 0.0
    fun getAverageConfidence(): Double = if (executionCount > 0) totalConfidence / executionCount else 0.0
    fun getAverageRating(): Double = if (feedbackCount > 0) totalRating.toDouble() / feedbackCount else 0.0
}

/**
 * Historical metrics for a tool
 */
@Serializable
data class HistoricalMetrics(
    val toolName: String,
    val category: McpToolCategory,
    private val executions: MutableList<ExecutionRecord> = mutableListOf(),
    private val feedbacks: MutableList<UserFeedback> = mutableListOf()
) {
    
    fun addExecution(executionTime: Long, success: Boolean, resultQuality: Double?) {
        executions.add(ExecutionRecord(
            timestamp = Instant.now(),
            executionTime = executionTime,
            success = success,
            resultQuality = resultQuality
        ))
        
        // Keep only recent executions
        if (executions.size > 1000) {
            executions.removeAt(0)
        }
    }
    
    fun addFeedback(feedback: UserFeedback) {
        feedbacks.add(feedback)
        
        // Keep only recent feedback
        if (feedbacks.size > 500) {
            feedbacks.removeAt(0)
        }
    }
    
    fun hasDataInTimeRange(cutoff: Instant): Boolean {
        return executions.any { it.timestamp.isAfter(cutoff) } ||
               feedbacks.any { it.timestamp.isAfter(cutoff) }
    }
    
    fun getSuccessRate(): Double {
        return if (executions.isNotEmpty()) {
            executions.count { it.success }.toDouble() / executions.size
        } else 0.0
    }
    
    fun getAverageExecutionTime(): Double {
        return if (executions.isNotEmpty()) {
            executions.map { it.executionTime }.average()
        } else 0.0
    }
    
    fun getAverageUserRating(): Double? {
        return if (feedbacks.isNotEmpty()) {
            feedbacks.map { it.rating }.average().takeIf { it.isFinite() }
        } else null
    }

    fun getExecutionCount(): Int = executions.size
}

/**
 * User feedback input
 */
@Serializable
data class UserFeedbackInput(
    val rating: Int, // 1-5
    val helpful: Boolean,
    val comment: String?,
    val issues: List<String>,
    val suggestions: List<String>
)

/**
 * User feedback record
 */
@Serializable
data class UserFeedback(
    val sessionId: String,
    val toolName: String,
    @Contextual val timestamp: Instant,
    val rating: Int,
    val helpful: Boolean,
    val comment: String?,
    val issues: List<String>,
    val suggestions: List<String>
)

/**
 * Execution record for historical metrics
 */
@Serializable
data class ExecutionRecord(
    @Contextual val timestamp: Instant,
    val executionTime: Long,
    val success: Boolean,
    val resultQuality: Double?
)

/**
 * Execution data record
 */
@Serializable
data class ExecutionData(
    @Contextual val timestamp: Instant,
    val executionTime: Long,
    val success: Boolean,
    val parameters: Map<String, String>,
    val resultQuality: Double?
)

/**
 * Performance alert
 */
@Serializable
data class PerformanceAlert(
    val id: String,
    val type: AlertType,
    val severity: AlertSeverity,
    val toolName: String,
    val message: String,
    @Contextual val timestamp: Instant,
    var resolved: Boolean
)

/**
 * Alert types
 */
enum class AlertType {
    SLOW_EXECUTION,
    LOW_SUCCESS_RATE,
    POOR_USER_FEEDBACK,
    RECURRING_ISSUE,
    IMPROVEMENT_SUGGESTION
}

/**
 * Alert severity
 */
enum class AlertSeverity {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL
}

/**
 * Performance dashboard
 */
data class PerformanceDashboard(
    val timeRange: TimeRange,
    val overallMetrics: OverallMetrics,
    val toolPerformance: List<ToolPerformanceSummary>,
    val topPerformers: List<ToolPerformanceSummary>,
    val performanceIssues: List<ToolPerformanceIssue>,
    val insights: List<PerformanceInsight>,
    val trends: List<PerformanceTrend>,
    val activeAlerts: List<PerformanceAlert>
)

/**
 * Overall metrics
 */
data class OverallMetrics(
    val totalExecutions: Int,
    val successRate: Double,
    val averageExecutionTime: Double,
    val averageUserRating: Double,
    val totalSessions: Int,
    val activeAlerts: Int
)

/**
 * Tool performance summary
 */
data class ToolPerformanceSummary(
    val toolName: String,
    val category: McpToolCategory,
    val successRate: Double,
    val averageExecutionTime: Double,
    val averageUserRating: Double?,
    val totalExecutions: Int,
    val overallScore: Double
) {
    constructor(metrics: HistoricalMetrics) : this(
        toolName = metrics.toolName,
        category = metrics.category,
        successRate = metrics.getSuccessRate(),
        averageExecutionTime = metrics.getAverageExecutionTime(),
        averageUserRating = metrics.getAverageUserRating(),
        totalExecutions = metrics.getExecutionCount(),
        overallScore = calculateOverallScore(metrics)
    )
    
    companion object {
        private fun calculateOverallScore(metrics: HistoricalMetrics): Double {
            val successScore = metrics.getSuccessRate() * 0.4
            val speedScore = (1.0 - min(1.0, metrics.getAverageExecutionTime() / 10000.0)) * 0.3
            val ratingScore = (metrics.getAverageUserRating() ?: 3.0) / 5.0 * 0.3
            return successScore + speedScore + ratingScore
        }
    }
}

/**
 * Performance issue
 */
data class ToolPerformanceIssue(
    val toolName: String,
    val issueType: IssueType,
    val severity: IssueSeverity,
    val description: String
)

/**
 * Issue types
 */
enum class IssueType {
    LOW_SUCCESS_RATE,
    SLOW_EXECUTION,
    POOR_USER_FEEDBACK
}

/**
 * Issue severity
 */
enum class IssueSeverity {
    LOW,
    MEDIUM,
    HIGH
}

/**
 * Performance insight
 */
data class PerformanceInsight(
    val type: InsightType,
    val title: String,
    val description: String,
    val recommendation: String
)

/**
 * Insight types
 */
enum class InsightType {
    POSITIVE,
    NEGATIVE,
    WARNING,
    OPTIMIZATION
}

/**
 * Performance trend
 */
data class PerformanceTrend(
    val metric: String,
    val trend: Double,
    val description: String
)

/**
 * Time range for analytics
 */
enum class TimeRange {
    LAST_HOUR,
    LAST_24_HOURS,
    LAST_WEEK,
    LAST_MONTH
}

/**
 * Feedback collector interface
 */
interface FeedbackCollector {
    fun storeFeedback(feedback: UserFeedback)
}

/**
 * Metrics storage interface
 */
interface MetricsStorage {
    fun storeExecutionData(sessionId: String, toolName: String, executionData: ExecutionData)
}
