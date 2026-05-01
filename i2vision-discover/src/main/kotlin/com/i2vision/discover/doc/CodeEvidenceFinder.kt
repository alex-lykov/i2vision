/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

package com.i2vision.discover.doc

import com.i2vision.vslfc.VSLFCLayerContracts
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files

/**
 * Code Evidence Finder
 * 
 * Searches the codebase for evidence that documented requirements/components are actually implemented.
 * Used to calculate confidence scores and validate documentation.
 * 
 * ## Evidence Types
 * 
 * 1. **Class/Interface Evidence** - requirement mentions "DiscoveryPipeline" → find DiscoveryPipeline.kt
 * 2. **Method Evidence** - requirement mentions "discover" → find functions with "discover" in name
 * 3. **Pattern Evidence** - requirement mentions concepts → find related code patterns
 * 4. **File Evidence** - general keyword search across codebase
 * 
 * ## Confidence Calculation
 * 
 * - 0 matches: confidence * 0.5 (no evidence - probably not implemented)
 * - 1-2 matches: confidence * 1.0 (base confidence - likely implemented)
 * - 3+ matches: confidence * 1.2 (strong evidence - definitely implemented)
 * - Capped at 1.0
 */
class CodeEvidenceFinder(
    private val projectRoot: File,
    private val sourceRoots: List<String> = listOf("src/main/kotlin", "src/main/java")
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Find evidence for a requirement/component in the codebase.
     */
    fun findEvidence(
        text: String,
        layer: VSLFCLayerContracts.Layer
    ): EvidenceResult {
        log.debug("[EVIDENCE] Searching for: ${text.take(50)}...")

        val keywords = (extractKeywords(text) + expandSemanticKeywords(text)).distinct()
        if (keywords.isEmpty()) {
            return EvidenceResult(text, emptyList(), 0.0, "No searchable keywords")
        }

        val allEvidence = mutableListOf<CodeEvidence>()

        // Search for each keyword
        keywords.forEach { keyword ->
            val evidence = searchForKeyword(keyword)
            allEvidence.addAll(evidence)
        }

        // Remove duplicates (same file mentioned multiple times)
        val uniqueEvidence = allEvidence.distinctBy { "${it.file}:${it.line}" }

        // Calculate confidence boost
        val confidenceMultiplier = when (uniqueEvidence.size) {
            0 -> 0.5      // No evidence - reduce confidence
            1, 2 -> 1.0   // Some evidence - maintain confidence
            else -> 1.2   // Strong evidence - boost confidence
        }

        log.info("[EVIDENCE] Found ${uniqueEvidence.size} evidence(s) for '${keywords.joinToString(", ")}'")

        return EvidenceResult(
            searchText = text,
            evidence = uniqueEvidence,
            confidenceMultiplier = confidenceMultiplier.coerceAtMost(1.0),
            message = if (uniqueEvidence.isEmpty()) "No code evidence found" else "${uniqueEvidence.size} evidence(s) found"
        )
    }

    /**
     * Find evidence for multiple requirements/components at once.
     */
    fun findEvidenceForAll(
        items: List<String>,
        layer: VSLFCLayerContracts.Layer
    ): Map<String, EvidenceResult> {
        log.info("[EVIDENCE] Finding evidence for ${items.size} item(s)")

        return items.associate { item ->
            item to findEvidence(item, layer)
        }
    }

    /**
     * Extract searchable keywords from a requirement text.
     * 
     * Examples:
     * - "DiscoveryPipeline orchestrates discovery" → ["DiscoveryPipeline", "orchestrates", "discovery"]
     * - "System must support coroutines" → ["coroutines"]
     */
    private fun extractKeywords(text: String): List<String> {
        // Remove common words
        val stopWords = setOf(
            "the", "a", "an", "and", "or", "but", "in", "on", "at", "to", "for",
            "of", "with", "by", "from", "as", "is", "was", "are", "were",
            "this", "that", "these", "those", "must", "should", "will", "can",
            "system", "feature", "provide", "support", "implement", "add"
        )

        // Split on non-alphanumeric, filter out stop words and short words
        val words = text.split(Regex("[^a-zA-Z0-9]+"))
            .map { it.trim() }
            .filter { it.length > 3 }
            .filter { it.lowercase() !in stopWords }
            .distinct()

        // Prioritize capitalized words (likely class names)
        val capitalizedWords = words.filter { it[0].isUpperCase() }
        val otherWords = words.filter { it[0].isLowerCase() }.take(3) // Limit non-capitalized words

        return (capitalizedWords + otherWords).take(5) // Max 5 keywords
    }

    /**
     * Search for a keyword in the codebase.
     */
    private fun searchForKeyword(keyword: String): List<CodeEvidence> {
        val evidence = mutableListOf<CodeEvidence>()

        // Search in source roots
        sourceRoots.forEach { sourceRoot ->
            val sourceDir = File(projectRoot, sourceRoot)
            if (sourceDir.exists() && sourceDir.isDirectory) {
                searchInDirectory(sourceDir, keyword, evidence)
            }
        }

        // Also scan build metadata where dependency/version evidence often lives.
        listOf("build.gradle.kts", "build.gradle", "settings.gradle.kts", "settings.gradle", "gradle.properties")
            .map { File(projectRoot, it) }
            .filter { it.exists() && it.isFile }
            .forEach { searchInFile(it, keyword, evidence, maxMatches = 20) }

        return evidence
    }

    private fun expandSemanticKeywords(text: String): List<String> {
        val lower = text.lowercase()
        val expanded = mutableListOf<String>()

        // Kotlin coroutines
        if (lower.contains("coroutine")) {
            expanded += listOf("kotlinx.coroutines", "suspend fun", "CoroutineScope", "flow")
        }
        // Kotlin language features
        if (lower.contains("kotlin")) {
            expanded += listOf("kotlin(", "kotlinVersion", "org.jetbrains.kotlin")
        }
        // Version/build info
        if (lower.contains("version")) {
            expanded += listOf("version", "kotlinVersion", "plugin")
        }
        // Result/Return type patterns
        if (lower.contains("return") && (lower.contains("result") || lower.contains("typed"))) {
            expanded += listOf(
                "sealed class",           // sealed class Result
                "sealed interface",       // sealed interface Result
                "data class.*Result",     // data class SuccessResult
                ": Result<",              // Return type with generic
                "fun.*:.*Result"          // Function returning Result
            )
        }
        // SnakeYAML evidence
        if (lower.contains("snakeyaml") || lower.contains("yaml")) {
            expanded += listOf(
                "import org.yaml.snakeyaml",
                "SnakeYAML",
                "Yaml().load",
                "ObjectMapper(YAMLFactory())"
            )
        }

        return expanded
    }

    /**
     * Check if a line matches a regex pattern (for semantic matching).
     */
    private fun matchesPattern(line: String, patterns: List<String>): Boolean {
        return patterns.any { pattern ->
            try {
                Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(line)
            } catch (e: Exception) {
                // Fallback to simple contains if regex is invalid
                line.contains(pattern, ignoreCase = true)
            }
        }
    }

    /**
     * Recursively search a directory for keyword matches.
     * All exceptions are caught and handled to prevent propagation.
     */
    private fun searchInDirectory(
        dir: File,
        keyword: String,
        evidence: MutableList<CodeEvidence>,
        maxMatches: Int = 10
    ) {
        if (evidence.size >= maxMatches) return

        try {
            try {
                val files = dir.listFiles() ?: return
                files.forEach { file ->
                    if (evidence.size >= maxMatches) return

                    try {
                        when {
                            file.isDirectory -> searchInDirectory(file, keyword, evidence, maxMatches)
                            file.isFile && isSourceFile(file) -> searchInFile(file, keyword, evidence, maxMatches)
                        }
                    } catch (e: SecurityException) {
                        // Skip files we don't have access to
                        log.trace("[EVIDENCE] Access denied to file: ${file.path}")
                    } catch (e: Exception) {
                        // Skip any other file-level errors
                        log.trace("[EVIDENCE] Error processing ${file.path}: ${e.message}")
                    }
                }
            } catch (e: SecurityException) {
                // Skip directories we don't have access to (common on Windows)
                // Don't log at DEBUG to avoid filling logs with access denied messages
                log.trace("[EVIDENCE] Access denied to directory: ${dir.path}")
            } catch (e: Exception) {
                // Log but don't fail on directory listing errors
                log.trace("[EVIDENCE] Error listing directory ${dir.path}: ${e.message}")
            }
        } catch (e: Throwable) {
            // Catch any unexpected Throwable to ensure complete isolation
            log.trace("[EVIDENCE] Unexpected error in directory search for ${dir.path}")
        }
    }

    /**
     * Check if a file is a source code file.
     */
    private fun isSourceFile(file: File): Boolean {
        val extension = file.extension.lowercase()
        return extension in setOf("kt", "java", "kts")
    }

    /**
     * Search for keyword in a file.
     */
    private fun searchInFile(
        file: File,
        keyword: String,
        evidence: MutableList<CodeEvidence>,
        maxMatches: Int
    ) {
        if (evidence.size >= maxMatches) return
        try {
            // Use NIO for thread-safe file reading on Windows
            val lines = Files.readAllLines(file.toPath())
            // Always treat expanded/semantic keywords as patterns
            lines.forEachIndexed { index, line ->
                if (evidence.size >= maxMatches) return

                try {
                    val matches = matchesPattern(line, listOf(keyword))
                    if (matches) {
                        val matchType = determineMatchType(line, keyword)
                        evidence.add(
                            CodeEvidence(
                                file = file.absolutePath,
                                line = index + 1,
                                content = line.trim(),
                                matchType = matchType
                            )
                        )
                        log.debug("[EVIDENCE] Found evidence in ${file.name}:${index + 1}")
                    }
                } catch (e: Exception) {
                    log.trace("[EVIDENCE] Error matching pattern in ${file.name}:${index + 1}")
                }
            }
        } catch (e: Exception) {
            log.trace("[EVIDENCE] Error reading file ${file.path}: ${e.message}")
        }
    }

    /**
     * Determine the type of match for a keyword in a line.
     */
    private fun determineMatchType(line: String, keyword: String): CodeEvidence.MatchType {
        val trimmed = line.trim()
        return when {
            trimmed.startsWith("package") -> CodeEvidence.MatchType.PACKAGE
            trimmed.startsWith("import") -> CodeEvidence.MatchType.IMPORT
            trimmed.contains("class ") || trimmed.contains("interface ") -> CodeEvidence.MatchType.CLASS
            trimmed.contains("fun ") -> CodeEvidence.MatchType.METHOD
            trimmed.contains("val ") || trimmed.contains("var ") -> CodeEvidence.MatchType.FIELD
            else -> CodeEvidence.MatchType.OTHER
        }
    }

    // Data classes

    /**
     * Evidence found in the codebase.
     */
    data class CodeEvidence(
        val file: String,
        val line: Int,
        val content: String,
        val matchType: MatchType = MatchType.OTHER
    ) {
        enum class MatchType {
            PACKAGE, CLASS, METHOD, FIELD, IMPORT, OTHER
        }
    }

    /**
     * Result of evidence search.
     */
    data class EvidenceResult(
        val searchText: String,
        val evidence: List<CodeEvidence>,
        val confidenceMultiplier: Double,
        val message: String
    )
}