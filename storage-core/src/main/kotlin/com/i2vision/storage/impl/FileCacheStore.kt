/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.storage.impl

import com.i2vision.storage.api.CacheStore
import com.i2vision.storage.model.Artifact
import com.i2vision.storage.model.ArtifactMetadata
import com.i2vision.storage.model.ArtifactRef
import com.i2vision.storage.model.Layer
import com.i2vision.vslfc.PutResult
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * File-based implementation of CacheStore.
 * INTERNAL - knows the physical storage layout.
 * 
 * Uses NIO file operations for thread-safe concurrent access on Windows.
 */
class FileCacheStore(
    private val cacheDir: File
) : CacheStore {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun put(ref: ArtifactRef, content: ByteArray): PutResult {
        val relativePath = "${ref.module}/${ref.layer.name.lowercase()}/${ref.name}"
        val file = File(cacheDir, relativePath)
        file.parentFile?.mkdirs()
        
        // Use NIO for thread-safe file writing on Windows
        Files.write(file.toPath(), content)

        val hash = computeHash(content)
        val metadata = ArtifactMetadata(
            createdAt = Instant.now().toEpochMilli(),
            sourceFiles = emptyList(),
            hash = hash
        )

        // Store metadata using NIO
        val metaFile = File(cacheDir, "$relativePath.meta")
        Files.writeString(metaFile.toPath(), json.encodeToString(metadata))

        return PutResult.Success(ref)
    }

    override suspend fun get(ref: ArtifactRef): Artifact? {
        val relativePath = "${ref.module}/${ref.layer.name.lowercase()}/${ref.name}"
        val file = File(cacheDir, relativePath)
        if (!file.exists()) return null

        // Use NIO for thread-safe file reading on Windows
        val content = Files.readAllBytes(file.toPath())
        val metadataFile = File(cacheDir, "$relativePath.meta")
        val metadata = if (metadataFile.exists()) {
            json.decodeFromString(Files.readString(metadataFile.toPath()))
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
        val layerDir = File(cacheDir, "${module}/${layer.name.lowercase()}")
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

        // Use NIO for thread-safe directory traversal on Windows
        Files.walk(cacheDir.toPath())
            .filter { path -> path.toString().endsWith(".meta") && Files.isRegularFile(path) }
            .forEach { metaPath ->
                try {
                    val metadataText = Files.readString(metaPath)
                    val metadata = json.decodeFromString<ArtifactMetadata>(metadataText)
                    val hasChanged = metadata.sourceFiles.any { it in changedFiles }
                    if (hasChanged) {
                        val ref = pathToRef(metaPath.toString())
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
        val moduleDir = File(cacheDir, module)
        if (!moduleDir.exists()) return 0

        var count = 0
        moduleDir.deleteRecursively()
        count++
        return count
    }

    private fun pathToRef(path: String): ArtifactRef? {
        val relativePath = if (File(path).isAbsolute) {
            path.removePrefix("${cacheDir.absolutePath}/")
        } else {
            path
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