# conf-agent-core

Core configurable agent framework with discovery, execution, and formatting engines.

## Overview

conf-agent-core provides the foundational components for building configurable AI agents with:

- **DiscoveryEngine**: Parses model output to extract tool calls and reasoning
- **ExecutionEngine**: Executes tool calls with timeout and error handling
- **FormattingEngine**: Formats assistant text and iteration messages
- **Shell Integration**: Command execution and shell tool handlers
- **Agent Memory**: Conversation history and fact retrieval
- **Tool Registry**: Builder for Koog tool registries

## Installation

```kotlin
dependencies {
    implementation("com.i2vision:conf-agent-core:1.0.0")
}
```

## Usage

### Creating a Configurable Agent

```kotlin
import com.i2vision.agent.ConfigurableAgent
import com.i2vision.agent.BaseConfigurableAgent
import com.i2vision.agent.engines.DiscoveryEngine
import com.i2vision.agent.engines.ExecutionEngine
import com.i2vision.agent.engines.FormattingEngine

class MyAgent(
    configPath: String
) : BaseConfigurableAgent<MyConfig, String, String, MyContext>(
    configPath = configPath,
    configLoader = { path -> loadConfig(path) },
    discoveryEngine = DiscoveryEngine(),
    executionEngine = ExecutionEngine(),
    formattingEngine = FormattingEngine(),
    configProvider = MyConfigProvider()
)

suspend fun processTask(task: String, context: MyContext): String {
    return agent.process(task, context)
}
```

### Discovery Engine

```kotlin
import com.i2vision.agent.engines.DiscoveryEngine

val discoveryEngine = DiscoveryEngine()

// Parse assistant output
val output = discoveryEngine.parseAssistantOutput(rawText)
println("Tool call: ${output.toolCall}")
println("Reasoning: ${output.reasoning}")
println("Assistant text: ${output.assistantText}")
```

### Execution Engine

```kotlin
import com.i2vision.agent.engines.ExecutionEngine

val executionEngine = ExecutionEngine()

// Execute tool call
val result = executionEngine.executeToolCall(
    toolCall = toolCall,
    projectPath = "/path/to/project",
    timeoutSeconds = 30,
    fileOperationMode = "direct"
)
```

## Features

### Engines

- **DiscoveryEngine**: Multi-format tool call parsing (JSON, XML, quoted JSON)
- **ExecutionEngine**: Tool execution with timeout and error handling
- **FormattingEngine**: Text formatting and iteration limit messages

### Tool Support

- **ToolNameNormalizer**: Normalizes tool names across different formats
- **ToolDispatcher**: Dispatches tool calls to appropriate handlers
- **ToolExecutionResult**: Structured tool execution results

### Shell Integration

- **ShellCommandRunner**: Executes shell commands safely
- **ShellToolHandlers**: Built-in shell tool handlers

### Agent Memory

- **AgentMemory**: Conversation history management
- **RetrieveFactsFromHistory**: Fact extraction from conversation history

## License

MIT License - see [LICENSE](LICENSE) file for details.

## Contributing

Contributions are welcome! Please read [contributing.md](contributing.md) for details.
