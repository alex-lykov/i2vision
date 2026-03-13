package core

import com.alyk.ai.koog.config.Config
import com.alyk.ai.koog.core.orchestrator.mcp.McpStatus
import com.alyk.ai.koog.core.session.project.ProjectRepository
import com.alyk.ai.koog.database.settings.TerminalSettingsRepository
import com.alyk.ai.koog.database.store.LoadedModelsStore
import kotlinx.coroutines.flow.Flow
import java.time.Duration
import java.time.Instant

interface AgentLauncher {
    fun initialize(config: Config): Result<Unit>
    fun start()
    fun shutdown()
    fun getStatus(): AgentStatus
    fun getStatusStream(): Flow<AgentStatus>
    fun processTask(task: String, mode: TaskMode, agentType: gui.data.AgentTypeDto = gui.data.AgentTypeDto.IMPLEMENTATION): Flow<OutputEvent>
    fun switchModel(target: ModelType): Result<Unit>
    fun addStatusListener(listener: StatusListener)
    suspend fun loadProject(projectPath: String): Result<Unit>
    /** Unload current project (clear selection and agent context). */
    suspend fun unloadProject(): Result<Unit>
    
    /**
     * Get list of available models from registry
     */
    fun getAvailableModels(): List<com.alyk.ai.koog.models.wrappers.ModelInfo>
    
    /**
     * Switch to a specific model by ID
     */
    fun switchToModel(modelId: String): Result<Unit>
    
    /**
     * Get MCP integration status
     */
    fun getMcpStatus(): McpStatus
    
    /**
     * Get available MCP tools for current project
     */
    fun getAvailableMCPTools(): Flow<String>
    
    /**
     * Get the UnifiedModelManager for advanced model operations
     */
    fun getUnifiedModelManager(): UnifiedModelManager

    /**
     * Get the project repository (projects added from UI, stored in DB).
     * Use this for the Projects card — only these projects are linked to the agent.
     */
    fun getProjectRepository(): ProjectRepository

    /**
     * Store for loaded LLM model status (local/cloud). Persist on start/stop; restore running models on boot.
     */
    fun getLoadedModelsStore(): LoadedModelsStore

    /**
     * Terminal output settings (per-session). Used to filter/format terminal feed. Null if DB not available.
     */
    fun getTerminalSettingsRepository(): TerminalSettingsRepository?

    /**
     * Register a suspend callback to run before app exit (e.g. stop all models silently).
     * Called from GUI when window closes; then [shutdown] is invoked.
     */
    fun registerBeforeExit(callback: suspend () -> Unit)

    /**
     * Run all registered before-exit hooks (e.g. stop all models). Called by GUI on window close before [shutdown].
     */
    suspend fun runBeforeExitHooks()
}

data class AgentStatus(
    val currentModel: ModelType,
    val contextUsage: Float,
    val filesLoaded: Int,
    val sessionTime: Duration,
    val confidence: Float,
    val lastSwitch: Instant?,
    val errors: List<String>,
    val avgResponseTimeMs: Long = 0,
    val performanceWarnings: List<String> = emptyList(),
    val hasPerformanceAlert: Boolean = false,
    val currentModelId: String = "",
    // Enhanced monitoring fields
    val totalRequests: Int = 0,
    val totalTokensUsed: Int = 0,
    val totalTokensGenerated: Int = 0,
    val maxResponseTimeMs: Long = 0,
    val toolUsageStats: ToolUsageStats? = null,
    val fileAccessStats: FileAccessStats? = null,
    val projectPath: String? = null,
    val mcpToolsCount: Int = 0
)

data class ToolUsageStats(
    val totalToolCalls: Int,
    val recentToolCalls: Int,
    val successRate: Float,
    val avgToolDurationMs: Long,
    val toolBreakdown: Map<String, Int>
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

enum class TaskMode {
    CURRENT_MODEL,  // Use current model
    SMART_ANALYZE,  // Let system decide
    DEBUG           // Enable debug mode
}

sealed class OutputEvent {
    data class Standard(val text: String) : OutputEvent()
    data class Success(val text: String) : OutputEvent()
    data class Warning(val text: String) : OutputEvent()
    data class Error(val text: String) : OutputEvent()
    data class System(val text: String) : OutputEvent()
    data class Debug(val text: String) : OutputEvent()
    data class Progress(val percent: Int, val message: String) : OutputEvent()
    object Complete : OutputEvent()
}

// ModelType enum for different model sources
enum class ModelType {
    LOCAL,           // Local Ollama instance
    CLOUD,           // Cloud-hosted Ollama
    SWITCHING,       // Automatic switching enabled
    ERROR,           // Model in error state
    CLOUD_OLLAMA,    // Ollama Cloud service
    CLOUD_HF,        // Hugging Face inference
    CLOUD_REPLICATE, // Replicate API
    CLOUD_ANYSCALE   // Anyscale endpoint
}
