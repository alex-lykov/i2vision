/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.storage.api

import com.i2vision.storage.model.*

/**
 * Public API for VSLFC artifact operations.
 * Combines CacheStore functionality with layer-specific operations.
 */
interface ArtifactStore {

    /**
     * Store an artifact with metadata.
     */
    suspend fun putArtifact(ref: ArtifactRef, content: ByteArray, metadata: ArtifactMetadata): PutResult

    /**
     * Get an artifact with metadata.
     */
    suspend fun getArtifact(ref: ArtifactRef): Artifact?

    /**
     * Delete an artifact.
     */
    suspend fun deleteArtifact(ref: ArtifactRef): Boolean

    /**
     * List all artifacts for a module.
     */
    suspend fun listModuleArtifacts(module: String): List<ArtifactRef>

    /**
     * List all artifacts for a specific layer across all modules.
     */
    suspend fun listLayerArtifacts(layer: Layer): List<ArtifactRef>
}
