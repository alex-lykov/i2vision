package com.alyk.ai.koog.core.orchestrator.mcp

/**
 * Enhanced MCP Selection Module with ML-based intelligence
 * Replaces hardcoded keyword matching with adaptive learning system
 */
class McpSelectionModule(
    private val mcpIntegration: McpIntegration,
    private val parameterExtractor: IntelligentParameterExtractor,
    private val thresholdManager: DynamicThresholdManager,
    private val mlSelector: MLToolSelector,
    private val debugger: ExecutionPlanDebugger,
    private val analyticsSystem: PerformanceAnalyticsSystem
) {
    
    // Fallback basic keyword mappings for edge cases
    private val basicKeywordMappings = mapOf(
        McpToolCategory.CONTEXT to listOf("project", "context", "structure", "overview", "summary", "files", "directory"),
        McpToolCategory.ANALYSIS to listOf("analyze", "analysis", "quality", "complexity", "security", "review", "check"),
        McpToolCategory.SEARCH to listOf("search", "find", "locate", "look for", "where", "pattern", "regex"),
        McpToolCategory.EXECUTION to listOf("run", "execute", "build", "test", "compile", "start", "launch"),
        McpToolCategory.VERSION_CONTROL to listOf("git", "commit", "status", "diff", "log", "blame", "version", "history"),
        McpToolCategory.NAVIGATION to listOf("navigate", "go to", "open", "show", "display", "list", "browse"),
        McpToolCategory.FILE_ACCESS to listOf("read", "write", "edit", "modify", "change", "file", "code", "add", "update", "create")
    )

    // Backwards-compatible alias for older scoring code paths.
    private val keywordMappings = basicKeywordMappings
    
    // Weighted tool-specific keywords for better matching
    private val toolKeywords = mapOf(
        "project_context" to mapOf(
            "project" to 1.0, "context" to 1.0, "overview" to 0.8, "summary" to 0.8, 
            "structure" to 0.9, "files" to 1.0, "list" to 1.0, "directory" to 0.9
        ),
        "file_analyzer" to mapOf(
            "analyze" to 1.0, "quality" to 0.9, "complexity" to 0.9, "security" to 0.8, 
            "review" to 0.8, "check" to 0.7
        ),
        "code_search" to mapOf(
            "search" to 1.0, "find" to 1.0, "pattern" to 0.9, "function" to 0.8, 
            "variable" to 0.8, "look" to 0.7
        ),
        "dependency_scanner" to mapOf(
            "dependency" to 1.0, "library" to 0.9, "vulnerability" to 0.9, "security" to 0.8
        ),
        "build_runner" to mapOf(
            "build" to 1.0, "compile" to 0.9, "gradle" to 1.0, "maven" to 0.8, "make" to 0.7
        ),
        "test_executor" to mapOf(
            "test" to 1.0, "testing" to 0.9, "coverage" to 0.8, "spec" to 0.7
        ),
        "git_operations" to mapOf(
            "git" to 1.0, "commit" to 0.9, "status" to 0.8, "diff" to 0.8, 
            "log" to 0.7, "push" to 0.9, "pull" to 0.9
        ),
        "read_file" to mapOf(
            "read" to 1.0, "file" to 1.0, "open" to 0.8, "view" to 0.8, "content" to 0.7
        ),
        "write_file" to mapOf(
            "write" to 1.0, "file" to 1.0, "create" to 0.9, "save" to 0.8, "content" to 0.7
        ),
        "edit_file" to mapOf(
            "edit" to 1.0, "modify" to 1.0, "change" to 0.9, "update" to 0.8, "file" to 1.0,
            "remove" to 1.0, "delete" to 1.0, "coroutine" to 0.9, "function" to 0.8, 
            "method" to 0.8, "line" to 0.7, "block" to 0.7
        )
    )
    
    /**
     * Analyze prompt and select relevant MCP tools using ML intelligence
     */
    suspend fun selectRelevantTools(prompt: String): List<SelectedMcpTool> {
        println("[SELECTION] Analyzing prompt: '$prompt'")
        
        // Get available tools
        val availableTools = getAvailableTools()
        println("[SELECTION] Available tools count: ${availableTools.size}")
        
        // Create selection context
        val context = SelectionContext(
            complexity = estimateComplexity(prompt, emptyList()),
            projectPath = null, // Would be set from actual context
            sessionId = "current-session"
        )
        
        // Use ML-based selection
        val mlSelectedTools = mlSelector.selectTools(prompt, availableTools, context)
        
        // Convert to SelectedMcpTool format for compatibility
        val selectedTools = mlSelectedTools.map { mlTool ->
            SelectedMcpTool(
                tool = mlTool.tool,
                relevanceScore = mlTool.mlScore,
                matchedKeywords = extractMatchedKeywords(prompt, mlTool.tool)
            )
        }
        
        // Record for analytics
        selectedTools.forEach { selectedTool ->
            analyticsSystem.recordToolExecution(
                sessionId = context.sessionId,
                toolName = selectedTool.tool.name,
                category = selectedTool.tool.category,
                executionTime = 0, // Selection time, not execution
                success = true,
                parameters = emptyMap(),
                resultQuality = selectedTool.relevanceScore
            )
        }
        
        println("[SELECTION] ML selected ${selectedTools.size} tools")
        selectedTools.forEach { tool ->
            println("[SELECTION] Selected: ${tool.tool.name} (score: ${tool.relevanceScore})")
        }
        
        return selectedTools
    }
    
    /**
     * Calculate relevance score for a tool based on prompt analysis
     */
    private fun calculateRelevanceScore(
        tool: ProjectMcpTool, 
        promptLower: String, 
        words: List<String>
    ): Double {
        var score = 0.0
        
        // Category-level keyword matching (weight: 0.3)
        val categoryKeywords = keywordMappings[tool.category] ?: emptyList()
        val categoryMatches = categoryKeywords.count { keyword -> 
            promptLower.contains(keyword) 
        }
        score += (if (categoryKeywords.isNotEmpty()) categoryMatches.toDouble() / categoryKeywords.size else 0.0) * 0.3
        
        // Tool-specific keyword matching (weight: 0.5) - using weighted keywords
        val toolKeywordMap = toolKeywords[tool.name] ?: emptyMap()
        if (toolKeywordMap.isNotEmpty()) {
            val totalWeight = toolKeywordMap.values.sum()
            val matchedWeight = toolKeywordMap.entries.sumOf { (keyword, weight) ->
                if (promptLower.contains(keyword)) weight else 0.0
            }
            score += (matchedWeight / totalWeight) * 0.5
        }
        
        // Name matching (weight: 0.2)
        val nameWords = tool.name.split("_")
        val nameMatches = nameWords.count { nameWord ->
            words.any { it.contains(nameWord) }
        }
        score += (nameMatches.toDouble() / nameWords.size) * 0.2
        
        // Bonus for early keyword appearances
        val allKeywords = mutableListOf<String>()
        allKeywords.addAll(categoryKeywords)
        allKeywords.addAll(toolKeywordMap.keys)
        val firstKeywordIndex = findFirstKeywordIndex(promptLower, allKeywords)
        if (firstKeywordIndex != -1) {
            val positionBonus = 1.0 - (firstKeywordIndex.toDouble() / words.size)
            score += positionBonus * 0.1
        }
        
        return score.coerceAtMost(1.0)
    }
    
    /**
     * Get keywords that matched for a tool
     */
    private fun getMatchedKeywords(tool: ProjectMcpTool, promptLower: String): List<String> {
        val matchedKeywords = mutableListOf<String>()
        
        // Category keywords
        keywordMappings[tool.category]?.forEach { keyword ->
            if (promptLower.contains(keyword)) {
                matchedKeywords.add(keyword)
            }
        }
        
        // Tool-specific keywords (weighted)
        toolKeywords[tool.name]?.forEach { (keyword, weight) ->
            if (promptLower.contains(keyword)) {
                matchedKeywords.add("$keyword(${weight})")
            }
        }
        
        return matchedKeywords.distinct()
    }
    
    /**
     * Find the index of the first keyword appearance in the prompt
     */
    private fun findFirstKeywordIndex(promptLower: String, keywords: List<String>): Int {
        val words = promptLower.split(Regex("\\s+"))
        return words.indices.firstOrNull { index ->
            keywords.any { keyword -> words[index].contains(keyword) }
        } ?: -1
    }
    
    /**
     * Extract parameters using intelligent system
     */
    fun extractParameters(prompt: String, selectedTools: List<SelectedMcpTool>): Map<String, Map<String, Any>> {
        println("[SELECTION] Extracting parameters with intelligent system")
        return parameterExtractor.extractParameters(prompt, selectedTools)
    }
    
    /**
     * Get available tools from MCP integration
     */
    private suspend fun getAvailableTools(): List<ProjectMcpTool> {
        return mutableListOf<ProjectMcpTool>().apply {
            mcpIntegration.getAvailableTools().collect { tool ->
                add(tool)
            }
        }
    }
    
    /**
     * Get execution strategy using intelligent analysis
     */
    fun determineExecutionStrategy(selectedTools: List<SelectedMcpTool>): ExecutionStrategy {
        return when {
            selectedTools.isEmpty() -> ExecutionStrategy.NONE
            selectedTools.size == 1 -> ExecutionStrategy.SEQUENTIAL
            selectedTools.all { canRunInParallel(it.tool) } -> ExecutionStrategy.PARALLEL
            else -> ExecutionStrategy.SEQUENTIAL
        }
    }
    
    /**
     * Check if a tool can run in parallel with others
     */
    private fun canRunInParallel(tool: ProjectMcpTool): Boolean {
        return tool.category in listOf(
            McpToolCategory.ANALYSIS,
            McpToolCategory.SEARCH,
            McpToolCategory.NAVIGATION
        )
    }
    
    /**
     * Estimate task complexity using intelligent analysis
     */
    fun estimateComplexity(prompt: String, selectedTools: List<SelectedMcpTool>): TaskComplexity {
        // Use dynamic threshold recommendation for complexity assessment
        val recommendation = thresholdManager.getThresholdRecommendation(
            promptComplexity = TaskComplexity.MODERATE, // Will be recalculated
            toolCount = selectedTools.size,
            hasHighConfidenceTools = selectedTools.any { it.relevanceScore > 0.7 }
        )
        
        val promptLength = prompt.length
        val toolCount = selectedTools.size
        val hasExecutionTools = selectedTools.any { 
            it.tool.category == McpToolCategory.EXECUTION 
        }
        
        // Enhanced complexity calculation with ML insights
        return when {
            promptLength < 50 && toolCount <= 1 && !hasExecutionTools -> TaskComplexity.SIMPLE
            promptLength < 200 && toolCount <= 3 && recommendation.confidence > 0.7 -> TaskComplexity.MODERATE
            promptLength > 300 || toolCount > 5 || hasExecutionTools -> TaskComplexity.COMPLEX
            else -> TaskComplexity.MODERATE
        }
    }
    
    /**
     * Extract matched keywords for compatibility
     */
    private fun extractMatchedKeywords(prompt: String, tool: ProjectMcpTool): List<String> {
        val promptLower = prompt.lowercase()
        val keywords = mutableListOf<String>()
        
        // Check basic category keywords
        basicKeywordMappings[tool.category]?.forEach { keyword ->
            if (promptLower.contains(keyword)) {
                keywords.add(keyword)
            }
        }
        
        return keywords.distinct()
    }
}

/**
 * Represents a selected MCP tool with relevance information
 */
data class SelectedMcpTool(
    val tool: ProjectMcpTool,
    val relevanceScore: Double,
    val matchedKeywords: List<String>
)

/**
 * Execution strategy for selected tools
 */
enum class ExecutionStrategy {
    NONE,
    SEQUENTIAL,
    PARALLEL
}

/**
 * Task complexity estimation
 */
enum class TaskComplexity {
    SIMPLE,
    MODERATE,
    COMPLEX
}
