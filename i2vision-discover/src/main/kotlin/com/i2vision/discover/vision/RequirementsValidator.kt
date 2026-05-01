/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.discover.vision

import com.i2vision.discover.pipeline.VisionCodeEvidence
import org.slf4j.LoggerFactory
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.nio.file.Files

data class HumanRequirement(
    val id: String,
    val title: String,
    val description: String?,
    val priority: String,
    val status: String,
    val acceptanceCriteria: List<String>,
    val evidence: List<RequirementEvidence>
)

data class RequirementEvidence(
    val file: String,
    val line: Int,
    val confidence: Double
)

data class RequirementMatch(
    val humanId: String,
    val humanTitle: String,
    val codeIds: List<String>,
    val status: MatchStatus,
    val evidence: List<VisionCodeEvidence>
)

enum class MatchStatus {
    IMPLEMENTED,
    MISSING
}

data class ValidationResult(
    val implemented: List<RequirementMatch>,
    val missing: List<RequirementMatch>,
    val orphaned: List<InferredRequirement>,
    val totalHuman: Int,
    val totalCode: Int
)

class RequirementsValidator(
    private val projectRoot: File
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val yaml = Yaml()

    fun validate(
        humanRequirements: List<HumanRequirement>,
        codeRequirements: List<InferredRequirement>
    ): ValidationResult {
        log.info("[VALIDATOR] Validating {} human requirements against {} code-inferred requirements",
            humanRequirements.size, codeRequirements.size)

        val implemented = mutableListOf<RequirementMatch>()
        val missing = mutableListOf<RequirementMatch>()
        val orphaned = mutableListOf<InferredRequirement>()

        humanRequirements.forEach { humanReq ->
            val matches = findCodeMatches(humanReq, codeRequirements)
            if (matches.isNotEmpty()) {
                implemented.add(RequirementMatch(
                    humanId = humanReq.id,
                    humanTitle = humanReq.title,
                    codeIds = matches.map { it.id },
                    status = MatchStatus.IMPLEMENTED,
                    evidence = matches.flatMap { it.evidence }
                ))
            } else {
                missing.add(RequirementMatch(
                    humanId = humanReq.id,
                    humanTitle = humanReq.title,
                    codeIds = emptyList(),
                    status = MatchStatus.MISSING,
                    evidence = emptyList()
                ))
            }
        }

        codeRequirements.forEach { codeReq ->
            val hasHumanMatch = humanRequirements.any { human ->
                human.title.equals(codeReq.title, ignoreCase = true) ||
                human.description?.contains(codeReq.title, ignoreCase = true) == true
            }
            if (!hasHumanMatch) {
                orphaned.add(codeReq)
            }
        }

        log.info("[VALIDATOR] Validation complete: {} implemented, {} missing, {} orphaned",
            implemented.size, missing.size, orphaned.size)

        return ValidationResult(
            implemented = implemented,
            missing = missing,
            orphaned = orphaned,
            totalHuman = humanRequirements.size,
            totalCode = codeRequirements.size
        )
    }

    fun loadHumanRequirements(): List<HumanRequirement> {
        val requirementsDir = File(projectRoot, ".vision-ai/.vision/requirements")
        if (!requirementsDir.exists()) {
            log.warn("[VALIDATOR] Human requirements directory not found: {}", requirementsDir.path)
            return emptyList()
        }

        val requirements = mutableListOf<HumanRequirement>()
        try {
            val files = try {
                requirementsDir.listFiles { _, name -> name.endsWith(".yaml") }
            } catch (e: SecurityException) {
                // Skip directory if access denied (common on Windows)
                log.trace("[VALIDATOR] Access denied to requirements directory: {}", requirementsDir.path)
                null
            }
            files?.forEach { file ->
                try {
                    // Use NIO for thread-safe file reading on Windows
                    val yamlContent = String(Files.readAllBytes(file.toPath()), Charsets.UTF_8)
                    val data = yaml.load<Map<String, Any>>(yamlContent)
                    val req = HumanRequirement(
                        id = data["id"] as? String ?: file.nameWithoutExtension,
                        title = data["title"] as? String ?: "(no title)",
                        description = data["description"] as? String,
                        priority = data["priority"] as? String ?: "MEDIUM",
                        status = data["status"] as? String ?: "DRAFT",
                        acceptanceCriteria = data["acceptance_criteria"] as? List<String> ?: emptyList(),
                        evidence = (data["evidence"] as? List<*>)?.mapNotNull { evidence ->
                            val evidenceMap = evidence as? Map<*, *>
                            if (evidenceMap != null) {
                                RequirementEvidence(
                                    file = evidenceMap["file"] as? String ?: "",
                                    line = (evidenceMap["line"] as? Number)?.toInt() ?: 0,
                                    confidence = (evidenceMap["confidence"] as? Number)?.toDouble() ?: 0.0
                                )
                            } else null
                        } ?: emptyList()
                    )
                    requirements.add(req)
                } catch (e: Exception) {
                    log.warn("[VALIDATOR] Error loading requirement from {}: {}", file.name, e.message)
                }
            }
        } catch (e: SecurityException) {
            log.trace("[VALIDATOR] Access denied to requirements directory: {}", requirementsDir.path)
        }

        log.info("[VALIDATOR] Loaded {} human requirements from {}", requirements.size, requirementsDir.path)
        return requirements
    }

    private fun findCodeMatches(
        humanReq: HumanRequirement,
        codeRequirements: List<InferredRequirement>
    ): List<InferredRequirement> {
        return codeRequirements.filter { codeReq ->
            val titleMatch = humanReq.title.equals(codeReq.title, ignoreCase = true)
            val humanKeywords = extractKeywords(humanReq.title + " " + (humanReq.description ?: ""))
            val codeKeywords = extractKeywords(codeReq.title)
            val keywordMatch = humanKeywords.intersect(codeKeywords).size >= 2
            titleMatch || keywordMatch
        }
    }

    private fun extractKeywords(text: String): Set<String> {
        val stopWords = setOf("the", "a", "an", "and", "or", "but", "in", "on", "at", "to", "for",
                               "of", "with", "by", "from", "as", "is", "was", "are", "were",
                               "this", "that", "these", "those", "must", "should", "will", "can",
                               "system", "feature", "provide", "support", "implement", "add")
        return text.split(Regex("[^a-zA-Z0-9]+"))
            .map { it.trim().lowercase() }
            .filter { it.length > 3 }
            .filter { it !in stopWords }
            .toSet()
    }
}