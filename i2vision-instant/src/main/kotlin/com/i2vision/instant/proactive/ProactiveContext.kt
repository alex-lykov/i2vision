package com.i2vision.instant.proactive

import com.i2vision.index.IndexProvider
import com.i2vision.index.CustomIndex
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Proactive Context - Pre-fetches related files for faster context retrieval.
 * 
 * This component proactively identifies and caches related files based on
 * the current working context, reducing latency for subsequent context requests.
 */
class ProactiveContext(
    private val projectRoot: String
) {

    private val log = LoggerFactory.getLogger(ProactiveContext::class.java)
    private val indexProvider: IndexProvider by lazy { CustomIndex(projectRoot) }
    private val cache = mutableMapOf<String, Set<String>>()

    /**
     * Pre-fetch related files for a given file.
     * 
     * @param filePath Path to the file (relative to project root)
     * @param depth How many hops to explore in the call graph (default: 2)
     * @return Set of related file paths
     */
    suspend fun prefetchRelatedFiles(filePath: String, depth: Int = 2): Set<String> {
        log.debug("[PROACTIVE] Prefetching related files for: {}, depth: {}", filePath, depth)

        val absolutePath = File(projectRoot, filePath)
        if (!absolutePath.exists()) {
            return emptySet()
        }

        // Check cache first
        if (cache.containsKey(filePath)) {
            log.debug("[PROACTIVE] Cache hit for: {}", filePath)
            return cache[filePath]!!
        }

        // Get symbols in the file
        val symbols = indexProvider.symbolsInFile(absolutePath)

        // Find related files using reachability
        val relatedFiles = mutableSetOf<String>()

        symbols.forEach { symbol ->
            try {
                val reachable = indexProvider.getReachableFiles(symbol, depth)
                reachable.forEach { file ->
                    if (file.exists() && file.absolutePath != absolutePath.absolutePath) {
                        relatedFiles.add(file.relativeTo(File(projectRoot)).path)
                    }
                }
            } catch (e: Exception) {
                log.debug("[PROACTIVE] Failed to get reachable files for ${symbol.name}: ${e.message}")
            }
        }

        // Cache the result
        cache[filePath] = relatedFiles

        log.debug("[PROACTIVE] Prefetched {} related files for: {}", relatedFiles.size, filePath)
        return relatedFiles
    }

    /**
     * Pre-fetch related files for multiple files.
     * 
     * @param filePaths List of file paths (relative to project root)
     * @param depth How many hops to explore in the call graph (default: 2)
     * @return Set of all related file paths
     */
    suspend fun prefetchRelatedFilesForFiles(filePaths: List<String>, depth: Int = 2): Set<String> {
        log.debug("[PROACTIVE] Prefetching related files for {} files, depth: {}", filePaths.size, depth)

        val allRelatedFiles = mutableSetOf<String>()

        filePaths.forEach { filePath ->
            val related = prefetchRelatedFiles(filePath, depth)
            allRelatedFiles.addAll(related)
        }

        log.debug(
            "[PROACTIVE] Prefetched {} total related files for {} input files",
            allRelatedFiles.size,
            filePaths.size
        )
        return allRelatedFiles
    }

    /**
     * Get cached related files for a file.
     * 
     * @param filePath Path to the file (relative to project root)
     * @return Set of related file paths, or null if not cached
     */
    fun getCachedRelatedFiles(filePath: String): Set<String>? {
        return cache[filePath]
    }

    /**
     * Clear the cache.
     */
    fun clearCache() {
        log.debug("[PROACTIVE] Clearing cache ({} entries)", cache.size)
        cache.clear()
    }

    /**
     * Get cache statistics.
     * 
     * @return CacheStats with current cache information
     */
    fun getCacheStats(): CacheStats {
        return CacheStats(
            size = cache.size,
            totalRelatedFiles = cache.values.sumOf { it.size },
            hitRate = 0.0 // TODO: Implement hit rate tracking
        )
    }
}

/**
 * Cache statistics.
 */
data class CacheStats(
    val size: Int,
    val totalRelatedFiles: Int,
    val hitRate: Double
)
