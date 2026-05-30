# Koog GraphStrategy for i2Vision Agents

This package implements the Koog GraphStrategy-based agent loop, replacing the legacy imperative iteration loop with a declarative directed graph.

## Package Structure

```
com.i2vision.agent.koog/
├── I2VisionStrategyGraph.kt    # Main graph definition
├── StrategyNodes.kt            # Node implementations (components)
├── AgentState.kt               # State management
├── I2VisionKoogAgent.kt        # Agent that uses the graph
└── README.md                   # This file
```

## Overview

The Koog GraphStrategy replaces the legacy `BaseConfigurableAgent.executeWithTools()` imperative loop:

| Legacy (BaseConfigurableAgent) | Modern (Koog GraphStrategy) |
|--------------------------------|------------------------------|
| Imperative `for` loop | Declarative directed graph |
| Manual iteration counting | Koog handles state transitions |
| Hard-coded prompt → parse → execute | Graph nodes with conditional edges |
| Simple `if/else` branching | Decision nodes with strategy patterns |
| No built-in observability | Koog tracing built-in |
| No built-in checkpointing | Koog checkpoint/restore support |

## Graph Flow

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

## Components

### I2VisionStrategyGraph

The main graph builder that orchestrates all components:

```kotlin
class I2VisionStrategyGraph(
    private val config: KoogStrategyConfig,
    private val contextEnricher: ContextEnricher,
    private val promptBuilder: PromptBuilder,
    private val modelInvoker: ModelInvoker,
    private val outputParser: OutputParser,
    private val toolExecutor: ToolExecutor,
    private val responseFormatter: ResponseFormatter
)
```

### Strategy Nodes (Components)

Each graph node is implemented as a separate component for testability:

#### ContextEnricher
Injects i2vision instant context and discovery cache:

```kotlin
class ContextEnricher(
    private val instantContextProvider: InstantContextProvider,
    private val discoveryCache: DiscoveryCache
) {
    suspend fun enrich(
        task: String,
        workspaceRoot: String,
        currentFile: String?,
        vslfcLayer: VslfcLayer
    ): EnrichedContext
}
```

#### PromptBuilder
Builds iteration prompts with context and history:

```kotlin
class PromptBuilder(
    private val promptTemplate: KoogPromptConfig,
    private val config: AgentPromptConfiguration
) {
    fun buildIterationPrompt(
        basePrompt: String,
        iteration: Int,
        maxIterations: Int,
        history: List<IterationStep>,
        enrichedContext: EnrichedContext
    ): String
}
```

#### OutputParser
Parses model output into structured format with multiple strategies:

```kotlin
class OutputParser(
    private val parsingConfig: ParsingConfig
) {
    fun parse(rawOutput: String): ParsedOutput
    
    // Parsing strategies:
    // - HEADER: `tool_call:` JSON header
    // - NAKED_JSON: `{"tool":"...", "args":{...}}`
    // - XML_INVOKE: `<invoke name="tool">...</invoke>`
    // - QUOTED_JSON: Escaped string containing JSON
}
```

#### ModelInvoker
Invokes the LLM with retries and timeout:

```kotlin
class ModelInvoker(
    private val modelProvider: ModelProvider,
    private val config: KoogLlmConfig
) {
    suspend fun invoke(prompt: String): String
    suspend fun reflect(task: String, toolResult: ToolExecutionResult, history: List<IterationStep>): String
}
```

#### ToolExecutor
Executes tool calls with timeout handling:

```kotlin
class ToolExecutor(
    private val toolRegistry: ToolRegistry
) {
    suspend fun execute(
        toolName: String,
        args: Map<String, Any>,
        timeoutSeconds: Long
    ): ToolExecutionResult
}
```

#### ResponseFormatter
Formats final responses:

```kotlin
class ResponseFormatter(
    private val config: AgentPromptConfiguration
) {
    fun format(
        task: String,
        history: List<IterationStep>,
        toolCalls: List<ToolCallRecord>,
        outcome: Outcome,
        layer: VslfcLayer
    ): AgentResponse
}
```

### AgentState

Mutable state maintained during graph execution:

```kotlin
class AgentState(
    val requestId: String,
    val task: String,
    val context: AgentContext,
    val maxIterations: Int = 10,
    val maxConsecutiveToolCalls: Int = 12
) {
    var iteration: Int = 0
    var consecutiveToolCalls: Int = 0
    var isComplete: Boolean = false
    var outcome: Outcome = Outcome.SUCCESS
    var enrichedContext: EnrichedContext = EnrichedContext()
    var parsedOutput: ParsedOutput? = null
    val history: MutableList<IterationStep> = mutableListOf()
    val toolCalls: MutableList<ToolCallRecord> = mutableListOf()
    // ... and more
}
```

### I2VisionKoogAgent

The main agent implementation that uses the graph:

```kotlin
class I2VisionKoogAgent(
    override val id: String,
    override val layer: VslfcLayer,
    override val displayName: String,
    private val strategyGraph: I2VisionStrategyGraph,
    private val config: AgentConfig,
    private val koogConfigs: KoogConfigs
) : I2VisionAgent
```

## Usage

### Create Agent from YAML Configuration

```kotlin
val agent = I2VisionKoogAgent.fromConfig(
    configPath = ".vision-ai/coding-agent.yaml",
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
        currentFile = "/path/to/project/src/main/kotlin/MyClass.kt"
    )
)

val response = agent.process(request)
println("Outcome: ${response.outcome}")
println("Response: ${response.finalText}")
```

### Create Agent from Default Configuration

```kotlin
val agent = I2VisionKoogAgent.fromDefault(
    layer = VslfcLayer.CODE,
    modelProvider = myModelProvider,
    toolRegistry = myToolRegistry,
    instantContextProvider = myInstantContextProvider,
    discoveryCache = myDiscoveryCache
)
```

### Streaming Execution

```kotlin
agent.processStreaming(request).collect { chunk ->
    when (chunk) {
        is AgentChunk.Reasoning -> println("Thinking: ${chunk.text}")
        is AgentChunk.ToolCallStarted -> println("Calling: ${chunk.toolName}")
        is AgentChunk.ToolCallCompleted -> println("Result: ${chunk.output}")
        is AgentChunk.Done -> println("Completed: ${chunk.outcome}")
        is AgentChunk.ChunkError -> println("Error: ${chunk.error.message}")
        else -> {}
    }
}
```

### Cancellation

```kotlin
val requestId = request.id

// Start task
val job = launch {
    agent.process(request)
}

// Cancel if needed
agent.cancel(requestId)
```

## Decision Logic

The DECIDE node uses the following logic:

```kotlin
when {
    // Task complete
    parsed.isTaskComplete() -> Decision.DONE
    
    // Tool call requested
    parsed.hasToolCall() -> {
        if (state.consecutiveToolCalls >= config.maxConsecutiveToolCalls) {
            Decision.DONE  // Force stop
        } else {
            Decision.EXECUTE_TOOL
        }
    }
    
    // Agent needs clarification
    parsed.needsClarification() -> Decision.CLARIFY
    
    // Hit iteration limit
    state.iteration >= config.maxIterations -> Decision.DONE
    
    // Malformed output — kickstart
    parsed.isMalformed() && config.enableKickstart -> {
        if (state.kickstartCount < config.maxKickstarts) {
            Decision.RETRY_WITH_KICKSTART
        } else {
            Decision.DONE  // Give up
        }
    }
    
    // Default: keep planning
    else -> Decision.CONTINUE
}
```

## Parsing Strategies

The OutputParser supports multiple parsing strategies:

### Header-Based Format
```
reasoning: I need to read the file first
tool_call: {"tool": "read_file", "args": {"path": "src/main.kt"}}
```

### Naked JSON
```json
{"tool": "read_file", "args": {"path": "src/main.kt"}}
```

### XML Invoke
```xml
<invoke name="read_file">
  <arg name="path">src/main.kt</arg>
</invoke>
```

### Quoted JSON
```
"{\"tool\": \"read_file\", \"args\": {\"path\": \"src/main.kt\"}}"
```

## State Management

The `AgentState` class maintains:

- **Iteration tracking** - Current iteration, max iterations
- **Tool call tracking** - Consecutive calls, total calls, retries
- **History** - All prompts, outputs, and parsed results
- **Context** - Enriched context from i2vision
- **Outcome** - Final task outcome
- **Errors** - Any errors encountered

## Error Handling

Each node handles errors appropriately:

- **ContextEnricher** - Gracefully handles missing context
- **ModelInvoker** - Retries with backoff on failure
- **OutputParser** - Falls back to plain text if parsing fails
- **ToolExecutor** - Timeout handling and error wrapping
- **ResponseFormatter** - Always produces a response, even on error

## Testing

Each component can be tested independently:

```kotlin
// Test OutputParser
val parser = OutputParser(parsingConfig)
val result = parser.parse("tool_call: {\"tool\": \"read_file\"}")
assertEquals("read_file", result.toolCall?.name)

// Test PromptBuilder
val builder = PromptBuilder(promptTemplate, config)
val prompt = builder.buildIterationPrompt(basePrompt, 1, 10, emptyList(), enrichedContext)
assertTrue(prompt.contains("## Available Context"))

// Test Decision Logic
val state = AgentState(requestId, task, context)
state.parsedOutput = ParsedOutput(..., toolCall = ToolCall("read_file", emptyMap()))
val decision = decide(state)
assertEquals(Decision.EXECUTE_TOOL, decision)
```

## Integration Points

| Component | Used By | Purpose |
|-----------|---------|---------|
| **KoogStrategyConfig** | task-13 | Configuration from YAML |
| **KoogPromptConfig** | task-15 | Prompt template settings |
| **ToolRegistry** | task-16 | Tool execution |
| **ModelProvider** | task-22 | LLM invocation |
| **InstantContextProvider** | i2vision-instant | Semantic context |
| **DiscoveryCache** | i2vision-discovery | Project-wide context |

## Advantages Over Legacy

### 1. **Declarative Flow**
The graph structure makes the agent flow explicit and visual, unlike the hidden imperative loop.

### 2. **Modifiable**
Add/remove nodes without editing core loop logic. For example, adding a validation node:

```kotlin
node("validate") {
    action { state ->
        // Validate output before proceeding
    }
    transitionTo("plan")
}
```

### 3. **Testable**
Each component can be tested in isolation, unlike the monolithic loop.

### 4. **Observable**
Built-in tracing and streaming provide visibility into agent execution.

### 5. **Checkpointable**
State can be saved and restored for long-running tasks.

### 6. **Parallelizable**
Future extension: parallel tool calls in EXECUTE_TOOL node.

## Configuration

The graph is configured via `KoogStrategyConfig`:

```kotlin
data class KoogStrategyConfig(
    val maxIterations: Int = 10,
    val maxConsecutiveToolCalls: Int = 12,
    val reflectionEnabled: Boolean = true,
    val selfCorrectionEnabled: Boolean = true,
    val llmRetries: Int = 3,
    val llmTimeoutSeconds: Long = 60,
    val streamingEnabled: Boolean = true,
    val enableKickstart: Boolean = true,
    val maxKickstarts: Int = 3,
    val maxToolRetries: Int = 2
)
```

## Next Steps

1. **task-15**: Implement Koog PromptTemplate (used by PromptBuilder)
2. **task-16**: Build ToolRegistry (used by ToolExecutor)
3. **task-18**: Implement LocalTypeScriptAgent (alternative to Koog agent)
4. **task-22**: Implement JSON-RPC Server (hosts Koog agents)

## References

- [Koog Framework Documentation](https://github.com/JetBrains/koog)
- [I2Vision Agent Interfaces](../README.md)
- [YAML Configuration](../config/README.md)
- [Legacy Recovery Guide](../../../../configurable-agent/RECOVERY_GUIDE.md)
