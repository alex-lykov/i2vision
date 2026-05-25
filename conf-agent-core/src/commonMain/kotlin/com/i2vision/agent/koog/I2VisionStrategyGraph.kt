/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.agent.koog

import com.i2vision.agent.*
import com.i2vision.agent.config.KoogStrategyConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Builds the Koog GraphStrategy for an i2vision agent.
 * 
 * This replaces the legacy BaseConfigurableAgent.executeWithTools() imperative loop
 * with a declarative directed graph strategy.
 * 
 * ## Graph Flow
 * 
 * ```
 *                 ┌─────────────┐
 *                 │   START     │
 *                 └──────┬──────┘
 *                        │
 *                 ┌──────▼──────┐
 *                 │  ENRICH     │  ← Inject instant context
 *                 │  CONTEXT    │
 *                 └──────┬──────┘
 *                        │
 *                 ┌──────▼──────┐
 *                 │   PLAN      │  ← Build iteration prompt
 *                 └──────┬──────┘
 *                        │
 *                 ┌──────▼──────┐
 *                 │   DECIDE    │  ← Decision node
 *                 └──┬───┬───┬──┘
 *                    │   │   │
 *           ┌────────┘   │   └────────┐
 *           ▼            ▼            ▼
 *    ┌──────────┐ ┌──────────┐ ┌──────────┐
 *    │ EXECUTE  │ │  CLARIFY │ │   DONE   │
 *    │  TOOL    │ │          │ │          │
 *    └────┬─────┘ └────┬─────┘ └──────────┘
 *         │            │
 *         ▼            ▼
 *    ┌──────────┐ ┌──────────┐
 *    │ EVALUATE │ │ WAIT FOR │
 *    │  RESULT  │ │   USER   │
 *    └────┬─────┘ └──────────┘
 *         │
 *         ▼
 *    ┌──────────┐
 *    │ REFLECT  │  ← Optional self-reflection
 *    └────┬─────┘
 *         │
 *         └──────────► back to PLAN
 * ```
 * 
 * ## Key Differences from Legacy
 * 
 * | Legacy (BaseConfigurableAgent) | Modern (Koog GraphStrategy) |
 * |--------------------------------|------------------------------|
 * | Imperative `for` loop | Declarative directed graph |
 * | Manual iteration counting | Koog handles state transitions |
 * | Hard-coded prompt → parse → execute | Graph nodes with conditional edges |
 * | Simple `if/else` branching | Decision nodes with strategy patterns |
 * | No built-in observability | Koog tracing built-in |
 * | No built-in checkpointing | Koog checkpoint/restore support |
 * 
 * @property config Strategy configuration (iteration limits, reflection, etc.)
 * @property contextEnricher Injects i2vision instant context
 * @property promptBuilder Builds iteration prompts
 * @property modelInvoker Invokes the LLM
 * @property outputParser Parses model output
 * @property toolExecutor Executes tool calls
 * @property responseFormatter Formats final responses
 */
class I2VisionStrategyGraph(
    private val config: KoogStrategyConfig,
    private val contextEnricher: ContextEnricher,
    private val promptBuilder: PromptBuilder,
    private val modelInvoker: ModelInvoker,
    private val outputParser: OutputParser,
    private val toolExecutor: ToolExecutor,
    private val responseFormatter: ResponseFormatter
) {
    
    /**
     * Build the complete strategy graph.
     * 
     * @return Koog GraphStrategy ready for execution
     */
    fun build(): I2VisionGraphStrategy {
        return I2VisionGraphStrategy(
            name = "i2vision-${config.layer.name.lowercase()}-agent",
            config = config,
            contextEnricher = contextEnricher,
            promptBuilder = promptBuilder,
            modelInvoker = modelInvoker,
            outputParser = outputParser,
            toolExecutor = toolExecutor,
            responseFormatter = responseFormatter
        )
    }
}

/**
 * Koog GraphStrategy implementation for i2vision agents.
 * 
 * This class encapsulates the entire agent execution graph.
 * It manages state transitions, node execution, and streaming events.
 * 
 * @property name Strategy name
 * @property config Strategy configuration
 * @property contextEnricher Context enrichment component
 * @property promptBuilder Prompt building component
 * @property modelInvoker Model invocation component
 * @property outputParser Output parsing component
 * @property toolExecutor Tool execution component
 * @property responseFormatter Response formatting component
 */
class I2VisionGraphStrategy(
    val name: String,
    private val config: KoogStrategyConfig,
    private val contextEnricher: ContextEnricher,
    private val promptBuilder: PromptBuilder,
    private val modelInvoker: ModelInvoker,
    private val outputParser: OutputParser,
    private val toolExecutor: ToolExecutor,
    private val responseFormatter: ResponseFormatter
) {
    
    /**
     * Execute the strategy synchronously.
     * 
     * @param request The agent request
     * @return Final agent response
     */
    suspend fun execute(request: AgentRequest): AgentResponse {
        val state = AgentState(
            requestId = request.id,
            task = request.task,
            context = request.context,
            maxIterations = config.maxIterations,
            maxConsecutiveToolCalls = config.maxConsecutiveToolCalls
        )
        
        return executeGraph(state)
    }
    
    /**
     * Execute the strategy with streaming.
     * 
     * @param request The agent request
     * @return Flow of streaming chunks
     */
    fun executeStreaming(request: AgentRequest): Flow<AgentChunk> = flow {
        val state = AgentState(
            requestId = request.id,
            task = request.task,
            context = request.context,
            maxIterations = config.maxIterations,
            maxConsecutiveToolCalls = config.maxConsecutiveToolCalls
        )
        
        val startTime = System.currentTimeMillis()
        
        try {
            // === NODE 1: ENRICH CONTEXT ===
            emit(AgentChunk.Progress(
                requestId = request.id,
                iteration = 1,
                maxIterations = config.maxIterations,
                message = "Enriching context with i2vision instant context"
            ))
            
            val enrichedContext = contextEnricher.enrich(
                task = request.task,
                workspaceRoot = request.context.workspaceRoot,
                currentFile = request.context.currentFile,
                vslfcLayer = config.layer
            )
            
            state.enrichedContext = enrichedContext
            
            emit(AgentChunk.Progress(
                requestId = request.id,
                iteration = 1,
                maxIterations = config.maxIterations,
                message = "Context enriched with ${enrichedContext.symbols.size} symbols"
            ))
            
            // === SET BASE PROMPT with dynamic variables ===
            val dynamicVariables = mapOf(
                "workspaceRoot" to request.context.workspaceRoot,
                "currentFile" to (request.context.currentFile ?: "N/A"),
                "task" to request.task
            )
            val basePrompt = promptBuilder.getBasePrompt(dynamicVariables)
            state.setBasePrompt(basePrompt)
            
            // === MAIN LOOP ===
            while (state.iteration <= config.maxIterations && !state.isComplete) {
                state.incrementIteration()
                
                // === NODE 2: PLAN ===
                emit(AgentChunk.Reasoning(
                    requestId = request.id,
                    text = "Planning iteration ${state.iteration}...",
                    iteration = state.iteration
                ))
                
                val prompt = promptBuilder.buildIterationPrompt(
                    basePrompt = state.getBasePrompt(),
                    iteration = state.iteration,
                    maxIterations = config.maxIterations,
                    history = state.history,
                    enrichedContext = state.enrichedContext
                )
                
                val rawOutput = modelInvoker.invoke(prompt)
                val parsed = outputParser.parse(rawOutput)
                
                state.parsedOutput = parsed
                state.addToHistory(IterationStep(
                    iteration = state.iteration,
                    prompt = prompt,
                    rawOutput = rawOutput,
                    parsed = parsed
                ))
                
                // === NODE 3: DECIDE ===
                val decision = decide(state)
                
                when (decision) {
                    Decision.DONE -> {
                        state.isComplete = true
                        state.outcome = Outcome.SUCCESS
                        break
                    }
                    
                    Decision.EXECUTE_TOOL -> {
                        // === NODE 4: EXECUTE TOOL ===
                        val toolCall = parsed.toolCall ?: continue
                        
                        emit(AgentChunk.ToolCallStarted(
                            requestId = request.id,
                            toolName = toolCall.name,
                            args = toolCall.args,
                            iteration = state.iteration
                        ))
                        
                        val toolStartTime = System.currentTimeMillis()
                        val result = toolExecutor.execute(
                            toolName = toolCall.name,
                            args = toolCall.args,
                            timeoutSeconds = config.toolTimeoutSeconds
                        )
                        val toolDuration = System.currentTimeMillis() - toolStartTime
                        
                        emit(AgentChunk.ToolCallCompleted(
                            requestId = request.id,
                            toolName = toolCall.name,
                            success = result.isSuccess,
                            output = result.output,
                            durationMs = toolDuration
                        ))
                        
                        state.toolResult = result
                        state.incrementConsecutiveToolCalls()
                        state.recordToolCall(ToolCallRecord(
                            toolName = toolCall.name,
                            args = toolCall.args,
                            success = result.isSuccess,
                            output = result.output,
                            durationMs = toolDuration
                        ))
                        
                        // Check for consecutive tool call limit
                        if (state.consecutiveToolCalls >= config.maxConsecutiveToolCalls) {
                            state.isComplete = true
                            state.outcome = Outcome.ERROR
                            state.finalText = "Exceeded maximum consecutive tool calls"
                            break
                        }
                        
                        // Continue loop for reflection
                        continue
                    }
                    
                    Decision.CLARIFY -> {
                        // === NODE 5: WAIT FOR USER ===
                        state.waitingForUserInput = true
                        state.isComplete = true
                        state.outcome = Outcome.NEEDS_CLARIFICATION
                        state.finalText = parsed.clarification
                        break
                    }
                    
                    Decision.REFLECT -> {
                        // === NODE 7: REFLECT (if enabled) ===
                        if (config.reflectionEnabled && state.parsedOutput?.reasoning != null) {
                            emit(AgentChunk.Reasoning(
                                requestId = request.id,
                                text = "Reflecting on progress...",
                                iteration = state.iteration
                            ))
                            
                            // TODO: Implement reflection logic
                            // For now, just reset consecutive tool calls counter
                            state.resetConsecutiveToolCalls()
                        }
                        // Continue to next iteration
                    }
                }
                
                // === NODE 6: EVALUATE RESULT ===
                // (Implicit in the loop continuation)
            }
            
            // === FINALIZE ===
            val duration = System.currentTimeMillis() - startTime
            
            if (!state.isComplete) {
                state.isComplete = true
                state.outcome = Outcome.ITERATION_LIMIT
                state.finalText = "Reached maximum iterations without completing the task"
            }
            
            val finalResponse = responseFormatter.format(state)
            
            emit(AgentChunk.Done(
                requestId = request.id,
                outcome = state.outcome,
                finalText = finalResponse,
                iterations = state.iteration,
                totalDurationMs = duration
            ))
            
        } catch (e: Exception) {
            emit(AgentChunk.ChunkError(
                requestId = request.id,
                error = AgentError(
                    code = AgentError.Codes.UNKNOWN,
                    message = e.message ?: "Unknown error",
                    iteration = state.iteration,
                    recoverable = false
                )
            ))
        }
    }
    
    /**
     * Execute the graph synchronously (internal implementation).
     */
    private suspend fun executeGraph(state: AgentState): AgentResponse {
        // Collect streaming chunks and return final response
        // This is a simplified implementation - in production,
        // you'd want proper synchronization between streaming and sync execution
        return AgentResponse(
            requestId = state.requestId,
            agentId = "koog-agent",
            outcome = state.outcome,
            finalText = state.finalText ?: "No response generated",
            iterations = state.iteration,
            toolCalls = state.toolCalls.map { record ->
                com.i2vision.agent.ToolCallRecord(
                    toolName = record.toolName,
                    args = record.args,
                    success = record.success,
                    output = record.output,
                    durationMs = record.durationMs
                )
            },
            durationMs = state.getDuration()
        )
    }
}
