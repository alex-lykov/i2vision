package com.alyk.ai.koog.core.orchestrator.agents

import ai.koog.agents.core.tools.ToolRegistry
import com.alyk.ai.koog.core.orchestrator.mcp.workspace.McpWorkspace
import com.alyk.ai.koog.core.orchestrator.router.AgentResponse
import com.alyk.ai.koog.core.orchestrator.router.AgentResponseChunk
import com.alyk.ai.koog.core.orchestrator.router.AgentType
import com.alyk.ai.koog.core.orchestrator.router.TaskContext
import com.alyk.ai.koog.core.orchestrator.tools.KoogToolRegistryBuilder
import com.alyk.ai.koog.core.orchestrator.tools.ToolUsageTracker
import com.alyk.ai.koog.models.wrappers.ModelWrapper
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

@Serializable
data class ToolCall(
    val tool: String,
    val args: JsonElement
)

@Serializable
data class ReadFileArgs(val path: String)

@Serializable
data class WriteFileArgs(val path: String, val content: String)

@Serializable
data class ListDirectoryArgs(val path: String)

@Serializable
data class RegexSearchArgs(val pattern: String, val directory: String? = null, val filePattern: String? = null)

/**
 * Implementation Agent: Handles actual code implementation
 * Uses MCP Workspace: Implementation
 * This agent uses Koog's AIAgent with manual tool execution loop for file access
 */
class ImplementationAgent(
    workspace: McpWorkspace,
    private val implementationModelWrapper: ModelWrapper,
    toolRegistry: ToolRegistry = ToolRegistry.EMPTY,
    private val toolUsageTracker: ToolUsageTracker? = null
) : BaseAgent(AgentType.IMPLEMENTATION, workspace, toolRegistry, implementationModelWrapper) {
    
    private val json = Json { ignoreUnknownKeys = true }
    
    override suspend fun process(task: String, context: TaskContext): AgentResponse {
        println("[IMPLEMENTATION] Processing task: '$task'")
        println("[IMPLEMENTATION] Project path from context: ${context.projectPath ?: "null"}")

        // Require a project selected from the Projects card (DB); do not use current working directory
        if (context.projectPath == null) {
            val msg = "No project selected. Please add and select a project from the Projects card so file operations run in the correct project."
            println("[IMPLEMENTATION] $msg")
            return AgentResponse(
                agentType = AgentType.IMPLEMENTATION,
                result = msg,
                metadata = mapOf(
                    "workspace" to workspace.name,
                    "agent" to "implementation",
                    "no_project" to "true"
                )
            )
        }

        // Fast-path: handle basic arithmetic locally (no LLM, instant)
        computeBasicArithmetic(task)?.let { arithmeticResult ->
            println("[IMPLEMENTATION] Fast arithmetic result (no LLM): $arithmeticResult")
            return AgentResponse(
                agentType = AgentType.IMPLEMENTATION,
                result = arithmeticResult,
                metadata = mapOf(
                    "workspace" to workspace.name,
                    "agent" to "implementation",
                    "model" to implementationModelWrapper.modelName,
                    "tools_used" to "false",
                    "simple_task" to "true",
                    "simple_task_type" to "arithmetic_local"
                )
            )
        }
        
        // Check if this is a simple task that doesn't need tools
        if (isSimpleTask(task)) {
            println("[IMPLEMENTATION] Detected simple task, using direct response")
            val simpleResult = handleSimpleTask(task)
            return AgentResponse(
                agentType = AgentType.IMPLEMENTATION,
                result = simpleResult,
                metadata = mapOf(
                    "workspace" to workspace.name,
                    "agent" to "implementation",
                    "model" to implementationModelWrapper.modelName,
                    "tools_used" to "false",
                    "simple_task" to "true"
                )
            )
        }
        
        // Use project root from context only (project selected from Projects card / DB)
        val projectRoot = context.projectPath!!
        println("[IMPLEMENTATION] Using project root for tools: '$projectRoot'")
        val tools = KoogToolRegistryBuilder.FileAccessTools(projectRoot)
        
        // Fast-path: "list project root folders" = one list_directory on project root, no LLM
        if (isListProjectRootTask(task)) {
            println("[IMPLEMENTATION] Fast-path: listing project root (UI-selected project) without LLM")
            val output = tools.listDirectory(".")
            val result = if (output.success) {
                "✅ Directory listing:\n${output.entries.joinToString("\n") { "${if (it.isDirectory) "📁" else "📄"} ${it.path}" }}"
            } else {
                "❌ Error listing directory: ${output.error}"
            }
            return AgentResponse(
                agentType = AgentType.IMPLEMENTATION,
                result = result,
                metadata = mapOf(
                    "workspace" to workspace.name,
                    "agent" to "implementation",
                    "model" to implementationModelWrapper.modelName,
                    "tools_used" to "true",
                    "simple_task" to "true",
                    "simple_task_type" to "list_project_root"
                )
            )
        }
        
        // Use Koog AIAgent with tool execution loop
        val prompt = buildPromptWithContext(task, context)
        
        // Execute with tool loop (creates new agents for each iteration)
        val result = executeWithTools(prompt, tools, maxIterations = 10)
        
        return AgentResponse(
            agentType = AgentType.IMPLEMENTATION,
            result = result,
            metadata = mapOf(
                "workspace" to workspace.name,
                "agent" to "implementation",
                "model" to implementationModelWrapper.modelName,
                "tools_used" to "true"
            )
        )
    }
    
    /**
     * Check if task is simple (math, questions, etc.) that doesn't need file access
     */
    private fun isSimpleTask(task: String): Boolean {
        val trimmed = task.trim()
        // Simple math expressions (e.g., "2+2", "10*5", "100/4")
        // Match: number, operator, number (with optional whitespace, no requirement for trailing content)
        if (trimmed.matches(Regex("""^\d+\s*[+\-*/]\s*\d+\s*$"""))) {
            return true
        }
        // Also match math expressions with trailing content (e.g., "2+2 please", "10*5 now")
        if (trimmed.matches(Regex("""^\d+\s*[+\-*/]\s*\d+\s+.+"""))) {
            return true
        }
        // Very short questions (less than 50 chars, no file references)
        if (trimmed.length < 50 && !trimmed.contains("file", ignoreCase = true) 
            && !trimmed.contains("read", ignoreCase = true)
            && !trimmed.contains("write", ignoreCase = true)
            && !trimmed.contains("code", ignoreCase = true)
            && !trimmed.contains("project", ignoreCase = true)
            && !trimmed.contains("directory", ignoreCase = true)
            && !trimmed.contains("list", ignoreCase = true)
            && !trimmed.contains("show", ignoreCase = true)
            && !trimmed.contains("output", ignoreCase = true)
            && !trimmed.contains("app", ignoreCase = true)) {
            return true
        }
        return false
    }

    /**
     * True when the task is "list project root folders" (or similar): we list the UI-selected project root
     * with one list_directory call and no LLM iterations.
     */
    private fun isListProjectRootTask(task: String): Boolean {
        val t = task.trim().lowercase()
        if (!t.contains("list")) return false
        return t.contains("project root") || t.contains("root folder") || t.contains("root directory") ||
            (t.contains("root") && t.contains("folder")) || (t.contains("root") && t.contains("directory"))
    }

    /**
     * Compute trivial arithmetic like "2+2" locally to avoid slow LLM/tool loop.
     * Supports: + - * / with integer operands.
     */
    private fun computeBasicArithmetic(task: String): String? {
        val trimmed = task.trim()
        // Match: number operator number (with optional whitespace)
        val match = Regex("""^\s*(\d+)\s*([+\-*/])\s*(\d+)\s*$""").find(trimmed) ?: run {
            // Debug: log if we're close but not matching
            if (trimmed.matches(Regex("""^\s*\d+\s*[+\-*/]\s*\d+.*"""))) {
                println("[IMPLEMENTATION] ⚠️ Arithmetic pattern detected but regex didn't match: '$trimmed'")
            }
            return null
        }
        val a = match.groupValues[1].toLongOrNull() ?: return null
        val op = match.groupValues[2]
        val b = match.groupValues[3].toLongOrNull() ?: return null

        return try {
            when (op) {
                "+" -> (a + b).toString()
                "-" -> (a - b).toString()
                "*" -> (a * b).toString()
                "/" -> {
                    if (b == 0L) "Division by zero"
                    else {
                        // If divides evenly, return integer; otherwise return decimal.
                        if (a % b == 0L) (a / b).toString()
                        else (a.toDouble() / b.toDouble()).toString()
                    }
                }
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }
    
    /**
     * Handle simple tasks directly without tools
     */
    private suspend fun handleSimpleTask(task: String): String {
        return try {
            // Use a simple prompt for direct answers
            val simplePrompt = """
                Answer the following question or solve the problem directly and concisely:
                $task
                
                Provide only the answer, no explanations unless asked.
            """.trimIndent()
            
            val agent = createKoogAgent(
                modelName = implementationModelWrapper.modelName,
                contextLength = implementationModelWrapper.maxContextLength.toLong()
            )
            
            println("[IMPLEMENTATION] Handling simple task with timeout (30s)")
            withTimeout(30000) { // 30 second timeout
                agent.run(simplePrompt)
            }
        } catch (e: TimeoutCancellationException) {
            println("[IMPLEMENTATION] ⚠️ Simple task timed out after 30s")
            "The request timed out. Please try again or rephrase your question."
        } catch (e: Exception) {
            println("[IMPLEMENTATION] ❌ Error handling simple task: ${e.message}")
            "Error: ${e.message}"
        }
    }
    
    /**
     * Execute agent with tool execution loop
     * Intercepts tool calls and executes them manually
     * Note: AIAgent is single-use, so we create a new one for each iteration
     */
    private suspend fun executeWithTools(
        initialPrompt: String,
        tools: KoogToolRegistryBuilder.FileAccessTools,
        maxIterations: Int = 10
    ): String {
        var conversationHistory = mutableListOf<String>()
        conversationHistory.add("User: $initialPrompt")
        var finalResponse = ""
        var toolCallCount = 0
        val calledPaths = mutableSetOf<String>()
        var lastSuccessfulListDirectoryOutput: String? = null
        var lastSuccessfulToolOutput: String? = null
        val toolCallKeys = mutableListOf<String>()
        
        for (iteration in 0 until maxIterations) {
            // Only log iteration start for first few or if verbose
            if (iteration < 3 || iteration == maxIterations - 1) {
                println("[IMPLEMENTATION] ===== Iteration ${iteration + 1}/$maxIterations =====")
            }
            
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
                
                // Only log prompt size for first iteration or if verbose
                if (iteration < 2) {
                    println("[IMPLEMENTATION] Calling agent.run() with prompt (${currentPrompt.length} chars)")
                }
                val agentResponse = try {
                    withTimeout(60000) { // 60 second timeout per iteration
                        agent.run(currentPrompt)
                    }
                } catch (e: TimeoutCancellationException) {
                    println("[IMPLEMENTATION] ⚠️ Agent execution timed out after 60s")
                    return "The agent execution timed out. The model may be stuck or taking too long. Please try a simpler task or check the model status."
                }
                println("[IMPLEMENTATION] Agent response length: ${agentResponse.length} chars")
                if (agentResponse.length < 500 && iteration < 2) {
                    println("[IMPLEMENTATION] Agent response preview: ${agentResponse.take(300)}...")
                }
                
                // Try to extract tool call first
                val toolCall = extractToolCall(agentResponse)
                
                // Check if response looks like a final answer (even if it contains JSON-like text)
                val looksLikeFinalAnswer = toolCall == null && 
                    agentResponse.length > 30 && 
                    !agentResponse.trim().startsWith("{") && 
                    !agentResponse.trim().startsWith("```json") &&
                    (agentResponse.contains(".") || agentResponse.contains("\n") || 
                     agentResponse.contains("folder") || agentResponse.contains("directory") ||
                     agentResponse.contains("root") || agentResponse.contains("build") ||
                     agentResponse.contains("src"))
                
                if (toolCall != null) {
                    println("[IMPLEMENTATION] 🔧 Tool call detected: ${toolCall.tool}")
                    val startTime = System.currentTimeMillis()
                    val callKey = toolCallKey(toolCall)
                    toolCallKeys.add(callKey)
                    val sameCallCount = toolCallKeys.count { it == callKey }
                    
                    // Track tool calls to detect loops
                    toolCallCount++
                    if (toolCall.tool == "list_directory" || toolCall.tool == "listDirectory") {
                        try {
                            val path = json.decodeFromJsonElement(ListDirectoryArgs.serializer(), toolCall.args).path
                            calledPaths.add(path)
                            // If we've called list_directory on similar paths multiple times, suggest stopping
                            val similarPathCount = calledPaths.count { 
                                it == path || it.startsWith(path) || path.startsWith(it) 
                            }
                            if (similarPathCount > 2 && iteration >= 2) {
                                conversationHistory.add("Note: You have already listed similar directories multiple times. Please provide a summary answer instead of continuing to list directories.")
                            }
                        } catch (e: Exception) {
                            // Ignore path extraction errors
                        }
                    }
                    
                    try {
                        val toolOutput = executeTool(toolCall, tools)
                        val duration = System.currentTimeMillis() - startTime
                        
                        // Our tool outputs are prefixed with ✅ / ❌
                        val success = toolOutput.trimStart().startsWith("✅")
                        
                        toolUsageTracker?.recordToolUsage(toolCall.tool, success, duration)
                        
                        if (success) {
                            println("[IMPLEMENTATION] ✅ Tool executed: ${toolCall.tool} (${duration}ms)")
                            lastSuccessfulToolOutput = toolOutput
                            if (toolCall.tool == "list_directory" || toolCall.tool == "listDirectory") {
                                lastSuccessfulListDirectoryOutput = toolOutput
                            }
                        } else {
                            println("[IMPLEMENTATION] ⚠️ Tool executed with errors: ${toolCall.tool} (${duration}ms)")
                        }
                        println("[IMPLEMENTATION] Tool output preview: ${toolOutput.take(200)}...")
                        
                        // Add tool output to conversation history for next iteration
                        // Include error context so agent can recover
                        conversationHistory.add("Tool Output: $toolOutput")
                        
                        // If same tool+args was called 3+ times, nudge to stop and use output as answer
                        if (sameCallCount >= 3) {
                            conversationHistory.add("Note: You have already called ${toolCall.tool} with the same arguments $sameCallCount times. Provide a final answer based on the tool output above instead of calling again.")
                        }
                        // If we've made many tool calls, suggest providing a final answer
                        if (toolCallCount >= 5 && iteration >= 3) {
                            conversationHistory.add("Note: You have gathered sufficient information from ${toolCallCount} tool calls. Please provide a final answer summarizing the results instead of making more tool calls.")
                        }
                        
                        // If tool failed critically, allow agent to retry or continue
                        if (!success && iteration < maxIterations - 1) {
                            conversationHistory.add("Note: The tool execution encountered an issue. You can try a different approach or continue with the information available.")
                        }
                    } catch (e: Exception) {
                        val duration = System.currentTimeMillis() - startTime
                        toolUsageTracker?.recordToolUsage(toolCall.tool, false, duration)
                        println("[IMPLEMENTATION] ❌ Tool execution error: ${e.message}")
                        
                        // Add error to conversation so agent can recover
                        val errorMessage = "Tool execution failed: ${e.message}. Please try a different approach or continue with available information."
                        conversationHistory.add("Tool Error: $errorMessage")
                        
                        // Don't break on tool errors - let agent try to recover
                        if (iteration >= maxIterations - 1) {
                            // Last iteration, return with error context
                            finalResponse = "Task incomplete. Last error: $errorMessage"
                            break
                        }
                    }
                } else {
                    // No tool call detected - check if it looks like a final answer
                    if (looksLikeFinalAnswer) {
                        // Looks like a natural language response, treat as final
                        finalResponse = agentResponse
                        if (iteration > 0) {
                            println("[IMPLEMENTATION] ✅ Final response received after ${iteration + 1} iteration(s)")
                        } else {
                            println("[IMPLEMENTATION] ✅ Final response received (no tool call detected)")
                        }
                        break
                    } else {
                        // Short or unclear response without tool call - might be incomplete
                        // If we've done multiple iterations, treat as final to avoid infinite loop
                        if (iteration >= 2) {
                            finalResponse = agentResponse
                            println("[IMPLEMENTATION] ⚠️ No tool call after ${iteration + 1} iterations, treating as final response")
                            break
                        } else {
                            // Early iteration, might be incomplete - add to history and continue
                            conversationHistory.add("Assistant: $agentResponse")
                            conversationHistory.add("Note: Your response was unclear. Please provide a complete answer or use a tool.")
                            continue
                        }
                    }
                }
            } catch (e: TimeoutCancellationException) {
                println("[IMPLEMENTATION] ⚠️ Iteration timed out: ${e.message}")
                if (iteration >= maxIterations - 1) {
                    return "The task timed out. Please try a simpler task or check the model status."
                }
                // Continue to next iteration
                conversationHistory.add("Note: Previous iteration timed out. Please provide a simpler response.")
            } catch (e: Exception) {
                println("[IMPLEMENTATION] ❌ Unexpected error: ${e.message}")
                e.printStackTrace()
                
                // Try to recover on non-critical errors
                val isCriticalError = e.message?.contains("Agent was already started") == true
                if (iteration < maxIterations - 1 && !isCriticalError) {
                    conversationHistory.add("Error occurred: ${e.message}. Please try again with a simpler approach.")
                    // Continue to next iteration
                } else {
                    // Critical error or last iteration
                    return "Error during execution: ${e.message}"
                }
            }
        }
        
        if (finalResponse.isEmpty()) {
            // For "list folders/root/directories" tasks, return the directory listing we got
            val taskLower = initialPrompt.lowercase()
            val isListFoldersTask = taskLower.contains("list") &&
                (taskLower.contains("folder") || taskLower.contains("root") || taskLower.contains("directory") || taskLower.contains("folders"))
            if (isListFoldersTask && lastSuccessfulListDirectoryOutput != null) {
                finalResponse = lastSuccessfulListDirectoryOutput.trim()
                println("[IMPLEMENTATION] ✅ Using list_directory output as final response (list-folders task)")
            }
            // If we had repeated identical tool calls, use last successful tool output as answer
            if (finalResponse.isEmpty() && lastSuccessfulToolOutput != null && toolCallKeys.isNotEmpty()) {
                val repeatedKey = toolCallKeys.groupingBy { k -> k }.eachCount().entries.firstOrNull { e -> e.value >= 3 }?.key
                if (repeatedKey != null) {
                    finalResponse = lastSuccessfulToolOutput.trim()
                    println("[IMPLEMENTATION] ✅ Using last tool output as final response (repeated tool call)")
                }
            }
            // Fallback: last assistant message or generic message
            if (finalResponse.isEmpty()) {
                val lastAgentResponse = conversationHistory.lastOrNull { it.startsWith("Assistant:") }
                if (lastAgentResponse != null) {
                    finalResponse = lastAgentResponse.removePrefix("Assistant: ").trim()
                    if (finalResponse.isEmpty()) {
                        finalResponse = "Reached maximum iterations. Please try a simpler task or rephrase your request."
                    }
                } else {
                    finalResponse = "Reached maximum iterations without a response. Please try a simpler task."
                }
            }
        }
        
        return finalResponse
    }
    
    /** Stable key for tool+args to detect repeated identical calls */
    private fun toolCallKey(toolCall: ToolCall): String {
        return when (toolCall.tool) {
            "read_file", "readFile" -> {
                val path = try {
                    json.decodeFromJsonElement(ReadFileArgs.serializer(), toolCall.args).path
                } catch (_: Exception) { "" }
                "read_file:$path"
            }
            "list_directory", "listDirectory" -> {
                val path = try {
                    json.decodeFromJsonElement(ListDirectoryArgs.serializer(), toolCall.args).path
                } catch (_: Exception) { "" }
                "list_directory:$path"
            }
            else -> "${toolCall.tool}:${toolCall.args}"
        }
    }
    
    /**
     * Extract tool call from agent response
     * Looks for JSON tool calls in the response
     */
    private fun extractToolCall(response: String): ToolCall? {
        // First, try to extract JSON from code blocks
        val codeBlockPattern = """```(?:json)?\s*(\{[\s\S]*?\})\s*```""".toRegex()
        val codeBlockMatch = codeBlockPattern.find(response)
        if (codeBlockMatch != null) {
            var jsonString = codeBlockMatch.groupValues[1]
            // Pre-fix Windows paths in code blocks - use string replacement for literal backslashes
            // Handle patterns like ".\src" -> "./src" (literal dot-backslash)
            // Replace .\ with ./ (literal string replacement, not regex)
            if (jsonString.contains(".\"")) {
                jsonString = jsonString.replace(".\"", "./")
            }
            // Replace remaining backslashes with forward slashes
            jsonString = jsonString.replace("\\", "/")
            return tryParseToolCall(jsonString, "code block")
        }
        
        // Try to find JSON object with "tool" field
        // Match from { to } including nested braces
        val jsonObjectPattern = """\{[^{}]*(?:\{[^{}]*\}[^{}]*)*\}""".toRegex(RegexOption.DOT_MATCHES_ALL)
        val matches = jsonObjectPattern.findAll(response)
        
        for (match in matches) {
            var jsonString = match.value
            // Pre-fix Windows paths before trying to parse
            // Handle patterns like ".\src" -> "./src" (literal dot-backslash)
            if (jsonString.contains(".\"")) {
                jsonString = jsonString.replace(".\"", "./")
            }
            // Replace remaining backslashes with forward slashes
            jsonString = jsonString.replace("\\", "/")
            
            // Check if it looks like a tool call
            if (jsonString.contains("\"tool\"") || jsonString.contains("'tool'")) {
                val toolCall = tryParseToolCall(jsonString, "json object")
                if (toolCall != null) return toolCall
            }
        }
        
        return null
    }
    
    /**
     * Try to parse a JSON string as a tool call
     * Handles common JSON issues like unescaped backslashes and malformed content
     */
    private fun tryParseToolCall(jsonString: String, source: String): ToolCall? {
        try {
            // First, try direct parsing
            val toolCall = json.decodeFromString<ToolCall>(jsonString)
            if (toolCall.tool.isNotBlank()) {
                println("[IMPLEMENTATION] ✅ Extracted tool call from $source: ${toolCall.tool}")
                return toolCall
            }
        } catch (e: Exception) {
            // If parsing fails, try to fix common issues
            println("[IMPLEMENTATION] ⚠️ Failed to parse from $source: ${e.message}")
            println("[IMPLEMENTATION] Attempting to fix JSON...")
            
            try {
                // Fix unescaped backslashes in paths (Windows paths)
                // Handle patterns like: ".\src\main" -> "./src/main"
                var fixedJson = jsonString
                    .replace("""\.\\""", "./")  // Fix .\ paths first
                    .replace("""\\""", "/")  // Replace remaining backslashes with forward slashes
                    .replace("""\./""", "./")  // Normalize ./
                
                // Try parsing again
                val toolCall = json.decodeFromString<ToolCall>(fixedJson)
                if (toolCall.tool.isNotBlank()) {
                    println("[IMPLEMENTATION] ✅ Extracted tool call (after fixing): ${toolCall.tool}")
                    return toolCall
                }
            } catch (e2: Exception) {
                println("[IMPLEMENTATION] ⚠️ Still failed after fixing: ${e2.message}")
            }
            
            // Try manual extraction - extract tool name and args separately
            try {
                val toolMatch = """["']tool["']\s*:\s*["']([^"']+)["']""".toRegex().find(jsonString)
                if (toolMatch != null) {
                    val toolName = toolMatch.groupValues[1]
                    println("[IMPLEMENTATION] Found tool name: $toolName")
                    
                    // Try to extract args - look for the args field
                    // Find the args object, but be careful about nested content
                    val argsStartPattern = """["']args["']\s*:\s*\{""".toRegex()
                    val argsStart = argsStartPattern.find(jsonString)
                    
                    if (argsStart != null) {
                        val argsStartPos = argsStart.range.last + 1
                        // Find the matching closing brace
                        var braceCount = 1
                        var pos = argsStartPos
                        var argsEndPos = -1
                        
                        while (pos < jsonString.length && braceCount > 0) {
                            when (jsonString[pos]) {
                                '{' -> braceCount++
                                '}' -> {
                                    braceCount--
                                    if (braceCount == 0) {
                                        argsEndPos = pos
                                        break
                                    }
                                }
                            }
                            pos++
                        }
                        
                        if (argsEndPos > argsStartPos) {
                            val argsJson = jsonString.substring(argsStartPos, argsEndPos)
                            // Try to create a minimal valid JSON object
                            val minimalArgs = try {
                                // Try to parse as-is first
                                json.parseToJsonElement("{$argsJson}")
                            } catch (e: Exception) {
                                // If that fails, try to extract just the path/content field
                                // Strict: value between quotes; lenient: value truncated (missing closing quote before })
                                val pathMatch = """["']path["']\s*:\s*["']([^"']*)["']""".toRegex().find(argsJson)
                                    ?: """["']path["']\s*:\s*["']([^"'}\]]*)""".toRegex().find(argsJson)
                                val contentMatch = """["']content["']\s*:\s*["']([^"']*)["']""".toRegex(RegexOption.DOT_MATCHES_ALL).find(argsJson)
                                
                                when (toolName) {
                                    "read_file", "readFile", "list_directory", "listDirectory" -> {
                                        if (pathMatch != null) {
                                            // Extract raw path value (may contain backslashes like .\src, or be truncated like "./)
                                            var path = pathMatch.groupValues[1].trim()
                                            if (path.isEmpty() || path == "." || path == "./") path = "."
                                            // Normalize Windows paths using string replacement (not regex)
                                            // Handle .\src -> ./src
                                            if (path.startsWith(".") && path.length > 1 && path[1] == '\\') {
                                                path = "./" + path.substring(2)
                                            }
                                            // Replace all remaining backslashes with forward slashes
                                            path = path.replace("\\", "/")
                                            // Normalize any double slashes
                                            path = path.replace("//", "/")
                                            // Now escape for JSON (only quotes need escaping after normalization)
                                            val escapedPath = path.replace("\"", "\\\"")
                                            json.parseToJsonElement("""{"path": "$escapedPath"}""")
                                        } else {
                                            null
                                        }
                                    }
                                    "write_file", "writeFile" -> {
                                        if (pathMatch != null && contentMatch != null) {
                                            // Extract and normalize path
                                            var path = pathMatch.groupValues[1]
                                            // Handle .\src -> ./src
                                            if (path.startsWith(".") && path.length > 1 && path[1] == '\\') {
                                                path = "./" + path.substring(2)
                                            }
                                            path = path.replace("\\", "/")
                                            path = path.replace("//", "/")
                                            val escapedPath = path.replace("\"", "\\\"")
                                            
                                            // Extract and escape content
                                            var content = contentMatch.groupValues[1]
                                            // Content may have escaped sequences, handle them
                                            content = content.replace("\\\"", "\"")  // Unescape quotes
                                            content = content.replace("\\\\", "\\")  // Unescape backslashes
                                            content = content.replace("\\n", "\n")  // Unescape newlines
                                            // Re-escape for JSON
                                            val escapedContent = content
                                                .replace("\\", "\\\\")
                                                .replace("\"", "\\\"")
                                                .replace("\n", "\\n")
                                            json.parseToJsonElement("""{"path": "$escapedPath", "content": "$escapedContent"}""")
                                        } else {
                                            null
                                        }
                                    }
                                    else -> null
                                }
                            }
                            
                            if (minimalArgs != null) {
                                val toolCall = ToolCall(tool = toolName, args = minimalArgs)
                                println("[IMPLEMENTATION] ✅ Extracted tool call (manual): ${toolCall.tool}")
                                return toolCall
                            }
                        }
                    }
                }
            } catch (e3: Exception) {
                println("[IMPLEMENTATION] ⚠️ Manual extraction failed: ${e3.message}")
            }
        }
        
        return null
    }
    
    /**
     * Execute a tool call
     */
    private fun executeTool(toolCall: ToolCall, tools: KoogToolRegistryBuilder.FileAccessTools): String {
        return try {
            when (toolCall.tool) {
                "read_file", "readFile" -> {
                    val args = json.decodeFromJsonElement(ReadFileArgs.serializer(), toolCall.args)
                    val output = tools.readFile(args.path)
                    if (output.success && output.content != null) {
                        "✅ File read successfully:\n${output.content}"
                    } else {
                        "❌ Error reading file: ${output.error}"
                    }
                }
                "write_file", "writeFile" -> {
                    val args = json.decodeFromJsonElement(WriteFileArgs.serializer(), toolCall.args)
                    val output = tools.writeFile(args.path, args.content)
                    if (output.success) {
                        "✅ File written successfully: ${output.path}"
                    } else {
                        "❌ Error writing file: ${output.error}"
                    }
                }
                "list_directory", "listDirectory" -> {
                    val args = json.decodeFromJsonElement(ListDirectoryArgs.serializer(), toolCall.args)
                    val output = tools.listDirectory(args.path)
                    if (output.success) {
                        "✅ Directory listing:\n${output.entries.joinToString("\n") { "${if (it.isDirectory) "📁" else "📄"} ${it.path}" }}"
                    } else {
                        "❌ Error listing directory: ${output.error}"
                    }
                }
                "regex_search", "regexSearch" -> {
                    val args = json.decodeFromJsonElement(RegexSearchArgs.serializer(), toolCall.args)
                    val output = tools.regexSearch(args.pattern, args.directory, args.filePattern)
                    if (output.success) {
                        "✅ Found ${output.matches.size} matches:\n${output.matches.take(10).joinToString("\n") { "${it.file}:${it.line} - ${it.match}" }}"
                    } else {
                        "❌ Error searching: ${output.error}"
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
    
    override suspend fun processStreaming(task: String, context: TaskContext): Flow<AgentResponseChunk> = flow {
        println("[IMPLEMENTATION] Processing streaming task: '$task'")
        println("[IMPLEMENTATION] Project path from context: ${context.projectPath ?: "null"}")
        
        // Fast-path: simple arithmetic locally (no LLM/tool loop)
        computeBasicArithmetic(task)?.let { arithmeticResult ->
            println("[IMPLEMENTATION] ✅ Fast arithmetic result: $arithmeticResult")
            emit(AgentResponseChunk.Text(arithmeticResult))
            emit(AgentResponseChunk.Complete)
            return@flow
        }

        // Simple tasks: keep behavior consistent with non-streaming process()
        if (isSimpleTask(task)) {
            val simpleResult = handleSimpleTask(task)
            emit(AgentResponseChunk.Text(simpleResult))
            emit(AgentResponseChunk.Complete)
            return@flow
        }

        // Require a project selected from the Projects card (DB); do not use current working directory
        if (context.projectPath == null) {
            val msg = "No project selected. Please add and select a project from the Projects card so file operations run in the correct project."
            println("[IMPLEMENTATION] $msg")
            emit(AgentResponseChunk.Text(msg))
            emit(AgentResponseChunk.Complete)
            return@flow
        }

        // Use project root from context only (project selected from Projects card / DB)
        val projectRoot = context.projectPath!!
        println("[IMPLEMENTATION] Using project root for tools (streaming): '$projectRoot'")
        val tools = KoogToolRegistryBuilder.FileAccessTools(projectRoot)
        
        // Fast-path: "list project root folders" = one list_directory on project root, no LLM
        if (isListProjectRootTask(task)) {
            println("[IMPLEMENTATION] Fast-path: listing project root (UI-selected project) without LLM")
            emit(AgentResponseChunk.Progress(50, "Listing project root"))
            val output = tools.listDirectory(".")
            val result = if (output.success) {
                "✅ Directory listing:\n${output.entries.joinToString("\n") { "${if (it.isDirectory) "📁" else "📄"} ${it.path}" }}"
            } else {
                "❌ Error listing directory: ${output.error}"
            }
            emit(AgentResponseChunk.Text(result))
            emit(AgentResponseChunk.Progress(100, "Done"))
            emit(AgentResponseChunk.Complete)
            return@flow
        }
        
        emit(AgentResponseChunk.Progress(10, "Analyzing implementation requirements"))
        emit(AgentResponseChunk.Progress(30, "Gathering context from workspace"))
        
        val prompt = buildPromptWithContext(task, context)
        
        emit(AgentResponseChunk.Progress(50, "Generating implementation with tools"))
        
        // Execute with tool loop (creates new agents for each iteration)
        val result = executeWithTools(prompt, tools, maxIterations = 10)
        
        // Stream the result in chunks
        result.chunked(100).forEach { chunk ->
            emit(AgentResponseChunk.Text(chunk))
        }
        
        emit(AgentResponseChunk.Progress(100, "Implementation complete"))
        emit(AgentResponseChunk.Complete)
    }
    
    override protected fun getSystemPrompt(): String {
        return """
            You are an Implementation Agent specialized in:
            - Writing actual code implementations
            - Fixing bugs
            - Adding features
            - Refactoring code
            - Code quality and best practices
            
            You have access to file access tools. To use them, output a JSON object in this exact format:
            {"tool": "tool_name", "args": {...}}
            
            Available tools:
            1. **read_file**: Read a file's content
               Example: {"tool": "read_file", "args": {"path": "src/main/kotlin/Main.kt"}}
            
            2. **write_file**: Write content to a file
               Example: {"tool": "write_file", "args": {"path": "src/main/kotlin/NewFile.kt", "content": "package com.example\n\nfun main() {\n    println(\"Hello\")\n}"}}
            
            3. **list_directory**: List directory contents
               Example: {"tool": "list_directory", "args": {"path": "src/main/kotlin"}}
            
            4. **regex_search**: Search for patterns in files
               Example: {"tool": "regex_search", "args": {"pattern": "class.*Agent", "directory": "src", "filePattern": "*.kt"}}
            
            CRITICAL INSTRUCTIONS:
            - For simple math/questions (like "2+2"), answer directly: "4"
            - For code tasks, you MUST use tools to actually read/write files. Do not just describe what to do.
            - When you need to read a file, output the JSON tool call. The tool will execute and return the file content.
            - When you need to write code, use write_file tool to actually create/modify files.
            - Always use tools to perform actions, not just describe them.
            - Output the tool call JSON on its own line, clearly separated from any explanation.
        """.trimIndent()
    }
}
