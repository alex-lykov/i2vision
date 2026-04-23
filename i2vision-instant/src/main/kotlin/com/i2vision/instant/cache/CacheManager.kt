package com.i2vision.instant.cache

import org.slf4j.LoggerFactory
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap

/**
 * Cache manager for i2vision-instant context caching
 * Provides cache invalidation and cleanup functionality
 */
class CacheManager(
    private val defaultExpiryMinutes: Long = 5
) {

    private val log = LoggerFactory.getLogger(CacheManager::class.java)
    private val cache = ConcurrentHashMap<String, CachedContext>()

    data class CachedContext(
        val result: Any,
        val timestamp: LocalDateTime,
        val expiryMinutes: Long = 5
    ) {
        fun isExpired(): Boolean =
            ChronoUnit.MINUTES.between(timestamp, LocalDateTime.now()) > expiryMinutes
    }

    /**
     * Get cached value if available and not expired
     */
    fun get(key: String): Any? {
        val cached = cache[key]
        return if (cached != null && !cached.isExpired()) {
            log.debug("[CACHE] Cache hit for key: {}", key)
            cached.result
        } else {
            log.debug("[CACHE] Cache miss or expired for key: {}", key)
            null
        }
    }

    /**
     * Put value in cache
     */
    fun put(key: String, value: Any, expiryMinutes: Long = defaultExpiryMinutes) {
        cache[key] = CachedContext(
            result = value,
            timestamp = LocalDateTime.now(),
            expiryMinutes = expiryMinutes
        )
        log.debug("[CACHE] Cached key: {} with expiry: {} minutes", key, expiryMinutes)
    }

    /**
     * Invalidate cache entries matching pattern
     */
    fun invalidate(pattern: String) {
        val keysToRemove = cache.keys.filter { it.contains(pattern) }
        keysToRemove.forEach { cache.remove(it) }
        log.info("[CACHE] Invalidated {} cache entries matching pattern: {}", keysToRemove.size, pattern)
    }

    /**
     * Clear expired cache entries
     */
    fun cleanExpired() {
        val expiredKeys = cache.entries
            .filter { it.value.isExpired() }
            .map { it.key }
        expiredKeys.forEach { cache.remove(it) }
        log.info("[CACHE] Cleaned {} expired cache entries", expiredKeys.size)
    }

    /**
     * Clear all cache entries
     */
    fun clearAll() {
        val size = cache.size
        cache.clear()
        log.info("[CACHE] Cleared all {} cache entries", size)
    }

    /**
     * Get cache statistics
     */
    fun getStats(): CacheStats {
        val expiredCount = cache.values.count { it.isExpired() }
        return CacheStats(
            totalEntries = cache.size,
            expiredEntries = expiredCount,
            validEntries = cache.size - expiredCount
        )
    }

    data class CacheStats(
        val totalEntries: Int,
        val expiredEntries: Int,
        val validEntries: Int
    )
}
