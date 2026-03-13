package com.alyk.ai.koog.core.session

import kotlinx.serialization.Serializable

/**
 * Serializable record for the decision log.
 * Tracks why choices were made (e.g. "Chose React because user mentioned web app").
 */
@Serializable
data class DecisionLogEntry(
    val prompt: String,
    val selectedTools: List<String>,
    val executionStrategy: String,
    val complexity: String,
    val timestampMs: Long,
    val rationale: String? = null
)
