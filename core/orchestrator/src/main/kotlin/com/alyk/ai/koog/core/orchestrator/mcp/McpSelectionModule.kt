package com.alyk.ai.koog.core.orchestrator.mcp

/**
 * MCP Selection Module: Analyzes prompts and selects relevant MCP tools
 * Implements keyword-based relevance scoring and threshold filtering
 */
class McpSelectionModule(
    private val mcpIntegration: McpIntegration,
    private val relevanceThreshold: Double = 0.2
) {
    
    // Keyword mappings for tool categories
    private val keywordMappings = mapOf(
        McpToolCategory.CONTEXT to listOf(
            "project", "context", "structure", "overview", "summary", "files", "directory"
        ),
        McpToolCategory.ANALYSIS to listOf(
            "analyze", "analysis", "quality", "complexity", "security", "review", "check"
        ),
        McpToolCategory.SEARCH to listOf(
            "search", "find", "locate", "look for", "where", "pattern", "regex"
        ),
        McpToolCategory.EXECUTION to listOf(
            "run", "execute", "build", "test", "compile", "start", "launch"
        ),
        McpToolCategory.VERSION_CONTROL to listOf(
            "git", "commit", "status", "diff", "log", "blame", "version", "history"
        ),
        McpToolCategory.NAVIGATION to listOf(
            "navigate", "go to", "open", "show", "display", "list", "browse"
        )
    )
    
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
        )
    )
    
    /**
     * Analyze prompt and select relevant MCP tools with relevance scores
     */
    suspend fun selectRelevantTools(prompt: String): List<SelectedMcpTool> {
        println("[SELECTION] Analyzing prompt: '$prompt'")
        val availableTools = getAvailableTools()
        val toolsList = mutableListOf<ProjectMcpTool>()
        availableTools.forEach { tool: ProjectMcpTool ->
            toolsList.add(tool)
            println("[SELECTION] Available tool: ${tool.name} (${tool.category})")
        }
        println("[SELECTION] Available tools count: ${toolsList.size}")
        
        val promptLower = prompt.lowercase()
        val words = promptLower.split(Regex("\\s+"))
        println("[SELECTION] Prompt words: $words")
        
        val selectedTools = toolsList.map { tool ->
            val score = calculateRelevanceScore(tool, promptLower, words)
            println("[SELECTION] Tool ${tool.name} score: $score")
            SelectedMcpTool(
                tool = tool,
                relevanceScore = score,
                matchedKeywords = getMatchedKeywords(tool, promptLower)
            )
        }
        .filter { it.relevanceScore >= relevanceThreshold }
        .sortedByDescending { it.relevanceScore }
        
        println("[SELECTION] Selected ${selectedTools.size} tools above threshold $relevanceThreshold")
        selectedTools.forEach { tool ->
            println("[SELECTION] Final selection: ${tool.tool.name} (score: ${tool.relevanceScore})")
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
        score += (categoryMatches.toDouble() / categoryKeywords.size) * 0.3
        
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
     * Extract parameters from prompt for selected tools
     */
    fun extractParameters(prompt: String, selectedTools: List<SelectedMcpTool>): Map<String, Map<String, Any>> {
        val parameters = mutableMapOf<String, Map<String, Any>>()
        val promptLower = prompt.lowercase()
        
        selectedTools.forEach { selectedTool ->
            val toolParams = mutableMapOf<String, Any>()
            val tool = selectedTool.tool
            
            when (tool.name) {
                "project_context" -> {
                    // Extract project path if mentioned
                    val pathPattern = """(?:path|directory|folder)[\s:]+([^\s,]+)""".toRegex(RegexOption.IGNORE_CASE)
                    val pathMatch = pathPattern.find(promptLower)
                    toolParams["path"] = pathMatch?.groupValues?.get(1)?.trim() ?: "."
                }
                
                "file_analyzer" -> {
                    // Extract file path for analysis
                    val filePattern = """(?:analyze|check)[\s]+(?:file\s+)?([^\s,]+)""".toRegex(RegexOption.IGNORE_CASE)
                    val fileMatch = filePattern.find(promptLower)
                    toolParams["file"] = fileMatch?.groupValues?.get(1)?.trim() ?: "src/main/kotlin"
                }
                
                "code_search" -> {
                    // Extract search pattern and directory
                    val searchPattern = """(?:search|find)[\s]+(?:for\s+)?([^\s]+)(?:\s+in\s+([^\s]+))?""".toRegex(RegexOption.IGNORE_CASE)
                    val searchMatch = searchPattern.find(promptLower)
                    toolParams["pattern"] = searchMatch?.groupValues?.get(1)?.trim() ?: ""
                    toolParams["directory"] = searchMatch?.groupValues?.get(2)?.trim() ?: "src/main/kotlin"
                }
                
                "build_runner" -> {
                    // Extract build command or task
                    val buildPattern = """(?:build|run|compile|test)[\s]+([^\s]+)?""".toRegex(RegexOption.IGNORE_CASE)
                    val buildMatch = buildPattern.find(promptLower)
                    toolParams["task"] = buildMatch?.groupValues?.get(1)?.trim() ?: "build"
                }
                
                "git_operations" -> {
                    // Extract git operation and optional file/branch
                    val gitPattern = """(?:git)[\s]+([^\s]+)(?:\s+([^\s]+))?""".toRegex(RegexOption.IGNORE_CASE)
                    val gitMatch = gitPattern.find(promptLower)
                    toolParams["operation"] = gitMatch?.groupValues?.get(1)?.trim() ?: "status"
                    toolParams["target"] = gitMatch?.groupValues?.get(2)?.trim() ?: ""
                }
                
                "test_executor" -> {
                    // Extract test type and target
                    val testPattern = """(?:test)[\s]+([^\s]+)(?:\s+for\s+([^\s]+))?""".toRegex(RegexOption.IGNORE_CASE)
                    val testMatch = testPattern.find(promptLower)
                    toolParams["type"] = testMatch?.groupValues?.get(1)?.trim() ?: "unit"
                    toolParams["target"] = testMatch?.groupValues?.get(2)?.trim() ?: "src/test/kotlin"
                }
            }
            
            parameters[tool.name] = toolParams
        }
        
        return parameters
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
     * Get execution strategy based on selected tools
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
     * Estimate task complexity based on prompt and selected tools
     */
    fun estimateComplexity(prompt: String, selectedTools: List<SelectedMcpTool>): TaskComplexity {
        val promptLength = prompt.length
        val toolCount = selectedTools.size
        val hasExecutionTools = selectedTools.any { 
            it.tool.category == McpToolCategory.EXECUTION 
        }
        
        return when {
            promptLength < 50 && toolCount <= 1 && !hasExecutionTools -> TaskComplexity.SIMPLE
            promptLength < 200 && toolCount <= 3 -> TaskComplexity.MODERATE
            else -> TaskComplexity.COMPLEX
        }
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
