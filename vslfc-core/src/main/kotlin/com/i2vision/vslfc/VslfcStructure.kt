/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

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

    // Contract templates (simplified - single contract.yaml per layer)
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

    private val visionContractTemplate = """
# Vision Layer Contracts
# All contracts for the Vision layer

version: "1.0"
layer: VISION

documentation:
  primary: "README.md"
  secondary: []

contracts:
  - id: "vision-documentation"
    name: "Vision Documentation Contract"
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

  - id: "vision-to-structure"
    name: "Vision to Structure Contract"
    direction: "outgoing"
    mappings:
      - requirement_pattern: "REQ-.*"
        component_pattern: ".*Service|.*Orchestrator"
      
      - requirement_pattern: ".*Discovery.*"
        component_pattern: "i2vision-discover"

validation:
  rules:
    - rule: "every_doc_requirement_has_code_evidence"
      severity: "warning"
""".trimIndent()

    private val structureContractTemplate = """
# Structure Layer Contracts
# All contracts for the Structure layer

version: "1.0"
layer: STRUCTURE

documentation:
  primary: "docs/concepts/project-structure.md"
  secondary:
    - "docs/concepts/architecture.md"
    - "README.md"

contracts:
  - id: "structure-documentation"
    name: "Structure Documentation Contract"
    mappings:
      - doc_section: "## Module List"
        layer_field: "components"
        parser: "markdown_table"
        confidence: 0.95
      
      - doc_section: "## Architecture"
        layer_field: "architecture_patterns"
        parser: "markdown_list"
        confidence: 0.9

  - id: "structure-from-vision"
    name: "Structure from Vision Contract"
    direction: "incoming"
    expectations:
      - component-definitions
      - dependency-graphs

  - id: "structure-to-logic"
    name: "Structure to Logic Contract"
    direction: "outgoing"
    mappings:
      - component_pattern: ".*Service"
        logic_pattern: "business_rules"

validation:
  rules:
    - rule: "every_doc_component_has_code_evidence"
      severity: "warning"
""".trimIndent()

    private val logicContractTemplate = """
# Logic Layer Contracts
# All contracts for the Logic layer

version: "1.0"
layer: LOGIC

documentation:
  primary: "docs/concepts/contracts.md"
  secondary:
    - "docs/concepts/vslfc-layers.md"

contracts:
  - id: "logic-documentation"
    name: "Logic Documentation Contract"
    mappings:
      - doc_section: "## Contract System"
        layer_field: "business_rules"
        parser: "markdown_list"
        confidence: 0.95
      
      - doc_section: "## Validation Rules"
        layer_field: "validation_rules"
        parser: "markdown_list"
        confidence: 0.9

  - id: "logic-from-structure"
    name: "Logic from Structure Contract"
    direction: "incoming"
    expectations:
      - component-definitions
      - business-rules

  - id: "logic-to-flow"
    name: "Logic to Flow Contract"
    direction: "outgoing"
    mappings:
      - rule_pattern: ".*validate.*"
        flow_pattern: "validation_sequence"

validation:
  rules:
    - rule: "every_doc_rule_has_code_evidence"
      severity: "warning"
""".trimIndent()

    private val flowContractTemplate = """
# Flow Layer Contracts
# All contracts for the Flow layer

version: "1.0"
layer: FLOW

documentation:
  primary: "docs/diagrams/"
  secondary:
    - "docs/reference/api.md"
    - "docs/guides/mcp-tools.md"

contracts:
  - id: "flow-documentation"
    name: "Flow Documentation Contract"
    mappings:
      - doc_section: "## Diagrams"
        layer_field: "sequences"
        parser: "markdown_list"
        confidence: 0.95
      
      - doc_section: "## API Reference"
        layer_field: "api_endpoints"
        parser: "markdown_list"
        confidence: 0.9

  - id: "flow-from-logic"
    name: "Flow from Logic Contract"
    direction: "incoming"
    expectations:
      - business-rules
      - validation-sequences

  - id: "flow-to-code"
    name: "Flow to Code Contract"
    direction: "outgoing"
    mappings:
      - sequence_pattern: ".*authenticate.*"
        code_pattern: "AuthService.kt"

validation:
  rules:
    - rule: "every_doc_sequence_has_code_evidence"
      severity: "warning"
""".trimIndent()

    private val codeContractTemplate = """
# Code Layer Contracts
# All contracts for the Code layer

version: "1.0"
layer: CODE

documentation:
  primary: "docs/reference/api.md"
  secondary:
    - "docs/guides/deployment.md"
    - "README.md"

contracts:
  - id: "code-documentation"
    name: "Code Documentation Contract"
    mappings:
      - doc_section: "## API Reference"
        layer_field: "implementations"
        parser: "markdown_list"
        confidence: 0.95
      
      - doc_section: "## Deployment"
        layer_field: "deployment_config"
        parser: "markdown_list"
        confidence: 0.9

  - id: "code-from-flow"
    name: "Code from Flow Contract"
    direction: "incoming"
    expectations:
      - api-endpoints
      - call-sequences

validation:
  rules:
    - rule: "every_doc_implementation_has_code_evidence"
      severity: "warning"
""".trimIndent()

    /**
     * Contract templates per layer (simplified - single contract.yaml per layer)
     * Maps layer name to contract template content
     */
    val CONTRACT_TEMPLATES = mapOf(
        "vision" to visionContractTemplate,
        "structure" to structureContractTemplate,
        "logic" to logicContractTemplate,
        "flow" to flowContractTemplate,
        "code" to codeContractTemplate
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
     * Get the contract filename for a layer (simplified - single contract.yaml)
     */
    fun contractFileName(): String = "contract.yaml"
}
