/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.i2vision.instant.context.ContextProvider
import com.i2vision.storage.impl.FileCacheStore
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Context Command - Get instant context for files and directories.
 * 
 * Usage: i2vision context <subcommand> [options]
 */
class ContextCommand : CliktCommand(
    name = "context",
    help = "Get instant context for files, directories, or project"
) {

    init {
        subcommands(
            FileContext(),
            FilesContext(),
            EnhancedContext(),
            CacheContext()
        )
    }

    override fun run() = Unit
}

/**
 * Extract module path from file path.
 * Example: "i2vision-instant/src/main/kotlin/..." -> "i2vision-instant"
 */
private fun extractModulePath(filePath: String): String {
    val normalizedPath = filePath.replace("\\", "/")
    return when {
        normalizedPath.startsWith("i2vision-") -> normalizedPath.substringBefore("/src/")
        normalizedPath.startsWith("vslfc-core") -> "vslfc-core"
        normalizedPath.startsWith("llm-client") -> "llm-client"
        normalizedPath.startsWith("storage-core") -> "storage-core"
        normalizedPath.startsWith("discovery-api") -> "discovery-api"
        normalizedPath.startsWith("discovery-engine") -> "discovery-engine"
        normalizedPath.startsWith("intent-parser") -> "intent-parser"
        normalizedPath.startsWith("architecture-types") -> "architecture-types"
        normalizedPath.startsWith("conf-agent-core") -> "conf-agent-core"
        normalizedPath.startsWith("contracts") -> "contracts"
        normalizedPath.startsWith("index-provider") -> "index-provider"
        normalizedPath.startsWith("link-service") -> "link-service"
        else -> normalizedPath.substringBefore("/src/").ifEmpty { "root" }
    }
}

/**
 * Get context for a specific file.
 */
class FileContext : CliktCommand(name = "file", help = "Get context for a specific file") {

    private val log = LoggerFactory.getLogger(FileContext::class.java)

    private val path by option("--path", "-p", help = "File path").required()
    private val task by option(
        "--task",
        "-t",
        help = "Task type (debug, refactor, add feature, fix bug, optimize)"
    ).default("discovery")
    private val project by option("--project", help = "Project root").default(".")

    override fun run() {
        log.info("[CLI] Context file command for: $path, task: $task")

        val projectRoot = File(project).absoluteFile
        if (!projectRoot.exists()) {
            echo("Error: Project root does not exist: $project", err = true)
            throw IllegalArgumentException("Project root does not exist: $project")
        }

        // Convert absolute path to relative path if necessary
        val filePath = if (File(path).isAbsolute) {
            File(path).relativeTo(projectRoot).path
        } else {
            path
        }

        val contextProvider = ContextProvider(
            projectRoot = projectRoot.path,
            cacheStore = FileCacheStore(projectRoot)
        )

        val context = runBlocking {
            contextProvider.getContext(filePath, task)
        }

        if (context.success) {
            echo("=== File Context ===")
            echo("File: ${context.filePath}")

            // Check and display cache status
            val modulePath = extractModulePath(filePath)
            val hasCache = contextProvider.hasDiscoveryCache(modulePath)
            if (hasCache) {
                echo("[ENHANCED] Discovery cache available")
                echo("   Run 'i2vision discover --intent=full_discovery' to enable deep analysis")
            } else {
                echo("[BASIC] Context (symbols + complexity)")
                echo("   Run discovery for enhanced: i2vision discover --intent=full_discovery")
            }
            echo("")
            echo("Symbols: ${context.symbols.size}")
            echo("Related files: ${context.relatedFiles.size}")
            echo("")

            if (context.symbols.isNotEmpty()) {
                echo("Symbols:")
                context.symbols.take(20).forEach { symbol ->
                    echo("  - ${symbol.kind} ${symbol.name} (${symbol.file}:${symbol.line})")
                }
                if (context.symbols.size > 20) {
                    echo("  ... and ${context.symbols.size - 20} more")
                }
                echo("")
            }

            if (context.relatedFiles.isNotEmpty()) {
                echo("Related files:")
                context.relatedFiles.take(10).forEach { file ->
                    echo("  - $file")
                }
                if (context.relatedFiles.size > 10) {
                    echo("  ... and ${context.relatedFiles.size - 10} more")
                }
                echo("")
            }

            echo("Task: ${context.taskContext.task}")
            echo("Suggestions:")
            context.taskContext.suggestions.forEach { echo("  - $it") }
            echo("")

            if (context.artifacts.isNotEmpty()) {
                echo("Artifacts: ${context.artifacts.joinToString(", ")}")
            }

            val complexity = context.complexityDetails
            if (complexity != null) {
                echo("")
                echo("Complexity Score: ${complexity.complexityScore}")
                echo("Cyclomatic Complexity: ${complexity.cyclomaticComplexity}")
                echo("Cognitive Complexity: ${complexity.cognitiveComplexity}")
                echo("Maintainability Index: ${complexity.maintainabilityIndex}")
            }

            if (context.strategySuggestions.isNotEmpty()) {
                echo("")
                echo("Strategy suggestions:")
                context.strategySuggestions.forEach { suggestion ->
                    echo("  - ${suggestion.name}: ${suggestion.description}")
                }
            }
        } else {
            echo("Error: ${context.error}", err = true)
            throw RuntimeException(context.error)
        }
    }
}

/**
 * Get context for multiple files.
 */
class FilesContext : CliktCommand(name = "files", help = "Get context for multiple files") {

    private val log = LoggerFactory.getLogger(FilesContext::class.java)

    private val paths by argument("paths", help = "File paths").multiple()
    private val task by option(
        "--task",
        "-t",
        help = "Task type (debug, refactor, add feature, fix bug, optimize)"
    ).default("discovery")
    private val project by option("--project", help = "Project root").default(".")

    override fun run() {
        log.info("[CLI] Context files command for ${paths.size} files, task: $task")

        if (paths.isEmpty()) {
            echo("Error: At least one file path required", err = true)
            throw IllegalArgumentException("At least one file path required")
        }

        val projectRoot = File(project).absoluteFile
        if (!projectRoot.exists()) {
            echo("Error: Project root does not exist: $project", err = true)
            throw IllegalArgumentException("Project root does not exist: $project")
        }

        // Convert absolute paths to relative paths if necessary
        val filePaths = paths.map { path ->
            if (File(path).isAbsolute) {
                File(path).relativeTo(projectRoot).path
            } else {
                path
            }
        }

        val contextProvider = ContextProvider(
            projectRoot = projectRoot.path,
            cacheStore = FileCacheStore(projectRoot)
        )

        val context = runBlocking {
            contextProvider.getContextForFiles(filePaths, task)
        }

        if (context.success) {
            echo("=== Files Context ===")
            echo("Files: ${context.filePath}")

            // Check and display cache status (use first file's module)
            val modulePath = extractModulePath(filePaths.first())
            val hasCache = contextProvider.hasDiscoveryCache(modulePath)
            if (hasCache) {
                echo("[ENHANCED] Discovery cache available")
                echo("   Run 'i2vision discover --intent=full_discovery' to enable deep analysis")
            } else {
                echo("[BASIC] Context (symbols + complexity)")
                echo("   Run discovery for enhanced: i2vision discover --intent=full_discovery")
            }
            echo("")
            echo("Total symbols: ${context.symbols.size}")
            echo("Total related files: ${context.relatedFiles.size}")
            echo("")

            if (context.symbols.isNotEmpty()) {
                echo("Symbols:")
                context.symbols.take(30).forEach { symbol ->
                    echo("  - ${symbol.kind} ${symbol.name} (${symbol.file}:${symbol.line})")
                }
                if (context.symbols.size > 30) {
                    echo("  ... and ${context.symbols.size - 30} more")
                }
                echo("")
            }

            if (context.relatedFiles.isNotEmpty()) {
                echo("Related files:")
                context.relatedFiles.take(15).forEach { file ->
                    echo("  - $file")
                }
                if (context.relatedFiles.size > 15) {
                    echo("  ... and ${context.relatedFiles.size - 15} more")
                }
                echo("")
            }

            echo("Task: ${context.taskContext.task}")
            echo("Suggestions:")
            context.taskContext.suggestions.forEach { echo("  - $it") }
            echo("")

            if (context.artifacts.isNotEmpty()) {
                echo("Artifacts: ${context.artifacts.joinToString(", ")}")
            }

            if (context.strategySuggestions.isNotEmpty()) {
                echo("")
                echo("Strategy suggestions:")
                context.strategySuggestions.forEach { suggestion ->
                    echo("  - ${suggestion.name}: ${suggestion.description}")
                }
            }
        } else {
            echo("Error: ${context.error}", err = true)
            throw RuntimeException(context.error)
        }
    }
}

/**
 * Get enhanced context (requires discovery cache).
 */
class EnhancedContext : CliktCommand(name = "enhanced", help = "Get enhanced context (requires discovery cache)") {

    private val log = LoggerFactory.getLogger(EnhancedContext::class.java)

    private val path by option("--path", "-p", help = "File path").required()
    private val task by option(
        "--task",
        "-t",
        help = "Task type (debug, refactor, add feature, fix bug, optimize)"
    ).default("discovery")
    private val project by option("--project", help = "Project root").default(".")

    override fun run() {
        log.info("[CLI] Context enhanced command for: $path, task: $task")

        val projectRoot = File(project).absoluteFile
        if (!projectRoot.exists()) {
            echo("Error: Project root does not exist: $project", err = true)
            throw IllegalArgumentException("Project root does not exist: $project")
        }

        // Convert absolute path to relative path if necessary
        val filePath = if (File(path).isAbsolute) {
            File(path).relativeTo(projectRoot).path
        } else {
            path
        }

        val contextProvider = ContextProvider(
            projectRoot = projectRoot.path,
            cacheStore = FileCacheStore(projectRoot)
        )

        // Check cache first
        val modulePath = extractModulePath(filePath)
        if (!contextProvider.hasDiscoveryCache(modulePath)) {
            echo("Error: No discovery cache found for module '$modulePath'", err = true)
            echo("Run discovery first:", err = true)
            echo("  i2vision discover --intent=full_discovery", err = true)
            throw IllegalArgumentException("No discovery cache found")
        }

        // Get enhanced context
        val context = runBlocking {
            contextProvider.getEnhancedContext(filePath, task)
        }

        if (context.success) {
            echo("=== Enhanced Context ===")
            echo("File: ${context.filePath}")
            echo("[ENHANCED] Discovery cache active")
            echo("")

            // Flows
            if (context.flows.isNotEmpty()) {
                echo("Flows: ${context.flows.size}")
                context.flows.take(5).forEach { flow ->
                    echo("  - ${flow.name} (${flow.steps.size} steps)")
                }
                if (context.flows.size > 5) {
                    echo("  ... and ${context.flows.size - 5} more")
                }
                echo("")
            }

            // Business Rules
            if (context.businessRules.isNotEmpty()) {
                echo("Business Rules: ${context.businessRules.size}")
                context.businessRules.take(5).forEach { rule ->
                    echo("  - ${rule.description.take(80)}...")
                }
                if (context.businessRules.size > 5) {
                    echo("  ... and ${context.businessRules.size - 5} more")
                }
                echo("")
            }

            // Component
            context.component?.let { comp ->
                echo("Component: ${comp.name} (cohesion: ${String.format("%.2f", comp.cohesion)})")
                echo("Files: ${comp.files.size}")
                echo("")
            }

            // Related Components
            if (context.relatedComponents.isNotEmpty()) {
                echo("Related Components:")
                context.relatedComponents.take(10).forEach { dep ->
                    echo("  - ${dep.from} -> ${dep.to} (${dep.type})")
                }
                if (context.relatedComponents.size > 10) {
                    echo("  ... and ${context.relatedComponents.size - 10} more")
                }
                echo("")
            }

            // Basic context (always show)
            echo("Symbols: ${context.symbols.size}")
            echo("Related files: ${context.relatedFiles.size}")
            echo("")

            if (context.symbols.isNotEmpty()) {
                echo("Symbols:")
                context.symbols.take(20).forEach { symbol ->
                    echo("  - ${symbol.kind} ${symbol.name} (${symbol.file}:${symbol.line})")
                }
                if (context.symbols.size > 20) {
                    echo("  ... and ${context.symbols.size - 20} more")
                }
                echo("")
            }

            if (context.relatedFiles.isNotEmpty()) {
                echo("Related files:")
                context.relatedFiles.take(10).forEach { file ->
                    echo("  - $file")
                }
                if (context.relatedFiles.size > 10) {
                    echo("  ... and ${context.relatedFiles.size - 10} more")
                }
                echo("")
            }

            echo("Task: ${context.taskContext.task}")
            echo("Suggestions:")
            context.taskContext.suggestions.forEach { echo("  - $it") }
            echo("")

            if (context.artifacts.isNotEmpty()) {
                echo("Artifacts: ${context.artifacts.joinToString(", ")}")
            }

            val complexity = context.complexityDetails
            if (complexity != null) {
                echo("")
                echo("Complexity Score: ${complexity.complexityScore}")
                echo("Cyclomatic Complexity: ${complexity.cyclomaticComplexity}")
                echo("Cognitive Complexity: ${complexity.cognitiveComplexity}")
                echo("Maintainability Index: ${complexity.maintainabilityIndex}")
            }

            if (context.strategySuggestions.isNotEmpty()) {
                echo("")
                echo("Strategy suggestions:")
                context.strategySuggestions.forEach { suggestion ->
                    echo("  - ${suggestion.name}: ${suggestion.description}")
                }
            }
        } else {
            echo("Error: ${context.error}", err = true)
            throw RuntimeException(context.error)
        }
    }
}

/**
 * Cache management for context.
 */
class CacheContext : CliktCommand(name = "cache", help = "Context cache management") {

    private val log = LoggerFactory.getLogger(CacheContext::class.java)

    private val action by argument("action", help = "Action: stats, clean, invalidate")
    private val pattern by option("--pattern", help = "Pattern for invalidate action")
    private val project by option("--project", help = "Project root").default(".")

    override fun run() {
        log.info("[CLI] Context cache command with action: $action")

        val projectRoot = File(project).absoluteFile
        if (!projectRoot.exists()) {
            echo("Error: Project root does not exist: $project", err = true)
            throw IllegalArgumentException("Project root does not exist: $project")
        }

        val contextProvider = ContextProvider(
            projectRoot = projectRoot.path,
            cacheStore = FileCacheStore(projectRoot)
        )

        when (action.lowercase()) {
            "stats" -> showCacheStats(contextProvider)
            "clean" -> cleanCache(contextProvider)
            "invalidate" -> invalidateCache(contextProvider)
            else -> {
                echo("Error: Unknown action '$action'. Valid actions: stats, clean, invalidate", err = true)
                throw IllegalArgumentException("Unknown action: $action")
            }
        }
    }

    private fun showCacheStats(contextProvider: ContextProvider) {
        val stats = contextProvider.getCacheStats()
        echo("Cache Statistics:")
        echo("  Total entries: ${stats.totalEntries}")
        echo("  Expired entries: ${stats.expiredEntries}")
        echo("  Valid entries: ${stats.validEntries}")
    }

    private fun cleanCache(contextProvider: ContextProvider) {
        contextProvider.cleanCache()
        echo("Cache cleaned successfully")
    }

    private fun invalidateCache(contextProvider: ContextProvider) {
        val patternValue = pattern ?: run {
            echo("Error: --pattern required for invalidate action", err = true)
            throw IllegalArgumentException("--pattern required for invalidate action")
        }
        contextProvider.invalidateCache(patternValue)
        echo("Cache invalidated for pattern: $patternValue")
    }
}
