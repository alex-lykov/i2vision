/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.storage

/**
 * Prompt caching: store and retrieve cached prompt/completion by key.
 * Key is typically a hash of the prompt (or prompt + model) to avoid re-sending unchanged prefixes.
 */
interface PromptCacheStore {
    /**
     * Get cached value for key, or null if missing or expired.
     * @param key cache key (e.g. hash of prompt)
     */
    suspend fun get(key: String): CachedPrompt?

    /**
     * Store a cached prompt/completion.
     * @param key cache key
     * @param value cached content (e.g. serialized completion or cache handle from provider)
     * @param ttlSeconds optional time-to-live; null means no expiry
     */
    suspend fun put(key: String, value: String, ttlSeconds: Long? = null)

    /**
     * Remove entry by key.
     */
    suspend fun invalidate(key: String)

    /**
     * Remove all entries (e.g. for cache clear).
     */
    suspend fun invalidateAll()

    /**
     * Remove expired entries. Call periodically if using TTL.
     */
    suspend fun cleanupExpired()
}

data class CachedPrompt(
    val key: String,
    val value: String,
    val expiresAtMillis: Long? = null
) {
    val isExpired: Boolean
        get() = expiresAtMillis != null && System.currentTimeMillis() > expiresAtMillis
}
