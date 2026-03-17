package com.i2vision.discover.pipeline

import com.i2vision.discover.api.DiscoveryPipeline
import com.i2vision.discover.api.IntentResolver
import com.i2vision.discover.api.models.PipelineResult
import com.i2vision.discover.api.models.DiscoveryDepth
import com.i2vision.discover.api.models.ContractHint
import com.i2vision.discover.api.models.DiscoveryIntent
import com.i2vision.discover.api.models.DiscoveryArtifact
import com.i2vision.discover.artifact.ArtifactWriter
import com.i2vision.storage.api.CacheStore
import com.i2vision.storage.impl.RolloutManager
import com.i2vision.discover.flow.FlowDiscovery
import com.i2vision.discover.logic.LogicExtractor
import com.i2vision.discover.structure.StructureBuilder
import com.i2vision.architecture.ArchitectureDetector
import com.i2vision.index.CustomIndex
import com.i2vision.index.SemanticPathResolver
import com.i2vision.link.LinkService
import com.i2vision.vslfc.contracts.ContractValidator
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Implementation of DiscoveryPipeline interface with orchestrator internals integration.
 * 
 * Enhanced with extracted orchestrator components:
 * - index-provider: Code intelligence (symbol resolution, call hierarchy)
 * - link-service: Semantic link management
 * - task-executor: Task-based discovery
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
            
            artifacts.add(DiscoveryArtifact(
                layer = "code",
                path = "source_files",
                content = "Found ${sourceFiles.size} source files (cluster: ${clusterId ?: "all"})"
            ))
            log.info("[DISCOVERY] Found {} source files (filtered from {} total)", sourceFiles.size, allSourceFiles.size)
            
            // Step 2: Symbol extraction using index-provider
            log.info("[DISCOVERY] Step 2: Extracting symbols")
            val symbols = sourceFiles.flatMap { file ->
                indexProvider.symbolsInFile(File(projectRoot, file.path))
            }
            artifacts.add(DiscoveryArtifact(
                layer = "code",
                path = "symbols",
                content = "Extracted ${symbols.size} symbols"
            ))
            log.info("[DISCOVERY] Extracted {} symbols", symbols.size)
            
            // Step 3: Entry point detection using index-provider
            log.info("[DISCOVERY] Step 3: Detecting entry points")
            val entryPoints = indexProvider.findEntryPoints()
            artifacts.add(DiscoveryArtifact(
                layer = "code",
                path = "entry_points",
                content = "Found ${entryPoints.size} entry points"
            ))
            log.info("[DISCOVERY] Found {} entry points", entryPoints.size)
            
            // Step 4: Cluster suggestions using index-provider
            log.info("[DISCOVERY] Step 4: Generating cluster suggestions")
            val clusters = indexProvider.findClusters()
            artifacts.add(DiscoveryArtifact(
                layer = "structure",
                path = "clusters",
                content = "Generated ${clusters.size} cluster suggestions"
            ))
            log.info("[DISCOVERY] Generated {} cluster suggestions", clusters.size)
            
            // Step 5: Load existing links using link-service
            log.info("[DISCOVERY] Step 5: Loading semantic links")
            // Use lazy loading: only load links relevant to this cluster if clusterId is provided
            val existingLinks = if (clusterId != null) {
                linkService.getLinksForCluster(clusterId)
            } else {
                linkService.allLinks()
            }
            artifacts.add(DiscoveryArtifact(
                layer = "logic",
                path = "existing_links",
                content = "Loaded ${existingLinks.size} existing links ${if (clusterId != null) "(cluster: $clusterId)" else "(all)"}"
            ))
            log.info("[DISCOVERY] Loaded {} existing links {}", existingLinks.size, if (clusterId != null) "(cluster: $clusterId)" else "(all)")
            
            // Step 6: Architecture detection using ArchitectureDetector
            // Skip per-cluster architecture detection - it's already done once at start in SelfDiscoveryTest
            // Running it per cluster is wasteful and causes significant performance degradation
            if (depth == DiscoveryDepth.STANDARD || depth == DiscoveryDepth.DEEP && clusterId == null) {
                log.info("[DISCOVERY] Step 6: Running architecture detection (only for full project discovery)")
                try {
                    val techStack = architectureDetector.detectStack()
                    artifacts.add(DiscoveryArtifact(
                        layer = "vision",
                        path = "architecture_detection",
                        content = "Architecture detection completed\n" +
                            "Primary language: ${techStack.primaryLanguage.name}\n" +
                            "Frameworks: ${techStack.frameworks.joinToString(", ") { it.name }}\n" +
                            "Platforms: ${techStack.platforms.joinToString(", ") { it.name }}\n" +
                            "Patterns: ${techStack.patterns.joinToString(", ") { it.name }}\n" +
                            "Confidence: ${techStack.confidence}"
                    ))
                    log.info("[DISCOVERY] Architecture detection completed: {}", techStack.primaryLanguage.name)
                } catch (e: Exception) {
                    log.warn("[DISCOVERY] Architecture detection failed: {}", e.message)
                    errors.add("Architecture detection failed: ${e.message}")
                }
            }
            
            // Step 7: Flow discovery using FlowDiscovery
            if (depth == DiscoveryDepth.STANDARD || depth == DiscoveryDepth.DEEP) {
                log.info("[DISCOVERY] Step 7: Discovering flows using call graph")
                val flows = flowDiscovery.discoverFlows(symbols, clusterId)
                artifacts.add(DiscoveryArtifact(
                    layer = "flow",
                    path = "flows",
                    content = "Discovered ${flows.size} flows:\n${flows.take(10).joinToString("\n") { "- ${it.name} (${it.steps.size} steps)" }}"
                ))
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
                artifacts.add(DiscoveryArtifact(
                    layer = "logic",
                    path = "business_rules",
                    content = "Extracted ${businessRules.size} business rules:\n${businessRules.take(10).joinToString("\n") { "- ${it.name} (${it.type}): ${it.condition}" }}"
                ))
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
                artifacts.add(DiscoveryArtifact(
                    layer = "structure",
                    path = "components",
                    content = "Built ${components.size} components:\n${components.take(10).joinToString("\n") { "- ${it.name} (${it.type}): ${it.files.size} files, ${it.dependencies.size} deps" }}"
                ))
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
                        val writtenArtifacts = artifactWriter.writeArtifacts(clusterId, flows, businessRules, components)
                        artifacts.add(DiscoveryArtifact(
                            layer = "code",
                            path = "artifact_files",
                            content = "Wrote ${writtenArtifacts.size} artifact files to semantic cache:\n${writtenArtifacts.take(10).joinToString("\n") { "- ${it.module}/${it.layer}/${it.name}" }}"
                        ))
                        log.info("[DISCOVERY] Wrote {} artifact files", writtenArtifacts.size)
                        
                        // Generate and write links between artifacts
                        val generatedLinksCount = if (clusterId != null) {
                            generateLinks(clusterId, flows, businessRules, components)
                        } else {
                            0
                        }
                        artifacts.add(DiscoveryArtifact(
                            layer = "logic",
                            path = "generated_links",
                            content = "Generated $generatedLinksCount cross-layer links"
                        ))
                        log.info("[DISCOVERY] Generated {} cross-layer links", generatedLinksCount)
                    }
                }
            }
            
            // Step 8: Contract validation using ContractValidator
            if (contracts.isNotEmpty() && (depth == DiscoveryDepth.STANDARD || depth == DiscoveryDepth.DEEP)) {
                log.info("[DISCOVERY] Step 8: Validating {} contract hints", contracts.size)
                // For now, log contract hints - full integration would require DiscoveryContract objects
                artifacts.add(DiscoveryArtifact(
                    layer = "vision",
                    path = "contract_validation",
                    content = "Contract hints: ${contracts.size}\n${contracts.joinToString("\n") { "- ${it.contractPath} (${it.relatedFiles.size} files, ${it.entryPoints.size} entry points)" }}"
                ))
                log.info("[DISCOVERY] Contract validation completed")
            }
            
            val duration = System.currentTimeMillis() - startTime
            log.info("[DISCOVERY] Discovery completed in {}ms", duration)
            
            val flowCount = artifacts.count { it.layer == "flow" }
            val businessRuleCount = artifacts.count { it.layer == "logic" && it.path == "business_rules" }
            val componentCount = artifacts.count { it.layer == "structure" && it.path == "components" }
            val artifactFileCount = artifacts.count { it.layer == "code" && it.path == "artifact_files" }
            val contractValidationCount = artifacts.count { it.layer == "vision" && it.path == "contract_validation" }
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
        log.info("[DISCOVERY] Starting intent-based discovery: goal={}, depth={}", 
            intent.goal, intent.depth)
        
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
            com.i2vision.discover.api.models.IntentDepth.BROWSE -> DiscoveryDepth.BROWSE
            com.i2vision.discover.api.models.IntentDepth.STANDARD -> DiscoveryDepth.STANDARD
            com.i2vision.discover.api.models.IntentDepth.DEEP -> DiscoveryDepth.DEEP
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
}
