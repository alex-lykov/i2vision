# Agent Implementation Guide

## Overview

The AgentBridge implements a modern agentic loop with reflection, where the LLM sees tool results and decides if more tools are needed or if the task is complete.

## Architecture

```
User Input → AgentBridge → Agent Core → LLM → Tool Calls → Execution → Results → LLM → Final Answer
```

## Key Features

### 1. Iteration Control

- **Max Iterations**: Configurable (default: 10)
- **Decision Nudge**: After each tool result, LLM is reminded of remaining iterations
- **Loop Detection**: Repeated tool calls trigger nudge at 2x, force stop at 4x

### 2. Tool Calling

- **Supported Tools**:
  - `list_directory` - List files in a directory
  - `read_file` - Read file contents
  - `write_file` - Write content to file
  - `search_files` - Search files by regex pattern
  - `run_command` - Execute shell commands
  - `get_file_context` - Get file context (classes, functions, imports)

- **Tool Result Linking**: Uses `tool_call_id` to link results with calls

### 3. Safety Features

- **Blocked Commands**: Long-running servers are blocked:
  - `npm run dev`, `npm start`
  - `yarn dev`, `yarn start`
  - `gradlew run`, `./gradlew run`
  - `mvn spring-boot:run`, `mvn jetty:run`
  - `node server`, `nodemon`, `vite`, `next dev`

- **Path Validation**: workspaceRoot must be explicitly provided
- **Extension Isolation**: workspaceRoot and extensionRoot must be different

### 4. Error Handling

- **Empty Results**: Formatted as clear messages
- **File Not Found**: Returns `FILE_NOT_FOUND` without retrying
- **Directory Not Found**: Returns `DIRECTORY_NOT_FOUND` with suggestions
- **Timeout Prevention**: Blocked commands return immediate error

### 5. Streaming Support

Real-time progress updates via `processStreaming()`:

```typescript
for await (const chunk of agent.processStreaming(userInput, context)) {
  // Handle: thinking, tool_call_started, tool_call_completed, text, done, error
}
```

## Message Format

### Assistant Message
```typescript
{
  role: 'assistant',
  content: 'I will read the file to understand its contents'
}
```

### Tool Result Message
```typescript
{
  role: 'tool',
  content: 'File contents...',
  tool_call_id: 'call_abc123'
}
```

**Note**: Ollama does NOT accept `tool_calls` in assistant messages (causes 400 error). Only include `tool_call_id` in tool results.

## Decision Nudge

After each tool result, the LLM receives:

```
Tool results received. You have X of Y iterations remaining. 
If you have enough information to answer the user's question, provide your answer now. 
Only call another tool if you're missing critical information.
```

This encourages completion in 3-5 iterations instead of 10+.

## Loop Detection

Tracks tool call signatures (name + args) across iterations:

- **2nd repeat**: Injects nudge message to try different approach
- **4th repeat**: Force stops with error message

## Plan Detection

Detects when LLM returns only plans without execution:

```typescript
const isPlanOnly = 
  response.startsWith('I will:') ||
  response.startsWith('I\'ll') ||
  /^[Ii] will (call|use|read)/.test(response);
```

If detected, forces tool execution with nudge message.

## Configuration

Agent behavior is configured via YAML:

```yaml
iterationSettings:
  maxIterations: 10
  maxConsecutiveToolCalls: 5
  
toolSelection:
  toolTimeoutSeconds: 30
  
formatting:
  maxObservationChars: 8000
```

## Usage Example

```typescript
const agent = new AgentBridge(config, outputChannel, extensionRoot, workspaceRoot);
await agent.initialize();

const response = await agent.process(
  'Find the main entry point',
  { currentFile: 'src/index.ts', workspaceRoot: '/project' },
  (event) => {
    console.log(`Iteration ${event.iteration}: ${event.message}`);
  }
);

console.log(response.finalText);
console.log(`Tools used: ${response.toolCalls.length}`);
console.log(`Iterations: ${response.iterations}`);
```

## Performance

- **Typical Iterations**: 3-5 for exploratory tasks
- **Tool Execution**: < 100ms per tool
- **Total Duration**: 1-3 seconds for simple queries

## Testing

See `docs/AGENT_TOOL_CALLING_TEST.md` for test procedures.

## LLM Provider Support

The AgentBridge supports multiple LLM providers through the `llm-client` module:

### Ollama (Local)
- **Setup**: Install Ollama, pull models locally
- **Cost**: Free
- **Models**: llama3.2, mistral, codellama, etc.
- **Best for**: Development, testing, privacy-sensitive tasks

### DeepSeek (Cloud)
- **Setup**: API key only
- **Cost**: ~$0.14/1M tokens
- **Models**: deepseek-chat, deepseek-reasoner
- **Best for**: Complex reasoning, large context (64K), production

See [DeepSeek Integration Guide](./deepseek-integration.md) for configuration details.

## Related Documents

- [Agent Configuration](../reference/agent-config.md)
- [Tool API Reference](../reference/tools.md)
- [DeepSeek Integration](./deepseek-integration.md)
- [Architecture Decision Records](../adr/)
