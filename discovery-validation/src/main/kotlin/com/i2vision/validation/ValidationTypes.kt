/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.validation

import com.i2vision.discover.api.models.PipelineResult
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * Validation status for self-test phases
 */
enum class ValidationStatus {
    PASS,
    SUSPECT,
    FAIL
}

/**
 * Validation result for a single check
 */
data class ValidationResult(
    val phase: String,
    val status: ValidationStatus,
    val metric: String,
    val actual: String,
    val expected: String,
    val detail: String
)

/**
 * Data class to hold cluster discovery result with timing
 */
data class ClusterDiscoveryResult(
    val clusterId: String,
    val result: PipelineResult,
    val duration: Long
)

/**
 * Strategy routing validation report
 */
data class StrategyRoutingReport(
    val totalValidations: Int,
    val passed: Int,
    val failed: Int,
    val suspect: Int,
    val status: ValidationStatus,
    val clusterValidations: Map<String, List<ValidationResult>>
)

/**
 * Layer coverage validation report
 */
data class LayerCoverageReport(
    val requiredLayers: List<String>,
    val layersWithOutput: Int,
    val layersValid: Map<String, Boolean>,
    val layerArtifactCounts: Map<String, Int>,
    val status: ValidationStatus
)

/**
 * Quality metrics for a single symbol
 */
data class QualityMetrics(
    val symbolId: String,
    val descriptionLength: Int,
    val containsVerb: Boolean,
    val confidenceScore: Double,
    val isTautological: Boolean
)

/**
 * Anti-pattern detected in verbalization
 */
data class AntiPatternDetected(
    val symbolId: String,
    val type: String,
    val description: String,
    val severity: String
)

/**
 * Quality assessment report
 */
data class QualityReport(
    val totalSymbols: Int,
    val metrics: List<QualityMetrics>,
    val antiPatterns: List<AntiPatternDetected>,
    val antiPatternRate: Double,
    val status: ValidationStatus
)

/**
 * Enrichment comparison between strategies
 */
data class EnrichmentComparison(
    val symbolId: String,
    val incrementalLength: Int,
    val multiPassLength: Int,
    val novelTokens: Int,
    val hasCrossReferences: Boolean,
    val crossReferences: List<String>
)

/**
 * Cross-layer enrichment report
 */
data class EnrichmentReport(
    val comparisons: List<EnrichmentComparison>,
    val symbolsCompared: Int,
    val enrichmentRate: Double,
    val avgNovelTokens: Double,
    val status: ValidationStatus
)

/**
 * Performance benchmark report
 */
data class PerformanceReport(
    val incrementalLatencyMs: Double,
    val multiPassLatencyMs: Double,
    val learningLatencyMs: Double,
    val status: ValidationStatus
)

/**
 * Cache effectiveness metrics
 */
data class CacheMetricsReport(
    val hitRate: Double,
    val staleRate: Double,
    val status: ValidationStatus
)

/**
 * Feedback application report
 */
data class FeedbackReport(
    val feedbackApplied: Int,
    val status: ValidationStatus
)

/**
 * Overall self-test report
 */
data class VerbalizationSelfTestReport(
    val timestamp: Instant,
    var overallStatus: ValidationStatus = ValidationStatus.PASS,
    var strategyRouting: StrategyRoutingReport = StrategyRoutingReport(0, 0, 0, 0, ValidationStatus.PASS, emptyMap()),
    var layerCoverage: LayerCoverageReport = LayerCoverageReport(emptyList(), 0, emptyMap(), emptyMap(), ValidationStatus.PASS),
    var qualityMetrics: QualityReport = QualityReport(0, emptyList(), emptyList(), 0.0, ValidationStatus.PASS),
    var enrichmentEvidence: EnrichmentReport = EnrichmentReport(emptyList(), 0, 0.0, 0.0, ValidationStatus.PASS),
    var performance: PerformanceReport = PerformanceReport(0.0, 0.0, 0.0, ValidationStatus.PASS),
    var cacheMetrics: CacheMetricsReport = CacheMetricsReport(1.0, 0.0, ValidationStatus.PASS),
    var feedbackApplication: FeedbackReport = FeedbackReport(0, ValidationStatus.PASS),
    var regressions: List<String> = emptyList()
) {
    val formattedTimestamp: String
        get() = DateTimeFormatter.ISO_INSTANT.format(timestamp)
    
    val totalPhases: Int = 8
    
    val phasesPassed: Int
        get() = listOf(
            strategyRouting.status,
            layerCoverage.status,
            qualityMetrics.status,
            enrichmentEvidence.status,
            performance.status,
            cacheMetrics.status,
            feedbackApplication.status
        ).count { it == ValidationStatus.PASS }
    
    val totalValidations: Int
        get() = strategyRouting.totalValidations + 
                layerCoverage.layersWithOutput + 
                qualityMetrics.totalSymbols + 
                enrichmentEvidence.symbolsCompared
    
    val passedValidations: Int
        get() = strategyRouting.passed + 
                layerCoverage.layersValid.count { it.value } + 
                qualityMetrics.metrics.count { !it.isTautological && it.descriptionLength >= 20 } + 
                enrichmentEvidence.comparisons.count { it.novelTokens > 3 || it.hasCrossReferences }
    
    val failedValidations: Int
        get() = strategyRouting.failed
    
    val suspectValidations: Int
        get() = strategyRouting.suspect
}
