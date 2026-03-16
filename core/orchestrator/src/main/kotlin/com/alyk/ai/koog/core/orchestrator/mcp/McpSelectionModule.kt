package com.alyk.ai.koog.core.orchestrator.mcp

import com.alyk.ai.koog.context.provider.ContextProvider
import com.alyk.ai.koog.core.orchestrator.mcp.learning.AdaptiveLearningSystem
import com.alyk.ai.koog.core.orchestrator.mcp.learning.FeedbackEntry
import com.alyk.ai.koog.core.orchestrator.mcp.ml.ToolSelectionML
import com.alyk.ai.koog.core.orchestrator.mcp.nlp.AdvancedParameterExtractor

/**
 * Advanced MCP Selection Module with ML and NLP capabilities
 * Implements intelligent tool selection with learning and adaptation
 */
class McpSelectionModule(
    private val mcpIntegration: McpIntegration,
    private val contextProvider: ContextProvider,
    private val relevanceThreshold: Double = 0.2,
    private val enableML: Boolean = true
) {
    
    // Advanced components
    private val mlModel = ToolSelectionML()
    private val parameterExtractor = AdvancedParameterExtractor()
    private val learningSystem = AdaptiveLearningSystem()
    
    // Enhanced tool categories with advanced capabilities
    private val advancedToolCategories = mapOf(
        McpToolCategory.CODE_GENERATION to listOf(
            "generate_code", "create_class", "create_function", "create_test", "create_api"
        ),
        McpToolCategory.DATABASE_INTEGRATION to listOf(
            "query_database", "migrate_database", "backup_database", "restore_database"
        ),
        McpToolCategory.API_INTERACTION to listOf(
            "call_api", "test_api", "document_api", "mock_api"
        ),
        McpToolCategory.ADVANCED_ANALYSIS to listOf(
            "security_scan", "performance_analysis", "code_review", "dependency_analysis"
        )
    )
    
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
        ),
        McpToolCategory.FILE_ACCESS to listOf(
            "read", "write", "edit", "modify", "change", "file", "code", "add", "update", "create"
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
        ),
        "read_file" to mapOf(
            "read" to 1.0, "file" to 1.0, "open" to 0.8, "view" to 0.8, "content" to 0.7
        ),
        "write_file" to mapOf(
            "write" to 1.0, "file" to 1.0, "create" to 0.9, "save" to 0.8, "content" to 0.7
        ),
        "edit_file" to mapOf(
            "edit" to 1.0, "modify" to 1.0, "change" to 0.9, "update" to 0.8, "file" to 1.0
        )
    )
    
    /**
     * Advanced tool selection with ML and NLP
     */
    suspend fun selectRelevantTools(prompt: String): List<SelectedMcpTool> {
        println("[SELECTION] Analyzing prompt: '$prompt'")
        val toolsList = getAvailableTools()
        println("[SELECTION] Available tools count: ${toolsList.size}")
        
        val promptLower = prompt.lowercase()
        val words = promptLower.split(Regex("\\s+"))
        println("[SELECTION] Prompt words: $words")
        
        // Generate dynamic keywords from project context
        val projectKeywords = generateProjectKeywords(prompt)
        println("[SELECTION] Generated project keywords: ${projectKeywords.keys.joinToString(", ")}")
        
        // Calculate dynamic threshold based on learning
        val dynamicThreshold = if (enableML) {
            mlModel.calculateDynamicThreshold(prompt, toolsList.map { tool -> tool.name })
        } else {
            relevanceThreshold
        }
        println("[SELECTION] Using dynamic threshold: $dynamicThreshold")
        
        val selectedTools = toolsList.map { tool ->
            val score = if (enableML) {
                // ML-based scoring
                val features = mlModel.extractFeatures(prompt, projectKeywords, tool.name)
                val mlScore = mlModel.predictScore(features, tool.name)
                val performanceScore = learningSystem.getPerformanceScore(tool.name)
                
                // Combine ML score with performance score
                (mlScore * 0.7) + (performanceScore * 0.3)
            } else {
                // Fallback to traditional scoring
                calculateProjectAwareRelevanceScore(tool, promptLower, words, projectKeywords)
            }
            
            println("[SELECTION] Tool ${tool.name} score: $score")
            SelectedMcpTool(
                tool = tool,
                relevanceScore = score,
                matchedKeywords = getMatchedKeywords(tool, promptLower, projectKeywords)
            )
        }
        .filter { it.relevanceScore >= dynamicThreshold }
        .sortedByDescending { it.relevanceScore }
        
        println("[SELECTION] Selected ${selectedTools.size} tools above dynamic threshold $dynamicThreshold")
        selectedTools.forEach { tool ->
            println("[SELECTION] Final selection: ${tool.tool.name} (score: ${tool.relevanceScore})")
        }
        
        return selectedTools
    }
    
    /**
     * Generate dynamic keywords by analyzing actual file content
     */
    private suspend fun generateProjectKeywords(prompt: String): Map<String, Double> {
        val keywords = mutableMapOf<String, Double>()
        
        if (!contextProvider.isProjectLoaded()) {
            println("[SELECTION] No project loaded, using static keywords only")
            return keywords
        }
        
        val projectFiles = contextProvider.getLoadedFiles()
        println("[SELECTION] Analyzing content of ${projectFiles.size} files for dynamic keywords")
        
        // Analyze actual file content, not just filenames
        projectFiles.forEach { filePath ->
            try {
                val content = analyzeFileContent(filePath)
                content.forEach { (keyword, weight) ->
                    keywords[keyword] = maxOf(keywords.getOrDefault(keyword, 0.0), weight)
                }
            } catch (e: Exception) {
                println("[SELECTION] Could not analyze file $filePath: ${e.message}")
            }
        }
        
        println("[SELECTION] Generated ${keywords.size} dynamic keywords from content analysis")
        return keywords
    }
    
    /**
     * Analyze actual file content to extract relevant keywords and patterns
     */
    private suspend fun analyzeFileContent(filePath: String): Map<String, Double> {
        val keywords = mutableMapOf<String, Double>()
        
        // Read file content
        val content = try {
            java.io.File(filePath).readText()
        } catch (e: Exception) {
            println("[SELECTION] Failed to read file $filePath: ${e.message}")
            return keywords
        }
        
        val lines = content.lines()
        
        // Analyze for programming patterns with semantic understanding
        lines.forEach { line ->
            val trimmedLine = line.trim()
            
            // Kotlin/Java coroutine patterns - semantic detection
            detectCoroutinePatterns(trimmedLine, keywords)
            
            // Function/method patterns - semantic detection
            detectFunctionPatterns(trimmedLine, keywords)
            
            // Class patterns - semantic detection
            detectClassPatterns(trimmedLine, keywords)
            
            // Test patterns - semantic detection
            detectTestPatterns(trimmedLine, keywords)
            
            // Build patterns - semantic detection
            detectBuildPatterns(trimmedLine, keywords)
            
            // Import patterns - detect libraries/frameworks
            detectImportPatterns(trimmedLine, keywords)
        }
        
        // Language detection from file extension
        detectLanguage(filePath, keywords)
        
        return keywords
    }
    
    private fun detectCoroutinePatterns(line: String, keywords: MutableMap<String, Double>) {
        when {
            // Suspend functions
            line.matches(Regex(".*\\bsuspend\\s+fun\\s+\\w+.*")) -> {
                keywords["suspend"] = 1.0
                keywords["coroutine"] = 0.9
                keywords["async"] = 0.8
            }
            // Async calls
            line.contains("async") && (line.contains("{") || line.contains("launch")) -> {
                keywords["async"] = 1.0
                keywords["await"] = 0.9
                keywords["coroutine"] = 0.8
            }
            // Await calls
            line.matches(Regex(".*\\.await\\(.*")) || line.contains("await") -> {
                keywords["await"] = 1.0
                keywords["async"] = 0.9
                keywords["coroutine"] = 0.8
            }
            // Launch calls
            line.matches(Regex(".*\\blaunch\\s*\\{.*")) || line.contains("launch") -> {
                keywords["launch"] = 1.0
                keywords["coroutine"] = 0.9
                keywords["start"] = 0.7
            }
            // Run blocking
            line.contains("runBlocking") -> {
                keywords["runblocking"] = 1.0
                keywords["coroutine"] = 0.9
            }
            // Coroutine scope
            line.contains("CoroutineScope") || line.contains("GlobalScope") -> {
                keywords["coroutine"] = 0.8
                keywords["scope"] = 0.7
            }
        }
    }
    
    private fun detectFunctionPatterns(line: String, keywords: MutableMap<String, Double>) {
        when {
            // Function definitions across languages
            line.matches(Regex(".*\\b(fun|function|def|func)\\s+\\w+\\s*\\(.*")) -> {
                keywords["function"] = 0.8
                keywords["method"] = 0.7
            }
            // Method calls
            line.matches(Regex(".*\\w+\\s*\\(.*\\).*")) && !line.contains("if") && !line.contains("when") -> {
                keywords["call"] = 0.5
                keywords["invoke"] = 0.4
            }
        }
    }
    
    private fun detectClassPatterns(line: String, keywords: MutableMap<String, Double>) {
        when {
            // Class/interface definitions
            line.matches(Regex(".*\\b(class|interface)\\s+\\w+.*")) -> {
                keywords["class"] = 0.8
                keywords["interface"] = 0.7
                keywords["type"] = 0.6
            }
            // Object declarations
            line.matches(Regex(".*\\bobject\\s+\\w+.*")) -> {
                keywords["object"] = 0.8
                keywords["singleton"] = 0.6
            }
        }
    }
    
    private fun detectTestPatterns(line: String, keywords: MutableMap<String, Double>) {
        when {
            // Test annotations
            line.contains("@Test") || line.contains("@Testify") || line.contains("@TestCase") -> {
                keywords["test"] = 1.0
                keywords["testing"] = 0.9
                keywords["unit"] = 0.7
            }
            // Test functions
            line.matches(Regex(".*\\b(test|should|when)\\s+\\w+.*")) -> {
                keywords["test"] = 0.8
                keywords["testing"] = 0.7
            }
            // Assertion calls
            line.contains("assert") || line.contains("assertEquals") || line.contains("shouldBe") -> {
                keywords["assert"] = 0.8
                keywords["verify"] = 0.7
            }
        }
    }
    
    private fun detectBuildPatterns(line: String, keywords: MutableMap<String, Double>) {
        when {
            // Gradle build files
            line.contains("build.gradle") || line.contains("settings.gradle") -> {
                keywords["build"] = 1.0
                keywords["gradle"] = 0.9
            }
            // Dependencies
            line.contains("implementation") || line.contains("api") || line.contains("compile") -> {
                keywords["dependency"] = 0.9
                keywords["build"] = 0.7
            }
            // Plugins
            line.contains("plugins") || line.contains("apply plugin") -> {
                keywords["plugin"] = 0.8
                keywords["build"] = 0.6
            }
        }
    }
    
    private fun detectImportPatterns(line: String, keywords: MutableMap<String, Double>) {
        when {
            // Kotlin/Java imports
            line.matches(Regex("import\\s+.*")) -> {
                when {
                    line.contains("kotlinx.coroutines") -> {
                        keywords["coroutine"] = 0.9
                        keywords["kotlinx"] = 0.7
                    }
                    line.contains("junit") -> {
                        keywords["test"] = 0.8
                        keywords["junit"] = 0.7
                    }
                    line.contains("spring") -> {
                        keywords["spring"] = 0.9
                        keywords["framework"] = 0.7
                    }
                    line.contains("react") -> {
                        keywords["react"] = 0.9
                        keywords["frontend"] = 0.7
                    }
                }
            }
        }
    }
    
    private fun detectLanguage(filePath: String, keywords: MutableMap<String, Double>) {
        when {
            filePath.endsWith(".kt") || filePath.endsWith(".kts") -> {
                keywords["kotlin"] = 1.0
            }
            filePath.endsWith(".java") -> {
                keywords["java"] = 1.0
            }
            filePath.endsWith(".js") || filePath.endsWith(".ts") -> {
                keywords["javascript"] = 0.9
                keywords["typescript"] = 0.9
            }
            filePath.endsWith(".py") -> {
                keywords["python"] = 1.0
            }
            filePath.endsWith(".go") -> {
                keywords["golang"] = 1.0
            }
            filePath.endsWith(".rs") -> {
                keywords["rust"] = 1.0
            }
        }
    }
    
    /**
     * Calculate project-aware relevance score for a tool based on prompt analysis and project context
     */
    private suspend fun calculateProjectAwareRelevanceScore(
        tool: ProjectMcpTool, 
        promptLower: String, 
        words: List<String>,
        projectKeywords: Map<String, Double>
    ): Double {
        var score = 0.0
        
        // Category-level keyword matching (weight: 0.2)
        val categoryKeywords = keywordMappings[tool.category] ?: emptyList()
        val categoryMatches = categoryKeywords.count { keyword -> 
            promptLower.contains(keyword) 
        }
        score += (categoryMatches.toDouble() / categoryKeywords.size) * 0.2
        
        // Tool-specific keyword matching (weight: 0.3)
        val toolKeywordMap = toolKeywords[tool.name] ?: emptyMap()
        if (toolKeywordMap.isNotEmpty()) {
            val totalWeight = toolKeywordMap.values.sum()
            val matchedWeight = toolKeywordMap.entries.sumOf { (keyword, weight) ->
                if (promptLower.contains(keyword)) weight else 0.0
            }
            score += (matchedWeight / totalWeight) * 0.3
        }
        
        // Project context keyword matching (weight: 0.4) - NEW
        if (projectKeywords.isNotEmpty()) {
            val totalProjectWeight = projectKeywords.values.sum()
            val matchedProjectWeight = projectKeywords.entries.sumOf { (keyword, weight) ->
                if (promptLower.contains(keyword)) weight else 0.0
            }
            score += (matchedProjectWeight / totalProjectWeight) * 0.4
        }
        
        // Name matching (weight: 0.1)
        val nameWords = tool.name.split("_")
        val nameMatches = nameWords.count { nameWord ->
            words.any { it.contains(nameWord) }
        }
        score += (nameMatches.toDouble() / nameWords.size) * 0.1
        
        return score.coerceAtMost(1.0)
    }
    
    /**
     * Get keywords that matched for a tool (updated for project context)
     */
    private fun getMatchedKeywords(tool: ProjectMcpTool, promptLower: String, projectKeywords: Map<String, Double>): List<String> {
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
        
        // Project context keywords
        projectKeywords.forEach { (keyword, weight) ->
            if (promptLower.contains(keyword)) {
                matchedKeywords.add("$keyword(proj:$weight)")
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
     * Advanced parameter extraction with NLP
     */
    fun extractParameters(prompt: String, selectedTools: List<SelectedMcpTool>): Map<String, Map<String, Any>> {
        val parameters = mutableMapOf<String, Map<String, Any>>()
        val projectContext = getProjectContext()
        
        selectedTools.forEach { selectedTool ->
            val toolParams = mutableMapOf<String, Any>()
            val tool = selectedTool.tool
            
            // Use advanced NLP parameter extraction
            val extractedParams = parameterExtractor.extractParameters(prompt, tool.name, projectContext)
            
            extractedParams.forEach { param ->
                toolParams[param.name] = when (param.type) {
                    com.alyk.ai.koog.core.orchestrator.mcp.nlp.ParameterType.NUMBER -> {
                        param.value.toDoubleOrNull() ?: param.value
                    }
                    com.alyk.ai.koog.core.orchestrator.mcp.nlp.ParameterType.BOOLEAN -> {
                        param.value.toBooleanStrictOrNull() ?: param.value
                    }
                    else -> param.value
                }
            }
            
            // Fallback to basic extraction for missing parameters
            if (toolParams.isEmpty()) {
                val fallbackParams = extractBasicParameters(prompt, tool)
                toolParams.putAll(fallbackParams)
            }
            
            parameters[tool.name] = toolParams
        }
        
        return parameters
    }
    
    /**
     * Record tool execution for learning
     */
    fun recordExecution(toolName: String, prompt: String, wasSuccessful: Boolean, executionTime: Long) {
        learningSystem.recordUsage(toolName, wasSuccessful, executionTime)
        
        // Train ML model
        if (enableML) {
            val features = mlModel.extractFeatures(prompt, emptyMap(), toolName)
            val trainingExample = com.alyk.ai.koog.core.orchestrator.mcp.ml.TrainingExample(
                prompt = prompt,
                selectedTool = toolName,
                wasSuccessful = wasSuccessful,
                executionTime = executionTime,
                features = features
            )
            mlModel.trainModel(trainingExample)
        }
        
        println("[SELECTION] Recorded execution: $toolName, success=$wasSuccessful, time=${executionTime}ms")
    }
    
    /**
     * Record user feedback for learning
     */
    fun recordFeedback(toolName: String, prompt: String, rating: Int, comments: String? = null) {
        val feedback = FeedbackEntry(
            toolName = toolName,
            prompt = prompt,
            userRating = rating,
            wasSuccessful = rating >= 3, // Assume 3+ is successful
            executionTime = 0, // Not available at feedback time
            timestamp = System.currentTimeMillis(),
            userComments = comments
        )
        
        learningSystem.recordFeedback(feedback)
        println("[SELECTION] Recorded feedback: $toolName, rating=$rating")
    }
    
    /**
     * Get learning analytics
     */
    fun getAnalytics(): Map<String, Any> {
        return learningSystem.getAnalytics()
    }
    
    /**
     * Get recommendations for tool improvement
     */
    fun getRecommendations(toolName: String): List<String> {
        return learningSystem.getRecommendations(toolName)
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
    
    private fun getProjectContext(): Map<String, String> {
        val context = mutableMapOf<String, String>()
        
        if (contextProvider.isProjectLoaded()) {
            context["projectRoot"] = contextProvider.getProjectRoot() ?: ""
            context["loadedFiles"] = contextProvider.getLoadedFiles().joinToString(",")
        }
        
        return context
    }
    
    private fun extractBasicParameters(prompt: String, tool: ProjectMcpTool): Map<String, Any> {
        val params = mutableMapOf<String, Any>()
        val promptLower = prompt.lowercase()
        
        when (tool.name) {
            "project_context" -> {
                val pathPattern = """(?:path|directory|folder)[\s:]+([^\s,]+)""".toRegex(RegexOption.IGNORE_CASE)
                val pathMatch = pathPattern.find(promptLower)
                params["path"] = pathMatch?.groupValues?.get(1)?.trim() ?: "."
            }
            "file_analyzer" -> {
                val filePattern = """(?:analyze|check)[\s]+(?:file\s+)?([^\s,]+)""".toRegex(RegexOption.IGNORE_CASE)
                val fileMatch = filePattern.find(promptLower)
                params["file"] = fileMatch?.groupValues?.get(1)?.trim() ?: "src/main/kotlin"
            }
            "code_search" -> {
                val searchPattern = """(?:search|find)[\s]+(?:for\s+)?([^\s]+)(?:\s+in\s+([^\s]+))?""".toRegex(RegexOption.IGNORE_CASE)
                val searchMatch = searchPattern.find(promptLower)
                params["pattern"] = searchMatch?.groupValues?.get(1)?.trim() ?: ""
                params["directory"] = searchMatch?.groupValues?.get(2)?.trim() ?: "src/main/kotlin"
            }
            "build_runner" -> {
                val buildPattern = """(?:build|run|compile|test)[\s]+([^\s]+)?""".toRegex(RegexOption.IGNORE_CASE)
                val buildMatch = buildPattern.find(promptLower)
                params["task"] = buildMatch?.groupValues?.get(1)?.trim() ?: "build"
            }
            "git_operations" -> {
                val gitPattern = """(?:git)[\s]+([^\s]+)(?:\s+([^\s]+))?""".toRegex(RegexOption.IGNORE_CASE)
                val gitMatch = gitPattern.find(promptLower)
                params["operation"] = gitMatch?.groupValues?.get(1)?.trim() ?: "status"
                params["target"] = gitMatch?.groupValues?.get(2)?.trim() ?: ""
            }
            "test_executor" -> {
                val testPattern = """(?:test)[\s]+([^\s]+)(?:\s+for\s+([^\s]+))?""".toRegex(RegexOption.IGNORE_CASE)
                val testMatch = testPattern.find(promptLower)
                params["type"] = testMatch?.groupValues?.get(1)?.trim() ?: "unit"
                params["target"] = testMatch?.groupValues?.get(2)?.trim() ?: "src/test/kotlin"
            }
        }
        
        return params
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
