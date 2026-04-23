package com.i2vision.vslfc

/**
 * VSLFC Layer Contracts: Documentation as First-Class Citizen
 * 
 * ## Core Principle
 * 
 * **Every VSLFC layer MUST have a contract with its corresponding documentation.**
 * 
 * This transforms documentation from an afterthought to a structural component
 * of the architecture with:
 * - Explicit mappings (doc sections → layer fields)
 * - Confidence scoring (based on doc quality/freshness)
 * - Sync rules (import/export/bidirectional)
 * - Validation rules (consistency enforcement)
 * 
 * ## The Five Contracts
 * 
 * ```
 * VISION     ←──Contract──→ README.md / docs/INDEX.md
 * STRUCTURE  ←──Contract──→ docs/architecture.md / docs/PROJECT_STRUCTURE.md
 * LOGIC      ←──Contract──→ docs/ORCHESTRATOR.md / docs/STRATEGIES.md
 * FLOW       ←──Contract──→ docs/ORCHESTRATOR.md / docs/VSLFC_LAYERS.md
 * CODE       ←──Contract──→ docs/API_REFERENCE.md / inline comments
 * ```
 * 
 * ## Contract Structure
 * 
 * Each contract defines:
 * 
 * ### 1. Documentation Mapping
 * ```yaml
 * mappings:
 *   - doc_section: "## Components"
 *     layer_field: "components"
 *     parser: "markdown_list"
 *     confidence: 0.95
 * ```
 * 
 * ### 2. Sync Rules
 * ```yaml
 * sync_rules:
 *   on_doc_change: reimport + validate
 *   on_layer_change: suggest_update
 *   conflict_resolution: prefer_code_evidence
 * ```
 * 
 * ### 3. Validation Rules
 * ```yaml
 * validation:
 *   - every_doc_item_has_code_evidence
 *   - every_layer_item_has_doc_reference
 *   - no_contradictions
 * ```
 * 
 * ## Contract Registry
 * 
 * Contracts are stored in `.vision-ai/.<layer>/contracts/with-docs.yaml`
 * 
 * ```
 * .vision-ai/
 *   .vision/
 *     contracts/
 *       with-docs.yaml          # Vision ↔ docs contract
 *   .structure/
 *     contracts/
 *       with-docs.yaml          # Structure ↔ docs contract
 *   .logic/
 *     contracts/
 *       with-docs.yaml          # Logic ↔ docs contract
 *   .flow/
 *     contracts/
 *       with-docs.yaml          # Flow ↔ docs contract
 *   .code/
 *     contracts/
 *       with-docs.yaml          # Code ↔ docs contract
 * ```
 * 
 * ## Benefits
 * 
 * | Without Contracts | With Contracts |
 * |-------------------|----------------|
 * | Docs optional | Docs structural |
 * | Manual sync | Auto sync |
 * | Docs can be wrong | Contradictions flagged |
 * | No confidence | Scored reliability |
 * | Afterthought | First-class citizen |
 * 
 * ## Usage
 * 
 * ```kotlin
 * // Load contract for a layer
 * val contract = ContractRegistry.load(VSLFCLayer.STRUCTURE)
 * 
 * // Import from docs
 * val structureArtifacts = contract.importFromDocs()
 * 
 * // Validate consistency
 * val report = contract.validate(structureArtifacts)
 * 
 * // Export to docs
 * contract.exportToDocs(structureArtifacts)
 * ```
 * 
 * ## Implementation
 * 
 * See:
 * - `LayerContractRegistry.kt` - Contract loader
 * - `contracts/` directory - Contract definitions for each layer
 * - `DocLayerContractManager.kt` - Sync/validation implementation
 */
object VSLFCLayerContracts {
    const val VERSION = "1.0"
    const val APPROACH = "Documentation as First-Class Citizen"
    
    /**
     * All VSLFC layers that must have contracts.
     */
    enum class Layer {
        VISION,     // Business requirements, constraints
        STRUCTURE,  // Architecture, components, dependencies
        LOGIC,      // Business rules, entities, state machines
        FLOW,       // User flows, API sequences, interactions
        CODE        // API documentation, inline comments
    }
    
    /**
     * Standard contract locations for each layer.
     */
    fun contractPath(layer: Layer): String = when (layer) {
        Layer.VISION -> ".vision-ai/.vision/contracts/with-docs.yaml"
        Layer.STRUCTURE -> ".vision-ai/.structure/contracts/with-docs.yaml"
        Layer.LOGIC -> ".vision-ai/.logic/contracts/with-docs.yaml"
        Layer.FLOW -> ".vision-ai/.flow/contracts/with-docs.yaml"
        Layer.CODE -> ".vision-ai/.code/contracts/with-docs.yaml"
    }
    
    /**
     * Primary documentation for each layer.
     */
    fun primaryDocs(layer: Layer): List<String> = when (layer) {
        Layer.VISION -> listOf("README.md", "docs/INDEX.md")
        Layer.STRUCTURE -> listOf("docs/architecture.md", "docs/PROJECT_STRUCTURE.md")
        Layer.LOGIC -> listOf("docs/ORCHESTRATOR.md", "docs/STRATEGIES.md")
        Layer.FLOW -> listOf("docs/ORCHESTRATOR.md", "docs/VSLFC_LAYERS.md")
        Layer.CODE -> listOf("docs/API_REFERENCE.md", "inline comments")
    }
}
