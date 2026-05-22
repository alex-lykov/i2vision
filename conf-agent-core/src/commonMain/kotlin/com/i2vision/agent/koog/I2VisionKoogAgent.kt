/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.agent.koog

import com.i2vision.agent.*
import com.i2vision.agent.config.AgentPromptConfiguration
import com.i2vision.agent.config.KoogConfigs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * i2vision agent implementation using Koog GraphStrategy.
 * 
 * This replaces the legacy BaseConfigurableAgent entirely.
 * 
 * ## Architecture
 * 
 * ```
 * I2VisionKoogAgent
 *     ↓ delegates to
 * I2VisionGraphStrategy
 *     ↓ executes nodes
 * ├── ContextEnricher (instant context + discovery cache)
 * ├── PromptBuilder (iteration prompts)
 * ├── ModelInvoker (LLM calls)
 * ├── OutputParser (parse tool calls)
 * ├── ToolExecutor (execute tools)
 * └── ResponseFormatter (format response)
 * ```
 * 
 * ## Key Differences from Legacy
 * 
 * | Feature | Legacy Loop | Koog GraphStrategy |
 * |---------|-------------|-------------------|
 * | **Flow visibility** | Hidden in code | Visual graph |
 * | **Modification** | Edit imperative code | Add/remove nodes |
 * | **Testing** | Test entire loop | Test individual nodes |
 * | **Observability** | Manual logging | Built-in tracing |
 * | **Checkpointing** | Not possible | Save/restore state |
 * | **Streaming** | Custom implementation | Built-in support |
 * | **Error handling** | Try/catch in loop | Per-node error handlers |
 * | **Parallelism** | Sequential only | Parallel tool calls possible |
 * 
 * @property id Unique agent identifier
 * @property layer VSLFC layer this agent specializes in
 * @property displayName Human-readable name
 * @property strategyGraph Strategy graph builder
 * @property config Runtime configuration
 * @property koogConfigs Koog-specific configurations
 */
class I2VisionKoogAgent(
    override val id: String,
    override val layer: VslfcLayer,
    override val displayName: String,
    private val strategyGraph: I2VisionStrategyGraph,
    private val config: AgentConfig,
    private val koogConfigs: KoogConfigs
) : I2VisionAgent {
    
    override val capabilities: AgentCapabilities = AgentCapabilities(
        supportsStreaming = true,
        supportsCancellation = true,
        supportsRuntimeConfig = true,
        maxContextTokens = config.maxContextTokens,
        availableTools = config.availableTools.map { it.toToolInfo() },
        supportedLayers = listOf(layer),
        modelProvider = config.modelProvider,
        modelId = config.modelId
    )
    
    private val graph = strategyGraph.build()
    private val activeRequests = mutableMapOf<String, KoogExecution>()
    
    /**
     * Process a task synchronously.
     * 
     * Executes the strategy graph until completion.
     * 
     * @param request The task request
     * @return Complete response after all iterations
     */
    override suspend fun process(request: AgentRequest): AgentResponse {
        val execution = graph.execute(request)
        activeRequests[request.id] = KoogExecution(execution)
        
        return execution
    }
    
    /**
     * Process a task with streaming responses.
     * 
     * Emits chunks as the agent progresses through the graph.
     * 
     * @param request The task request
     * @return Flow of streaming chunks
     */
    override fun processStreaming(request: AgentRequest): Flow<AgentChunk> {
        val execution = graph.executeStreaming(request)
        activeRequests[request.id] = KoogExecution(execution)
        
        return execution.map { event ->
            // Events are already AgentChunks from executeStreaming
            event
        }
    }
    
    /**
     * Cancel a running task.
     * 
     * @param requestId Request ID to cancel
     * @return true if cancelled, false if not found
     */
    override suspend fun cancel(requestId: String): Boolean {
        val execution = activeRequests[requestId] ?: return false
        execution.cancel()
        activeRequests.remove(requestId)
        return true
    }
    
    /**
     * Get the agent's current configuration.
     */
    override fun getConfig(): AgentConfig = config
    
    /**
     * Update runtime configuration.
     * 
     * @param overrides Configuration overrides
     * @return New effective configuration
     */
    override suspend fun updateConfig(overrides: AgentConfigOverrides): AgentConfig {
        // Apply overrides to runtime config
        config.applyOverrides(overrides)
        return config
    }
    
    /**
     * Dispose of agent resources.
     * 
     * Cancels all active requests and cleans up.
     */
    override suspend fun dispose() {
        activeRequests.values.forEach { it.cancel() }
        activeRequests.clear()
    }
    
    companion object {
        /**
         * Create an I2VisionKoogAgent from YAML configuration.
         * 
         * This is the primary factory method for creating agents.
         * 
         * Example:
         * ```kotlin
         * val agent = I2VisionKoogAgent.fromConfig(
         *     configPath = ".vscode/i2vision/agents/coding-agent.yaml",
         *     layer = VslfcLayer.CODE
         * )
         * ```
         * 
         * @param configPath Path to YAML configuration file
         * @param layer VSLFC layer for the agent
         * @param modelProvider Model provider implementation
         * @param toolRegistry Tool registry implementation
         * @param instantContextProvider Instant context provider
         * @param discoveryCache Discovery cache implementation
         * @return Configured agent instance
         */
        suspend fun fromConfig(
            configPath: String,
            layer: VslfcLayer,
            modelProvider: ModelProvider,
            toolRegistry: ToolRegistry,
            instantContextProvider: InstantContextProvider,
            discoveryCache: DiscoveryCache
        ): I2VisionKoogAgent {
            // Load YAML configuration
            val yamlConfig = com.i2vision.agent.config.YamlConfigLoader.load(configPath)
            
            // Create adapter and factory
            val adapter = com.i2vision.agent.config.ConfigurationAdapter()
            val koogFactory = com.i2vision.agent.config.KoogConfigFactory()
            
            // Convert to new interfaces
            val agentConfig = adapter.toAgentConfig(yamlConfig)
            val koogConfigs = koogFactory.createAll(yamlConfig)
            
            // Create strategy components
            val contextEnricher = ContextEnricher(
                instantContextProvider = instantContextProvider,
                discoveryCache = discoveryCache
            )
            
            val promptBuilder = PromptBuilder(
                promptTemplate = koogConfigs.promptConfig,
                config = yamlConfig
            )
            
            val modelInvoker = ModelInvoker(
                modelProvider = modelProvider,
                config = koogConfigs.llmConfig
            )
            
            val outputParser = OutputParser(
                parsingConfig = koogConfigs.promptConfig.parsingConfig
            )
            
            val toolExecutor = ToolExecutor(
                toolRegistry = toolRegistry
            )
            
            val responseFormatter = ResponseFormatter(
                config = yamlConfig
            )
            
            // Create strategy graph
            val strategyConfig = koogConfigs.strategyConfig.copy(
                layer = layer,
                agentId = generateAgentId(layer)
            )
            
            val strategyGraph = I2VisionStrategyGraph(
                config = strategyConfig,
                contextEnricher = contextEnricher,
                promptBuilder = promptBuilder,
                modelInvoker = modelInvoker,
                outputParser = outputParser,
                toolExecutor = toolExecutor,
                responseFormatter = responseFormatter
            )
            
            // Create agent
            return I2VisionKoogAgent(
                id = strategyConfig.agentId,
                layer = layer,
                displayName = createAgentDisplayName(layer, koogConfigs.llmConfig.modelId, koogConfigs.llmConfig.provider),
                strategyGraph = strategyGraph,
                config = agentConfig,
                koogConfigs = koogConfigs
            )
        }
        
        /**
         * Create an I2VisionKoogAgent from default layer configuration.
         * 
         * This is a convenience method for quick prototyping.
         * 
         * Example:
         * ```kotlin
         * val agent = I2VisionKoogAgent.fromDefault(
         *     layer = VslfcLayer.CODE,
         *     modelProvider = myModelProvider,
         *     toolRegistry = myToolRegistry
         * )
         * ```
         */
        suspend fun fromDefault(
            layer: VslfcLayer,
            modelProvider: ModelProvider,
            toolRegistry: ToolRegistry,
            instantContextProvider: InstantContextProvider,
            discoveryCache: DiscoveryCache
        ): I2VisionKoogAgent {
            // Get default configuration for layer
            val yamlConfig = com.i2vision.agent.config.DefaultConfigs.forLayer(layer)
            
            // Create adapter and factory
            val adapter = com.i2vision.agent.config.ConfigurationAdapter()
            val koogFactory = com.i2vision.agent.config.KoogConfigFactory()
            
            // Convert to new interfaces
            val agentConfig = adapter.toAgentConfig(yamlConfig)
            val koogConfigs = koogFactory.createAll(yamlConfig)
            
            // Create strategy components
            val contextEnricher = ContextEnricher(
                instantContextProvider = instantContextProvider,
                discoveryCache = discoveryCache
            )
            
            val promptBuilder = PromptBuilder(
                promptTemplate = koogConfigs.promptConfig,
                config = yamlConfig
            )
            
            val modelInvoker = ModelInvoker(
                modelProvider = modelProvider,
                config = koogConfigs.llmConfig
            )
            
            val outputParser = OutputParser(
                parsingConfig = koogConfigs.promptConfig.parsingConfig
            )
            
            val toolExecutor = ToolExecutor(
                toolRegistry = toolRegistry
            )
            
            val responseFormatter = ResponseFormatter(
                config = yamlConfig
            )
            
            // Create strategy graph
            val strategyConfig = koogConfigs.strategyConfig.copy(
                layer = layer,
                agentId = generateAgentId(layer)
            )
            
            val strategyGraph = I2VisionStrategyGraph(
                config = strategyConfig,
                contextEnricher = contextEnricher,
                promptBuilder = promptBuilder,
                modelInvoker = modelInvoker,
                outputParser = outputParser,
                toolExecutor = toolExecutor,
                responseFormatter = responseFormatter
            )
            
            // Create agent
            return I2VisionKoogAgent(
                id = strategyConfig.agentId,
                layer = layer,
                displayName = createAgentDisplayName(layer, koogConfigs.llmConfig.modelId, koogConfigs.llmConfig.provider),
                strategyGraph = strategyGraph,
                config = agentConfig,
                koogConfigs = koogConfigs
            )
        }
    }
}

/**
 * Wrapper for Koog graph execution.
 * 
 * Manages the lifecycle of a graph execution, including cancellation.
 */
class KoogExecution(
    private val result: AgentResponse
) {
    private var cancelled: Boolean = false
    
    /**
     * Cancel the execution.
     */
    fun cancel() {
        cancelled = true
    }
    
    /**
     * Check if execution is cancelled.
     */
    fun isCancelled(): Boolean = cancelled
    
    /**
     * Await the result.
     */
    suspend fun await(): AgentResponse = result
}

/**
 * Extension function to create ToolInfo from AgentConfig tool.
 */
fun com.i2vision.agent.config.ToolInfo.toToolInfo(): com.i2vision.agent.ToolInfo =
    com.i2vision.agent.ToolInfo(
        name = name,
        description = description,
        category = category,
        isReadOnly = isReadOnly
    )

/**
 * Extension function to create ToolInfo from config.
 */
fun String.toToolInfo(): com.i2vision.agent.ToolInfo =
    com.i2vision.agent.ToolInfo(
        name = this,
        description = "Tool: $this",
        category = com.i2vision.agent.ToolCategory.CONTROL,
        isReadOnly = false
    )
