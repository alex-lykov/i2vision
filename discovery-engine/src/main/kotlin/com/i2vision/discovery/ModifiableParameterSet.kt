package com.i2vision.discovery

/**
 * Modifiable Parameter Set - Tunable parameters organized by category.
 * Allows users to adjust discovery behavior per project/module.
 */
data class ModifiableParameterSet(
    val name: String,
    val description: String = "",

    // Parameter categories
    val flowParameters: FlowDiscoveryParameters = FlowDiscoveryParameters(),
    val qualityGateParameters: QualityGateParameters = QualityGateParameters(),
    val linkGenerationParameters: LinkGenerationParameters = LinkGenerationParameters(),
    val architectureDetectionParameters: ArchitectureDetectionParameters = ArchitectureDetectionParameters(),
    val clusteringParameters: ClusteringParameters = ClusteringParameters(),
    val llmEnhancementParameters: LlmEnhancementParameters = LlmEnhancementParameters()
) {
    /**
     * Flow Discovery Parameters - Control flow discovery behavior
     */
    data class FlowDiscoveryParameters(
        val maxCallDepth: Int = 5,
        val maxStepsPerFlow: Int = 15,
        val maxFlows: Int = 0, // 0 = unlimited
        val minSteps: Int = 1,
        val enableBusinessLogicFilter: Boolean = true,
        val entryPointPatterns: List<String> = listOf(
            ".*Controller$", ".*Handler$", ".*Orchestrator$",
            ".*Agent$", ".*Service$", ".*Repository$"
        ),
        val entryAnnotations: List<String> = listOf(
            "@RestController", "@Controller", "@GetMapping",
            "@PostMapping", "@EventListener", "@Scheduled"
        ),
        val excludePatterns: List<String> = listOf(
            ".*Test$", ".*TestKt$", ".*Spec$"
        )
    )

    /**
     * Quality Gate Parameters - Control quality filtering
     */
    data class QualityGateParameters(
        val minBusinessSteps: Int = 1,
        val minParticipants: Int = 1,
        val maxSelfCallRatio: Double = 0.5,
        val requireErrorPath: Boolean = false,
        val highPriorityThreshold: Double = 0.7,
        val mediumPriorityThreshold: Double = 0.5,
        val lowPriorityThreshold: Double = 0.3
    )

    /**
     * Link Generation Parameters - Control cross-layer link creation
     */
    data class LinkGenerationParameters(
        val maxFlowLinks: Int = 100,
        val maxRequirementLinks: Int = 20,
        val maxComponentLinks: Int = 50,
        val minConfidence: Double = 0.60
    )

    /**
     * Architecture Detection Parameters - Control architecture classification
     */
    data class ArchitectureDetectionParameters(
        val sensitivity: String = "medium", // low, medium, high
        val frameworkHints: List<String> = emptyList(),
        val customPatterns: List<String> = emptyList(),
        val enableLayerDetection: Boolean = true,
        val enablePatternDetection: Boolean = true
    )

    /**
     * Clustering Parameters - Control module boundary detection
     */
    data class ClusteringParameters(
        val algorithm: String = "default", // default, hierarchical, density
        val resolution: String = "medium", // low, medium, high
        val minClusterSize: Int = 3,
        val maxClusterSize: Int = 50,
        val enableCrossModuleAnalysis: Boolean = true
    )

    /**
     * LLM Enhancement Parameters - Control LLM-based enhancement
     */
    data class LlmEnhancementParameters(
        val llmModel: String = "minimax-m2.1",
        val enhancementDepth: String = "none", // none, partial, full
        val temperature: Double = 0.2,
        val enableRuleVerbalization: Boolean = false,
        val enableFlowDescription: Boolean = false,
        val enableArchitectureSummarization: Boolean = false
    )

    companion object {
        /**
         * Convert from DiscoveryStrategy to ModifiableParameterSet
         */
        fun from(strategy: DiscoveryStrategy): ModifiableParameterSet {
            return ModifiableParameterSet(
                name = strategy.name,
                description = strategy.description,
                flowParameters = FlowDiscoveryParameters(
                    maxCallDepth = strategy.flowConfig.maxCallDepth,
                    maxStepsPerFlow = strategy.flowConfig.maxStepsPerFlow,
                    maxFlows = strategy.flowConfig.maxFlows,
                    minSteps = strategy.flowConfig.minSteps,
                    enableBusinessLogicFilter = strategy.flowConfig.enableBusinessLogicFilter,
                    entryPointPatterns = strategy.flowConfig.entryPointPatterns,
                    entryAnnotations = strategy.flowConfig.entryAnnotations,
                    excludePatterns = strategy.flowConfig.excludePatterns
                ),
                qualityGateParameters = QualityGateParameters(
                    minBusinessSteps = strategy.qualityGates.minBusinessSteps,
                    minParticipants = strategy.qualityGates.minParticipants,
                    maxSelfCallRatio = strategy.qualityGates.maxSelfCallRatio,
                    requireErrorPath = strategy.qualityGates.requireErrorPath,
                    highPriorityThreshold = strategy.qualityGates.highPriorityThreshold,
                    mediumPriorityThreshold = strategy.qualityGates.mediumPriorityThreshold,
                    lowPriorityThreshold = strategy.qualityGates.lowPriorityThreshold
                ),
                linkGenerationParameters = LinkGenerationParameters(
                    maxFlowLinks = strategy.linkConfig.maxFlowLinks,
                    maxRequirementLinks = strategy.linkConfig.maxRequirementLinks,
                    maxComponentLinks = strategy.linkConfig.maxComponentLinks,
                    minConfidence = strategy.linkConfig.minConfidence
                )
            )
        }

        /**
         * Convert from ModifiableParameterSet to DiscoveryStrategy
         */
        fun toDiscoveryStrategy(parameterSet: ModifiableParameterSet): DiscoveryStrategy {
            return DiscoveryStrategy(
                name = parameterSet.name,
                description = parameterSet.description,
                flowConfig = DiscoveryStrategy.FlowDiscoveryConfig(
                    maxCallDepth = parameterSet.flowParameters.maxCallDepth,
                    maxStepsPerFlow = parameterSet.flowParameters.maxStepsPerFlow,
                    maxFlows = parameterSet.flowParameters.maxFlows,
                    minSteps = parameterSet.flowParameters.minSteps,
                    enableBusinessLogicFilter = parameterSet.flowParameters.enableBusinessLogicFilter,
                    entryPointPatterns = parameterSet.flowParameters.entryPointPatterns,
                    entryAnnotations = parameterSet.flowParameters.entryAnnotations,
                    excludePatterns = parameterSet.flowParameters.excludePatterns
                ),
                qualityGates = DiscoveryStrategy.QualityGateConfig(
                    minBusinessSteps = parameterSet.qualityGateParameters.minBusinessSteps,
                    minParticipants = parameterSet.qualityGateParameters.minParticipants,
                    maxSelfCallRatio = parameterSet.qualityGateParameters.maxSelfCallRatio,
                    requireErrorPath = parameterSet.qualityGateParameters.requireErrorPath,
                    highPriorityThreshold = parameterSet.qualityGateParameters.highPriorityThreshold,
                    mediumPriorityThreshold = parameterSet.qualityGateParameters.mediumPriorityThreshold,
                    lowPriorityThreshold = parameterSet.qualityGateParameters.lowPriorityThreshold
                ),
                linkConfig = DiscoveryStrategy.LinkGenerationConfig(
                    maxFlowLinks = parameterSet.linkGenerationParameters.maxFlowLinks,
                    maxRequirementLinks = parameterSet.linkGenerationParameters.maxRequirementLinks,
                    maxComponentLinks = parameterSet.linkGenerationParameters.maxComponentLinks,
                    minConfidence = parameterSet.linkGenerationParameters.minConfidence
                )
            )
        }
    }
}
