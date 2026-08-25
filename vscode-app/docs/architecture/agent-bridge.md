# AgentBridge Architecture

## Overview

`AgentBridge` is the central hub that connects the VSCode extension with the agent core. It follows a **hub-and-spoke** design pattern where the main `AgentBridge` class delegates specialized functionality to spoke modules.

## Hub-and-Spoke Design

```
AgentBridge (Hub)
├── Diagnostics (Spoke)
├── LLMAdapter (Spoke)
├── SearchCache (Spoke)
├── SessionManagerBridge (Spoke)
├── ToolPipeline (Spoke)
├── CommandExecutor (Spoke)
└── LegacyTools (Spoke)
```

### Benefits

- **Separation of Concerns**: Each spoke handles a specific domain
- **Testability**: Spokes can be unit-tested in isolation
- **Maintainability**: Changes to one spoke don't affect others
- **Extensibility**: New spokes can be added without modifying the hub

## Spoke Modules

### 1. Diagnostics (`AgentBridge.Diagnostics.ts`)

**Purpose**: Centralized logging and structured data tracking

**Responsibilities**:
- Agent lifecycle logging
- Tool execution tracking
- Performance metrics
- Error reporting

**Key Methods**:
```typescript
log(message: string, level?: 'info' | 'warn' | 'error'): void
logToolExecution(toolName: string, args: Record<string, any>, startTime: number): void
emitProgress(event: ProgressEvent): void
```

### 2. LLMAdapter (`AgentBridge.LLMAdapter.ts`)

**Purpose**: Provider capability detection and model configuration

**Responsibilities**:
- Provider capability detection (streaming, tool calls, sessions)
- Token estimation
- Message trimming and summarization
- Response parsing (reasoning extraction, tool call detection)

**Key Methods**:
```typescript
estimateTokens(messages: LLMMessage[]): number
trimMessagesToBudget(maxTokens: number, messages: LLMMessage[]): LLMMessage[]
summarizeConversation(messages: LLMMessage[]): Promise<string>
extractReasoning(text: string): string
detectProxyMisbehavior(text: string): boolean
```

### 3. SearchCache (`AgentBridge.SearchCache.ts`)

**Purpose**: TTL-based search result caching

**Responsibilities**:
- Cache search patterns and results
- Prevent redundant searches
- Detect similar search patterns

**Key Methods**:
```typescript
recordSearchPattern(pattern: string, results: any[]): void
findSimilarSearch(currentPattern: string): string | null
getSearchResults(pattern: string): any[] | null
```

### 4. SessionManagerBridge (`AgentBridge.SessionManagerBridge.ts`)

**Purpose**: Session error classification and recovery

**Responsibilities**:
- Classify session errors (context limit, network, auth)
- Inject recovery prompts
- Manage pending messages

**Key Methods**:
```typescript
isSessionError(errorText: string): boolean
isContextExhaustionError(errorText: string): boolean
injectSimplifiedToolPrompt(toolCall: LLMToolCall): void
```

### 5. ToolPipeline (`AgentBridge.ToolPipeline.ts`)

**Purpose**: Tool execution, retry logic, rate limiting, and statistics

**Responsibilities**:
- Execute tools with rate limiting
- Retry failed tool calls
- Track tool execution statistics
- Validate tool results

**Key Methods**:
```typescript
executeTool(toolCall: LLMToolCall): Promise<{ result: string; error?: string }>
executeToolWithRetry(toolCall: LLMToolCall, maxRetries: number): Promise<...>
executeToolRateLimited(toolCall: LLMToolCall): Promise<...>
getToolExecutionStats(): ToolExecutionStats
```

### 6. CommandExecutor (`AgentBridge.CommandExecutor.ts`)

**Purpose**: Command validation, build/terminal/Git execution

**Responsibilities**:
- Execute terminal commands
- Run build commands
- Git operations
- Long-running process detection

**Key Methods**:
```typescript
executeCommand(command: string, workingDir: string): Promise<string>
getDefaultCompileCommand(): string
isBuildCommand(command: string): boolean
```

### 7. LegacyTools (`AgentBridge.LegacyTools.ts`)

**Purpose**: Legacy tool wrappers and name mapping

**Responsibilities**:
- Backward compatibility for old tool names
- Map legacy tool calls to new implementations

## AgentBridge Core Responsibilities

### 1. Agent Lifecycle

```typescript
async initialize(): Promise<void>
async process(userInput: string, currentFile?: string): Promise<AgentResponse>
async *executeAgentLoop(userInput: string, systemPrompt: string, options: AgentLoopOptions): AsyncGenerator<AgentChunk>
```

### 2. State Machine Integration

The agent uses a state machine for flow control:

```
Intent → Plan → Constraints → Sequence → Execute → Verify → Output
```

**State Transitions**:
- `Idle` → `Processing` → `ToolExecution` → `Verification` → `Idle`
- `Idle` → `Processing` → `Failed` → `Idle` (on error)

### 3. Tool Registry

Declarative tool management with layer-based filtering:

```typescript
toolRegistry: ToolRegistry = new ToolRegistry()

// Register tools by category
toolRegistry.registerAll(fileTools)
toolRegistry.registerAll(gitTools)
toolRegistry.registerAll(terminalTools)
toolRegistry.registerAll(editTools)
toolRegistry.registerAll(buildTools)

// Get tools filtered by VSLFC layer
const tools = toolRegistry.getLLMTools(currentLayer)
```

### 4. Session Management

Provider-specific session management:

```typescript
sessionManager: SessionManager

// Providers with session management
- 3D LLM: ProxySessionManager
- Mistral: ProxySessionManager

// Providers without session management
- Ollama: NullSessionManager
- DeepSeek: NullSessionManager
```

### 5. Context Management

Token budget tracking and message trimming:

```typescript
// Estimate tokens
const estimatedTokens = this.estimateTokens(messages)

// Trim if approaching limit (>80%)
if (estimatedTokens > this.config.model.contextLength * 0.8) {
  const trimmedMessages = await this.trimMessagesToBudget(messages, 0.75)
}
```

### 6. Prompt Building

Dynamic system prompt composition from provider rules:

```typescript
// New provider rules system
private buildSystemPrompt(variables: Record<string, string>): string {
  const providerId = this.config.model.provider
  const rules = this.rulesResolver.getRulesForProvider(providerId)
  
  return PromptBuilder.buildWithDefaults({
    providerId,
    templateVariables: variables,
    systemPromptTemplate: this.config.systemPromptTemplate,
    rules
  })
}
```

## Data Flow

### Request Flow

```
User Input (Webview)
    ↓
AgentBridge.process()
    ↓
Build System Prompt (with provider rules)
    ↓
Load Context (VSLFC layers)
    ↓
Call LLM (via LLMAdapter)
    ↓
Parse Response (tool calls, reasoning, text)
    ↓
Execute Tools (via ToolPipeline)
    ↓
Verify Results (build check, state machine)
    ↓
Stream Response (via Diagnostics)
```

### Tool Execution Flow

```
LLM Tool Call
    ↓
ToolPipeline.executeTool()
    ↓
Rate Limit Check
    ↓
ToolRegistry.resolve(toolName)
    ↓
Execute Tool Handler
    ↓
Validate Result
    ↓
Return to LLM
```

## Configuration

### Agent Config Structure

```typescript
interface AgentConfig {
  key: string
  agentType: string
  systemPromptTemplate: string
  templateVariables: Record<string, string>
  model: {
    id: string
    provider: string
    contextLength: number
    maxOutputTokens: number
    temperature: number
  }
  iterationSettings: {
    maxIterations: number
    maxConsecutiveToolCalls: number
  }
  toolSelection: {
    requiredToolsForModification: string[]
    toolTimeoutSeconds: number
  }
}
```

### Provider Rules

Provider-specific rules are loaded from `.vscode/i2-vision-provider-rules.json`:

```json
{
  "ollama": [
    {
      "id": "max_edits",
      "description": "Maximum apply_edits operations",
      "defaultValue": 50
    }
  ]
}
```

## Error Handling

### Retry Strategy

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
      await delay(backoff[attempt])
    }
  }
}
```

### Session Recovery

```typescript
if (sessionManager.isContextExhausted()) {
  await sessionManager.resetSession(agentId, 'token_limit')
  this.invalidatePromptCache()
}
```

## Performance Optimizations

### 1. File Read Cache

```typescript
private _fileReadCache: Map<string, { mtime: number; content: string }>

async readFileCached(filePath: string): Promise<string> {
  const cached = this._fileReadCache.get(filePath)
  if (cached && cached.mtime === await getMtime(filePath)) {
    return cached.content // Cache hit
  }
  // Cache miss - read and cache
}
```

### 2. Search Cache

```typescript
private _searchCache: Map<string, { results: any[], timestamp: number }>
private static readonly SEARCH_CACHE_TTL_MS = 5 * 60 * 1000 // 5 minutes
```

### 3. Prompt Caching

```typescript
private cachedSystemPrompt: string | undefined

private buildSystemPrompt(variables: Record<string, string>): string {
  if (this.cachedSystemPrompt) {
    return this.cachedSystemPrompt // Return cached
  }
  // Build and cache
  this.cachedSystemPrompt = PromptBuilder.build(...)
  return this.cachedSystemPrompt
}
```

## Testing

### Unit Tests

Test individual spokes in isolation:

```typescript
describe('ToolPipeline', () => {
  it('should retry failed tool calls', async () => {
    const pipeline = new ToolPipeline()
    // Test retry logic
  })
})
```

### Integration Tests

Test agent end-to-end:

```typescript
describe('AgentBridge', () => {
  it('should process user input and execute tools', async () => {
    const bridge = new AgentBridge(config, ...)
    const response = await bridge.process('Fix the bug in file.ts')
    expect(response.success).toBe(true)
  })
})
```

## Related Documentation

- [Tool System Architecture](tool-system.md)
- [Session Management](session-management.md)
- [Provider Rules](../concepts/provider-rules.md)
- [VSLFC Layers](../concepts/vslfc-layers.md)
