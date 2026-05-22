/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.agent.koog

import com.i2vision.agent.*
import com.i2vision.agent.config.KoogPromptConfig
import com.i2vision.agent.config.ParsingConfig

/**
 * Individual node logic components extracted for testability and reuse.
 * 
 * These components are used by I2VisionStrategyGraph to implement
 * the graph nodes. Each component is a single responsibility class
 * that can be tested independently.
 */

/**
 * Enriches task context with i2vision instant context and discovery cache.
 * 
 * This component injects semantic context from:
 * - i2vision-instant (file-level symbols, flows, business rules)
 * - Discovery cache (project-wide architecture, contracts)
 * 
 * @property instantContextProvider Provides file-level semantic context
 * @property discoveryCache Provides project-wide context
 */
class ContextEnricher(
    private val instantContextProvider: InstantContextProvider,
    private val discoveryCache: DiscoveryCache
) {
    
    /**
     * Enrich task with available context.
     * 
     * @param task The task description
     * @param workspaceRoot Workspace root path
     * @param currentFile Currently active file (if any)
     * @param vslfcLayer VSLFC layer for context filtering
     * @return Enriched context with symbols, files, architecture
     */
    suspend fun enrich(
        task: String,
        workspaceRoot: String,
        currentFile: String?,
        vslfcLayer: VslfcLayer
    ): EnrichedContext {
        // Use i2vision-instant for semantic context
        val fileContext = currentFile?.let {
            instantContextProvider.getContext(file = it, task = task)
        }
        
        // Use discovery cache for project-wide context
        val discovery = discoveryCache.getOrDiscover(workspaceRoot)
        
        return EnrichedContext(
            symbols = fileContext?.symbols ?: emptyList(),
            relatedFiles = fileContext?.relatedFiles ?: emptyList(),
            architecture = discovery?.architecture,
            contracts = discovery?.contracts,
            flows = fileContext?.flows ?: emptyList(),
            businessRules = fileContext?.businessRules ?: emptyList(),
            layer = vslfcLayer
        )
    }
}

/**
 * Builds iteration prompts with context and history.
 * 
 * This component constructs the prompt for each iteration by:
 * - Starting with the base system prompt
 * - Adding enriched context (symbols, architecture, etc.)
 * - Appending conversation history
 * - Including iteration hints and constraints
 * 
 * @property promptTemplate Base prompt template
 * @property config Prompt configuration
 */
class PromptBuilder(
    private val promptTemplate: KoogPromptConfig,
    private val config: AgentPromptConfiguration
) {
    
    /**
     * Build prompt for a specific iteration.
     * 
     * @param basePrompt Base system prompt
     * @param iteration Current iteration number (1-based)
     * @param maxIterations Maximum iterations allowed
     * @param history Conversation history
     * @param enrichedContext Enriched context from ContextEnricher
     * @return Complete iteration prompt
     */
    fun buildIterationPrompt(
        basePrompt: String,
        iteration: Int,
        maxIterations: Int,
        history: List<IterationStep>,
        enrichedContext: EnrichedContext
    ): String {
        return buildString {
            // Base system prompt
            appendLine(basePrompt)
            
            // Add enriched context
            if (enrichedContext.isNotEmpty()) {
                appendLine("\n## Available Context")
                appendLine(enrichedContext.toPromptSection())
            }
            
            // Add conversation history
            if (history.isNotEmpty()) {
                appendLine("\n## Previous Steps")
                history.takeLast(5).forEach { step ->
                    appendLine(step.toPromptSection())
                }
            }
            
            // Add iteration hint
            appendLine("\n## Current Step ($iteration/$maxIterations)")
            appendLine("Continue from where you left off.")
            
            if (iteration == maxIterations) {
                appendLine("⚠️ This is your last iteration. Provide a final response.")
            }
            
            // Add kickstart hint if needed
            if (iteration > 1 && history.lastOrNull()?.parsed?.isMalformed() == true) {
                appendLine("\n## Important")
                appendLine("Your previous output was malformed. Please follow the expected format.")
            }
        }
    }
    
    /**
     * Get the base system prompt with variables rendered.
     */
    fun getBasePrompt(variables: Map<String, String> = emptyMap()): String =
        promptTemplate.systemPrompt
}

/**
 * Parses model output into structured format.
 * 
 * This component extracts:
 * - Reasoning/prose text
 * - Tool calls (if any)
 * - Completion signals
 * - Clarification requests
 * 
 * It supports multiple parsing strategies:
 * - Header-based: `tool_call:` JSON header
 * - Naked JSON: `{"tool":"...", "args":{...}}`
 * - XML invoke: `<invoke name="tool">...</invoke>`
 * - Quoted JSON: Escaped string containing JSON
 * 
 * @property parsingConfig Parsing configuration
 */
class OutputParser(
    private val parsingConfig: ParsingConfig
) {
    
    /**
     * Parse raw model output into structured format.
     * 
     * @param rawOutput Raw text from the model
     * @return Parsed output with reasoning and tool calls
     */
    fun parse(rawOutput: String): ParsedOutput {
        // Try each parser in configured order
        for (parserType in parsingConfig.enabledParsers) {
            val result = when (parserType) {
                ParserType.HEADER -> parseHeaderFormat(rawOutput)
                ParserType.NAKED_JSON -> parseNakedJson(rawOutput)
                ParserType.XML_INVOKE -> parseXmlInvoke(rawOutput)
                ParserType.QUOTED_JSON -> parseQuotedJson(rawOutput)
            }
            if (result != null) return result
        }
        
        // Fallback: treat as plain text response
        return ParsedOutput(
            rawText = rawOutput,
            reasoning = rawOutput,
            toolCall = null,
            isComplete = true,
            isMalformed = false
        )
    }
    
    /**
     * Parse header-based format.
     * 
     * Expected format:
     * ```
     * reasoning: I need to read the file first
     * tool_call: {"tool": "read_file", "args": {"path": "..."}}
     * ```
     */
    private fun parseHeaderFormat(rawOutput: String): ParsedOutput? {
        val lines = rawOutput.lines()
        var reasoning = StringBuilder()
        var toolCallJson: String? = null
        
        for (line in lines) {
            when {
                line.startsWith("reasoning:", ignoreCase = true) -> {
                    reasoning.appendLine(line.substringAfter("reasoning:").trim())
                }
                line.startsWith("tool_call:", ignoreCase = true) -> {
                    toolCallJson = line.substringAfter("tool_call:").trim()
                }
                else -> {
                    if (toolCallJson == null) {
                        reasoning.appendLine(line)
                    }
                }
            }
        }
        
        val toolCall = toolCallJson?.let { parseToolCallJson(it) }
        
        return ParsedOutput(
            rawText = rawOutput,
            reasoning = reasoning.toString().trim(),
            toolCall = toolCall,
            isComplete = toolCall == null,
            isMalformed = false
        )
    }
    
    /**
     * Parse naked JSON format.
     * 
     * Expected format:
     * ```
     * {"tool": "read_file", "args": {"path": "..."}}
     * ```
     */
    private fun parseNakedJson(rawOutput: String): ParsedOutput? {
        val jsonStart = rawOutput.indexOf('{')
        if (jsonStart == -1) return null
        
        val jsonEnd = rawOutput.lastIndexOf('}')
        if (jsonEnd == -1 || jsonEnd <= jsonStart) return null
        
        val jsonStr = rawOutput.substring(jsonStart, jsonEnd + 1)
        val toolCall = parseToolCallJson(jsonStr) ?: return null
        
        val prose = rawOutput.substring(0, jsonStart).trim()
        
        return ParsedOutput(
            rawText = rawOutput,
            reasoning = prose.ifEmpty { null },
            toolCall = toolCall,
            isComplete = false,
            isMalformed = false
        )
    }
    
    /**
     * Parse XML invoke format.
     * 
     * Expected format:
     * ```
     * <invoke name="read_file">
     *   <arg name="path">src/main.kt</arg>
     * </invoke>
     * ```
     */
    private fun parseXmlInvoke(rawOutput: String): ParsedOutput? {
        val invokeRegex = Regex("<invoke\\s+name=\"([^\"]+)\"\\s*>(.*?)</invoke>", RegexOption.DOT_MATCHES_ALL)
        val match = invokeRegex.find(rawOutput) ?: return null
        
        val toolName = match.groupValues[1]
        val argsXml = match.groupValues[2]
        
        val args = parseXmlArgs(argsXml)
        
        val prose = rawOutput.replace(match.value, "").trim()
        
        return ParsedOutput(
            rawText = rawOutput,
            reasoning = prose.ifEmpty { null },
            toolCall = ToolCall(name = toolName, args = args),
            isComplete = false,
            isMalformed = false
        )
    }
    
    /**
     * Parse quoted JSON format.
     * 
     * Expected format:
     * ```
     * "{\"tool\": \"read_file\", \"args\": {\"path\": \"...\"}}"
     * ```
     */
    private fun parseQuotedJson(rawOutput: String): ParsedOutput? {
        val quotedJsonRegex = Regex("\"(\\{.*\\})\"")
        val match = quotedJsonRegex.find(rawOutput) ?: return null
        
        val jsonStr = match.groupValues[1].replace("\\\"", "\"")
        val toolCall = parseToolCallJson(jsonStr) ?: return null
        
        val prose = rawOutput.replace(match.value, "").trim()
        
        return ParsedOutput(
            rawText = rawOutput,
            reasoning = prose.ifEmpty { null },
            toolCall = toolCall,
            isComplete = false,
            isMalformed = false
        )
    }
    
    /**
     * Parse tool call JSON.
     */
    private fun parseToolCallJson(jsonStr: String): ToolCall? {
        return try {
            // Simple JSON parsing (in production, use kotlinx.serialization)
            val toolRegex = Regex("\"tool\"\\s*:\\s*\"([^\"]+)\"")
            val argsRegex = Regex("\"args\"\\s*:\\s*(\\{[^}]+\\})")
            
            val toolMatch = toolRegex.find(jsonStr) ?: return null
            val toolName = toolMatch.groupValues[1]
            
            val argsMatch = argsRegex.find(jsonStr)
            val args = argsMatch?.let { parseSimpleJson(it.groupValues[1]) } ?: emptyMap()
            
            ToolCall(name = toolName, args = args)
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Parse simple JSON object to Map.
     */
    private fun parseSimpleJson(jsonStr: String): Map<String, Any> {
        val result = mutableMapOf<String, Any>()
        
        // Simple key-value parsing (in production, use proper JSON parser)
        val kvRegex = Regex("\"([^\"]+)\"\\s*:\\s*\"([^\"]+)\"")
        for (match in kvRegex.findAll(jsonStr)) {
            result[match.groupValues[1]] = match.groupValues[2]
        }
        
        return result
    }
    
    /**
     * Parse XML arguments to Map.
     */
    private fun parseXmlArgs(xmlStr: String): Map<String, Any> {
        val result = mutableMapOf<String, Any>()
        
        val argRegex = Regex("<arg\\s+name=\"([^\"]+)\"\\s*>([^<]*)</arg>")
        for (match in argRegex.findAll(xmlStr)) {
            result[match.groupValues[1]] = match.groupValues[2]
        }
        
        return result
    }
}

/**
 * Invokes the LLM model.
 * 
 * This component handles:
 * - Model invocation with retries
 * - Timeout handling
 * - Streaming support
 * - Reflection prompts
 * 
 * @property modelProvider Model provider interface
 * @property config LLM configuration
 */
class ModelInvoker(
    private val modelProvider: ModelProvider,
    private val config: KoogLlmConfig
) {
    
    /**
     * Invoke the model with a prompt.
     * 
     * @param prompt The prompt to send
     * @return Model response text
     */
    suspend fun invoke(prompt: String): String {
        var lastError: Exception? = null
        
        for (attempt in 1..config.retries) {
            try {
                return modelProvider.generate(
                    prompt = prompt,
                    temperature = config.temperature,
                    topP = config.topP,
                    topK = config.topK,
                    maxTokens = config.maxTokens,
                    timeoutSeconds = config.timeoutSeconds
                )
            } catch (e: Exception) {
                lastError = e
                if (attempt < config.retries) {
                    // Backoff before retry
                    kotlinx.coroutines.delay(1000L * attempt)
                }
            }
        }
        
        throw lastError ?: IllegalStateException("Model invocation failed")
    }
    
    /**
     * Invoke model for reflection.
     * 
     * @param task The original task
     * @param toolResult Result from tool execution
     * @param history Conversation history
     * @return Reflection text
     */
    suspend fun reflect(
        task: String,
        toolResult: ToolExecutionResult,
        history: List<IterationStep>
    ): String {
        val reflectionPrompt = buildString {
            appendLine("Reflect on the tool execution result.")
            appendLine("\n## Task")
            appendLine(task)
            appendLine("\n## Tool Result")
            appendLine(toolResult.output ?: "Error: ${toolResult.error}")
            appendLine("\n## History")
            history.takeLast(3).forEach { step ->
                appendLine("- Iteration ${step.iteration}: ${step.rawOutput.take(100)}...")
            }
            appendLine("\n## Reflection")
            appendLine("What did you learn? What should you do next?")
        }
        
        return invoke(reflectionPrompt)
    }
}

/**
 * Executes tool calls.
 * 
 * This component handles:
 * - Tool dispatching by name
 * - Timeout handling
 * - Result formatting
 * - Error handling
 * 
 * @property toolRegistry Registry of available tools
 */
class ToolExecutor(
    private val toolRegistry: ToolRegistry
) {
    
    /**
     * Execute a tool call.
     * 
     * @param toolName Name of the tool to execute
     * @param args Tool arguments
     * @param timeoutSeconds Execution timeout
     * @return Tool execution result
     */
    suspend fun execute(
        toolName: String,
        args: Map<String, Any>,
        timeoutSeconds: Long
    ): ToolExecutionResult {
        return try {
            kotlinx.coroutines.withTimeout(timeoutSeconds * 1000) {
                val result = toolRegistry.execute(toolName, args)
                ToolExecutionResult(
                    toolName = toolName,
                    args = args,
                    isSuccess = true,
                    output = result.toString(),
                    signal = detectSignal(result)
                )
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            ToolExecutionResult(
                toolName = toolName,
                args = args,
                isSuccess = false,
                output = null,
                error = "Tool execution timed out after ${timeoutSeconds}s",
                signal = null
            )
        } catch (e: Exception) {
            ToolExecutionResult(
                toolName = toolName,
                args = args,
                isSuccess = false,
                output = null,
                error = e.message ?: "Tool execution failed",
                signal = null
            )
        }
    }
    
    /**
     * Detect task completion signals in tool output.
     */
    private fun detectSignal(result: Any): String? {
        val resultStr = result.toString().lowercase()
        return when {
            "task_complete" in resultStr || "task_stop" in resultStr -> "TASK_STOP"
            else -> null
        }
    }
}

/**
 * Formats final agent responses.
 * 
 * This component constructs the final response by:
 * - Summarizing the execution history
 * - Including tool call results
 * - Formatting according to layer-specific rules
 * - Adding reasoning trace (if configured)
 * 
 * @property config Formatting configuration
 */
class ResponseFormatter(
    private val config: AgentPromptConfiguration
) {
    
    /**
     * Format final response.
     * 
     * @param task Original task
     * @param history Execution history
     * @param toolCalls Tool calls made
     * @param outcome Task outcome
     * @param layer VSLFC layer
     * @return Formatted response
     */
    fun format(
        task: String,
        history: List<IterationStep>,
        toolCalls: List<ToolCallRecord>,
        outcome: Outcome,
        layer: VslfcLayer
    ): AgentResponse {
        val responseText = buildString {
            // Outcome header
            appendLine("## ${outcome.name}")
            appendLine()
            
            // Summary
            appendLine("### Summary")
            appendLine(formatSummary(task, history, outcome))
            appendLine()
            
            // Tool calls (if any)
            if (toolCalls.isNotEmpty()) {
                appendLine("### Tool Calls")
                toolCalls.forEach { call ->
                    appendLine("- **${call.toolName}**: ${if (call.success) "Success" : "Failed"}")
                    if (config.formatting.includeToolCallDetails) {
                        appendLine("  - Args: ${call.args}")
                        if (call.output != null) {
                            appendLine("  - Output: ${call.output.take(200)}")
                        }
                    }
                }
                appendLine()
            }
            
            // Reasoning trace (if configured)
            if (config.formatting.includeReasoningTrace && history.isNotEmpty()) {
                appendLine("### Reasoning Trace")
                history.takeLast(3).forEach { step ->
                    appendLine("#### Iteration ${step.iteration}")
                    appendLine(step.parsed.reasoning?.take(300) ?: "No reasoning provided")
                    appendLine()
                }
            }
            
            // Final text
            appendLine("### Response")
            appendLine(formatFinalText(history, layer))
        }
        
        return AgentResponse(
            requestId = "response",
            agentId = "formatter",
            outcome = outcome,
            finalText = responseText,
            iterations = history.size,
            toolCalls = toolCalls,
            durationMs = 0
        )
    }
    
    /**
     * Format execution summary.
     */
    private fun formatSummary(task: String, history: List<IterationStep>, outcome: Outcome): String {
        return when (outcome) {
            Outcome.SUCCESS -> "Task completed successfully in ${history.size} iterations."
            Outcome.ITERATION_LIMIT -> "Reached maximum iterations (${history.size}) without completing the task."
            Outcome.CANCELLED -> "Task was cancelled by the user."
            Outcome.ERROR -> "Task failed with an error."
            Outcome.NEEDS_CLARIFICATION -> "Task requires additional clarification from the user."
        }
    }
    
    /**
     * Format final text from history.
     */
    private fun formatFinalText(history: List<IterationStep>, layer: VslfcLayer): String {
        return history.lastOrNull()?.parsed?.reasoning ?: "No response generated."
    }
}
