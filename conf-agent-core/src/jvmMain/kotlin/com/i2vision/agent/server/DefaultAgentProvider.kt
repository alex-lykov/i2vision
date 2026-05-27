/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.agent.server

import com.i2vision.agent.*
import com.i2vision.agent.config.AgentPromptConfiguration
import com.i2vision.agent.config.ConfigurationAdapter
import com.i2vision.agent.config.DefaultConfigs
import com.i2vision.agent.config.YamlConfigLoader
import com.i2vision.agent.koog.I2VisionKoogAgent
import com.i2vision.agent.tools.DiscoveryCache
import com.i2vision.agent.tools.DiscoveryCacheFactory
import com.i2vision.agent.tools.DiscoveryEngine
import com.i2vision.agent.tools.DiscoveryEngineFactory
import com.i2vision.agent.tools.I2VisionToolRegistry
import com.i2vision.agent.tools.InstantContextProvider
import com.i2vision.agent.tools.InstantContextProviderFactory
import com.i2vision.llm.OllamaLlmClient
import com.i2vision.llm.ToolRegistry as KoogToolRegistry
import com.i2vision.agent.tools.ToolRegistry as I2VisionToolRegistryImpl
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
    private val instantContextProvider: InstantContextProvider = InstantContextProviderFactory.create(workspaceRoot),
    private val discoveryCache: DiscoveryCache = DiscoveryCacheFactory.create(
        cacheStore = FileCacheStore(I2VisionPaths.getProjectCacheDir(workspaceRoot))
    )
) : I2VisionAgentProvider {

    override val id: String = generateProviderId("kotlin")
    override val type: String = "kotlin"
    override val displayName: String = createProviderDisplayName("Kotlin", "Koog")

    private val discoveryEngine = DiscoveryEngineFactory.create(workspaceRoot)

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
                val yamlConfig = YamlConfigLoader.load(file.absolutePath)
                val agentConfig = ConfigurationAdapter().toAgentConfig(yamlConfig)
                
                AgentConfigSummary(
                    id = yamlConfig.key,
                    name = yamlConfig.key,
                    description = "",
                    modelProvider = agentConfig.modelProvider,
                    modelId = agentConfig.modelId,
                    supportedLayers = listOf(VslfcLayer.fromString(yamlConfig.agentType)),
                    maxContextTokens = agentConfig.maxContextTokens.toLong(),
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
        val yamlConfig = DefaultConfigs.forLayer(layer)
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

        val yamlConfig = YamlConfigLoader.load(configPath.toString())
        return createAgentFromYamlConfig(layer, yamlConfig)
    }

    private suspend fun createAgentFromYamlConfig(
        layer: VslfcLayer,
        yamlConfig: AgentPromptConfiguration
    ): I2VisionAgent {
        // Convert to agent config
        val adapter = ConfigurationAdapter()
        val agentConfig = adapter.toAgentConfig(yamlConfig)

        // Create Koog tool registry (wrapper around i2vision tools)
        val toolRegistry = createKoogToolRegistry(
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

    private fun createKoogToolRegistry(
        config: AgentConfig,
        layer: VslfcLayer,
        workspaceRoot: String,
        instantContext: InstantContextProvider,
        discoveryEngine: DiscoveryEngine,
        discoveryCache: DiscoveryCache
    ): KoogToolRegistry {
        // Create i2vision tool registry
        val toolRegistry = I2VisionToolRegistry.create(
            config = config,
            layer = layer,
            workspaceRoot = workspaceRoot,
            instantContext = instantContext,
            discoveryEngine = discoveryEngine,
            discoveryCache = discoveryCache
        )
        
        // Wrap it as a Koog ToolRegistry
        return KoogToolRegistryWrapper(toolRegistry)
    }

    override suspend fun dispose() {
        discoveryEngine.close()
        discoveryCache.close()
    }
}

/**
 * Wrapper to adapt ToolRegistry to Koog ToolRegistry interface.
 */
class KoogToolRegistryWrapper(
    private val toolRegistry: I2VisionToolRegistryImpl
) : KoogToolRegistry {
    override suspend fun execute(toolName: String, args: Map<String, Any>): Any {
        val result = toolRegistry.execute(toolName, args)
        return result.output ?: result.error ?: ""
    }
}

/**
 * Default configurations for each layer when no configId is provided.
 */
object DefaultConfigs {

    fun forLayer(layer: VslfcLayer): AgentPromptConfiguration {
        return when (layer) {
            VslfcLayer.VISION -> createDefaultConfig(
                key = "vision-default",
                layer = "VISION",
                tools = listOf("file_search", "artifact_discovery", "contract_validation")
            )

            VslfcLayer.STRUCTURE -> createDefaultConfig(
                key = "structure-default",
                layer = "STRUCTURE",
                tools = listOf("file_search", "symbol_analysis", "call_hierarchy")
            )

            VslfcLayer.LOGIC -> createDefaultConfig(
                key = "logic-default",
                layer = "LOGIC",
                tools = listOf("file_search", "code_analysis", "dependency_analysis")
            )

            VslfcLayer.FLOW -> createDefaultConfig(
                key = "flow-default",
                layer = "FLOW",
                tools = listOf("file_read", "symbol_search", "reference_search")
            )

            VslfcLayer.CODE -> createDefaultConfig(
                key = "code-default",
                layer = "CODE",
                tools = listOf("file_read", "symbol_search", "reference_search")
            )
        }
    }
    
    private fun createDefaultConfig(
        key: String,
        layer: String,
        tools: List<String>
    ): AgentPromptConfiguration {
        return AgentPromptConfiguration(
            key = key,
            agentType = layer,
            version = "1.0.0",
            isActive = true,
            systemPromptTemplate = "You are a $layer layer agent.",
            templateVariables = emptyMap(),
            ruleSetKeys = emptyList(),
            model = com.i2vision.agent.config.ModelConfig(
                provider = "Ollama",
                id = "llama3.2:3b",
                contextLength = 8192,
                temperature = 0.7,
                topP = 0.9,
                topK = 40,
                maxTokens = 4096
            ),
            llm = com.i2vision.agent.config.LlmConfig(
                retries = 3,
                timeoutSeconds = 60,
                streaming = true
            ),
            formattingRules = com.i2vision.agent.config.FormattingRulesConfig(
                indentSize = 4,
                useTabs = false,
                maxLineLength = 120,
                trimTrailingWhitespace = true,
                insertFinalNewline = true
            ),
            iterationSettings = com.i2vision.agent.config.IterationConfig(
                maxIterations = 10,
                maxConsecutiveToolCalls = 12,
                enableKickstart = true,
                kickstartMinInvalidOutputs = 2,
                reflectionEnabled = true,
                selfCorrectionEnabled = true
            ),
            toolSelection = com.i2vision.agent.config.ToolSelectionConfig(
                enabledTools = tools,
                disabledTools = emptyList(),
                toolTimeoutSeconds = 30,
                requireConfirmationFor = emptyList(),
                readOnlyMode = false
            ),
            safety = com.i2vision.agent.config.SafetyConfig(
                allowFileWrites = true,
                allowedDirectories = emptyList(),
                forbiddenDirectories = emptyList(),
                enableBuildVerification = false,
                maxFileSize = 1024 * 1024,
                requireBackupBeforeWrite = true,
                blockGeneratedPaths = listOf("build", "target", "dist", "out", ".gradle"),
                protectedPaths = emptyList(),
                allowHiddenFileWrites = false,
                maxFileSizeBytes = 1024 * 1024
            ),
            parsing = com.i2vision.agent.config.ParsingConfig(
                enabledParsers = listOf(
                    com.i2vision.agent.config.ParserType.HEADER,
                    com.i2vision.agent.config.ParserType.NAKED_JSON,
                    com.i2vision.agent.config.ParserType.XML_INVOKE
                ),
                strictJsonParsing = true,
                allowMarkdownCodeBlocks = true,
                fallbackToPlainText = true,
                maxParseAttempts = 3
            ),
            discovery = com.i2vision.agent.config.DiscoveryConfig(
                enableClusterContext = true,
                cachePath = null,
                autoRefresh = false
            ),
            execution = com.i2vision.agent.config.ExecutionConfig(
                buildCommand = "",
                fileOperationMode = "DIRECT",
                workingDirectory = null
            ),
            formatting = com.i2vision.agent.config.FormattingConfig(
                includeReasoningTrace = true,
                includeToolCallDetails = true,
                compactMode = false,
                syntaxHighlighting = true
            ),
            streaming = com.i2vision.agent.config.StreamingConfig(
                enabled = true,
                chunkSize = 100
            ),
            mcp = com.i2vision.agent.config.McpConfig(
                enabled = false,
                servers = emptyList(),
                injectClusterContext = false
            )
        )
    }
}
