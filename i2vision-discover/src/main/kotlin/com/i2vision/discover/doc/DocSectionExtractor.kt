package com.i2vision.discover.doc

import com.i2vision.vslfc.DocLayerContract
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Document Section Extractor
 * 
 * Extracts sections from markdown documentation files based on contract mappings.
 * 
 * ## Supported Parser Types
 * 
 * ### MARKDOWN_LIST
 * Extracts bullet points or numbered lists:
 * ```markdown
 * ## Components
 * - DiscoveryPipeline: Orchestrates discovery
 * - IndexProvider: Provides indexing
 * ```
 * 
 * ### MARKDOWN_TABLE
 * Extracts table rows:
 * ```markdown
 * | Component | Purpose |
 * |-----------|---------|
 * | Pipeline  | Orchestration |
 * ```
 * 
 * ### FREE_TEXT
 * Extracts all text between headers:
 * ```markdown
 * ## Overview
 * This is the system overview
 * that spans multiple lines.
 * ```
 * 
 * ### YAML_EMBEDDED
 * Extracts YAML code blocks:
 * ```markdown
 * ## Configuration
 * ```yaml
 * key: value
 * ```
 * ```
 */
class DocSectionExtractor {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Extract a section from a documentation file.
     */
    fun extractSection(
        docFile: File,
        sectionHeader: String,
        parserType: DocLayerContract.ParserType
    ): ExtractionResult {
        log.debug("[EXTRACTOR] Extracting '$sectionHeader' from ${docFile.name}")

        if (!docFile.exists()) {
            return ExtractionResult.notFound(sectionHeader, docFile.name)
        }

        val content = docFile.readText()
        val sectionContent = findSection(content, sectionHeader)

        if (sectionContent == null) {
            if (parserType == DocLayerContract.ParserType.FREE_TEXT && isIntroSection(sectionHeader)) {
                val intro = extractDocumentIntro(content)
                if (!intro.isNullOrBlank()) {
                    log.info("[EXTRACTOR] Fallback intro extraction used for '$sectionHeader' in ${docFile.name}")
                    return ExtractionResult.success(sectionHeader, docFile.name, listOf(intro))
                }
            }
            log.warn("[EXTRACTOR] Section '$sectionHeader' not found in ${docFile.name}")
            return ExtractionResult.notFound(sectionHeader, docFile.name)
        }

        val items = when (parserType) {
            DocLayerContract.ParserType.MARKDOWN_LIST -> extractMarkdownList(sectionContent)
            DocLayerContract.ParserType.MARKDOWN_TABLE -> extractMarkdownTable(sectionContent)
            DocLayerContract.ParserType.FREE_TEXT -> listOf(extractFreeText(sectionContent))
            DocLayerContract.ParserType.YAML_EMBEDDED -> extractYamlEmbedded(sectionContent)
        }

        log.info("[EXTRACTOR] Extracted ${items.size} item(s) from '$sectionHeader'")
        return ExtractionResult.success(sectionHeader, docFile.name, items)
    }

    /**
     * Extract multiple sections from a documentation file.
     */
    fun extractSections(
        docFile: File,
        mappings: List<DocLayerContract.DocMapping>
    ): Map<String, ExtractionResult> {
        log.info("[EXTRACTOR] Extracting ${mappings.size} section(s) from ${docFile.name}")

        return mappings.associate { mapping ->
            mapping.layerField to extractSection(docFile, mapping.docSection, mapping.parser)
        }
    }

    // Section finding

    /**
     * Find content between a header and the next header of same or higher level.
     */
    private fun findSection(content: String, header: String): String? {
        val lines = content.lines()
        val requestedLevel = header.takeWhile { it == '#' }.length
        val requestedTitle = normalizeHeaderText(header.removePrefix("#").trim())

        // Find start of section
        val startIndexExact = lines.indexOfFirst { line ->
            line.trim().startsWith(header.trim(), ignoreCase = true)
        }

        val startIndex = if (startIndexExact != -1) {
            startIndexExact
        } else {
            lines.indexOfFirst { line ->
                val trimmed = line.trim()
                if (!trimmed.startsWith("#")) return@indexOfFirst false
                val lineTitle = normalizeHeaderText(trimmed.trimStart('#').trim())
                lineTitle == requestedTitle ||
                        lineTitle.contains(requestedTitle) ||
                        requestedTitle.contains(lineTitle)
            }
        }

        if (startIndex == -1) return null

        // Find end of section (next header of same or higher level)
        val startLevel = lines[startIndex].trim().takeWhile { it == '#' }.length
            .takeIf { it > 0 }
            ?: requestedLevel

        val endIndex = lines.drop(startIndex + 1).indexOfFirst { line ->
            val trimmed = line.trim()
            if (!trimmed.startsWith("#")) return@indexOfFirst false

            val level = trimmed.takeWhile { it == '#' }.length
            level <= startLevel
        }

        val sectionLines = if (endIndex == -1) {
            lines.drop(startIndex + 1)
        } else {
            lines.subList(startIndex + 1, startIndex + 1 + endIndex)
        }

        return sectionLines.joinToString("\n").trim()
    }

    private fun normalizeHeaderText(text: String): String {
        return text
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
    }

    private fun isIntroSection(sectionHeader: String): Boolean {
        val normalized = normalizeHeaderText(sectionHeader.trimStart('#').trim())
        return normalized.contains("purpose") ||
                normalized.contains("overview") ||
                normalized.contains("description")
    }

    private fun extractDocumentIntro(content: String): String? {
        val lines = content.lines()
        var seenTitle = false
        val intro = mutableListOf<String>()

        for (line in lines) {
            val trimmed = line.trim()
            if (!seenTitle) {
                if (trimmed.startsWith("#")) seenTitle = true
                continue
            }

            if (trimmed.startsWith("##")) break
            if (trimmed.isNotBlank() && !trimmed.startsWith("[")) {
                intro += trimmed
            }
            if (intro.isNotEmpty() && trimmed.isBlank()) break
        }

        return intro.joinToString(" ").takeIf { it.isNotBlank() }
    }

    // Parser implementations

    /**
     * Extract markdown list items (bullet or numbered).
     * 
     * Example:
     * ```
     * - Item 1
     * - Item 2
     *   - Sub-item (included)
     * 1. Numbered item
     * ```
     */
    fun extractMarkdownList(content: String): List<String> {
        val items = mutableListOf<String>()
        val lines = content.lines()

        var currentItem: StringBuilder? = null

        lines.forEach { line ->
            val trimmed = line.trim()

            // Check if this is a list item
            val isListItem = trimmed.startsWith("- ") ||
                    trimmed.startsWith("* ") ||
                    trimmed.matches(Regex("""^\d+\.\s+.*"""))

            if (isListItem) {
                // Save previous item
                currentItem?.let { items.add(it.toString().trim()) }

                // Start new item (remove list marker)
                val text = when {
                    trimmed.startsWith("- ") -> trimmed.substring(2)
                    trimmed.startsWith("* ") -> trimmed.substring(2)
                    else -> trimmed.replaceFirst(Regex("""^\d+\.\s+"""), "")
                }
                currentItem = StringBuilder(text)
            } else if (trimmed.isNotEmpty() && currentItem != null) {
                // Continuation of current item
                currentItem.append(" ").append(trimmed)
            } else if (trimmed.isEmpty() && currentItem != null) {
                // Empty line - save current item
                items.add(currentItem.toString().trim())
                currentItem = null
            }
        }

        // Don't forget the last item
        currentItem?.let { items.add(it.toString().trim()) }

        return items.filter { it.isNotBlank() }
    }

    /**
     * Extract markdown table rows.
     * 
     * Example:
     * ```
     * | Column1 | Column2 |
     * |---------|---------|
     * | Value1  | Value2  |
     * ```
     * 
     * Returns: ["Column1: Value1, Column2: Value2"]
     */
    fun extractMarkdownTable(content: String): List<String> {
        val lines = content.lines().map { it.trim() }.filter { it.startsWith("|") }

        if (lines.size < 3) return emptyList() // Need header, separator, and at least one row

        // Parse header
        val headerLine = lines[0]
        val headers = headerLine.split("|")
            .drop(1).dropLast(1) // Remove empty strings from start/end pipes
            .map { it.trim() }

        // Skip separator line (index 1)

        // Parse data rows
        val items = mutableListOf<String>()
        lines.drop(2).forEach { line ->
            val values = line.split("|")
                .drop(1).dropLast(1)
                .map { it.trim() }

            if (values.size == headers.size) {
                val row = headers.zip(values).joinToString(", ") { (header, value) ->
                    "$header: $value"
                }
                items.add(row)
            }
        }

        return items
    }

    /**
     * Extract free text (all content between headers).
     */
    fun extractFreeText(content: String): String {
        return content.lines()
            .filter { !it.trim().startsWith("#") } // Remove any nested headers
            .joinToString("\n")
            .trim()
    }

    /**
     * Extract YAML embedded in code blocks.
     * 
     * Example:
     * ```
     * ```yaml
     * key: value
     * ```
     * ```
     */
    private fun extractYamlEmbedded(content: String): List<String> {
        val yamlBlocks = mutableListOf<String>()
        val lines = content.lines()

        var inYamlBlock = false
        val currentBlock = StringBuilder()

        lines.forEach { line ->
            val trimmed = line.trim()

            if (trimmed.startsWith("```yaml") || trimmed.startsWith("```yml")) {
                inYamlBlock = true
                currentBlock.clear()
            } else if (trimmed == "```" && inYamlBlock) {
                inYamlBlock = false
                yamlBlocks.add(currentBlock.toString().trim())
            } else if (inYamlBlock) {
                currentBlock.append(line).append("\n")
            }
        }

        return yamlBlocks
    }

    // Result classes

    data class ExtractionResult(
        val sectionHeader: String,
        val sourceFile: String,
        val found: Boolean,
        val items: List<String> = emptyList(),
        val error: String? = null
    ) {
        companion object {
            fun success(header: String, file: String, items: List<String>) =
                ExtractionResult(header, file, true, items)

            fun notFound(header: String, file: String) =
                ExtractionResult(header, file, false, error = "Section not found")
        }
    }
}
