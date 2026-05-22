/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.agent.koog

import com.i2vision.agent.*

/**
 * Mutable state maintained during graph execution.
 * 
 * This class holds all state that changes as the agent progresses
 * through the strategy graph. It's passed between nodes and updated
 * at each step.
 * 
 * @property requestId Request identifier
 * @property task Task description
 * @property context Agent context
 * @property maxIterations Maximum iterations allowed
 * @property maxConsecutiveToolCalls Maximum consecutive tool calls
 */
class AgentState(
    val requestId: String,
    val task: String,
    val context: AgentContext,
    val maxIterations: Int = 10,
    val maxConsecutiveToolCalls: Int = 12
) {
    /** Current iteration number (1-based) */
    var iteration: Int = 0
        private set
    
    /** Consecutive tool calls in current iteration */
    var consecutiveToolCalls: Int = 0
        private set
    
    /** Whether execution is complete */
    var isComplete: Boolean = false
        internal set
    
    /** Task outcome */
    var outcome: Outcome = Outcome.SUCCESS
        internal set
    
    /** Final response text */
    var finalText: String? = null
        internal set
    
    /** Enriched context from ContextEnricher */
    var enrichedContext: EnrichedContext = EnrichedContext()
        internal set
    
    /** Parsed output from model */
    var parsedOutput: ParsedOutput? = null
        internal set
    
    /** Tool execution result */
    var toolResult: ToolExecutionResult? = null
        internal set
    
    /** Reflection text (if enabled) */
    var reflection: String? = null
        internal set
    
    /** Conversation history */
    val history: MutableList<IterationStep> = mutableListOf()
        private set
    
    /** Tool calls made during execution */
    val toolCalls: MutableList<ToolCallRecord> = mutableListOf()
        private set
    
    /** Errors encountered */
    var errors: List<AgentError> = emptyList()
        internal set
    
    /** Number of kickstart attempts */
    var kickstartCount: Int = 0
        private set
    
    /** Number of tool retries */
    var toolRetryCount: Int = 0
        private set
    
    /** Whether waiting for user input */
    var waitingForUserInput: Boolean = false
        internal set
    
    /** Base prompt (system prompt) */
    private var basePrompt: String = ""
    
    /**
     * Get the base prompt.
     */
    fun getBasePrompt(): String = basePrompt
    
    /**
     * Set the base prompt.
     */
    internal fun setBasePrompt(prompt: String) {
        basePrompt = prompt
    }
    
    /**
     * Increment iteration counter.
     */
    internal fun incrementIteration() {
        iteration++
    }
    
    /**
     * Increment consecutive tool calls counter.
     */
    internal fun incrementConsecutiveToolCalls() {
        consecutiveToolCalls++
    }
    
    /**
     * Reset consecutive tool calls counter.
     */
    internal fun resetConsecutiveToolCalls() {
        consecutiveToolCalls = 0
    }
    
    /**
     * Increment kickstart count.
     */
    internal fun incrementKickstartCount() {
        kickstartCount++
    }
    
    /**
     * Increment tool retry count.
     */
    internal fun incrementToolRetryCount() {
        toolRetryCount++
    }
    
    /**
     * Add step to history.
     */
    internal fun addToHistory(step: IterationStep) {
        history.add(step)
    }
    
    /**
     * Record a tool call.
     */
    internal fun recordToolCall(record: ToolCallRecord) {
        toolCalls.add(record)
    }
    
    /**
     * Get the maximum kickstarts allowed.
     */
    fun getMaxKickstarts(): Int = 3
    
    /**
     * Get the maximum tool retries allowed.
     */
    fun getMaxToolRetries(): Int = 2
    
    /**
     * Get the total execution duration (estimated).
     */
    fun getDuration(): Long = System.currentTimeMillis() // Placeholder
}

/**
 * Enriched context with i2vision semantic information.
 * 
 * @property symbols Symbols found in the current file
 * @property relatedFiles Related files discovered
 * @property architecture Project architecture (if available)
 * @property contracts VSLFC contracts (if available)
 * @property flows Data/control flows
 * @property businessRules Business rules discovered
 * @property layer VSLFC layer this context is for
 */
data class EnrichedContext(
    val symbols: List<SymbolInfo> = emptyList(),
    val relatedFiles: List<String> = emptyList(),
    val architecture: ArchitectureInfo? = null,
    val contracts: List<ContractInfo> = emptyList(),
    val flows: List<FlowInfo> = emptyList(),
    val businessRules: List<BusinessRuleInfo> = emptyList(),
    val layer: VslfcLayer = VslfcLayer.CODE
) {
    /**
     * Check if context is empty.
     */
    fun isNotEmpty(): Boolean =
        symbols.isNotEmpty() || relatedFiles.isNotEmpty() ||
        architecture != null || contracts.isNotEmpty() ||
        flows.isNotEmpty() || businessRules.isNotEmpty()
    
    /**
     * Convert to prompt section.
     */
    fun toPromptSection(): String = buildString {
        if (symbols.isNotEmpty()) {
            appendLine("#### Symbols")
            symbols.take(10).forEach { symbol ->
                appendLine("- ${symbol.kind}: ${symbol.name} (${symbol.location})")
            }
            if (symbols.size > 10) {
                appendLine("- ... and ${symbols.size - 10} more")
            }
            appendLine()
        }
        
        if (relatedFiles.isNotEmpty()) {
            appendLine("#### Related Files")
            relatedFiles.take(10).forEach { file ->
                appendLine("- $file")
            }
            if (relatedFiles.size > 10) {
                appendLine("- ... and ${relatedFiles.size - 10} more")
            }
            appendLine()
        }
        
        if (architecture != null) {
            appendLine("#### Architecture")
            appendLine(architecture.description)
            appendLine()
        }
        
        if (contracts.isNotEmpty()) {
            appendLine("#### Contracts")
            contracts.take(5).forEach { contract ->
                appendLine("- ${contract.layer}: ${contract.description.take(100)}")
            }
            appendLine()
        }
        
        if (flows.isNotEmpty()) {
            appendLine("#### Flows")
            flows.take(5).forEach { flow ->
                appendLine("- ${flow.name}: ${flow.description.take(100)}")
            }
            appendLine()
        }
        
        if (businessRules.isNotEmpty()) {
            appendLine("#### Business Rules")
            businessRules.take(5).forEach { rule ->
                appendLine("- ${rule.description.take(100)}")
            }
            appendLine()
        }
    }
}

/**
 * Symbol information from i2vision-instant.
 */
data class SymbolInfo(
    val name: String,
    val kind: String,
    val location: String,
    val signature: String? = null
)

/**
 * Architecture information from discovery cache.
 */
data class ArchitectureInfo(
    val description: String,
    val components: List<String> = emptyList(),
    val layers: List<String> = emptyList()
)

/**
 * Contract information from VSLFC validation.
 */
data class ContractInfo(
    val layer: VslfcLayer,
    val description: String,
    val isViolated: Boolean = false
)

/**
 * Flow information from i2vision-instant.
 */
data class FlowInfo(
    val name: String,
    val description: String,
    val steps: List<String> = emptyList()
)

/**
 * Business rule information from i2vision-instant.
 */
data class BusinessRuleInfo(
    val description: String,
    val source: String? = null
)

/**
 * A step in the conversation history.
 * 
 * @property iteration Iteration number
 * @property prompt Prompt sent to model
 * @property rawOutput Raw output from model
 * @property parsed Parsed output
 */
data class IterationStep(
    val iteration: Int,
    val prompt: String,
    val rawOutput: String,
    val parsed: ParsedOutput
) {
    /**
     * Convert to prompt section.
     */
    fun toPromptSection(): String = buildString {
        appendLine("##### Iteration $iteration")
        if (parsed.reasoning != null) {
            appendLine("**Reasoning:** ${parsed.reasoning.take(200)}")
        }
        if (parsed.toolCall != null) {
            appendLine("**Tool:** ${parsed.toolCall.name}")
            appendLine("**Args:** ${parsed.toolCall.args}")
        }
    }
}

/**
 * Parsed output from the model.
 * 
 * @property rawText Raw output from model
 * @property reasoning Reasoning/prose text
 * @property toolCall Tool call (if any)
 * @property isComplete Whether the task is complete
 * @property isMalformed Whether the output is malformed
 * @property clarification Clarification request (if any)
 */
data class ParsedOutput(
    val rawText: String,
    val reasoning: String?,
    val toolCall: ToolCall?,
    val isComplete: Boolean,
    val isMalformed: Boolean = false,
    val clarification: String? = null
) {
    /**
     * Check if output has a tool call.
     */
    fun hasToolCall(): Boolean = toolCall != null
    
    /**
     * Check if output needs clarification.
     */
    fun needsClarification(): Boolean = clarification != null
    
    /**
     * Check if task is complete.
     */
    fun isTaskComplete(): Boolean = isComplete ||
        toolCall?.name?.lowercase()?.contains("complete") == true ||
        toolCall?.name?.lowercase()?.contains("done") == true ||
        toolCall?.name?.lowercase()?.contains("finish") == true ||
        toolCall?.name?.lowercase()?.contains("stop") == true
}

/**
 * A tool call request.
 * 
 * @property name Tool name
 * @property args Tool arguments
 */
data class ToolCall(
    val name: String,
    val args: Map<String, Any>
)

/**
 * Result from tool execution.
 * 
 * @property toolName Tool that was executed
 * @property args Arguments passed to tool
 * @property isSuccess Whether execution succeeded
 * @property output Output from tool (if successful)
 * @property error Error message (if failed)
 * @property signal Signal (e.g., "TASK_STOP")
 */
data class ToolExecutionResult(
    val toolName: String,
    val args: Map<String, Any>,
    val isSuccess: Boolean,
    val output: String?,
    val error: String? = null,
    val signal: String? = null
)

/**
 * Interface for providing instant context.
 */
interface InstantContextProvider {
    /**
     * Get context for a specific file and task.
     */
    suspend fun getContext(file: String, task: String): FileContext?
}

/**
 * Context for a specific file.
 */
data class FileContext(
    val symbols: List<SymbolInfo>,
    val relatedFiles: List<String>,
    val flows: List<FlowInfo>,
    val businessRules: List<BusinessRuleInfo>
)

/**
 * Interface for discovery cache.
 */
interface DiscoveryCache {
    /**
     * Get or discover context for a workspace.
     */
    suspend fun getOrDiscover(workspaceRoot: String): DiscoveryResult?
}

/**
 * Result from discovery.
 */
data class DiscoveryResult(
    val architecture: ArchitectureInfo?,
    val contracts: List<ContractInfo>,
    val timestamp: Long
)

/**
 * Interface for model provider.
 */
interface ModelProvider {
    /**
     * Generate text from a prompt.
     */
    suspend fun generate(
        prompt: String,
        temperature: Double,
        topP: Double,
        topK: Int,
        maxTokens: Int,
        timeoutSeconds: Long
    ): String
}

/**
 * Interface for tool registry.
 */
interface ToolRegistry {
    /**
     * Execute a tool with arguments.
     */
    suspend fun execute(toolName: String, args: Map<String, Any>): Any
}
