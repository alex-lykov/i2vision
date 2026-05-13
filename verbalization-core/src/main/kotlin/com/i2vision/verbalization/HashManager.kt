/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.verbalization

import com.i2vision.vslfc.Symbol
import java.security.MessageDigest

/**
 * Manages content hashes for incremental verbalization.
 * Implements two-tier hashing:
 * - **Local hash**: Symbol's own source (fast check)
 * - **Context hash**: Immediate dependencies (callers/callees) for context-aware invalidation
 *
 * ## Hash Input Specification
 *
 * ### Local Hash (computed from):
 * - Symbol name
 * - Symbol content (full body text)
 * - Symbol metadata (annotations, modifiers, visibility)
 * - Symbol kind (class, function, property, etc.)
 * - Symbol signature (parameters, return type)
 *
 * ### Context Hash (computed from):
 * - All local hash inputs PLUS:
 * - Direct dependencies (imports used in symbol body)
 * - Called methods/functions (extracted from symbol content)
 * - Calling symbols (reverse lookup - who calls this symbol)
 * - Interface/parent class references
 *
 * ## Invalidation Triggers
 *
 * A symbol's verbalization is invalidated when:
 * 1. ✅ Symbol body changes (local hash mismatch)
 * 2. ✅ Direct dependencies change (context hash mismatch)
 * 3. ✅ Called methods are renamed/removed (context hash mismatch)
 * 4. ✅ Interface contracts change (context hash mismatch)
 *
 * ## Cache Hit Rate Target: >80%
 */
class HashManager {

    private val localHashes = mutableMapOf<String, String>()
    private val contextHashes = mutableMapOf<String, String>()

    /**
     * Compute local hash for a symbol based on its own content only.
     * Fast computation, catches direct changes to symbol body.
     */
    fun computeLocalHash(symbol: Symbol): String {
        val content = buildString {
            append(symbol.name)
            append(":")
            append(symbol.kind.name)
            append(":")
            append(symbol.content)
            append(":")
            append(symbol.metadata.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value}" })
            append(":")
            append(extractSignature(symbol))
        }
        return sha256(content)
    }

    /**
     * Compute context hash including dependencies.
     * Slower computation, catches changes in symbol's context.
     */
    fun computeContextHash(symbol: Symbol, dependencies: List<Symbol> = emptyList()): String {
        val localHash = computeLocalHash(symbol)
        
        val contextContent = buildString {
            append(localHash)
            append(":")
            // Add dependency hashes in sorted order for consistency
            append(dependencies.map { computeLocalHash(it) }.sorted().joinToString("|"))
            append(":")
            // Add extracted dependencies from content (imports, calls)
            append(extractDependencySignatures(symbol.content).sorted().joinToString(","))
        }
        return sha256(contextContent)
    }

    /**
     * Check if a symbol has changed using local hash (fast check).
     */
    fun hasLocalChanged(symbol: Symbol, currentLocalHash: String): Boolean {
        val key = getSymbolKey(symbol)
        val previousHash = localHashes[key]
        return previousHash != currentLocalHash
    }

    /**
     * Check if a symbol's context has changed (slower check).
     */
    fun hasContextChanged(symbol: Symbol, currentContextHash: String): Boolean {
        val key = getSymbolKey(symbol)
        val previousHash = contextHashes[key]
        return previousHash != currentContextHash
    }

    /**
     * Check if symbol needs re-verbalization (local OR context changed).
     */
    fun hasChanged(symbol: Symbol, currentLocalHash: String, currentContextHash: String? = null): Boolean {
        val localChanged = hasLocalChanged(symbol, currentLocalHash)
        
        // If context hash provided, check that too
        if (currentContextHash != null) {
            return localChanged || hasContextChanged(symbol, currentContextHash)
        }
        
        return localChanged
    }

    /**
     * Update both local and context hashes after verbalization.
     */
    fun updateHash(symbol: Symbol, localHash: String, contextHash: String? = null) {
        val key = getSymbolKey(symbol)
        localHashes[key] = localHash
        if (contextHash != null) {
            contextHashes[key] = contextHash
        }
    }

    /**
     * Load local hashes from persistent storage.
     */
    fun loadHashes(storedHashes: Map<String, String>) {
        localHashes.putAll(storedHashes)
    }

    /**
     * Load context hashes from persistent storage.
     */
    fun loadContextHashes(storedHashes: Map<String, String>) {
        contextHashes.putAll(storedHashes)
    }

    /**
     * Get all local hashes for persistence.
     */
    fun getAllHashes(): Map<String, String> = localHashes.toMap()

    /**
     * Get all context hashes for persistence.
     */
    fun getAllContextHashes(): Map<String, String> = contextHashes.toMap()

    /**
     * Clear all hashes (for cache invalidation).
     */
    fun clear() {
        localHashes.clear()
        contextHashes.clear()
    }

    /**
     * Clear hashes for a specific symbol.
     */
    fun clearSymbol(symbol: Symbol) {
        val key = getSymbolKey(symbol)
        localHashes.remove(key)
        contextHashes.remove(key)
    }

    /**
     * Generate unique key for a symbol.
     */
    private fun getSymbolKey(symbol: Symbol): String {
        return "${symbol.filePath}:${symbol.lineNumber}:${symbol.name}"
    }

    /**
     * Extract signature from symbol (parameters, return type).
     */
    private fun extractSignature(symbol: Symbol): String {
        val signaturePattern = Regex("""(?:fun|val|var|class)\s+\w+\s*(\([^)]*\))?\s*(?::\s*(\w+))?""")
        val match = signaturePattern.find(symbol.content) ?: return ""
        val params = match.groupValues[1] ?: ""
        val returnType = match.groupValues[2] ?: ""
        return "$params:$returnType"
    }

    /**
     * Extract dependency signatures from content (imports, method calls, type references).
     */
    private fun extractDependencySignatures(content: String): List<String> {
        val dependencies = mutableListOf<String>()
        
        // Extract imports
        val importPattern = Regex("""import\s+([\w.]+)""")
        dependencies.addAll(
            importPattern.findAll(content)
                .map { it.groupValues[1] }
                .filter { !it.startsWith("kotlin") && !it.startsWith("java") }
        )
        
        // Extract method calls (potential dependencies)
        val callPattern = Regex("""(\w+)\s*\(""")
        dependencies.addAll(
            callPattern.findAll(content)
                .map { it.groupValues[1] }
                .filter { it.length > 2 && it.first().isUpperCase() } // Likely class names
        )
        
        // Extract type references (potential dependencies)
        val typePattern = Regex(""":\s*(\w+)""")
        dependencies.addAll(
            typePattern.findAll(content)
                .map { it.groupValues[1] }
                .filter { it.first().isUpperCase() && it.length > 2 }
        )
        
        return dependencies.distinct()
    }

    /**
     * Compute SHA-256 hash of content.
     */
    private fun sha256(content: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(content.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
