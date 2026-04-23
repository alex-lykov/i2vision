package com.i2vision.agent.engines

import org.slf4j.LoggerFactory

internal object ToolArguments {
    private val log = LoggerFactory.getLogger(ToolArguments::class.java)

    /**
     * Normalize parameter names to canonical forms.
     * Handles variations like:
     * - filePath, file_path, filepath, filename → filePath
     * - content, contents, file_content, text → content
     * - query, pattern → query
     * - includePattern, include_pattern, filePattern → includePattern
     * - line_start, lineStart → line_start
     * - line_end, lineEnd → line_end
     */
    fun normalize(args: Map<String, Any>): Map<String, Any> {
        if (args.isEmpty()) return args
        val normalized = linkedMapOf<String, Any>()
        val mappedKeys = mutableSetOf<String>() // track which canonical keys we've seen

        args.forEach { (key, value) ->
            val rawKey = key.trim()
            val canonical = toCanonical(rawKey)

            // Log if mapping occurred (for debugging model parameter issues)
            if (canonical != rawKey) {
                log.debug("[PARAM_NORM] {} → {}", rawKey, canonical)
            }

            // If we've already seen this canonical key, warn about duplicates
            if (canonical in normalized && canonical in mappedKeys) {
                log.warn("[PARAM_NORM] Duplicate canonical key '{}'; overwriting previous value", canonical)
            }

            normalized[canonical] = value
            mappedKeys.add(canonical)
        }
        return normalized
    }

    /**
     * Maps all known variations of a parameter to its canonical form.
     */
    private fun toCanonical(rawKey: String): String {
        val loweredSnake = rawKey
            .trim()
            .replace(Regex("([a-z])([A-Z])"), "$1_$2")  // camelCase → snake_case
            .replace('-', '_')
            .lowercase()

        return when (loweredSnake) {
            // File path parameters
            "filepath", "file_path", "filename", "file_name", "path", "p" -> "filePath"

            // Content/text parameters
            "contents", "file_content", "filecontent", "text", "data", "body", "source", "code" -> "content"

            // Search query parameters
            "query", "q", "pattern", "search", "regex", "expression" -> "query"

            // File pattern/filter parameters
            "include_pattern", "includepattern", "file_pattern", "filepattern", "glob", "filter", "match" -> "includePattern"

            // Line range parameters
            "line_start", "linestart", "start", "start_line", "from", "line_from" -> "line_start"
            "line_end", "lineend", "end", "end_line", "to", "line_to" -> "line_end"

            // Summary/message parameters
            "summary", "message", "msg", "result", "status" -> "summary"

            // Default: return as-is (already normalized to snake_case)
            else -> loweredSnake
        }
    }

    fun firstString(args: Map<String, Any>, vararg keys: String): String? =
        keys.firstNotNullOfOrNull { key -> args[key]?.toString()?.takeIf { it.isNotBlank() } }

    fun firstInt(args: Map<String, Any>, vararg keys: String): Int? =
        keys.firstNotNullOfOrNull { key ->
            args[key]?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.toIntOrNull()
        }

    /**
     * Extract a required string parameter. Throws IllegalArgumentException if not found or blank.
     */
    fun requireString(args: Map<String, Any>, key: String): String {
        return firstString(args, key)
            ?: throw IllegalArgumentException("Required parameter '$key' not found. Available: ${args.keys}")
    }

    fun sliceByLineRange(content: String, lineStart: Int?, lineEnd: Int?): Result<String> {
        if (lineStart == null && lineEnd == null) return Result.success(content)
        val lines = content.lines()
        if (lines.isEmpty()) return Result.success("")
        val start = lineStart ?: 1
        val end = lineEnd ?: lines.size
        if (start < 1 || end < 1 || end < start)
            return Result.failure(IllegalArgumentException("Invalid line range: start=$start end=$end"))
        if (start > lines.size)
            return Result.failure(IllegalArgumentException("line_start out of bounds: $start > ${lines.size}"))
        return Result.success(lines.subList(start - 1, minOf(end, lines.size)).joinToString("\n"))
    }

    fun globToRegex(glob: String): String = buildString {
        append('^')
        glob.forEach { ch ->
            when (ch) {
                '*' -> append(".*")
                '?' -> append('.')
                '.', '(', ')', '[', ']', '{', '}', '+', '^', '$', '|', '\\' -> {
                    append('\\'); append(ch)
                }

                else -> append(ch)
            }
        }
        append('$')
    }
}

