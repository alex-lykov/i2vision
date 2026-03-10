package com.alyk.ai.koog.core.orchestrator.tools

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tracks tool usage across all agents
 * Monitors file access operations for performance and debugging
 */
class ToolUsageTracker {
    private val toolUsageCounts = ConcurrentHashMap<String, AtomicInteger>()
    private val toolUsageHistory = mutableListOf<ToolUsageEvent>()
    private val maxHistorySize = 1000
    
    /**
     * Record a tool usage event
     */
    fun recordToolUsage(toolName: String, success: Boolean, durationMs: Long = 0) {
        toolUsageCounts.getOrPut(toolName) { AtomicInteger(0) }.incrementAndGet()
        
        synchronized(toolUsageHistory) {
            toolUsageHistory.add(
                ToolUsageEvent(
                    toolName = toolName,
                    timestamp = Instant.now(),
                    success = success,
                    durationMs = durationMs
                )
            )
            
            // Keep history size manageable
            if (toolUsageHistory.size > maxHistorySize) {
                toolUsageHistory.removeAt(0)
            }
        }
    }
    
    /**
     * Get tool usage statistics
     */
    fun getToolUsageStats(): ToolUsageStats {
        val totalUsage = toolUsageCounts.values.sumOf { it.get() }
        val recentUsage = synchronized(toolUsageHistory) {
            toolUsageHistory.takeLast(100)
        }
        
        val successCount = recentUsage.count { it.success }
        val failureCount = recentUsage.size - successCount
        val avgDuration = if (recentUsage.isNotEmpty()) {
            recentUsage.map { it.durationMs }.average().toLong()
        } else 0L
        
        return ToolUsageStats(
            totalToolCalls = totalUsage,
            recentToolCalls = recentUsage.size,
            successRate = if (recentUsage.isNotEmpty()) successCount.toFloat() / recentUsage.size else 0f,
            avgToolDurationMs = avgDuration,
            toolBreakdown = toolUsageCounts.mapValues { it.value.get() },
            recentFailures = recentUsage.filter { !it.success }.take(10)
        )
    }
    
    /**
     * Get file access specific statistics
     */
    fun getFileAccessStats(): FileAccessStats {
        val fileTools = listOf("list_directory", "read_file", "write_file", "regex_search")
        val fileToolUsage = synchronized(toolUsageHistory) {
            toolUsageHistory.filter { it.toolName in fileTools }
        }
        
        return FileAccessStats(
            totalFileOperations = fileToolUsage.size,
            readOperations = fileToolUsage.count { it.toolName == "read_file" },
            writeOperations = fileToolUsage.count { it.toolName == "write_file" },
            searchOperations = fileToolUsage.count { it.toolName == "regex_search" },
            listOperations = fileToolUsage.count { it.toolName == "list_directory" },
            successfulOperations = fileToolUsage.count { it.success },
            failedOperations = fileToolUsage.count { !it.success }
        )
    }
    
    /**
     * Clear all tracking data
     */
    fun clear() {
        toolUsageCounts.clear()
        synchronized(toolUsageHistory) {
            toolUsageHistory.clear()
        }
    }
}

data class ToolUsageEvent(
    val toolName: String,
    val timestamp: Instant,
    val success: Boolean,
    val durationMs: Long
)

data class ToolUsageStats(
    val totalToolCalls: Int,
    val recentToolCalls: Int,
    val successRate: Float,
    val avgToolDurationMs: Long,
    val toolBreakdown: Map<String, Int>,
    val recentFailures: List<ToolUsageEvent>
)

data class FileAccessStats(
    val totalFileOperations: Int,
    val readOperations: Int,
    val writeOperations: Int,
    val searchOperations: Int,
    val listOperations: Int,
    val successfulOperations: Int,
    val failedOperations: Int
)
