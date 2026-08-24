/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.llm

enum class ToolCallingMode {
    PROMPT_BASED,
    NATIVE
}

data class ProviderCapabilities(
    val supportsStreaming: Boolean = false,
    val toolCallingMode: ToolCallingMode = ToolCallingMode.PROMPT_BASED,
    val supportsVision: Boolean = false,
    val maxContextTokens: Int = 8192
)

data class ModelSettings(
    val temperature: Double = 0.7,
    val topP: Double = 0.9,
    val topK: Int = 40,
    val maxTokens: Int = 2048,
    val stop: List<String> = emptyList()
)

data class ProviderPromptProfile(
    val systemRole: String? = null,
    val toolRules: String? = null
)

data class SessionPolicy(
    val compactionThreshold: Int = 85,
    val resetOnProviderChange: Boolean = true
)

data class ProviderProfile(
    val provider: LLMProvider,
    val modelDefaults: ModelSettings = ModelSettings(),
    val capabilities: ProviderCapabilities = ProviderCapabilities(),
    val prompt: ProviderPromptProfile = ProviderPromptProfile(),
    val sessionPolicy: SessionPolicy = SessionPolicy()
)

data class ProviderResolution(
    val provider: LLMProvider,
    val profile: ProviderProfile
)

object ProviderProfiles {

    fun forProvider(provider: LLMProvider): ProviderProfile = when (provider) {
        LLMProvider.OLLAMA -> ProviderProfile(
            provider = provider,
            modelDefaults = ModelSettings(
                temperature = 0.7,
                topP = 0.9,
                topK = 40,
                maxTokens = 2048
            ),
            capabilities = ProviderCapabilities(
                supportsStreaming = false,
                toolCallingMode = ToolCallingMode.PROMPT_BASED,
                supportsVision = false,
                maxContextTokens = 8192
            ),
            prompt = ProviderPromptProfile(
                systemRole = null,
                toolRules = """
                    Tool rules:
                    - When you need data outside your own knowledge, call a tool.
                    - Return only the JSON object expected by the tool schema.
                    - If the previous tool call failed validation, correct the JSON and retry once.
                    - Never invent tool output; wait for the actual tool response.
                """.trimIndent()
            ),
            sessionPolicy = SessionPolicy(
                compactionThreshold = 85,
                resetOnProviderChange = true
            )
        )

        LLMProvider.DEEPSEEK -> ProviderProfile(
            provider = provider,
            modelDefaults = ModelSettings(
                temperature = 0.7,
                topP = 0.9,
                topK = 40,
                maxTokens = 4096
            ),
            capabilities = ProviderCapabilities(
                supportsStreaming = true,
                toolCallingMode = ToolCallingMode.PROMPT_BASED,
                supportsVision = false,
                maxContextTokens = 65536
            ),
            prompt = ProviderPromptProfile(
                systemRole = null,
                toolRules = """
                    Tool rules:
                    - Use tools only when the requested information cannot be answered directly.
                    - Emit a single valid JSON tool call with required fields only.
                    - If a tool call fails, recover by fixing the input schema.
                """.trimIndent()
            )
        )

        LLMProvider.MISTRAL -> ProviderProfile(
            provider = provider,
            modelDefaults = ModelSettings(
                temperature = 0.7,
                topP = 0.9,
                topK = 40,
                maxTokens = 4096
            ),
            capabilities = ProviderCapabilities(
                supportsStreaming = true,
                toolCallingMode = ToolCallingMode.PROMPT_BASED,
                supportsVision = false,
                maxContextTokens = 32768
            ),
            prompt = ProviderPromptProfile(
                systemRole = null,
                toolRules = """
                    Tool rules:
                    - Prefer native tool calls when available.
                    - Use concise tool inputs and match field names exactly.
                    - Do not combine multiple tool calls into one message unless explicitly required.
                """.trimIndent()
            )
        )

        LLMProvider.THREED_LLM -> ProviderProfile(
            provider = provider,
            modelDefaults = ModelSettings(
                temperature = 0.7,
                topP = 0.9,
                topK = 40,
                maxTokens = 4096
            ),
            capabilities = ProviderCapabilities(
                supportsStreaming = true,
                toolCallingMode = ToolCallingMode.PROMPT_BASED,
                supportsVision = false,
                maxContextTokens = 65536
            ),
            prompt = ProviderPromptProfile(
                systemRole = null,
                toolRules = """
                    Tool rules:
                    - Your response will be parsed by an 8-stage tool-extraction pipeline.
                    - Place exactly one tool call in a fenced JSON block.
                    - Include all required arguments and no commentary inside the JSON block.
                    - If extraction fails, simplify the JSON shape and retry.
                """.trimIndent()
            ),
            sessionPolicy = SessionPolicy(
                compactionThreshold = 85,
                resetOnProviderChange = true
            )
        )
    }
}

object PromptAssembler {

    fun assemble(
        coreRules: String,
        projectContext: String? = null,
        toolProtocol: String? = null,
        profile: ProviderProfile,
        toolsEnabled: Boolean = true
    ): String = buildString {
        append(coreRules.trim())

        if (!projectContext.isNullOrBlank()) {
            append("\n\n")
            append(projectContext.trim())
        }

        if (!toolProtocol.isNullOrBlank()) {
            append("\n\n")
            append(toolProtocol.trim())
        }

        if (toolsEnabled && !profile.prompt.toolRules.isNullOrBlank()) {
            append("\n\n")
            append(profile.prompt.toolRules.trim())
        }
    }
}
