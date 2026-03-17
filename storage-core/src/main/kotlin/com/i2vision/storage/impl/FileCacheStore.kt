package com.i2vision.storage.impl

import com.i2vision.storage.api.*
import com.i2vision.storage.impl.internal.StorageLayout
import com.i2vision.storage.model.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * File-based implementation of CacheStore.
 * INTERNAL - knows the physical storage layout.
 */
class FileCacheStore(
    private val projectRoot: File
) : CacheStore {
    
    private val json = Json { ignoreUnknownKeys = true }
    
    override suspend fun put(ref: ArtifactRef, content: ByteArray): PutResult {
        val relativePath = StorageLayout.artifactPath(
            module = ref.module,
            layer = ref.layer.name.lowercase(),
            name = ref.name
        )
        val file = if (File(relativePath).isAbsolute) File(relativePath) else File(projectRoot, relativePath)
        file.parentFile?.mkdirs()
        file.writeBytes(content)
        
        val hash = computeHash(content)
        val metadata = ArtifactMetadata(
            createdAt = Instant.now().toEpochMilli(),
            sourceFiles = emptyList(),
            hash = hash
        )
        
        // Store metadata
        val metaPath = StorageLayout.metadataPath(relativePath)
        val metaFile = if (File(metaPath).isAbsolute) File(metaPath) else File(projectRoot, metaPath)
        metaFile.writeText(
            json.encodeToString(metadata)
        )
        
        return PutResult.Success(ref)
    }
    
    override suspend fun get(ref: ArtifactRef): Artifact? {
        val relativePath = StorageLayout.artifactPath(
            module = ref.module,
            layer = ref.layer.name.lowercase(),
            name = ref.name
        )
        val file = if (File(relativePath).isAbsolute) File(relativePath) else File(projectRoot, relativePath)
        if (!file.exists()) return null
        
        val content = file.readBytes()
        val metaPath = StorageLayout.metadataPath(relativePath)
        val metadataFile = if (File(metaPath).isAbsolute) File(metaPath) else File(projectRoot, metaPath)
        val metadata = if (metadataFile.exists()) {
            json.decodeFromString<ArtifactMetadata>(metadataFile.readText())
        } else {
            ArtifactMetadata(
                createdAt = Instant.now().toEpochMilli(),
                sourceFiles = emptyList(),
                hash = computeHash(content)
            )
        }
        
        return Artifact(ref, content, metadata)
    }
    
    override suspend fun list(module: String, layer: Layer): List<ArtifactRef> {
        val layerDirPath = StorageLayout.layerDir(module, layer.name.lowercase())
        val layerDir = if (File(layerDirPath).isAbsolute) File(layerDirPath) else File(projectRoot, layerDirPath)
        if (!layerDir.exists()) return emptyList()
        
        return layerDir.listFiles()
            ?.filter { it.isFile && it.extension in setOf("yaml", "json", "sd") }
            ?.map { ArtifactRef(module, layer, it.name) }
            ?: emptyList()
    }
    
    override suspend fun isFresh(ref: ArtifactRef, maxAge: Duration): Boolean {
        val artifact = get(ref) ?: return false
        val age = Instant.now().toEpochMilli() - artifact.metadata.createdAt
        return age.milliseconds <= maxAge
    }
    
    override suspend fun getAffected(changedFiles: List<String>): List<ArtifactRef> {
        val affected = mutableListOf<ArtifactRef>()
        val cacheRoot = if (File(StorageLayout.SEMANTIC_CACHE).isAbsolute) File(StorageLayout.SEMANTIC_CACHE) else File(projectRoot, StorageLayout.SEMANTIC_CACHE)
        
        cacheRoot.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".meta") }
            .forEach { metaFile ->
                try {
                    val metadata = json.decodeFromString<ArtifactMetadata>(metaFile.readText())
                    val hasChanged = metadata.sourceFiles.any { it in changedFiles }
                    if (hasChanged) {
                        val ref = pathToRef(metaFile.path.removeSuffix(".meta"))
                        if (ref != null) {
                            affected.add(ref)
                        }
                    }
                } catch (e: Exception) {
                    // Skip corrupted metadata files
                }
            }
        
        return affected
    }
    
    override suspend fun clear(module: String): Int {
        val moduleDirPath = StorageLayout.moduleCacheDir(module)
        val moduleDir = if (File(moduleDirPath).isAbsolute) File(moduleDirPath) else File(projectRoot, moduleDirPath)
        if (!moduleDir.exists()) return 0
        
        var count = 0
        moduleDir.deleteRecursively()
        count++
        return count
    }
    
    private fun pathToRef(path: String): ArtifactRef? {
        // Handle absolute paths from user home directory
        val relativePath = if (File(path).isAbsolute) {
            path.removePrefix("${StorageLayout.SEMANTIC_CACHE}/")
        } else {
            path.removePrefix("${projectRoot.absolutePath}/").removePrefix("${StorageLayout.SEMANTIC_CACHE}/")
        }
        val parts = relativePath.split('/')
        if (parts.size < 3) return null
        
        val module = parts[0]
        val layer = try {
            Layer.valueOf(parts[1].uppercase())
        } catch (e: IllegalArgumentException) {
            return null
        }
        val name = parts[2]
        
        return ArtifactRef(module, layer, name)
    }
    
    private fun computeHash(content: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(content).joinToString("") { "%02x".format(it) }
    }
}
