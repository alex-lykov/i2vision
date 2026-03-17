package com.alyk.ai.koog.core.orchestrator.agents

import ai.koog.agents.core.tools.ToolRegistry
import com.alyk.ai.koog.core.orchestrator.mcp.McpDecisionModule
import com.alyk.ai.koog.core.orchestrator.mcp.McpSelectionModule
import com.alyk.ai.koog.core.orchestrator.mcp.workspace.McpWorkspace
import com.alyk.ai.koog.core.orchestrator.router.AgentResponse
import com.alyk.ai.koog.core.orchestrator.router.AgentResponseChunk
import com.alyk.ai.koog.core.orchestrator.router.AgentType
import com.alyk.ai.koog.core.orchestrator.router.TaskContext
import com.alyk.ai.koog.core.orchestrator.tools.KoogToolRegistryBuilder
import com.alyk.ai.koog.core.orchestrator.tools.ToolUsageTracker
import com.alyk.ai.koog.core.session.DecisionLogEntry
import com.alyk.ai.koog.core.session.ISessionStore
import com.alyk.ai.koog.core.session.SessionManager
import com.alyk.ai.koog.models.wrappers.ModelWrapper
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.util.*

/**
 * Enhanced Implementation Agent with MCP Selection & Decision Modules
 * Uses intelligent tool selection and execution planning
 */
class EnhancedImplementationAgent(
    workspace: McpWorkspace,
    private val implementationModelWrapper: ModelWrapper,
    toolRegistry: ToolRegistry = ToolRegistry.EMPTY,
    private val toolUsageTracker: ToolUsageTracker? = null,
    private val selectionModule: McpSelectionModule,
    private val decisionModule: McpDecisionModule,
    sessionStore: ISessionStore? = null,
    private val sessionManager: SessionManager? = null
) : BaseAgent(AgentType.IMPLEMENTATION, workspace, toolRegistry, implementationModelWrapper, sessionStore) {
    
    private val json = Json { ignoreUnknownKeys = true }
    private var pendingFileDiff: AgentResponseChunk.FileDiff? = null
    
/**
 * Tool call arguments data classes
 */
@Serializable
data class ReadFileArgs(val path: String)

@Serializable
data class WriteFileArgs(val path: String, val content: String)

@Serializable
data class ListDirectoryArgs(val path: String)

@Serializable
data class RegexSearchArgs(val pattern: String, val directory: String? = null, val filePattern: String? = null)

@Serializable
data class RunCommandArgs(val command: String, val timeoutMs: Long = 30000)

@Serializable
data class ToolCall(val tool: String, val args: JsonElement)
    
    override fun getSystemPrompt(): String {
        return """
            You are a file system automation agent. Your sole purpose is to execute tasks by calling the provided file system tools.
            You do not provide explanations or generate code snippets. Your ONLY valid output is a JSON object representing a tool call.
            You will be given a task. You will call the tools in sequence until the task is complete.
            Your final action for any modification task MUST be a 'writeFile' call.
        """.trimIndent()
    }
    
    override suspend fun processStreaming(task: String, context: TaskContext): Flow<AgentResponseChunk> = flow {
        if (context.projectPath == null) {
            val response = process(task, context)
            emit(AgentResponseChunk.Text(response.result))
            emit(AgentResponseChunk.Complete)
            return@flow
        }
        emit(AgentResponseChunk.Progress(5, "Starting implementation"))
        // Emit a Thinking chunk early so the UI always has a visible "Thinking" event,
        // even when plan generation results in 0 steps or fails fast.
        emit(AgentResponseChunk.Thinking("Planning…"))
        val response = processWithMcpModules(task, context, emitChunk = { emit(it) })
        emit(AgentResponseChunk.Text(response.result))
        emit(AgentResponseChunk.Complete)
    }
    
    override suspend fun process(task: String, context: TaskContext): AgentResponse {
        println("[ENHANCED_IMPLEMENTATION] === ENHANCED IMPLEMENTATION AGENT CALLED ===")
        println("[ENHANCED_IMPLEMENTATION] Processing task: '$task'")
        println("[ENHANCED_IMPLEMENTATION] Project path from context: ${context.projectPath ?: "null"}")
        println("[ENHANCED_IMPLEMENTATION] Current files count: ${context.currentFiles.size}")
        println("[ENHANCED_IMPLEMENTATION] Session ID: ${context.sessionId}")

        // Require a project selected from the Projects card
        if (context.projectPath == null) {
            val msg = "No project selected. Please add and select a project from the Projects card so file operations run in the correct project."
            println("[ENHANCED_IMPLEMENTATION] $msg")
            return AgentResponse(
                agentType = AgentType.IMPLEMENTATION,
                result = msg,
                metadata = mapOf<String, Any>(
                    "workspace" to workspace.name,
                    "agent" to "enhanced_implementation",
                    "no_project" to "true"
                )
            )
        }

        println("[ENHANCED_IMPLEMENTATION] Project path validated: ${context.projectPath}")
        // Use MCP Selection & Decision modules for intelligent processing
        return processWithMcpModules(task, context)
    }
    
    /**
     * Process task using MCP Selection & Decision modules.
     * When [emitChunk] is provided, ToolCall chunks are emitted as tools execute (for streaming UI).
     */
    private suspend fun processWithMcpModules(task: String, context: TaskContext, emitChunk: (suspend (AgentResponseChunk) -> Unit)? = null): AgentResponse {
        println("[ENHANCED_IMPLEMENTATION] Using MCP Selection & Decision modules")
        
        // Initialize MCP integration with the selected project
        val projectPath = context.projectPath!!
        println("[ENHANCED_IMPLEMENTATION] Initializing MCP for project: $projectPath")
        
        try {
            // Step 1: Select relevant tools using MCP Selection Module
            println("[ENHANCED_IMPLEMENTATION] Step 1: Selecting relevant tools...")
            val selectedTools = selectionModule.selectRelevantTools(task)
            println("[ENHANCED_IMPLEMENTATION] Selected ${selectedTools.size} tools for execution")
            
            // Step 2: Extract parameters for selected tools
            println("[ENHANCED_IMPLEMENTATION] Step 2: Extracting parameters...")
            val extractedParams = selectionModule.extractParameters(task, selectedTools)
            println("[ENHANCED_IMPLEMENTATION] Extracted parameters for ${extractedParams.size} tools")
            
            // Step 3: Create execution plan using decision module
            println("[ENHANCED_IMPLEMENTATION] Step 3: Creating execution plan...")
            val executionPlan = decisionModule.analyzeAndCreatePlan(task, selectedTools, extractedParams)
            println("[ENHANCED_IMPLEMENTATION] Execution plan created with ${executionPlan.steps.size} steps")

            emitChunk?.invoke(
                AgentResponseChunk.Thinking(
                    buildString {
                        append("Plan: ")
                        val steps = executionPlan.steps
                            .take(6)
                            .mapNotNull { s -> s.tool?.name ?: s.type.name.lowercase() }
                            .filter { it.isNotBlank() }
                        if (steps.isEmpty()) {
                            append("(no planned steps)")
                        } else {
                            append(steps.joinToString(" → "))
                        }
                        if (executionPlan.steps.size > 6) append(" → …")
                    }
                )
            )
            
            // Execute the plan
            val result = when (executionPlan.executionStrategy) {
                com.alyk.ai.koog.core.orchestrator.mcp.ExecutionStrategy.NONE -> {
                    handleDirectResponse(task, executionPlan)
                }
                com.alyk.ai.koog.core.orchestrator.mcp.ExecutionStrategy.SEQUENTIAL -> {
                    executeSequentialPlan(task, executionPlan, context, emitChunk = emitChunk)
                }
                com.alyk.ai.koog.core.orchestrator.mcp.ExecutionStrategy.PARALLEL -> {
                    executeParallelPlan(task, executionPlan, context, emitChunk = emitChunk)
                }
            }

            // Persist decision log to session
            persistDecisionLog(context, executionPlan)
            
            return AgentResponse(
                agentType = AgentType.IMPLEMENTATION,
                result = result,
                metadata = mapOf<String, Any>(
                    "workspace" to workspace.name,
                    "agent" to "enhanced_implementation",
                    "model" to implementationModelWrapper.modelName,
                    "tools_used" to (executionPlan.relevantTools.isNotEmpty()),
                    "execution_strategy" to executionPlan.executionStrategy.name,
                    "complexity" to executionPlan.complexity.name,
                    "tools_selected" to executionPlan.relevantTools.map { it.name },
                    "tool_scores" to executionPlan.toolScores,
                    "estimated_duration_ms" to executionPlan.estimatedDurationMs
                )
            )
            
        } catch (e: TimeoutCancellationException) {
            println("[ENHANCED_IMPLEMENTATION] ⚠️ Processing timed out: ${e.message}")
            return AgentResponse(
                agentType = AgentType.IMPLEMENTATION,
                result = "The task timed out. Please try a simpler task or check the model status.",
                metadata = mapOf<String, Any>(
                    "workspace" to workspace.name,
                    "agent" to "enhanced_implementation",
                    "error" to "timeout",
                    "error_message" to (e.message ?: "Unknown timeout error")
                )
            )
        } catch (e: Exception) {
            println("[ENHANCED_IMPLEMENTATION] ❌ Error during processing: ${e.message}")
            e.printStackTrace()
            return AgentResponse(
                agentType = AgentType.IMPLEMENTATION,
                result = "Error during processing: ${e.message}",
                metadata = mapOf<String, Any>(
                    "workspace" to workspace.name,
                    "agent" to "enhanced_implementation",
                    "error" to "processing_error",
                    "error_message" to (e.message ?: "Unknown timeout error")
                )
            )
        }
    }

    private suspend fun persistDecisionLog(context: TaskContext, executionPlan: com.alyk.ai.koog.core.orchestrator.mcp.ExecutionPlan) {
        val store = sessionStore ?: return
        kotlin.runCatching {
            val uuid = UUID.fromString(context.sessionId)
            val entry = DecisionLogEntry(
                prompt = executionPlan.prompt.take(500),
                selectedTools = executionPlan.relevantTools.map { it.name },
                executionStrategy = executionPlan.executionStrategy.name,
                complexity = executionPlan.complexity.name,
                timestampMs = java.time.Instant.now().toEpochMilli(),
                rationale = null
            )
            store.updateSession(uuid) {
                it.copy(decisionLog = (it.decisionLog + entry).takeLast(20).toMutableList())
            }
        }.onFailure { println("[ENHANCED_IMPLEMENTATION] Failed to persist decision log: ${it.message}") }
    }
    
    /**
     * Handle direct response without tools
     */
    private suspend fun handleDirectResponse(task: String, executionPlan: com.alyk.ai.koog.core.orchestrator.mcp.ExecutionPlan): String {
        println("[ENHANCED_IMPLEMENTATION] Handling direct response (no tools selected)")
        
        val agent = createKoogAgent(
            modelName = implementationModelWrapper.modelName,
            contextLength = implementationModelWrapper.maxContextLength.toLong()
        )
        
        val enhancedPrompt = """
            You are an AI coding assistant. Please respond to the following request directly and concisely.
            
            Request: $task
            
            Context: This is a simple task that doesn't require file operations or external tools.
            Provide a helpful, direct response.
        """.trimIndent()
        
        return withTimeout(30000) { // 30 second timeout
            agent.run(enhancedPrompt)
        }
    }
    
    /**
     * Execute sequential execution plan.
     * When [onToolExecuted] is provided (e.g. for streaming), it is invoked after each tool run.
     */
    private suspend fun executeSequentialPlan(
        task: String, 
        executionPlan: com.alyk.ai.koog.core.orchestrator.mcp.ExecutionPlan,
        context: TaskContext,
        emitChunk: (suspend (AgentResponseChunk) -> Unit)? = null
    ): String {
        println("[ENHANCED_IMPLEMENTATION] Using ImplementationAgent's proven tool execution")
        
        // Use the same approach as regular ImplementationAgent
        val projectRoot = context.projectPath!!
        val tools = KoogToolRegistryBuilder.FileAccessTools(projectRoot)

        // If the user references a concrete file name (e.g. main.kt), we already have a reliable source-of-truth:
        // context.currentFiles (from ContextProvider scan). Use it to avoid build/ directory rabbit holes.
        val taskFileHint = extractLikelyFileNameFromTask(task)
        val knownMatch = taskFileHint?.let { hint ->
            context.currentFiles.firstOrNull { it.endsWith("\\$hint", ignoreCase = true) || it.endsWith("/$hint", ignoreCase = true) }
        }
        val knownFilesPreview = context.currentFiles.take(20).joinToString("\n") { "- $it" }
        
        // Create a more explicit prompt that forces tool execution
        val prompt = """
            TASK: $task
            
            KNOWN PROJECT FILES (from project scan; prefer these paths over exploring build artifacts):
            ${if (knownFilesPreview.isBlank()) "- (none)" else knownFilesPreview}
            
            AVAILABLE TOOLS:
            - readFile: Read file contents (path: String)
            - writeFile: Write file contents (path: String, content: String)  
            - listDirectory: List directory contents (path: String)
            - regexSearch: Search for patterns (pattern: String, directory: String?, filePattern: String?)
            - runCommand: Execute shell commands (command: String, timeoutMs: Long)
            
            CRITICAL RULES OF OPERATION:
            1.  You are a file manipulation agent. Your ONLY output format is a JSON tool call.
            2.  Do NOT write any text, explanation, or code outside of a JSON tool call.
            3.  For any task that involves changing a file, the process is MANDATORY:
                a. Call `readFile` on the target file.
                b. In the NEXT turn, call `writeFile` with the COMPLETE, MODIFIED content of the file.
            4.  Never output code snippets. The only way to produce code is inside the `content` argument of a `writeFile` tool call. Any other code output is a system failure.
            5.  EXECUTE A TOOL NOW.
            6.  If TASK mentions a specific file (e.g. "$taskFileHint") and it exists in KNOWN PROJECT FILES, you MUST call readFile on that exact path first.
            
            PROJECT ROOT: $projectRoot
            
            Execute the tools now and provide the actual results!
        """.trimIndent()
        
        // If we already know the exact file path, prime the loop with a successful read output so the model doesn't wander.
        // This is the "modern agent" behavior: resolve target deterministically, then operate.
        val primedPrompt = if (knownMatch != null) {
            val read = tools.readFile(knownMatch)
            if (read.success && read.content != null) {
                prompt + "\n\nTOOL OUTPUT (prefetched): ✅ File read successfully:\n${read.content}"
            } else {
                prompt
            }
        } else prompt

        return executeWithTools(initialPrompt = primedPrompt, tools = tools, context = context, maxIterations = 10, originalTask = task, emitChunk = emitChunk)
    }
    
    /**
     * Execute parallel execution plan (for now delegates to sequential with same callback).
     */
    private suspend fun executeParallelPlan(
        task: String,
        executionPlan: com.alyk.ai.koog.core.orchestrator.mcp.ExecutionPlan,
        context: TaskContext,
        emitChunk: (suspend (AgentResponseChunk) -> Unit)? = null
    ): String {
        println("[ENHANCED_IMPLEMENTATION] Using ImplementationAgent's proven tool execution for parallel plan")
        return executeSequentialPlan(task, executionPlan, context, emitChunk = emitChunk)
    }
    
    /**
     * Build execution prompt that includes the MCP plan and project context
     */
    private fun buildExecutionPrompt(
        task: String,
        executionPlan: com.alyk.ai.koog.core.orchestrator.mcp.ExecutionPlan,
        context: TaskContext
    ): String {
        println("[ENHANCED_IMPLEMENTATION] Building execution prompt...")
        println("[ENHANCED_IMPLEMENTATION] Context projectPath: ${context.projectPath}")
        println("[ENHANCED_IMPLEMENTATION] Context currentFiles: ${context.currentFiles.size} files")
        
        val toolsList = executionPlan.relevantTools.map { 
            "${it.name} (${it.category}): ${it.description}" 
        }.joinToString("\n- ")
        
        val prompt = """
            ${getSystemPrompt()}
            
            Original Task: $task
            
            Project Context:
            - Project Path: ${context.projectPath ?: "NULL"}
            - Current Files: ${context.currentFiles.take(10).joinToString(", ")}
            
            MCP Analysis Results:
            - Selected Tools: ${executionPlan.relevantTools.map { it.name }.joinToString(", ")}
            - Execution Strategy: ${executionPlan.executionStrategy}
            - Complexity: ${executionPlan.complexity}
            - Estimated Duration: ${executionPlan.estimatedDurationMs}ms
            
            Available Tools:
            - $toolsList
            
            Execution Plan:
            ${executionPlan.steps.mapIndexed { index, step ->
                "${index + 1}. ${step.type}: ${step.tool?.name ?: "N/A"}"
            }.joinToString("\n")}
            
            Please execute this task using the available tools. The project appears to be a Kotlin project based on the build.gradle.kts file.
            
            For the task "run 100 test coroutines in Main.kt", you should:
            1. Check if Main.kt exists in src/main/kotlin/
            2. If not, create Main.kt with 100 coroutines
            3. Use build tools to run the application
            4. Report the results
            
            Use the available file access and build tools to complete this task.
        """.trimIndent()
        
        println("[ENHANCED_IMPLEMENTATION] Generated prompt length: ${prompt.length}")
        return prompt
    }
    
    /**
     * Execute a single tool step
     */
    private suspend fun executeToolStep(
        step: com.alyk.ai.koog.core.orchestrator.mcp.ExecutionStep,
        tools: KoogToolRegistryBuilder.FileAccessTools,
        previousResults: List<String>
    ): String {
        val tool = step.tool ?: return "No tool specified"
        val startTime = System.currentTimeMillis()
        
        return try {
            val result = when (tool.name) {
                "project_context" -> executeProjectContext(step.parameters, tools)
                "file_analyzer" -> executeFileAnalyzer(step.parameters, tools)
                "code_search" -> executeCodeSearch(step.parameters, tools)
                "dependency_scanner" -> executeDependencyScanner(step.parameters, tools)
                "build_runner" -> executeBuildRunner(step.parameters, tools)
                "test_executor" -> executeTestExecutor(step.parameters, tools)
                "git_operations" -> executeGitOperations(step.parameters, tools)
                else -> "Unknown tool: ${tool.name}"
            }
            
            val duration = System.currentTimeMillis() - startTime
            toolUsageTracker?.recordToolUsage(tool.name, true, duration)
            
            println("[ENHANCED_IMPLEMENTATION] ✅ Tool executed: ${tool.name} (${duration}ms)")
            result
            
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            toolUsageTracker?.recordToolUsage(tool.name, false, duration)
            
            println("[ENHANCED_IMPLEMENTATION] ❌ Tool execution failed: ${tool.name} - ${e.message}")
            "Tool execution failed: ${e.message}"
        }
    }
    
    /**
     * Synthesize results from multiple tool executions
     */
    private suspend fun synthesizeResults(
        task: String,
        results: List<String>,
        executionPlan: com.alyk.ai.koog.core.orchestrator.mcp.ExecutionPlan
    ): String {
        println("[ENHANCED_IMPLEMENTATION] Synthesizing results from ${results.size} previous steps")
        
        val agent = createKoogAgent(
            modelName = implementationModelWrapper.modelName,
            contextLength = implementationModelWrapper.maxContextLength.toLong()
        )
        
        val synthesisPrompt = """
            You are an AI coding assistant. Please synthesize the following tool execution results to answer the user's request.
            
            Original Request: $task
            
            Tool Execution Results:
            ${results.joinToString("\n\n") { "- $it" }}
            
            Please provide a comprehensive response that addresses the original request using the information gathered from the tool executions.
            Be helpful, specific, and actionable.
        """.trimIndent()
        
        return withTimeout(30000) { // 30 second timeout
            agent.run(synthesisPrompt)
        }
    }
    
    /**
     * Group execution steps by their dependencies for parallel execution
     */
    private fun groupStepsByDependencies(steps: List<com.alyk.ai.koog.core.orchestrator.mcp.ExecutionStep>): List<List<com.alyk.ai.koog.core.orchestrator.mcp.ExecutionStep>> {
        val groups = mutableListOf<List<com.alyk.ai.koog.core.orchestrator.mcp.ExecutionStep>>()
        val remaining = steps.toMutableList()
        
        while (remaining.isNotEmpty()) {
            val currentGroup = mutableListOf<com.alyk.ai.koog.core.orchestrator.mcp.ExecutionStep>()
            val iterator = remaining.iterator()
            
            while (iterator.hasNext()) {
                val step = iterator.next()
                // Check if all dependencies are satisfied (i.e., dependencies are in previous groups)
                val dependenciesSatisfied = step.dependencies.all { dep ->
                    dep < groups.sumOf { it.size }
                }
                
                if (dependenciesSatisfied) {
                    currentGroup.add(step)
                    iterator.remove()
                }
            }
            
            if (currentGroup.isNotEmpty()) {
                groups.add(currentGroup)
            } else {
                // If no steps can be executed, take the next one to avoid deadlock
                groups.add(listOf(remaining.removeAt(0)))
            }
        }
        
        return groups
    }
    
    // Tool execution implementations (simplified for demonstration)
    private suspend fun executeProjectContext(parameters: Map<String, Any>, tools: KoogToolRegistryBuilder.FileAccessTools): String {
        val includeContents = parameters["include_file_contents"] as? Boolean ?: false
        val maxFiles = (parameters["max_files"] as? Number)?.toInt() ?: 50
        
        val listing = tools.listDirectory(".")
        return if (listing.success) {
            val fileCount = listing.entries.count { !it.isDirectory }
            val dirCount = listing.entries.count { it.isDirectory }
            "Project contains $fileCount files and $dirCount directories. ${if (includeContents) "File contents included." else "File contents not included."}"
        } else {
            "Failed to get project context: ${listing.error}"
        }
    }
    
    private suspend fun executeFileAnalyzer(parameters: Map<String, Any>, tools: KoogToolRegistryBuilder.FileAccessTools): String {
        val filePath = parameters["file_path"] as? String ?: return "No file path specified"
        val analysisType = parameters["analysis_type"] as? String ?: "all"
        
        val fileContent = tools.readFile(filePath)
        return if (fileContent.success) {
            val lines = fileContent.lines
            val chars = fileContent.content?.length ?: 0
            "File $filePath: $lines lines, $chars characters. Analysis type: $analysisType"
        } else {
            "Failed to analyze file: ${fileContent.error}"
        }
    }
    
    private suspend fun executeCodeSearch(parameters: Map<String, Any>, tools: KoogToolRegistryBuilder.FileAccessTools): String {
        val query = parameters["query"] as? String ?: return "No query specified"
        val fileTypes = parameters["file_types"] as? List<String> ?: listOf(".kt", ".java")
        
        val searchResults = tools.regexSearch(query, null, fileTypes.firstOrNull())
        return if (searchResults.success) {
            val matches = searchResults.matches.size
            "Found $matches matches for '$query' in ${fileTypes.joinToString(", ")} files"
        } else {
            "Search failed: ${searchResults.error}"
        }
    }
    
    private suspend fun executeDependencyScanner(parameters: Map<String, Any>, tools: KoogToolRegistryBuilder.FileAccessTools): String {
        val includeTransitive = parameters["include_transitive"] as? Boolean ?: true
        val checkVulnerabilities = parameters["check_vulnerabilities"] as? Boolean ?: true
        
        // Simplified implementation - in reality would scan build files
        return "Dependency scan completed. Transitive: $includeTransitive, Vulnerability check: $checkVulnerabilities"
    }
    
    private suspend fun executeBuildRunner(parameters: Map<String, Any>, tools: KoogToolRegistryBuilder.FileAccessTools): String {
        val command = parameters["command"] as? String ?: "build"
        val clean = parameters["clean"] as? Boolean ?: false
        
        return "Build command '$command' executed. Clean: $clean"
    }
    
    private suspend fun executeTestExecutor(parameters: Map<String, Any>, tools: KoogToolRegistryBuilder.FileAccessTools): String {
        val testPattern = parameters["test_pattern"] as? String ?: "*"
        val generateCoverage = parameters["generate_coverage"] as? Boolean ?: true
        
        return "Tests executed with pattern '$testPattern'. Coverage: $generateCoverage"
    }
    
    private suspend fun executeGitOperations(parameters: Map<String, Any>, tools: KoogToolRegistryBuilder.FileAccessTools): String {
        val operation = parameters["operation"] as? String ?: "status"
        val filePath = parameters["file_path"] as? String
        
        return "Git operation '$operation' executed${filePath?.let { " on file $it" } ?: ""}"
    }
    
    /**
     * Execute task using tools with synthesis step when max iterations reached without direct answer.
     * When [onToolExecuted] is provided (e.g. for streaming UI), it is invoked after each tool run.
     */
    private suspend fun executeWithTools(
        initialPrompt: String,
        tools: KoogToolRegistryBuilder.FileAccessTools,
        context: TaskContext,
        maxIterations: Int = 10,
        originalTask: String = initialPrompt.take(200),
        emitChunk: (suspend (AgentResponseChunk) -> Unit)? = null
    ): String {
        // Load history from database
        val conversationHistory = try {
            sessionStore?.getSession(UUID.fromString(context.sessionId))?.conversationHistory?.toMutableList() 
                ?: mutableListOf()
        } catch (e: IllegalArgumentException) {
            mutableListOf()
        }
        
        if (conversationHistory.isEmpty()) {
            conversationHistory.add("User: $initialPrompt")
        }
        
        var finalResponse = ""
        var toolCallCount = 0
        var exitedWithDirectAnswer = false
        
        // Observations for synthesis when max iterations reached without direct answer
        val observations = mutableListOf<Triple<String, String, String>>() // (tool, params, result)
        
        // Local state tracking for this execution
        var lastSuccessfulToolOutput: String? = null
        var consecutiveToolCalls = 0
        val maxConsecutiveToolCalls = 3 // Prevent infinite loops
        
        for (iteration in 0 until maxIterations) {
            println("[ENHANCED_IMPLEMENTATION] ===== Iteration ${iteration + 1}/$maxIterations =====")
            
            try {
                // Create a NEW AIAgent for each iteration (AIAgent is single-use)
                val agent = createKoogAgent(
                    modelName = implementationModelWrapper.modelName,
                    contextLength = implementationModelWrapper.maxContextLength.toLong()
                )
                
                // Build prompt with conversation history
                val currentPrompt = if (conversationHistory.size > 1) {
                    conversationHistory.joinToString("\n\n") + "\n\nAssistant:"
                } else {
                    initialPrompt
                }
                
                println("[ENHANCED_IMPLEMENTATION] Calling LLM with prompt (${currentPrompt.length} chars)")
                val agentResponse = try {
                    withTimeout(60000) { // 60 second timeout per iteration
                        sessionManager?.writeSessionWithResult { session ->
                            session.requestLLM(currentPrompt)
                        } ?: agent.run(currentPrompt)
                    }
                } catch (e: TimeoutCancellationException) {
                    println("[ENHANCED_IMPLEMENTATION] ⚠️ Agent execution timed out after 60s")
                    return "The agent execution timed out. The model may be stuck or taking too long."
                }
                
                println("[ENHANCED_IMPLEMENTATION] Agent response length: ${agentResponse.length} chars")
                println("[ENHANCED_IMPLEMENTATION] Agent response: '$agentResponse'")
                
                // Try to extract tool call
                val toolCall = extractToolCall(agentResponse)
                
                // Check if response looks like a final answer
                // More lenient criteria: accept shorter responses if they seem complete
                val looksLikeFinalAnswer = toolCall == null &&
                    agentResponse.length > 20 &&
                    // Don't treat JSON tool-call shapes as final answers
                    !agentResponse.trimStart().startsWith("```json") &&
                    !agentResponse.trimStart().startsWith("{") &&
                    (
                    agentResponse.contains("✅") || 
                    agentResponse.contains("files:") || 
                    agentResponse.contains("directory:") ||
                    agentResponse.contains("found:") ||
                    agentResponse.contains("error:") ||
                    agentResponse.contains("result:") ||
                    agentResponse.contains(".") || // Directory listings often have dots
                    agentResponse.contains("/") || // File paths
                    agentResponse.contains("📁") || // File emojis
                    agentResponse.contains("📄")    // File emojis
                )
                
                if (toolCall != null) {
                    // Check for repeated tool calls to prevent infinite loops
                    if (consecutiveToolCalls >= maxConsecutiveToolCalls) {
                        println("[ENHANCED_IMPLEMENTATION] ⚠️ Too many consecutive tool calls, breaking out")
                        finalResponse = "Tool execution completed but no final answer received. Results: ${lastSuccessfulToolOutput ?: "No results"}"
                        break
                    }
                    
                    consecutiveToolCalls++
                    println("[ENHANCED_IMPLEMENTATION] 🔧 Tool call detected: ${toolCall.tool}")
                    val startTime = System.currentTimeMillis()
                    
                    try {
                        val toolOutput = executeTool(toolCall, tools, originalTask = originalTask)
                        val duration = System.currentTimeMillis() - startTime
                        val paramsStr = toolCall.args.toString().take(300)
                        observations.add(Triple(toolCall.tool, paramsStr, toolOutput))
                        
                        val success = toolOutput.trimStart().startsWith("✅")
                        
                        emitChunk?.invoke(
                            AgentResponseChunk.ToolCall(
                                toolName = toolCall.tool,
                                parameters = jsonElementToParams(toolCall.args),
                                result = toolOutput,
                                success = success,
                                durationMs = duration
                            )
                        )
                        pendingFileDiff?.let { diff ->
                            emitChunk?.invoke(diff)
                            pendingFileDiff = null
                        }
                        
                        // Stop the tool loop after a successful write: for modification tasks, the work is done.
                        // Without this, some models oscillate read/write and never produce a final answer.
                        if (success && (toolCall.tool == "write_file" || toolCall.tool == "writeFile")) {
                            exitedWithDirectAnswer = true
                            finalResponse = "✅ Updated ${try { json.decodeFromJsonElement(WriteFileArgs.serializer(), toolCall.args).path } catch (_: Exception) { "file" }}."
                            break
                        }

                        if (success) {
                            println("[ENHANCED_IMPLEMENTATION] ✅ Tool executed: ${toolCall.tool} (${duration}ms)")
                            lastSuccessfulToolOutput = toolOutput
                            consecutiveToolCalls = 0 // Reset counter on successful execution
                        } else {
                            println("[ENHANCED_IMPLEMENTATION] ❌ Tool failed: ${toolCall.tool} (${duration}ms)")
                        }
                        
                        // Add tool result to conversation
                        conversationHistory.add("Assistant: $agentResponse")
                        conversationHistory.add("Observation: $toolOutput")
                        
                    } catch (e: Exception) {
                        val duration = System.currentTimeMillis() - startTime
                        emitChunk?.invoke(
                            AgentResponseChunk.ToolCall(
                                toolName = toolCall.tool,
                                parameters = jsonElementToParams(toolCall.args),
                                result = "Error: ${e.message}",
                                success = false,
                                durationMs = duration
                            )
                        )
                        println("[ENHANCED_IMPLEMENTATION] ❌ Tool execution error: ${e.message}")
                        observations.add(Triple(toolCall.tool, toolCall.args.toString().take(300), "Error: ${e.message}"))
                        conversationHistory.add("Assistant: $agentResponse")
                        conversationHistory.add("Observation: Error executing ${toolCall.tool}: ${e.message}")
                    }
                    
                } else {
                    consecutiveToolCalls = 0 // Reset when no tool call
                    exitedWithDirectAnswer = true
                    if (looksLikeFinalAnswer) {
                        println("[ENHANCED_IMPLEMENTATION] ✅ Final answer detected")
                        finalResponse = agentResponse
                        break
                    } else {
                        println("[ENHANCED_IMPLEMENTATION] ⚠️ No tool call or final answer detected")
                        if (agentResponse.length > 10) {
                            println("[ENHANCED_IMPLEMENTATION] 🔄 Treating short response as potential final answer")
                            finalResponse = agentResponse
                            break
                        } else {
                            println("[ENHANCED_IMPLEMENTATION] 🔄 Response too short, continuing...")
                            finalResponse = agentResponse
                            break
                        }
                    }
                }
                
                toolCallCount++
            } catch (e: Exception) {
                println("[ENHANCED_IMPLEMENTATION] ❌ Iteration error: ${e.message}")
                finalResponse = "Error during execution: ${e.message}"
                break
            }
        }
        
        // Synthesis step: when max iterations reached without direct answer, synthesize from observations
        if (!exitedWithDirectAnswer && observations.isNotEmpty()) {
            println("[ENHANCED_IMPLEMENTATION] 📝 Max iterations reached without direct answer - synthesizing from ${observations.size} observations")
            finalResponse = synthesizeAnswer(originalTask, observations)
        }
        
        // Save history to database
        try {
            sessionStore?.updateSession(UUID.fromString(context.sessionId)) {
                it.copy(conversationHistory = conversationHistory)
            }
        } catch (e: IllegalArgumentException) {
            // Ignore if session ID is not a valid UUID
        }
        
        return finalResponse.ifEmpty { "No response generated." }
    }
    
    /** Convert JsonElement (e.g. tool args) to Map<String, Any> for streaming chunk parameters. */
    private fun jsonElementToParams(element: JsonElement): Map<String, Any> {
        if (element !is JsonObject) return emptyMap()
        return element.mapValues { (_, v) ->
            when (v) {
                is JsonPrimitive -> v.content
                is JsonObject -> v.toString()
                else -> v.toString()
            }
        }
    }
    
    /**
     * Extract tool call from agent response.
     * Handles: (1) args/arguments, (2) flat format {"tool":"readFile","path":"..."}, (3) bracket-aware parsing.
     */
    private fun extractToolCall(response: String): ToolCall? {
        val cleaned = response
            .replace(Regex("""```(?:json)?\s*"""), "")
            .replace(Regex("""\s*```"""), "")
            .trim()
        
        val jsonStart = cleaned.indexOf("{")
        if (jsonStart == -1) return null
        
        val potentialJson = extractJsonObject(cleaned, jsonStart) ?: return null
        val normalized = if (""""arguments"""" in potentialJson) {
            potentialJson.replace("""\"arguments\"""", """\"args\"""")
        } else potentialJson
        
        return try {
            json.decodeFromString<ToolCall>(normalized)
        } catch (e: Exception) {
            try {
                // Fallback: models sometimes return flat format {"tool":"readFile","path":"..."} instead of {"tool":"readFile","args":{"path":"..."}}
                normalizeFlatToolCall(normalized)
            } catch (e2: Exception) {
                println("[ENHANCED_IMPLEMENTATION] ⚠️ Tool call parse failed: ${e.message?.take(80)}")
                null
            }
        }
    }
    
    private fun normalizeFlatToolCall(jsonStr: String): ToolCall? {
        val obj = json.decodeFromString<JsonObject>(jsonStr)
        val explicitTool = obj["tool"]?.toString()?.trim('"')
        if (explicitTool == null) {
            // Some models emit {"readFile": {...}} or {"listDirectory": {...}} instead of {"tool":"readFile","args":{...}}
            val known = setOf("readFile", "writeFile", "listDirectory", "regexSearch", "runCommand", "read_file", "write_file", "list_directory", "regex_search", "run_command", "search")
            val key = obj.keys.firstOrNull { it in known } ?: return null
            val argsEl = obj[key]
            if (argsEl is JsonObject) return ToolCall(key, argsEl)
            return null
        }
        val tool = explicitTool
        val argsEl = obj["args"] ?: obj["arguments"]
        if (argsEl != null) {
            return ToolCall(tool, argsEl)
        }
        // Build args from flat fields: {"tool":"readFile","path":"..."} -> {"tool":"readFile","args":{"path":"..."}}
        val argsMap = mutableMapOf<String, JsonElement>()
        for ((k, v) in obj) {
            if (k != "tool" && k != "args" && k != "arguments") argsMap[k] = v
        }
        if (argsMap.isEmpty()) return null
        val argsObj = buildJsonObject { argsMap.forEach { (key, value) -> put(key, value) } }
        return ToolCall(tool, argsObj)
    }
    
    /** Extract top-level JSON object using bracket matching (respects strings so { } inside content work). */
    private fun extractJsonObject(s: String, start: Int): String? {
        if (start >= s.length || s[start] != '{') return null
        var depth = 0
        var i = start
        var inString = false
        var escape = false
        var quote = ' '
        while (i < s.length) {
            val c = s[i]
            when {
                escape -> { escape = false; i++; continue }
                inString -> {
                    if (c == '\\') escape = true
                    else if (c == quote) inString = false
                    i++
                    continue
                }
                c == '"' || c == '\'' -> { inString = true; quote = c; i++; continue }
                c == '{' -> { depth++; i++; continue }
                c == '}' -> {
                    depth--
                    if (depth == 0) return s.substring(start, i + 1)
                    i++
                    continue
                }
                else -> i++
            }
        }
        return null
    }
    
    /**
     * Synthesize a final answer from observations when max iterations reached without direct answer.
     */
    private suspend fun synthesizeAnswer(
        originalTask: String,
        observations: List<Triple<String, String, String>>
    ): String {
        val formattedObs = formatObservations(observations)
        val synthesisPrompt = """
            Original question: $originalTask
            
            I gathered this information using tools:
            
            $formattedObs
            
            Based on this information, please answer the original question in a clear, concise way.
            If information is incomplete, explain what was found and what might be missing.
            Do not output JSON or tool calls - just a direct answer for the user.
        """.trimIndent()
        
        return try {
            sessionManager?.writeSessionWithResult { session ->
                session.requestLLM(synthesisPrompt)
            } ?: implementationModelWrapper.generate(synthesisPrompt)
        } catch (e: Exception) {
            println("[ENHANCED_IMPLEMENTATION] ⚠️ Synthesis failed: ${e.message}")
            "I gathered information but could not synthesize an answer. Observations: ${observations.size} tool results."
        }
    }
    
    private fun formatObservations(observations: List<Triple<String, String, String>>, maxResultChars: Int = 800): String {
        return observations.mapIndexed { i, (tool, params, result) ->
            val truncated = if (result.length > maxResultChars) {
                result.take(maxResultChars) + "\n... (truncated, ${result.length - maxResultChars} more chars)"
            } else result
            "--- Observation ${i + 1} ---\nTool: $tool\nParams: $params\nResult:\n$truncated"
        }.joinToString("\n\n")
    }
    
    /**
     * Execute a tool call (copied from ImplementationAgent)
     */
    private suspend fun executeTool(toolCall: ToolCall, tools: KoogToolRegistryBuilder.FileAccessTools, originalTask: String): String {
        return try {
            // Safety: for "list/show files" tasks, do not allow mutating operations.
            if (isPureListingTask(originalTask) && (toolCall.tool == "write_file" || toolCall.tool == "writeFile")) {
                return "❌ Blocked writeFile: task is a listing request ('${originalTask.take(80)}...'). Use listDirectory/regexSearch/readFile only."
            }
            when (toolCall.tool) {
                "read_file", "readFile" -> {
                    val args = json.decodeFromJsonElement(ReadFileArgs.serializer(), toolCall.args)
                    val output = tools.readFile(args.path)
                    if (output.success) {
                        "✅ File read successfully: ${output.content}"
                    } else {
                        "❌ Error reading file: ${output.error}"
                    }
                }
                "write_file", "writeFile" -> {
                    val args = json.decodeFromJsonElement(WriteFileArgs.serializer(), toolCall.args)
                    val before = tools.readFile(args.path).let { if (it.success) it.content else null }
                    val output = tools.writeFile(args.path, args.content)
                    if (output.success) {
                        val after = args.content
                        val oldLines = before?.lines()?.size ?: 0
                        val newLines = after.lines().size
                        val oldPreview = before?.lineSequence()?.take(8)?.joinToString("\n")
                        val newPreview = after.lineSequence().take(8).joinToString("\n")
                        pendingFileDiff = AgentResponseChunk.FileDiff(
                            path = args.path,
                            oldPreview = oldPreview,
                            newPreview = newPreview,
                            addedLines = (newLines - oldLines).coerceAtLeast(0),
                            removedLines = (oldLines - newLines).coerceAtLeast(0)
                        )
                        "✅ File written successfully: ${args.path}"
                    } else {
                        "❌ Error writing file: ${output.error}"
                    }
                }
                "list_directory", "listDirectory" -> {
                    val args = json.decodeFromJsonElement(ListDirectoryArgs.serializer(), toolCall.args)
                    // Modern agent behavior: avoid "list root again" loops when we actually need a file.
                    // If the model tries to list ".", prefer a targeted filename search first.
                    if (args.path == "." || args.path.isBlank()) {
                        val candidate = extractLikelyFileNameFromTask(originalTask = originalTask)
                        if (candidate != null) {
                            val search = tools.regexSearch(
                                pattern = Regex.escape(candidate),
                                directory = "src",
                                filePattern = "*.kt"
                            )
                            if (search.success && search.matches.isNotEmpty()) {
                                val files = search.matches.map { it.file }.distinct().take(10).joinToString("\n") { "📄 $it" }
                                return "✅ Found candidate source files for '$candidate' (via regex_search, avoiding root listing):\n$files\nNext: call read_file on the correct one."
                            }
                        }
                    }

                    val output = tools.listDirectory(args.path)
                    if (output.success) {
                        val entries = output.entries.take(20).joinToString("\n") { 
                            "${if (it.isDirectory) "📁" else "📄"} ${it.path}" 
                        }
                        "✅ Directory listing:\n$entries"
                    } else {
                        "❌ Error listing directory: ${output.error}"
                    }
                }
                "regex_search", "search" -> {
                    val args = json.decodeFromJsonElement(RegexSearchArgs.serializer(), toolCall.args)
                    val output = tools.regexSearch(args.pattern, args.directory, args.filePattern)
                    if (output.success) {
                        val matches = output.matches.take(10).joinToString("\n") { 
                            "${it.file}:${it.line} - ${it.match}" 
                        }
                        "✅ Found ${output.matches.size} matches:\n$matches"
                    } else {
                        "❌ Error searching: ${output.error}"
                    }
                }
                "run_command", "runCommand" -> {
                    val args = json.decodeFromJsonElement(RunCommandArgs.serializer(), toolCall.args)
                    val output = tools.runCommand(args.command, args.timeoutMs)
                    if (output.success) {
                        "✅ Command executed successfully:\n${output.output}"
                    } else {
                        "❌ Error executing command: ${output.error}"
                    }
                }
                else -> {
                    "❌ Unknown tool: ${toolCall.tool}"
                }
            }
        } catch (e: Exception) {
            "❌ Error executing tool ${toolCall.tool}: ${e.message}"
        }
    }

    // (no diff helper; diff is stored in pendingFileDiff and emitted in executeWithTools)

    private fun extractLikelyFileNameFromTask(originalTask: String?): String? {
        if (originalTask.isNullOrBlank()) return null
        val m = """([A-Za-z0-9_\-]+\.(kt|java|py|js|ts|go|rs|cs|scala))""".toRegex(RegexOption.IGNORE_CASE).find(originalTask)
        return m?.groupValues?.getOrNull(1)
    }

    private fun isPureListingTask(task: String): Boolean {
        val t = task.trim().lowercase()
        if (!t.contains("list") && !t.contains("show")) return false
        // If it also contains obvious modification verbs, it's not "pure listing".
        val modify = listOf("edit", "modify", "update", "change", "fix", "set ", "replace", "remove", "delete", "add", "write")
        if (modify.any { t.contains(it) }) return false
        return t.contains("file") || t.contains("files") || t.contains("directory") || t.contains("folders") || t.contains("project")
    }
}
