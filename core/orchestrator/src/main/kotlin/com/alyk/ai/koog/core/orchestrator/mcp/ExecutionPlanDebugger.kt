package com.alyk.ai.koog.core.orchestrator.mcp

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Execution plan debugging and visualization system
 * Provides real-time insights into tool selection and execution planning
 */
class ExecutionPlanDebugger(
    private val analyticsCollector: AnalyticsCollector
) {
    
    // Debug session storage
    private val debugSessions = ConcurrentHashMap<String, DebugSession>()
    
    // Visualization data cache
    private val visualizationCache = ConcurrentHashMap<String, VisualizationData>()
    
    /**
     * Create debug session for execution plan
     */
    fun createDebugSession(
        planId: String,
        prompt: String,
        selectedTools: List<MLSelectedTool>,
        executionPlan: ExecutionPlan
    ): DebugSession {
        
        val session = DebugSession(
            sessionId = planId,
            prompt = prompt,
            timestamp = Instant.now(),
            selectedTools = selectedTools,
            executionPlan = executionPlan,
            debugEvents = mutableListOf()
        )
        
        debugSessions[planId] = session
        
        // Generate initial visualization data
        val vizData = generateVisualizationData(session)
        visualizationCache[planId] = vizData
        
        println("[DEBUGGER] Created debug session for plan: $planId")
        
        return session
    }
    
    /**
     * Record debug event during execution
     */
    fun recordDebugEvent(
        planId: String,
        eventType: DebugEventType,
        toolName: String?,
        message: String,
        data: Map<String, Any> = emptyMap()
    ) {
        val session = debugSessions[planId] ?: return
        
        val event = DebugEvent(
            timestamp = Instant.now(),
            type = eventType,
            toolName = toolName,
            message = message,
            data = data.mapValues { (_, v) -> v.toString() }
        )
        
        session.debugEvents.add(event)
        
        // Update visualization data
        updateVisualizationData(planId, event)
        
        println("[DEBUGGER] Recorded event: ${eventType.name} for $toolName - $message")
    }
    
    /**
     * Generate visualization data for debugging
     */
    private fun generateVisualizationData(session: DebugSession): VisualizationData {
        
        // Tool selection visualization
        val toolSelectionViz = ToolSelectionVisualization(
            tools = session.selectedTools.map { tool ->
                ToolSelectionNode(
                    name = tool.tool.name,
                    category = tool.tool.category,
                    score = tool.mlScore,
                    confidence = tool.confidence,
                    reasoning = tool.reasoning,
                    features = tool.features
                )
            },
            threshold = calculateVisualizationThreshold(session.selectedTools),
            selectionPath = reconstructSelectionPath(session.selectedTools)
        )
        
        // Execution plan visualization
        val executionPlanViz = ExecutionPlanVisualization(
            steps = session.executionPlan.steps.mapIndexed { index, step ->
                ExecutionStepNode(
                    id = index.toString(),
                    type = step.type.name,
                    toolName = step.tool?.name ?: "N/A",
                    description = generateStepDescription(step),
                    estimatedDuration = step.estimatedDurationMs,
                    dependencies = step.dependencies.map { it.toString() },
                    status = StepStatus.PENDING
                )
            }.toMutableList(),
            strategy = session.executionPlan.executionStrategy.name,
            complexity = session.executionPlan.complexity.name,
            estimatedTotalDuration = session.executionPlan.estimatedDurationMs
        )
        
        // Performance metrics visualization
        val performanceViz = PerformanceVisualization(
            toolScores = session.selectedTools.map { it.mlScore },
            confidenceScores = session.selectedTools.map { it.confidence },
            categoryDistribution = calculateCategoryDistribution(session.selectedTools),
            complexityMetrics = calculateComplexityMetrics(session.selectedTools, session.executionPlan)
        )
        
        return VisualizationData(
            sessionId = session.sessionId,
            toolSelection = toolSelectionViz,
            executionPlan = executionPlanViz,
            performance = performanceViz,
            timeline = mutableListOf(),
            insights = generateInitialInsights(session).toMutableList()
        )
    }
    
    /**
     * Update visualization data with new debug event
     */
    private fun updateVisualizationData(planId: String, event: DebugEvent) {
        val vizData = visualizationCache[planId] ?: return
        
        // Add timeline event
        val timelineEvent = TimelineEvent(
            timestamp = event.timestamp,
            type = event.type.name,
            toolName = event.toolName,
            message = event.message,
            severity = when (event.type) {
                DebugEventType.ERROR -> EventSeverity.ERROR
                DebugEventType.WARNING -> EventSeverity.WARNING
                DebugEventType.INFO -> EventSeverity.INFO
                DebugEventType.SUCCESS -> EventSeverity.SUCCESS
                DebugEventType.TOOL_START -> EventSeverity.INFO
                DebugEventType.TOOL_COMPLETE -> EventSeverity.SUCCESS
                DebugEventType.PARAMETER_EXTRACTION -> EventSeverity.INFO
                DebugEventType.THRESHOLD_ADJUSTMENT -> EventSeverity.WARNING
            }
        )
        
        vizData.timeline.add(timelineEvent)
        
        // Update step status if applicable
        if (event.toolName != null) {
            vizData.executionPlan.steps.find { it.toolName == event.toolName }?.let { step ->
                step.status = when (event.type) {
                    DebugEventType.SUCCESS -> StepStatus.COMPLETED
                    DebugEventType.ERROR -> StepStatus.FAILED
                    DebugEventType.WARNING -> StepStatus.WARNING
                    else -> StepStatus.RUNNING
                }
            }
        }
        
        // Update insights based on event
        val newInsight = generateInsightFromEvent(event)
        if (newInsight != null) {
            vizData.insights.add(newInsight)
        }
    }
    
    /**
     * Get debugging interface data
     */
    fun getDebugInterface(planId: String): DebugInterface? {
        val session = debugSessions[planId] ?: return null
        val vizData = visualizationCache[planId] ?: return null
        
        return DebugInterface(
            session = session,
            visualization = vizData,
            recommendations = generateRecommendations(session, vizData),
            analytics = analyticsCollector.getSessionAnalytics(planId)
        )
    }
    
    /**
     * Generate step description
     */
    private fun generateStepDescription(step: ExecutionStep): String {
        return when (step.type) {
            StepType.TOOL_EXECUTION -> "Execute ${step.tool?.name ?: "unknown tool"}"
            StepType.DIRECT_RESPONSE -> "Generate direct response"
            StepType.SYNTHESIS -> "Synthesize results from previous steps"
        }
    }
    
    /**
     * Calculate visualization threshold
     */
    private fun calculateVisualizationThreshold(tools: List<MLSelectedTool>): Double {
        val scores = tools.map { it.mlScore }
        return if (scores.isNotEmpty()) {
            scores.average() - calculateStandardDeviation(scores, scores.average())
        } else 0.5
    }
    
    /**
     * Reconstruct selection path
     */
    private fun reconstructSelectionPath(tools: List<MLSelectedTool>): List<String> {
        return tools.map { "${it.tool.name} (${(it.mlScore * 100).toInt()}%)" }
    }
    
    /**
     * Calculate category distribution
     */
    private fun calculateCategoryDistribution(tools: List<MLSelectedTool>): Map<String, Int> {
        return tools.groupBy { it.tool.category.name }
            .mapValues { it.value.size }
    }
    
    /**
     * Calculate complexity metrics
     */
    private fun calculateComplexityMetrics(
        tools: List<MLSelectedTool>,
        plan: ExecutionPlan
    ): ComplexityMetrics {
        return ComplexityMetrics(
            toolCount = tools.size,
            stepCount = plan.steps.size,
            estimatedDuration = plan.estimatedDurationMs,
            parallelizableSteps = plan.steps.count { it.dependencies.isEmpty() },
            criticalPathLength = calculateCriticalPathLength(plan.steps)
        )
    }
    
    /**
     * Calculate critical path length
     */
    private fun calculateCriticalPathLength(steps: List<ExecutionStep>): Int {
        // Simplified critical path calculation
        return steps.maxOfOrNull { step ->
            1 + (step.dependencies.maxOfOrNull { dep -> 
                steps.indexOfFirst { steps.indexOf(it) == dep } + 1 
            } ?: 0)
        } ?: steps.size
    }
    
    /**
     * Generate initial insights
     */
    private fun generateInitialInsights(session: DebugSession): List<DebugInsight> {
        val insights = mutableListOf<DebugInsight>()
        
        // Tool selection insights
        if (session.selectedTools.isEmpty()) {
            insights.add(DebugInsight(
                type = DebugInsightType.WARNING,
                title = "No Tools Selected",
                message = "The ML model didn't select any tools. Consider adjusting thresholds or checking prompt clarity.",
                severity = InsightSeverity.MEDIUM,
                recommendation = "Lower the selection threshold or rephrase the prompt with more specific keywords."
            ))
        }
        
        // High confidence tools
        val highConfidenceTools = session.selectedTools.filter { it.confidence > 0.8 }
        if (highConfidenceTools.isNotEmpty()) {
            insights.add(DebugInsight(
                type = DebugInsightType.INFO,
                title = "High Confidence Selections",
                message = "Found ${highConfidenceTools.size} tools with high confidence scores.",
                severity = InsightSeverity.LOW,
                recommendation = "These tools are likely to be effective for the task."
            ))
        }
        
        // Complexity insights
        if (session.executionPlan.complexity == TaskComplexity.COMPLEX && session.selectedTools.size < 3) {
            insights.add(DebugInsight(
                type = DebugInsightType.WARNING,
                title = "Complex Task, Few Tools",
                message = "Task was classified as complex but few tools were selected.",
                severity = InsightSeverity.MEDIUM,
                recommendation = "Consider if additional tools might be needed for this complex task."
            ))
        }
        
        return insights
    }
    
    /**
     * Generate insight from debug event
     */
    private fun generateInsightFromEvent(event: DebugEvent): DebugInsight? {
        return when (event.type) {
            DebugEventType.ERROR -> DebugInsight(
                type = DebugInsightType.ERROR,
                title = "Execution Error",
                message = "Error in ${event.toolName}: ${event.message}",
                severity = InsightSeverity.HIGH,
                recommendation = "Check tool parameters and dependencies."
            )
            
            DebugEventType.WARNING -> DebugInsight(
                type = DebugInsightType.WARNING,
                title = "Execution Warning",
                message = "Warning in ${event.toolName}: ${event.message}",
                severity = InsightSeverity.MEDIUM,
                recommendation = "Monitor execution closely."
            )
            
            else -> null
        }
    }
    
    /**
     * Generate recommendations
     */
    private fun generateRecommendations(
        session: DebugSession,
        vizData: VisualizationData
    ): List<DebugRecommendation> {
        val recommendations = mutableListOf<DebugRecommendation>()
        
        // Threshold recommendations
        val avgScore = session.selectedTools.map { it.mlScore }.average()
        if (avgScore < 0.3) {
            recommendations.add(DebugRecommendation(
                type = RecommendationType.THRESHOLD,
                title = "Consider Lowering Threshold",
                description = "Average tool score is low (${(avgScore * 100).toInt()}%). Consider lowering the selection threshold.",
                impact = ImpactLevel.MEDIUM,
                effort = EffortLevel.LOW
            ))
        }
        
        // Performance recommendations
        val totalDuration = vizData.executionPlan.estimatedTotalDuration
        if (totalDuration > 30000) { // > 30 seconds
            recommendations.add(DebugRecommendation(
                type = RecommendationType.PERFORMANCE,
                title = "Long Execution Time",
                description = "Estimated execution time is ${totalDuration / 1000} seconds. Consider optimization.",
                impact = ImpactLevel.HIGH,
                effort = EffortLevel.MEDIUM
            ))
        }
        
        // Tool diversity recommendations
        val categories = session.selectedTools.map { it.tool.category }.distinct()
        if (categories.size == 1 && session.selectedTools.size > 3) {
            recommendations.add(DebugRecommendation(
                type = RecommendationType.DIVERSITY,
                title = "Limited Tool Diversity",
                description = "All selected tools are from the same category. Consider broader tool selection.",
                impact = ImpactLevel.MEDIUM,
                effort = EffortLevel.LOW
            ))
        }
        
        return recommendations
    }
    
    /**
     * Calculate standard deviation
     */
    private fun calculateStandardDeviation(values: List<Double>, mean: Double): Double {
        val variance = values.map { (it - mean) * (it - mean) }.average()
        return kotlin.math.sqrt(variance)
    }
}

/**
 * Debug session data
 */
data class DebugSession(
    val sessionId: String,
    val prompt: String,
    @Contextual val timestamp: Instant,
    val selectedTools: List<MLSelectedTool>,
    val executionPlan: ExecutionPlan,
    val debugEvents: MutableList<DebugEvent>
)

/**
 * Debug event
 */
@Serializable
data class DebugEvent(
    @Contextual val timestamp: Instant,
    val type: DebugEventType,
    val toolName: String?,
    val message: String,
    val data: Map<String, String>
)

/**
 * Debug event types
 */
enum class DebugEventType {
    INFO,
    WARNING,
    ERROR,
    SUCCESS,
    TOOL_START,
    TOOL_COMPLETE,
    PARAMETER_EXTRACTION,
    THRESHOLD_ADJUSTMENT
}

/**
 * Visualization data for debugging
 */
@Serializable
data class VisualizationData(
    val sessionId: String,
    val toolSelection: ToolSelectionVisualization,
    val executionPlan: ExecutionPlanVisualization,
    val performance: PerformanceVisualization,
    val timeline: MutableList<TimelineEvent>,
    val insights: MutableList<DebugInsight>
)

/**
 * Tool selection visualization
 */
@Serializable
data class ToolSelectionVisualization(
    val tools: List<ToolSelectionNode>,
    val threshold: Double,
    val selectionPath: List<String>
)

/**
 * Individual tool selection node
 */
@Serializable
data class ToolSelectionNode(
    val name: String,
    val category: McpToolCategory,
    val score: Double,
    val confidence: Double,
    val reasoning: String,
    val features: FeatureVector
)

/**
 * Execution plan visualization
 */
@Serializable
data class ExecutionPlanVisualization(
    val steps: MutableList<ExecutionStepNode>,
    val strategy: String,
    val complexity: String,
    val estimatedTotalDuration: Long
)

/**
 * Individual execution step node
 */
@Serializable
data class ExecutionStepNode(
    val id: String,
    val type: String,
    val toolName: String,
    val description: String,
    val estimatedDuration: Long,
    val dependencies: List<String>,
    var status: StepStatus
)

/**
 * Step status
 */
enum class StepStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    WARNING
}

/**
 * Performance visualization
 */
@Serializable
data class PerformanceVisualization(
    val toolScores: List<Double>,
    val confidenceScores: List<Double>,
    val categoryDistribution: Map<String, Int>,
    val complexityMetrics: ComplexityMetrics
)

/**
 * Complexity metrics
 */
@Serializable
data class ComplexityMetrics(
    val toolCount: Int,
    val stepCount: Int,
    val estimatedDuration: Long,
    val parallelizableSteps: Int,
    val criticalPathLength: Int
)

/**
 * Timeline event
 */
@Serializable
data class TimelineEvent(
    @Contextual val timestamp: Instant,
    val type: String,
    val toolName: String?,
    val message: String,
    val severity: EventSeverity
)

/**
 * Event severity
 */
enum class EventSeverity {
    INFO,
    SUCCESS,
    WARNING,
    ERROR
}

/**
 * Debug insight
 */
@Serializable
data class DebugInsight(
    val type: DebugInsightType,
    val title: String,
    val message: String,
    val severity: InsightSeverity,
    val recommendation: String
)

/**
 * Debug insight types
 */
enum class DebugInsightType {
    INFO,
    WARNING,
    ERROR,
    OPTIMIZATION,
    PERFORMANCE
}

/**
 * Insight severity
 */
enum class InsightSeverity {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL
}

/**
 * Debug recommendation
 */
@Serializable
data class DebugRecommendation(
    val type: RecommendationType,
    val title: String,
    val description: String,
    val impact: ImpactLevel,
    val effort: EffortLevel
)

/**
 * Recommendation types
 */
enum class RecommendationType {
    THRESHOLD,
    PERFORMANCE,
    DIVERSITY,
    OPTIMIZATION,
    TRAINING
}

/**
 * Impact levels
 */
enum class ImpactLevel {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL
}

/**
 * Effort levels
 */
enum class EffortLevel {
    LOW,
    MEDIUM,
    HIGH
}

/**
 * Complete debug interface
 */
data class DebugInterface(
    val session: DebugSession,
    val visualization: VisualizationData,
    val recommendations: List<DebugRecommendation>,
    val analytics: SessionAnalytics?
)

/**
 * Analytics collector interface
 */
interface AnalyticsCollector {
    fun getSessionAnalytics(sessionId: String): SessionAnalytics?
}

/**
 * Session analytics data
 */
data class SessionAnalytics(
    val sessionId: String,
    val totalExecutionTime: Long,
    val toolSuccessRate: Double,
    val averageConfidence: Double,
    val userSatisfaction: Double?
)
