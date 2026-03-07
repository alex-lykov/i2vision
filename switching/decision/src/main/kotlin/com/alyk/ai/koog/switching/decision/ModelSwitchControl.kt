package com.alyk.ai.koog.switching.decision

import com.alyk.ai.koog.models.wrappers.ModelInfo
import com.alyk.ai.koog.models.wrappers.ModelWrapper
import com.alyk.ai.koog.switching.monitor.PerformanceMonitor

/**
 * Controls model switching operations, managing the current active model
 * and handling transitions between different models.
 */
class ModelSwitchControl(
    private val performanceMonitor: PerformanceMonitor,
    private var initialModel: ModelWrapper
) {
    private var currentModel: ModelWrapper = initialModel
    private val modelHistory = mutableListOf<ModelSwitchEvent>()
    
    /**
     * Get the currently active model
     */
    fun getCurrentModel(): ModelWrapper = currentModel
    
    /**
     * Get the ID of the currently active model
     */
    fun getCurrentModelId(): String = currentModel.modelName
    
    /**
     * Switch to a different model
     */
    fun switchModel(newModel: ModelWrapper): Result<Unit> {
        return try {
            val previousModel = currentModel
            currentModel = newModel
            
            // Record the switch event
            modelHistory.add(ModelSwitchEvent(
                fromModel = previousModel.modelName,
                toModel = newModel.modelName,
                timestamp = System.currentTimeMillis(),
                reason = "User initiated"
            ))
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * Switch to a model by ID using the provided factory
     */
    fun switchToModel(
        modelId: String,
        modelFactory: (String, PerformanceMonitor) -> ModelWrapper?
    ): Result<Unit> {
        val newModel = modelFactory(modelId, performanceMonitor)
            ?: return Result.failure(IllegalArgumentException("Model not found: $modelId"))
        
        return switchModel(newModel)
    }
    
    /**
     * Get the history of model switches
     */
    fun getSwitchHistory(): List<ModelSwitchEvent> = modelHistory.toList()
    
    /**
     * Check if a switch to the specified model is recommended based on performance
     */
    fun isSwitchRecommended(): Boolean {
        val warnings = performanceMonitor.detectWarningSignals()
        return warnings.any { it.severity == com.alyk.ai.koog.switching.monitor.Severity.HIGH }
    }
    
    /**
     * Get recommendation for optimal model based on current performance
     */
    fun getSwitchRecommendation(availableModels: List<ModelInfo>): ModelSwitchRecommendation? {
        val alerts = performanceMonitor.checkThresholds()
        if (alerts.isEmpty()) return null
        
        // If current model is very slow, recommend a smaller/faster model
        val slowAlert = alerts.find { it.message.contains("CRITICAL") && it.message.contains("Response time") }
        if (slowAlert != null) {
            // Find smallest model as fallback
            val fallbackModel = availableModels.minByOrNull { it.size }
                ?: return null
            
            return ModelSwitchRecommendation(
                targetModelId = fallbackModel.id,
                reason = "Current model responding too slowly (${slowAlert.currentValue.toLong()}ms)",
                priority = com.alyk.ai.koog.switching.monitor.Severity.HIGH
            )
        }
        
        return null
    }
}

data class ModelSwitchEvent(
    val fromModel: String,
    val toModel: String,
    val timestamp: Long,
    val reason: String
)

data class ModelSwitchRecommendation(
    val targetModelId: String,
    val reason: String,
    val priority: com.alyk.ai.koog.switching.monitor.Severity
)
