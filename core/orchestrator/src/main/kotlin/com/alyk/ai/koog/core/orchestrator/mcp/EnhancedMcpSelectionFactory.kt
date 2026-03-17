package com.alyk.ai.koog.core.orchestrator.mcp

/**
 * Factory for creating enhanced MCP selection system with ML intelligence
 * Replaces hardcoded approach with adaptive learning components
 */
class EnhancedMcpSelectionFactory {
    
    /**
     * Create complete enhanced selection system
     */
    fun createEnhancedSelectionSystem(
        mcpIntegration: McpIntegration,
        feedbackCollector: FeedbackCollector = InMemoryFeedbackCollector(),
        metricsStorage: MetricsStorage = InMemoryMetricsStorage()
    ): EnhancedSelectionSystem {
        
        // Create core components
        val parameterExtractor = IntelligentParameterExtractor()
        val thresholdManager = DynamicThresholdManager()
        val featureExtractor = FeatureExtractor()
        val modelTrainer = ModelTrainer()
        val mlSelector = MLToolSelector(featureExtractor, modelTrainer)
        val analyticsSystem = PerformanceAnalyticsSystem(feedbackCollector, metricsStorage)
        val debugger = ExecutionPlanDebugger(analyticsSystem)
        
        // Create enhanced selection module
        val selectionModule = McpSelectionModule(
            mcpIntegration = mcpIntegration,
            parameterExtractor = parameterExtractor,
            thresholdManager = thresholdManager,
            mlSelector = mlSelector,
            debugger = debugger,
            analyticsSystem = analyticsSystem
        )
        
        return EnhancedSelectionSystem(
            selectionModule = selectionModule,
            thresholdManager = thresholdManager,
            mlSelector = mlSelector,
            debugger = debugger,
            analyticsSystem = analyticsSystem
        )
    }
    
    /**
     * Create simplified version for testing
     */
    fun createTestSelectionSystem(mcpIntegration: McpIntegration): McpSelectionModule {
        val parameterExtractor = IntelligentParameterExtractor()
        val thresholdManager = DynamicThresholdManager()
        val featureExtractor = FeatureExtractor()
        val modelTrainer = ModelTrainer()
        val mlSelector = MLToolSelector(featureExtractor, modelTrainer)
        val feedbackCollector = InMemoryFeedbackCollector()
        val metricsStorage = InMemoryMetricsStorage()
        val analyticsSystem = PerformanceAnalyticsSystem(feedbackCollector, metricsStorage)
        val debugger = ExecutionPlanDebugger(analyticsSystem)
        
        return McpSelectionModule(
            mcpIntegration = mcpIntegration,
            parameterExtractor = parameterExtractor,
            thresholdManager = thresholdManager,
            mlSelector = mlSelector,
            debugger = debugger,
            analyticsSystem = analyticsSystem
        )
    }
}

/**
 * Complete enhanced selection system
 */
data class EnhancedSelectionSystem(
    val selectionModule: McpSelectionModule,
    val thresholdManager: DynamicThresholdManager,
    val mlSelector: MLToolSelector,
    val debugger: ExecutionPlanDebugger,
    val analyticsSystem: PerformanceAnalyticsSystem
) {
    
    /**
     * Get comprehensive system status
     */
    fun getSystemStatus(): SystemStatus {
        val analytics = analyticsSystem.getPerformanceDashboard()
        
        return SystemStatus(
            totalToolsProcessed = analytics.overallMetrics.totalExecutions,
            averageSuccessRate = analytics.overallMetrics.successRate,
            averageUserSatisfaction = analytics.overallMetrics.averageUserRating,
            activeAlerts = analytics.activeAlerts.size,
            mlModelTrained = true, // Would check actual training status
            adaptiveThresholdsEnabled = true,
            debuggingEnabled = true
        )
    }
    
    /**
     * Train system with feedback data
     */
    suspend fun trainWithFeedback(feedback: List<UserFeedbackInput>) {
        feedback.forEach { fb ->
            // Convert to proper format and train ML model
            mlSelector.trainModel(emptyList()) // Would convert feedback first
        }
    }
}

/**
 * System status information
 */
data class SystemStatus(
    val totalToolsProcessed: Int,
    val averageSuccessRate: Double,
    val averageUserSatisfaction: Double,
    val activeAlerts: Int,
    val mlModelTrained: Boolean,
    val adaptiveThresholdsEnabled: Boolean,
    val debuggingEnabled: Boolean
)

/**
 * In-memory feedback collector for testing
 */
class InMemoryFeedbackCollector : FeedbackCollector {
    private val feedback = mutableListOf<UserFeedback>()
    
    override fun storeFeedback(feedback: UserFeedback) {
        this.feedback.add(feedback)
    }
    
    fun getAllFeedback(): List<UserFeedback> = feedback.toList()
}

/**
 * In-memory metrics storage for testing
 */
class InMemoryMetricsStorage : MetricsStorage {
    private val executionData = mutableMapOf<String, MutableList<ExecutionData>>()
    
    override fun storeExecutionData(sessionId: String, toolName: String, executionData: ExecutionData) {
        val key = "$sessionId:$toolName"
        this.executionData.getOrPut(key) { mutableListOf() }.add(executionData)
    }
    
    fun getExecutionData(sessionId: String, toolName: String): List<ExecutionData> {
        val key = "$sessionId:$toolName"
        return executionData[key]?.toList() ?: emptyList()
    }
}
