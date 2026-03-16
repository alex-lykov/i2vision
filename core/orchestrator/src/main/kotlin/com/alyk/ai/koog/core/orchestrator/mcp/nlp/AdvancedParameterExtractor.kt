package com.alyk.ai.koog.core.orchestrator.mcp.nlp

import kotlinx.serialization.Serializable

/**
 * Advanced Natural Language Processing for parameter extraction
 */

@Serializable
data class ExtractedParameter(
    val name: String,
    val value: String,
    val type: ParameterType,
    val confidence: Double,
    val context: String
)

@Serializable
enum class ParameterType {
    FILE_PATH,
    DIRECTORY,
    TEXT,
    NUMBER,
    BOOLEAN,
    CODE_SNIPPET,
    URL,
    EMAIL,
    DATE,
    JSON,
    LIST,
    UNKNOWN
}

class AdvancedParameterExtractor {
    
    /**
     * Extract parameters using NLP techniques
     */
    fun extractParameters(prompt: String, toolName: String, projectContext: Map<String, String>): List<ExtractedParameter> {
        val parameters = mutableListOf<ExtractedParameter>()
        
        // Extract based on tool-specific patterns
        when {
            toolName.contains("write") || toolName.contains("create") -> {
                parameters.addAll(extractFileCreationParameters(prompt, projectContext))
            }
            toolName.contains("search") || toolName.contains("find") -> {
                parameters.addAll(extractSearchParameters(prompt, projectContext))
            }
            toolName.contains("build") || toolName.contains("compile") -> {
                parameters.addAll(extractBuildParameters(prompt, projectContext))
            }
            toolName.contains("test") -> {
                parameters.addAll(extractTestParameters(prompt, projectContext))
            }
            toolName.contains("git") -> {
                parameters.addAll(extractGitParameters(prompt, projectContext))
            }
        }
        
        // Extract common parameters
        parameters.addAll(extractCommonParameters(prompt))
        
        // Apply NLP understanding
        return applyNLPUnderstanding(prompt, parameters)
    }
    
    private fun extractFileCreationParameters(prompt: String, projectContext: Map<String, String>): List<ExtractedParameter> {
        val parameters = mutableListOf<ExtractedParameter>()
        
        // Extract file path with context awareness
        val filePathPatterns = listOf(
            """(?:create|write|make|generate)\s+(?:file|code)\s+["']?([^"'\s]+)["']?""".toRegex(RegexOption.IGNORE_CASE),
            """(?:in|to|at)\s+["']?([^"'\s]+)["']?\s*(?:file|path)""".toRegex(RegexOption.IGNORE_CASE),
            """["']([^"']+\.(kt|java|js|ts|py|go|rs|json|yaml|yml|md|txt))["']""".toRegex(RegexOption.IGNORE_CASE)
        )
        
        filePathPatterns.forEach { pattern ->
            pattern.find(prompt)?.let { match ->
                val filePath = match.groupValues[1]
                parameters.add(ExtractedParameter(
                    name = "filePath",
                    value = resolvePath(filePath, projectContext),
                    type = ParameterType.FILE_PATH,
                    confidence = 0.9,
                    context = match.value
                ))
            }
        }
        
        // Extract content/code snippets
        val contentPattern = """(?:with|containing|content)\s+["']([^"']+)["']""".toRegex(RegexOption.IGNORE_CASE)
        contentPattern.find(prompt)?.let { match ->
            parameters.add(ExtractedParameter(
                name = "content",
                value = match.groupValues[1],
                type = ParameterType.TEXT,
                confidence = 0.8,
                context = match.value
            ))
        }
        
        return parameters
    }
    
    private fun extractSearchParameters(prompt: String, projectContext: Map<String, String>): List<ExtractedParameter> {
        val parameters = mutableListOf<ExtractedParameter>()
        
        // Extract search pattern
        val searchPatterns = listOf(
            """(?:search|find|look for)\s+["']?([^"'\s]+)["']?""".toRegex(RegexOption.IGNORE_CASE),
            """(?:pattern|regex)\s+["']([^"']+)["']""".toRegex(RegexOption.IGNORE_CASE)
        )
        
        searchPatterns.forEach { pattern ->
            pattern.find(prompt)?.let { match ->
                parameters.add(ExtractedParameter(
                    name = "pattern",
                    value = match.groupValues[1],
                    type = ParameterType.TEXT,
                    confidence = 0.9,
                    context = match.value
                ))
            }
        }
        
        // Extract directory/scope
        val directoryPatterns = listOf(
            """(?:in|within|under)\s+["']?([^"'\s]+)["']?\s*(?:directory|folder|path)?""".toRegex(RegexOption.IGNORE_CASE),
            """(?:scope|area)\s+["']?([^"'\s]+)["']?""".toRegex(RegexOption.IGNORE_CASE)
        )
        
        directoryPatterns.forEach { pattern ->
            pattern.find(prompt)?.let { match ->
                parameters.add(ExtractedParameter(
                    name = "directory",
                    value = resolvePath(match.groupValues[1], projectContext),
                    type = ParameterType.DIRECTORY,
                    confidence = 0.8,
                    context = match.value
                ))
            }
        }
        
        return parameters
    }
    
    private fun extractBuildParameters(prompt: String, projectContext: Map<String, String>): List<ExtractedParameter> {
        val parameters = mutableListOf<ExtractedParameter>()
        
        // Extract build task
        val taskPattern = """(?:build|run|compile|test|clean|install)\s+(?:task\s+)?["']?([^"'\s]+)["']?""".toRegex(RegexOption.IGNORE_CASE)
        taskPattern.find(prompt)?.let { match ->
            parameters.add(ExtractedParameter(
                name = "task",
                value = match.groupValues[1],
                type = ParameterType.TEXT,
                confidence = 0.9,
                context = match.value
            ))
        }
        
        // Extract configuration
        val configPattern = """(?:with|using|configuration)\s+["']?([^"'\s]+)["']?""".toRegex(RegexOption.IGNORE_CASE)
        configPattern.find(prompt)?.let { match ->
            parameters.add(ExtractedParameter(
                name = "configuration",
                value = match.groupValues[1],
                type = ParameterType.TEXT,
                confidence = 0.7,
                context = match.value
            ))
        }
        
        return parameters
    }
    
    private fun extractTestParameters(prompt: String, projectContext: Map<String, String>): List<ExtractedParameter> {
        val parameters = mutableListOf<ExtractedParameter>()
        
        // Extract test type
        val testTypePattern = """(?:run|execute)\s+(?:unit|integration|e2e|functional|performance)\s+test""".toRegex(RegexOption.IGNORE_CASE)
        testTypePattern.find(prompt)?.let { match ->
            val testType = when {
                match.value.contains("unit") -> "unit"
                match.value.contains("integration") -> "integration"
                match.value.contains("e2e") -> "e2e"
                match.value.contains("functional") -> "functional"
                match.value.contains("performance") -> "performance"
                else -> "unit"
            }
            parameters.add(ExtractedParameter(
                name = "testType",
                value = testType,
                type = ParameterType.TEXT,
                confidence = 0.9,
                context = match.value
            ))
        }
        
        // Extract specific test class/method
        val specificTestPattern = """(?:test|spec)\s+["']?([^"'\s]+)["']?""".toRegex(RegexOption.IGNORE_CASE)
        specificTestPattern.find(prompt)?.let { match ->
            parameters.add(ExtractedParameter(
                name = "target",
                value = match.groupValues[1],
                type = ParameterType.TEXT,
                confidence = 0.8,
                context = match.value
            ))
        }
        
        return parameters
    }
    
    private fun extractGitParameters(prompt: String, projectContext: Map<String, String>): List<ExtractedParameter> {
        val parameters = mutableListOf<ExtractedParameter>()
        
        // Extract git operation
        val operations = listOf("add", "commit", "push", "pull", "checkout", "branch", "merge", "status", "log", "diff")
        val operation = operations.find { prompt.lowercase().contains(it) }
        
        if (operation != null) {
            parameters.add(ExtractedParameter(
                name = "operation",
                value = operation,
                type = ParameterType.TEXT,
                confidence = 0.9,
                context = operation
            ))
        }
        
        // Extract commit message
        val commitPattern = """(?:message|m)\s+["']([^"']+)["']""".toRegex(RegexOption.IGNORE_CASE)
        commitPattern.find(prompt)?.let { match ->
            parameters.add(ExtractedParameter(
                name = "message",
                value = match.groupValues[1],
                type = ParameterType.TEXT,
                confidence = 0.8,
                context = match.value
            ))
        }
        
        // Extract branch name
        val branchPattern = """(?:branch|b)\s+["']?([^"'\s]+)["']?""".toRegex(RegexOption.IGNORE_CASE)
        branchPattern.find(prompt)?.let { match ->
            parameters.add(ExtractedParameter(
                name = "branch",
                value = match.groupValues[1],
                type = ParameterType.TEXT,
                confidence = 0.8,
                context = match.value
            ))
        }
        
        return parameters
    }
    
    private fun extractCommonParameters(prompt: String): List<ExtractedParameter> {
        val parameters = mutableListOf<ExtractedParameter>()
        
        // Extract URLs
        val urlPattern = """https?://[^\s]+""".toRegex()
        urlPattern.findAll(prompt).forEach { match ->
            parameters.add(ExtractedParameter(
                name = "url",
                value = match.value,
                type = ParameterType.URL,
                confidence = 0.95,
                context = match.value
            ))
        }
        
        // Extract numbers
        val numberPattern = """\b\d+\.?\d*\b""".toRegex()
        numberPattern.findAll(prompt).forEach { match ->
            parameters.add(ExtractedParameter(
                name = "number",
                value = match.value,
                type = ParameterType.NUMBER,
                confidence = 0.7,
                context = match.value
            ))
        }
        
        // Extract boolean indicators
        val booleanPatterns = mapOf(
            """(?:true|yes|on|enable|enabled)""".toRegex(RegexOption.IGNORE_CASE) to true,
            """(?:false|no|off|disable|disabled)""".toRegex(RegexOption.IGNORE_CASE) to false
        )
        
        booleanPatterns.forEach { (pattern, value) ->
            if (pattern.containsMatchIn(prompt)) {
                parameters.add(ExtractedParameter(
                    name = "boolean",
                    value = value.toString(),
                    type = ParameterType.BOOLEAN,
                    confidence = 0.8,
                    context = "boolean_indicator"
                ))
            }
        }
        
        return parameters
    }
    
    private fun applyNLPUnderstanding(prompt: String, parameters: List<ExtractedParameter>): List<ExtractedParameter> {
        // Apply semantic understanding to improve parameter extraction
        val enhancedParameters = parameters.toMutableList()
        
        // Contextual disambiguation
        enhancedParameters.forEach { param ->
            when (param.type) {
                ParameterType.FILE_PATH -> {
                    // Resolve relative paths based on project structure
                    if (param.value.startsWith("./") || !param.value.contains("/")) {
                        val resolvedPath = resolveRelativePath(param.value, prompt)
                        enhancedParameters[enhancedParameters.indexOf(param)] = param.copy(value = resolvedPath)
                    }
                }
                ParameterType.TEXT -> {
                    // Detect if text might be code
                    if (param.value.contains("{") || param.value.contains("fun ") || param.value.contains("class ")) {
                        val updatedParam = param.copy(
                            type = ParameterType.CODE_SNIPPET,
                            confidence = minOf(param.confidence + 0.2, 1.0)
                        )
                        enhancedParameters[enhancedParameters.indexOf(param)] = updatedParam
                    }
                }
                else -> { /* no change */ }
            }
        }
        
        return enhancedParameters.sortedByDescending { it.confidence }
    }
    
    private fun resolvePath(path: String, projectContext: Map<String, String>): String {
        return when {
            path.startsWith("./") -> path
            path.startsWith("/") -> path
            path.contains("src") -> path
            else -> {
                // Try to resolve relative to common project directories
                val commonDirs = listOf("src/main/kotlin", "src/test/kotlin", "src", ".", "docs")
                val resolved = commonDirs.firstNotNullOfOrNull { dir ->
                    val fullPath = if (dir == ".") path else "$dir/$path"
                    if (projectContext.containsKey(fullPath)) fullPath else null
                }
                resolved ?: path
            }
        }
    }
    
    private fun resolveRelativePath(path: String, prompt: String): String {
        // Simple heuristic to resolve relative paths based on prompt context
        return when {
            prompt.contains("test") -> "src/test/kotlin/$path"
            prompt.contains("main") -> "src/main/kotlin/$path"
            path.endsWith(".kt") || path.endsWith(".java") -> "src/main/kotlin/$path"
            else -> path
        }
    }
}
