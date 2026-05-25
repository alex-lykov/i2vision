/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.agent.koog

import com.i2vision.agent.*
import com.i2vision.agent.config.ParserType
import com.i2vision.agent.config.ParsingConfig
import com.i2vision.agent.tools.DiscoveryCache
import com.i2vision.agent.tools.InstantContextProvider
import com.i2vision.agent.tools.TaskType

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
            instantContextProvider.getContext(file = it, task = TaskType.fromString(task))
        }
        
        // Use discovery cache for project-wide context
        val discovery = discoveryCache.get(workspaceRoot)
        
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
 */
class PromptBuilder(
    private val promptTemplate: com.i2vision.agent.config.KoogPromptConfig
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
            if (iteration > 1 && history.lastOrNull()?.parsed?.isMalformed == true) {
                appendLine("\n## Important")
                appendLine("Your previous output was malformed. Please follow the expected format.")
            }
        }
    }
    
    /**
     * Get the base system prompt with variables rendered.
     * 
     * @param dynamicVariables Runtime variables (workspaceRoot, currentFile, etc.)
     * @return Rendered system prompt
     */
    fun getBasePrompt(dynamicVariables: Map<String, String> = emptyMap()): String =
        promptTemplate.renderSystemPrompt(dynamicVariables)
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
            val map = parseJsonToMap(jsonStr)
            val toolName = map["tool"]?.toString() ?: map["name"]?.toString() ?: return null
            val args = (map["args"] as? Map<*, *>)?.filterKeys { it != null }?.mapKeys { it.key.toString() } ?: emptyMap()
            ToolCall(name = toolName, args = args as Map<String, Any>)
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Parse XML arguments.
     */
    private fun parseXmlArgs(argsXml: String): Map<String, Any> {
        val argRegex = Regex("<arg\\s+name=\"([^\"]+)\"\\s*>(.*?)</arg>", RegexOption.DOT_MATCHES_ALL)
        return argRegex.findAll(argsXml).associate { match ->
            match.groupValues[1] to match.groupValues[2].trim()
        }
    }
    
    /**
     * Simple JSON parser for tool call arguments.
     */
    private fun parseJsonToMap(jsonStr: String): Map<String, Any?> {
        // Simple JSON parsing - in production use a proper JSON library
        val map = mutableMapOf<String, Any?>()
        val cleaned = jsonStr.trim().trimStart('{').trimEnd('}')
        
        // Extract key-value pairs
        val pairs = cleaned.split(",")
        for (pair in pairs) {
            val colonIndex = pair.indexOf(':')
            if (colonIndex > 0) {
                val key = pair.substring(0, colonIndex).trim().trim('"')
                val value = pair.substring(colonIndex + 1).trim()
                map[key] = parseJsonValue(value)
            }
        }
        
        return map
    }
    
    /**
     * Parse a JSON value.
     */
    private fun parseJsonValue(value: String): Any? {
        return when {
            value == "null" -> null
            value == "true" -> true
            value == "false" -> false
            value.startsWith("\"") && value.endsWith("\"") -> value.substring(1, value.length - 1)
            value.toIntOrNull() != null -> value.toInt()
            value.toLongOrNull() != null -> value.toLong()
            value.toDoubleOrNull() != null -> value.toDouble()
            else -> value
        }
    }
}

/**
 * Invokes the LLM with a prompt.
 * 
 * This component handles:
 * - Model selection
 * - Parameter configuration (temperature, top_p, etc.)
 * - Retry logic
 * - Timeout handling
 * 
 * @property modelProvider Model provider implementation
 * @property config LLM configuration
 */
class ModelInvoker(
    private val modelProvider: com.i2vision.llm.ModelProvider,
    private val config: com.i2vision.agent.config.KoogLlmConfig
) {
    
    /**
     * Invoke the model with a prompt.
     * 
     * @param prompt The prompt to send
     * @return Raw model output
     */
    suspend fun invoke(prompt: String): String {
        // TODO: Implement model invocation with retries and timeouts
        return "Model response placeholder"
    }
}

/**
 * Executes tool calls.
 * 
 * This component:
 * - Looks up tools in the registry
 * - Validates arguments
 * - Executes the tool
 * - Handles timeouts and errors
 * 
 * @property toolRegistry Tool registry implementation
 */
class ToolExecutor(
    private val toolRegistry: com.i2vision.llm.ToolRegistry
) {
    
    /**
     * Execute a tool call.
     * 
     * @param toolName Name of the tool to execute
     * @param args Tool arguments
     * @param timeoutSeconds Timeout in seconds
     * @return Tool execution result
     */
    suspend fun execute(
        toolName: String,
        args: Map<String, Any>,
        timeoutSeconds: Long
    ): ToolExecutionResult {
        // TODO: Implement tool execution
        return ToolExecutionResult(
            isSuccess = false,
            output = "Tool execution not implemented"
        )
    }
}

/**
 * Formats final agent responses.
 * 
 * This component:
 * - Applies formatting rules
 * - Includes/excludes reasoning traces
 * - Formats code blocks
 * - Applies compact mode if enabled
 * 
 * @property config Agent configuration
 */
class ResponseFormatter(
    private val config: com.i2vision.agent.config.AgentPromptConfiguration
) {
    
    /**
     * Format the final response.
     * 
     * @param state Agent state with final result
     * @return Formatted response
     */
    fun format(state: AgentState): String {
        // TODO: Implement response formatting
        return state.finalText ?: "No response generated"
    }
}

/**
 * Decision types for the DECIDE node.
 */
enum class Decision {
    DONE,
    EXECUTE_TOOL,
    CLARIFY,
    REFLECT
}

/**
 * Decide the next action based on parsed output.
 * 
 * @param state Current agent state
 * @return Decision for next action
 */
fun decide(state: AgentState): Decision {
    val parsed = state.parsedOutput ?: return Decision.DONE
    
    // Check if task is complete
    if (parsed.isTaskComplete()) {
        return Decision.DONE
    }
    
    // Check if clarification is needed
    if (parsed.needsClarification()) {
        return Decision.CLARIFY
    }
    
    // Check if tool call is present
    if (parsed.hasToolCall()) {
        return Decision.EXECUTE_TOOL
    }
    
    // Default to reflect (if reflection enabled) or done
    return Decision.REFLECT
}
