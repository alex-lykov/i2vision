/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.storage.impl

import com.i2vision.storage.api.ArtifactStore
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

/**
 * File-based implementation of ArtifactStore.
 * INTERNAL - knows the physical storage layout.
 * 
 * Uses NIO file operations for thread-safe concurrent access on Windows.
 */
class FileArtifactStore(
    private val cacheDir: File
) : ArtifactStore {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun putArtifact(ref: ArtifactRef, content: ByteArray, metadata: ArtifactMetadata): PutResult {
        val relativePath = "${ref.module}/${ref.layer.name.lowercase()}/${ref.name}"

        // Write content using NIO for thread-safe file writing on Windows
        val file = File(cacheDir, relativePath)
        file.parentFile?.mkdirs()
        Files.write(file.toPath(), content)

        // Write metadata using NIO
        val metaFile = File(cacheDir, "$relativePath.meta")
        Files.writeString(metaFile.toPath(), json.encodeToString(metadata))

        return PutResult.Success(ref)
    }

    override suspend fun getArtifact(ref: ArtifactRef): Artifact? {
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
                createdAt = java.time.Instant.now().toEpochMilli(),
                sourceFiles = emptyList(),
                hash = computeHash(content)
            )
        }

        return Artifact(ref, content, metadata)
    }

    override suspend fun deleteArtifact(ref: ArtifactRef): Boolean {
        val relativePath = "${ref.module}/${ref.layer.name.lowercase()}/${ref.name}"
        val file = File(cacheDir, relativePath)
        val metaFile = File(cacheDir, "$relativePath.meta")

        val deleted = file.delete()
        if (metaFile.exists()) {
            metaFile.delete()
        }

        return deleted
    }

    override suspend fun listModuleArtifacts(module: String): List<ArtifactRef> {
        val artifacts = mutableListOf<ArtifactRef>()
        val moduleDir = File(cacheDir, module)

        if (moduleDir.exists()) {
            // Use NIO for thread-safe directory traversal on Windows
            Files.walk(moduleDir.toPath())
                .filter { path -> Files.isRegularFile(path) && isArtifactFile(path) }
                .forEach { path ->
                    val relativePath = path.toString().removePrefix(moduleDir.path + "/")
                    val parts = relativePath.split("/")
                    if (parts.size >= 2) {
                        val layer = try {
                            Layer.valueOf(parts[0].uppercase())
                        } catch (e: IllegalArgumentException) {
                            return@forEach
                        }
                        val name = parts[1]
                        artifacts.add(ArtifactRef(module, layer, name))
                    }
                }
        }

        return artifacts
    }

    override suspend fun listLayerArtifacts(layer: Layer): List<ArtifactRef> {
        val artifacts = mutableListOf<ArtifactRef>()

        if (cacheDir.exists()) {
            // Use NIO for thread-safe directory traversal on Windows
            Files.walk(cacheDir.toPath())
                .filter { path -> Files.isDirectory(path) && path.fileName.toString() == layer.name.lowercase() }
                .forEach { layerDir ->
                    val module = layerDir.parent?.fileName?.toString() ?: return@forEach
                    layerDir.toFile().listFiles()
                        ?.filter { isArtifactFile(it.toPath()) }
                        ?.forEach { file ->
                            artifacts.add(ArtifactRef(module, layer, file.name))
                        }
                }
        }

        return artifacts
    }

    private fun isArtifactFile(path: java.nio.file.Path): Boolean {
        val ext = path.fileName.toString().substringAfterLast('.', "")
        return ext in setOf("yaml", "json", "sd")
    }

    private fun computeHash(content: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(content).joinToString("") { "%02x".format(it) }
    }
}