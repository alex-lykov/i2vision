/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.discover.doc

import com.i2vision.vslfc.DocLayerContract
import com.i2vision.vslfc.VSLFCLayerContracts.Layer
import org.slf4j.LoggerFactory
import java.io.File
import java.time.Duration
import java.time.Instant

/**
 * Contract Validation Engine
 * 
 * Validates consistency between documentation, code, and VSLFC artifacts.
 * Applies validation rules from contracts and generates detailed reports.
 * 
 * ## Validation Rules
 * 
 * 1. **Code Evidence** - Every doc item should have code evidence
 * 2. **Doc Reference** - Every code feature should have doc reference
 * 3. **No Contradictions** - Docs and code should not contradict
 * 4. **Confidence Decay** - Apply time-based decay to stale docs
 * 5. **Freshness** - Flag docs older than thresholds
 * 
 * ## Example Usage
 * 
 * ```kotlin
 * val engine = ContractValidationEngine(projectRoot)
 * 
 * val contract = parser.parse(contractFile)
 * val artifacts = importer.importFromDocs(contract)
 * 
 * val report = engine.validate(contract, artifacts)
 * 
 * // Report contains:
 * // - Total checks: 20
 * // - Passed: 17
 * // - Failed: 3
 * // - Issues: [warnings, errors, info]
 * ```
 */
class ContractValidationEngine(
    private val projectRoot: File
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val evidenceFinder = CodeEvidenceFinder(projectRoot)

    /**
     * Validate imported artifacts against contract rules.
     */
    fun validate(
        contract: DocLayerContract,
        artifacts: DocLayerImporter.ImportResult
    ): ValidationReport {
        log.info("[VALIDATOR] Validating ${contract.layer.name} layer")

        val startTime = Instant.now()
        val issues = mutableListOf<ValidationIssue>()
        var totalChecks = 0
        var passed = 0

        // Apply each validation rule from contract
        contract.validationRules.forEach { rule ->
            val ruleIssues = applyValidationRule(rule, contract, artifacts)
            issues.addAll(ruleIssues)
            totalChecks++
            if (ruleIssues.isEmpty()) passed++
        }

        // Always check code evidence (even if not in contract)
        val evidenceIssues = checkCodeEvidence(artifacts)
        issues.addAll(evidenceIssues)
        totalChecks++
        if (evidenceIssues.isEmpty()) passed++

        // Always check freshness
        val freshnessIssues = checkFreshness(contract, artifacts)
        issues.addAll(freshnessIssues)
        totalChecks++
        if (freshnessIssues.isEmpty()) passed++

        // Check LLM requirements if present
        val llmReqs = contract.llmRequirements
        if (llmReqs != null) {
            val llmIssues = checkLLMRequirements(llmReqs)
            issues.addAll(llmIssues)
            totalChecks++
            if (llmIssues.isEmpty()) passed++
        }

        // Check description removal gates if present
        if (contract.descriptionRemovalGates.isNotEmpty()) {
            val gateIssues = checkRemovalGates(contract.descriptionRemovalGates, artifacts)
            issues.addAll(gateIssues)
            totalChecks++
            if (gateIssues.isEmpty()) passed++
        }

        val duration = Duration.between(startTime, Instant.now())

        val failed = totalChecks - passed
        log.info("[VALIDATOR] Validation complete: $passed/$totalChecks passed, ${issues.size} issue(s)")

        return ValidationReport(
            layer = contract.layer,
            contractId = contract.contractId,
            totalChecks = totalChecks,
            passed = passed,
            failed = failed,
            issues = issues.sortedByDescending { it.severity },
            durationMs = duration.toMillis()
        )
    }

    /**
     * Apply a specific validation rule.
     */
    private fun applyValidationRule(
        rule: DocLayerContract.ValidationRule,
        contract: DocLayerContract,
        artifacts: DocLayerImporter.ImportResult
    ): List<ValidationIssue> {
        log.debug("[VALIDATOR] Applying rule: ${rule.rule}")

        return when {
            rule.rule.contains("code_evidence") -> checkCodeEvidence(artifacts)
            rule.rule.contains("doc_reference") -> checkDocReferences(artifacts)
            rule.rule.contains("contradiction") -> checkContradictions(artifacts)
            rule.rule.contains("confidence_decay") -> applyConfidenceDecay(rule, contract, artifacts)
            rule.rule.contains("doc_ref") -> checkDocRefResolution(artifacts, rule)
            else -> emptyList()
        }
    }

    /**
     * Check that every doc item has code evidence.
     */
    private fun checkCodeEvidence(artifacts: DocLayerImporter.ImportResult): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()

        artifacts.artifacts.forEach { (field, items) ->
            items.forEach { item ->
                if (item.codeEvidence.isEmpty()) {
                    issues.add(
                        ValidationIssue(
                            rule = "code_evidence",
                            severity = DocLayerContract.Severity.WARNING,
                            message = "No code evidence found for '${item.title}'",
                            location = "$field/${item.title}",
                            suggestion = "Verify if '${item.title}' is implemented or update documentation"
                        )
                    )
                } else if (item.finalConfidence < 0.5) {
                    issues.add(
                        ValidationIssue(
                            rule = "code_evidence",
                            severity = DocLayerContract.Severity.INFO,
                            message = "Low confidence (${
                                String.format(
                                    "%.2f",
                                    item.finalConfidence
                                )
                            }) for '${item.title}'",
                            location = "$field/${item.title}",
                            suggestion = "Review implementation or documentation accuracy"
                        )
                    )
                }
            }
        }

        return issues
    }

    /**
     * Check that every layer item has documentation reference.
     */
    private fun checkDocReferences(artifacts: DocLayerImporter.ImportResult): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()

        // For now, all items came from docs, so they have references
        // This would be more useful when we also have code-discovered items
        artifacts.artifacts.forEach { (field, items) ->
            items.forEach { item ->
                if (item.source != "documentation") {
                    issues.add(
                        ValidationIssue(
                            rule = "doc_reference",
                            severity = DocLayerContract.Severity.INFO,
                            message = "'${item.title}' discovered from code but not documented",
                            location = "$field/${item.title}",
                            suggestion = "Add to documentation: ${item.sourceDoc}"
                        )
                    )
                }
            }
        }

        return issues
    }

    /**
     * Check for contradictions between documentation and code.
     */
    private fun checkContradictions(artifacts: DocLayerImporter.ImportResult): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()

        // Check if documented items have conflicting evidence
        artifacts.artifacts.forEach { (field, items) ->
            items.forEach { item ->
                // If confidence is very low despite being documented, might be a contradiction
                if (item.baseConfidence > 0.8 && item.finalConfidence < 0.4) {
                    issues.add(
                        ValidationIssue(
                            rule = "contradiction",
                            severity = DocLayerContract.Severity.ERROR,
                            message = "Possible contradiction: '${item.title}' documented but weak code evidence",
                            location = "$field/${item.title}",
                            suggestion = "Verify if documentation matches implementation"
                        )
                    )
                }
            }
        }

        return issues
    }

    /**
     * Apply confidence decay based on document age.
     */
    private fun applyConfidenceDecay(
        rule: DocLayerContract.ValidationRule,
        contract: DocLayerContract,
        artifacts: DocLayerImporter.ImportResult
    ): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()

        // Get the decay formula and threshold from rule
        val formula = rule.formula ?: "confidence * (0.95 ^ months_since_update)"
        val threshold = rule.threshold ?: 0.7

        // Check primary doc age
        val primaryDoc = File(projectRoot, contract.primaryDoc)
        if (primaryDoc.exists()) {
            val lastModified = Instant.ofEpochMilli(primaryDoc.lastModified())
            val monthsOld = Duration.between(lastModified, Instant.now()).toDays() / 30.0

            if (monthsOld > 1) {
                // Calculate decayed confidence based on formula
                val decayRate = extractDecayRate(formula)
                val decayedConfidence = Math.pow(decayRate, monthsOld)

                if (decayedConfidence < threshold) {
                    issues.add(
                        ValidationIssue(
                            rule = "confidence_decay",
                            severity = DocLayerContract.Severity.WARNING,
                            message = "Documentation is ${monthsOld.toInt()} months old, confidence decayed to ${
                                String.format(
                                    "%.2f",
                                    decayedConfidence
                                )
                            }",
                            location = contract.primaryDoc,
                            suggestion = "Revalidate or update documentation"
                        )
                    )
                }
            }
        }

        return issues
    }

    /**
     * Check documentation freshness.
     */
    private fun checkFreshness(
        contract: DocLayerContract,
        artifacts: DocLayerImporter.ImportResult
    ): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()

        val freshnessRules = contract.syncRules.freshness

        // Check primary doc
        val primaryDoc = File(projectRoot, contract.primaryDoc)
        if (primaryDoc.exists()) {
            val lastModified = Instant.ofEpochMilli(primaryDoc.lastModified())
            val daysOld = Duration.between(lastModified, Instant.now()).toDays()

            when {
                daysOld > freshnessRules.criticalDays -> {
                    issues.add(
                        ValidationIssue(
                            rule = "freshness",
                            severity = DocLayerContract.Severity.ERROR,
                            message = "Documentation is very stale ($daysOld days old, critical threshold: ${freshnessRules.criticalDays} days)",
                            location = contract.primaryDoc,
                            suggestion = "Update documentation urgently"
                        )
                    )
                }

                daysOld > freshnessRules.warningDays -> {
                    issues.add(
                        ValidationIssue(
                            rule = "freshness",
                            severity = DocLayerContract.Severity.WARNING,
                            message = "Documentation is stale ($daysOld days old, warning threshold: ${freshnessRules.warningDays} days)",
                            location = contract.primaryDoc,
                            suggestion = "Consider reviewing and updating documentation"
                        )
                    )
                }
            }
        }

        // Check secondary docs
        contract.secondaryDocs.forEach { secondaryPath ->
            val secondaryDoc = File(projectRoot, secondaryPath)
            if (secondaryDoc.exists()) {
                val lastModified = Instant.ofEpochMilli(secondaryDoc.lastModified())
                val daysOld = Duration.between(lastModified, Instant.now()).toDays()

                if (daysOld > freshnessRules.criticalDays) {
                    issues.add(
                        ValidationIssue(
                            rule = "freshness",
                            severity = DocLayerContract.Severity.WARNING,
                            message = "Secondary doc is very stale ($daysOld days old)",
                            location = secondaryPath,
                            suggestion = "Review and update if needed"
                        )
                    )
                }
            }
        }

        return issues
    }

    /**
     * Extract decay rate from formula string.
     * Example: "confidence * (0.95 ^ months)" → 0.95
     */
    private fun extractDecayRate(formula: String): Double {
        val match = Regex("""(0\.\d+)""").find(formula)
        return match?.value?.toDoubleOrNull() ?: 0.95
    }

    /**
     * Check that all doc_ref pointers resolve to valid files and anchors.
     */
    private fun checkDocRefResolution(
        artifacts: DocLayerImporter.ImportResult,
        rule: DocLayerContract.ValidationRule
    ): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()

        artifacts.artifacts.forEach { (field, items) ->
            items.forEach { item ->
                val docRef = extractDocRef(item)
                if (docRef != null) {
                    // Parse doc_ref format: "filename.md#anchor" or "filename.md"
                    val parts = docRef.split("#")
                    val filename = parts[0]
                    val anchor = parts.getOrNull(1)

                    // Check if file exists
                    val docFile = File(projectRoot, filename)
                    if (!docFile.exists()) {
                        issues.add(
                            ValidationIssue(
                                rule = "doc_ref_resolution",
                                severity = DocLayerContract.Severity.ERROR,
                                message = "Doc reference points to non-existent file: $filename",
                                location = "$field/${item.title}",
                                suggestion = "Create missing file or update doc_ref in source"
                            )
                        )
                    } else {
                        // Check if anchor exists in file
                        if (anchor != null) {
                            val content = docFile.readText()
                            val anchorPattern =
                                Regex("""#{1,6}\s+\Q${anchor.replace("-", "[-\\s]")}\E""", RegexOption.IGNORE_CASE)
                            if (!anchorPattern.containsMatchIn(content)) {
                                issues.add(
                                    ValidationIssue(
                                        rule = "doc_ref_resolution",
                                        severity = DocLayerContract.Severity.ERROR,
                                        message = "Doc reference anchor not found: $anchor in $filename",
                                        location = "$field/${item.title}",
                                        suggestion = "Add anchor section to file or update doc_ref"
                                    )
                                )
                            }
                        }

                        // Check content quality if rule specifies threshold
                        val threshold = rule.threshold?.toInt() ?: 100
                        val contentLength = docFile.readText().length
                        if (contentLength < threshold) {
                            issues.add(
                                ValidationIssue(
                                    rule = "doc_ref_quality",
                                    severity = DocLayerContract.Severity.WARNING,
                                    message = "Referenced doc has insufficient content ($contentLength chars, minimum: $threshold)",
                                    location = "$field/${item.title}",
                                    suggestion = "Expand documentation content"
                                )
                            )
                        }
                    }
                }
            }
        }

        return issues
    }

    /**
     * Extract doc_ref from imported item (if present).
     * This will be needed when items start using doc_ref instead of description.
     */
    private fun extractDocRef(item: DocLayerImporter.ImportedItem): String? {
        // For now, items use source_doc + section, future items will have doc_ref
        // This is a placeholder for when the new format is adopted
        return null
    }

    /**
     * Check LLM availability and configuration for relationship extraction.
     */
    private fun checkLLMRequirements(llmRequirements: DocLayerContract.LLMRequirements): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()

        val relationshipExtraction = llmRequirements.relationshipExtraction

        // Check if LLM is required and available
        if (relationshipExtraction.requiresLlm) {
            // In a real implementation, this would check if an LLM provider is configured
            // For now, we'll assume it's not configured and log a warning
            issues.add(
                ValidationIssue(
                    rule = "llm_availability",
                    severity = DocLayerContract.Severity.WARNING,
                    message = "LLM required for relationship extraction but provider not configured",
                    location = "llm_requirements",
                    suggestion = relationshipExtraction.fallbackStrategy
                )
            )
        }

        // Check monitoring metrics
        relationshipExtraction.monitoring.forEach { metric ->
            when (metric.check) {
                "provider_configured" -> {
                    // In real implementation: check if LLM provider is configured
                    if (metric.actionIfMissing != null) {
                        log.warn("[LLM] ${metric.actionIfMissing}")
                    }
                }

                "sufficient_for_extraction" -> {
                    // In real implementation: check token budget
                    metric.minimumTokens?.let { minTokens ->
                        log.debug("[LLM] Minimum tokens required: $minTokens")
                    }
                }

                "validate_extracted_relationships" -> {
                    // In real implementation: validate relationship extraction quality
                    metric.confidenceThreshold?.let { threshold ->
                        log.debug("[LLM] Confidence threshold: $threshold")
                    }
                }
            }
        }

        return issues
    }

    /**
     * Check description removal gates to ensure safe transition.
     */
    private fun checkRemovalGates(
        gates: List<DocLayerContract.RemovalGate>,
        artifacts: DocLayerImporter.ImportResult
    ): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()

        gates.forEach { gate ->
            val passed = when (gate.condition) {
                "all_doc_refs_resolve" -> {
                    // Check if all doc refs would resolve (placeholder for future implementation)
                    false  // Currently not implemented, so gate fails
                }

                "all_doc_sections_have_minimum_content" -> {
                    // Check if all referenced docs have minimum content
                    val allDocsHaveContent = artifacts.artifacts.values.flatten()
                        .all { item ->
                            val docFile = File(projectRoot, item.sourceDoc)
                            docFile.exists() && docFile.readText().length >= (gate.minimumContentLength ?: 100)
                        }
                    allDocsHaveContent
                }

                "relationships_extracted_or_manual_reviewed" -> {
                    // Check if relationships have been extracted (placeholder)
                    false  // Not yet implemented
                }

                "description_backup_exists" -> {
                    // Check if backup of descriptions exists
                    val backupFile = File(projectRoot, ".semantic-cache/description-backup.yaml")
                    backupFile.exists()
                }

                else -> false
            }

            if (!passed) {
                issues.add(
                    ValidationIssue(
                        rule = "removal_gate",
                        severity = gate.severity,
                        message = "Removal gate '${gate.gate}' not satisfied: ${gate.description}",
                        location = "description_removal_gates/${gate.gate}",
                        suggestion = "Satisfy gate condition before removing descriptions"
                    )
                )
            }
        }

        return issues
    }

    /**
     * Validate all layers at once.
     */
    fun validateAll(
        contracts: Map<Layer, DocLayerContract>,
        allArtifacts: Map<Layer, DocLayerImporter.ImportResult>
    ): Map<Layer, ValidationReport> {
        log.info("[VALIDATOR] Validating all ${contracts.size} layer(s)")

        return contracts.mapNotNull { (layer, contract) ->
            val artifacts = allArtifacts[layer]
            if (artifacts != null) {
                layer to validate(contract, artifacts)
            } else {
                log.warn("[VALIDATOR] No artifacts for ${layer.name}")
                null
            }
        }.toMap()
    }

    // Data classes

    data class ValidationReport(
        val layer: DocLayerContract.VSLFCLayer,
        val contractId: String,
        val totalChecks: Int,
        val passed: Int,
        val failed: Int,
        val issues: List<ValidationIssue>,
        val durationMs: Long
    ) {
        val success: Boolean get() = failed == 0
        val hasErrors: Boolean get() = issues.any { it.severity == DocLayerContract.Severity.ERROR }
        val hasWarnings: Boolean get() = issues.any { it.severity == DocLayerContract.Severity.WARNING }

        fun summary(): String = buildString {
            val status = when {
                hasErrors -> "❌ FAILED"
                hasWarnings -> "⚠️ WARNING"
                else -> "✅ PASSED"
            }

            appendLine("Validation ${layer.name} layer: $status")
            appendLine("  Contract: $contractId")
            appendLine("  Checks: $passed/$totalChecks passed")
            appendLine("  Duration: ${durationMs}ms")

            if (issues.isNotEmpty()) {
                val errors = issues.count { it.severity == DocLayerContract.Severity.ERROR }
                val warnings = issues.count { it.severity == DocLayerContract.Severity.WARNING }
                val infos = issues.count { it.severity == DocLayerContract.Severity.INFO }

                appendLine("  Issues: $errors error(s), $warnings warning(s), $infos info")
                appendLine()
                appendLine("  Details:")

                issues.take(5).forEach { issue ->
                    val icon = when (issue.severity) {
                        DocLayerContract.Severity.ERROR -> "❌"
                        DocLayerContract.Severity.WARNING -> "⚠️"
                        DocLayerContract.Severity.INFO -> "ℹ️"
                    }
                    appendLine("    $icon [${issue.rule}] ${issue.message}")
                    appendLine("       Location: ${issue.location}")
                    if (issue.suggestion != null) {
                        appendLine("       Suggestion: ${issue.suggestion}")
                    }
                }

                if (issues.size > 5) {
                    appendLine("    ... and ${issues.size - 5} more issue(s)")
                }
            }
        }

        fun toYaml(): String = buildString {
            appendLine("validation_report:")
            appendLine("  layer: ${layer.name}")
            appendLine("  contract: $contractId")
            appendLine("  total_checks: $totalChecks")
            appendLine("  passed: $passed")
            appendLine("  failed: $failed")
            appendLine("  duration_ms: $durationMs")

            if (issues.isNotEmpty()) {
                appendLine("  issues:")
                issues.forEach { issue ->
                    appendLine("    - rule: ${issue.rule}")
                    appendLine("      severity: ${issue.severity.name.lowercase()}")
                    appendLine("      message: \"${issue.message}\"")
                    appendLine("      location: \"${issue.location}\"")
                    if (issue.suggestion != null) {
                        appendLine("      suggestion: \"${issue.suggestion}\"")
                    }
                }
            }
        }
    }

    data class ValidationIssue(
        val rule: String,
        val severity: DocLayerContract.Severity,
        val message: String,
        val location: String,
        val suggestion: String? = null
    )
}
