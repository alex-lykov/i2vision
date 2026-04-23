package com.i2vision.discovery

data class DiscoveryManifest(
    val projectPath: String,
    val timestamp: String = java.time.Instant.now().toString(),
    val version: String = "v1",
    val selectedStrategy: String,
    val confidence: Double,
    val heuristics: Map<String, Double> = emptyMap(),
    val recommendedActions: List<Map<String, Any>> = emptyList(),
    val gitCommit: String? = null,
    val origin: String = "auto",
    val applied: Boolean = false,
    val backupFile: String? = null
)

