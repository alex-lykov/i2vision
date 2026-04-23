package com.i2vision.mcp.intelligence

import com.i2vision.storage.I2VisionPaths
import org.slf4j.LoggerFactory
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.time.Instant

/**
 * IntelligenceService - Exposes quality metrics and intelligence data
 * from discovery artifacts via a unified API.
 *
 * Data Sources:
 * - .semantic-cache/{module}/code/complexity.yaml
 * - .semantic-cache/{module}/structure/components.yaml (for cohesion)
 * - .semantic-cache/.tools/cross-module-imports.yaml
 */
class IntelligenceService(private val projectRoot: String) {

    private val log = LoggerFactory.getLogger(IntelligenceService::class.java)
    private val yaml = Yaml()
    private val semanticCachePath = I2VisionPaths.getProjectCacheDir(projectRoot).absolutePath

    /**
     * Get quality metrics for a specific module
     */
    fun getQualityMetrics(modulePath: String): QualityMetrics {
        return try {
            val complexityScore = readComplexityScore(modulePath)
            val cohesionScore = readCohesionScore(modulePath)
            val couplingScore = readCouplingScore(modulePath)

            QualityMetrics(
                module = modulePath,
                complexityScore = complexityScore,
                cohesionScore = cohesionScore,
                couplingScore = couplingScore,
                timestamp = System.currentTimeMillis(),
                timestamp_iso = Instant.now().toString()
            ).also {
                log.info(
                    "[INTELLIGENCE] Quality metrics for {}: complexity={}, cohesion={}, coupling={}",
                    modulePath,
                    String.format("%.2f", complexityScore),
                    String.format("%.2f", cohesionScore),
                    String.format("%.2f", couplingScore)
                )
            }
        } catch (e: Exception) {
            log.error("[INTELLIGENCE] Failed to get quality metrics for {}: {}", modulePath, e.message)
            QualityMetrics(
                module = modulePath,
                complexityScore = 0.0,
                cohesionScore = 0.0,
                couplingScore = 0.0,
                timestamp = System.currentTimeMillis(),
                timestamp_iso = Instant.now().toString()
            )
        }
    }

    /**
     * Get related files for proactive context (when user opens a file)
     * Reads from .semantic-cache/.tools/cross-module-imports.yaml
     */
    fun getRelatedFiles(openedFile: String, modulePath: String): List<RelatedFile> {
        return try {
            val importsFilePath = ".tools/cross-module-imports.yaml"
            val importsFile = File(semanticCachePath, importsFilePath)

            if (!importsFile.exists()) {
                log.warn("[INTELLIGENCE] Cross-module imports file not found")
                return emptyList()
            }

            @Suppress("UNCHECKED_CAST")
            val data = yaml.load(importsFile.readText()) as? Map<String, Any> ?: return emptyList()
            val imports = data["imports"] as? List<Map<String, Any>> ?: return emptyList()

            imports
                .filter { imp ->
                    val fromFile = imp["from_file"] as? String ?: ""
                    fromFile.contains(openedFile) || openedFile.contains(fromFile)
                }
                .map { imp ->
                    RelatedFile(
                        file = imp["to_file"] as? String ?: "",
                        module = imp["to_module"] as? String ?: "",
                        importedSymbols = (imp["imported_symbols"] as? List<String>) ?: emptyList(),
                        reason = "Cross-module import detected"
                    )
                }
                .distinctBy { it.file }
                .also {
                    log.info(
                        "[INTELLIGENCE] Found ${it.size} related files for {} in module {}",
                        openedFile,
                        modulePath
                    )
                }
        } catch (e: Exception) {
            log.error(
                "[INTELLIGENCE] Failed to get related files for {} in module {}: {}",
                openedFile,
                modulePath,
                e.message
            )
            emptyList()
        }
    }

    /**
     * Get complexity details for a module
     */
    fun getComplexityDetails(modulePath: String): ComplexityDetails? {
        return try {
            val complexityFilePath = "$modulePath/code/complexity.yaml"
            val complexityFile = File(semanticCachePath, complexityFilePath)

            if (!complexityFile.exists()) return null

            @Suppress("UNCHECKED_CAST")
            val data = yaml.load(complexityFile.readText()) as? Map<String, Any> ?: return null

            ComplexityDetails(
                module = modulePath,
                averageScore = (data["average_score"] as? Number)?.toDouble() ?: 0.0,
                averageCyclomatic = (data["average_cyclomatic"] as? Number)?.toDouble() ?: 0.0,
                averageCognitive = (data["average_cognitive"] as? Number)?.toDouble() ?: 0.0,
                totalFiles = (data["total_files"] as? Number)?.toInt() ?: 0,
                files = ((data["files"] as? List<Map<String, Any>>) ?: emptyList()).map { file ->
                    FileComplexity(
                        path = file["path"] as? String ?: "",
                        cyclomaticComplexity = (file["cyclomatic_complexity"] as? Number)?.toInt() ?: 0,
                        cognitiveComplexity = (file["cognitive_complexity"] as? Number)?.toInt() ?: 0,
                        complexityScore = (file["complexity_score"] as? Number)?.toDouble() ?: 0.0
                    )
                }
            )
        } catch (e: Exception) {
            log.error("[INTELLIGENCE] Failed to get complexity details for {}: {}", modulePath, e.message)
            null
        }
    }

    /**
     * Get cohesion details for a module
     */
    fun getCohesionDetails(modulePath: String): CohesionDetails? {
        return try {
            val componentsFilePath = "$modulePath/structure/components.yaml"
            val componentsFile = File(semanticCachePath, componentsFilePath)

            if (!componentsFile.exists()) return null

            @Suppress("UNCHECKED_CAST")
            val data = yaml.load(componentsFile.readText()) as? Map<String, Any> ?: return null
            val components = data["components"] as? List<Map<String, Any>> ?: emptyList()

            CohesionDetails(
                module = modulePath,
                averageCohesion = components.mapNotNull { c -> (c["cohesion"] as? Number)?.toDouble() }.average(),
                components = components.map { comp ->
                    ComponentCohesion(
                        name = comp["name"] as? String ?: "",
                        path = comp["path"] as? String ?: "",
                        cohesion = (comp["cohesion"] as? Number)?.toDouble() ?: 0.0,
                        internalDeps = (comp["internal_deps"] as? Number)?.toInt() ?: 0,
                        externalDeps = (comp["external_deps"] as? Number)?.toInt() ?: 0
                    )
                }
            )
        } catch (e: Exception) {
            log.error("[INTELLIGENCE] Failed to get cohesion details for {}: {}", modulePath, e.message)
            null
        }
    }

    // ── Private Helper Methods ────────────────────────────────────────

    private fun readComplexityScore(modulePath: String): Double {
        return try {
            val complexityFilePath = "$modulePath/code/complexity.yaml"
            val complexityFile = File(semanticCachePath, complexityFilePath)

            if (!complexityFile.exists()) return 0.0

            @Suppress("UNCHECKED_CAST")
            val data = yaml.load(complexityFile.readText()) as? Map<String, Any> ?: return 0.0
            (data["average_score"] as? Number)?.toDouble() ?: 0.0
        } catch (e: Exception) {
            0.0
        }
    }

    private fun readCohesionScore(modulePath: String): Double {
        return try {
            val componentsFilePath = "$modulePath/structure/components.yaml"
            val componentsFile = File(semanticCachePath, componentsFilePath)

            if (!componentsFile.exists()) return 0.0

            @Suppress("UNCHECKED_CAST")
            val data = yaml.load(componentsFile.readText()) as? Map<String, Any> ?: return 0.0
            val components = data["components"] as? List<Map<String, Any>> ?: return 0.0

            if (components.isEmpty()) return 0.0
            components.mapNotNull { c -> (c["cohesion"] as? Number)?.toDouble() }.average()
        } catch (e: Exception) {
            0.0
        }
    }

    private fun readCouplingScore(modulePath: String): Double {
        return try {
            val depsFilePath = "$modulePath/structure/dependencies.yaml"
            val depsFile = File(semanticCachePath, depsFilePath)

            if (!depsFile.exists()) return 0.5  // Default neutral score

            @Suppress("UNCHECKED_CAST")
            val data = yaml.load(depsFile.readText()) as? Map<String, Any> ?: return 0.5

            val externalDeps = (data["external_count"] as? Number)?.toDouble() ?: 0.0
            val internalDeps = (data["internal_count"] as? Number)?.toDouble() ?: 1.0

            // Lower coupling is better: 1.0 = no external deps, 0.0 = all external
            1.0 - (externalDeps / (externalDeps + internalDeps))
        } catch (e: Exception) {
            0.5  // Default neutral score
        }
    }
}

// ── Data Classes ────────────────────────────────────────────────────

data class QualityMetrics(
    val module: String,
    val complexityScore: Double,       // 0.0 (complex) to 1.0 (simple)
    val cohesionScore: Double,         // 0.0 (loose) to 1.0 (tight)
    val couplingScore: Double,         // 0.0 (high) to 1.0 (low)
    val timestamp: Long,
    val timestamp_iso: String
)

data class RelatedFile(
    val file: String,
    val module: String,
    val importedSymbols: List<String> = emptyList(),
    val reason: String
)

data class ComplexityDetails(
    val module: String,
    val averageScore: Double,
    val averageCyclomatic: Double,
    val averageCognitive: Double,
    val totalFiles: Int,
    val files: List<FileComplexity>
)

data class FileComplexity(
    val path: String,
    val cyclomaticComplexity: Int,
    val cognitiveComplexity: Int,
    val complexityScore: Double
)

data class CohesionDetails(
    val module: String,
    val averageCohesion: Double,
    val components: List<ComponentCohesion>
)

data class ComponentCohesion(
    val name: String,
    val path: String,
    val cohesion: Double,
    val internalDeps: Int,
    val externalDeps: Int
)
