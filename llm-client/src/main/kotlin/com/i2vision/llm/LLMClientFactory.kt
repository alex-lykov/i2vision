/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.llm

/**
 * Enum representing supported LLM providers.
 */
enum class LLMProvider {
    /**
     * Local Ollama instance (free, self-hosted)
     */
    OLLAMA,

    /**
     * DeepSeek cloud API (paid, high-quality reasoning)
     */
    DEEPSEEK,

    /**
     * 3D LLM proxy (FreeDeepseekAPI - DeepSeek Web V3 via OpenAI-compatible endpoint)
     */
    THREED_LLM
}

/**
 * Configuration for a specific LLM provider.
 */
data class ProviderConfig(
    val apiKey: String? = null,
    val baseUrl: String? = null,
    val model: String? = null,
    val organization: String? = null
)

/**
 * Factory for creating LLM clients based on provider configuration.
 * 
 * Provides a unified interface for accessing different LLM backends.
 */
object LLMClientFactory {
    
    /**
     * Create an LLM client for the specified provider.
     * 
     * @param provider The LLM provider to use
     * @param config Provider-specific configuration
     * @return ModelProvider implementation for the specified provider
     * @throws IllegalArgumentException if required configuration is missing
     */
    fun createClient(
        provider: LLMProvider,
        config: ProviderConfig
    ): ModelProvider {
        return when (provider) {
            LLMProvider.OLLAMA -> {
                OllamaLlmClient(
                    baseUrl = config.baseUrl ?: "http://localhost:11434",
                    defaultModel = config.model ?: "llama3.2:3b"
                )
            }
            
            LLMProvider.DEEPSEEK -> {
                val apiKey = config.apiKey
                    ?: throw IllegalArgumentException("DeepSeek API key is required")

                DeepSeekClient(
                    apiKey = apiKey,
                    baseUrl = config.baseUrl ?: "https://api.deepseek.com",
                    defaultModel = config.model ?: "deepseek-chat"
                )
            }

            LLMProvider.THREED_LLM -> {
                ThreeDLlmClient(
                    baseUrl = config.baseUrl ?: "http://host2.onldigital.com:9654",
                    defaultModel = config.model ?: "deepseek-web-v3"
                )
            }
        }
    }
    
    /**
     * Create a default Ollama client.
     */
    fun createOllamaClient(
        baseUrl: String = "http://localhost:11434",
        model: String = "llama3.2:3b"
    ): OllamaLlmClient {
        return OllamaLlmClient(baseUrl, model)
    }
    
    /**
     * Create a DeepSeek client.
     */
    fun createDeepSeekClient(
        apiKey: String,
        baseUrl: String = "https://api.deepseek.com",
        model: String = "deepseek-chat"
    ): DeepSeekClient {
        return DeepSeekClient(apiKey, baseUrl, model)
    }

}

/**
 * Builder for creating ProviderConfig instances.
 */
class ProviderConfigBuilder {
    private var apiKey: String? = null
    private var baseUrl: String? = null
    private var model: String? = null
    private var organization: String? = null
    
    fun apiKey(key: String) = apply { this.apiKey = key }
    fun baseUrl(url: String) = apply { this.baseUrl = url }
    fun model(name: String) = apply { this.model = name }
    fun organization(org: String) = apply { this.organization = org }
    
    fun build(): ProviderConfig {
        return ProviderConfig(apiKey, baseUrl, model, organization)
    }
}

/**
 * Helper functions for creating ProviderConfig instances.
 */
fun ollamaConfig(
    baseUrl: String = "http://localhost:11434",
    model: String = "llama3.2:3b"
): ProviderConfig {
    return ProviderConfig(baseUrl = baseUrl, model = model)
}

fun deepseekConfig(
    apiKey: String,
    baseUrl: String = "https://api.deepseek.com",
    model: String = "deepseek-chat"
): ProviderConfig {
    return ProviderConfig(apiKey = apiKey, baseUrl = baseUrl, model = model)
}

