/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.vslfc

/**
 * Public API for verbalization storage.
 * Provides verbalization-specific operations on top of the generic CacheStore.
 */
interface VerbalizationStore {

    /**
     * Store verbalization results for a cluster.
     */
    suspend fun putVerbalizations(clusterId: String, results: List<VerbalizationResult>): PutResult

    /**
     * Get verbalization results for a cluster.
     */
    suspend fun getVerbalizations(clusterId: String): List<VerbalizationResult>?

    /**
     * Get verbalization for a specific symbol.
     */
    suspend fun getVerbalization(symbol: Symbol): VerbalizationResult?

    /**
     * Store hash tracking data for incremental verbalization.
     */
    suspend fun putHashes(clusterId: String, hashes: Map<String, String>): PutResult

    /**
     * Get hash tracking data for a cluster.
     */
    suspend fun getHashes(clusterId: String): Map<String, String>?

    /**
     * Check if a symbol needs re-verbalization based on hash.
     */
    suspend fun needsReverbalization(symbol: Symbol, currentHash: String): Boolean

    /**
     * Clear all verbalization data for a cluster.
     */
    suspend fun clearVerbalizations(clusterId: String): Int
}
