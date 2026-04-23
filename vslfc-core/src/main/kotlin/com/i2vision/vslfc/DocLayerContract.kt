/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.vslfc

/**
 * Contract defining relationship between documentation and VSLFC layer.
 */
data class DocLayerContract(
    val contractId: String,
    val layer: VSLFCLayer,
    val version: String,
    val direction: SyncDirection,

    // Documentation sources
    val primaryDoc: String,  // e.g., "README.md"
    val secondaryDocs: List<String> = emptyList(),

    // Mappings
    val mappings: List<DocMapping>,

    // Sync and validation rules
    val syncRules: SyncRules,
    val validationRules: List<ValidationRule>,

    // LLM dependency monitoring (for relationship extraction, etc.)
    val llmRequirements: LLMRequirements? = null,

    // Pre-removal validation gates (before removing description fields)
    val descriptionRemovalGates: List<RemovalGate> = emptyList()
) {
    enum class VSLFCLayer { VISION, STRUCTURE, LOGIC, FLOW, CODE }

    enum class SyncDirection { IMPORT_ONLY, EXPORT_ONLY, BIDIRECTIONAL }

    data class DocMapping(
        val docSection: String,      // e.g., "## Requirements"
        val layerField: String,       // e.g., "requirements"
        val parser: ParserType,
        val confidence: Double
    )

    enum class ParserType {
        MARKDOWN_LIST,
        MARKDOWN_TABLE,
        YAML_EMBEDDED,
        FREE_TEXT
    }

    data class SyncRules(
        val onDocChange: SyncAction,
        val onLayerChange: SyncAction,
        val conflictResolution: ConflictResolutionStrategy,
        val freshness: FreshnessRules = FreshnessRules()
    )

    enum class SyncAction {
        REIMPORT,
        SUGGEST_UPDATE,
        AUTO_UPDATE,
        FLAG_FOR_REVIEW
    }

    data class ConflictResolutionStrategy(
        val rules: List<ConflictRule> = listOf(
            ConflictRule(confidenceThreshold = 0.8, action = ConflictAction.PREFER_CODE_EVIDENCE),
            ConflictRule(confidenceThreshold = 0.5, action = ConflictAction.FLAG_FOR_REVIEW)
        ),
        val default: ConflictAction = ConflictAction.MANUAL_REVIEW
    )

    data class ConflictRule(
        val confidenceThreshold: Double,
        val action: ConflictAction
    )

    enum class ConflictAction {
        PREFER_DOC,
        PREFER_LAYER,
        PREFER_CODE_EVIDENCE,
        FLAG_FOR_REVIEW,
        MANUAL_REVIEW
    }

    data class FreshnessRules(
        val warningDays: Int = 90,
        val criticalDays: Int = 180,
        val actionOnStale: StaleAction = StaleAction.REVALIDATE_WITH_LOWER_CONFIDENCE
    )

    enum class StaleAction {
        REVALIDATE_WITH_LOWER_CONFIDENCE,
        FLAG_FOR_UPDATE,
        AUTO_REIMPORT
    }

    data class ValidationRule(
        val rule: String,  // e.g., "every_doc_requirement_has_layer_evidence"
        val severity: Severity,
        val formula: String? = null,  // e.g., "confidence * (0.95 ^ months_since_update)"
        val threshold: Double? = null,
        val action: String? = null,
        val description: String? = null
    )

    enum class Severity { ERROR, WARNING, INFO }

    // LLM dependency monitoring for complex operations like relationship extraction
    data class LLMRequirements(
        val relationshipExtraction: LLMDependency
    )

    data class LLMDependency(
        val requiresLlm: Boolean,
        val fallbackStrategy: String,
        val monitoring: List<LLMMonitoringMetric>
    )

    data class LLMMonitoringMetric(
        val metric: String,
        val check: String,
        val actionIfMissing: String? = null,
        val minimumTokens: Int? = null,
        val confidenceThreshold: Double? = null,
        val actionIfInsufficient: String? = null,
        val actionIfLow: String? = null
    )

    // Pre-removal validation gates (before removing description fields)
    data class RemovalGate(
        val gate: String,
        val condition: String,
        val severity: Severity,
        val description: String,
        val minimumContentLength: Int? = null
    )
}
