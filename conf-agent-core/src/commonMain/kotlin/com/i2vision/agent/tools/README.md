# ToolRegistry with i2vision and MCP Tools

This package implements a unified tool registry that combines i2vision tools, MCP tools, and file system operations for the i2-Vision agent architecture.

## Package Structure

```
com.i2vision.agent.tools/
├── I2VisionToolRegistry.kt        # Main registry builder
├── DiscoveryTools.kt              # Discovery/context tools
├── AnalysisTools.kt               # Validation/verbalization tools
├── FileSystemTools.kt             # File operation tools
├── ToolSafetyChecker.kt           # Safety validation
├── ToolCategories.kt              # Tool categories and permissions
└── README.md                      # This file
```

## Overview

The ToolRegistry provides a unified interface for agent tool execution:

```
ToolRegistry
├── Discovery Tools
│   ├── i2vision_discover        → Full VSLFC discovery
│   ├── i2vision_get_context     → Instant context for a file
│   └── i2vision_get_related     → Related files
├── Analysis Tools
│   ├── i2vision_validate_contracts → Contract validation
│   ├── i2vision_search_symbols     → Symbol search
│   └── i2vision_verbalize         → Symbol verbalization
├── File System Tools
│   ├── read_file                → Read file contents
│   ├── write_file               → Create/overwrite file
│   ├── edit_file                → Targeted edit
│   ├── list_directory           → List directory contents
│   └── regex_search             → Search with regex
└── Control Tools
    └── task_complete            → Signal completion
```

## Tool Categories

### FILE_SYSTEM
File manipulation operations:
- `read_file` - Read file contents
- `write_file` - Create or overwrite files
- `edit_file` - Make targeted edits (search/replace)
- `list_directory` - List directory contents
- `regex_search` - Search with regex patterns

**Availability:** All layers (write operations restricted)
**Safety:** Write operations require safety checks

### DISCOVERY
i2vision discovery and context:
- `i2vision_discover` - Full VSLFC discovery of a project
- `i2vision_get_context` - Instant context for a specific file
- `i2vision_get_related` - Find related files

**Availability:** All layers
**Caching:** Results cached for performance

### ANALYSIS
Code analysis and validation:
- `i2vision_validate_contracts` - Validate VSLFC contracts
- `i2vision_search_symbols` - Search for symbol definitions
- `i2vision_verbalize` - Natural language description of symbols

**Availability:** STRUCTURE, LOGIC, CODE layers
**Caching:** Some results cached

### CONTROL
Task control operations:
- `task_complete` - Signal task completion

**Availability:** All layers
**Special:** Signals task completion to agent loop

### MCP
MCP server tools:
- Configurable per deployment

**Availability:** Configurable per layer

## Tool Permissions by VSLFC Layer

| Tool | VISION | STRUCTURE | LOGIC | FLOW | CODE |
|------|--------|-----------|-------|------|------|
| `i2vision_discover` | ✅ | ✅ | ✅ | ✅ | ✅ |
| `i2vision_get_context` | ✅ | ✅ | ✅ | ✅ | ✅ |
| `i2vision_get_related` | ✅ | ✅ | ✅ | ✅ | ✅ |
| `i2vision_validate_contracts` | ❌ | ✅ | ✅ | ❌ | ✅ |
| `i2vision_search_symbols` | ❌ | ✅ | ✅ | ❌ | ✅ |
| `i2vision_verbalize` | ❌ | ✅ | ❌ | ❌ | ✅ |
| `read_file` | ✅ | ✅ | ✅ | ✅ | ✅ |
| `list_directory` | ✅ | ✅ | ✅ | ✅ | ✅ |
| `regex_search` | ✅ | ✅ | ✅ | ✅ | ✅ |
| `write_file` | ❌ | ❌ | ✅ | ✅ | ✅ |
| `edit_file` | ❌ | ❌ | ✅ | ✅ | ✅ |
| `task_complete` | ✅ | ✅ | ✅ | ✅ | ✅ |

## Usage

### Create Tool Registry

```kotlin
val registry = I2VisionToolRegistry(
    config = agentConfig,
    layer = VslfcLayer.CODE,
    workspaceRoot = "/path/to/project",
    instantContext = instantContextProvider,
    discoveryEngine = discoveryEngine,
    discoveryCache = discoveryCache
)

val tools = registry.build()

// Execute a tool
val result = tools.execute("read_file", mapOf("path" to "src/main.kt"))
println(result.output)
```

### Using Tool Components

```kotlin
// Create discovery tools
val discoveryTools = DiscoveryTools(
    instantContext = instantContextProvider,
    discoveryEngine = discoveryEngine,
    discoveryCache = discoveryCache
)

// Get a specific tool
val discoverTool = discoveryTools.discover()

// Execute
val result = discoverTool.handler(mapOf(
    "path" to "/path/to/project",
    "intent" to "quick_overview"
))
```

### Safety Checking

```kotlin
val safetyChecker = ToolSafetyChecker(
    workspaceRoot = "/path/to/project",
    config = safetyConfig
)

// Check if write is allowed
val result = safetyChecker.checkWrite("src/main.kt")
if (result.allowed) {
    // Proceed with write
} else {
    println("Write blocked: ${result.reason}")
}

// Check with severity
if (result.hasWarning()) {
    println("Warning: ${result.reason}")
}
```

## Tool Definitions

### i2vision_discover

Full VSLFC discovery of a project or module.

**Parameters:**
- `path` (string, required) - Project or module path
- `intent` (string, optional) - Discovery intent:
  - `full_discovery` - Complete analysis
  - `refactoring_analysis` - Focus on refactoring opportunities
  - `quick_overview` - Fast overview (default)
  - `architecture_audit` - Architecture-focused
  - `flow_mapping` - Data/control flow mapping
  - `documentation_generation` - Documentation-focused
- `force` (boolean, optional) - Force re-discovery

**Caching:** 1 hour TTL

**Example:**
```kotlin
val result = tools.execute("i2vision_discover", mapOf(
    "path" to "/path/to/project",
    "intent" to "quick_overview",
    "force" to false
))
```

### i2vision_get_context

Get architectural context for a specific file.

**Parameters:**
- `file` (string, required) - File path relative to workspace
- `task` (string, optional) - Task type:
  - `debug` - Debugging context
  - `refactor` - Refactoring context
  - `add_feature` - Feature addition context
  - `fix_bug` - Bug fix context
  - `optimize` - Optimization context
  - `discovery` - General discovery (default)

**Caching:** 5 minutes TTL

**Example:**
```kotlin
val result = tools.execute("i2vision_get_context", mapOf(
    "file" to "src/main/kotlin/MyClass.kt",
    "task" to "refactor"
))
```

### i2vision_get_related

Find files related to a given file.

**Parameters:**
- `file` (string, required) - File path
- `relationType` (string, optional) - Relation type:
  - `imports` - Files this file imports
  - `imported_by` - Files that import this file
  - `calls` - Files this file calls
  - `called_by` - Files that call this file
  - `all` - All relations (default)

**Caching:** 10 minutes TTL

**Example:**
```kotlin
val result = tools.execute("i2vision_get_related", mapOf(
    "file" to "src/main/kotlin/MyClass.kt",
    "relationType" to "imported_by"
))
```

### i2vision_validate_contracts

Validate VSLFC contracts for a file or project.

**Parameters:**
- `path` (string, required) - File or directory path
- `layer` (string, optional) - Specific layer to validate
- `strict` (boolean, optional) - Enable strict validation

**Example:**
```kotlin
val result = tools.execute("i2vision_validate_contracts", mapOf(
    "path" to "src/main/kotlin",
    "layer" to "CODE",
    "strict" to true
))
```

### i2vision_search_symbols

Search for symbol definitions.

**Parameters:**
- `name` (string, required) - Symbol name or pattern (supports * wildcard)
- `kind` (string, optional) - Symbol kind:
  - `class`, `interface`, `function`, `property`, `method`, `field`, `all`
- `layer` (string, optional) - VSLFC layer filter
- `maxResults` (integer, optional) - Maximum results (default: 50)

**Example:**
```kotlin
val result = tools.execute("i2vision_search_symbols", mapOf(
    "name" to "User*",
    "kind" to "class",
    "layer" to "LOGIC",
    "maxResults" to 20
))
```

### i2vision_verbalize

Get natural language description of a symbol.

**Parameters:**
- `symbol` (string, required) - Symbol name
- `file` (string, optional) - File containing the symbol
- `detail` (string, optional) - Detail level:
  - `brief` - Short description
  - `normal` - Standard description (default)
  - `detailed` - Comprehensive description

**Caching:** 10 minutes TTL

**Example:**
```kotlin
val result = tools.execute("i2vision_verbalize", mapOf(
    "symbol" to "UserService",
    "file" to "src/main/kotlin/UserService.kt",
    "detail" to "normal"
))
```

### read_file

Read file contents.

**Parameters:**
- `path` (string, required) - File path
- `startLine` (integer, optional) - Start line (1-based)
- `endLine` (integer, optional) - End line (1-based)

**Example:**
```kotlin
val result = tools.execute("read_file", mapOf(
    "path" to "src/main.kt",
    "startLine" to 1,
    "endLine" to 50
))
```

### write_file

Create or overwrite a file.

**Parameters:**
- `path` (string, required) - File path
- `content` (string, required) - File content

**Safety:** Validated by ToolSafetyChecker

**Example:**
```kotlin
val result = tools.execute("write_file", mapOf(
    "path" to "src/test.kt",
    "content" to "fun test() { ... }"
))
```

### edit_file

Make targeted edits using search and replace.

**Parameters:**
- `path` (string, required) - File path
- `search` (string, required) - Text to search for (must match exactly once)
- `replace` (string, required) - Replacement text
- `contextLines` (integer, optional) - Context lines for verification

**Safety:** Validated by ToolSafetyChecker

**Example:**
```kotlin
val result = tools.execute("edit_file", mapOf(
    "path" to "src/main.kt",
    "search" to "fun oldName() {",
    "replace" to "fun newName() {",
    "contextLines" to 3
))
```

### list_directory

List directory contents.

**Parameters:**
- `path` (string, required) - Directory path
- `recursive` (boolean, optional) - Recursive listing
- `pattern` (string, optional) - File pattern filter (e.g., *.kt)

**Example:**
```kotlin
val result = tools.execute("list_directory", mapOf(
    "path" to "src/main/kotlin",
    "recursive" to true,
    "pattern" to "*.kt"
))
```

### regex_search

Search for regex pattern across files.

**Parameters:**
- `pattern` (string, required) - Regex pattern
- `path` (string, required) - Directory or file to search
- `filePattern` (string, optional) - File pattern filter
- `maxResults` (integer, optional) - Maximum results (default: 50)
- `contextLines` (integer, optional) - Context lines around matches

**Example:**
```kotlin
val result = tools.execute("regex_search", mapOf(
    "pattern" to "fun\s+\w+\(",
    "path" to "src/main/kotlin",
    "filePattern" to "*.kt",
    "maxResults" to 20,
    "contextLines" to 2
))
```

### task_complete

Signal task completion.

**Parameters:**
- `message` (string, required) - Completion message
- `outcome` (string, optional) - Outcome:
  - `success` - Task completed successfully (default)
  - `partial` - Partially completed
  - `needs_followup` - Requires additional work

**Example:**
```kotlin
val result = tools.execute("task_complete", mapOf(
    "message" to "Refactoring complete. All functions updated.",
    "outcome" to "success"
))
```

## Safety System

### ToolSafetyChecker

Validates operations before execution:

**Rules:**
1. **Workspace Boundary** - All writes must be within workspace root
2. **Generated Paths** - Block writes to build/, target/, dist/, etc.
3. **Design Files** - Block writes to .vision-ai/ (managed by i2vision)
4. **System Files** - Block writes to protected system files
5. **Hidden Files** - Warn on writes to hidden files

**Usage:**
```kotlin
val safetyChecker = ToolSafetyChecker(
    workspaceRoot = "/path/to/workspace",
    config = safetyConfig
)

val result = safetyChecker.checkWrite("src/main.kt")
when {
    result.allowed && !result.hasWarning() -> {
        // Safe to proceed
    }
    result.hasWarning() -> {
        // Proceed with caution
        println("Warning: ${result.reason}")
    }
    !result.allowed -> {
        // Blocked
        println("Blocked: ${result.reason}")
    }
}
```

### SafetyConfig

Configuration for safety checks:

```kotlin
data class SafetyConfig(
    val blockGeneratedPaths: List<String> = listOf(
        "build", "target", "dist", "out", ".gradle", "node_modules"
    ),
    val protectedPaths: List<String> = listOf(
        ".git", ".svn", ".hg"
    ),
    val allowHiddenFileWrites: Boolean = false,
    val maxFileSizeBytes: Long = 10 * 1024 * 1024  // 10MB
)
```

## Caching

### Discovery Cache

Discovery results are cached to avoid repeated expensive analysis:

```kotlin
interface DiscoveryCache {
    fun get(path: String): DiscoveryResult?
    fun put(path: String, result: DiscoveryResult)
    fun clear(path: String)
    fun clearAll()
}
```

**Cache TTL:**
- `i2vision_discover`: 1 hour
- `i2vision_get_context`: 5 minutes
- `i2vision_get_related`: 10 minutes
- `i2vision_verbalize`: 10 minutes

## Integration with Task-14

The `ToolExecutor` from task-14 uses this registry:

```kotlin
class ToolExecutor(
    private val toolRegistry: ToolRegistry  // From task-16
) {
    suspend fun execute(
        toolName: String,
        args: Map<String, Any>,
        timeoutSeconds: Long
    ): ToolExecutionResult {
        val result = toolRegistry.execute(toolName, args)
        
        return ToolExecutionResult(
            toolName = toolName,
            args = args,
            isSuccess = result.success,
            output = result.output,
            error = result.error,
            signal = result.signal?.name
        )
    }
}
```

## Tool Result Format

```kotlin
data class ToolResult(
    val success: Boolean,
    val output: String? = null,
    val error: String? = null,
    val signal: ToolSignal = ToolSignal.NONE,
    val metadata: Map<String, String> = emptyMap()
)
```

**Signals:**
- `NONE` - No special signal
- `TASK_STOP` - Signal task completion

## Error Handling

### Tool Execution Errors

```kotlin
// Tool not found
ToolResult.failure("Unknown tool: invalid_tool_name")

// Parameter validation error
ToolResult.failure("path is required")

// Operation failed
ToolResult.failure("File not found: src/main.kt")

// Safety check failed
ToolResult.failure("Write blocked: Cannot write outside workspace")
```

### Exception Handling

Tools catch exceptions and return structured errors:

```kotlin
handler = { args ->
    try {
        // Tool logic
        ToolResult.success(output = "Success")
    } catch (e: Exception) {
        ToolResult.failure(error = "Operation failed: ${e.message}")
    }
}
```

## Testing

### Mock Tool Registry

```kotlin
class MockToolRegistry : ToolRegistry {
    override fun execute(toolName: String, args: Map<String, Any>): ToolResult {
        return when (toolName) {
            "read_file" -> ToolResult.success("Mock file content")
            "task_complete" -> ToolResult.success("Done", signal = ToolSignal.TASK_STOP)
            else -> ToolResult.failure("Unknown tool: $toolName")
        }
    }
}
```

### Test Safety Checker

```kotlin
@Test
fun testSafetyCheckerBlocksOutsideWrites() {
    val checker = ToolSafetyChecker("/workspace", SafetyConfig())
    
    val result = checker.checkWrite("/etc/passwd")
    
    assertFalse(result.allowed)
    assertTrue(result.reason!!.contains("outside workspace"))
}
```

## Integration Points

| Component | Used By | From Task |
|-----------|---------|-----------|
| **ToolRegistry** | ToolExecutor | task-14 |
| **DiscoveryTools** | I2VisionToolRegistry | task-16 |
| **AnalysisTools** | I2VisionToolRegistry | task-16 |
| **FileSystemTools** | I2VisionToolRegistry | task-16 |
| **ToolSafetyChecker** | FileSystemTools | task-16 |

## Next Steps

1. **task-18**: Implement LocalTypeScriptAgent (uses same tool registry)
2. **task-22**: Implement JSON-RPC Server (exposes tools to clients)
3. **task-24**: Integrate MCP Tools (extend registry with MCP tools)

## References

- [Koog Framework Tools](https://github.com/JetBrains/koog)
- [I2Vision Instant Context](../../instant/README.md)
- [I2Vision Discovery](../../discover/README.md)
- [Koog GraphStrategy](../koog/README.md)
