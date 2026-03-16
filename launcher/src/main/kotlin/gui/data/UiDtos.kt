package gui.data

import java.time.Duration
import java.time.Instant
import java.util.*

/**
 * DTOs (Data Transfer Objects) for UI layer
 * These are pure data classes without business logic
 */

/**
 * Structured payload for terminal events. Enables readable rendering (tool name/result, file ops, diffs).
 */
sealed class TerminalEventPayload {
    data class ToolCall(
        val toolName: String,
        val params: Map<String, Any>,
        val result: String? = null,
        val durationMs: Long? = null,
        val success: Boolean? = null
    ) : TerminalEventPayload()
    data class FileOp(
        val operation: String, // "read" | "write" | "delete" | "list"
        val path: String,
        val contentPreview: String? = null,
        val bytesWritten: Int? = null,
        val success: Boolean? = null
    ) : TerminalEventPayload()
    data class FileDiff(
        val path: String,
        val oldPreview: String? = null,
        val newPreview: String? = null,
        val addedLines: Int = 0,
        val removedLines: Int = 0
    ) : TerminalEventPayload()
    data class Decision(
        val phase: String,
        val message: String,
        val details: Map<String, Any>? = null
    ) : TerminalEventPayload()
    data class Thinking(val text: String) : TerminalEventPayload()
}

/**
 * Terminal event DTO for display.
 * [outputSettingKey] links to terminal_settings_definitions (e.g. show_ai_responses, show_command_execution).
 * [payload] optional structured data for readable card rendering.
 * [renderOptions] specific rendering options for this event (overriding defaults).
 */
data class TerminalEventDto(
    val id: String,
    val timestamp: Instant,
    val type: EventType,
    val message: String,
    val progressPercent: Int? = null,
    val outputSettingKey: String? = null,
    val payload: TerminalEventPayload? = null,
    val renderOptions: Map<String, Any>? = null
) {
    enum class EventType {
        STANDARD,
        SUCCESS,
        WARNING,
        ERROR,
        SYSTEM,
        DEBUG,
        PROGRESS,
        COMPLETE,
        TOOL_CALL,
        FILE_OP,
        FILE_DIFF,
        DECISION,
        THINKING
    }
}

/**
 * MCP tool selection status for UI display
 */
data class McpSelectionStatusDto(
    val selectedTools: List<String>,
    val toolScores: Map<String, Double>,
    val matchedKeywords: Map<String, List<String>>,
    val relevanceThreshold: Double,
    val totalAvailableTools: Int
)

/**
 * MCP execution plan status for UI display
 */
data class McpExecutionPlanDto(
    val executionStrategy: String,
    val complexity: String,
    val estimatedDurationMs: Long,
    val canRunInParallel: Boolean,
    val stepCount: Int,
    val selectedServers: List<String>
)

/**
 * Combined MCP decision status for UI display
 */
data class McpDecisionStatusDto(
    val selection: McpSelectionStatusDto,
    val executionPlan: McpExecutionPlanDto,
    val timestamp: Instant
)

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
    val selectedAgentType: AgentTypeDto = AgentTypeDto.IMPLEMENTATION,
    val inputHistory: List<String> = emptyList(),
    val historyIndex: Int = -1 // -1 means no history selection
)

/**
 * Display options for terminal output (from TerminalSettingsState).
 * Used to align Terminal.kt with db/ui settings (e.g. show_timestamps, compact_mode).
 */
data class TerminalDisplayOptionsDto(
    val showTimestamps: Boolean = false,
    val compactMode: Boolean = false,
    val showStatusBar: Boolean = true,
    val renderRichToolCards: Boolean = true, // New setting for rich tool cards
    val highlightToolParams: Boolean = true  // New setting for param highlighting
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

/**
 * Represents a single agent tab with its state
 */
data class AgentTab(
    val id: String,
    val name: String,
    val agentType: AgentTypeDto,
    val sessionId: UUID? = null,
    val isActive: Boolean = false,
    val createdAt: Instant = Instant.now(),
    val lastActiveAt: Instant = Instant.now(),
    val projectPath: String? = null,
    val unreadCount: Int = 0,
    val hasReceivedFirstPrompt: Boolean = false
)

/**
 * State for managing multiple agent tabs
 */
data class AgentTabsState(
    val tabs: List<AgentTab> = emptyList(),
    val activeTabId: String? = null
) {
    val activeTab: AgentTab?
        get() = tabs.find { it.id == activeTabId }
        
    val hasActiveTab: Boolean
        get() = activeTabId != null && tabs.any { it.id == activeTabId }
}
