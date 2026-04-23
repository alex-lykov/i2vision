/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.storage.impl

import com.i2vision.storage.api.ArtifactStore
import com.i2vision.storage.model.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * File-based implementation of ArtifactStore.
 * INTERNAL - knows the physical storage layout.
 */
class FileArtifactStore(
    private val cacheDir: File
) : ArtifactStore {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun putArtifact(ref: ArtifactRef, content: ByteArray, metadata: ArtifactMetadata): PutResult {
        val relativePath = "${ref.module}/${ref.layer.name.lowercase()}/${ref.name}"

        // Write content
        val file = File(cacheDir, relativePath)
        file.parentFile?.mkdirs()
        file.writeBytes(content)

        // Write metadata
        val metaFile = File(cacheDir, "$relativePath.meta")
        metaFile.writeText(json.encodeToString(metadata))

        return PutResult.Success(ref)
    }

    override suspend fun getArtifact(ref: ArtifactRef): Artifact? {
        val relativePath = "${ref.module}/${ref.layer.name.lowercase()}/${ref.name}"
        val file = File(cacheDir, relativePath)
        if (!file.exists()) return null

        val content = file.readBytes()
        val metadataFile = File(cacheDir, "$relativePath.meta")
        val metadata = if (metadataFile.exists()) {
            json.decodeFromString<ArtifactMetadata>(metadataFile.readText())
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
            moduleDir.walkTopDown()
                .filter { it.isFile && it.extension in setOf("yaml", "json", "sd") }
                .forEach { file ->
                    val relativePath = file.path.removePrefix(moduleDir.path + "/")
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
            cacheDir.walkTopDown()
                .filter { it.isDirectory }
                .filter { it.name == layer.name.lowercase() }
                .forEach { layerDir ->
                    val module = layerDir.parentFile?.name ?: return@forEach
                    layerDir.listFiles()
                        ?.filter { it.isFile && it.extension in setOf("yaml", "json", "sd") }
                        ?.forEach { file ->
                            artifacts.add(ArtifactRef(module, layer, file.name))
                        }
                }
        }

        return artifacts
    }

    private fun computeHash(content: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest(content).joinToString("") { "%02x".format(it) }
    }
}
