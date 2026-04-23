package com.i2vision.link

import com.i2vision.storage.I2VisionPaths
import org.slf4j.LoggerFactory
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml
import java.io.File

/**
 * Reads and writes semantic link snapshots under .semantic-cache/links.yaml.
 *
 * Design principles from the vision document:
 * - Links are EXPLICIT (no annotations in code)
 * - Code is OPAQUE — only paths and symbol names are stored
 * - Multi-level: directory | file | symbol | range
 */
class LinkService(private val projectRoot: String) {
    private val log = LoggerFactory.getLogger(LinkService::class.java)
    private val linksFile get() = File(I2VisionPaths.getProjectCacheDir(projectRoot), "links.yaml")
    private var cachedLinks: LinksFile? = null
    private var cachedLinksLastModified: Long = Long.MIN_VALUE
    private val aliasCache = mutableMapOf<String, Map<String, String>>()
    private val aliasCacheStamp = mutableMapOf<String, Long>()
    private val normalizedRefCache = mutableMapOf<String, String?>()
    
    // Batch mode for collecting links in memory during parallel discovery
    private val batchBuffer = mutableMapOf<String, LayerLink>()
    private var batchMode = false

    // ── Read ─────────────────────────────────────────────────────────────────

    fun load(): LinksFile {
        if (!linksFile.exists()) {
            cachedLinks = LinksFile()
            cachedLinksLastModified = Long.MIN_VALUE
            return LinksFile()
        }
        val lastModified = linksFile.lastModified()
        cachedLinks?.takeIf { cachedLinksLastModified == lastModified }?.let { return it }
        return try {
            val raw = Yaml().load<Map<String, Any>>(linksFile.readText()) ?: return LinksFile()
            parseLinksFile(raw).also {
                cachedLinks = it
                cachedLinksLastModified = lastModified
            }
        } catch (e: Exception) {
            log.warn("[LINK_SERVICE] Failed to parse {}: {}", linksFile.path, e.message)
            LinksFile()
        }
    }

    fun allLinks(): List<LayerLink> = load().links

    fun findByLogic(logicRef: String): List<LayerLink> =
        allLinks().filter { link ->
            val refString = "${link.logic.module}/${link.logic.artifact}:${link.logic.ref}"
            refString == logicRef || link.logic.ref == logicRef
        }

    @Suppress("unused")
    fun findByCodePath(path: String): List<LayerLink> =
        allLinks().filter { it.code.file == path }

    /**
     * Load only links relevant to a specific cluster for lazy loading optimization.
     * This reduces the overhead of loading all 4,782 links for every cluster.
     */
    fun getLinksForCluster(clusterId: String): List<LayerLink> {
        val clusterPath = clusterId.replace(":", "/")
        return allLinks().filter { link ->
            // Check if link references files in this cluster
            link.code.file.contains(clusterId) || link.code.file.contains(clusterPath) ||
            link.logic.module.contains(clusterId) || link.logic.module.contains(clusterPath)
        }
    }

    // ── Write ─────────────────────────────────────────────────────────────────

    /**
     * Enable batch mode - collects links in memory without writing to disk.
     * Use flushBatch() to write all buffered links at once.
     */
    fun enableBatchMode() {
        synchronized(batchBuffer) {
            batchMode = true
            batchBuffer.clear()
        }
    }

    /**
     * Disable batch mode and flush all buffered links to disk.
     */
    fun disableBatchMode() {
        flushBatch()
        synchronized(batchBuffer) {
            batchMode = false
            batchBuffer.clear()
        }
    }

    /**
     * Write all buffered links to disk in a single operation.
     */
    fun flushBatch() {
        synchronized(batchBuffer) {
            if (batchBuffer.isEmpty()) return
            
            val current = load()
            val merged = LinkedHashMap<String, LayerLink>()
            current.links.forEach { existing ->
                merged[identityKey(existing)] = existing
            }
            
            // Add all buffered links
            batchBuffer.values.forEach { link ->
                merged[identityKey(link)] = link
            }
            
            save(current.copy(links = merged.values.toList()))
            batchBuffer.clear()
        }
    }

    fun addLink(link: LayerLink): Result<Unit> {
        val current = load()
        if (current.links.any { identityKey(it) == identityKey(link) }) {
            log.debug("[LINK_SERVICE] Link already exists: {}", identityKey(link))
            return Result.success(Unit)
        }
        val updated = current.copy(links = current.links + link)
        return save(updated)
    }

    fun removeLink(id: String): Result<Unit> {
        val current = load()
        val updated = current.copy(links = current.links.filterNot { it.id == id })
        return save(updated)
    }

    fun upsertLink(link: LayerLink): Result<Unit> {
        return upsertLinks(listOf(link))
    }

    fun upsertLinks(links: List<LayerLink>): Result<Unit> {
        if (links.isEmpty()) return Result.success(Unit)
        
        // In batch mode, just add to buffer without writing
        if (batchMode) {
            synchronized(batchBuffer) {
                links.forEach { link ->
                    batchBuffer[identityKey(link)] = link
                }
            }
            return Result.success(Unit)
        }
        
        // Normal mode: load, merge, and save
        val current = load()
        val merged = LinkedHashMap<String, LayerLink>()
        current.links.forEach { existing ->
            merged[identityKey(existing)] = existing
        }

        var changed = false
        links.forEach { incoming ->
            val key = identityKey(incoming)
            val previous = merged[key]
            if (previous != incoming) changed = true
            merged[key] = incoming
        }

        if (!changed && merged.size == current.links.size) return Result.success(Unit)
        return save(current.copy(links = merged.values.toList()))
    }

    // ── Validation ────────────────────────────────────────────────────────────

    /** Checks each link's code target actually exists on disk. */
    fun validateLinks(): List<BrokenLinkReport> {
        return allLinks().mapNotNull { link ->
            val target = File(projectRoot, link.code.file)
            when {
                !target.exists() -> BrokenLinkReport(
                    link = link,
                    reason = "Code path does not exist: ${link.code.file}"
                )
                else -> null
            }
        }
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private fun save(file: LinksFile): Result<Unit> = runCatching {
        linksFile.parentFile?.mkdirs()
        aliasCache.clear()
        aliasCacheStamp.clear()
        normalizedRefCache.clear()
        val yaml = buildYaml()
        val data = mapOf(
            "version" to file.version,
            "links" to file.links.map { link ->
                mutableMapOf<String, Any>(
                    "id" to link.id,
                    "logic" to mapOf(
                        "module" to link.logic.module,
                        "artifact" to link.logic.artifact,
                        "ref" to link.logic.ref
                    ),
                    "code" to buildMap<String, Any> {
                        put("file", link.code.file)
                        link.code.line?.let { put("line", it) }
                        link.code.symbol?.let { put("symbol", it) }
                    },
                    "type" to link.type.name,
                    "confidence" to link.confidence,
                    "source" to link.source.name
                ).apply {
                    link.note?.let { put("note", it) }
                }
            }
        )
        linksFile.writeText(yaml.dump(data))
        cachedLinks = file
        cachedLinksLastModified = linksFile.lastModified()
        log.info("[LINK_SERVICE] Saved {} links to {}", file.links.size, linksFile.path)
    }.onFailure { log.error("[LINK_SERVICE] Save failed: {}", it.message) }

    @Suppress("UNCHECKED_CAST")
    private fun parseLinksFile(raw: Map<String, Any>): LinksFile {
        val links = (raw["links"] as? List<Map<String, Any>>)?.mapNotNull { entry ->
            try {
                val id = entry["id"] as? String ?: return@mapNotNull null
                val logicMap = entry["logic"] as? Map<String, Any> ?: return@mapNotNull null
                val codeMap = entry["code"] as? Map<String, Any> ?: return@mapNotNull null

                val logicRef = LogicRef(
                    module = logicMap["module"] as? String ?: return@mapNotNull null,
                    artifact = logicMap["artifact"] as? String ?: return@mapNotNull null,
                    ref = logicMap["ref"] as? String ?: return@mapNotNull null
                )

                val codeRef = CodeRef(
                    file = codeMap["file"] as? String ?: return@mapNotNull null,
                    line = codeMap["line"] as? Int,
                    symbol = codeMap["symbol"] as? String
                )

                val type = (entry["type"] as? String)
                    ?.let { runCatching { LinkType.valueOf(it) }.getOrDefault(LinkType.implements) }
                    ?: LinkType.implements

                val confidence = (entry["confidence"] as? Number)?.toDouble() ?: 1.0

                val source = (entry["source"] as? String)
                    ?.let { runCatching { LinkSource.valueOf(it) }.getOrNull() }
                    ?: LinkSource.manual

                LayerLink(
                    id = id,
                    logic = logicRef,
                    code = codeRef,
                    type = type,
                    confidence = confidence,
                    note = entry["note"] as? String,
                    source = source
                )
            } catch (_: Exception) { null }
        } ?: emptyList()
        return LinksFile(version = raw["version"]?.toString() ?: "1", links = links)
    }

    fun normalizeSemanticRef(ref: String?, layer: String = "logic"): String? {
        ref ?: return null
        val normalizedRef = ref.replace('\\', '/').trim()
        if (normalizedRef.isBlank()) return normalizedRef
        val cacheKey = "$layer|$normalizedRef"
        if (normalizedRefCache.containsKey(cacheKey)) return normalizedRefCache[cacheKey]
        val resolved = semanticAliasIndex(layer)[normalizedRef] ?: normalizedRef
        normalizedRefCache[cacheKey] = resolved
        return resolved
    }

    private fun identityKey(link: LayerLink): String =
        "${link.id}|${link.logic.module}:${link.logic.artifact}:${link.logic.ref}|${link.code.file}:${link.code.symbol ?: ""}"

    private fun semanticAliasIndex(layer: String): Map<String, String> {
        val artifactFiles = semanticArtifactFiles(layer)
        val stamp = artifactFiles.fold(17L) { acc, file ->
            (acc * 31L) xor file.lastModified() xor file.length()
        }
        aliasCache[layer]?.let { cached ->
            if (aliasCacheStamp[layer] == stamp) return cached
        }

        val aliases = mutableMapOf<String, String>()
        val ambiguous = mutableSetOf<String>()

        artifactFiles.forEach { file ->
            val raw = runCatching { Yaml().load<Any>(file.readText()) }.getOrNull() ?: return@forEach
            val artifactFile = (raw as? Map<*, *>)?.get("artifact_file")?.toString()?.trim().orEmpty()
            extractSemanticAliases(raw) { canonical, shortRef ->
                registerAlias(aliases, ambiguous, canonical, canonical)
                if (shortRef.isNotBlank()) {
                    registerAlias(aliases, ambiguous, shortRef, canonical)
                    if (artifactFile.isNotBlank()) registerAlias(aliases, ambiguous, "$artifactFile:$shortRef", canonical)
                }
            }
        }

        ambiguous.forEach { aliases.remove(it) }
        return aliases.also {
            aliasCache[layer] = it
            aliasCacheStamp[layer] = stamp
        }
    }

    private fun semanticArtifactFiles(layer: String): List<File> = buildList {
        val normalizedLayer = layer.lowercase()
        listOf(
            I2VisionPaths.getProjectCacheDir(projectRoot),
            File(projectRoot, "src/$normalizedLayer"),
            File(projectRoot, ".vision-ai/overrides/$normalizedLayer")
        ).filter { it.exists() }.forEach { root ->
            addAll(
                root.walkTopDown()
                    .filter { it.isFile && it.extension == "yaml" && it.path.replace('\\', '/').contains("/$normalizedLayer/") }
                    .toList()
            )
        }
    }

    private fun extractSemanticAliases(node: Any?, onAlias: (canonical: String, shortRef: String) -> Unit) {
        when (node) {
            is Map<*, *> -> {
                val canonical = node["id"]?.toString()?.trim().orEmpty()
                val shortRef = node["ref"]?.toString()?.trim().orEmpty()
                if (canonical.isNotBlank()) onAlias(canonical, shortRef)
                node.values.forEach { extractSemanticAliases(it, onAlias) }
            }
            is List<*> -> node.forEach { extractSemanticAliases(it, onAlias) }
        }
    }

    private fun registerAlias(target: MutableMap<String, String>, ambiguous: MutableSet<String>, alias: String, canonical: String) {
        val normalizedAlias = alias.replace('\\', '/').trim()
        if (normalizedAlias.isBlank()) return
        val existing = target[normalizedAlias]
        when {
            existing == null -> target[normalizedAlias] = canonical
            existing != canonical -> ambiguous += normalizedAlias
        }
    }

    private fun buildYaml(): Yaml {
        val opts = DumperOptions().apply {
            defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
            isPrettyFlow = true
        }
        return Yaml(opts)
    }
}

data class BrokenLinkReport(val link: LayerLink, val reason: String)
