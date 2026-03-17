package com.i2vision.discover.api.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Result of a discovery pipeline execution.
 */
@Serializable
data class PipelineResult(
    val success: Boolean,
    val artifacts: List<DiscoveryArtifact> = emptyList(),
    val errors: List<String> = emptyList(),
    val metadata: Map<String, String> = emptyMap()
)

/**
 * Discovered artifact from the pipeline.
 */
@Serializable
data class DiscoveryArtifact(
    val layer: String,
    val path: String,
    val content: String,
    val confidence: Double = 0.0
)

/**
 * Discovery depth levels.
 */
enum class DiscoveryDepth {
    BROWSE,     // Project-wide scan for initial overview
    STANDARD,   // Contract-based focused discovery
    DEEP        // LLM-enhanced deep analysis
}

/**
 * Contract hint for contract-based discovery.
 */
@Serializable
data class ContractHint(
    val contractPath: String,
    val relatedFiles: List<String> = emptyList(),
    val entryPoints: List<String> = emptyList()
)

/**
 * High-level DiscoveryIntent for user-driven discovery.
 */
@Serializable
data class DiscoveryIntent(
    val goal: DiscoveryGoal,
    val depth: IntentDepth,
    val quality: DiscoveryQuality,
    val layerFocus: List<String> = emptyList(),
    val customParameters: Map<String, String> = emptyMap()
)

/**
 * Discovery goal types.
 */
enum class DiscoveryGoal {
    UNDERSTAND,      // Understand the codebase
    ANALYZE,         // Analyze specific components
    VALIDATE,        // Validate contracts
    GENERATE,        // Generate documentation
    REFACTOR         // Refactor code
}

/**
 * Intent depth levels.
 */
enum class IntentDepth {
    BROWSE,          // Quick overview
    STANDARD,        // Standard analysis
    DEEP             // Deep analysis
}

/**
 * Discovery quality levels.
 */
enum class DiscoveryQuality {
    FAST,            // Fast, approximate results
    BALANCED,        // Balance between speed and accuracy
    THOROUGH         // Thorough, accurate results
}

/**
 * Modifiable parameter set for discovery.
 * Uses JsonElement for flexible parameter serialization.
 */
@Serializable
data class ModifiableParameterSet(
    val name: String,
    val description: String,
    val parameters: Map<String, JsonElement> = emptyMap()
)
