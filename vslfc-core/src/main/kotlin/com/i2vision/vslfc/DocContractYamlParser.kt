/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.vslfc

import com.i2vision.vslfc.VSLFCLayerContracts.Layer
import org.slf4j.LoggerFactory
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * YAML Contract Parser
 * 
 * Parses documentation contract YAML files for all VSLFC layers.
 * 
 * ## Contract File Structure
 * 
 * ```yaml
 * contract: vision-documentation
 * version: 2.0
 * layer: VISION
 * direction: bidirectional
 * 
 * documentation:
 *   primary: "README.md"
 *   secondary:
 *     - "docs/requirements.md"
 * 
 * mappings:
 *   - doc_section: "## Requirements"
 *     layer_field: "requirements"
 *     parser: "markdown_list"
 *     confidence: 0.95
 * 
 * sync_rules:
 *   on_doc_change: "reimport"
 *   on_layer_change: "suggest_update"
 *   conflict_resolution:
 *     rules:
 *       - confidence_threshold: 0.8
 *         action: "prefer_code_evidence"
 *     default: "manual_review"
 *   freshness:
 *     warning_days: 90
 *     critical_days: 180
 *     action_on_stale: "revalidate_with_lower_confidence"
 *   export_to: ".semantic-cache/{cluster}/docs/generated-vision.md"
 *   import_from: ["README.md", "docs/requirements.md"]
 * 
 * validation:
 *   - rule: "every_doc_requirement_has_code_evidence"
 *     severity: "warning"
 *   - rule: "confidence_decay"
 *     formula: "confidence * (0.95 ^ months_since_update)"
 *     threshold: 0.7
 *     action: "revalidate"
 * ```
 */
class DocContractYamlParser(
    private val projectRoot: File
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val yaml = Yaml()

    // Thread-safe cache for parsed contracts to avoid concurrent file access on Windows
    private val contractCache = ConcurrentHashMap<String, DocLayerContract>()

    // Cache for all contracts per layer
    private val layerContractsCache = ConcurrentHashMap<Layer, List<DocLayerContract>>()

    /**
     * Parse a contract file for a specific VSLFC layer.
     * Handles both old format (single contract with 'contract' field) and new format (contracts array).
     * Results are cached to avoid concurrent file access on Windows.
     * Returns null if the contracts array is empty.
     */
    fun parse(contractFile: File): DocLayerContract? {
        val cacheKey = contractFile.absolutePath

        // Return cached result if available
        contractCache[cacheKey]?.let { cachedContract ->
            log.debug("[PARSER] Using cached contract: ${contractFile.path}")
            return cachedContract
        }

        log.info("[PARSER] Parsing contract: ${contractFile.path}")

        if (!contractFile.exists()) {
            throw ContractParseException("Contract file not found: ${contractFile.path}")
        }

        try {
            val data = yaml.load<Map<String, Any>>(contractFile.reader())
            if (data == null) {
                throw ContractParseException("Failed to parse contract ${contractFile.name}: YAML parser returned null (file may be empty or invalid)")
            }

            // Check if this is the new simplified format with contracts array
            val contractsData = data["contracts"] as? List<Map<String, Any>>
            val contract = if (contractsData != null && contractsData.isNotEmpty()) {
                // New format: contracts array in single file - parse the first contract
                val layer = parseLayerFromData(data)
                val contractData = contractsData[0]
                // Pass file-level documentation as fallback for individual contracts
                val fileLevelDocData = (data["documentation"] as? Map<String, Any>) ?: emptyMap<String, Any>()
                parseContractFromMap(contractData, layer, contractFile, fileLevelDocData)
            } else if (contractsData != null && contractsData.isEmpty()) {
                // Edge case: contracts array exists but is empty - skip this file
                log.warn("Contract file ${contractFile.name} has empty contracts array, skipping")
                return null
            } else {
                // Old format: single contract per file with 'contract' field
                parseContract(data, contractFile)
            }

            // Cache the result for future calls
            contractCache[cacheKey] = contract
            return contract
        } catch (e: Exception) {
            val errorMessage = e.message ?: "Unknown error"
            throw ContractParseException("Failed to parse contract ${contractFile.name}: $errorMessage", e)
        }
    }

    /**
     * Parse layer from data (for new format where layer is at top level).
     */
    private fun parseLayerFromData(data: Map<String, Any>): VSLFCLayerContracts.Layer {
        val layerStr = data["layer"] as? String ?: throw ContractParseException("Missing 'layer' field")
        return when (layerStr.uppercase()) {
            "VISION" -> VSLFCLayerContracts.Layer.VISION
            "STRUCTURE" -> VSLFCLayerContracts.Layer.STRUCTURE
            "LOGIC" -> VSLFCLayerContracts.Layer.LOGIC
            "FLOW" -> VSLFCLayerContracts.Layer.FLOW
            "CODE" -> VSLFCLayerContracts.Layer.CODE
            else -> throw ContractParseException("Invalid layer: $layerStr")
        }
    }

    /**
     * Parse all contracts for a specific layer from a single contract.yaml file.
     * The simplified structure has all contracts for a layer in one file.
     * Results are cached to avoid concurrent file access on Windows.
     */
    fun parseAllContractsForLayer(layer: Layer): List<DocLayerContract> {
        // Return cached result if available
        layerContractsCache[layer]?.let { cachedContracts ->
            log.debug("[PARSER] Using cached contracts for layer: ${layer.name}")
            return cachedContracts
        }

        log.info("[PARSER] Parsing all contracts for ${layer.name} layer")

        val contractPath = VSLFCLayerContracts.contractPath(layer)
        val contractFile = File(projectRoot, contractPath)

        if (!contractFile.exists()) {
            log.warn("[PARSER] Contract file not found for ${layer.name}: $contractPath")
            return emptyList()
        }

        val contracts = try {
            val data = yaml.load<Map<String, Any>>(contractFile.reader())
            if (data == null) {
                log.error("[PARSER] Failed to parse contract file: YAML parser returned null")
                return emptyList()
            }

            // Check if this is the new simplified format with contracts array
            val contractsData = data["contracts"] as? List<Map<String, Any>>
            if (contractsData != null) {
                // New format: contracts array in single file
                // Get file-level documentation for fallback in individual contracts
                val fileLevelDocData = (data["documentation"] as? Map<String, Any>) ?: emptyMap<String, Any>()
                contractsData.mapNotNull { contractData ->
                    try {
                        parseContractFromMap(contractData, layer, contractFile, fileLevelDocData)
                    } catch (e: Exception) {
                        log.error("[PARSER] Failed to parse contract in array: ${e.message}")
                        null
                    }
                }
            } else {
                // Old format: single contract per file (backward compatibility)
                try {
                    listOf(parseContract(data, contractFile))
                } catch (e: Exception) {
                    log.error("[PARSER] Failed to parse contract: ${e.message}")
                    emptyList()
                }
            }
        } catch (e: Exception) {
            log.error("[PARSER] Failed to parse contract file ${contractFile.name}: ${e.message}")
            emptyList()
        }

        // Cache the result
        if (contracts.isNotEmpty()) {
            layerContractsCache[layer] = contracts
        }

        return contracts
    }

    /**
     * Parse a contract from a map (for the new simplified format).
     * @param data The contract data from the contracts array
     * @param layer The VSLFC layer
     * @param sourceFile The source contract.yaml file
     * @param fileLevelDocData File-level documentation (for fallback when contract doesn't have its own documentation)
     */
    private fun parseContractFromMap(
        data: Map<String, Any>,
        layer: Layer,
        sourceFile: File,
        fileLevelDocData: Map<String, Any> = emptyMap()
    ): DocLayerContract {
        val contractId = data["id"] as? String
            ?: throw ContractParseException("Missing 'id' field in contract")

        val version = data["version"]?.toString() ?: "1.0"

        val directionStr = data["direction"] as? String ?: "bidirectional"
        val direction = parseDirection(directionStr)

        // Extract documentation - prefer contract-specific, fall back to file-level
        val contractDocData = (data["documentation"] as? Map<String, Any>) ?: emptyMap()
        val docData = if (contractDocData.isNotEmpty()) {
            contractDocData
        } else {
            // Use file-level documentation as fallback for individual contracts
            fileLevelDocData
        }
        val documentation = parseDocumentation(docData)

        val mappings = parseMappings(data["mappings"] as? List<Map<String, Any>> ?: emptyList())

        // Use default sync rules if not specified
        val syncRules = parseSyncRules(data["sync_rules"] as? Map<String, Any> ?: emptyMap())

        // Handle validation - can be either a list (old) or a map with rules (new)
        val validationData = data["validation"]
        val validationRules = if (validationData is Map<*, *>) {
            // New format: validation: rules: [...]
            parseValidationRules((validationData["rules"] as? List<Map<String, Any>>) ?: emptyList())
        } else {
            // Old format: validation: - rule: ... (backward compatibility)
            parseValidationRules(validationData as? List<Map<String, Any>> ?: emptyList())
        }

        val llmRequirements = parseLLMRequirements(data["llm_requirements"] as? Map<String, Any>)

        val removalGates = parseRemovalGates(data["description_removal_gates"] as? List<Map<String, Any>>)

        log.debug("[PARSER] Parsed contract: $contractId for layer $layer")

        return DocLayerContract(
            contractId = contractId,
            layer = parseLayer(layer.name),
            version = version,
            direction = direction,
            primaryDoc = documentation.primary,
            secondaryDocs = documentation.secondary,
            mappings = mappings,
            syncRules = syncRules,
            validationRules = validationRules,
            llmRequirements = llmRequirements,
            descriptionRemovalGates = removalGates
        )
    }

    /**
     * Validate a contract structure without throwing exceptions.
     */
    fun validate(contractFile: File): ValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        if (!contractFile.exists()) {
            errors.add("Contract file not found")
            return ValidationResult(false, errors, warnings)
        }

        try {
            val data = yaml.load<Map<String, Any>>(contractFile.reader())

            // Required fields
            if (!data.containsKey("contract")) errors.add("Missing 'contract' field")
            if (!data.containsKey("layer")) errors.add("Missing 'layer' field")
            if (!data.containsKey("version")) warnings.add("Missing 'version' field")

            // Validate layer value
            if (data.containsKey("layer")) {
                val layerValue = data["layer"] as? String
                if (layerValue !in listOf("VISION", "STRUCTURE", "LOGIC", "FLOW", "CODE")) {
                    errors.add("Invalid layer: $layerValue")
                }
            }

            // Validate documentation section
            if (!data.containsKey("documentation")) {
                warnings.add("Missing 'documentation' section")
            }

            // Validate mappings
            if (!data.containsKey("mappings")) {
                warnings.add("Missing 'mappings' section")
            }

        } catch (e: Exception) {
            errors.add("YAML parse error: ${e.message}")
        }

        return ValidationResult(errors.isEmpty(), errors, warnings)
    }

    // Private parsing methods

    private fun parseContract(data: Map<String, Any>, sourceFile: File): DocLayerContract {
        val contractId = data["contract"] as? String
            ?: throw ContractParseException("Missing 'contract' field")

        val layerStr = data["layer"] as? String
            ?: throw ContractParseException("Missing 'layer' field")

        val layer = parseLayer(layerStr)

        val version = data["version"]?.toString() ?: "1.0"

        val directionStr = data["direction"] as? String ?: "bidirectional"
        val direction = parseDirection(directionStr)

        val documentation = parseDocumentation(data["documentation"] as? Map<String, Any> ?: emptyMap())

        val mappings = parseMappings(data["mappings"] as? List<Map<String, Any>> ?: emptyList())

        val syncRules = parseSyncRules(data["sync_rules"] as? Map<String, Any> ?: emptyMap())

        // Handle validation - can be either a list (old) or a map with rules (new)
        val validationData = data["validation"]
        val validationRules = if (validationData is Map<*, *>) {
            // New format: validation: rules: [...]
            parseValidationRules((validationData["rules"] as? List<Map<String, Any>>) ?: emptyList())
        } else {
            // Old format: validation: - rule: ... (backward compatibility)
            parseValidationRules(validationData as? List<Map<String, Any>> ?: emptyList())
        }

        val llmRequirements = parseLLMRequirements(data["llm_requirements"] as? Map<String, Any>)

        val removalGates = parseRemovalGates(data["description_removal_gates"] as? List<Map<String, Any>>)

        log.debug("[PARSER] Parsed contract: $contractId for layer $layerStr")

        return DocLayerContract(
            contractId = contractId,
            layer = layer,
            version = version,
            direction = direction,
            primaryDoc = documentation.primary,
            secondaryDocs = documentation.secondary,
            mappings = mappings,
            syncRules = syncRules,
            validationRules = validationRules,
            llmRequirements = llmRequirements,
            descriptionRemovalGates = removalGates
        )
    }

    private fun parseLayer(layerStr: String): DocLayerContract.VSLFCLayer {
        return when (layerStr.uppercase()) {
            "VISION" -> DocLayerContract.VSLFCLayer.VISION
            "STRUCTURE" -> DocLayerContract.VSLFCLayer.STRUCTURE
            "LOGIC" -> DocLayerContract.VSLFCLayer.LOGIC
            "FLOW" -> DocLayerContract.VSLFCLayer.FLOW
            "CODE" -> DocLayerContract.VSLFCLayer.CODE
            else -> throw ContractParseException("Invalid layer: $layerStr")
        }
    }

    private fun parseDirection(directionStr: String): DocLayerContract.SyncDirection {
        return when (directionStr.lowercase()) {
            "import_only" -> DocLayerContract.SyncDirection.IMPORT_ONLY
            "export_only" -> DocLayerContract.SyncDirection.EXPORT_ONLY
            "bidirectional" -> DocLayerContract.SyncDirection.BIDIRECTIONAL
            else -> throw ContractParseException("Invalid direction: $directionStr")
        }
    }

    private fun parseDocumentation(data: Map<String, Any>): DocumentationConfig {
        val primary = data["primary"] as? String ?: ""
        val secondary = (data["secondary"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList()

        return DocumentationConfig(primary, secondary)
    }

    private fun parseMappings(data: List<Map<String, Any>>): List<DocLayerContract.DocMapping> {
        return data.mapNotNull { mapping ->
            // Handle standard doc-to-layer mappings
            val docSection = mapping["doc_section"] as? String
            val layerField = mapping["layer_field"] as? String
            
            if (docSection != null && layerField != null) {
                val parserStr = mapping["parser"] as? String ?: "free_text"
                val parser = parseParserType(parserStr)
                val confidence = (mapping["confidence"] as? Number)?.toDouble() ?: 0.8
                return@mapNotNull DocLayerContract.DocMapping(docSection, layerField, parser, confidence)
            }
            
            // Handle pattern-based mappings (skip them for now as they're not doc-to-layer)
            // These are used for cross-layer validation, not doc extraction
            log.debug("[PARSER] Skipping pattern-based mapping: ${mapping.keys}")
            null
        }
    }

    private fun parseParserType(parserStr: String): DocLayerContract.ParserType {
        return when (parserStr.lowercase()) {
            "markdown_list" -> DocLayerContract.ParserType.MARKDOWN_LIST
            "markdown_table" -> DocLayerContract.ParserType.MARKDOWN_TABLE
            "yaml_embedded" -> DocLayerContract.ParserType.YAML_EMBEDDED
            "free_text" -> DocLayerContract.ParserType.FREE_TEXT
            else -> DocLayerContract.ParserType.FREE_TEXT
        }
    }

    private fun parseSyncRules(data: Map<String, Any>): DocLayerContract.SyncRules {
        val onDocChangeStr = data["on_doc_change"] as? String ?: "reimport"
        val onDocChange = parseSyncAction(onDocChangeStr)

        val onLayerChangeStr = data["on_layer_change"] as? String ?: "suggest_update"
        val onLayerChange = parseSyncAction(onLayerChangeStr)

        val conflictResolution = parseConflictResolution(
            data["conflict_resolution"] as? Map<String, Any> ?: emptyMap()
        )

        val freshness = parseFreshnessRules(
            data["freshness"] as? Map<String, Any> ?: emptyMap()
        )

        return DocLayerContract.SyncRules(
            onDocChange = onDocChange,
            onLayerChange = onLayerChange,
            conflictResolution = conflictResolution,
            freshness = freshness
        )
    }

    private fun parseSyncAction(actionStr: String): DocLayerContract.SyncAction {
        return when (actionStr.lowercase()) {
            "reimport" -> DocLayerContract.SyncAction.REIMPORT
            "suggest_update" -> DocLayerContract.SyncAction.SUGGEST_UPDATE
            "auto_update" -> DocLayerContract.SyncAction.AUTO_UPDATE
            "flag_for_review" -> DocLayerContract.SyncAction.FLAG_FOR_REVIEW
            else -> DocLayerContract.SyncAction.FLAG_FOR_REVIEW
        }
    }

    private fun parseConflictResolution(data: Map<String, Any>): DocLayerContract.ConflictResolutionStrategy {
        val rulesData = data["rules"] as? List<Map<String, Any>> ?: emptyList()
        val rules = rulesData.map { ruleData ->
            val threshold = (ruleData["confidence_threshold"] as? Number)?.toDouble() ?: 0.5
            val actionStr = ruleData["action"] as? String ?: "manual_review"
            val action = parseConflictAction(actionStr)

            DocLayerContract.ConflictRule(threshold, action)
        }

        val defaultStr = data["default"] as? String ?: "manual_review"
        val default = parseConflictAction(defaultStr)

        return DocLayerContract.ConflictResolutionStrategy(rules, default)
    }

    private fun parseConflictAction(actionStr: String): DocLayerContract.ConflictAction {
        return when (actionStr.lowercase()) {
            "prefer_doc" -> DocLayerContract.ConflictAction.PREFER_DOC
            "prefer_layer" -> DocLayerContract.ConflictAction.PREFER_LAYER
            "prefer_code_evidence" -> DocLayerContract.ConflictAction.PREFER_CODE_EVIDENCE
            "flag_for_review" -> DocLayerContract.ConflictAction.FLAG_FOR_REVIEW
            "manual_review" -> DocLayerContract.ConflictAction.MANUAL_REVIEW
            else -> DocLayerContract.ConflictAction.MANUAL_REVIEW
        }
    }

    private fun parseFreshnessRules(data: Map<String, Any>): DocLayerContract.FreshnessRules {
        val warningDays = (data["warning_days"] as? Number)?.toInt() ?: 90
        val criticalDays = (data["critical_days"] as? Number)?.toInt() ?: 180

        val actionStr = data["action_on_stale"] as? String ?: "revalidate_with_lower_confidence"
        val action = parseStaleAction(actionStr)

        return DocLayerContract.FreshnessRules(warningDays, criticalDays, action)
    }

    private fun parseStaleAction(actionStr: String): DocLayerContract.StaleAction {
        return when (actionStr.lowercase()) {
            "revalidate_with_lower_confidence" -> DocLayerContract.StaleAction.REVALIDATE_WITH_LOWER_CONFIDENCE
            "flag_for_update" -> DocLayerContract.StaleAction.FLAG_FOR_UPDATE
            "auto_reimport" -> DocLayerContract.StaleAction.AUTO_REIMPORT
            else -> DocLayerContract.StaleAction.REVALIDATE_WITH_LOWER_CONFIDENCE
        }
    }

    private fun parseValidationRules(data: List<Map<String, Any>>): List<DocLayerContract.ValidationRule> {
        return data.map { ruleData ->
            val rule = ruleData["rule"] as? String
                ?: throw ContractParseException("Validation rule missing 'rule' field")

            val severityStr = ruleData["severity"] as? String ?: "warning"
            val severity = parseSeverity(severityStr)

            val formula = ruleData["formula"] as? String
            val threshold = (ruleData["threshold"] as? Number)?.toDouble()
            val action = ruleData["action"] as? String
            val description = ruleData["description"] as? String

            DocLayerContract.ValidationRule(rule, severity, formula, threshold, action, description)
        }
    }

    private fun parseLLMRequirements(data: Map<String, Any>?): DocLayerContract.LLMRequirements? {
        if (data == null) return null

        val relationshipExtractionData = data["relationship_extraction"] as? Map<String, Any> ?: return null
        val requiresLlm = relationshipExtractionData["requires_llm"] as? Boolean ?: false
        val fallbackStrategy = relationshipExtractionData["fallback_strategy"] as? String ?: "manual_curation"

        val monitoringData = relationshipExtractionData["monitoring"] as? List<Map<String, Any>> ?: emptyList()
        val monitoring = monitoringData.map { metricData ->
            DocLayerContract.LLMMonitoringMetric(
                metric = metricData["metric"] as? String ?: "",
                check = metricData["check"] as? String ?: "",
                actionIfMissing = metricData["action_if_missing"] as? String,
                minimumTokens = (metricData["minimum_tokens"] as? Number)?.toInt(),
                confidenceThreshold = (metricData["confidence_threshold"] as? Number)?.toDouble(),
                actionIfInsufficient = metricData["action_if_insufficient"] as? String,
                actionIfLow = metricData["action_if_low"] as? String
            )
        }

        return DocLayerContract.LLMRequirements(
            relationshipExtraction = DocLayerContract.LLMDependency(
                requiresLlm = requiresLlm,
                fallbackStrategy = fallbackStrategy,
                monitoring = monitoring
            )
        )
    }

    private fun parseRemovalGates(data: List<Map<String, Any>>?): List<DocLayerContract.RemovalGate> {
        if (data == null) return emptyList()

        return data.map { gateData ->
            val gate = gateData["gate"] as? String ?: ""
            val condition = gateData["condition"] as? String ?: ""
            val severityStr = gateData["severity"] as? String ?: "warning"
            val severity = parseSeverity(severityStr)
            val description = gateData["description"] as? String ?: ""
            val minimumContentLength = (gateData["minimum_content_length"] as? Number)?.toInt()

            DocLayerContract.RemovalGate(gate, condition, severity, description, minimumContentLength)
        }
    }

    private fun parseSeverity(severityStr: String): DocLayerContract.Severity {
        return when (severityStr.lowercase()) {
            "error" -> DocLayerContract.Severity.ERROR
            "warning" -> DocLayerContract.Severity.WARNING
            "info" -> DocLayerContract.Severity.INFO
            else -> DocLayerContract.Severity.WARNING
        }
    }

    // Data classes

    private data class DocumentationConfig(
        val primary: String,
        val secondary: List<String>
    )

    data class ValidationResult(
        val valid: Boolean,
        val errors: List<String>,
        val warnings: List<String>
    )

    class ContractParseException(message: String, cause: Throwable? = null) : Exception(message, cause)
}
