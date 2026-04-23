package com.i2vision.agent.engines

import org.slf4j.LoggerFactory
import org.yaml.snakeyaml.Yaml

data class ParsedToolCall(
    val tool: String,
    val args: Map<String, Any> = emptyMap()
)

data class ParsedAssistantOutput(
    val rawText: String,
    val assistantText: String,
    val reasoning: String? = null,
    val toolCall: ParsedToolCall? = null
)

data class IterationHistoryEntry(
    val iteration: Int,
    val assistantOutput: String,
    val observation: String? = null
)

class DiscoveryEngine(
    private val yaml: Yaml = Yaml()
) {
    private val log = LoggerFactory.getLogger(DiscoveryEngine::class.java)

    private val reasoningRegex = Regex("(?im)^\\s*reasoning\\s*:\\s*(.+)$")
    private val toolHeaderRegex = Regex("(?im)^\\s*(?:```\\s*)?tool_call\\s*:")

    // XML <invoke> patterns – handles multiple model dialects:
    //   <invoke name="readFile">…</invoke>
    //   <invoke="readFile">…</invoke>          (non-standard but seen in the wild)
    //   <function_calls><invoke …>…</invoke></function_calls>
    private val xmlInvokeOuterRegex = Regex(
        """<invoke(?:\s[^>]*)?>(.*?)</invoke>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    )
    private val xmlFunctionCallsRegex = Regex(
        """<function_calls>\s*(.*?)\s*</function_calls>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    )

    // Captures tool name from: name="tool" | name='tool' | ="tool" | ='tool'
    private val xmlToolNameRegex = Regex(
        """<invoke(?:\s+name\s*=\s*["']([^"'>\s]+)["']|\s*=\s*["']([^"'>\s]+)["']|\s+name\s*=\s*([^"'>\s]+))[^>]*>""",
        RegexOption.IGNORE_CASE
    )

    // Simple child element: <key>value</key>  or  <parameter name="key">value</parameter>
    private val xmlChildParamRegex = Regex(
        """<(\w+)(?:\s+name\s*=\s*["']([^"']+)["'])?[^>]*>([^<]*)</\1>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    )

    fun buildIterationPrompt(
        basePrompt: String,
        iteration: Int,
        maxIterations: Int,
        history: List<IterationHistoryEntry>
    ): String = buildString {
        append(basePrompt)
        if (history.isNotEmpty()) {
            append("\n\nConversation History:\n")
            history.takeLast(minOf(10, history.size)).forEach { entry ->
                append("Assistant: ${entry.assistantOutput}\n")
                entry.observation?.let { append("Observation: $it\n") }
            }
        }
        if (iteration >= maxIterations - 2) {
            append("\n\n[System: You have ${maxIterations - iteration} turn(s) left. Complete the task now.]")
        }
    }.also { prompt ->
        log.debug(
            "[DISCOVERY][TRACE] buildIterationPrompt; iteration={}/{}, baseLen={}, historySize={}, promptLen={}",
            iteration,
            maxIterations,
            basePrompt.length,
            history.size,
            prompt.length
        )
    }

    fun parseAssistantOutput(rawText: String): ParsedAssistantOutput {
        val toolSegment = extractToolSegment(rawText)
        val quotedJsonTool = if (toolSegment == null) parseQuotedJsonToolCall(rawText) else null
        val parsedTool = toolSegment?.let { seg ->
            if (seg.isXml) parseXmlToolCall(seg.json)
            else parseToolCall(seg.json)
        } ?: quotedJsonTool
        val assistantText = toolSegment?.let {
            (rawText.substring(0, it.startIndex) + rawText.substring(it.endIndexExclusive)).trim()
        } ?: if (quotedJsonTool != null) "" else rawText.trim()

        log.debug(
            "[DISCOVERY][TRACE] parseAssistantOutput; rawLen={}, headerHit={}, hasToolSegment={}, isXml={}, parsedTool={}, assistantLen={}",
            rawText.length,
            toolHeaderRegex.containsMatchIn(rawText),
            toolSegment != null,
            toolSegment?.isXml ?: false,
            parsedTool?.tool ?: "(none)",
            assistantText.length
        )

        return ParsedAssistantOutput(
            rawText = rawText,
            assistantText = assistantText,
            reasoning = reasoningRegex.find(assistantText)?.groupValues?.getOrNull(1)?.trim(),
            toolCall = parsedTool
        )
    }

    fun discover(input: Any): String = "Discovered: $input"

    private fun parseToolCall(json: String): ParsedToolCall? {
        val loaded = runCatching { yaml.load<Any>(json) }
            .onFailure { ex ->
                log.debug("[DISCOVERY][TRACE] parseToolCall; yaml parse failed: {}", ex.message)
            }
            .getOrNull() as? Map<*, *> ?: run {
            log.debug("[DISCOVERY][TRACE] parseToolCall; payload did not parse to map")
            return null
        }
        val normalized = normalizeMap(loaded)
        // Accept both {"tool":…} and {"name":…} — some models use "name" instead of "tool"
        val tool = (normalized["tool"] ?: normalized["name"])?.toString()?.trim().orEmpty()
        if (tool.isEmpty()) {
            log.debug("[DISCOVERY][TRACE] parseToolCall; missing 'tool'/'name' key. keys={}", normalized.keys)
            return null
        }

        val argsRaw =
            normalized["args"] ?: normalized["arguments"] ?: normalized["parameters"] ?: emptyMap<String, Any>()
        val args = when (argsRaw) {
            is Map<*, *> -> normalizeMap(argsRaw)
            else -> mapOf("value" to argsRaw)
        }
        log.debug("[DISCOVERY][TRACE] parseToolCall; tool={}, argKeys={}", tool, args.keys)
        return ParsedToolCall(tool = tool, args = args)
    }

    private fun normalizeMap(input: Map<*, *>): Map<String, Any> {
        val out = linkedMapOf<String, Any>()
        input.forEach { (key, value) ->
            val k = key?.toString()?.trim().orEmpty()
            if (k.isNotEmpty()) out[k] = normalizeValue(value)
        }
        return out
    }

    private fun normalizeValue(value: Any?): Any {
        return when (value) {
            null -> ""
            is Map<*, *> -> normalizeMap(value)
            is List<*> -> value.map { normalizeValue(it) }
            else -> value
        }
    }

    private fun extractToolSegment(text: String): ToolSegment? {
        // 1. JSON with explicit tool_call: header
        val headerMatch = toolHeaderRegex.find(text)
        if (headerMatch != null) {
            val seg = extractFirstJsonObject(text, headerMatch.range.last + 1)
            if (seg != null) {
                log.debug("[DISCOVERY][TRACE] extractToolSegment; strategy=json_header, segmentFound=true")
                return seg
            }
        }

        // 2. Naked JSON starting with {"tool" / {'tool'  OR  {"name" / {'name'
        //    Some models (e.g. gpt-oss) emit {"name":"tool","args":{…}} instead of {"tool":…}
        val directJsonStart = text.indexOf("{\"tool\"", ignoreCase = true).takeIf { it >= 0 }
            ?: text.indexOf("{'tool'", ignoreCase = true).takeIf { it >= 0 }
            ?: text.indexOf("{\"name\"", ignoreCase = true).takeIf { it >= 0 }
            ?: text.indexOf("{'name'", ignoreCase = true).takeIf { it >= 0 }
        if (directJsonStart != null) {
            val seg = extractFirstJsonObject(text, directJsonStart)
            if (seg != null) {
                log.debug(
                    "[DISCOVERY][TRACE] extractToolSegment; strategy=naked_json at {}, segmentFound=true",
                    directJsonStart
                )
                return seg
            }
        }

        // 3. XML <invoke> format (seen with several cloud models)
        val xmlSeg = extractXmlInvoke(text)
        if (xmlSeg != null) {
            log.info("[DISCOVERY][TRACE] extractToolSegment; strategy=xml_invoke, segmentFound=true — model used XML tool format")
            return xmlSeg
        }

        log.debug("[DISCOVERY][TRACE] extractToolSegment; no tool segment found")
        return null
    }

    private fun parseQuotedJsonToolCall(text: String): ParsedToolCall? {
        val trimmed = text.trim()
        val quote = trimmed.firstOrNull() ?: return null
        if (quote != '"' && quote != '\'') return null
        if (trimmed.length < 3 || trimmed.last() != quote) return null

        val unquoted = trimmed.substring(1, trimmed.length - 1)
        if (!unquoted.contains("{\"") && !unquoted.contains("{'") && !unquoted.contains("{\\\"")) return null

        val unescaped = unquoted
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")

        val parsed = parseToolCall(unescaped)
        if (parsed != null) {
            log.info(
                "[DISCOVERY][TRACE] parseQuotedJsonToolCall; extracted tool={} from quoted JSON payload",
                parsed.tool
            )
        }
        return parsed
    }

    /** Finds the first {@code <invoke>} block (optionally inside {@code <function_calls>}). */
    private fun extractXmlInvoke(text: String): ToolSegment? {
        // Prefer the whole <function_calls> wrapper when present
        val searchIn = xmlFunctionCallsRegex.find(text)?.value ?: text
        val offset = if (searchIn !== text) text.indexOf(searchIn) else 0

        val invokeMatch = xmlInvokeOuterRegex.find(searchIn) ?: return null
        val absStart = offset + invokeMatch.range.first
        val absEnd = offset + invokeMatch.range.last + 1
        return ToolSegment(startIndex = absStart, endIndexExclusive = absEnd, json = invokeMatch.value, isXml = true)
    }

    /** Parses an XML {@code <invoke>} element into a [ParsedToolCall]. */
    private fun parseXmlToolCall(xmlText: String): ParsedToolCall? {
        val nameMatch = xmlToolNameRegex.find(xmlText)
        val rawToolName = nameMatch?.groupValues
            ?.drop(1)
            ?.firstOrNull { it.isNotEmpty() }
            ?.trim()

        if (rawToolName.isNullOrEmpty()) {
            log.warn("[DISCOVERY][TRACE] parseXmlToolCall; no tool name found. xml='{}'", xmlText.take(120))
            return null
        }

        // Normalize: camelCase → snake_case, then through ToolNameNormalizer
        val normalizedTool = ToolNameNormalizer.normalize(camelToSnake(rawToolName))

        // Extract inner XML and parse child elements as args
        val inner = xmlText
            .substringAfter(">", "")
            .substringBeforeLast("</invoke>", "")
            .trim()

        val args = linkedMapOf<String, Any>()
        xmlChildParamRegex.findAll(inner).forEach { m ->
            // If <parameter name="key">value</parameter> → use name attr; otherwise use tag name
            val key = m.groupValues[2].takeIf { it.isNotEmpty() } ?: m.groupValues[1]
            val value = m.groupValues[3].trim()
            if (key.isNotEmpty()) args[key] = value
        }

        // Also capture bare text content if no child elements (e.g. <invoke name="tool">path</invoke>)
        if (args.isEmpty()) {
            val bare = inner.replace(Regex("<[^>]+>"), "").trim()
            if (bare.isNotEmpty()) args["value"] = bare
        }

        log.info(
            "[DISCOVERY][TRACE] parseXmlToolCall; rawTool={} → normalizedTool={}, argKeys={}",
            rawToolName,
            normalizedTool,
            args.keys
        )
        return ParsedToolCall(tool = normalizedTool, args = args)
    }

    /** Converts camelCase or PascalCase to snake_case before further normalization. */
    private fun camelToSnake(name: String): String =
        name.replace(Regex("([a-z])([A-Z])"), "$1_$2")
            .replace(Regex("([A-Z]+)([A-Z][a-z])"), "$1_$2")

    private fun extractFirstJsonObject(text: String, searchFrom: Int): ToolSegment? {
        val start = text.indexOf('{', searchFrom).takeIf { it >= 0 } ?: return null
        var depth = 0
        var inString = false
        var escaped = false

        for (i in start until text.length) {
            val ch = text[i]
            if (escaped) {
                escaped = false; continue
            }
            when (ch) {
                '\\' -> if (inString) escaped = true
                '"' -> inString = !inString
                '{' -> if (!inString) depth++
                '}' -> if (!inString) {
                    depth--
                    if (depth == 0) return ToolSegment(start, i + 1, text.substring(start, i + 1))
                }
            }
        }
        return null
    }

    private data class ToolSegment(
        val startIndex: Int,
        val endIndexExclusive: Int,
        val json: String,
        val isXml: Boolean = false
    )
}
