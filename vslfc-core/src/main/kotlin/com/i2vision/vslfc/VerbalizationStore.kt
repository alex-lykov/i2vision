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
 * 
 * ## Two-Tier Hash Support:
 * - `putHashes` / `getHashes`: Local hashes (symbol body only)
 * - `putContextHashes` / `getContextHashes`: Context hashes (dependencies included)
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
     * Stores local hashes (symbol body only).
     */
    suspend fun putHashes(clusterId: String, hashes: Map<String, String>): PutResult

    /**
     * Get hash tracking data for a cluster.
     * Returns local hashes (symbol body only).
     */
    suspend fun getHashes(clusterId: String): Map<String, String>?

    /**
     * Store context hash tracking data for incremental verbalization.
     * Stores context hashes (includes dependencies).
     * 
     * @since 1.1.0
     */
    suspend fun putContextHashes(clusterId: String, hashes: Map<String, String>): PutResult

    /**
     * Get context hash tracking data for a cluster.
     * Returns context hashes (includes dependencies).
     * 
     * @since 1.1.0
     */
    suspend fun getContextHashes(clusterId: String): Map<String, String>?

    /**
     * Check if a symbol needs re-verbalization based on hash.
     */
    suspend fun needsReverbalization(symbol: Symbol, currentHash: String): Boolean

    /**
     * Clear all verbalization data for a cluster.
     */
    suspend fun clearVerbalizations(clusterId: String): Int
}
