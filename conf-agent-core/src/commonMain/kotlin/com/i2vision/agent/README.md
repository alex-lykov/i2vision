# I2Vision Agent Core Interfaces

This package contains the core interfaces and data models for the i2-Vision agent architecture.

## Package Structure

```
com.i2vision.agent/
├── I2VisionAgent.kt              # Core agent interface
├── I2VisionAgentProvider.kt      # Agent factory interface
├── VslfcLayer.kt                 # VSLFC layer enum
├── AgentCapabilities.kt          # Capability declaration
├── AgentConfig.kt                # Runtime configuration
├── AgentContext.kt               # Request context
├── AgentRequest.kt               # Request model
├── AgentResponse.kt              # Response model
├── AgentChunk.kt                 # Streaming chunk types
├── koog/                         # Koog GraphStrategy implementation
│   ├── I2VisionStrategyGraph.kt  # Main graph definition
│   ├── StrategyNodes.kt          # Node implementations
│   ├── AgentState.kt             # State management
│   ├── I2VisionKoogAgent.kt      # Agent implementation
│   └── prompt/                   # Prompt template system
├── tools/                        # Tool registry implementation
│   ├── I2VisionToolRegistry.kt   # Main registry builder
│   ├── DiscoveryTools.kt         # Discovery tools
│   ├── AnalysisTools.kt          # Analysis tools
│   ├── FileSystemTools.kt        # File system tools
│   └── ToolSafetyChecker.kt      # Safety validation
└── README.md                     # This file
```

## Core Interfaces

### I2VisionAgent

The primary contract for all i2vision agents:

```kotlin
interface I2VisionAgent {
    val id: String
    val layer: VslfcLayer
    val displayName: String
    val capabilities: AgentCapabilities
    
    suspend fun process(request: AgentRequest): AgentResponse
    fun processStreaming(request: AgentRequest): Flow<AgentChunk>
    suspend fun cancel(requestId: String): Boolean
    fun getConfig(): AgentConfig
    suspend fun updateConfig(overrides: AgentConfigOverrides): AgentConfig
    suspend fun dispose()
}
```

**Implementations:**
- ✅ `I2VisionKoogAgent` - Kotlin/JVM with Koog GraphStrategy (task-14)
- ⏳ `LocalTypeScriptAgent` - TypeScript for VS Code development (task-18)
- ⏳ `BridgedAgent` - TypeScript wrapper with JSON-RPC to Kotlin backend

### I2VisionAgentProvider

Factory interface for creating and managing agents:

```kotlin
interface I2VisionAgentProvider {
    val id: String
    val type: String
    val displayName: String
    
    suspend fun ping(): Boolean
    suspend fun listConfigurations(): List<AgentConfigSummary>
    suspend fun createAgent(layer: VslfcLayer, config: AgentConfig?): I2VisionAgent
    suspend fun createAgent(layer: VslfcLayer, configurationId: String): I2VisionAgent
    suspend fun dispose()
}
```

**Implementations:**
- ⏳ `LocalAgentProvider` - Creates local TypeScript agents
- ⏳ `KotlinAgentProvider` - Creates Koog-based Kotlin agents
- ⏳ `BridgedAgentProvider` - Creates agents communicating via JSON-RPC

## Data Models

### Request/Response

- **AgentRequest** - Task request with context and configuration
- **AgentResponse** - Complete response after task execution
- **AgentContext** - Rich context from client environment
- **AgentConfig** - Runtime configuration
- **AgentConfigOverrides** - Per-request configuration overrides

### Streaming

- **AgentChunk** (sealed class) - Streaming events:
  - `Reasoning` - Agent thinking
  - `ToolCallStarted` - Tool about to be called
  - `ToolCallCompleted` - Tool execution result
  - `Text` - Response text streaming
  - `Progress` - Progress updates
  - `Done` - Task completion
  - `ChunkError` - Error occurred

### Capabilities & Configuration

- **AgentCapabilities** - What an agent can do
- **AgentConfigSummary** - Lightweight configuration metadata
- **VslfcLayer** - VSLFC layer enum (VISION, STRUCTURE, LOGIC, FLOW, CODE)
- **ToolInfo** - Tool metadata
- **ToolCategory** - Tool categorization

## Usage Examples

### Synchronous Processing

```kotlin
val agent = I2VisionKoogAgent.fromDefault(
    layer = VslfcLayer.CODE,
    modelProvider = myModelProvider,
    toolRegistry = myToolRegistry,
    instantContextProvider = myInstantContextProvider,
    discoveryCache = myDiscoveryCache
)

val request = AgentRequest(
    id = AgentRequest.generateId(),
    task = "Refactor this function to use coroutines",
    context = AgentContext(
        workspaceRoot = "/path/to/project",
        currentFile = "/path/to/project/src/main/kotlin/MyClass.kt",
        sessionId = "session-123"
    )
)

val response = agent.process(request)
println("Outcome: ${response.outcome}")
println("Response: ${response.finalText}")
```

### Streaming Processing

```kotlin
agent.processStreaming(request).collect { chunk ->
    when (chunk) {
        is AgentChunk.Reasoning -> ui.appendReasoning(chunk.text)
        is AgentChunk.ToolCallStarted -> ui.showToolCall(chunk.toolName, chunk.args)
        is AgentChunk.ToolCallCompleted -> ui.hideToolCall(chunk.toolName, chunk.output)
        is AgentChunk.Text -> ui.appendText(chunk.text)
        is AgentChunk.Progress -> ui.updateProgress(chunk.iteration, chunk.maxIterations)
        is AgentChunk.Done -> ui.markComplete(chunk.outcome, chunk.finalText)
        is AgentChunk.ChunkError -> ui.showError(chunk.error.message)
    }
}
```

### Provider Switching

```kotlin
// Development: Use local TypeScript agent
val localProvider = LocalAgentProvider()
val localAgent = localProvider.createAgent(VslfcLayer.CODE)

// Production: Use Kotlin agent via JSON-RPC
val kotlinProvider = KotlinAgentProvider()
val kotlinAgent = kotlinProvider.createAgent(VslfcLayer.CODE)

// Both implement the same interface!
processTask(agent) // Works with either implementation
```

### Configuration Overrides

```kotlin
val baseConfig = AgentConfig.default()
val overrides = AgentConfigOverrides(
    maxIterations = 5,
    enableBuildVerification = true
)

val effectiveConfig = baseConfig.withOverrides(overrides)
val agent = provider.createAgent(VslfcLayer.CODE, effectiveConfig)
```

## Design Principles

### Framework-Agnostic
The interfaces don't depend on Koog, LangChain, or any specific framework. Implementations can use any agent framework.

### Transport-Agnostic
Same interface works for:
- Local agents (in-process)
- Remote agents (HTTP/JSON-RPC)
- Bridged agents (stdio/IPC)

### VSLFC-Aware
Each agent is bound to a specific VSLFC layer, which influences:
- Available tools
- Context prioritization
- Response structure
- Contract validation

### Streaming-First
All agents support both synchronous and streaming responses via `Flow<AgentChunk>`.

### Context-Rich
`AgentContext` provides:
- Workspace awareness
- User interaction context
- Session continuity
- VSLFC layer context
- Discovery cache integration

## Error Handling

### AgentExecutionException
Thrown when agent execution fails:

```kotlin
try {
    val response = agent.process(request)
} catch (e: AgentExecutionException) {
    when (e.errorCode) {
        AgentError.Codes.TOOL_TIMEOUT -> handleToolTimeout()
        AgentError.Codes.CONTEXT_LIMIT_EXCEEDED -> handleContextLimit()
        AgentError.Codes.CANCELLED -> handleCancellation()
        else -> handleUnknownError(e)
    }
}
```

### AgentProviderException
Thrown when provider operations fail:

```kotlin
try {
    val agent = provider.createAgent(VslfcLayer.CODE)
} catch (e: AgentProviderException) {
    when (e.errorCode) {
        "PROVIDER_UNHEALTHY" -> handleUnhealthyProvider()
        "CONFIGURATION_NOT_FOUND" -> handleMissingConfig()
        else -> handleProviderError(e)
    }
}
```

## Extension Functions

### AgentChunk Extensions

```kotlin
// Check if chunk is terminal
if (chunk.isTerminal()) {
    // Done or unrecoverable error
}

// Extract text from any chunk
val text = chunk.extractText()

// Get iteration number
val iteration = chunk.getIteration()
```

### AgentConfig Extensions

```kotlin
// Create config with overrides
val config = AgentConfig.default().withOverrides(overrides)

// Use conservative settings
val config = AgentConfig.conservative()

// Use permissive settings for exploration
val config = AgentConfig.permissive()
```

## Testing

### Mock Agent for Testing

```kotlin
class MockAgent(
    override val id: String = "mock-agent",
    override val layer: VslfcLayer = VslfcLayer.CODE,
    override val displayName: String = "Mock Agent",
    override val capabilities: AgentCapabilities = AgentCapabilities()
) : I2VisionAgent {
    override suspend fun process(request: AgentRequest): AgentResponse {
        return AgentResponse.success(
            requestId = request.id,
            agentId = id,
            finalText = "Mock response"
        )
    }
    
    override fun processStreaming(request: AgentRequest): Flow<AgentChunk> = flow {
        emit(AgentChunk.Text(request.id, "Mock response", isFinal = true))
        emit(AgentChunk.Done(request.id, Outcome.SUCCESS, "Mock response", 1, 100))
    }
    
    override suspend fun cancel(requestId: String): Boolean = true
    override fun getConfig(): AgentConfig = AgentConfig.default()
    override suspend fun updateConfig(overrides: AgentConfigOverrides): AgentConfig = AgentConfig.default()
    override suspend fun dispose() {}
}
```

## Architecture

### Agent Flow (Koog GraphStrategy)

```
                 ┌─────────────┐
                 │   START     │
                 └──────┬──────┘
                        │
                 ┌──────▼──────┐
                 │  ENRICH     │  ← Inject instant context
                 │  CONTEXT    │
                 └──────┬──────┘
                        │
                 ┌──────▼──────┐
                 │   PLAN      │  ← Build iteration prompt
                 └──────┬──────┘
                        │
                 ┌──────▼──────┐
                 │   DECIDE    │  ← Decision node
                 └──┬───┬───┬──┘
                    │   │   │
           ┌────────┘   │   └────────┐
           ▼            ▼            ▼
    ┌──────────┐ ┌──────────┐ ┌──────────┐
    │ EXECUTE  │ │  CLARIFY │ │   DONE   │
    │  TOOL    │ │          │ │          │
    └────┬─────┘ └────┬─────┘ └──────────┘
         │            │
         ▼            ▼
    ┌──────────┐ ┌──────────┐
    │ EVALUATE │ │ WAIT FOR │
    │  RESULT  │ │   USER   │
    └────┬─────┘ └──────────┘
         │
         ▼
    ┌──────────┐
    │ REFLECT  │  ← Optional self-reflection
    └────┬─────┘
         │
         └──────────► back to PLAN
```

### Tool Architecture

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

## Implementation Status

### ✅ Completed Tasks

| Task | Description | Status |
|------|-------------|--------|
| **task-11** | Define I2VisionAgent Core Interfaces | ✅ Complete |
| **task-12** | Define I2VisionAgentProvider Interface | ✅ Complete |
| **task-13** | Implement YAML Configuration Loading | ✅ Complete |
| **task-14** | Build Koog GraphStrategy for Agent Loop | ✅ Complete |
| **task-15** | Implement Koog PromptTemplate Integration | ✅ Complete |
| **task-16** | Build ToolRegistry with i2vision and MCP Tools | ✅ Complete |

### ⏳ Upcoming Tasks

| Task | Description | Status |
|------|-------------|--------|
| **task-18** | Implement LocalTypeScriptAgent | ⏳ Pending |
| **task-21** | Implement Agent Providers | ⏳ Pending |
| **task-22** | Implement JSON-RPC Server | ⏳ Pending |
| **task-24** | Integrate MCP Tools | ⏳ Pending |

## Next Steps

1. **task-18**: Implement LocalTypeScriptAgent (VS Code development)
2. **task-21**: Implement Agent Providers (provider switching)
3. **task-22**: Implement JSON-RPC Server (Kotlin backend)
4. **task-24**: Integrate MCP Tools (MCP server integration)

## References

- [Koog Framework](https://github.com/JetBrains/koog)
- [VSLFC Architecture](../../../docs/architecture/vslfc-layers.md)
- [Agent Architecture Roadmap](../../../backlog/docs/DOC-4.md)
- [Koog GraphStrategy](koog/README.md)
- [Prompt Template System](koog/prompt/README.md)
- [Tool Registry](tools/README.md)
