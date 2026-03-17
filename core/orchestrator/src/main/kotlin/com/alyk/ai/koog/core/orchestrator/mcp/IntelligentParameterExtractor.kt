package com.alyk.ai.koog.core.orchestrator.mcp

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import java.util.regex.Pattern

/**
 * Intelligent parameter extraction system
 * Uses pattern matching, context analysis, and learning
 */
class IntelligentParameterExtractor {
    
    // Dynamic pattern registry that learns from usage
    private val patternRegistry = mutableMapOf<String, List<ExtractionPattern>>()
    
    // Context-aware extraction rules
    private val contextRules = mutableMapOf<String, ContextRule>()
    
    init {
        initializeDefaultPatterns()
        initializeContextRules()
    }
    
    /**
     * Extract parameters using intelligent pattern matching
     */
    fun extractParameters(prompt: String, selectedTools: List<SelectedMcpTool>): Map<String, Map<String, Any>> {
        val parameters = mutableMapOf<String, Map<String, Any>>()
        val promptLower = prompt.lowercase()
        
        selectedTools.forEach { selectedTool ->
            val toolParams = extractToolParameters(prompt, promptLower, selectedTool.tool)
            parameters[selectedTool.tool.name] = toolParams
            
            // Learn from successful extractions
            recordSuccessfulPattern(selectedTool.tool.name, toolParams, prompt)
        }
        
        return parameters
    }
    
    /**
     * Extract parameters for a specific tool using multiple strategies
     */
    private fun extractToolParameters(prompt: String, promptLower: String, tool: ProjectMcpTool): Map<String, Any> {
        val params = mutableMapOf<String, Any>()
        
        // Strategy 1: Pattern-based extraction
        val patterns = patternRegistry[tool.name] ?: emptyList()
        patterns.forEach { pattern ->
            val matches = pattern.extract(prompt)
            if (matches.isNotEmpty()) {
                params.putAll(matches)
            }
        }
        
        // Strategy 2: Context-aware extraction
        val contextRule = contextRules[tool.name]
        if (contextRule != null) {
            val contextParams = contextRule.extractor(prompt, promptLower)
            params.putAll(contextParams)
        }

        // FILE_ACCESS tools frequently need a path even when other params were extracted.
        // Don't let early partial matches (e.g., detecting "modify") block path extraction.
        if (tool.category == McpToolCategory.FILE_ACCESS && params["path"] == null) {
            extractFileOperationParams(prompt, promptLower, params)
        }
        
        // Strategy 3: Fallback extraction using NLP-like analysis
        if (params.isEmpty()) {
            val fallbackParams = fallbackExtraction(prompt, promptLower, tool)
            params.putAll(fallbackParams)
        }
        
        return params
    }
    
    /**
     * Fallback extraction using semantic analysis
     */
    private fun fallbackExtraction(prompt: String, promptLower: String, tool: ProjectMcpTool): Map<String, Any> {
        val params = mutableMapOf<String, Any>()
        
        when (tool.category) {
            McpToolCategory.FILE_ACCESS -> {
                // Extract file paths, operations, and targets
                extractFileOperationParams(prompt, promptLower, params)
            }
            McpToolCategory.SEARCH -> {
                // Extract search patterns and locations
                extractSearchParams(prompt, promptLower, params)
            }
            McpToolCategory.EXECUTION -> {
                // Extract commands and targets
                extractExecutionParams(prompt, promptLower, params)
            }
            else -> {
                // Generic extraction
                extractGenericParams(prompt, promptLower, params)
            }
        }
        
        return params
    }
    
    /**
     * Extract file operation parameters intelligently
     */
    private fun extractFileOperationParams(prompt: String, promptLower: String, params: MutableMap<String, Any>) {
        // Extract file path
        val filePathPattern = Pattern.compile("""(?:file|path|directory)[\s:]+([^\s,\.]+)""", Pattern.CASE_INSENSITIVE)
        val pathMatcher = filePathPattern.matcher(prompt)
        if (pathMatcher.find()) {
            params["path"] = (pathMatcher.group(1) ?: "").trim().trim('`', '"', '\'')
        } else {
            // Look for .kt, .java, .py etc.
            val fileExtensionPattern = Pattern.compile("""([a-zA-Z0-9_\-/]+\.(kt|java|py|js|ts|cpp|c|h|go|rs|swift|scala|cs|php|rb|sh|bash|zsh|ps1|cmd|bat))""")
            val fileMatcher = fileExtensionPattern.matcher(prompt)
            if (fileMatcher.find()) {
                params["path"] = (fileMatcher.group(1) ?: "").trim().trim('`', '"', '\'')
            }
        }

        // If the user provided just a filename (e.g. "Main.kt"), assume conventional Kotlin sources location.
        val rawPath = (params["path"] as? String)?.trim()
        if (!rawPath.isNullOrEmpty() &&
            !rawPath.contains("/") &&
            !rawPath.contains("\\") &&
            rawPath.contains(".") &&
            rawPath.lowercase().endsWith(".kt")
        ) {
            params["path"] = "src/main/kotlin/$rawPath"
        }
        
        // Extract operation type
        val operations = mapOf(
            "remove" to "delete",
            "delete" to "delete", 
            "add" to "insert",
            "create" to "create",
            "edit" to "modify",
            "modify" to "modify",
            "update" to "modify",
            "list" to "list",
            "read" to "read",
            "view" to "read",
            "open" to "read"
        )
        
        operations.forEach { (keyword, operation) ->
            if (promptLower.contains(keyword)) {
                params["operation"] = operation
                return@forEach
            }
        }
        
        // Extract ordinal numbers and targets
        val ordinalPattern = """(\d+(?:st|nd|rd|th)|first|second|third|fourth|fifth|sixth|seventh|eighth|ninth|tenth)\s+([a-zA-Z_][a-zA-Z0-9_]*)""".toRegex(RegexOption.IGNORE_CASE)
        val ordinalMatches = ordinalPattern.findAll(prompt)
        
        val ordinalTargets = mutableMapOf<String, String>()
        ordinalMatches.forEach { match ->
            val ordinal = match.groupValues[1]
            val target = match.groupValues[2]
            ordinalTargets[target] = ordinal
        }
        
        if (ordinalTargets.isNotEmpty()) {
            params["ordinal_targets"] = ordinalTargets
        }
        
        // Extract specific code elements
        val codeElements = listOf("function", "method", "class", "interface", "variable", "constant", "coroutine", "loop", "condition", "import", "package")
        val foundElements = codeElements.filter { promptLower.contains(it) }
        if (foundElements.isNotEmpty()) {
            params["code_elements"] = foundElements
        }
    }
    
    /**
     * Extract search parameters
     */
    private fun extractSearchParams(prompt: String, promptLower: String, params: MutableMap<String, Any>) {
        // Extract search pattern
        val searchPattern = """(?:search|find|look for|locate)\s+(?:for\s+)?([^\s]+(?:\s+[^\s]+)*?)(?:\s+in\s+([^\s]+))?""".toRegex(RegexOption.IGNORE_CASE)
        val searchMatch = searchPattern.find(prompt)
        if (searchMatch != null) {
            params["pattern"] = searchMatch.groupValues[1].trim()
            if (searchMatch.groupValues.size > 2 && searchMatch.groupValues[2].isNotEmpty()) {
                params["directory"] = searchMatch.groupValues[2].trim()
            }
        }
        
        // Extract search type
        val searchTypes = mapOf(
            "regex" to "regex",
            "pattern" to "regex", 
            "text" to "text",
            "string" to "text",
            "word" to "word",
            "symbol" to "symbol",
            "function" to "function",
            "class" to "class",
            "variable" to "variable"
        )
        
        searchTypes.forEach { (keyword, type) ->
            if (promptLower.contains(keyword)) {
                params["search_type"] = type
                return@forEach
            }
        }
    }
    
    /**
     * Extract execution parameters
     */
    private fun extractExecutionParams(prompt: String, promptLower: String, params: MutableMap<String, Any>) {
        // Extract command/task
        val commandPattern = """(?:run|execute|build|test|compile|start|launch)\s+([^\s,]+(?:\s+[^\s,]+)*)""".toRegex(RegexOption.IGNORE_CASE)
        val commandMatch = commandPattern.find(prompt)
        if (commandMatch != null) {
            params["command"] = commandMatch.groupValues[1].trim()
        }
        
        // Extract execution context
        val contexts = listOf("test", "build", "compile", "deploy", "run", "debug", "profile")
        val foundContexts = contexts.filter { promptLower.contains(it) }
        if (foundContexts.isNotEmpty()) {
            params["context"] = foundContexts.first()
        }
    }
    
    /**
     * Generic parameter extraction
     */
    private fun extractGenericParams(prompt: String, promptLower: String, params: MutableMap<String, Any>) {
        // Extract quoted strings
        val quotedPattern = """["']([^"']+)["']""".toRegex()
        val quotedMatches = quotedPattern.findAll(prompt)
        val quotedValues = quotedMatches.map { it.groupValues[1] }.toList()
        if (quotedValues.isNotEmpty()) {
            params["quoted_values"] = quotedValues
        }
        
        // Extract numbers
        val numberPattern = """\b(\d+(?:\.\d+)?)\b""".toRegex()
        val numberMatches = numberPattern.findAll(prompt)
        val numbers = numberMatches.map { it.groupValues[1] }.toList()
        if (numbers.isNotEmpty()) {
            params["numbers"] = numbers
        }
        
        // Extract file paths (generic)
        val pathPattern = """[a-zA-Z0-9_\-/\\.]+\.[a-zA-Z0-9_]+""".toRegex()
        val pathMatches = pathPattern.findAll(prompt)
        val paths = pathMatches.map { it.value }.toList()
        if (paths.isNotEmpty()) {
            params["paths"] = paths
        }
    }
    
    /**
     * Record successful patterns for learning
     */
    private fun recordSuccessfulPattern(toolName: String, params: Map<String, Any>, prompt: String) {
        // This would integrate with a learning system
        // For now, just log the successful extraction
        println("[EXTRACTION] Successful extraction for $toolName: $params")
    }
    
    /**
     * Initialize default extraction patterns
     */
    private fun initializeDefaultPatterns() {
        // File operation patterns
        patternRegistry["read_file"] = listOf(
            ExtractionPattern(
                name = "file_path",
                regex = Pattern.compile("""(?:read|open|view)\s+(?:file\s+)?([^\s,]+)""", Pattern.CASE_INSENSITIVE),
                parameterMappings = mapOf(1 to "path")
            ),
            ExtractionPattern(
                name = "file_path_quoted",
                regex = Pattern.compile("""(?:read|open|view)\s+(?:file\s+)?["']([^"']+)["']""", Pattern.CASE_INSENSITIVE),
                parameterMappings = mapOf(1 to "path")
            )
        )
        
        patternRegistry["edit_file"] = listOf(
            ExtractionPattern(
                name = "edit_operation",
                regex = Pattern.compile("""(remove|delete|add|modify|edit|update)\s+(?:the\s+)?(\d+(?:st|nd|rd|th)|first|second|third|fourth|fifth)\s+([a-zA-Z_][a-zA-Z0-9_]*)""", Pattern.CASE_INSENSITIVE),
                parameterMappings = mapOf(
                    1 to "operation",
                    2 to "ordinal", 
                    3 to "target"
                )
            ),
            ExtractionPattern(
                name = "file_path_edit",
                regex = Pattern.compile("""(?:in|from|for)\s+(?:file\s+)?([^\s,]+)""", Pattern.CASE_INSENSITIVE),
                parameterMappings = mapOf(1 to "path")
            )
        )
    }
    
    /**
     * Initialize context rules
     */
    private fun initializeContextRules() {
        contextRules["edit_file"] = ContextRule(
            name = "file_edit_context",
            priority = 1.0,
            extractor = { prompt, promptLower ->
                val params = mutableMapOf<String, Any>()
                
                // Detect if this is a removal operation
                if (promptLower.contains("remove") || promptLower.contains("delete")) {
                    params["operation"] = "delete"
                }
                
                // Extract ordinal information
                val ordinals = mapOf(
                    "1st" to 1, "first" to 1,
                    "2nd" to 2, "second" to 2, 
                    "3rd" to 3, "third" to 3,
                    "4th" to 4, "fourth" to 4,
                    "5th" to 5, "fifth" to 5
                )
                
                ordinals.forEach { (ordinalText, ordinalNum) ->
                    if (promptLower.contains(ordinalText)) {
                        params["ordinal_number"] = ordinalNum
                        params["ordinal_text"] = ordinalText
                    }
                }
                
                params
            }
        )
    }
}

/**
 * Represents an extraction pattern
 */
@Serializable
data class ExtractionPattern(
    val name: String,
    @Contextual val regex: Pattern,
    val parameterMappings: Map<Int, String>,
    val priority: Double = 1.0
) {
    fun extract(text: String): Map<String, Any> {
        val results = mutableMapOf<String, Any>()
        val matcher = regex.matcher(text)
        if (matcher.find()) {
            parameterMappings.forEach { (groupIndex, paramName) ->
                val value = matcher.group(groupIndex)
                if (!value.isNullOrBlank()) {
                    results[paramName] = value
                }
            }
        }
        return results
    }
}

/**
 * Context-aware extraction rule
 */
data class ContextRule(
    val name: String,
    val priority: Double,
    val extractor: (String, String) -> Map<String, Any>
)
