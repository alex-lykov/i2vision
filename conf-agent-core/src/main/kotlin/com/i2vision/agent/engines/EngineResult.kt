package com.i2vision.agent.engines

internal data class EngineResult(
    val success: Boolean,
    val output: String = "",
    val error: String? = null
) {
    fun format(): String = if (success) "success\n$output" else "error\n${error.orEmpty()}"

    companion object {
        fun parse(payload: String): EngineResult {
            val lines = payload.lines()
            val head = lines.firstOrNull()?.trim().orEmpty()
            val body = lines.drop(1).joinToString("\n").trim()
            return if (head.equals("success", ignoreCase = true)) {
                EngineResult(success = true, output = body)
            } else {
                EngineResult(success = false, error = body.ifBlank { "Unknown tool error" })
            }
        }
    }
}

