package com.alyk.ai.koog.core.orchestrator

import com.alyk.ai.koog.context.hierarchy.HierarchyBuilder
import com.alyk.ai.koog.context.provider.ContextProvider
import com.alyk.ai.koog.core.session.SessionStore
import com.alyk.ai.koog.models.wrappers.ModelWrapper
import com.alyk.ai.koog.switching.decision.DecisionEngine
import com.alyk.ai.koog.switching.decision.ModelSwitchControl

/**
 * Central coordination of all agent components, managing request routing,
 * model selection, and session lifecycle.
 */
class AgentOrchestrator(
    private val sessionStore: SessionStore,
    private val decisionEngine: DecisionEngine,
    private val contextProvider: ContextProvider,
    private val hierarchyBuilder: HierarchyBuilder,
    private val localModel: ModelWrapper,
    private val cloudModel: ModelWrapper? = null,
    private val modelSwitchControl: ModelSwitchControl? = null
) {
    /**
     * Initialize and maintain references to local/cloud model wrappers
     */
    suspend fun initialize(projectPath: String? = null) {
        projectPath?.let {
            loadProject(it)
        }
    }

    /**
     * Load or switch to a different project at runtime
     */
    suspend fun loadProject(projectPath: String): Result<Unit> {
        return try {
            contextProvider.loadProject(projectPath)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Get currently loaded project information
     */
    fun getCurrentProject(): String? = contextProvider.getProjectRoot()

    /**
     * Route incoming tasks based on decision engine recommendations
     */
    suspend fun routeTask(task: String, sessionId: String): String {
        val context = contextProvider.getContextForTask(task)
        val promptWithContext = buildPrompt(task, context)
        
        // Use current model from ModelSwitchControl if available, otherwise fall back to localModel
        val currentModel = modelSwitchControl?.getCurrentModel() ?: localModel
        return currentModel.generate(promptWithContext)
    }

    private fun buildPrompt(task: String, context: String): String {
        return """
            |Task: $task
            |
            |$context
            |
            |Please complete the task considering the project context above.
        """.trimMargin()
    }

    /**
     * Maintain session state across model switches using coroutines
     */
    suspend fun switchModel(sessionId: String, newModel: ModelWrapper) {
        // TODO: Implement model switching with state preservation
    }

    /**
     * Implement fallback strategies when primary model fails
     */
    suspend fun handleFallback(sessionId: String, error: Throwable): String {
        // TODO: Implement fallback logic
        return "Fallback handled (stub)"
    }

    /**
     * Track agent health metrics and expose status endpoint
     */
    fun getHealthStatus(): HealthStatus {
        // Minimal health status for launcher integration.
        return HealthStatus(isHealthy = true, activeSessions = 0)
    }

    /**
     * Get performance monitoring statistics
     */
    fun getPerformanceStats(): com.alyk.ai.koog.switching.monitor.PerformanceStats {
        return decisionEngine.getPerformanceStats()
    }

    /**
     * Get current warning signals from performance monitoring
     */
    fun getPerformanceWarnings(): List<com.alyk.ai.koog.switching.monitor.WarningSignal> {
        return decisionEngine.getPerformanceWarnings()
    }

    /**
     * Check for performance alerts that exceed thresholds
     */
    fun getPerformanceAlerts(): List<com.alyk.ai.koog.switching.monitor.Alert> {
        return decisionEngine.getPerformanceAlerts()
    }
}

data class HealthStatus(
    val isHealthy: Boolean,
    val activeSessions: Int
)
