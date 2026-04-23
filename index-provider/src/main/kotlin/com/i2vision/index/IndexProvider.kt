/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.index

import java.io.File

/**
 * IndexProvider — formal contract for code intelligence queries.
 *
 * Implementations:
 *   CustomIndex   — regex-based, always available, no external deps (current)
 *   LspIndex      — stdio LSP client, deferred to Phase 3
 *   IntellijIndex — IntelliJ PSI, deferred to mcp-ide-plugin module
 *
 * All implementations must be safe to call from any coroutine context.
 * Heavy operations should be cached; see [IndexCache].
 */
interface IndexProvider {

    // ── Symbol resolution ─────────────────────────────────────────────────────

    /** Find a single symbol by exact name across the whole project. */
    fun findSymbol(name: String): SymbolInfo?

    /** Find symbols whose name matches [pattern] (glob or substring). */
    fun findSymbols(pattern: String): List<SymbolInfo>

    /** All top-level symbols declared in [file]. */
    fun symbolsInFile(file: File): List<SymbolInfo>

    /** All source files in the project (honours exclusion rules). */
    fun listSourceFiles(subPath: String = "src"): List<SourceFile>

    // ── Call / type hierarchy ─────────────────────────────────────────────────

    /**
     * Best-effort call hierarchy for [symbol].
     * [CallHierarchy.callees] — what [symbol] calls.
     * [CallHierarchy.callers] — what calls [symbol].
     * CustomIndex produces grep-approximate results; LSP/PSI produce exact results.
     */
    fun getCallHierarchy(symbol: SymbolInfo): CallHierarchy

    // ── Reachability ──────────────────────────────────────────────────────────

    /**
     * Set of files reachable from [entry] within [depth] call-graph hops.
     * CustomIndex approximates via grep; real indexes use resolved call graphs.
     */
    fun getReachableFiles(entry: SymbolInfo, depth: Int = 3): Set<File>

    // ── Entry-point discovery ─────────────────────────────────────────────────

    /**
     * Heuristic entry points: `main` functions, HTTP controllers, service roots,
     * Compose previews, etc.
     */
    fun findEntryPoints(): List<SymbolInfo>

    /**
     * Suggest cluster groupings based on cohesion (directory, package, or call graph).
     * Implementations are free to use whichever signal is available.
     */
    fun findClusters(subPath: String = "src/main"): List<ClusterSuggestion>
}

// ─── IndexProvider data types ─────────────────────────────────────────────────

/**
 * A resolved symbol as returned by [IndexProvider].
 * [qualifiedName] is used as the stable identity key.
 */
data class SymbolInfo(
    val name: String,
    val qualifiedName: String,
    val kind: String,           // class | fun | val | interface | object | enum
    val file: File,
    val line: Int,
    val language: String
)

data class CallHierarchy(
    val symbol: SymbolInfo,
    val callees: List<SymbolInfo>,   // what this symbol calls
    val callers: List<SymbolInfo>    // what calls this symbol
)

data class ClusterSuggestion(
    val name: String,
    val entryPoints: List<SymbolInfo>,
    val files: Set<File>,
    /** 0.0–1.0: how tightly cohesive this cluster is. */
    val cohesion: Double
)
