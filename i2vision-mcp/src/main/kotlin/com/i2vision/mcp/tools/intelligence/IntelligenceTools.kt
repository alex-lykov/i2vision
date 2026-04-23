package com.i2vision.mcp.tools.intelligence

import com.i2vision.mcp.intelligence.IntelligenceService
import org.slf4j.LoggerFactory

/**
 * IntelligenceTools - MCP tools for quality metrics and code intelligence
 * Exposes IntelligenceService functionality as MCP tools
 */
class IntelligenceTools(
    private val projectRoot: String
) {

    private val log = LoggerFactory.getLogger(IntelligenceTools::class.java)
    private val intelligenceService = IntelligenceService(projectRoot)

    /**
     * Get quality metrics for a module
     */
    fun getQualityMetrics(modulePath: String): Map<String, Any> {
        log.debug("[INTELLIGENCE_TOOLS] Getting quality metrics for module: {}", modulePath)
        val metrics = intelligenceService.getQualityMetrics(modulePath)

        return mapOf(
            "module" to metrics.module,
            "complexity_score" to metrics.complexityScore,
            "cohesion_score" to metrics.cohesionScore,
            "coupling_score" to metrics.couplingScore,
            "timestamp" to metrics.timestamp,
            "timestamp_iso" to metrics.timestamp_iso,
            "assessment" to assessOverallQuality(metrics)
        )
    }

    /**
     * Get related files for a specific file
     */
    fun getRelatedFiles(openedFile: String, modulePath: String): Map<String, Any> {
        log.debug("[INTELLIGENCE_TOOLS] Getting related files for: {} in module: {}", openedFile, modulePath)
        val relatedFiles = intelligenceService.getRelatedFiles(openedFile, modulePath)

        return mapOf(
            "opened_file" to openedFile,
            "module_path" to modulePath,
            "related_files" to relatedFiles.map { rf ->
                mapOf(
                    "file" to rf.file,
                    "module" to rf.module,
                    "imported_symbols" to rf.importedSymbols,
                    "reason" to rf.reason
                )
            },
            "count" to relatedFiles.size
        )
    }

    /**
     * Get complexity details for a module
     */
    fun getComplexityDetails(modulePath: String): Map<String, Any> {
        log.debug("[INTELLIGENCE_TOOLS] Getting complexity details for module: {}", modulePath)
        val details = intelligenceService.getComplexityDetails(modulePath)

        if (details == null) {
            return mapOf(
                "module" to modulePath,
                "error" to "Complexity details not available for this module"
            )
        }

        return mapOf(
            "module" to details.module,
            "average_score" to details.averageScore,
            "average_cyclomatic" to details.averageCyclomatic,
            "average_cognitive" to details.averageCognitive,
            "total_files" to details.totalFiles,
            "files" to details.files.map { fc ->
                mapOf(
                    "path" to fc.path,
                    "cyclomatic_complexity" to fc.cyclomaticComplexity,
                    "cognitive_complexity" to fc.cognitiveComplexity,
                    "complexity_score" to fc.complexityScore
                )
            },
            "assessment" to assessComplexity(details)
        )
    }

    /**
     * Get cohesion details for a module
     */
    fun getCohesionDetails(modulePath: String): Map<String, Any> {
        log.debug("[INTELLIGENCE_TOOLS] Getting cohesion details for module: {}", modulePath)
        val details = intelligenceService.getCohesionDetails(modulePath)

        if (details == null) {
            return mapOf(
                "module" to modulePath,
                "error" to "Cohesion details not available for this module"
            )
        }

        return mapOf(
            "module" to details.module,
            "average_cohesion" to details.averageCohesion,
            "components" to details.components.map { cc ->
                mapOf(
                    "name" to cc.name,
                    "path" to cc.path,
                    "cohesion" to cc.cohesion,
                    "internal_deps" to cc.internalDeps,
                    "external_deps" to cc.externalDeps
                )
            },
            "assessment" to assessCohesion(details)
        )
    }

    /**
     * Get comprehensive intelligence report for a module
     */
    fun getIntelligenceReport(modulePath: String): Map<String, Any> {
        log.debug("[INTELLIGENCE_TOOLS] Generating intelligence report for module: {}", modulePath)

        val qualityMetrics = intelligenceService.getQualityMetrics(modulePath)
        val complexityDetails = intelligenceService.getComplexityDetails(modulePath)
        val cohesionDetails = intelligenceService.getCohesionDetails(modulePath)

        return mapOf(
            "module" to modulePath,
            "quality_metrics" to mapOf(
                "complexity_score" to qualityMetrics.complexityScore,
                "cohesion_score" to qualityMetrics.cohesionScore,
                "coupling_score" to qualityMetrics.couplingScore,
                "timestamp" to qualityMetrics.timestamp_iso
            ),
            "complexity" to (complexityDetails?.let { cd ->
                mapOf(
                    "average_score" to cd.averageScore as Any,
                    "average_cyclomatic" to cd.averageCyclomatic as Any,
                    "average_cognitive" to cd.averageCognitive as Any,
                    "total_files" to cd.totalFiles as Any
                )
            } ?: mapOf("error" to "Not available" as Any)) as Any,
            "cohesion" to (cohesionDetails?.let { ch ->
                mapOf(
                    "average_cohesion" to ch.averageCohesion as Any,
                    "component_count" to ch.components.size as Any
                )
            } ?: mapOf("error" to "Not available" as Any)) as Any,
            "overall_assessment" to generateOverallAssessment(qualityMetrics, complexityDetails, cohesionDetails)
        )
    }

    // ── Assessment Helper Methods ────────────────────────────────────────

    private fun assessOverallQuality(metrics: com.i2vision.mcp.intelligence.QualityMetrics): String {
        val avgScore = (metrics.complexityScore + metrics.cohesionScore + metrics.couplingScore) / 3.0
        return when {
            avgScore >= 0.8 -> "Excellent"
            avgScore >= 0.6 -> "Good"
            avgScore >= 0.4 -> "Fair"
            avgScore >= 0.2 -> "Poor"
            else -> "Critical"
        }
    }

    private fun assessComplexity(details: com.i2vision.mcp.intelligence.ComplexityDetails): String {
        return when {
            details.averageScore >= 0.8 -> "Low complexity - well structured"
            details.averageScore >= 0.5 -> "Moderate complexity - acceptable"
            details.averageScore >= 0.3 -> "High complexity - needs attention"
            else -> "Very high complexity - refactoring recommended"
        }
    }

    private fun assessCohesion(details: com.i2vision.mcp.intelligence.CohesionDetails): String {
        return when {
            details.averageCohesion >= 0.8 -> "High cohesion - well organized"
            details.averageCohesion >= 0.5 -> "Moderate cohesion - acceptable"
            details.averageCohesion >= 0.3 -> "Low cohesion - component boundaries unclear"
            else -> "Very low cohesion - consider refactoring"
        }
    }

    private fun generateOverallAssessment(
        metrics: com.i2vision.mcp.intelligence.QualityMetrics,
        complexityDetails: com.i2vision.mcp.intelligence.ComplexityDetails?,
        cohesionDetails: com.i2vision.mcp.intelligence.CohesionDetails?
    ): Map<String, Any> {
        val issues = mutableListOf<String>()
        val recommendations = mutableListOf<String>()

        // Analyze complexity
        if (metrics.complexityScore < 0.4) {
            issues.add("High complexity detected")
            recommendations.add("Consider breaking down complex functions")
        }

        // Analyze cohesion
        if (metrics.cohesionScore < 0.4) {
            issues.add("Low cohesion between components")
            recommendations.add("Review component boundaries and responsibilities")
        }

        // Analyze coupling
        if (metrics.couplingScore < 0.4) {
            issues.add("High coupling between modules")
            recommendations.add("Apply dependency inversion principle")
        }

        // Add specific complexity recommendations
        complexityDetails?.let { cd ->
            if (cd.averageCyclomatic > 10) {
                issues.add("High cyclomatic complexity")
                recommendations.add("Reduce branching and simplify control flow")
            }
            if (cd.averageCognitive > 15) {
                issues.add("High cognitive complexity")
                recommendations.add("Extract complex logic into smaller functions")
            }
        }

        // Add specific cohesion recommendations
        cohesionDetails?.let { ch ->
            val lowCohesionComponents = ch.components.filter { it.cohesion < 0.5 }
            if (lowCohesionComponents.isNotEmpty()) {
                issues.add("Components with low cohesion: ${lowCohesionComponents.map { it.name }.joinToString(", ")}")
                recommendations.add("Review and restructure low-cohesion components")
            }
        }

        return mapOf(
            "overall_quality" to assessOverallQuality(metrics),
            "issues" to issues,
            "recommendations" to recommendations,
            "priority" to if (issues.isEmpty()) "Low" else if (issues.size <= 2) "Medium" else "High"
        )
    }
}
