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
                        
                        // === NODE 5: EVALUATE ===
                        if (result.isSuccess) {
                            if (result.signal == "TASK_STOP") {
                                state.isComplete = true
                                state.outcome = Outcome.SUCCESS
                                break
                            }
                            
                            // === NODE 6: REFLECT (optional) ===
                            if (config.reflectionEnabled) {
                                val reflection = modelInvoker.reflect(
                                    task = request.task,
                                    toolResult = result,
                                    history = state.history
                                )
                                
                                emit(AgentChunk.Reasoning(
                                    requestId = request.id,
                                    text = reflection,
                                    iteration = state.iteration
                                ))
                                
                                state.reflection = reflection
                            }
                            
                            // Continue to next iteration
                            state.resetConsecutiveToolCalls()
                        } else {
                            // Tool failed
                            if (state.toolRetryCount < config.maxToolRetries) {
                                state.incrementToolRetryCount()
                                // Retry in next iteration
                            } else {
                                state.isComplete = true
                                state.outcome = Outcome.ERROR
                                state.errors = listOf(AgentError(
                                    code = AgentError.Codes.TOOL_EXECUTION_FAILED,
                                    message = "Tool ${toolCall.name} failed after ${state.toolRetryCount} retries",
                                    iteration = state.iteration,
                                    recoverable = false
                                ))
                                break
                            }
                        }
                    }
                    
                    Decision.CLARIFY -> {
                        // === NODE 7: CLARIFY ===
                        val clarification = parsed.clarification ?: "I need clarification to proceed."
                        
                        emit(AgentChunk.Text(
                            requestId = request.id,
                            text = clarification,
                            isFinal = true
                        ))
                        
                        state.isComplete = true
                        state.outcome = Outcome.NEEDS_CLARIFICATION
                        state.finalText = clarification
                        break
                    }
                    
                    Decision.RETRY_WITH_KICKSTART -> {
                        state.incrementKickstartCount()
                        if (state.kickstartCount > config.maxKickstarts) {
                            state.isComplete = true
                            state.outcome = Outcome.ERROR
                            state.errors = listOf(AgentError(
                                code = AgentError.Codes.LLM_ERROR,
                                message = "Kickstart limit exceeded",
                                iteration = state.iteration,
                                recoverable = false
                            ))
                            break
                        }
                        // Continue to next iteration with kickstart
                    }
                    
                    Decision.CONTINUE -> {
                        // Continue to next iteration
                    }
                }
            }
            
            // Check iteration limit
            if (state.iteration > config.maxIterations && !state.isComplete) {
                state.isComplete = true
                state.outcome = Outcome.ITERATION_LIMIT
            }
            
            // === NODE 8: DONE (Terminal) ===
            val duration = System.currentTimeMillis() - startTime
            val response = responseFormatter.format(
                task = request.task,
                history = state.history,
                toolCalls = state.toolCalls,
                outcome = state.outcome,
                layer = config.layer
            )
            
            state.finalText = response.finalText
            
            emit(AgentChunk.Done(
                requestId = request.id,
                outcome = state.outcome,
                finalText = response.finalText,
                iterations = state.iteration,
                totalDurationMs = duration
            ))
            
        } catch (e: Exception) {
            emit(AgentChunk.ChunkError(
                requestId = request.id,
                error = AgentError(
                    code = AgentError.Codes.UNKNOWN,
                    message = e.message ?: "Unknown error",
                    recoverable = false
                )
            ))
            
            state.outcome = Outcome.ERROR
            state.errors = listOf(AgentError(
                code = AgentError.Codes.UNKNOWN,
                message = e.message ?: "Unknown error",
                recoverable = false
            ))
        }
    }
    
    /**
     * Decision logic for the DECIDE node.
     */
    private fun decide(state: AgentState): Decision {
        val parsed = state.parsedOutput ?: return Decision.CONTINUE
        
        return when {
            // Task complete
            parsed.isTaskComplete() -> Decision.DONE
            
            // Tool call requested
            parsed.hasToolCall() -> {
                if (state.consecutiveToolCalls >= config.maxConsecutiveToolCalls) {
                    Decision.DONE  // Force stop if too many consecutive tools
                } else {
                    Decision.EXECUTE_TOOL
                }
            }
            
            // Agent needs clarification
            parsed.needsClarification() -> Decision.CLARIFY
            
            // Hit iteration limit
            state.iteration >= config.maxIterations -> Decision.DONE
            
            // Malformed output — kickstart
            parsed.isMalformed() && config.enableKickstart -> {
                if (state.kickstartCount < config.maxKickstarts) {
                    Decision.RETRY_WITH_KICKSTART
                } else {
                    Decision.DONE  // Give up
                }
            }
            
            // Default: keep planning
            else -> Decision.CONTINUE
        }
    }
    
    /**
     * Execute the graph and return final response.
     */
    private suspend fun executeGraph(state: AgentState): AgentResponse {
        val startTime = System.currentTimeMillis()
        
        try {
            // Run the graph (same logic as executeStreaming but collect final result)
            executeStreaming(AgentRequest(
                id = state.requestId,
                task = state.task,
                context = state.context
            )).collect { chunk ->
                // Collect chunks but don't emit to caller
                if (chunk is AgentChunk.Done) {
                    state.outcome = chunk.outcome
                    state.finalText = chunk.finalText
                }
            }
        } catch (e: Exception) {
            state.outcome = Outcome.ERROR
            state.errors = listOf(AgentError(
                code = AgentError.Codes.UNKNOWN,
                message = e.message ?: "Unknown error",
                recoverable = false
            ))
        }
        
        val duration = System.currentTimeMillis() - startTime
        
        return AgentResponse(
            requestId = state.requestId,
            agentId = config.agentId,
            outcome = state.outcome,
            finalText = state.finalText,
            iterations = state.iteration,
            toolCalls = state.toolCalls,
            durationMs = duration,
            reasoningTrace = state.history.map { step ->
                ReasoningStep(
                    iteration = step.iteration,
                    type = ReasoningType.PLAN,
                    content = step.rawOutput,
                    timestamp = System.currentTimeMillis()
                )
            },
            errors = state.errors
        )
    }
}

/**
 * Decision outcomes from the DECIDE node.
 */
enum class Decision {
    DONE,
    EXECUTE_TOOL,
    CLARIFY,
    RETRY_WITH_KICKSTART,
    CONTINUE
}
