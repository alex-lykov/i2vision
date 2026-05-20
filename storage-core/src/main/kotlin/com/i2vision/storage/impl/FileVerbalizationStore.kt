/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.storage.impl

import com.i2vision.storage.api.CacheStore
import com.i2vision.storage.model.ArtifactRef
import com.i2vision.storage.model.Layer
import com.i2vision.vslfc.PutResult
import com.i2vision.vslfc.Symbol
import com.i2vision.vslfc.VerbalizationResult
import com.i2vision.vslfc.VerbalizationStore
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * File-based implementation of VerbalizationStore.
 * Uses the underlying CacheStore but provides verbalization-specific operations.
 * 
 * ## Storage Structure:
 * - `verbalization/verbalizations.yaml`: Verbalization results
 * - `verbalization/.meta/hashes.yaml`: Local hashes (symbol body only)
 * - `verbalization/.meta/context-hashes.yaml`: Context hashes (dependencies included)
 */
class FileVerbalizationStore(
    private val cacheStore: CacheStore
) : VerbalizationStore {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun putVerbalizations(clusterId: String, results: List<VerbalizationResult>): PutResult {
        val artifactRef = ArtifactRef(
            module = clusterId,
            layer = Layer.CODE,
            name = "verbalization/verbalizations.yaml"
        )

        val content = json.encodeToString(results).toByteArray()
        return cacheStore.put(artifactRef, content)
    }

    override suspend fun getVerbalizations(clusterId: String): List<VerbalizationResult>? {
        val artifactRef = ArtifactRef(
            module = clusterId,
            layer = Layer.CODE,
            name = "verbalization/verbalizations.yaml"
        )

        val artifact = cacheStore.get(artifactRef)
        return artifact?.let {
            json.decodeFromString<List<VerbalizationResult>>(String(it.content))
        }
    }

    override suspend fun getVerbalization(symbol: Symbol): VerbalizationResult? {
        val clusterId = extractClusterId(symbol.filePath)
        val verbalizations = getVerbalizations(clusterId)
        return verbalizations?.find { it.symbol.name == symbol.name && it.symbol.filePath == symbol.filePath }
    }

    override suspend fun putHashes(clusterId: String, hashes: Map<String, String>): PutResult {
        val artifactRef = ArtifactRef(
            module = clusterId,
            layer = Layer.CODE,
            name = "verbalization/.meta/hashes.yaml"
        )

        val content = json.encodeToString(hashes).toByteArray()
        return cacheStore.put(artifactRef, content)
    }

    override suspend fun getHashes(clusterId: String): Map<String, String>? {
        val artifactRef = ArtifactRef(
            module = clusterId,
            layer = Layer.CODE,
            name = "verbalization/.meta/hashes.yaml"
        )

        val artifact = cacheStore.get(artifactRef)
        return artifact?.let {
            json.decodeFromString<Map<String, String>>(String(it.content))
        }
    }

    override suspend fun putContextHashes(clusterId: String, hashes: Map<String, String>): PutResult {
        val artifactRef = ArtifactRef(
            module = clusterId,
            layer = Layer.CODE,
            name = "verbalization/.meta/context-hashes.yaml"
        )

        val content = json.encodeToString(hashes).toByteArray()
        return cacheStore.put(artifactRef, content)
    }

    override suspend fun getContextHashes(clusterId: String): Map<String, String>? {
        val artifactRef = ArtifactRef(
            module = clusterId,
            layer = Layer.CODE,
            name = "verbalization/.meta/context-hashes.yaml"
        )

        val artifact = cacheStore.get(artifactRef)
        return artifact?.let {
            json.decodeFromString<Map<String, String>>(String(it.content))
        }
    }

    override suspend fun needsReverbalization(symbol: Symbol, currentHash: String): Boolean {
        val clusterId = extractClusterId(symbol.filePath)
        val hashes = getHashes(clusterId) ?: return true // No hashes means first time

        val symbolKey = getSymbolKey(symbol)
        val storedHash = hashes[symbolKey]

        return storedHash != currentHash
    }

    override suspend fun clearVerbalizations(clusterId: String): Int {
        return cacheStore.clear(clusterId)
    }

    /**
     * Extract cluster ID from file path.
     */
    private fun extractClusterId(filePath: String): String {
        // Simple extraction - in real implementation this would be more sophisticated
        val parts = filePath.split("/")
        return if (parts.size >= 2) "${parts[0]}/${parts[1]}" else "unknown"
    }

    /**
     * Generate unique key for a symbol.
     */
    private fun getSymbolKey(symbol: Symbol): String {
        return "${symbol.filePath}:${symbol.lineNumber}:${symbol.name}"
    }
}
