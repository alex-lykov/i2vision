/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.agent.server

import com.i2vision.agent.*
import com.i2vision.agent.config.ConfigurationAdapter
import com.i2vision.agent.config.YamlConfigLoader
import com.i2vision.agent.koog.I2VisionKoogAgent
import com.i2vision.agent.tools.I2VisionToolRegistry
import com.i2vision.discovery.engine.DiscoveryEngine
import com.i2vision.discovery.engine.cache.DiscoveryCache
import com.i2vision.instant.context.InstantContextProvider
import com.i2vision.llm.OllamaLlmClient
import com.i2vision.storage.I2VisionPaths
import com.i2vision.storage.impl.FileCacheStore
import java.io.File
import java.nio.file.Path

/**
 * Default I2VisionAgentProvider implementation that wires real i2vision modules.
 * 
 * Creates I2VisionKoogAgent instances with:
 * - YAML configuration loading
 * - Ollama LLM client
 * - Instant context provider
 * - Discovery cache
 * - Tool registry with i2vision tools
 */
class DefaultAgentProvider(
    private val configDir: Path,
    private val workspaceRoot: String,
    private val llmClient: OllamaLlmClient = OllamaLlmClient(),
    private val instantContextProvider: InstantContextProvider = InstantContextProvider.create(workspaceRoot),
    private val discoveryCache: DiscoveryCache = DiscoveryCache.create(
        cacheStore = FileCacheStore(File(I2VisionPaths.getProjectCacheDir(workspaceRoot)))
    )
) : I2VisionAgentProvider {

    override val id: String = generateProviderId("kotlin")
    override val type: String = "kotlin"
    override val displayName: String = createProviderDisplayName("Kotlin", "Koog")

    private val discoveryEngine = DiscoveryEngine.create(workspaceRoot)
    private val configLoader = YamlConfigLoader()

    override suspend fun ping(): Boolean {
        return try {
            // Check if config directory is accessible
            configDir.toFile().exists()
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun listConfigurations(): List<AgentConfigSummary> {
        val configFiles = configDir.toFile().listFiles { f -> f.extension == "yaml" } ?: emptyArray()
        
        return configFiles.mapNotNull { file ->
            try {
                val yamlConfig = configLoader.load(file.absolutePath)
                val agentConfig = ConfigurationAdapter().toAgentConfig(yamlConfig)
                
                AgentConfigSummary(
                    id = yamlConfig.key,
                    name = yamlConfig.name ?: yamlConfig.key,
                    description = yamlConfig.description ?: "",
                    modelProvider = agentConfig.model.provider,
                    modelId = agentConfig.model.id,
                    supportedLayers = listOf(VslfcLayer.fromString(yamlConfig.layer)),
                    maxContextTokens = agentConfig.model.contextLength.toLong(),
                    isDefault = false
                )
            } catch (e: Exception) {
                null // Skip invalid configs
            }
        }
    }

    override suspend fun createAgent(
        layer: VslfcLayer,
        config: AgentConfig?
    ): I2VisionAgent {
        // If no config provided, use default for layer
        val yamlConfig = if (config != null) {
            // Convert AgentConfig back to YAML config (simplified)
            DefaultConfigs.forLayer(layer)
        } else {
            DefaultConfigs.forLayer(layer)
        }

        return createAgentFromYamlConfig(layer, yamlConfig)
    }

    override suspend fun createAgent(
        layer: VslfcLayer,
        configurationId: String
    ): I2VisionAgent {
        val configPath = configDir.resolve("$configurationId.yaml")
        if (!configPath.toFile().exists()) {
            throw AgentProviderException("Configuration not found: $configurationId")
        }

        val yamlConfig = configLoader.load(configPath.toString())
        return createAgentFromYamlConfig(layer, yamlConfig)
    }

    private suspend fun createAgentFromYamlConfig(
        layer: VslfcLayer,
        yamlConfig: YamlConfigLoader.Config
    ): I2VisionAgent {
        // Convert to agent config
        val agentConfig = ConfigurationAdapter().toAgentConfig(yamlConfig)

        // Create tool registry
        val toolRegistry = I2VisionToolRegistry.create(
            config = agentConfig,
            layer = layer,
            workspaceRoot = workspaceRoot,
            instantContext = instantContextProvider,
            discoveryEngine = discoveryEngine,
            discoveryCache = discoveryCache
        )

        // Build Koog agent
        return I2VisionKoogAgent.fromConfig(
            configPath = configDir.resolve("${yamlConfig.key}.yaml").toString(),
            layer = layer,
            modelProvider = llmClient,
            toolRegistry = toolRegistry,
            instantContextProvider = instantContextProvider,
            discoveryCache = discoveryCache
        )
    }

    override suspend fun dispose() {
        discoveryEngine.close()
        discoveryCache.close()
    }
}

/**
 * Default configurations for each layer when no configId is provided.
 */
object DefaultConfigs {

    fun forLayer(layer: VslfcLayer): YamlConfigLoader.Config {
        return when (layer) {
            VslfcLayer.VISION -> YamlConfigLoader.Config(
                key = "vision-default",
                name = "Vision Layer Default",
                description = "Default configuration for vision layer analysis",
                layer = "VISION",
                strategy = "hierarchical",
                promptTemplate = "default",
                tools = listOf(
                    "file_search",
                    "artifact_discovery",
                    "contract_validation"
                ),
                maxIterations = 10,
                temperature = 0.7
            )

            VslfcLayer.STRUCTURE -> YamlConfigLoader.Config(
                key = "structure-default",
                name = "Structure Layer Default",
                description = "Default configuration for structure layer analysis",
                layer = "STRUCTURE",
                strategy = "depth-first",
                promptTemplate = "default",
                tools = listOf(
                    "file_search",
                    "symbol_analysis",
                    "call_hierarchy"
                ),
                maxIterations = 15,
                temperature = 0.5
            )

            VslfcLayer.LOGIC -> YamlConfigLoader.Config(
                key = "logic-default",
                name = "Logic Layer Default",
                description = "Default configuration for logic layer analysis",
                layer = "LOGIC",
                strategy = "breadth-first",
                promptTemplate = "default",
                tools = listOf(
                    "file_search",
                    "code_analysis",
                    "dependency_analysis"
                ),
                maxIterations = 20,
                temperature = 0.3
            )

            VslfcLayer.FLOW -> YamlConfigLoader.Config(
                key = "flow-default",
                name = "Flow Layer Default",
                description = "Default configuration for flow layer analysis",
                layer = "FLOW",
                strategy = "focused",
                promptTemplate = "default",
                tools = listOf(
                    "file_read",
                    "symbol_search",
                    "reference_search"
                ),
                maxIterations = 25,
                temperature = 0.2
            )

            VslfcLayer.CODE -> YamlConfigLoader.Config(
                key = "code-default",
                name = "Code Layer Default",
                description = "Default configuration for code layer analysis",
                layer = "CODE",
                strategy = "focused",
                promptTemplate = "default",
                tools = listOf(
                    "file_read",
                    "symbol_search",
                    "reference_search"
                ),
                maxIterations = 25,
                temperature = 0.2
            )
        }
    }
}
