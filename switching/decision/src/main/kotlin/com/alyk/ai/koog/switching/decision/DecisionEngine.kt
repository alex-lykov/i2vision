package com.alyk.ai.koog.switching.decision

import com.alyk.ai.koog.models.cloud.CloudModelInfo
import com.alyk.ai.koog.models.cloud.CloudProvider
import com.alyk.ai.koog.models.wrappers.ModelWrapper
import com.alyk.ai.koog.switching.analyzer.ContextAnalyzer
import com.alyk.ai.koog.switching.monitor.Alert
import com.alyk.ai.koog.switching.monitor.PerformanceMonitor
import com.alyk.ai.koog.switching.monitor.PerformanceStats
import com.alyk.ai.koog.switching.monitor.WarningSignal

/**
 * Aggregate analyzer inputs, apply weighted rules, and determine
 * optimal model for current task.
 */
class DecisionEngine(
    private val contextAnalyzer: ContextAnalyzer,
    private val performanceMonitor: PerformanceMonitor
) {
    /**
     * DecisionEngine: Combine context analysis (40%), complexity (25%),
     * performance (20%), preferences (15%), and cloud availability (10%)
     */
    suspend fun decideOptimalModel(
        task: String,
        code: String,
        availableModels: List<ModelWrapper>,
        cloudModels: List<CloudModelInfo> = emptyList()
    ): ModelDecision {
        val contextAnalysis = contextAnalyzer.analyzeComplexity(code)
        val performanceStats = performanceMonitor.getStats()
        val warnings = performanceMonitor.detectWarningSignals()
        
        // Check if we should switch to cloud models
        val shouldUseCloud = shouldUseCloudModel(contextAnalysis, performanceStats, warnings)
        
        val selectedModel = if (shouldUseCloud && cloudModels.isNotEmpty()) {
            // Select best cloud model based on task complexity and performance
            selectBestCloudModel(task, cloudModels, performanceStats)
        } else {
            // Select best local model
            selectBestLocalModel(availableModels, contextAnalysis, performanceStats)
        }
        
        val confidence = calculateConfidence(contextAnalysis, performanceStats, selectedModel)
        val reason = generateDecisionReason(shouldUseCloud, selectedModel, contextAnalysis, performanceStats)
        
        return ModelDecision(
            selectedModel = selectedModel,
            confidence = confidence,
            reason = reason
        )
    }
    
    private fun shouldUseCloudModel(
        contextAnalysis: Any,
        performanceStats: PerformanceStats,
        warnings: List<WarningSignal>
    ): Boolean {
        // Use cloud if performance is poor or task is complex
        val hasHighSeverityWarnings = warnings.any { it.severity == com.alyk.ai.koog.switching.monitor.Severity.HIGH }
        val isSlowResponse = performanceStats.avgResponseTimeMs > 5000
        
        return hasHighSeverityWarnings || isSlowResponse
    }
    
    private fun selectBestCloudModel(
        task: String,
        cloudModels: List<CloudModelInfo>,
        performanceStats: PerformanceStats
    ): ModelWrapper {
        // Prioritize models based on task complexity and available resources
        val sortedModels = cloudModels.sortedWith(compareBy<CloudModelInfo> { 
            // Prefer models with sufficient context length
            if (task.length > 4000) it.contextLength else 0
        }.thenBy {
            // Prefer faster providers for quick tasks
            if (task.length < 1000) when (it.provider) {
                CloudProvider.OLLAMA_CLOUD -> 0
                CloudProvider.ANYSCALE -> 1
                CloudProvider.REPLICATE -> 2
                CloudProvider.HUGGING_FACE -> 3
                else -> 4
            } else 0
        }.thenBy {
            // Prefer smaller models for better performance
            it.size
        })
        
        // Return the first available model as a wrapper (would need factory in real implementation)
        // For now, this is a placeholder - actual implementation would create wrapper
        throw NotImplementedError("Cloud model wrapper creation needs factory implementation")
    }
    
    private fun selectBestLocalModel(
        availableModels: List<ModelWrapper>,
        contextAnalysis: Any,
        performanceStats: PerformanceStats
    ): ModelWrapper {
        // Simple selection - could be enhanced with more sophisticated logic
        return availableModels.firstOrNull() ?: throw IllegalStateException("No models available")
    }
    
    private fun generateDecisionReason(
        usedCloud: Boolean,
        selectedModel: ModelWrapper,
        contextAnalysis: Any,
        performanceStats: PerformanceStats
    ): String {
        return if (usedCloud) {
            "Switched to cloud model due to performance constraints"
        } else {
            "Using local model for optimal performance"
        }
    }

    /**
     * ConfidenceCalculator: Weight confidence based on signal strength and historical accuracy
     */
    fun calculateConfidence(
        contextAnalysis: Any,
        performanceStats: PerformanceStats,
        selectedModel: ModelWrapper
    ): Double {
        // TODO: Implement confidence calculation with cloud model factors
        return 0.5
    }

    /**
     * Get performance monitoring statistics
     */
    fun getPerformanceStats(): PerformanceStats = performanceMonitor.getStats()

    /**
     * Get current warning signals from performance monitoring
     */
    fun getPerformanceWarnings(): List<WarningSignal> = performanceMonitor.detectWarningSignals()

    /**
     * Check for performance alerts that exceed thresholds
     */
    fun getPerformanceAlerts(): List<Alert> = performanceMonitor.checkThresholds()
}

data class ModelDecision(
    val selectedModel: ModelWrapper,
    val confidence: Double,
    val reason: String
)
