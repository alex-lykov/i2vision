package com.i2vision.agent

/**
 * Generic adapter from an agent-specific config object to Koog runtime settings.
 * Keeps [com.alyk.ai.agent.core.agent.BaseConfigurableAgent] model-agnostic and
 * free from hardcoded config schemas.
 *
 * Currently supports:
 * - Ollama (default, fully implemented)
 * - Other providers (empty stubs for future expansion)
 */
interface AgentConfigurationProvider<ConfigT> {
    // ==================== Basic Configuration ====================
    fun getSystemPrompt(config: ConfigT): String
    fun getModelId(config: ConfigT): String
    fun getLLMProviderName(config: ConfigT): String = "ollama"  // ollama, openai, anthropic, google, cohere
    fun getContextLength(config: ConfigT): Long
    fun getMaxOutputTokens(config: ConfigT): Long

    // ==================== Optional Advanced Configuration ====================
    /**
     * Optional temperature for model sampling (0.0 to 2.0).
     * Higher values increase randomness/creativity.
     */
    fun getTemperature(config: ConfigT): Double? = null

    /**
     * Optional top_p (nucleus sampling) parameter.
     * Controls diversity via cumulative probability.
     */
    fun getTopP(config: ConfigT): Double? = null

    /**
     * Tool execution timeout in seconds.
     */
    fun getToolTimeoutSeconds(config: ConfigT): Long = 30

    /**
     * Enable parallel execution of multiple tool calls.
     */
    fun enableParallelToolCalls(config: ConfigT): Boolean = true

    /**
     * Max iterations for agent reasoning loop.
     */
    fun getMaxIterations(config: ConfigT): Int = 10

    /**
     * Maximum number of kickstart (format-reminder) injections allowed per task.
     * Each kickstart consumes one iteration; once this limit is reached the loop
     * treats a missing tool_call as a final answer instead of retrying.
     */
    fun getMaxKickstarts(config: ConfigT): Int = 2

    /**
     * Full formatting-rules text injected at the end of every prompt so the model
     * knows exactly which output format is expected (JSON tool_call, not XML, markdown, etc.).
     * Return null or blank to skip injection (not recommended when tools are registered).
     */
    fun getFormattingRules(config: ConfigT): String? = null

    /**
     * Compact one-liner appended after [getFormattingRules] as a quick reminder.
     * Helps models that skim multi-line instructions.
     */
    fun getFormattingRulesBrief(config: ConfigT): String? = null

    /**
     * Model capabilities for LLM runtime.
     */
    fun getModelCapabilities(config: ConfigT): List<String> = emptyList()

    /**
     * Enable native streaming API usage when supported by the runtime.
     */
    fun enableStreaming(config: ConfigT): Boolean = false

    /**
     * Allow fallback to non-streaming `run()` if native streaming is unavailable or fails.
     */
    fun fallbackToNonStreamingOnStreamFailure(config: ConfigT): Boolean = true

    /**
     * Chunk size used only for non-streaming fallback emission.
     */
    fun getFallbackStreamingChunkSize(config: ConfigT): Int = 512

    /**
     * Delay between fallback chunks in milliseconds.
     */
    fun getFallbackStreamingChunkDelayMs(config: ConfigT): Long = 0

    /**
     * Candidate method names to probe for native streaming on AIAgent.
     */
    fun getStreamingMethodCandidates(config: ConfigT): List<String> =
        listOf("runStreaming", "stream", "runStream", "streamRun")
}

