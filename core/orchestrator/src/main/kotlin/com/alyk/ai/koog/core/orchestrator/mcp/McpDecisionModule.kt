package com.alyk.ai.koog.core.orchestrator.mcp

import java.time.Instant

/**
 * MCP Decision Module: Orchestrates tool execution based on selection results
 * Creates execution plans, manages dependencies, and handles parallel/sequential execution
 */
class McpDecisionModule(
    private val selectionModule: McpSelectionModule,
    private val mcpIntegration: McpIntegration
) {
    
    // Execution history for context awareness
    private val executionHistory = mutableListOf<ExecutionDecision>()
    private val maxHistorySize = 5
    
    /**
     * Analyze prompt and create comprehensive execution plan
     */
    suspend fun analyzeAndCreatePlan(
        prompt: String, 
        selectedTools: List<SelectedMcpTool> = emptyList(),
        extractedParams: Map<String, Map<String, Any>> = emptyMap()
    ): ExecutionPlan {
        println("[DECISION] Analyzing prompt: '$prompt'")
        println("[DECISION] Using ${selectedTools.size} pre-selected tools")
        println("[DECISION] Using ${extractedParams.size} extracted parameter sets")
        
        // Use passed selectedTools or select new ones if empty
        val toolsToUse = if (selectedTools.isNotEmpty()) selectedTools else selectionModule.selectRelevantTools(prompt)
        println("[DECISION] Using ${toolsToUse.size} tools: ${toolsToUse.map { it.tool.name }}")
        
        // Determine execution strategy
        val executionStrategy = selectionModule.determineExecutionStrategy(toolsToUse)
        println("[DECISION] Execution strategy: $executionStrategy")
        
        // Estimate complexity
        val complexity = selectionModule.estimateComplexity(prompt, toolsToUse)
        println("[DECISION] Task complexity: $complexity")
        
        // Create execution plan
        val plan = createExecutionPlan(toolsToUse, executionStrategy, complexity)
        println("[DECISION] Created execution plan with ${plan.steps.size} steps")
        
        // Record decision for future context
        recordExecutionDecision(prompt, toolsToUse, executionStrategy, complexity)
        
        return plan
    }
    
    /**
     * Create detailed execution plan with dependencies and optimization
     */
    private fun createExecutionPlan(
        selectedTools: List<SelectedMcpTool>,
        executionStrategy: ExecutionStrategy,
        complexity: TaskComplexity
    ): ExecutionPlan {
        val steps = mutableListOf<ExecutionStep>()
        
        when (executionStrategy) {
            ExecutionStrategy.NONE -> {
                // No tools selected, direct LLM response
                steps.add(ExecutionStep(
                    type = StepType.DIRECT_RESPONSE,
                    tool = null,
                    parameters = emptyMap(),
                    dependencies = emptyList(),
                    estimatedDurationMs = 1000
                ))
            }
            
            ExecutionStrategy.SEQUENTIAL -> {
                // Create optimized sequential execution plan
                val orderedTools = optimizeToolOrder(selectedTools, complexity)
                val contextAwareParams = mutableMapOf<String, Map<String, Any>>()
                
                orderedTools.forEachIndexed { index, selectedTool ->
                    // Enhance parameters with context from previous steps
                    val enhancedParams = enhanceParametersWithContext(
                        selectedTool.tool, 
                        extractParameters(selectedTool.tool),
                        emptyList(), // No previous steps in first call
                        contextAwareParams
                    )
                    contextAwareParams[selectedTool.tool.name] = enhancedParams
                    
                    steps.add(ExecutionStep(
                        type = StepType.TOOL_EXECUTION,
                        tool = selectedTool.tool,
                        parameters = enhancedParams,
                        dependencies = if (index > 0) listOf(index - 1) else emptyList(),
                        estimatedDurationMs = estimateToolDuration(selectedTool.tool, enhancedParams)
                    ))
                }
                
                // Add intelligent synthesis step
                steps.add(ExecutionStep(
                    type = StepType.SYNTHESIS,
                    tool = null,
                    parameters = mapOf(
                        "context_summary" to createContextSummary(orderedTools, contextAwareParams),
                        "optimization_applied" to true
                    ),
                    dependencies = listOf(steps.size - 2),
                    estimatedDurationMs = calculateSynthesisDuration(orderedTools, complexity)
                ))
            }
            
            ExecutionStrategy.PARALLEL -> {
                // Create optimized parallel execution plan
                val optimizedGroups = optimizeParallelGroups(selectedTools, complexity)
                val globalContext = mutableMapOf<String, Any>()
                
                optimizedGroups.forEachIndexed { groupIndex, group ->
                    val groupContext: MutableMap<String, Map<String, Any>> = mutableMapOf()
                    
                    group.forEach { selectedTool ->
                        // Enhance parameters with global and group context
                        val enhancedParams = enhanceParametersWithContext(
                            selectedTool.tool,
                            extractParameters(selectedTool.tool),
                            emptyList(), // No previous steps in first call
                            groupContext
                        )
                        groupContext[selectedTool.tool.name] = enhancedParams
                        
                        steps.add(ExecutionStep(
                            type = StepType.TOOL_EXECUTION,
                            tool = selectedTool.tool,
                            parameters = enhancedParams,
                            dependencies = emptyList(), // Parallel tools have no dependencies within group
                            estimatedDurationMs = estimateToolDuration(selectedTool.tool, enhancedParams)
                        ))
                    }
                    
                    // Update global context for next groups
                    globalContext.putAll(groupContext)
                    
                    // Add group synthesis step
                    steps.add(ExecutionStep(
                        type = StepType.SYNTHESIS,
                        tool = null,
                        parameters = mapOf(
                            "group_index" to groupIndex,
                            "group_summary" to createGroupSummary(group),
                            "global_context" to globalContext
                        ),
                        dependencies = if (groupIndex > 0) listOf(steps.size - 2) else emptyList(),
                        estimatedDurationMs = calculateGroupSynthesisDuration(group, complexity)
                    ))
                }
            }
        }
        
        return ExecutionPlan(
            prompt = "", // Will be set by caller
            selectedServers = selectedTools.map { it.tool.category },
            relevantTools = selectedTools.map { it.tool },
            toolScores = selectedTools.associate { it.tool.name to it.relevanceScore },
            steps = steps,
            executionStrategy = executionStrategy,
            complexity = complexity,
            estimatedDurationMs = steps.sumOf { it.estimatedDurationMs },
            canRunInParallel = executionStrategy == ExecutionStrategy.PARALLEL,
            context = ExecutionContext(
                executionHistory = executionHistory.takeLast(maxHistorySize),
                toolContext = getCurrentToolContext(),
                promptContext = emptyMap()
            )
        )
    }
    
    /**
     * Order tools for sequential execution based on dependencies and priority
     */
    private fun orderToolsSequentially(selectedTools: List<SelectedMcpTool>): List<SelectedMcpTool> {
        return selectedTools.sortedWith(compareBy<SelectedMcpTool> { 
            // Context tools first
            if (it.tool.category == McpToolCategory.CONTEXT) 0 else 1
        }.thenByDescending { 
            // Then by relevance score
            it.relevanceScore 
        }.thenBy { 
            // Then by category priority
            when (it.tool.category) {
                McpToolCategory.CONTEXT -> 0
                McpToolCategory.SEARCH -> 1
                McpToolCategory.ANALYSIS -> 2
                McpToolCategory.FILE_ACCESS -> 3
                McpToolCategory.EXECUTION -> 4
                McpToolCategory.VERSION_CONTROL -> 5
                McpToolCategory.NAVIGATION -> 6
                McpToolCategory.CODE_GENERATION -> 7
                McpToolCategory.DATABASE_INTEGRATION -> 8
                McpToolCategory.API_INTERACTION -> 9
                McpToolCategory.ADVANCED_ANALYSIS -> 10
            }
        })
    }
    
    /**
     * Group tools that can run in parallel
     */
    private fun groupToolsForParallelExecution(selectedTools: List<SelectedMcpTool>): List<List<SelectedMcpTool>> {
        val groups = mutableListOf<List<SelectedMcpTool>>()
        val remaining = selectedTools.toMutableList()
        
        while (remaining.isNotEmpty()) {
            val currentGroup = mutableListOf<SelectedMcpTool>()
            val iterator = remaining.iterator()
            
            while (iterator.hasNext()) {
                val tool = iterator.next()
                if (canRunInParallelWithCurrent(tool, currentGroup)) {
                    currentGroup.add(tool)
                    iterator.remove()
                }
            }
            
            if (currentGroup.isNotEmpty()) {
                groups.add(currentGroup)
            } else {
                // If nothing can be parallelized, take the next tool as a single group
                groups.add(listOf(remaining.removeAt(0)))
            }
        }
        
        return groups
    }
    
    /**
     * Check if a tool can run in parallel with the current group
     */
    private fun canRunInParallelWithCurrent(
        tool: SelectedMcpTool, 
        currentGroup: List<SelectedMcpTool>
    ): Boolean {
        // Context tools should always run first
        if (tool.tool.category == McpToolCategory.CONTEXT) {
            return currentGroup.isEmpty()
        }
        
        // Don't parallelize with context tools
        if (currentGroup.any { it.tool.category == McpToolCategory.CONTEXT }) {
            return false
        }
        
        // Don't parallelize execution tools with others
        if (tool.tool.category == McpToolCategory.EXECUTION) {
            return currentGroup.isEmpty()
        }
        
        return true
    }
    
    /**
     * Optimize tool order based on dependencies and efficiency
     */
    private fun optimizeToolOrder(tools: List<SelectedMcpTool>, complexity: TaskComplexity): List<SelectedMcpTool> {
        return when (complexity) {
            TaskComplexity.SIMPLE -> tools.sortedByDescending { it.relevanceScore }
            TaskComplexity.MODERATE -> tools.sortedByDescending { it.relevanceScore }
            TaskComplexity.COMPLEX -> {
                // For complex tasks, prioritize context and analysis tools first
                val contextTools = tools.filter { it.tool.category == McpToolCategory.CONTEXT }
                val analysisTools = tools.filter { it.tool.category == McpToolCategory.ANALYSIS }
                val otherTools = tools.filter { it.tool.category !in listOf(McpToolCategory.CONTEXT, McpToolCategory.ANALYSIS) }
                
                (contextTools + analysisTools + otherTools).sortedByDescending { it.relevanceScore }
            }
        }
    }
    
    /**
     * Optimize parallel groups based on tool compatibility and resource usage
     */
    private fun optimizeParallelGroups(tools: List<SelectedMcpTool>, complexity: TaskComplexity): List<List<SelectedMcpTool>> {
        return when (complexity) {
            TaskComplexity.SIMPLE -> {
                // For simple tasks, maximize parallelism
                tools.chunked(3)
            }
            TaskComplexity.MODERATE -> {
                // For moderate tasks, group by category
                tools.groupBy { it.tool.category }.values.toList()
            }
            TaskComplexity.COMPLEX -> {
                // For complex tasks, be more conservative with dependencies
                val sequentialTools = tools.filter { 
                    it.tool.category in listOf(McpToolCategory.CONTEXT, McpToolCategory.ANALYSIS) 
                }
                val parallelTools = tools.filter { 
                    it.tool.category !in listOf(McpToolCategory.CONTEXT, McpToolCategory.ANALYSIS) 
                }
                
                if (sequentialTools.isNotEmpty()) {
                    listOf(sequentialTools) + parallelTools.chunked(2)
                } else {
                    parallelTools.chunked(2)
                }
            }
        }
    }
    
    /**
     * Enhance parameters with execution context
     */
    private fun enhanceParametersWithContext(
        tool: ProjectMcpTool,
        baseParams: Map<String, Any>,
        previousSteps: List<ExecutionStep>,
        contextAccumulator: MutableMap<String, Map<String, Any>>
    ): Map<String, Any> {
        val enhanced = mutableMapOf<String, Any>()
        enhanced.putAll(baseParams)
        
        // Add context from previous executions
        when (tool.category) {
            McpToolCategory.SEARCH -> {
                // For search tools, add context from previous results
                val previousResults = previousSteps.filter { it.type == StepType.TOOL_EXECUTION }
                    .mapNotNull { it.parameters["results"] }
                    .takeLast(3)
                if (previousResults.isNotEmpty()) {
                    enhanced["search_context"] = previousResults
                }
            }
            McpToolCategory.ANALYSIS -> {
                // For analysis tools, add file context
                val analyzedFiles = previousSteps.filter { it.type == StepType.TOOL_EXECUTION }
                    .mapNotNull { it.parameters["file"] }
                    .distinct()
                if (analyzedFiles.isNotEmpty()) {
                    enhanced["analyzed_files"] = analyzedFiles
                }
            }
            McpToolCategory.EXECUTION -> {
                // For build tools, add previous build context
                val previousBuilds = previousSteps.filter { it.type == StepType.TOOL_EXECUTION && it.tool?.category == McpToolCategory.EXECUTION }
                    .mapNotNull { it.parameters["build_result"] }
                    .takeLast(2)
                if (previousBuilds.isNotEmpty()) {
                    enhanced["build_history"] = previousBuilds
                }
            }
            McpToolCategory.CONTEXT -> {
                // For context tools, add project information
                enhanced["project_info"] = mapOf(
                    "available_tools" to mcpIntegration.getAvailableTools(),
                    "current_project" to (mcpIntegration.getCurrentProject() ?: "none")
                )
            }
            McpToolCategory.VERSION_CONTROL -> {
                // For version control, add previous operations context
                val previousVcs = previousSteps.filter { it.type == StepType.TOOL_EXECUTION && it.tool?.category == McpToolCategory.VERSION_CONTROL }
                    .mapNotNull { it.parameters["vcs_result"] }
                    .takeLast(2)
                if (previousVcs.isNotEmpty()) {
                    enhanced["vcs_history"] = previousVcs
                }
            }
            McpToolCategory.NAVIGATION -> {
                // For navigation tools, add file browsing context
                val previousNavigation = previousSteps.filter { it.type == StepType.TOOL_EXECUTION && it.tool?.category == McpToolCategory.NAVIGATION }
                    .mapNotNull { it.parameters["navigation_result"] }
                    .takeLast(2)
                if (previousNavigation.isNotEmpty()) {
                    enhanced["navigation_history"] = previousNavigation
                }
            }
            else -> {
                // Default enhancement for unknown categories
                enhanced["default_enhancement"] = true
            }
        }
        
        return enhanced
    }
    
    /**
     * Create context summary for synthesis
     */
    private fun createContextSummary(tools: List<SelectedMcpTool>, context: Map<String, Map<String, Any>>): String {
        val toolSummary = tools.joinToString("\n") { tool ->
            "- ${tool.tool.name} (score: ${tool.relevanceScore}, params: ${context[tool.tool.name]?.keys ?: emptyList()})"
        }
        return "Execution Context:\n$toolSummary"
    }
    
    /**
     * Create group summary for parallel synthesis
     */
    private fun createGroupSummary(group: List<SelectedMcpTool>): String {
        return "Parallel Group: ${group.map { it.tool.name }.joinToString(", ")}"
    }
    
    /**
     * Calculate synthesis duration based on tool complexity and count
     */
    private fun calculateSynthesisDuration(tools: List<SelectedMcpTool>, complexity: TaskComplexity): Long {
        val baseDuration = when (complexity) {
            TaskComplexity.SIMPLE -> 1500L
            TaskComplexity.MODERATE -> 2500L
            TaskComplexity.COMPLEX -> 4000L
        }
        return baseDuration + (tools.size * 500L) // Additional time for synthesis
    }
    
    /**
     * Calculate group synthesis duration
     */
    private fun calculateGroupSynthesisDuration(group: List<SelectedMcpTool>, complexity: TaskComplexity): Long {
        return when (complexity) {
            TaskComplexity.SIMPLE -> 2000L
            TaskComplexity.MODERATE -> 3500L
            TaskComplexity.COMPLEX -> 5000L
        }
    }
    
    /**
     * Extract parameters for a tool (placeholder implementation)
     */
    private fun extractParameters(tool: ProjectMcpTool): Map<String, Any> {
        // In a real implementation, this would extract parameters from the prompt
        // For now, return default values
        return tool.parameters.mapValues { it.value.defaultValue ?: "" }
    }
    
    /**
     * Estimate tool execution duration in milliseconds
     */
    private fun estimateToolDuration(tool: ProjectMcpTool): Long {
        return when (tool.category) {
            McpToolCategory.CONTEXT -> 2000L
            McpToolCategory.ANALYSIS -> 5000L
            McpToolCategory.SEARCH -> 3000L
            McpToolCategory.EXECUTION -> 4000L
            McpToolCategory.VERSION_CONTROL -> 2000L
            McpToolCategory.NAVIGATION -> 1000L
            else -> 3000L // Default for unknown categories
        }
    }
    
    /**
     * Estimate tool execution duration with parameter consideration
     */
    private fun estimateToolDuration(tool: ProjectMcpTool, parameters: Map<String, Any> = emptyMap()): Long {
        val baseDuration = when (tool.category) {
            McpToolCategory.CONTEXT -> 2000L
            McpToolCategory.ANALYSIS -> 5000L
            McpToolCategory.SEARCH -> 3000L
            McpToolCategory.FILE_ACCESS -> 1500L
            McpToolCategory.EXECUTION -> 4000L
            McpToolCategory.VERSION_CONTROL -> 2000L
            McpToolCategory.NAVIGATION -> 1000L
            McpToolCategory.CODE_GENERATION -> 6000L
            McpToolCategory.DATABASE_INTEGRATION -> 4000L
            McpToolCategory.API_INTERACTION -> 3000L
            McpToolCategory.ADVANCED_ANALYSIS -> 8000L
        }
        
        // Adjust duration based on parameter complexity
        val paramComplexity = parameters.size * 200L
        return baseDuration + paramComplexity
    }
    
    /**
     * Get current tool context
     */
    private fun getCurrentToolContext(): Map<String, Any> {
        return mapOf(
            "available_tools" to mcpIntegration.getAvailableTools(),
            "current_project" to (mcpIntegration.getCurrentProject() ?: "none"),
            "timestamp" to Instant.now()
        )
    }
    
    /**
     * Record execution decision for future context
     */
    private fun recordExecutionDecision(
        prompt: String,
        selectedTools: List<SelectedMcpTool>,
        executionStrategy: ExecutionStrategy,
        complexity: TaskComplexity
    ) {
        val decision = ExecutionDecision(
            prompt = prompt,
            selectedTools = selectedTools.map { it.tool.name },
            executionStrategy = executionStrategy,
            complexity = complexity,
            timestamp = Instant.now()
        )
        
        executionHistory.add(decision)
        
        // Keep history size manageable
        if (executionHistory.size > maxHistorySize) {
            executionHistory.removeAt(0)
        }
    }
    
    /**
     * Get execution history for context
     */
    fun getExecutionHistory(): List<ExecutionDecision> = executionHistory.toList()
}

/**
 * Comprehensive execution plan for tool orchestration
 */
data class ExecutionPlan(
    val prompt: String,
    val selectedServers: List<McpToolCategory>,
    val relevantTools: List<ProjectMcpTool>,
    val toolScores: Map<String, Double>,
    val steps: List<ExecutionStep>,
    val executionStrategy: ExecutionStrategy,
    val complexity: TaskComplexity,
    val estimatedDurationMs: Long,
    val canRunInParallel: Boolean,
    val context: ExecutionContext
)

/**
 * Individual execution step
 */
data class ExecutionStep(
    val type: StepType,
    val tool: ProjectMcpTool?,
    val parameters: Map<String, Any>,
    val dependencies: List<Int>,
    val estimatedDurationMs: Long
)

/**
 * Types of execution steps
 */
enum class StepType {
    DIRECT_RESPONSE,
    TOOL_EXECUTION,
    SYNTHESIS
}

/**
 * Execution context for decision making
 */
data class ExecutionContext(
    val executionHistory: List<ExecutionDecision>,
    val toolContext: Map<String, Any>,
    val promptContext: Map<String, Any>
)

/**
 * Record of past execution decisions
 */
data class ExecutionDecision(
    val prompt: String,
    val selectedTools: List<String>,
    val executionStrategy: ExecutionStrategy,
    val complexity: TaskComplexity,
    val timestamp: Instant
)
