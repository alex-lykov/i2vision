/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.arch.signature

import com.i2vision.arch.patterns.Pattern

/**
 * Matches architecture signatures against patterns and provides compatibility checks
 */
class SignatureMatcher {

    /**
     * Check if two signatures are compatible
     */
    fun areCompatible(signature1: ArchitectureSignature, signature2: ArchitectureSignature): Boolean {
        // Build systems must be compatible
        if (!areBuildSystemsCompatible(signature1.buildSystem, signature2.buildSystem)) {
            return false
        }

        // Frameworks should have some overlap (per-module)
        val allFrameworks1 = signature1.moduleFrameworks.values.flatten()
        val allFrameworks2 = signature2.moduleFrameworks.values.flatten()
        if (allFrameworks1.isNotEmpty() && allFrameworks2.isNotEmpty()) {
            val hasCommonFramework = allFrameworks1.intersect(allFrameworks2.toSet()).isNotEmpty()
            if (!hasCommonFramework) {
                return false
            }
        }

        return true
    }

    /**
     * Calculate similarity score between two signatures (0.0 to 1.0)
     */
    fun calculateSimilarity(signature1: ArchitectureSignature, signature2: ArchitectureSignature): Double {
        var score = 0.0
        var weight = 0.0

        // Build system weight: 0.2
        weight += 0.2
        if (signature1.buildSystem == signature2.buildSystem) {
            score += 0.2
        }

        // Frameworks weight: 0.3 (per-module)
        weight += 0.3
        val allFrameworks1 = signature1.moduleFrameworks.values.flatten()
        val allFrameworks2 = signature2.moduleFrameworks.values.flatten()
        if (allFrameworks1.isNotEmpty() && allFrameworks2.isNotEmpty()) {
            val intersection = allFrameworks1.intersect(allFrameworks2.toSet())
            val union = allFrameworks1.union(allFrameworks2).toSet()
            if (union.isNotEmpty()) {
                score += 0.3 * (intersection.size.toDouble() / union.size)
            }
        }

        // Module patterns weight: 0.3
        weight += 0.3
        if (signature1.modulePatterns.isNotEmpty() && signature2.modulePatterns.isNotEmpty()) {
            val intersection = signature1.modulePatterns.values.intersect(signature2.modulePatterns.values.toSet())
            val union = signature1.modulePatterns.values.union(signature2.modulePatterns.values).toSet()
            if (union.isNotEmpty()) {
                score += 0.3 * (intersection.size.toDouble() / union.size)
            }
        }

        // Design patterns weight: 0.1 (per-module)
        weight += 0.1
        val allDesignPatterns1 = signature1.moduleDesignPatterns.values.flatten()
        val allDesignPatterns2 = signature2.moduleDesignPatterns.values.flatten()
        if (allDesignPatterns1.isNotEmpty() && allDesignPatterns2.isNotEmpty()) {
            val intersection = allDesignPatterns1.intersect(allDesignPatterns2.toSet())
            val union = allDesignPatterns1.union(allDesignPatterns2).toSet()
            if (union.isNotEmpty()) {
                score += 0.1 * (intersection.size.toDouble() / union.size)
            }
        }

        // Deployment pattern weight: 0.1
        weight += 0.1
        if (signature1.deploymentPattern == signature2.deploymentPattern) {
            score += 0.1
        }

        return if (weight > 0) score / weight else 0.0
    }

    /**
     * Find best matching pattern from a catalog
     */
    fun findBestMatch(signature: ArchitectureSignature, catalog: List<Pattern>): Pattern? {
        return catalog.maxByOrNull { pattern ->
            calculatePatternMatch(signature, pattern)
        }
    }

    /**
     * Calculate match score between signature and pattern (0.0 to 1.0)
     */
    private fun calculatePatternMatch(signature: ArchitectureSignature, pattern: Pattern): Double {
        var score = 0.0
        var weight = 0.0

        // Module pattern match
        weight += 0.5
        if (signature.modulePatterns.values.contains(pattern.modulePattern)) {
            score += 0.5
        }

        // Design pattern match (per-module)
        weight += 0.3
        val allDesignPatterns = signature.moduleDesignPatterns.values.flatten()
        if (allDesignPatterns.contains(pattern.designPattern)) {
            score += 0.3
        }

        // Deployment pattern match
        weight += 0.2
        if (signature.deploymentPattern == pattern.deploymentPattern) {
            score += 0.2
        }

        return if (weight > 0) score / weight else 0.0
    }

    private fun areBuildSystemsCompatible(system1: BuildSystem?, system2: BuildSystem?): Boolean {
        if (system1 == null || system2 == null) return true
        if (system1 == BuildSystem.UNKNOWN || system2 == BuildSystem.UNKNOWN) return true
        return system1 == system2
    }
}
