/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.llm

/**
 * Interface for model provider.
 * 
 * Abstracts the LLM generation functionality to allow
 * different implementations (Ollama, cloud providers, etc.)
 */
interface ModelProvider {
    /**
     * Generate text from a prompt.
     * 
     * @param prompt The prompt to send to the model
     * @param temperature Sampling temperature (0.0-1.0)
     * @param topP Nucleus sampling parameter
     * @param topK Top-K sampling parameter
     * @param maxTokens Maximum tokens to generate
     * @param timeoutSeconds Request timeout in seconds
     * @return Generated text response
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
