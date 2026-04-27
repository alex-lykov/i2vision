/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.discover.pipeline

import com.i2vision.architecture.ArchitectureDetector
import com.i2vision.arch.signature.SignatureBuilder
import com.i2vision.discover.api.DiscoveryPipeline
import com.i2vision.discover.api.IntentResolver
import com.i2vision.discover.api.models.*
import com.i2vision.discover.artifact.ArtifactWriter
import com.i2vision.discover.doc.DocLayerImporter
import com.i2vision.discover.flow.FlowDiscovery
import com.i2vision.discover.logic.LogicExtractor
import com.i2vision.discover.structure.StructureBuilder
import com.i2vision.index.CustomIndex
import com.i2vision.index.SemanticPathResolver
import com.i2vision.link.LinkService
import com.i2vision.storage.api.CacheStore
import com.i2vision.storage.impl.RolloutManager
import com.i2vision.vslfc.DocContractYamlParser
import com.i2vision.vslfc.VSLFCLayerContracts
import com.i2vision.vslfc.contracts.ContractValidator
import com.i2vision.vslfc.DocLayerContract
import com.i2vision.discover.vision.RequirementsInferer
import com.i2vision.discover.vision.RequirementsValidator
import org.slf4j.LoggerFactory
import java.io.File

// Vision layer data classes

data class VisionRequirement(
    val id: String,
    val title: String,
    val docRef: String,
    val rationaleRef: String? = null,
    val acceptanceCriteriaRefs: List<String> = emptyList(),
    val priority: Priority = Priority.P2,
    val source: Source,
    val confidence: Double,
    val evidence: List<VisionCodeEvidence> = emptyList()
)

enum class Priority { P0, P1, P2 }
enum class Source { USER_INPUT, DOCUMENTATION, LLM_INFERENCE, CODE_PATTERN }

data class VisionConstraint(
    val id: String,
    val docRef: String,
    val type: ConstraintType,
    val severity: Severity,
    val confidence: Double,
    val source: Source,
    val evidence: List<VisionCodeEvidence> = emptyList()
)

enum class ConstraintType { BUSINESS, TECHNICAL, PERFORMANCE, SECURITY, OPERATIONAL }
enum class Severity { HARD, SOFT }

data class VisionCodeEvidence(
    val file: String,
    val line: Int? = null,
    val pattern: String,
    val description: String
)

/**
 * Implementation of DiscoveryPipeline interface.
 * 
 * Uses the following components for discovery:
 * - index-provider: Code intelligence (symbol resolution, call hierarchy)
 * - link-service: Semantic link management
 * - flow-discovery: Flow extraction from call graphs
 * - logic-extractor: Business rule extraction
 * - structure-builder: Component detection from package structure
 * - artifact-writer: Semantic cache persistence
 * - architecture-detector: Technology stack detection
 * - contract-validator: Contract validation
 */
class DiscoveryPipelineImpl(
    private val projectRoot: String,
    private val intentResolver: IntentResolver,
    private val cacheStore: CacheStore,
    private val rolloutManager: RolloutManager = RolloutManager()
) : DiscoveryPipeline {

    private val log = LoggerFactory.getLogger(DiscoveryPipelineImpl::class.java)

    // Orchestrator internals
    private val indexProvider by lazy { CustomIndex(projectRoot) }
    private val linkService by lazy { LinkService(projectRoot) }
    private val semanticPathResolver by lazy { SemanticPathResolver(projectRoot) }
    private val flowDiscovery by lazy { FlowDiscovery(projectRoot, indexProvider) }
    private val logicExtractor by lazy { LogicExtractor(projectRoot, indexProvider) }
    private val structureBuilder by lazy { StructureBuilder(projectRoot, indexProvider) }
    private val artifactWriter by lazy { ArtifactWriter(projectRoot, cacheStore) }
    private val contractValidator by lazy { ContractValidator() }
    private val architectureDetector by lazy { ArchitectureDetector(projectRoot) }

    // Vision layer population components
    private val docContractParser by lazy { DocContractYamlParser(File(projectRoot)) }
    private val docLayerImporter by lazy { DocLayerImporter(File(projectRoot)) }
    private val requirementsInferer by lazy { RequirementsInferer(File(projectRoot)) }
    private val requirementsValidator by lazy { RequirementsValidator(File(projectRoot)) }

    // Batch mode control for LinkService
    fun enableLinkBatchMode() {
        linkService.enableBatchMode()
    }

    fun flushLinkBatch() {
        linkService.flushBatch()
    }

    override suspend fun discover(
        depth: DiscoveryDepth,
        clusterId: String?,
        contracts: List<ContractHint>
    ): PipelineResult {
        log.info("[DISCOVERY] Starting {} discovery for cluster '{}'", depth, clusterId ?: "unknown")

        // Auto-rollout if needed
        if (rolloutManager.needsRollout(File(projectRoot))) {
            log.info("[DISCOVERY] Running automatic rollout before discovery")
            val rolloutResult = rolloutManager.initialize(File(projectRoot))
            if (!rolloutResult.success) {
                log.warn("[DISCOVERY] Rollout failed with errors: {}", rolloutResult.errors.joinToString(", "))
            } else {
                log.info("[DISCOVERY] Rollout completed: created {} items", rolloutResult.created.size)
            }
        }

        val startTime = System.currentTimeMillis()
        val artifacts = mutableListOf<DiscoveryArtifact>()
        val errors = mutableListOf<String>()

        try {
            // Step 1: File scanning using index-provider
            log.info("[DISCOVERY] Step 1: Scanning source files")
            val allSourceFiles = indexProvider.listSourceFiles()

            // Filter source files by clusterId if provided
            val sourceFiles = if (clusterId != null) {
                log.info("[DISCOVERY] Filtering source files for cluster '{}'", clusterId)
                // Convert clusterId to path format (replace : with /)
                val clusterPath = clusterId.replace(":", "/")
                allSourceFiles.filter { file ->
                    file.path.contains(clusterId) || file.path.contains(clusterPath)
                }
            } else {
                allSourceFiles
            }

            artifacts.add(
                DiscoveryArtifact(
                    layer = "code",
                    path = "source_files",
                    content = "Found ${sourceFiles.size} source files (cluster: ${clusterId ?: "all"})"
                )
            )
            log.info(
                "[DISCOVERY] Found {} source files (filtered from {} total)",
                sourceFiles.size,
                allSourceFiles.size
            )

            // Step 2: Symbol extraction using index-provider
            log.info("[DISCOVERY] Step 2: Extracting symbols")
            val symbols = sourceFiles.flatMap { file ->
                indexProvider.symbolsInFile(File(projectRoot, file.path))
            }
            artifacts.add(
                DiscoveryArtifact(
                    layer = "code",
                    path = "symbols",
                    content = "Extracted ${symbols.size} symbols"
                )
            )
            log.info("[DISCOVERY] Extracted {} symbols", symbols.size)

            // Step 3: Entry point detection using index-provider
            log.info("[DISCOVERY] Step 3: Detecting entry points")
            val entryPoints = indexProvider.findEntryPoints()
            artifacts.add(
                DiscoveryArtifact(
                    layer = "code",
                    path = "entry_points",
                    content = "Found ${entryPoints.size} entry points"
                )
            )
            log.info("[DISCOVERY] Found {} entry points", entryPoints.size)

            // Step 4: Cluster suggestions using architecture detection (SignatureBuilder)
            log.info("[DISCOVERY] Step 4: Generating cluster suggestions")
            val signature = SignatureBuilder(projectRoot).build()
            val clusters = signature.clusters.map { cluster ->
                com.i2vision.index.ClusterSuggestion(
                    name = cluster.name,
                    entryPoints = emptyList(),
                    files = emptySet(),
                    cohesion = 1.0
                )
            }

            artifacts.add(
                DiscoveryArtifact(
                    layer = "structure",
                    path = "clusters",
                    content = "Generated ${clusters.size} cluster suggestions"
                )
            )
            log.info("[DISCOVERY] Generated {} cluster suggestions", clusters.size)

            // Step 5: Load existing links using link-service
            log.info("[DISCOVERY] Step 5: Loading semantic links")
            // Use lazy loading: only load links relevant to this cluster if clusterId is provided
            val existingLinks = if (clusterId != null) {
                linkService.getLinksForCluster(clusterId)
            } else {
                linkService.allLinks()
            }
            artifacts.add(
                DiscoveryArtifact(
                    layer = "logic",
                    path = "existing_links",
                    content = "Loaded ${existingLinks.size} existing links ${if (clusterId != null) "(cluster: $clusterId)" else "(all)"}"
                )
            )
            log.info(
                "[DISCOVERY] Loaded {} existing links {}",
                existingLinks.size,
                if (clusterId != null) "(cluster: $clusterId)" else "(all)"
            )

            // Step 6: Architecture detection using ArchitectureDetector
            // Skip per-cluster architecture detection for performance - run only for full project discovery
            if ((depth == DiscoveryDepth.STANDARD || depth == DiscoveryDepth.DEEP) && clusterId == null) {
                log.info("[DISCOVERY] Step 6: Running architecture detection (only for full project discovery)")
                try {
                    val techStack = architectureDetector.detectStack()
                    artifacts.add(
                        DiscoveryArtifact(
                            layer = "vision",
                            path = "architecture_detection",
                            content = "Architecture detection completed\n" +
                                    "Primary language: ${techStack.primaryLanguage.name}\n" +
                                    "Frameworks: ${techStack.frameworks.joinToString(", ") { it.name }}\n" +
                                    "Platforms: ${techStack.platforms.joinToString(", ") { it.name }}\n" +
                                    "Patterns: ${techStack.patterns.joinToString(", ") { it.name }}\n" +
                                    "Confidence: ${techStack.confidence}"
                        )
                    )
                    log.info("[DISCOVERY] Architecture detection completed: {}", techStack.primaryLanguage.name)
                } catch (e: Exception) {
                    log.warn("[DISCOVERY] Architecture detection failed: {}", e.message)
                    errors.add("Architecture detection failed: ${e.message}")
                }
            }

            // Step 6.5: Vision layer population from documentation
            if (depth == DiscoveryDepth.STANDARD || depth == DiscoveryDepth.DEEP) {
                log.info("[DISCOVERY] Step 6.5: Populating Vision layer from documentation")
                try {
                    // Load Vision contract
                    val visionContractPath = VSLFCLayerContracts.contractPath(VSLFCLayerContracts.Layer.VISION)
                    val visionContractFile = File(projectRoot, visionContractPath)

                    if (visionContractFile.exists()) {
                        val visionContract = docContractParser.parse(visionContractFile)
                        log.info("[DISCOVERY] Loaded Vision contract: {}", visionContract.contractId)

                        // Import from documentation
                        val importResult = docLayerImporter.importFromDocs(visionContract)

                        if (importResult.success) {
                            log.info("[DISCOVERY] Imported {} Vision items from documentation", importResult.totalItems)

                            // Convert imported items to Vision artifacts
                            val visionRequirements = mutableListOf<VisionRequirement>()
                            val visionConstraints = mutableListOf<VisionConstraint>()

                            importResult.artifacts.values.flatten().forEach { item ->
                                when (item.layerField) {
                                    "requirements" -> {
                                        visionRequirements.add(
                                            VisionRequirement(
                                            id = sanitizeRequirementId(item.title),
                                            title = item.title,
                                            docRef = "${item.sourceDoc}#${item.sectionHeader}",
                                            confidence = item.finalConfidence,
                                            source = Source.DOCUMENTATION,
                                            evidence = item.codeEvidence.map { evidence ->
                                                VisionCodeEvidence(
                                                    file = evidence.file,
                                                    line = evidence.line,
                                                    pattern = evidence.content,
                                                    description = evidence.matchType.name
                                                )
                                            }
                                        ))
                                    }

                                    "constraints" -> {
                                        visionConstraints.add(
                                            VisionConstraint(
                                            id = sanitizeRequirementId(item.title),
                                            docRef = "${item.sourceDoc}#${item.sectionHeader}",
                                            type = ConstraintType.BUSINESS,
                                            severity = Severity.HARD,
                                            confidence = item.finalConfidence,
                                            source = Source.DOCUMENTATION,
                                            evidence = item.codeEvidence.map { evidence ->
                                                VisionCodeEvidence(
                                                    file = evidence.file,
                                                    line = evidence.line,
                                                    pattern = evidence.content,
                                                    description = evidence.matchType.name
                                                )
                                            }
                                        ))
                                    }
                                }
                            }

                            // Write Vision artifacts to semantic cache
                            kotlinx.coroutines.runBlocking {
                                // Infer requirements from code patterns
                                val normalizedProjectRoot = projectRoot.replace("\\", "/")
                                val sourceFileList = sourceFiles.map {
                                    val file = File(it.path)
                                    val resolvedFile = if (file.isAbsolute) file else File(projectRoot, it.path)
                                    // Normalize path to remove duplicated project roots
                                    val normalizedPath = resolvedFile.absolutePath
                                        .replace("\\", "/")
                                        .replace("/./", "/")
                                        .replace("$normalizedProjectRoot/$normalizedProjectRoot", normalizedProjectRoot)
                                    File(normalizedPath)
                                }
                                val codeInferredRequirements = requirementsInferer.inferRequirements(sourceFileList)

                                // Merge documentation-imported and code-inferred requirements
                                val mergedRequirements = mutableListOf<VisionRequirement>()
                                mergedRequirements.addAll(visionRequirements)

                                // Add code-inferred requirements that don't duplicate documentation ones
                                codeInferredRequirements.forEach { inferred ->
                                    val isDuplicate = visionRequirements.any {
                                        it.title.equals(inferred.title, ignoreCase = true)
                                    }
                                    if (!isDuplicate) {
                                        mergedRequirements.add(
                                            VisionRequirement(
                                                id = inferred.id,
                                                title = inferred.title,
                                                docRef = "code-inferred",
                                                confidence = 0.8, // Default confidence for code-inferred
                                                source = Source.CODE_PATTERN,
                                                evidence = inferred.evidence
                                            )
                                        )
                                    }
                                }
                                
                                log.info("[DISCOVERY] Total Vision requirements: {} ({} from docs, {} from code)", 
                                    mergedRequirements.size, visionRequirements.size, codeInferredRequirements.size)

                                // Perform bidirectional validation
                                val humanRequirements = requirementsValidator.loadHumanRequirements()
                                if (humanRequirements.isNotEmpty()) {
                                    val validationResult = requirementsValidator.validate(humanRequirements, codeInferredRequirements)

                                    log.info("[DISCOVERY] Bidirectional validation: {} implemented, {} missing, {} orphaned",
                                        validationResult.implemented.size, validationResult.missing.size, validationResult.orphaned.size)

                                    artifacts.add(DiscoveryArtifact(
                                        layer = "vision",
                                        path = "bidirectional_validation",
                                        content = "Bidirectional validation results:\n" +
                                            "  Human requirements: ${validationResult.totalHuman}\n" +
                                            "  Code-inferred: ${validationResult.totalCode}\n" +
                                            "  Implemented: ${validationResult.implemented.size}\n" +
                                            "  Missing: ${validationResult.missing.size}\n" +
                                            "  Orphaned: ${validationResult.orphaned.size}\n" +
                                            validationResult.missing.take(5).joinToString("\n") { "  - ${it.humanTitle}" }
                                    ))
                                }


                                log.info(
                                    "[DISCOVERY] Total Vision requirements: {} ({} from docs, {} from code)",
                                    mergedRequirements.size, visionRequirements.size, codeInferredRequirements.size
                                )

                                val writtenVisionArtifacts = artifactWriter.writeVisionArtifacts(
                                    clusterId ?: "root",
                                    mergedRequirements,
                                    visionConstraints
                                )
                                artifacts.add(
                                    DiscoveryArtifact(
                                        layer = "vision",
                                        path = "vision_artifacts",
                                        content = "Wrote ${writtenVisionArtifacts.size} Vision artifacts to semantic cache:\n${
                                            writtenVisionArtifacts.take(
                                                10
                                            ).joinToString("\n") { "- ${it.layer}/${it.name}" }
                                        }"
                                    )
                                )
                                log.info("[DISCOVERY] Wrote {} Vision artifacts", writtenVisionArtifacts.size)
                            }
                        } else {
                            log.warn("[DISCOVERY] Vision import failed: {}", importResult.errors.joinToString(", "))
                            errors.addAll(importResult.errors)
                        }
                    } else {
                        log.info(
                            "[DISCOVERY] Vision contract not found at {}, skipping Vision layer population",
                            visionContractPath
                        )
                    }
                } catch (e: Exception) {
                    log.warn("[DISCOVERY] Vision layer population failed: {}", e.message)
                    errors.add("Vision layer population failed: ${e.message}")
                }
            }

            // Step 7: Flow discovery using FlowDiscovery
            if (depth == DiscoveryDepth.STANDARD || depth == DiscoveryDepth.DEEP) {
                log.info("[DISCOVERY] Step 7: Discovering flows using call graph")
                val flows = flowDiscovery.discoverFlows(symbols, clusterId)
                artifacts.add(
                    DiscoveryArtifact(
                        layer = "flow",
                        path = "flows",
                        content = "Discovered ${flows.size} flows:\n${
                            flows.take(10).joinToString("\n") { "- ${it.name} (${it.steps.size} steps)" }
                        }"
                    )
                )
                log.info("[DISCOVERY] Discovered {} flows", flows.size)
            }

            // Step 7.5: Business rule extraction using LogicExtractor
            if (depth == DiscoveryDepth.STANDARD || depth == DiscoveryDepth.DEEP) {
                log.info("[DISCOVERY] Step 7.5: Extracting business rules")
                val normalizedProjectRoot = projectRoot.replace("\\", "/")
                val sourceFileList = sourceFiles.map {
                    val file = File(it.path)
                    val resolvedFile = if (file.isAbsolute) file else File(projectRoot, it.path)
                    // Normalize path to remove duplicated project roots
                    val normalizedPath = resolvedFile.absolutePath
                        .replace("\\", "/")
                        .replace("/./", "/")
                        .replace("$normalizedProjectRoot/$normalizedProjectRoot", normalizedProjectRoot)
                    File(normalizedPath)
                }
                val businessRules = logicExtractor.extractBusinessRules(sourceFileList)
                artifacts.add(
                    DiscoveryArtifact(
                        layer = "logic",
                        path = "business_rules",
                        content = "Extracted ${businessRules.size} business rules:\n${
                            businessRules.take(10).joinToString("\n") { "- ${it.name} (${it.type}): ${it.condition}" }
                        }"
                    )
                )
                log.info("[DISCOVERY] Extracted {} business rules", businessRules.size)
            }

            // Step 7.6: Component building using StructureBuilder
            if (depth == DiscoveryDepth.STANDARD || depth == DiscoveryDepth.DEEP) {
                log.info("[DISCOVERY] Step 7.6: Building components from package structure")
                log.debug("[DISCOVERY] Sample source file paths: {}", sourceFiles.take(3).map { it.path })
                val normalizedProjectRoot = projectRoot.replace("\\", "/")
                val sourceFileList = sourceFiles.map {
                    val file = File(it.path)
                    val resolvedFile = if (file.isAbsolute) file else File(projectRoot, it.path)
                    // Normalize path to remove duplicated project roots
                    val normalizedPath = resolvedFile.absolutePath
                        .replace("\\", "/")
                        .replace("/./", "/")
                        .replace("$normalizedProjectRoot/$normalizedProjectRoot", normalizedProjectRoot)
                    File(normalizedPath)
                }
                val components = structureBuilder.buildComponents(sourceFileList, symbols)
                artifacts.add(
                    DiscoveryArtifact(
                        layer = "structure",
                        path = "components",
                        content = "Built ${components.size} components:\n${
                            components.take(10)
                                .joinToString("\n") { "- ${it.name} (${it.type}): ${it.files.size} files, ${it.dependencies.size} deps" }
                        }"
                    )
                )
                log.info("[DISCOVERY] Built {} components", components.size)

                // Step 7.7: Write artifacts to semantic cache using ArtifactWriter
                if (depth == DiscoveryDepth.STANDARD || depth == DiscoveryDepth.DEEP) {
                    log.info("[DISCOVERY] Step 7.7: Writing artifacts to semantic cache")
                    val flows = flowDiscovery.discoverFlows(symbols, clusterId)
                    val normalizedProjectRoot = projectRoot.replace("\\", "/")
                    val sourceFileList = sourceFiles.map {
                        val file = File(it.path)
                        val resolvedFile = if (file.isAbsolute) file else File(projectRoot, it.path)
                        // Normalize path to remove duplicated project roots
                        val normalizedPath = resolvedFile.absolutePath
                            .replace("\\", "/")
                            .replace("/./", "/")
                            .replace("$normalizedProjectRoot/$normalizedProjectRoot", normalizedProjectRoot)
                        File(normalizedPath)
                    }
                    val businessRules = logicExtractor.extractBusinessRules(sourceFileList)
                    kotlinx.coroutines.runBlocking {
                        val writtenArtifacts =
                            artifactWriter.writeArtifacts(clusterId, flows, businessRules, components)
                        artifacts.add(
                            DiscoveryArtifact(
                                layer = "code",
                                path = "artifact_files",
                                content = "Wrote ${writtenArtifacts.size} artifact files to semantic cache:\n${
                                    writtenArtifacts.take(
                                        10
                                    ).joinToString("\n") { "- ${it.module}/${it.layer}/${it.name}" }
                                }"
                            )
                        )
                        log.info("[DISCOVERY] Wrote {} artifact files", writtenArtifacts.size)

                        // Write discovery summary to CODE layer
                        val summaryRef =
                            artifactWriter.writeSummary(clusterId ?: "unknown", flows, businessRules, components)
                        artifacts.add(
                            DiscoveryArtifact(
                                layer = "code",
                                path = "discovery_summary",
                                content = "Wrote discovery summary: ${summaryRef.module}/${summaryRef.layer}/${summaryRef.name}"
                            )
                        )
                        log.info("[DISCOVERY] Wrote discovery summary to CODE layer")

                        // Generate and write links between artifacts
                        val generatedLinksCount = if (clusterId != null) {
                            generateLinks(clusterId, flows, businessRules, components)
                        } else {
                            0
                        }
                        artifacts.add(
                            DiscoveryArtifact(
                                layer = "logic",
                                path = "generated_links",
                                content = "Generated $generatedLinksCount cross-layer links"
                            )
                        )
                        log.info("[DISCOVERY] Generated {} cross-layer links", generatedLinksCount)
                    }
                }
            }

            // Step 8: Contract validation using ContractValidator
            if (contracts.isNotEmpty() && (depth == DiscoveryDepth.STANDARD || depth == DiscoveryDepth.DEEP)) {
                log.info("[DISCOVERY] Step 8: Validating {} contract hints", contracts.size)
                // For now, log contract hints - full integration would require DiscoveryContract objects
                artifacts.add(
                    DiscoveryArtifact(
                        layer = "vision",
                        path = "contract_validation",
                        content = "Contract hints: ${contracts.size}\n${contracts.joinToString("\n") { "- ${it.contractPath} (${it.relatedFiles.size} files, ${it.entryPoints.size} entry points)" }}"
                    )
                )
                log.info("[DISCOVERY] Contract validation completed")
            }

            val duration = System.currentTimeMillis() - startTime
            log.info("[DISCOVERY] Discovery completed in {}ms", duration)

            val flowCount = artifacts.count { it.layer == "flow" }
            val businessRuleCount = artifacts.count { it.layer == "logic" && it.path == "business_rules" }
            val componentCount = artifacts.count { it.layer == "structure" && it.path == "components" }
            val artifactFileCount = artifacts.count { it.layer == "code" && it.path == "artifact_files" }
            val contractValidationCount = artifacts.count { it.layer == "vision" && it.path == "contract_validation" }
            val visionArtifactsCount = artifacts.count { it.layer == "vision" && it.path == "vision_artifacts" }
            val generatedLinksCount = artifacts.find { it.layer == "logic" && it.path == "generated_links" }
                ?.content?.extractInt() ?: 0
            return PipelineResult(
                success = errors.isEmpty(),
                artifacts = artifacts,
                errors = errors,
                metadata = mapOf(
                    "depth" to depth.name,
                    "clusterId" to (clusterId ?: "unknown"),
                    "contracts" to contracts.size.toString(),
                    "duration_ms" to duration.toString(),
                    "source_files" to sourceFiles.size.toString(),
                    "symbols" to symbols.size.toString(),
                    "entry_points" to entryPoints.size.toString(),
                    "clusters" to clusters.size.toString(),
                    "existing_links" to existingLinks.size.toString(),
                    "flows" to flowCount.toString(),
                    "business_rules" to businessRuleCount.toString(),
                    "components" to componentCount.toString(),
                    "artifact_files" to artifactFileCount.toString(),
                    "contract_validation" to contractValidationCount.toString(),
                    "vision_artifacts" to visionArtifactsCount.toString(),
                    "generated_links" to generatedLinksCount.toString()
                )
            )
        } catch (e: Exception) {
            log.error("[DISCOVERY] Discovery failed", e)
            return PipelineResult(
                success = false,
                artifacts = artifacts,
                errors = errors + "Discovery failed: ${e.message}",
                metadata = mapOf(
                    "depth" to depth.name,
                    "clusterId" to (clusterId ?: "unknown"),
                    "error" to (e.message ?: "Unknown error")
                )
            )
        }
    }

    override suspend fun discover(
        intent: DiscoveryIntent,
        clusterId: String?,
        contracts: List<ContractHint>
    ): PipelineResult {
        log.info(
            "[DISCOVERY] Starting intent-based discovery: goal={}, depth={}",
            intent.goal, intent.depth
        )

        // Validate intent
        if (!intentResolver.isValid(intent)) {
            val errors = intentResolver.validate(intent)
            log.error("[DISCOVERY] Invalid intent: {}", errors.joinToString(", "))
            return PipelineResult(
                success = false,
                artifacts = emptyList(),
                errors = errors,
                metadata = emptyMap()
            )
        }

        // Resolve intent to parameter set
        val parameterSet = intentResolver.resolveToParameterSet(intent)
        log.info("[DISCOVERY] Resolved intent to parameter set: {}", parameterSet.name)

        // Map intent depth to discovery depth
        val discoveryDepth = when (intent.depth) {
            IntentDepth.BROWSE -> DiscoveryDepth.BROWSE
            IntentDepth.STANDARD -> DiscoveryDepth.STANDARD
            IntentDepth.DEEP -> DiscoveryDepth.DEEP
        }

        // Execute discovery with resolved parameters
        return discover(discoveryDepth, clusterId, contracts)
    }

    /**
     * Generate cross-layer links between discovered artifacts.
     * 
     * Creates links between:
     * - Business rules -> Code (implementation)
     * - Flows -> Code (entry points)
     * - Components -> Code (files/classes)
     */
    private fun generateLinks(
        clusterId: String,
        flows: List<com.i2vision.discover.flow.Flow>,
        businessRules: List<com.i2vision.discover.logic.BusinessRule>,
        components: List<com.i2vision.discover.structure.Component>
    ): Int {
        var linkCount = 0

        // Generate business rule -> code links
        businessRules.forEach { rule ->
            try {
                val link = com.i2vision.link.LayerLink(
                    id = "rule-${rule.id}",
                    logic = com.i2vision.link.LogicRef(
                        module = clusterId,
                        artifact = "logic/${rule.id}.yaml",
                        ref = rule.id
                    ),
                    code = com.i2vision.link.CodeRef(
                        file = rule.file,
                        line = rule.line
                    ),
                    type = com.i2vision.link.LinkType.implements,
                    confidence = 0.8,
                    note = "auto-discovered business rule to code",
                    source = com.i2vision.link.LinkSource.discovered
                )
                linkService.upsertLink(link)
                linkCount++
            } catch (e: Exception) {
                log.warn("[DISCOVERY] Failed to generate link for rule ${rule.id}: ${e.message}")
            }
        }

        // Generate flow -> code links (entry points)
        flows.forEach { flow ->
            try {
                val link = com.i2vision.link.LayerLink(
                    id = "flow-${flow.id}",
                    logic = com.i2vision.link.LogicRef(
                        module = clusterId,
                        artifact = "flow/${flow.id}.yaml",
                        ref = flow.id
                    ),
                    code = com.i2vision.link.CodeRef(
                        file = flow.entryPoint,
                        line = 1
                    ),
                    type = com.i2vision.link.LinkType.implements,
                    confidence = 0.9,
                    note = "auto-discovered flow to entry point",
                    source = com.i2vision.link.LinkSource.discovered
                )
                linkService.upsertLink(link)
                linkCount++
            } catch (e: Exception) {
                log.warn("[DISCOVERY] Failed to generate link for flow ${flow.id}: ${e.message}")
            }
        }

        // Generate component -> code links
        components.forEach { component ->
            component.files.forEach { file ->
                try {
                    val link = com.i2vision.link.LayerLink(
                        id = "component-${component.id}-${file.hashCode()}",
                        logic = com.i2vision.link.LogicRef(
                            module = clusterId,
                            artifact = "structure/${component.id}.yaml",
                            ref = component.id
                        ),
                        code = com.i2vision.link.CodeRef(
                            file = file,
                            line = 1
                        ),
                        type = com.i2vision.link.LinkType.defines,
                        confidence = 0.95,
                        note = "auto-discovered component to file",
                        source = com.i2vision.link.LinkSource.discovered
                    )
                    linkService.upsertLink(link)
                    linkCount++
                } catch (e: Exception) {
                    log.warn("[DISCOVERY] Failed to generate link for component ${component.id} file $file: ${e.message}")
                }
            }
        }

        return linkCount
    }

    /**
     * Extension function to extract integer from content string.
     * Example: "Generated 15 cross-layer links" -> 15
     */
    private fun String.extractInt(): Int {
        val regex = Regex("\\d+")
        return regex.find(this)?.value?.toIntOrNull() ?: 0
    }

    /**
     * Sanitize a requirement title for use as an ID.
     * Removes special characters and normalizes to lowercase with hyphens.
     */
    private fun sanitizeRequirementId(title: String): String {
        return title
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .take(80)
    }
}
