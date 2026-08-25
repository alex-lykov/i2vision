# Tool System Architecture

## Overview

The tool system provides a declarative, extensible mechanism for AI agents to interact with the development environment. Tools are organized in a registry with VSLFC layer-based filtering.

## Architecture

```
ToolRegistry (Central Registry)
├── File Tools (read_file, write_file, search_files, list_directory)
├── Edit Tools (apply_edits, get_file_context)
├── Terminal Tools (run_terminal, run_build, check_terminal_status)
├── Git Tools (git_status, git_diff, git_commit)
└── Build Tools (run_build, check_build_status)
    ↓
VSLFC Layer Filter
    ↓
LLM-visible Tools
```

## Tool Registry

### Registration

Tools are registered declaratively by category:

```typescript
toolRegistry: ToolRegistry = new ToolRegistry()

// Register tools by category
toolRegistry.registerAll(fileTools)
toolRegistry.registerAll(gitTools)
toolRegistry.registerAll(terminalTools)
toolRegistry.registerAll(editTools)
toolRegistry.registerAll(buildTools)
```

### Tool Definition

Each tool is defined with metadata:

```typescript
interface LLMTool {
  name: string
  description: string
  parameters: {
    type: 'object'
    properties: Record<string, ParameterSchema>
    required: string[]
  }
  layer?: VslfcLayer // Optional: restrict to specific layer
}
```

### Tool Resolution

```typescript
// Get all tools
const allTools = toolRegistry.getLLMTools()

// Get tools filtered by VSLFC layer
const codeTools = toolRegistry.getLLMTools('code')

// Get tools by name
const specificTools = toolRegistry.getLLMToolsByName(['read_file', 'write_file'])
```

## VSLFC Layer Filtering

### Layers

The VSLFC architecture defines 5 layers:

| Layer | Purpose | Tool Access |
|-------|---------|-------------|
| **Vision** | UI/UX analysis | Read-only tools, screenshot analysis |
| **Structure** | Architecture analysis | Read tools, module discovery |
| **Logic** | Business logic | Read/write tools, test execution |
| **Flow** | Process orchestration | Terminal, build, Git tools |
| **Code** | Implementation | All tools including apply_edits |

### Layer-Based Filtering

```typescript
// Get tools for current layer
const tools = toolRegistry.getLLMTools(currentLayer)

// Example: Code layer gets all tools
//          Vision layer gets only read tools
```

### Tool Filter Modes

Additional filtering beyond layers:

```typescript
type ToolFilter = 'all' | 'fix_only' | 'read_only' | 'action_only'

// fix_only: apply_edits, read_file, write_file, get_file_context
// read_only: read_file, search_files, list_directory
// action_only: apply_edits, write_file, run_terminal, run_build (NO reads)
```

## Built-in Tools

### File Tools

| Tool | Description | Parameters | Layer |
|------|-------------|------------|-------|
| `read_file` | Read file content | `path: string` | All |
| `write_file` | Write file content | `path: string, content: string` | Code+ |
| `search_files` | Search files by pattern | `pattern: string, path?: string` | All |
| `list_directory` | List directory contents | `path: string` | All |

### Edit Tools

| Tool | Description | Parameters | Layer |
|------|-------------|------------|-------|
| `apply_edits` | Apply multiple text edits | `edits: Edit[]` | Code |
| `get_file_context` | Get file with surrounding context | `path: string` | Logic+ |

### Terminal Tools

| Tool | Description | Parameters | Layer |
|------|-------------|------------|-------|
| `run_terminal` | Execute terminal command | `command: string, workingDir?: string` | Flow+ |
| `run_build` | Run Gradle build | `task: string` | Flow+ |
| `check_terminal_status` | Check if terminal is running | `terminalId: string` | Flow+ |

### Git Tools

| Tool | Description | Parameters | Layer |
|------|-------------|------------|-------|
| `git_status` | Get Git repository status | - | Flow+ |
| `git_diff` | Get Git diff | `path?: string` | Flow+ |
| `git_commit` | Commit changes | `message: string` | Flow+ |

### Build Tools

| Tool | Description | Parameters | Layer |
|------|-------------|------------|-------|
| `run_build` | Run Gradle build task | `task: string` | Flow+ |
| `check_build_status` | Check last build status | - | Flow+ |

## Custom Tools

### YAML Configuration

Custom tools can be defined via YAML:

```yaml
# .vscode/i2vision/tools/custom-tools.yaml
tools:
  - name: run_tests
    description: Run unit tests for a module
    parameters:
      type: object
      properties:
        module:
          type: string
          description: Module path (e.g., :app:core)
      required:
        - module
    handler:
      type: terminal
      command: ./gradlew ${module}:test
```

### JavaScript Plugins

Custom tools can be implemented as JavaScript plugins:

```javascript
// tools/my-custom-tool.js
module.exports = {
  name: 'analyze_dependencies',
  description: 'Analyze module dependencies',
  parameters: { ... },
  async execute(args, context) {
    // Custom implementation
    return { result: '...' }
  }
}
```

### Plugin Loading

```typescript
// Load YAML tool configurations
toolConfigLoader = new ToolConfigLoader(toolRegistry, outputChannel)
await toolConfigLoader.loadConfig(workspaceRoot)

// Load JavaScript plugins
pluginLoader = new CustomToolPluginLoader(workspaceRoot, outputChannel)
const plugins = await pluginLoader.loadPlugins()
```

## Tool Execution Pipeline

### Execution Flow

```
LLM Tool Call
    ↓
ToolPipeline.executeTool()
    ↓
Rate Limit Check (max 3 concurrent)
    ↓
ToolRegistry.resolve(toolName)
    ↓
Execute Tool Handler
    ↓
Validate Result (max length, format)
    ↓
Compress Result (if too large)
    ↓
Return to LLM
```

### Rate Limiting

```typescript
private static readonly MAX_CONCURRENT_TOOLS = 3
private _activeToolExecutions: number = 0

async executeTool(toolCall: LLMToolCall): Promise<...> {
  while (this._activeToolExecutions >= MAX_CONCURRENT_TOOLS) {
    await waitForSlot()
  }
  this._activeToolExecutions++
  try {
    return await execute(toolCall)
  } finally {
    this._activeToolExecutions--
  }
}
```

### Retry Logic

```typescript
async executeToolWithRetry(
  toolCall: LLMToolCall,
  maxRetries: number = 3
): Promise<{ result: string; error?: string }> {
  for (let attempt = 0; attempt < maxRetries; attempt++) {
    try {
      return await this.executeTool(toolCall)
    } catch (error) {
      if (attempt === maxRetries - 1) throw error
      await delay(backoff[attempt]) // Exponential backoff
    }
  }
}
```

### Result Validation

```typescript
private static readonly MAX_TOOL_RESULT_LENGTH = 2000

validateToolResult(result: { result: string; error?: string }): { result: string; error?: string } {
  if (result.result.length > MAX_TOOL_RESULT_LENGTH) {
    result.result = result.result.substring(0, MAX_TOOL_RESULT_LENGTH) + '... (truncated)'
  }
  return result
}
```

### Result Compression

For large results, compression is applied:

```typescript
toolCompressor: ToolResultCompressor = new ToolResultCompressor()

async compressResult(result: string): Promise<string> {
  // Remove whitespace, summarize repetitive sections
  return compressed
}
```

## Tool Execution Statistics

### Tracking

```typescript
interface ToolExecutionStats {
  totalToolsExecuted: number
  errorRate: number
  recentTools: Array<{ toolName: string; success: boolean; durationMs?: number }>
  consecutiveErrors: number
  queueLength: number
  activeExecutions: number
}
```

### Monitoring

```typescript
getToolExecutionStats(): ToolExecutionStats {
  return {
    totalToolsExecuted: this.totalExecuted,
    errorRate: this.errors / this.totalExecuted,
    recentTools: this.recentExecutions,
    consecutiveErrors: this.consecutiveErrors,
    queueLength: this.queue.length,
    activeExecutions: this._activeToolExecutions
  }
}
```

## Tool Call History

### Pattern Detection

```typescript
_toolCallHistory: Array<{
  toolName: string
  iteration: number
  timestamp?: number
  hasError?: boolean
  resultLength?: number
}>

// Detect repeated failed tool calls
const recentFailures = this._toolCallHistory
  .filter(h => h.hasError && h.iteration > currentIteration - 3)
  
if (recentFailures.length >= 3) {
  // Trigger strategy rotation
  this._strategyRotationCount++
}
```

## Error Handling

### Tool Execution Errors

```typescript
try {
  const result = await tool.execute(args, context)
  return { result: result, error: undefined }
} catch (error: any) {
  return { result: '', error: error.message }
}
```

### Timeout Handling

```typescript
private static readonly TOOL_TIMEOUT_SECONDS = 30

async executeWithTimeout(toolCall: LLMToolCall): Promise<...> {
  const timeout = setTimeout(() => {
    throw new Error(`Tool execution timeout: ${toolCall.name}`)
  }, TOOL_TIMEOUT_SECONDS * 1000)
  
  try {
    return await tool.execute(toolCall.args)
  } finally {
    clearTimeout(timeout)
  }
}
```

## Security Considerations

### Command Validation

```typescript
// Block dangerous commands
const blockedPatterns = ['rm -rf', 'sudo', 'curl | bash']

isCommandSafe(command: string): boolean {
  return !blockedPatterns.some(pattern => command.includes(pattern))
}
```

### Path Validation

```typescript
// Ensure paths are within workspace
resolvePath(relativePath: string): string {
  const resolved = path.join(this.workspaceRoot, relativePath)
  if (!resolved.startsWith(this.workspaceRoot)) {
    throw new Error('Path traversal detected')
  }
  return resolved
}
```

## Testing

### Unit Tests

```typescript
describe('ToolRegistry', () => {
  it('should filter tools by layer', () => {
    const registry = new ToolRegistry()
    registry.registerAll(fileTools)
    
    const codeTools = registry.getLLMTools('code')
    const visionTools = registry.getLLMTools('vision')
    
    expect(codeTools.length).toBeGreaterThan(visionTools.length)
  })
})
```

### Integration Tests

```typescript
describe('ToolPipeline', () => {
  it('should execute tool with retry', async () => {
    const pipeline = new ToolPipeline()
    let callCount = 0
    
    const mockTool = {
      execute: () => {
        callCount++
        if (callCount < 3) throw new Error('Simulated failure')
        return { result: 'success' }
      }
    }
    
    const result = await pipeline.executeToolWithRetry(mockTool, 3)
    expect(result.result).toBe('success')
    expect(callCount).toBe(3)
  })
})
```

## Related Documentation

- [AgentBridge Architecture](agent-bridge.md)
- [VSLFC Layers](../concepts/vslfc-layers.md)
- [Custom Tools Guide](../guides/custom-tools.md)
- [Tools Reference](../reference/tools-reference.md)
