/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.contracts

import java.time.Instant

/**
 * Contract data models for contract-based discovery.
 * 
 * Contracts define explicit expectations between layers and guide discovery.
 */

data class DiscoveryContract(
    val metadata: ContractMetadata,
    val layerExpectations: LayerExpectations? = null,
    val discoveryConfig: DiscoveryConfig? = null,
    val qualityGates: QualityGates? = null,
    val caching: CachingConfig? = null
)

data class ContractMetadata(
    val name: String,
    val version: String = "1.0",
    val description: String? = null,
    val author: String? = null,
    val created: Instant = Instant.now(),
    val targetLayers: List<String> = listOf("all"),
    val priority: ContractPriority = ContractPriority.MEDIUM
)

enum class ContractPriority {
    LOW, MEDIUM, HIGH, CRITICAL
}

data class LayerExpectations(
    val vision: VisionExpectations? = null,
    val structure: StructureExpectations? = null,
    val logic: LogicExpectations? = null,
    val flow: FlowExpectations? = null,
    val code: CodeExpectations? = null
)

data class VisionExpectations(
    val requiredCapabilities: List<String> = emptyList(),
    val constraints: List<String> = emptyList(),
    val performanceTargets: PerformanceTargets? = null
)

data class StructureExpectations(
    val expectedComponents: List<ComponentExpectation> = emptyList(),
    val maxDependencies: Int? = null,
    val architecturePattern: String? = null
)

data class ComponentExpectation(
    val name: String,
    val purpose: String? = null,
    val responsibilities: List<String> = emptyList(),
    val filePatterns: List<String> = emptyList()
)

data class LogicExpectations(
    val expectedRules: List<RuleExpectation> = emptyList(),
    val expectedEntities: List<EntityExpectation> = emptyList(),
    val businessInvariants: List<String> = emptyList()
)

data class RuleExpectation(
    val id: String,
    val description: String,
    val triggerPatterns: List<String> = emptyList(),
    val validationPatterns: List<String> = emptyList(),
    val relatedFlows: List<String> = emptyList()
)

data class EntityExpectation(
    val name: String,
    val attributes: List<String> = emptyList(),
    val relationships: List<String> = emptyList()
)

data class FlowExpectations(
    val expectedFlows: List<FlowExpectation> = emptyList(),
    val entryPoints: List<EntryPointExpectation> = emptyList(),
    val interactionPatterns: List<String> = emptyList()
)

data class FlowExpectation(
    val name: String,
    val entryPoint: String? = null,
    val expectedSteps: List<String> = emptyList(),
    val relatedRules: List<String> = emptyList()
)

data class EntryPointExpectation(
    val pattern: String,
    val type: EntryPointType = EntryPointType.FUNCTION,
    val description: String? = null
)

enum class EntryPointType {
    FUNCTION, CLASS, ANNOTATION, PATTERN
}

data class CodeExpectations(
    val requiredSymbols: List<SymbolExpectation> = emptyList(),
    val testCoverageMinimum: Double? = null,
    val codePatterns: List<String> = emptyList()
)

data class SymbolExpectation(
    val name: String,
    val type: String,
    val filePattern: String? = null,
    val signaturePattern: String? = null
)

data class DiscoveryConfig(
    val depth: DiscoveryDepth = DiscoveryDepth.STANDARD,
    val includePatterns: List<String> = listOf("**/*.kt", "**/*.java"),
    val excludePatterns: List<String> = listOf("**/test/**", "**/build/**"),
    val autoDiscover: Boolean = true,
    val entryPoints: List<String> = emptyList()
)

enum class DiscoveryDepth {
    SHALLOW, STANDARD, DEEP
}

data class QualityGates(
    val minCoveragePercentage: Double = 70.0,
    val maxOrphanedRules: Int = 10,
    val requiredEntryPoints: Int? = null,
    val maxComplexity: Int? = null,
    val enforceTestCoverage: Boolean = false
)

data class PerformanceTargets(
    val maxResponseTimeMs: Int? = null,
    val throughputRps: Int? = null
)

data class CachingConfig(
    val enabled: Boolean = true,
    val ttlMinutes: Int = 60,
    val resultFile: String? = null,
    val invalidateOnChange: Boolean = true
)

data class ContractValidationResult(
    val contract: DiscoveryContract,
    val isValid: Boolean,
    val errors: List<ValidationError>,
    val warnings: List<ValidationWarning>,
    val timestamp: Instant = Instant.now()
)

data class ValidationError(
    val field: String,
    val message: String,
    val severity: ValidationSeverity = ValidationSeverity.ERROR
)

data class ValidationWarning(
    val field: String,
    val message: String,
    val suggestion: String? = null
)

enum class ValidationSeverity {
    INFO, WARNING, ERROR, CRITICAL
}

data class ContractExecutionResult(
    val contract: DiscoveryContract,
    val success: Boolean,
    val coverage: Map<String, Double>,
    val discoveredArtifacts: Map<String, List<String>>,
    val issues: List<String>,
    val timestamp: Instant = Instant.now()
)

data class ValidationSummary(
    val total: Int,
    val valid: Int,
    val invalid: Int,
    val totalErrors: Int,
    val totalWarnings: Int
)
