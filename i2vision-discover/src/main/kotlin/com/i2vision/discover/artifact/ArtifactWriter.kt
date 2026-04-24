/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.discover.artifact

import com.i2vision.discover.flow.Flow
import com.i2vision.discover.logic.BusinessRule
import com.i2vision.discover.pipeline.VisionConstraint
import com.i2vision.discover.pipeline.VisionRequirement
import com.i2vision.discover.structure.Component
import com.i2vision.storage.api.CacheStore
import com.i2vision.storage.model.ArtifactRef
import com.i2vision.storage.model.Layer
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml
import java.util.concurrent.ConcurrentHashMap

/**
 * Artifact writer for writing discovered artifacts to semantic cache.
 * 
 * Writes flows, business rules, and components as YAML files using storage-core API.
 * 
 * Thread-safety: Uses cluster-level locking to prevent concurrent write corruption
 * when multiple clusters write artifacts simultaneously.
 */
class ArtifactWriter(
    private val projectRoot: String,
    private val cacheStore: CacheStore
) {

    private val log = LoggerFactory.getLogger(ArtifactWriter::class.java)
    private val yaml = Yaml(DumperOptions().apply {
        defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
        indicatorIndent = 2
        indent = 4
    })

    // Cluster-level locks to prevent concurrent writes to the same cluster's artifacts
    private val clusterLocks = ConcurrentHashMap<String, Mutex>()

    private fun getClusterLock(clusterId: String?): Mutex {
        val key = clusterId ?: "default"
        return clusterLocks.computeIfAbsent(key) { Mutex() }
    }

    /**
     * Write all discovered artifacts to the semantic cache.
     * 
     * @param clusterId Optional cluster ID (used as module name)
     * @param flows List of discovered flows
     * @param businessRules List of discovered business rules
     * @param components List of discovered components
     * @return List of written artifact references
     */
    suspend fun writeArtifacts(
        clusterId: String? = null,
        flows: List<Flow>,
        businessRules: List<BusinessRule>,
        components: List<Component>
    ): List<ArtifactRef> {
        log.info(
            "[ARTIFACT_WRITER] Writing {} flows, {} business rules, {} components",
            flows.size, businessRules.size, components.size
        )

        val lock = getClusterLock(clusterId)
        val writtenArtifacts = mutableListOf<ArtifactRef>()

        return lock.withLock {
            // Write flows
            flows.forEach { flow ->
                writtenArtifacts.add(writeFlow(clusterId ?: "unknown", flow))
            }

            // Write business rules
            businessRules.forEach { rule ->
                writtenArtifacts.add(writeBusinessRule(clusterId ?: "unknown", rule))
            }

            // Write components
            components.forEach { component ->
                writtenArtifacts.add(writeComponent(clusterId ?: "unknown", component))
            }

            log.info("[ARTIFACT_WRITER] Wrote {} artifact files", writtenArtifacts.size)
            writtenArtifacts
        }
    }

    /**
     * Write a flow to the semantic cache using CacheStore.
     * 
     * @param moduleName Module name (cluster ID)
     * @param flow The flow to write
     * @return Artifact reference
     */
    private suspend fun writeFlow(moduleName: String, flow: Flow): ArtifactRef {
        val ref = ArtifactRef(
            module = moduleName,
            layer = Layer.FLOW,
            name = "${flow.id}.yaml"
        )

        // Create immutable copy to prevent ConcurrentModificationException during YAML serialization
        val flowData = mapOf(
            "id" to flow.id,
            "name" to flow.name,
            "entryPoint" to flow.entryPoint,
            "pattern" to (flow.pattern ?: ""),
            "steps" to flow.steps.map { step ->
                mapOf(
                    "symbolName" to step.symbolName,
                    "qualifiedName" to step.qualifiedName,
                    "file" to step.file,
                    "line" to step.line
                )
            }
        )

        // Debug: Check for any null values in the data structure
        fun checkForNulls(data: Any?, path: String = ""): Boolean {
            return when (data) {
                null -> {
                    log.error("[ARTIFACT_WRITER] Found null value at path: $path")
                    true
                }

                is Map<*, *> -> {
                    data.entries.any { (k, v) -> checkForNulls(v, "$path.$k") }
                }

                is List<*> -> {
                    data.withIndex().any { (i, item) -> checkForNulls(item, "$path[$i]") }
                }

                else -> false
            }
        }

        if (checkForNulls(flowData)) {
            log.error("[ARTIFACT_WRITER] Flow data contains null values, skipping serialization")
            return ref
        }

        try {
            cacheStore.put(ref, yaml.dump(flowData).toByteArray())
            log.debug("[ARTIFACT_WRITER] Wrote flow: {}", ref.name)
        } catch (e: Exception) {
            log.error("[ARTIFACT_WRITER] Failed to write flow: {}", ref.name, e)
            log.error(
                "[ARTIFACT_WRITER] Flow data: id={}, name={}, entryPoint={}, pattern={}, steps={}",
                flow.id, flow.name, flow.entryPoint, flow.pattern, flow.steps.size
            )
            // Don't rethrow - continue with other artifacts
        }

        return ref
    }

    /**
     * Write a business rule to the semantic cache using CacheStore.
     * 
     * @param moduleName Module name (cluster ID)
     * @param rule The business rule to write
     * @return Artifact reference
     */
    private suspend fun writeBusinessRule(moduleName: String, rule: BusinessRule): ArtifactRef {
        val ref = ArtifactRef(
            module = moduleName,
            layer = Layer.LOGIC,
            name = "${rule.id}.yaml"
        )

        val ruleData = mutableMapOf<String, Any>()
        ruleData["id"] = rule.id
        ruleData["name"] = rule.name
        ruleData["type"] = rule.type
        ruleData["condition"] = rule.condition
        ruleData["file"] = rule.file
        ruleData["line"] = rule.line

        try {
            cacheStore.put(ref, yaml.dump(ruleData).toByteArray())
            log.debug("[ARTIFACT_WRITER] Wrote business rule: {}", ref.name)
        } catch (e: Exception) {
            log.error("[ARTIFACT_WRITER] Failed to write business rule: {}", ref.name, e)
            log.error(
                "[ARTIFACT_WRITER] Rule data: id={}, name={}, type={}, condition={}, file={}, line={}",
                rule.id, rule.name, rule.type, rule.condition, rule.file, rule.line
            )
            throw e
        }

        return ref
    }

    /**
     * Write a component to the semantic cache using CacheStore.
     * 
     * @param moduleName Module name (cluster ID)
     * @param component The component to write
     * @return Artifact reference
     */
    private suspend fun writeComponent(moduleName: String, component: Component): ArtifactRef {
        val ref = ArtifactRef(
            module = moduleName,
            layer = Layer.STRUCTURE,
            name = "${component.id}.yaml"
        )

        val componentData = mutableMapOf<String, Any>()
        componentData["id"] = component.id
        componentData["name"] = component.name
        componentData["type"] = component.type
        componentData["packageName"] = component.packageName
        componentData["files"] = component.files
        componentData["classes"] = component.classes
        componentData["functions"] = component.functions
        componentData["dependencies"] = component.dependencies

        try {
            cacheStore.put(ref, yaml.dump(componentData).toByteArray())
            log.debug("[ARTIFACT_WRITER] Wrote component: {}", ref.name)
        } catch (e: Exception) {
            log.error("[ARTIFACT_WRITER] Failed to write component: {}", ref.name, e)
            log.error(
                "[ARTIFACT_WRITER] Component data: id={}, name={}, type={}, packageName={}, files={}, classes={}, functions={}, dependencies={}",
                component.id, component.name, component.type, component.packageName,
                component.files.size, component.classes.size, component.functions.size, component.dependencies.size
            )
            throw e
        }

        return ref
    }

    /**
     * Write a discovery summary to the semantic cache using CacheStore.
     * 
     * @param moduleName Module name (cluster ID)
     * @param flows List of flows
     * @param businessRules List of business rules
     * @param components List of components
     * @return Artifact reference
     */
    suspend fun writeSummary(
        moduleName: String,
        flows: List<Flow>,
        businessRules: List<BusinessRule>,
        components: List<Component>
    ): ArtifactRef {
        val ref = ArtifactRef(
            module = moduleName,
            layer = Layer.CODE,
            name = "discovery-summary.yaml"
        )

        val summaryData = mapOf(
            "timestamp" to System.currentTimeMillis(),
            "flows" to flows.size,
            "businessRules" to businessRules.size,
            "components" to components.size,
            "flowIds" to flows.map { it.id },
            "ruleIds" to businessRules.map { it.id },
            "componentIds" to components.map { it.id }
        )

        cacheStore.put(ref, yaml.dump(summaryData).toByteArray())
        log.debug("[ARTIFACT_WRITER] Wrote discovery summary: {}", ref.name)

        return ref
    }

    /**
     * Write Vision layer artifacts to the semantic cache using CacheStore.
     * 
     * @param moduleName Module name (cluster ID)
     * @param requirements List of vision requirements
     * @param constraints List of vision constraints
     * @return List of written artifact references
     */
    suspend fun writeVisionArtifacts(
        moduleName: String,
        requirements: List<VisionRequirement>,
        constraints: List<VisionConstraint>
    ): List<ArtifactRef> {
        log.info(
            "[ARTIFACT_WRITER] Writing {} requirements, {} constraints to vision layer",
            requirements.size, constraints.size
        )

        val writtenArtifacts = mutableListOf<ArtifactRef>()

        // Write requirements
        requirements.forEach { requirement ->
            val safeId = sanitizeFilename(requirement.id)
            val ref = ArtifactRef(
                module = moduleName,
                layer = Layer.VISION,
                name = "${safeId}.yaml"
            )

            val requirementData = mutableMapOf<String, Any>()
            requirement.id?.let { requirementData["id"] = it }
            requirement.title?.let { requirementData["title"] = it }
            requirement.docRef?.let { requirementData["docRef"] = it }
            requirement.rationaleRef?.let { requirementData["rationaleRef"] = it }
            requirement.acceptanceCriteriaRefs?.let { requirementData["acceptanceCriteriaRefs"] = it }
            requirement.priority?.let { requirementData["priority"] = it.name }
            requirement.source?.let { requirementData["source"] = it.name }
            requirement.confidence?.let { requirementData["confidence"] = it }
            requirement.evidence?.let { evidence ->
                val filteredEvidence = evidence.mapNotNull { ev ->
                    if (ev == null) return@mapNotNull null
                    mapOf(
                        "file" to (ev.file ?: ""),
                        "line" to (ev.line ?: 0),
                        "pattern" to (ev.pattern ?: ""),
                        "description" to (ev.description ?: "")
                    )
                }
                if (filteredEvidence.isNotEmpty()) {
                    requirementData["evidence"] = filteredEvidence
                }
            }

            try {
                // Filter out null values and empty collections before YAML serialization
                val filteredData = requirementData.filterValues { it != null && it != "" && it != 0 }
                // Also filter out empty lists
                val cleanedData = filteredData.filterValues { 
                    when (it) {
                        is List<*> -> it.isNotEmpty()
                        is Map<*, *> -> it.isNotEmpty()
                        else -> true
                    }
                }
                if (cleanedData.isNotEmpty()) {
                    // Recursively clean nested structures to remove any nulls
                    val deepCleaned = cleanNestedData(cleanedData)
                    
                    // Check if deep cleaning resulted in empty structure
                    val hasValidData = when (deepCleaned) {
                        is Map<*, *> -> deepCleaned.isNotEmpty()
                        is List<*> -> deepCleaned.isNotEmpty()
                        else -> true
                    }
                    
                    if (hasValidData) {
                        val yamlString = yaml.dump(deepCleaned)
                        cacheStore.put(ref, yamlString.toByteArray())
                        log.debug("[ARTIFACT_WRITER] Wrote vision requirement: {}", ref.name)
                        writtenArtifacts.add(ref)
                    } else {
                        log.warn("[ARTIFACT_WRITER] Skipping vision requirement with no data after cleaning: {}", ref.name)
                    }
                } else {
                    log.warn("[ARTIFACT_WRITER] Skipping vision requirement with no data: {}", ref.name)
                }
            } catch (e: Exception) {
                log.error("[ARTIFACT_WRITER] Failed to write vision requirement: {}", ref.name, e)
            }
        }

        // Write constraints
        constraints.forEach { constraint ->
            val safeId = sanitizeFilename(constraint.id)
            val ref = ArtifactRef(
                module = moduleName,
                layer = Layer.VISION,
                name = "${safeId}.yaml"
            )

            val constraintData = mutableMapOf<String, Any>()
            constraint.id?.let { constraintData["id"] = it }
            constraint.docRef?.let { constraintData["docRef"] = it }
            constraint.type?.let { constraintData["type"] = it.name }
            constraint.severity?.let { constraintData["severity"] = it.name }
            constraint.confidence?.let { constraintData["confidence"] = it }
            constraint.source?.let { constraintData["source"] = it.name }
            constraint.evidence?.let { evidence ->
                val filteredEvidence = evidence.mapNotNull { ev ->
                    if (ev == null) return@mapNotNull null
                    mapOf(
                        "file" to (ev.file ?: ""),
                        "line" to (ev.line ?: 0),
                        "pattern" to (ev.pattern ?: ""),
                        "description" to (ev.description ?: "")
                    )
                }
                if (filteredEvidence.isNotEmpty()) {
                    constraintData["evidence"] = filteredEvidence
                }
            }

            try {
                if (constraintData.isNotEmpty()) {
                    // Recursively clean nested structures to remove any nulls
                    val deepCleaned = cleanNestedData(constraintData)
                    val yamlString = yaml.dump(deepCleaned)
                    cacheStore.put(ref, yamlString.toByteArray())
                    log.debug("[ARTIFACT_WRITER] Wrote vision constraint: {}", ref.name)
                    writtenArtifacts.add(ref)
                } else {
                    log.warn("[ARTIFACT_WRITER] Skipping vision constraint with no data: {}", ref.name)
                }
            } catch (e: Exception) {
                log.error("[ARTIFACT_WRITER] Failed to write vision constraint: {}", ref.name, e)
            }
        }

        log.info("[ARTIFACT_WRITER] Wrote {} vision artifact files", writtenArtifacts.size)
        return writtenArtifacts
    }

    /**
     * Sanitize a string for use as a filename.
     * Removes or replaces characters that are invalid in filesystem paths.
     */
    private fun sanitizeFilename(name: String): String {
        return name
            .replace("\"", "")           // Remove double quotes
            .replace("'", "")            // Remove single quotes
            .replace(",", "")            // Remove commas
            .replace(":", "-")           // Replace colons with hyphens
            .replace("/", "-")           // Replace slashes with hyphens
            .replace("\\", "-")          // Replace backslashes with hyphens
            .replace("*", "")            // Remove asterisks
            .replace("?", "")            // Remove question marks
            .replace("<", "")            // Remove less than
            .replace(">", "")            // Remove greater than
            .replace("|", "")            // Remove pipe
            .replace(Regex("\\s+"), "-") // Replace whitespace with hyphens
            .replace(Regex("-+"), "-")   // Collapse multiple hyphens
            .trim('-')                   // Remove leading/trailing hyphens
            .lowercase()                 // Lowercase for consistency
            .take(100)                   // Limit filename length
    }

    /**
     * Recursively clean nested data structures to remove null values.
     * This prevents SnakeYAML from encountering null values during serialization.
     */
    @Suppress("UNCHECKED_CAST")
    private fun cleanNestedData(data: Any): Any {
        return when (data) {
            is Map<*, *> -> {
                data.entries
                    .filter { it.key != null && it.value != null }
                    .associate { (key, value) ->
                        key as String to cleanNestedData(value!!)
                    }
            }
            is List<*> -> {
                data
                    .filterNotNull()
                    .map { cleanNestedData(it!!) }
            }
            else -> data
        }
    }
}
