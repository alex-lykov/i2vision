/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.agent

import ai.koog.agents.core.tools.ToolRegistry
import kotlinx.coroutines.flow.Flow

/**
 * Primary contract for every configurable AI agent.
 *
 * Type parameters:
 * - [ConfigT]   – YAML-backed configuration object
 * - [ResponseT] – Final synchronous response type
 * - [ChunkT]    – Streaming chunk type
 * - [ContextT]  – Request context (e.g. TaskContext)
 */
interface ConfigurableAgent<ConfigT, ResponseT, ChunkT, ContextT> {
    val configPath: String
    val config: ConfigT
    val toolRegistry: ToolRegistry
    suspend fun process(task: String, context: ContextT): ResponseT
    suspend fun processStreaming(task: String, context: ContextT): Flow<ChunkT>
}

