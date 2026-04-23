/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.discover.doc

import com.i2vision.vslfc.DocLayerContract
import com.i2vision.vslfc.VSLFCLayerContracts
import org.slf4j.LoggerFactory
import java.io.File
import java.time.Instant

/**
 * Document Layer Importer
 * 
 * Combines all Phase 1 components to import documentation into VSLFC layer artifacts:
 * - Uses DocContractYamlParser to load contracts
 * - Uses DocSectionExtractor to extract doc sections
 * - Uses CodeEvidenceFinder to find code evidence
 * - Creates layer artifacts with evidence-based confidence scores
 * 
 * ## Import Process
 * 
 * ```
 * 1. Load Contract
 *    ↓
 * 2. For each mapping in contract:
 *    a. Extract section from doc (SectionExtractor)
 *    b. Find code evidence (EvidenceFinder)
 *    c. Calculate confidence (base × evidence multiplier)
 *    d. Create artifact item
 *    ↓
 * 3. Return ImportResult with all artifacts
 * ```
 * 
 * ## Example Usage
 * 
 * ```kotlin
 * val importer = DocLayerImporter(projectRoot)
 * 
 * // Import STRUCTURE layer from docs/architecture.md
 * val contract = parser.parse(File(".vision-ai/.structure/contracts/with-docs.yaml"))
 * val result = importer.importFromDocs(contract)
 * 
 * // Result contains:
 * // - components: [DiscoveryPipeline (confidence: 0.95), IndexProvider (0.88), ...]
 * // - dependencies: [Kotlin Coroutines (1.0), YAML (0.9), ...]
 * // - All with code evidence and traceability
 * ```
 */
class DocLayerImporter(
    private val projectRoot: File
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val sectionExtractor = DocSectionExtractor()
    private val evidenceFinder = CodeEvidenceFinder(projectRoot)

    /**
     * Import documentation into VSLFC artifacts based on contract.
     */
    fun importFromDocs(contract: DocLayerContract): ImportResult {
        log.info("[IMPORTER] Importing ${contract.layer.name} layer from documentation")

        val startTime = Instant.now()
        val artifacts = mutableMapOf<String, List<ImportedItem>>()
        val errors = mutableListOf<String>()
        var totalItemsImported = 0

        // Process primary documentation
        val primaryDoc = File(projectRoot, contract.primaryDoc)
        if (!primaryDoc.exists()) {
            val error = "Primary doc not found: ${contract.primaryDoc}"
            log.warn("[IMPORTER] $error")
            errors.add(error)
        } else {
            val primaryResults = processDocumentFile(primaryDoc, contract)
            artifacts.putAll(primaryResults)
            totalItemsImported += primaryResults.values.sumOf { it.size }
        }

        // Process secondary documentation
        contract.secondaryDocs.forEach { secondaryPath ->
            val secondaryDoc = File(projectRoot, secondaryPath)
            if (secondaryDoc.exists()) {
                val secondaryResults = processDocumentFile(secondaryDoc, contract)

                // Merge with existing artifacts
                secondaryResults.forEach { (field, items) ->
                    val existing = artifacts[field] ?: emptyList()
                    artifacts[field] = existing + items
                }
                totalItemsImported += secondaryResults.values.sumOf { it.size }
            } else {
                log.debug("[IMPORTER] Secondary doc not found: $secondaryPath")
            }
        }

        val duration = java.time.Duration.between(startTime, Instant.now())

        log.info("[IMPORTER] Imported $totalItemsImported item(s) from ${contract.layer.name} layer documentation")

        return ImportResult(
            layer = contract.layer,
            contractId = contract.contractId,
            artifacts = artifacts,
            totalItems = totalItemsImported,
            errors = errors,
            durationMs = duration.toMillis()
        )
    }

    /**
     * Process a single documentation file according to contract mappings.
     */
    private fun processDocumentFile(
        docFile: File,
        contract: DocLayerContract
    ): Map<String, List<ImportedItem>> {
        log.debug("[IMPORTER] Processing ${docFile.name}")

        val results = mutableMapOf<String, List<ImportedItem>>()

        // Extract all sections based on mappings
        val extractedSections = sectionExtractor.extractSections(docFile, contract.mappings)

        // Process each extracted section
        extractedSections.forEach { (layerField, extraction) ->
            if (!extraction.found) {
                log.debug("[IMPORTER] Section not found for field: $layerField")
                return@forEach
            }

            // Get the mapping for this field to know base confidence
            val mapping = contract.mappings.find { it.layerField == layerField }
            val baseConfidence = mapping?.confidence ?: 0.8

            // Process each item in the section
            val items = extraction.items.mapNotNull { itemText ->
                if (itemText.isBlank()) return@mapNotNull null

                processItem(
                    itemText = itemText,
                    layerField = layerField,
                    baseConfidence = baseConfidence,
                    layer = contract.layer,
                    sourceDoc = docFile.name,
                    sectionHeader = mapping?.docSection ?: ""
                )
            }

            if (items.isNotEmpty()) {
                results[layerField] = items
                log.debug("[IMPORTER] Imported ${items.size} item(s) for field: $layerField")
            }
        }

        return results
    }

    /**
     * Process a single item from documentation:
     * 1. Find code evidence
     * 2. Calculate confidence
     * 3. Create ImportedItem
     */
    private fun processItem(
        itemText: String,
        layerField: String,
        baseConfidence: Double,
        layer: DocLayerContract.VSLFCLayer,
        sourceDoc: String,
        sectionHeader: String
    ): ImportedItem {
        // Find code evidence
        val evidenceResult = evidenceFinder.findEvidence(
            itemText,
            convertToVSLFCLayer(layer)
        )

        // Calculate final confidence
        val finalConfidence = (baseConfidence * evidenceResult.confidenceMultiplier).coerceIn(0.0, 1.0)

        // Extract title from item text (first line or sentence)
        val title = extractTitle(itemText)
        val description = if (itemText.length > title.length) {
            itemText.substring(title.length).trim().removePrefix(":").trim()
        } else ""

        return ImportedItem(
            title = title,
            description = description,
            fullText = itemText,
            layerField = layerField,
            baseConfidence = baseConfidence,
            evidenceConfidence = evidenceResult.confidenceMultiplier,
            finalConfidence = finalConfidence,
            codeEvidence = evidenceResult.evidence,
            source = "documentation",
            sourceDoc = sourceDoc,
            sectionHeader = sectionHeader,
            importedAt = Instant.now()
        )
    }

    /**
     * Extract a title from item text.
     * Examples:
     * - "DiscoveryPipeline: Orchestrates discovery" → "DiscoveryPipeline"
     * - "System must support X" → "System must support X"
     */
    private fun extractTitle(text: String): String {
        // If there's a colon, take everything before it
        val colonIndex = text.indexOf(':')
        if (colonIndex > 0 && colonIndex < 50) {
            return text.substring(0, colonIndex).trim()
        }

        // Otherwise, take first line or first sentence
        val firstLine = text.lines().first().trim()
        val periodIndex = firstLine.indexOf('.')

        return if (periodIndex > 0 && periodIndex < firstLine.length - 1) {
            firstLine.substring(0, periodIndex + 1).trim()
        } else {
            firstLine.take(100).trim()
        }
    }

    /**
     * Convert DocLayerContract.VSLFCLayer to VSLFCLayerContracts.Layer
     */
    private fun convertToVSLFCLayer(layer: DocLayerContract.VSLFCLayer): VSLFCLayerContracts.Layer {
        return when (layer) {
            DocLayerContract.VSLFCLayer.VISION -> VSLFCLayerContracts.Layer.VISION
            DocLayerContract.VSLFCLayer.STRUCTURE -> VSLFCLayerContracts.Layer.STRUCTURE
            DocLayerContract.VSLFCLayer.LOGIC -> VSLFCLayerContracts.Layer.LOGIC
            DocLayerContract.VSLFCLayer.FLOW -> VSLFCLayerContracts.Layer.FLOW
            DocLayerContract.VSLFCLayer.CODE -> VSLFCLayerContracts.Layer.CODE
        }
    }

    /**
     * Import all layers from their respective documentation files.
     */
    fun importAllLayers(contracts: Map<VSLFCLayerContracts.Layer, DocLayerContract>): Map<VSLFCLayerContracts.Layer, ImportResult> {
        log.info("[IMPORTER] Importing all ${contracts.size} VSLFC layer(s)")

        return contracts.mapValues { (layer, contract) ->
            try {
                importFromDocs(contract)
            } catch (e: Exception) {
                log.error("[IMPORTER] Failed to import ${layer.name} layer: ${e.message}", e)
                ImportResult.error(contract.layer, contract.contractId, e.message ?: "Unknown error")
            }
        }
    }

    // Data classes

    /**
     * Result of importing a layer from documentation.
     */
    data class ImportResult(
        val layer: DocLayerContract.VSLFCLayer,
        val contractId: String,
        val artifacts: Map<String, List<ImportedItem>>,
        val totalItems: Int,
        val errors: List<String> = emptyList(),
        val durationMs: Long = 0
    ) {
        val success: Boolean get() = errors.isEmpty()

        fun summary(): String = buildString {
            appendLine("Import ${layer.name} layer: ${if (success) "✅ SUCCESS" else "❌ FAILED"}")
            appendLine("  Contract: $contractId")
            appendLine("  Total items: $totalItems")
            appendLine("  Duration: ${durationMs}ms")

            if (artifacts.isNotEmpty()) {
                appendLine("  Artifacts:")
                artifacts.forEach { (field, items) ->
                    val avgConfidence = items.map { it.finalConfidence }.average()
                    appendLine(
                        "    - $field: ${items.size} item(s) (avg confidence: ${
                            String.format(
                                "%.2f",
                                avgConfidence
                            )
                        })"
                    )
                }
            }

            if (errors.isNotEmpty()) {
                appendLine("  Errors:")
                errors.forEach { error ->
                    appendLine("    - $error")
                }
            }
        }

        companion object {
            fun error(layer: DocLayerContract.VSLFCLayer, contractId: String, error: String) =
                ImportResult(layer, contractId, emptyMap(), 0, listOf(error))
        }
    }

    /**
     * Single item imported from documentation.
     */
    data class ImportedItem(
        val title: String,
        val description: String,
        val fullText: String,
        val layerField: String,
        val baseConfidence: Double,
        val evidenceConfidence: Double,
        val finalConfidence: Double,
        val codeEvidence: List<CodeEvidenceFinder.CodeEvidence>,
        val source: String,
        val sourceDoc: String,
        val sectionHeader: String,
        val importedAt: Instant
    ) {
        fun toYaml(): String = buildString {
            appendLine("  - title: \"$title\"")
            if (description.isNotBlank()) {
                appendLine("    description: \"$description\"")
            }
            appendLine("    confidence: $finalConfidence")
            appendLine("    source: $source")
            appendLine("    source_doc: \"$sourceDoc\"")
            appendLine("    section: \"$sectionHeader\"")

            if (codeEvidence.isNotEmpty()) {
                appendLine("    evidence:")
                codeEvidence.take(3).forEach { evidence ->
                    appendLine("      - file: \"${evidence.file}\"")
                    appendLine("        line: ${evidence.line}")
                    appendLine("        type: ${evidence.matchType.name.lowercase()}")
                }
                if (codeEvidence.size > 3) {
                    appendLine("      # ... and ${codeEvidence.size - 3} more")
                }
            }
        }
    }
}
