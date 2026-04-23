/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.arch.compatibility

import com.i2vision.arch.signature.ArchitectureSignature

/**
 * Migration path for transitioning between architecture patterns
 */
data class MigrationPath(
    val fromSignature: ArchitectureSignature,
    val toSignature: ArchitectureSignature,
    val steps: List<MigrationStep>,
    val estimatedEffort: MigrationEffort
) {
    /**
     * Get total estimated effort
     */
    fun getTotalEffort(): Int {
        return steps.sumOf { it.effortScore }
    }

    /**
     * Get migration steps sorted by dependency order
     */
    fun getSortedSteps(): List<MigrationStep> {
        return steps.sortedBy { it.order }
    }
}

/**
 * Individual migration step
 */
data class MigrationStep(
    val order: Int,
    val description: String,
    val effortScore: Int, // 1-10 scale
    val riskLevel: RiskLevel,
    val dependencies: List<String> = emptyList()
)

/**
 * Migration effort estimation
 */
enum class MigrationEffort {
    TRIVIAL,    // 1-2 hours
    LOW,        // 1-2 days
    MEDIUM,     // 1-2 weeks
    HIGH,       // 1-2 months
    VERY_HIGH   // 2+ months
}

/**
 * Risk level for migration steps
 */
enum class RiskLevel {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL
}

/**
 * Migration path generator
 */
class MigrationPathGenerator {

    private val compatibilityChecker = PatternCompatibility()

    /**
     * Generate migration path from one signature to another
     */
    fun generatePath(fromSignature: ArchitectureSignature, toSignature: ArchitectureSignature): MigrationPath? {
        // If signatures are already compatible, no migration needed
        if (compatibilityChecker.areSignaturesCompatible(fromSignature, toSignature)) {
            return null
        }

        val steps = mutableListOf<MigrationStep>()
        var order = 1

        // Check deployment pattern migration
        if (fromSignature.deploymentPattern != toSignature.deploymentPattern) {
            steps.addAll(
                generateDeploymentMigrationSteps(
                    fromSignature.deploymentPattern,
                    toSignature.deploymentPattern,
                    order
                )
            )
            order += steps.size
        }

        // Check module pattern migration
        val moduleIssues = compatibilityChecker.getCompatibilityIssues(fromSignature, toSignature)
            .filter { it.contains("Module pattern") }

        if (moduleIssues.isNotEmpty()) {
            steps.addAll(generateModulePatternMigrationSteps(fromSignature, toSignature, order))
            order += steps.size
        }

        // Check design pattern migration
        val designIssues = compatibilityChecker.getCompatibilityIssues(fromSignature, toSignature)
            .filter { it.contains("Design pattern") }

        if (designIssues.isNotEmpty()) {
            steps.addAll(generateDesignPatternMigrationSteps(fromSignature, toSignature, order))
        }

        // Estimate overall effort
        val totalEffort = steps.sumOf { it.effortScore }
        val effortLevel = when {
            totalEffort <= 5 -> MigrationEffort.TRIVIAL
            totalEffort <= 15 -> MigrationEffort.LOW
            totalEffort <= 30 -> MigrationEffort.MEDIUM
            totalEffort <= 60 -> MigrationEffort.HIGH
            else -> MigrationEffort.VERY_HIGH
        }

        return MigrationPath(
            fromSignature = fromSignature,
            toSignature = toSignature,
            steps = steps,
            estimatedEffort = effortLevel
        )
    }

    private fun generateDeploymentMigrationSteps(
        from: com.i2vision.arch.signature.DeploymentPattern,
        to: com.i2vision.arch.signature.DeploymentPattern,
        startOrder: Int
    ): List<MigrationStep> {
        val steps = mutableListOf<MigrationStep>()
        var order = startOrder

        when {
            from == com.i2vision.arch.signature.DeploymentPattern.MONOLITH &&
                    to == com.i2vision.arch.signature.DeploymentPattern.MODULAR_MONOLITH -> {
                steps.add(
                    MigrationStep(
                        order = order++,
                        description = "Extract module boundaries from monolith",
                        effortScore = 8,
                        riskLevel = RiskLevel.MEDIUM
                    )
                )
                steps.add(
                    MigrationStep(
                        order = order++,
                        description = "Establish module interfaces",
                        effortScore = 6,
                        riskLevel = RiskLevel.LOW,
                        dependencies = listOf("Extract module boundaries from monolith")
                    )
                )
                steps.add(
                    MigrationStep(
                        order = order++,
                        description = "Refactor dependencies between modules",
                        effortScore = 7,
                        riskLevel = RiskLevel.MEDIUM,
                        dependencies = listOf("Establish module interfaces")
                    )
                )
            }

            from == com.i2vision.arch.signature.DeploymentPattern.MODULAR_MONOLITH &&
                    to == com.i2vision.arch.signature.DeploymentPattern.MICROSERVICES -> {
                steps.add(
                    MigrationStep(
                        order = order++,
                        description = "Identify service boundaries",
                        effortScore = 5,
                        riskLevel = RiskLevel.LOW
                    )
                )
                steps.add(
                    MigrationStep(
                        order = order++,
                        description = "Implement inter-service communication",
                        effortScore = 8,
                        riskLevel = RiskLevel.HIGH,
                        dependencies = listOf("Identify service boundaries")
                    )
                )
                steps.add(
                    MigrationStep(
                        order = order++,
                        description = "Extract services from modules",
                        effortScore = 10,
                        riskLevel = RiskLevel.HIGH,
                        dependencies = listOf("Implement inter-service communication")
                    )
                )
                steps.add(
                    MigrationStep(
                        order = order++,
                        description = "Deploy services independently",
                        effortScore = 7,
                        riskLevel = RiskLevel.MEDIUM,
                        dependencies = listOf("Extract services from modules")
                    )
                )
            }

            else -> {
                steps.add(
                    MigrationStep(
                        order = order++,
                        description = "Migrate from $from to $to deployment pattern",
                        effortScore = 10,
                        riskLevel = RiskLevel.HIGH
                    )
                )
            }
        }

        return steps
    }

    private fun generateModulePatternMigrationSteps(
        fromSignature: ArchitectureSignature,
        toSignature: ArchitectureSignature,
        startOrder: Int
    ): List<MigrationStep> {
        val steps = mutableListOf<MigrationStep>()
        var order = startOrder

        steps.add(
            MigrationStep(
                order = order++,
                description = "Analyze current module structure",
                effortScore = 3,
                riskLevel = RiskLevel.LOW
            )
        )
        steps.add(
            MigrationStep(
                order = order++,
                description = "Design target module pattern",
                effortScore = 4,
                riskLevel = RiskLevel.LOW,
                dependencies = listOf("Analyze current module structure")
            )
        )
        steps.add(
            MigrationStep(
                order = order++,
                description = "Refactor module structure",
                effortScore = 8,
                riskLevel = RiskLevel.MEDIUM,
                dependencies = listOf("Design target module pattern")
            )
        )

        return steps
    }

    private fun generateDesignPatternMigrationSteps(
        fromSignature: ArchitectureSignature,
        toSignature: ArchitectureSignature,
        startOrder: Int
    ): List<MigrationStep> {
        val steps = mutableListOf<MigrationStep>()
        var order = startOrder

        steps.add(
            MigrationStep(
                order = order++,
                description = "Identify design pattern usage",
                effortScore = 2,
                riskLevel = RiskLevel.LOW
            )
        )
        steps.add(
            MigrationStep(
                order = order++,
                description = "Replace incompatible design patterns",
                effortScore = 6,
                riskLevel = RiskLevel.MEDIUM,
                dependencies = listOf("Identify design pattern usage")
            )
        )

        return steps
    }
}
