package com.i2vision.agent.engines

data class ToolExecutionResult(
    val toolName: String,
    val parameters: Map<String, Any>,
    val success: Boolean,
    val output: String,
    val durationMs: Long,
    val errorMessage: String? = null
)

