package gui.data

import java.time.Duration
import java.time.Instant

/**
 * DTOs (Data Transfer Objects) for UI layer
 * These are pure data classes without business logic
 */

/**
 * Terminal event DTO for display
 */
data class TerminalEventDto(
    val id: String,
    val timestamp: Instant,
    val type: EventType,
    val message: String,
    val progressPercent: Int? = null
) {
    enum class EventType {
        STANDARD,
        SUCCESS,
        WARNING,
        ERROR,
        SYSTEM,
        DEBUG,
        PROGRESS,
        COMPLETE
    }
}

/**
 * Agent status DTO for UI display
 */
data class AgentStatusDto(
    val currentModel: ModelTypeDto,
    val contextUsage: Float,
    val filesLoaded: Int,
    val sessionTime: Duration,
    val confidence: Float,
    val lastSwitch: Instant?,
    val errors: List<String>,
    val avgResponseTimeMs: Long,
    val performanceWarnings: List<String>,
    val hasPerformanceAlert: Boolean,
    val currentModelId: String,
    // Enhanced monitoring fields
    val totalRequests: Int = 0,
    val totalTokensUsed: Int = 0,
    val totalTokensGenerated: Int = 0,
    val maxResponseTimeMs: Long = 0,
    val toolUsageStats: ToolUsageStatsDto? = null,
    val fileAccessStats: FileAccessStatsDto? = null,
    val projectPath: String? = null,
    val mcpToolsCount: Int = 0
)

/**
 * Tool usage statistics DTO
 */
data class ToolUsageStatsDto(
    val totalToolCalls: Int,
    val recentToolCalls: Int,
    val successRate: Float,
    val avgToolDurationMs: Long,
    val toolBreakdown: Map<String, Int>
)

/**
 * File access statistics DTO
 */
data class FileAccessStatsDto(
    val totalFileOperations: Int,
    val readOperations: Int,
    val writeOperations: Int,
    val searchOperations: Int,
    val listOperations: Int,
    val successfulOperations: Int,
    val failedOperations: Int
)

/**
 * Model type DTO
 */
enum class ModelTypeDto {
    LOCAL,
    CLOUD,
    SWITCHING,
    ERROR,
    CLOUD_OLLAMA,
    CLOUD_HF,
    CLOUD_REPLICATE,
    CLOUD_ANYSCALE
}

/**
 * Performance metrics DTO
 */
data class PerformanceMetricsDto(
    val avgResponseTimeMs: Long,
    val maxResponseTimeMs: Long,
    val totalRequests: Int,
    val totalTokensUsed: Int,
    val totalTokensGenerated: Int,
    val warnings: List<PerformanceWarningDto>,
    val alerts: List<PerformanceAlertDto>
)

data class PerformanceWarningDto(
    val severity: String,
    val message: String
)

data class PerformanceAlertDto(
    val message: String,
    val threshold: String,
    val currentValue: Double
)

/**
 * Project DTO
 */
data class ProjectDto(
    val id: String,
    val name: String,
    val path: String,
    val isActive: Boolean
)

/**
 * MCP status DTO
 */
sealed class McpStatusDto {
    object NotConnected : McpStatusDto()
    data class Connected(val projectPath: String) : McpStatusDto()
    data class Error(val message: String) : McpStatusDto()
}

/**
 * Terminal state DTO
 */
data class TerminalStateDto(
    val events: List<TerminalEventDto>,
    val inputText: String,
    val isProcessing: Boolean,
    val selectedAgentType: AgentTypeDto = AgentTypeDto.IMPLEMENTATION
)

/**
 * Agent type DTO for UI selection
 */
enum class AgentTypeDto {
    IDEA,
    ARCHITECTURE,
    MODULE,
    TEST,
    IMPLEMENTATION
}
