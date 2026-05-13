/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.verbalization

import com.i2vision.intent.DiscoveryIntent
import com.i2vision.verbalization.feedback.Feedback
import com.i2vision.verbalization.feedback.IFeedbackStore
import com.i2vision.vslfc.Symbol
import com.i2vision.vslfc.SymbolKind
import com.i2vision.vslfc.VerbalizationResult
import com.i2vision.vslfc.VerbalizationStrategy
import com.i2vision.vslfc.VerbalizationStore
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Context Neediness Score (CNS) Calculator
 *
 * Measures how much a cluster "needs" contextual verbalization assistance.
 * Higher CNS indicates more benefit from multi-pass or learning strategies.
 *
 * CNS Formula:
 * CNS = SymbolAmbiguity(35) + StructuralComplexity(25) +
 *       ArchitecturalSensitivity(20) + FeedbackDiscrepancy(20)
 *
 * @property symbolRepository Repository for accessing symbols
 * @property verbalizationStore Store for verbalization results
 * @property feedbackStore Store for user feedback (from feedback package)
 * @property config Configuration for CNS calculation
 */
class ContextNeedinessCalculator(
    private val symbolRepository: SymbolRepository,
    private val verbalizationStore: VerbalizationStore,
    private val feedbackStore: IFeedbackStore,
    private val config: CnsConfig = CnsConfig()
) {

    /**
     * Calculate CNS for a cluster.
     */
    suspend fun calculate(clusterId: String): ClusterNeedinessScore {
        val symbols = symbolRepository.getClusterSymbols(clusterId)
        val verbalizations = verbalizationStore.getVerbalizations(clusterId) ?: emptyList()
        val feedback = feedbackStore.getClusterFeedback(clusterId)

        return calculateWithData(clusterId, symbols, verbalizations, feedback)
    }

    /**
     * Calculate CNS with provided data (for testing).
     */
    suspend fun calculateWithData(
        clusterId: String,
        symbols: List<Symbol>,
        verbalizations: List<VerbalizationResult>,
        feedback: List<Feedback>
    ): ClusterNeedinessScore {

        val symbolAmbiguity = calculateSymbolAmbiguity(symbols, verbalizations)
        val structuralComplexity = calculateStructuralComplexity(symbols)
        val architecturalSensitivity = calculateArchitecturalSensitivity(clusterId, symbols, verbalizations)
        val feedbackDiscrepancy = calculateFeedbackDiscrepancy(verbalizations, feedback)

        val total = symbolAmbiguity.total + structuralComplexity.total +
                    architecturalSensitivity.total + feedbackDiscrepancy.total

        val recommendation = recommendStrategy(total)

        return ClusterNeedinessScore(
            clusterId = clusterId,
            symbolAmbiguity = symbolAmbiguity,
            structuralComplexity = structuralComplexity,
            architecturalSensitivity = architecturalSensitivity,
            feedbackDiscrepancy = feedbackDiscrepancy,
            total = roundToTwoDecimals(total),
            recommendation = recommendation,
            calculatedAt = System.currentTimeMillis()
        )
    }

    /**
     * Component 1: Symbol Ambiguity (0-35)
     * Measures how unclear/incomplete symbol documentation is.
     */
    private fun calculateSymbolAmbiguity(
        symbols: List<Symbol>,
        verbalizations: List<VerbalizationResult>
    ): ComponentScore {
        if (symbols.isEmpty()) {
            return ComponentScore(name = "SymbolAmbiguity", total = 0.0, breakdown = emptyMap())
        }

        val breakdown = mutableMapOf<String, Double>()
        var total = 0.0

        // Low confidence count (max 20 points)
        val lowConfidenceCount = verbalizations.count { it.confidence < config.lowConfidenceThreshold }
        val lowConfidenceScore = minOf((lowConfidenceCount.toDouble() / symbols.size) * 20, 20.0)
        breakdown["lowConfidenceRatio"] = roundToTwoDecimals(lowConfidenceScore)
        total += lowConfidenceScore

        // Duplicate name count (max 5 points)
        val nameGroups = symbols.groupBy { it.name }
        val duplicateNameCount = nameGroups.count { it.value.size > 1 }
        val duplicateNameScore = minOf(duplicateNameCount * config.duplicateNamePenalty, 5.0)
        breakdown["duplicateNames"] = roundToTwoDecimals(duplicateNameScore)
        total += duplicateNameScore

        // Missing documentation count (max 10 points)
        val verbalizedSymbols = verbalizations.map { it.symbol }.toSet()
        val missingDocCount = symbols.count { it !in verbalizedSymbols ||
            verbalizations.find { v -> v.symbol == it }?.description.isNullOrBlank() }
        val missingDocScore = minOf(missingDocCount * config.missingDocPenalty, 10.0)
        breakdown["missingDocumentation"] = roundToTwoDecimals(missingDocScore)
        total += missingDocScore

        return ComponentScore(
            name = "SymbolAmbiguity",
            total = roundToTwoDecimals(minOf(total, 35.0)),
            breakdown = breakdown
        )
    }

    /**
     * Component 2: Structural Complexity (0-25)
     * Measures code complexity and external dependencies.
     */
    private fun calculateStructuralComplexity(symbols: List<Symbol>): ComponentScore {
        if (symbols.isEmpty()) {
            return ComponentScore(name = "StructuralComplexity", total = 0.0, breakdown = emptyMap())
        }

        val breakdown = mutableMapOf<String, Double>()
        var total = 0.0

        // Cyclomatic complexity (max 15 points)
        val avgCyclomatic = symbols.map { calculateCyclomaticComplexity(it) }.average()
        val cyclomaticScore = minOf(avgCyclomatic / 10 * 15, 15.0)
        breakdown["cyclomaticComplexity"] = roundToTwoDecimals(cyclomaticScore)
        total += cyclomaticScore

        // External dependencies (max 10 points)
        val externalDeps = countExternalDependencies(symbols)
        val dependencyScore = minOf(externalDeps.toDouble() / symbols.size * 10, 10.0)
        breakdown["externalDependencies"] = roundToTwoDecimals(dependencyScore)
        total += dependencyScore

        return ComponentScore(
            name = "StructuralComplexity",
            total = roundToTwoDecimals(minOf(total, 25.0)),
            breakdown = breakdown
        )
    }

    /**
     * Calculate cyclomatic complexity for a symbol.
     */
    private fun calculateCyclomaticComplexity(symbol: Symbol): Double {
        var complexity = 1.0

        // Count branching constructs
        val content = symbol.content

        // Count if statements
        complexity += Regex("""if\s*\(""").findAll(content).count() * 1.0
        // Count when expressions (Kotlin)
        complexity += Regex("""when\s*\(""").findAll(content).count() * 1.0
        // Count for loops
        complexity += Regex("""for\s*\(""").findAll(content).count() * 1.0
        // Count while loops
        complexity += Regex("""while\s*\(""").findAll(content).count() * 1.0
        // Count catch blocks
        complexity += Regex("""catch\s*\(""").findAll(content).count() * 1.0
        // Count && and || operators
        complexity += Regex("""&&|\|\|""").findAll(content).count() * 0.5
        // Count ? : (ternary)
        complexity += Regex("""\?[^:]+:""").findAll(content).count() * 1.0

        return complexity
    }

    /**
     * Count external dependencies in symbols.
     */
    private fun countExternalDependencies(symbols: List<Symbol>): Int {
        val externalPatterns = listOf(
            Regex("""import\s+(?!com\.(i2vision|[a-z]+\.){1,2})[a-zA-Z_][a-zA-Z0-9_.]*"""),
            Regex("""(http|https)://"""),
            Regex("""@[A-Z][a-zA-Z0-9]*(Repository|Service|Controller|Component)""")
        )

        val content = symbols.joinToString(" ") { it.content }
        return externalPatterns.sumOf { pattern -> pattern.findAll(content).count() }
    }

    /**
     * Component 3: Architectural Sensitivity (0-20)
     * Measures domain module importance and cross-cutting concerns.
     */
    private fun calculateArchitecturalSensitivity(
        clusterId: String,
        symbols: List<Symbol>,
        verbalizations: List<VerbalizationResult>
    ): ComponentScore {
        val breakdown = mutableMapOf<String, Double>()
        var total = 0.0

        // Domain module check (max 10 points)
        val isDomainModule = isDomainModule(clusterId)
        val domainScore = if (isDomainModule) 10.0 else 0.0
        breakdown["domainModule"] = domainScore
        total += domainScore

        // Cross-cutting flows (max 10 points)
        val crossCuttingCount = countCrossCuttingFlows(symbols, verbalizations)
        val crossCuttingScore = minOf(crossCuttingCount * 3.0, 10.0)
        breakdown["crossCuttingFlows"] = roundToTwoDecimals(crossCuttingScore)
        total += crossCuttingScore

        return ComponentScore(
            name = "ArchitecturalSensitivity",
            total = roundToTwoDecimals(minOf(total, 20.0)),
            breakdown = breakdown
        )
    }

    /**
     * Check if cluster represents a domain module (vs utility/infrastructure).
     */
    private fun isDomainModule(clusterId: String): Boolean {
        val domainPatterns = listOf(
            Regex("""(core|domain|business|service|orchestrator)""", RegexOption.IGNORE_CASE),
            Regex("""[a-z]+/[a-z]+""") // Matches format like "core/auth"
        )

        return domainPatterns.any { it.containsMatchIn(clusterId) }
    }

    /**
     * Count cross-cutting flows (patterns that span multiple layers).
     */
    private fun countCrossCuttingFlows(
        symbols: List<Symbol>,
        verbalizations: List<VerbalizationResult>
    ): Int {
        val crossCuttingPatterns = listOf(
            "logging", "authentication", "authorization", "validation",
            "caching", "error handling", "retry", "circuit breaker"
        )

        val content = (symbols.map { it.content } + verbalizations.map { it.description }).joinToString(" ")

        return crossCuttingPatterns.count { pattern ->
            content.contains(pattern, ignoreCase = true)
        }
    }

    /**
     * Component 4: Feedback Discrepancy (0-20)
     * Measures difference between heuristic and user-provided descriptions.
     */
    private fun calculateFeedbackDiscrepancy(
        verbalizations: List<VerbalizationResult>,
        feedback: List<Feedback>
    ): ComponentScore {
        val breakdown = mutableMapOf<String, Double>()
        var total = 0.0

        if (feedback.isEmpty()) {
            return ComponentScore(name = "FeedbackDiscrepancy", total = 0.0, breakdown = breakdown)
        }

        // Semantic distance (max 15 points)
        val totalDistance = feedback.sumOf { calculateSemanticDistance(it) }
        val avgDistance = totalDistance / feedback.size
        val distanceScore = minOf(avgDistance * 0.5, 15.0)
        breakdown["semanticDistance"] = roundToTwoDecimals(distanceScore)
        total += distanceScore

        // Feedback frequency (max 5 points)
        val verbalizedWithFeedback = feedback.map { it.symbolPath }.toSet().size
        val feedbackFrequency = verbalizedWithFeedback.toDouble() /
            maxOf(verbalizations.size, 1) * 5.0
        breakdown["feedbackFrequency"] = roundToTwoDecimals(feedbackFrequency)
        total += feedbackFrequency

        return ComponentScore(
            name = "FeedbackDiscrepancy",
            total = roundToTwoDecimals(minOf(total, 20.0)),
            breakdown = breakdown
        )
    }

    /**
     * Calculate semantic distance between original and corrected descriptions.
     * Returns 0-1 scale where 1 = completely different.
     */
    private fun calculateSemanticDistance(feedback: Feedback): Double {
        val original = feedback.originalDescription.lowercase().split(Regex("""\s+"""))
        val correction = feedback.correction.lowercase().split(Regex("""\s+"""))

        if (original.isEmpty() || correction.isEmpty()) return 1.0

        val originalSet = original.toSet()
        val correctionSet = correction.toSet()

        // Jaccard similarity
        val intersection = originalSet.intersect(correctionSet).size.toDouble()
        val union = originalSet.union(correctionSet).size.toDouble()
        val similarity = if (union > 0) intersection / union else 0.0

        // Return 1 - similarity (distance)
        return 1.0 - similarity
    }

    /**
     * Recommend verbalization strategy based on CNS total.
     */
    private fun recommendStrategy(total: Double): StrategyRecommendation {
        return when {
            total < 25 -> StrategyRecommendation(
                strategy = VerbalizationStrategy.INCREMENTAL,
                reason = "Low CNS indicates stable, well-understood codebase",
                expectedBenefit = "Minimal verbalization needed"
            )
            total < 50 -> StrategyRecommendation(
                strategy = VerbalizationStrategy.MULTI_PASS,
                reason = "Medium CNS suggests benefit from context-aware refinement",
                expectedBenefit = "Improved accuracy through multi-pass processing"
            )
            else -> StrategyRecommendation(
                strategy = VerbalizationStrategy.LEARNING,
                reason = "High CNS indicates complex code with significant feedback",
                expectedBenefit = "Maximum improvement through learning from feedback"
            )
        }
    }

    private fun roundToTwoDecimals(value: Double): Double {
        return BigDecimal(value).setScale(2, RoundingMode.HALF_UP).toDouble()
    }
}

/**
 * CNS Configuration
 */
data class CnsConfig(
    val lowConfidenceThreshold: Double = 0.7,
    val duplicateNamePenalty: Double = 0.5,
    val missingDocPenalty: Double = 2.0
)

/**
 * CNS result for a cluster
 */
data class ClusterNeedinessScore(
    val clusterId: String,
    val symbolAmbiguity: ComponentScore,
    val structuralComplexity: ComponentScore,
    val architecturalSensitivity: ComponentScore,
    val feedbackDiscrepancy: ComponentScore,
    val total: Double,
    val recommendation: StrategyRecommendation,
    val calculatedAt: Long
)

/**
 * Component score with breakdown
 */
data class ComponentScore(
    val name: String,
    val total: Double,
    val breakdown: Map<String, Double>
)

/**
 * Strategy recommendation based on CNS.
 */
data class StrategyRecommendation(
    val strategy: VerbalizationStrategy,
    val reason: String,
    val expectedBenefit: String
)

/**
 * Repository interface for accessing symbols.
 */
interface SymbolRepository {
    suspend fun getClusterSymbols(clusterId: String): List<Symbol>
}

/**
 * Extension to create Feedback from simpler inputs.
 */
fun createFeedback(
    symbol: Symbol,
    originalDescription: String,
    correction: String,
    userId: String = "system"
): Feedback {
    return Feedback(
        id = "${symbol.filePath}:${symbol.name}:${System.currentTimeMillis()}",
        symbolId = "${symbol.filePath}#${symbol.name}",
        symbolPath = symbol.filePath,
        symbolName = symbol.name,
        originalDescription = originalDescription,
        correction = correction,
        rating = 5,
        reason = null,
        timestamp = System.currentTimeMillis()
    )
}