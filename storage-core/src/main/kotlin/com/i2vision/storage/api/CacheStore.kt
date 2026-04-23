/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.storage.api

import com.i2vision.storage.model.Artifact
import com.i2vision.storage.model.ArtifactRef
import com.i2vision.storage.model.Layer
import com.i2vision.storage.model.PutResult
import kotlin.time.Duration

/**
 * Public API for VSLFC artifact storage.
 * Callers don't know or care where artifacts are physically stored.
 */
interface CacheStore {

    /**
     * Store a VSLFC artifact.
     */
    suspend fun put(ref: ArtifactRef, content: ByteArray): PutResult

    /**
     * Retrieve a VSLFC artifact.
     */
    suspend fun get(ref: ArtifactRef): Artifact?

    /**
     * List artifacts by module and layer.
     */
    suspend fun list(module: String, layer: Layer): List<ArtifactRef>

    /**
     * Check if artifact exists and is fresh.
     */
    suspend fun isFresh(ref: ArtifactRef, maxAge: Duration): Boolean

    /**
     * Get artifacts affected by file changes.
     */
    suspend fun getAffected(changedFiles: List<String>): List<ArtifactRef>

    /**
     * Clear artifacts for a module.
     */
    suspend fun clear(module: String): Int
}
