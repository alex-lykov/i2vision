package com.i2vision.discover.artifact

import com.i2vision.discover.flow.Flow
import com.i2vision.discover.logic.BusinessRule
import com.i2vision.discover.structure.Component
import com.i2vision.storage.api.CacheStore
import com.i2vision.storage.model.ArtifactRef
import com.i2vision.storage.model.Layer
import org.slf4j.LoggerFactory
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
        log.info("[ARTIFACT_WRITER] Writing {} flows, {} business rules, {} components", 
            flows.size, businessRules.size, components.size)
        
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
            log.error("[ARTIFACT_WRITER] Flow data: id={}, name={}, entryPoint={}, pattern={}, steps={}", 
                flow.id, flow.name, flow.entryPoint, flow.pattern, flow.steps.size)
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
            log.error("[ARTIFACT_WRITER] Rule data: id={}, name={}, type={}, condition={}, file={}, line={}", 
                rule.id, rule.name, rule.type, rule.condition, rule.file, rule.line)
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
            log.error("[ARTIFACT_WRITER] Component data: id={}, name={}, type={}, packageName={}, files={}, classes={}, functions={}, dependencies={}",
                component.id, component.name, component.type, component.packageName,
                component.files.size, component.classes.size, component.functions.size, component.dependencies.size)
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
    private suspend fun writeSummary(
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
}
