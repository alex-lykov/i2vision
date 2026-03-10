package com.alyk.ai.koog.switching.monitor

import java.time.Instant
import com.alyk.ai.koog.models.wrappers.PerformanceMonitor as PerformanceMonitorInterface

/**
 * Real-time monitoring of model performance to detect struggle signals
 * indicating context insufficiency.
 */
class PerformanceMonitor : PerformanceMonitorInterface {
    private val metrics = mutableListOf<PerformanceMetric>()

    // Thresholds for performance warnings
    companion object {
        const val SLOW_RESPONSE_THRESHOLD_MS = 5000L  // 5 seconds
        const val VERY_SLOW_RESPONSE_THRESHOLD_MS = 15000L  // 15 seconds
        const val HIGH_TOKEN_RATIO_THRESHOLD = 2.0  // 2x tokens used vs generated
        const val SLIDING_WINDOW_SIZE = 10  // Last 10 requests for averages
    }

    /**
     * PerformanceTracker: Maintain sliding windows of response times, token usage
     */
    override fun recordMetric(responseTime: Long, tokensUsed: Int, tokensGenerated: Int) {
        metrics.add(PerformanceMetric(
            timestamp = Instant.now(),
            responseTime = responseTime,
            tokensUsed = tokensUsed,
            tokensGenerated = tokensGenerated
        ))
        // Keep only last 100 metrics
        if (metrics.size > 100) {
            metrics.removeAt(0)
        }
    }

    /**
     * SignalDetector: Identify warning patterns (slow responses, high token ratio, truncation)
     */
    fun detectWarningSignals(): List<WarningSignal> {
        val signals = mutableListOf<WarningSignal>()
        if (metrics.isEmpty()) return signals

        val recentMetrics = metrics.takeLast(SLIDING_WINDOW_SIZE)
        val avgResponseTime = recentMetrics.map { it.responseTime }.average()
        val lastMetric = metrics.last()

        // Detect slow response
        when {
            lastMetric.responseTime > VERY_SLOW_RESPONSE_THRESHOLD_MS -> {
                signals.add(WarningSignal(
                    type = SignalType.SLOW_RESPONSE,
                    severity = Severity.HIGH,
                    message = "Very slow response: ${lastMetric.responseTime}ms (threshold: ${VERY_SLOW_RESPONSE_THRESHOLD_MS}ms). Model may be struggling with context size."
                ))
            }
            lastMetric.responseTime > SLOW_RESPONSE_THRESHOLD_MS -> {
                signals.add(WarningSignal(
                    type = SignalType.SLOW_RESPONSE,
                    severity = Severity.MEDIUM,
                    message = "Slow response: ${lastMetric.responseTime}ms (threshold: ${SLOW_RESPONSE_THRESHOLD_MS}ms)"
                ))
            }
            avgResponseTime > SLOW_RESPONSE_THRESHOLD_MS -> {
                signals.add(WarningSignal(
                    type = SignalType.SLOW_RESPONSE,
                    severity = Severity.LOW,
                    message = "Average response time elevated: ${avgResponseTime.toLong()}ms over last $SLIDING_WINDOW_SIZE requests"
                ))
            }
        }

        // Detect high token ratio (inefficient token usage)
        if (lastMetric.tokensGenerated > 0) {
            val tokenRatio = lastMetric.tokensUsed.toDouble() / lastMetric.tokensGenerated
            if (tokenRatio > HIGH_TOKEN_RATIO_THRESHOLD) {
                signals.add(WarningSignal(
                    type = SignalType.HIGH_TOKEN_RATIO,
                    severity = Severity.MEDIUM,
                    message = "High token usage ratio: ${String.format("%.2f", tokenRatio)}x (used: ${lastMetric.tokensUsed}, generated: ${lastMetric.tokensGenerated})"
                ))
            }
        }

        // Detect truncation (0 tokens generated but tokens were used)
        if (lastMetric.tokensGenerated == 0 && lastMetric.tokensUsed > 0) {
            signals.add(WarningSignal(
                type = SignalType.TRUNCATION,
                severity = Severity.HIGH,
                message = "Response truncated or empty. Tokens used: ${lastMetric.tokensUsed}, but nothing generated."
            ))
        }

        return signals
    }

    /**
     * AlertManager: Trigger alerts when thresholds exceeded
     */
    fun checkThresholds(): List<Alert> {
        val alerts = mutableListOf<Alert>()
        if (metrics.isEmpty()) return alerts

        val recentMetrics = metrics.takeLast(SLIDING_WINDOW_SIZE)
        val avgResponseTime = recentMetrics.map { it.responseTime }.average()
        val lastMetric = metrics.last()

        // Response time alerts
        when {
            lastMetric.responseTime > VERY_SLOW_RESPONSE_THRESHOLD_MS -> {
                alerts.add(Alert(
                    message = "CRITICAL: Response time exceeded ${VERY_SLOW_RESPONSE_THRESHOLD_MS}ms",
                    threshold = "${VERY_SLOW_RESPONSE_THRESHOLD_MS}ms",
                    currentValue = lastMetric.responseTime.toDouble()
                ))
            }
            avgResponseTime > SLOW_RESPONSE_THRESHOLD_MS -> {
                alerts.add(Alert(
                    message = "WARNING: Average response time over ${SLOW_RESPONSE_THRESHOLD_MS}ms",
                    threshold = "${SLOW_RESPONSE_THRESHOLD_MS}ms (avg)",
                    currentValue = avgResponseTime
                ))
            }
        }

        // Token ratio alert
        if (lastMetric.tokensGenerated > 0) {
            val ratio = lastMetric.tokensUsed.toDouble() / lastMetric.tokensGenerated
            if (ratio > HIGH_TOKEN_RATIO_THRESHOLD) {
                alerts.add(Alert(
                    message = "WARNING: Token usage ratio exceeds ${HIGH_TOKEN_RATIO_THRESHOLD}x",
                    threshold = "${HIGH_TOKEN_RATIO_THRESHOLD}x",
                    currentValue = ratio
                ))
            }
        }

        return alerts
    }

    /**
     * Get summary statistics for display
     */
    fun getStats(): PerformanceStats {
        if (metrics.isEmpty()) return PerformanceStats()

        val recent = metrics.takeLast(SLIDING_WINDOW_SIZE)
        return PerformanceStats(
            totalRequests = metrics.size,
            avgResponseTimeMs = recent.map { it.responseTime }.average().toLong(),
            maxResponseTimeMs = recent.maxOfOrNull { it.responseTime } ?: 0,
            totalTokensUsed = recent.sumOf { it.tokensUsed },
            totalTokensGenerated = recent.sumOf { it.tokensGenerated }
        )
    }
}

data class PerformanceMetric(
    val timestamp: Instant,
    val responseTime: Long,
    val tokensUsed: Int,
    val tokensGenerated: Int
)

data class WarningSignal(
    val type: SignalType,
    val severity: Severity,
    val message: String
)

enum class SignalType {
    SLOW_RESPONSE, HIGH_TOKEN_RATIO, TRUNCATION
}

enum class Severity {
    LOW, MEDIUM, HIGH
}

data class Alert(
    val message: String,
    val threshold: String,
    val currentValue: Double
)

data class PerformanceStats(
    val totalRequests: Int = 0,
    val avgResponseTimeMs: Long = 0,
    val maxResponseTimeMs: Long = 0,
    val totalTokensUsed: Int = 0,
    val totalTokensGenerated: Int = 0
)
