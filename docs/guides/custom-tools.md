# Custom Tools Guide

## Overview

i2vision-mcp supports custom tool registration through the ToolRegistry system. This guide explains how to create and
register custom tools for the MCP server.

## Tool Handler Interface

All tools must implement the `ToolHandler` type alias:

```kotlin
typealias ToolHandler = suspend (Map<String, Any>) -> ToolResult
```

### ToolResult

```kotlin
data class ToolResult(
    val success: Boolean,
    val data: Map<String, Any>? = null,
    val error: String? = null
) {
    companion object {
        fun success(data: Map<String, Any>) = ToolResult(success = true, data = data)
        fun error(message: String) = ToolResult(success = false, error = message)
    }
}
```

## Creating a Custom Tool

### Step 1: Define the Tool Class

```kotlin
package com.i2vision.mcp.tools.custom

import com.i2vision.mcp.tools.ToolResult

class CustomAnalysisTool(private val projectRoot: String) {
    
    suspend fun analyzeCustomPattern(params: Map<String, Any>): ToolResult {
        val filePath = params["filePath"] as? String
        val pattern = params["pattern"] as? String
        
        if (filePath == null || pattern == null) {
            return ToolResult.error("Missing required parameters: filePath, pattern")
        }
        
        try {
            // Your custom analysis logic here
            val results = performAnalysis(filePath, pattern)
            
            return ToolResult.success(mapOf(
                "filePath" to filePath,
                "pattern" to pattern,
                "matches" to results.matches,
                "locations" to results.locations
            ))
        } catch (e: Exception) {
            return ToolResult.error("Analysis failed: ${e.message}")
        }
    }
    
    private fun performAnalysis(filePath: String, pattern: String): AnalysisResult {
        // Implementation
        return AnalysisResult(emptyList(), emptyList())
    }
    
    data class AnalysisResult(
        val matches: List<String>,
        val locations: List<Location>
    )
    
    data class Location(
        val line: Int,
        val column: Int,
        val context: String
    )
}
```

### Step 2: Register the Tool

```kotlin
package com.i2vision.mcp.server

import com.i2vision.mcp.tools.custom.CustomAnalysisTool

class McpServer(private val projectRoot: String) {
    
    private val toolRegistry = ToolRegistry()
    
    init {
        registerTools()
    }
    
    private fun registerTools() {
        val customTool = CustomAnalysisTool(projectRoot)
        
        toolRegistry.register(
            "analyze_custom_pattern",
            customTool::analyzeCustomPattern
        )
        
        // Register other tools...
    }
}
```

## Tool Description

To provide tool metadata for clients, implement the `ToolDescription` structure:

```kotlin
data class ToolDescription(
    val name: String,
    val description: String,
    val parameters: Map<String, ParameterSchema>
)

data class ParameterSchema(
    val type: String,
    val description: String,
    val required: Boolean = false
)
```

### Adding Tool Descriptions

```kotlin
fun listCustomTools(): List<ToolDescription> {
    return listOf(
        ToolDescription(
            name = "analyze_custom_pattern",
            description = "Analyzes a file for custom pattern matches",
            parameters = mapOf(
                "filePath" to ParameterSchema(
                    type = "string",
                    description = "Path to the file to analyze",
                    required = true
                ),
                "pattern" to ParameterSchema(
                    type = "string",
                    description = "Pattern to search for",
                    required = true
                )
            )
        )
    )
}
```

## Best Practices

### Error Handling

- Always validate required parameters
- Return meaningful error messages
- Use try-catch blocks for external operations
- Log errors for debugging

### Parameter Validation

```kotlin
suspend fun executeTool(params: Map<String, Any>): ToolResult {
    val requiredParams = listOf("filePath", "pattern")
    val missing = requiredParams.filter { it !in params }
    
    if (missing.isNotEmpty()) {
        return ToolResult.error("Missing required parameters: ${missing.joinToString()}")
    }
    
    // Type checking
    val filePath = params["filePath"] as? String
        ?: return ToolResult.error("Parameter 'filePath' must be a string")
    
    // Continue with execution...
}
```

### Async Operations

- Use `suspend` functions for I/O operations
- Leverage Kotlin coroutines for parallel processing
- Implement timeouts for long-running operations

```kotlin
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException

suspend fun executeTool(params: Map<String, Any>): ToolResult {
    return try {
        withTimeout(30_000) { // 30 second timeout
            performLongRunningOperation(params)
        }
    } catch (e: TimeoutCancellationException) {
        ToolResult.error("Operation timed out")
    } catch (e: Exception) {
        ToolResult.error("Operation failed: ${e.message}")
    }
}
```

### Resource Management

- Close file handles and connections
- Clean up temporary files
- Release acquired locks

```kotlin
suspend fun executeTool(params: Map<String, Any>): ToolResult {
    val file = File(params["filePath"] as String)
    
    return try {
        val content = file.readText()
        // Process content...
        ToolResult.success(result)
    } finally {
        // Cleanup if needed
    }
}
```

## Tool Categories

### Discovery Tools

Tools that analyze project structure and discover artifacts.

### Context Tools

Tools that provide context and information about code.

### Analysis Tools

Tools that perform code analysis and metrics calculation.

### Intelligence Tools

Tools that provide AI-powered insights and suggestions.

## Testing Custom Tools

### Unit Testing

```kotlin
import kotlinx.coroutines.test.runTest
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CustomAnalysisToolTest {
    
    @Test
    fun testAnalyzeCustomPattern() = runTest {
        val tool = CustomAnalysisTool("/test/project")
        val params = mapOf(
            "filePath" to "/test/file.kt",
            "pattern" to "test"
        )
        
        val result = tool.analyzeCustomPattern(params)
        
        assertTrue(result.success)
        assertEquals("/test/file.kt", result.data!!["filePath"])
    }
    
    @Test
    fun testMissingParameters() = runTest {
        val tool = CustomAnalysisTool("/test/project")
        val params = mapOf("filePath" to "/test/file.kt")
        
        val result = tool.analyzeCustomPattern(params)
        
        assertFalse(result.success)
        assertTrue(result.error!!.contains("Missing required parameters"))
    }
}
```

### Integration Testing

Test tools through the MCP server:

```kotlin
import kotlinx.coroutines.test.runTest

class McpServerIntegrationTest {
    
    @Test
    fun testCustomToolViaMcp() = runTest {
        val server = McpServer("/test/project")
        val request = """
            {
                "jsonrpc": "2.0",
                "method": "analyze_custom_pattern",
                "params": {
                    "filePath": "/test/file.kt",
                    "pattern": "test"
                },
                "id": 1
            }
        """.trimIndent()
        
        val response = server.handleJsonRpcRequest(request)
        val jsonResponse = Json.decodeFromString<JsonRpcResponse>(response)
        
        assertTrue(jsonResponse.result != null)
    }
}
```

## Advanced Topics

### Tool Composition

Combine multiple tools for complex operations:

```kotlin
suspend fun comprehensiveAnalysis(params: Map<String, Any>): ToolResult {
    val filePath = params["filePath"] as String
    
    // Use multiple tools
    val complexityResult = complexityTool.analyze(params)
    val patternResult = patternTool.analyze(params)
    
    return ToolResult.success(mapOf(
        "complexity" to complexityResult.data,
        "patterns" to patternResult.data
    ))
}
```

### Tool Chaining

Create workflows by chaining tool outputs:

```kotlin
suspend fun analysisWorkflow(params: Map<String, Any>): ToolResult {
    // Step 1: Discover structure
    val discoveryResult = discoveryTool.execute(params)
    
    if (!discoveryResult.success) {
        return discoveryResult
    }
    
    // Step 2: Analyze discovered files
    val analysisParams = params + ("files" to discoveryResult.data!!["files"])
    val analysisResult = analysisTool.execute(analysisParams)
    
    return analysisResult
}
```

### Tool Permissions

Implement permission checks for sensitive operations:

```kotlin
suspend fun executeTool(params: Map<String, Any>): ToolResult {
    val operation = params["operation"] as String
    
    if (isSensitiveOperation(operation) && !hasPermission(params)) {
        return ToolResult.error("Permission denied for operation: $operation")
    }
    
    // Execute operation...
}

private fun isSensitiveOperation(operation: String): Boolean {
    return operation in listOf("delete", "modify", "write")
}
```

## Examples

See existing tools for reference:

- `tools/discovery/DiscoveryTools.kt` - Discovery tool implementation
- `tools/context/ContextTools.kt` - Context tool implementation
- `tools/intelligence/IntelligenceTools.kt` - Intelligence tool implementation
