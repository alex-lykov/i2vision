package com.i2vision.vslfc

/**
 * VSLFC Structure Definition
 * 
 * This object defines the pure model of VSLFC (Vision-Structure-Logic-Flow-Code) layered structure.
 * It contains no filesystem operations - only the definition of what exists.
 * 
 * This is the single source of truth for:
 * - What layers exist
 * - What contracts exist per layer
 * - What agent configs exist
 * - What directory structure is expected
 */
object VslfcStructure {

    /**
     * The five VSLFC layers in order
     */
    val LAYERS = listOf("vision", "structure", "logic", "flow", "code")

    // Contract templates (must be defined before CONTRACT_TEMPLATES)
    private val visionRequirementTemplate = """
id: REQ-{number}
title: "Requirement Title"
description: "Describe the requirement in detail"
priority: MEDIUM
status: DRAFT
acceptance_criteria:
  - "What must be true for this requirement to be satisfied?"
evidence:
  - file: ""
    line: 0
    confidence: 0.0
""".trimIndent()

    private val visionWithDocsTemplate = """
contract: vision-documentation
version: 1.0
layer: VISION
direction: bidirectional

documentation:
  primary: "README.md"
  secondary: []

mappings:
  - doc_section: "## Purpose"
    layer_field: "requirements"
    parser: "free_text"
    confidence: 0.9
  
  - doc_section: "## Requirements"
    layer_field: "requirements"
    parser: "markdown_list"
    confidence: 0.95
  
  - doc_section: "## Constraints"
    layer_field: "constraints"
    parser: "markdown_list"
    confidence: 0.9

sync_rules:
  on_doc_change: "reimport"
  on_layer_change: "suggest_update"
  conflict_resolution:
    default: "manual_review"
  freshness:
    warning_days: 90
    critical_days: 180
    action_on_stale: "revalidate_with_lower_confidence"

validation:
  - rule: "every_doc_requirement_has_code_evidence"
    severity: "warning"
  - rule: "confidence_decay"
    formula: "confidence * (0.95 ^ months_since_update)"
    threshold: 0.7
    action: "revalidate"
""".trimIndent()

    private val visionToStructureTemplate = """
        version: "1.0"
        contract: vision-to-structure
        description: Defines expected structure artifacts from vision layer
        expectations:
          - component-diagrams
          - architecture-overview
    """.trimIndent()

    private val visionToCodeTemplate = """
        version: "1.0"
        contract: vision-to-code
        description: Defines expected code artifacts from vision layer
        expectations:
          - coding-standards
          - naming-conventions
    """.trimIndent()

    private val structureFromVisionTemplate = """
        version: "1.0"
        contract: structure-from-vision
        description: Defines how structure layer consumes vision artifacts
        expectations:
          - component-definitions
          - dependency-graphs
    """.trimIndent()

    private val structureToLogicTemplate = """
        version: "1.0"
        contract: structure-to-logic
        description: Defines expected logic artifacts from structure layer
        expectations:
          - business-rules
          - entity-definitions
    """.trimIndent()

    private val logicFromStructureTemplate = """
        version: "1.0"
        contract: logic-from-structure
        description: Defines how logic layer consumes structure artifacts
        expectations:
          - rule-implementations
          - state-machines
    """.trimIndent()

    private val logicToFlowTemplate = """
        version: "1.0"
        contract: logic-to-flow
        description: Defines expected flow artifacts from logic layer
        expectations:
          - interaction-flows
          - sequence-diagrams
    """.trimIndent()

    private val flowFromLogicTemplate = """
        version: "1.0"
        contract: flow-from-logic
        description: Defines how flow layer consumes logic artifacts
        expectations:
          - flow-implementations
          - call-graphs
    """.trimIndent()

    private val flowToCodeTemplate = """
        version: "1.0"
        contract: flow-to-code
        description: Defines expected code artifacts from flow layer
        expectations:
          - function-signatures
          - class-structures
    """.trimIndent()

    private val codeFromVisionTemplate = """
        version: "1.0"
        contract: code-from-vision
        description: Defines how code layer consumes vision artifacts
        expectations:
          - coding-standards
          - architecture-constraints
    """.trimIndent()

    private val codeFromFlowTemplate = """
        version: "1.0"
        contract: code-from-flow
        description: Defines how code layer consumes flow artifacts
        expectations:
          - function-implementations
          - class-implementations
    """.trimIndent()

    /**
     * Contract templates per layer
     * Maps layer name to list of (contract filename, template content)
     */
    val CONTRACT_TEMPLATES = mapOf(
        "vision" to listOf(
            "with-docs.yaml" to visionWithDocsTemplate,
            "to-structure.yaml" to visionToStructureTemplate,
            "to-code.yaml" to visionToCodeTemplate
        ),
        "structure" to listOf(
            "from-vision.yaml" to structureFromVisionTemplate,
            "to-logic.yaml" to structureToLogicTemplate
        ),
        "logic" to listOf(
            "from-structure.yaml" to logicFromStructureTemplate,
            "to-flow.yaml" to logicToFlowTemplate
        ),
        "flow" to listOf(
            "from-logic.yaml" to flowFromLogicTemplate,
            "to-code.yaml" to flowToCodeTemplate
        ),
        "code" to listOf(
            "from-vision.yaml" to codeFromVisionTemplate,
            "from-flow.yaml" to codeFromFlowTemplate
        )
    )

    /**
     * Requirement template for human-written requirements
     */
    val REQUIREMENT_TEMPLATE = visionRequirementTemplate

    /**
     * Default agent config template
     * This template is used when creating agent-config.yaml for each layer
     */
    val AGENT_CONFIG_TEMPLATE = """
        agent:
          name: {layer}-agent
          model: qwen3:4b
          system_prompt: |
            You are the {layer} layer agent for the VSLFC architecture.
            Your role is to analyze and generate artifacts for the {layer} layer.
            
            Guidelines:
            - Maintain consistency with other layers
            - Follow VSLFC contract specifications
            - Generate clear, maintainable artifacts
    """.trimIndent()

    /**
     * Current structure version
     */
    fun getCurrentVersion(): String = "2.0"

    /**
     * Get the directory name for a layer (e.g., "vision" -> ".vision")
     */
    fun layerDirName(layer: String): String = ".$layer"

    /**
     * Get the agent config filename for a layer
     */
    fun agentConfigFileName(layer: String): String = "agent-config.yaml"

    /**
     * Get the contracts directory name
     */
    fun contractsDirName(): String = "contracts"
}
