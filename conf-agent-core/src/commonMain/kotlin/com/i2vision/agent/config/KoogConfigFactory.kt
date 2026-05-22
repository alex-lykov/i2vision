/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.agent.config

/**
 * Factory for creating Koog-specific configurations from YAML.
 * 
 * This factory bridges the i2vision YAML configuration format to
 * Koog framework's configuration objects. It's used by:
 * - task-14: Koog GraphStrategy
 * - task-15: Koog PromptTemplate
 * - task-16: Koog ToolRegistry
 * 
 * Example usage:
 * ```kotlin
 * val yamlConfig = YamlConfigLoader.load("coding-agent.yaml")
 * val factory = KoogConfigFactory()
 * 
 * // Create all Koog configs at once
 * val koogConfigs = factory.createAll(yamlConfig)
 * 
 * // Or create individually
 * val promptConfig = factory.createPromptConfig(yamlConfig)
 * val strategyConfig = factory.createStrategyConfig(yamlConfig)
 * val toolRegistryConfig = factory.createToolRegistryConfig(yamlConfig)
 * ```
 */
class KoogConfigFactory {
    
    private val adapter = ConfigurationAdapter()
    
    /**
     * Create all Koog configurations from a YAML configuration.
     * 
     * @param yaml The YAML configuration
     * @return All Koog configurations bundled together
     */
    fun createAll(yaml: AgentPromptConfiguration): KoogConfigs {
        return KoogConfigs(
            promptConfig = createPromptConfig(yaml),
            strategyConfig = createStrategyConfig(yaml),
            toolRegistryConfig = createToolRegistryConfig(yaml),
            llmConfig = createLlmConfig(yaml)
        )
    }
    
    /**
     * Create Koog prompt template configuration.
     * 
     * Used by task-15 (Koog PromptTemplate Integration).
     * 
     * @param yaml The YAML configuration
     * @return Koog prompt configuration
     */
    fun createPromptConfig(yaml: AgentPromptConfiguration): KoogPromptConfig {
        return adapter.toKoogPromptConfig(yaml)
    }
    
    /**
     * Create Koog GraphStrategy configuration.
     * 
     * Used by task-14 (Koog GraphStrategy for Agent Loop).
     * 
     * @param yaml The YAML configuration
     * @return Koog strategy configuration
     */
    fun createStrategyConfig(yaml: AgentPromptConfiguration): KoogStrategyConfig {
        return adapter.toKoogStrategyConfig(yaml)
    }
    
    /**
     * Create Koog ToolRegistry configuration.
     * 
     * Used by task-16 (ToolRegistry with i2vision and MCP Tools).
     * 
     * @param yaml The YAML configuration
     * @return Koog tool registry configuration
     */
    fun createToolRegistryConfig(yaml: AgentPromptConfiguration): KoogToolRegistryConfig {
        return adapter.toKoogToolRegistryConfig(yaml)
    }
    
    /**
     * Create Koog LLM configuration.
     * 
     * Used for configuring the LLM client in Koog.
     * 
     * @param yaml The YAML configuration
     * @return Koog LLM configuration
     */
    fun createLlmConfig(yaml: AgentPromptConfiguration): KoogLlmConfig {
        return KoogLlmConfig(
            provider = yaml.model.provider,
            modelId = yaml.model.id,
            baseUrl = yaml.llm.baseUrl,
            apiKey = yaml.llm.apiKey,
            temperature = yaml.model.temperature,
            topP = yaml.model.topP,
            topK = yaml.model.topK,
            maxTokens = yaml.model.maxTokens,
            retries = yaml.llm.retries,
            timeoutSeconds = yaml.llm.timeoutSeconds,
            streaming = yaml.llm.streaming
        )
    }
}

/**
 * Bundled Koog configurations.
 * 
 * Contains all configurations needed to initialize a Koog-based agent.
 * 
 * @property promptConfig Prompt template configuration
 * @property strategyConfig GraphStrategy configuration
 * @property toolRegistryConfig ToolRegistry configuration
 * @property llmConfig LLM client configuration
 */
data class KoogConfigs(
    val promptConfig: KoogPromptConfig,
    val strategyConfig: KoogStrategyConfig,
    val toolRegistryConfig: KoogToolRegistryConfig,
    val llmConfig: KoogLlmConfig
)

/**
 * Koog LLM client configuration.
 * 
 * @property provider LLM provider name (e.g., "Ollama", "OpenAI")
 * @property modelId Model identifier (e.g., "llama3.2:3b", "gpt-4")
 * @property baseUrl API base URL (for HTTP providers)
 * @property apiKey API key (if required)
 * @property temperature LLM temperature
 * @property topP Top-p sampling parameter
 * @property topK Top-k sampling parameter
 * @property maxTokens Maximum tokens to generate
 * @property retries Number of retries on failure
 * @property timeoutSeconds Request timeout in seconds
 * @property streaming Whether streaming is enabled
 */
data class KoogLlmConfig(
    val provider: String,
    val modelId: String,
    val baseUrl: String? = null,
    val apiKey: String? = null,
    val temperature: Double,
    val topP: Double,
    val topK: Int,
    val maxTokens: Int,
    val retries: Int,
    val timeoutSeconds: Long,
    val streaming: Boolean
)

/**
 * Extension function to create Koog configs directly from YAML path.
 * 
 * Example:
 * ```kotlin
 * val configs = "path/to/config.yaml".toKoogConfigs()
 * ```
 */
suspend fun String.toKoogConfigs(): KoogConfigs {
    val yaml = YamlConfigLoader.load(this)
    return KoogConfigFactory().createAll(yaml)
}

/**
 * Extension function to create Koog configs from default layer config.
 * 
 * Example:
 * ```kotlin
 * val configs = VslfcLayer.CODE.toKoogConfigs()
 * ```
 */
fun com.i2vision.agent.VslfcLayer.toKoogConfigs(): KoogConfigs {
    val yaml = DefaultConfigs.forLayer(this)
    return KoogConfigFactory().createAll(yaml)
}
