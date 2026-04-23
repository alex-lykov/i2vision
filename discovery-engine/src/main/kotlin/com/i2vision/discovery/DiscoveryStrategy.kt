package com.i2vision.discovery

/**
 * Configurable discovery parameters for flow, logic, and quality filtering.
 * Allows customization of discovery behavior per project/module via templates or strategy files.
 */
data class DiscoveryStrategy(
    val name: String,
    val description: String = "",

    // Flow discovery configuration
    val flowConfig: FlowDiscoveryConfig = FlowDiscoveryConfig(),

    // Quality gate thresholds
    val qualityGates: QualityGateConfig = QualityGateConfig(),

    // Link generation limits
    val linkConfig: LinkGenerationConfig = LinkGenerationConfig(),

    // Feature flags for discovery stages
    val enabledStages: Set<DiscoveryStage> = DiscoveryStage.ALL
) {
    data class FlowDiscoveryConfig(
        /** Maximum depth for call hierarchy traversal */
        val maxCallDepth: Int = 5,

        /** Maximum number of steps to capture per flow */
        val maxStepsPerFlow: Int = 15,

        /** Maximum number of flows to discover (0 = unlimited) */
        val maxFlows: Int = 0,

        /** Minimum steps required for a valid flow */
        val minSteps: Int = 1,

        /** Enable business logic filtering */
        val enableBusinessLogicFilter: Boolean = true,

        /** Patterns for entry point detection (class/function names) */
        val entryPointPatterns: List<String> = listOf(
            ".*Controller$", ".*Handler$", ".*Orchestrator$",
            ".*Agent$", ".*Service$", ".*Repository$"
        ),

        /** Annotation patterns that indicate entry points */
        val entryAnnotations: List<String> = listOf(
            "@RestController", "@Controller", "@GetMapping",
            "@PostMapping", "@EventListener", "@Scheduled"
        ),

        /** Patterns to exclude (tests, internal) */
        val excludePatterns: List<String> = listOf(
            ".*Test$", ".*TestKt$", ".*Spec$"
        )
    )

    data class QualityGateConfig(
        /** Minimum business steps required for a flow to be valid */
        val minBusinessSteps: Int = 1,

        /** Minimum number of distinct participants (files) */
        val minParticipants: Int = 1,

        /** Maximum ratio of self-calls (0.0-1.0) */
        val maxSelfCallRatio: Double = 0.5,

        /** Require error handling paths */
        val requireErrorPath: Boolean = false,

        /** Quality threshold for HIGH priority flows (0.0-1.0) */
        val highPriorityThreshold: Double = 0.7,

        /** Quality threshold for MEDIUM priority flows */
        val mediumPriorityThreshold: Double = 0.5,

        /** Quality threshold for LOW priority flows */
        val lowPriorityThreshold: Double = 0.3
    )

    data class LinkGenerationConfig(
        /** Maximum links to create per flow */
        val maxFlowLinks: Int = 100,

        /** Maximum requirements to link */
        val maxRequirementLinks: Int = 20,

        /** Maximum components to link */
        val maxComponentLinks: Int = 50,

        /** Confidence threshold for auto-generated links (0.0-1.0) */
        val minConfidence: Double = 0.60
    )

    enum class DiscoveryStage {
        CODE, FLOW, LOGIC, STRUCTURE, VISION, LINKS, TRACEABILITY;

        companion object {
            val ALL = values().toSet()
            val MINIMAL = setOf(CODE, FLOW, LOGIC)
            val NO_LINKS = setOf(CODE, FLOW, LOGIC, STRUCTURE, VISION)
        }
    }

    companion object {
        /** Conservative strategy - high quality, fewer flows */
        val CONSERVATIVE = DiscoveryStrategy(
            name = "conservative",
            description = "High quality filtering, produces fewer but more relevant flows",
            qualityGates = QualityGateConfig(
                minBusinessSteps = 3,
                minParticipants = 2,
                maxSelfCallRatio = 0.3,
                requireErrorPath = true,
                highPriorityThreshold = 0.8,
                mediumPriorityThreshold = 0.6,
                lowPriorityThreshold = 0.4
            )
        )

        /** Balanced strategy - default for STANDARD depth */
        val BALANCED = DiscoveryStrategy(
            name = "balanced",
            description = "Balanced quality vs quantity for general use",
            qualityGates = QualityGateConfig(
                minBusinessSteps = 1,
                minParticipants = 1,
                maxSelfCallRatio = 0.5,
                requireErrorPath = false
            )
        )

        /** Permissive strategy - maximum discovery for BROWSE depth */
        val PERMISSIVE = DiscoveryStrategy(
            name = "permissive",
            description = "Maximum discovery, minimal filtering",
            flowConfig = FlowDiscoveryConfig(
                maxCallDepth = 3,
                maxStepsPerFlow = 10,
                enableBusinessLogicFilter = false,
                minSteps = 0
            ),
            qualityGates = QualityGateConfig(
                minBusinessSteps = 0,
                minParticipants = 1,
                maxSelfCallRatio = 0.8,
                requireErrorPath = false,
                lowPriorityThreshold = 0.1
            ),
            linkConfig = LinkGenerationConfig(
                maxFlowLinks = 50,
                maxRequirementLinks = 10,
                minConfidence = 0.50
            )
        )

        /** Deep analysis strategy for LLM-enhanced discovery */
        val DEEP_ANALYSIS = DiscoveryStrategy(
            name = "deep",
            description = "Deep analysis with LLM enhancement",
            flowConfig = FlowDiscoveryConfig(
                maxCallDepth = 8,
                maxStepsPerFlow = 25,
                maxFlows = 200
            ),
            qualityGates = QualityGateConfig(
                minBusinessSteps = 2,
                minParticipants = 2,
                maxSelfCallRatio = 0.4,
                requireErrorPath = false
            ),
            linkConfig = LinkGenerationConfig(
                maxFlowLinks = 200,
                maxRequirementLinks = 50,
                minConfidence = 0.70
            )
        )

        val DEFAULT = BALANCED
    }
}
