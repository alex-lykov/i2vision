package core

import kotlinx.coroutines.flow.Flow
import java.time.Duration
import java.time.Instant

interface AgentLauncher {
    fun initialize(config: Config): Result<Unit>
    fun start()
    fun shutdown()
    fun getStatus(): AgentStatus
    fun getStatusStream(): Flow<AgentStatus>
    fun processTask(task: String, mode: TaskMode): Flow<OutputEvent>
    fun switchModel(target: ModelType): Result<Unit>
    fun addStatusListener(listener: StatusListener)
    suspend fun loadProject(projectPath: String): Result<Unit>
    
    /**
     * Get list of available models from registry
     */
    fun getAvailableModels(): List<com.alyk.ai.koog.models.wrappers.ModelInfo>
    
    /**
     * Switch to a specific model by ID
     */
    fun switchToModel(modelId: String): Result<Unit>
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
    val currentModelId: String = ""
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

data class Config(
    val projectPath: String? = null,
    val defaultModel: String = "qwen3:4b",
    val maxContextLength: Int = 1024
)

// Dummy ModelType enum for compilation
enum class ModelType {
    LOCAL, CLOUD, SWITCHING, ERROR
}
