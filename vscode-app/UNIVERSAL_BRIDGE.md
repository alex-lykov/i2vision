# Universal Bridge Architecture

## Overview

The **Universal Bridge** is a transport-agnostic communication layer between UI clients (VS Code, IntelliJ, Web Apps, CLI) and the `conf-agent-core` agent engine. It implements the **Agent Communication Protocol (ACP)**, a JSON-RPC 2.0 based protocol that works across any transport mechanism.

## Architecture

```
┌──────────────────────────────────────────────────────────────┐
│                    UI CLIENTS                                 │
│                                                               │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌─────────────┐ │
│  │ VS Code  │  │ IntelliJ │  │  Web App │  │  CLI Tool   │ │
│  │Extension │  │  Plugin  │  │          │  │             │ │
│  └────┬─────┘  └────┬─────┘  └────┬─────┘  └──────┬──────┘ │
│       │             │             │               │          │
│       └─────────────┼─────────────┼───────────────┘          │
│                     │             │                           │
│              ┌──────▼─────┐ ┌────▼──────┐                    │
│              │ i2vision   │ │ i2vision  │                    │
│              │ UI Config  │ │ UI Config │                    │
│              │ (VS Code)  │ │(IntelliJ) │                    │
│              └──────┬─────┘ └────┬──────┘                    │
└─────────────────────┼────────────┼───────────────────────────┘
                      │            │
         ┌────────────┼────────────┼────────────┐
         │            │            │            │
         │     ┌──────▼────────────▼──────┐     │
         │     │   Universal Bridge       │     │
         │     │   (Protocol Layer)       │     │
         │     └──────────┬───────────────┘     │
         │                │                      │
         │     ┌──────────▼───────────────┐     │
         │     │   conf-agent-core        │     │
         │     │   (Agent Engine)         │     │
         │     └──────────────────────────┘     │
         │                                       │
         └─────────── PROTOCOL LAYER ───────────┘
```

## Key Principles

1. **Transport Agnostic**: Works with stdio, HTTP, WebSocket, or any custom transport
2. **Client Agnostic**: Same protocol for VS Code, IntelliJ, Web, CLI
3. **Separation of Concerns**: 
   - Agent logic → `conf-agent-core`
   - Protocol → Universal Bridge
   - UI → Client-specific settings
4. **Extensible**: Easy to add new transports, clients, or agent types

## Components

### 1. Protocol (`src/bridge/Protocol.ts`)

Defines the JSON-RPC 2.0 message format:

```typescript
// Request
interface AgentRequest {
  jsonrpc: '2.0';
  id: string | number;
  method: 'process';
  params: {
    agent: AgentConfigRef;
    task: string;
    context: AgentContext;
    uiHints?: UiHints;
  };
}

// Response
interface AgentResponse {
  jsonrpc: '2.0';
  id: string | number;
  result?: {
    outcome: 'success' | 'iteration_limit' | 'error' | 'cancelled';
    finalText?: string;
    iterations: number;
    toolCalls: ToolCallRecord[];
    durationMs: number;
    reasoningTrace?: ReasoningStep[];
  };
  error?: { code: number; message: string };
}

// Notification
interface AgentNotification {
  jsonrpc: '2.0';
  method: string;
  params?: {
    type: 'iteration' | 'tool_call' | 'progress' | 'stream';
    data: any;
  };
}
```

### 2. Transport Layer (`src/bridge/Transport.ts`)

Abstract interface for transport implementations:

```typescript
interface AgentTransport {
  send(request: AgentRequest): Promise<AgentResponse>;
  onNotification(callback: (n: AgentNotification) => void): void;
  isConnected(): boolean;
  dispose(): void;
}
```

#### Available Transports

**StdioTransport** - For local CLI processes:
```typescript
const transport = new StdioTransport({
  command: 'java -jar conf-agent-core.jar --mode=server',
  cwd: '/path/to/workspace',
  timeoutMs: 300000
});
```

**HttpTransport** - For remote servers:
```typescript
const transport = new HttpTransport({
  baseUrl: 'http://localhost:8765',
  timeoutMs: 300000,
  useSse: true // Enable Server-Sent Events for notifications
});
```

### 3. UI Configuration (`src/bridge/I2VisionUiConfig.ts`)

Declarative UI settings separate from agent logic:

```typescript
interface I2VisionUiConfig {
  agents: {
    enabled: ('vision' | 'structure' | 'logic' | 'flow' | 'code')[];
    defaultAgent: 'code';
  };
  chat: {
    showReasoningTrace: boolean;
    showToolCalls: boolean;
    showIterationCount: boolean;
    showDuration: boolean;
    maxHistoryDisplay: number;
  };
  contextGathering: {
    autoIncludeCurrentFile: boolean;
    autoIncludeDiscoveryCache: boolean;
    autoIncludeVslfcContext: boolean;
  };
  streaming: {
    enabled: boolean;
    chunkSize: number;
    delayMs: number;
  };
}
```

### 4. Universal Bridge (`src/bridge/UniversalAgentBridge.ts`)

Abstract base class handling protocol-level concerns:

```typescript
abstract class UniversalAgentBridge {
  protected transport: AgentTransport;
  protected uiConfig: I2VisionUiConfig;

  constructor(transport: AgentTransport, uiConfig: I2VisionUiConfig);

  // THE ONLY CLIENT-SPECIFIC METHOD
  protected abstract buildContext(): Promise<AgentContext>;

  // Protocol methods (same for all clients)
  async processTask(task: string, agentLayer?: string): Promise<AgentResponse>;
  async cancelTask(requestId: string): Promise<void>;
  async checkHealth(): Promise<any>;
}
```

### 5. VS Code Bridge (`src/bridge/VsCodeAgentBridge.ts`)

VS Code-specific implementation:

```typescript
class VsCodeAgentBridge extends UniversalAgentBridge {
  protected async buildContext(): Promise<AgentContext> {
    return {
      workspaceRoot: this.getWorkspaceRoot(),
      currentFile: this.getCurrentFilePath(),
      selectedFiles: await this.getSelectedFiles(),
      cursorPosition: this.getCursorPosition(),
      discoveryCache: this.getDiscoveryCachePath(),
      vslfcContext: await this.getVslfcContext()
    };
  }
}
```

## Usage Examples

### VS Code Extension

```typescript
import { createVsCodeBridge } from './bridge';

// Create bridge with stdio transport
const bridge = createVsCodeBridge(
  'java -jar conf-agent-core.jar --mode=server',
  workspaceRoot
);

// Process a task
const response = await bridge.processTask(
  'Refactor this class to use dependency injection',
  'code'
);

console.log(response.result?.finalText);
console.log(`Completed in ${response.result?.durationMs}ms`);

// Cancel a running task
await bridge.cancelTask(requestId);

// Check health
const health = await bridge.checkHealth();
console.log(`Server status: ${health.status}`);
```

### IntelliJ Plugin (Kotlin)

```kotlin
// Similar structure, different buildContext() implementation
class IntelliJAgentBridge : UniversalAgentBridge() {
    override suspend fun buildContext(): AgentContext {
        return AgentContext(
            workspaceRoot = ProjectManager.getInstance().defaultProject.basePath,
            currentFile = EditorUtil.getCurrentFile(),
            // ... IntelliJ-specific context
        )
    }
}
```

### Web App (React)

```typescript
// Use HTTP transport
const transport = new HttpTransport({
  baseUrl: 'http://localhost:8765'
});

const bridge = new WebAgentBridge(transport, uiConfig);

// buildContext() uses browser APIs
protected async buildContext(): Promise<AgentContext> {
  return {
    workspaceRoot: window.location.origin,
    currentFile: this.state.currentFile,
    // ... web-specific context
  };
}
```

## VS Code Settings

Add to `.vscode/settings.json`:

```json
{
  // Bridge Configuration
  "i2vision.bridge.transport": "stdio",
  "i2vision.bridge.httpUrl": "http://localhost:8765",
  "i2vision.bridge.command": "java -jar conf-agent-core.jar --mode=server",
  
  // UI Configuration
  "i2vision.ui.agents.enabled": ["code", "structure", "flow"],
  "i2vision.ui.agents.defaultAgent": "code",
  "i2vision.ui.chat.showReasoningTrace": true,
  "i2vision.ui.chat.showToolCalls": true,
  "i2vision.ui.context.autoIncludeCurrentFile": true,
  "i2vision.ui.streaming.enabled": true,
  
  // Model Overrides
  "i2vision.model.override": false,
  "i2vision.model.id": "codellama:13b",
  "i2vision.model.provider": "ollama",
  "i2vision.model.temperature": 0.7
}
```

## Protocol Messages

### Process Request

```json
{
  "jsonrpc": "2.0",
  "id": "1714234567890-abc123",
  "method": "process",
  "params": {
    "agent": {
      "configPath": ".vscode/i2vision/agents/coding-agent.yaml",
      "layer": "code",
      "overrides": {
        "model": {
          "id": "codellama:13b",
          "provider": "ollama"
        }
      }
    },
    "task": "Explain the architecture of this module",
    "context": {
      "workspaceRoot": "/path/to/project",
      "currentFile": "/path/to/module.ts",
      "discoveryCache": "/path/to/.vscode/i2vision/discovery-cache.json"
    },
    "uiHints": {
      "clientType": "vscode",
      "supportsStreaming": true,
      "preferredChunkSize": 200,
      "theme": "dark"
    }
  }
}
```

### Process Response

```json
{
  "jsonrpc": "2.0",
  "id": "1714234567890-abc123",
  "result": {
    "outcome": "success",
    "finalText": "This module follows a layered architecture...",
    "iterations": 3,
    "toolCalls": [
      {
        "toolName": "i2vision_read_file",
        "args": { "path": "module.ts" },
        "result": "// File content...",
        "durationMs": 45
      }
    ],
    "durationMs": 2340,
    "reasoningTrace": [
      {
        "step": 1,
        "reasoning": "First, I need to understand the module structure",
        "timestamp": 1714234567890
      }
    ]
  }
}
```

### Notification (Server → Client)

```json
{
  "jsonrpc": "2.0",
  "method": "agent.notification",
  "params": {
    "type": "tool_call",
    "data": {
      "toolName": "i2vision_read_file",
      "args": { "path": "module.ts" }
    },
    "requestId": "1714234567890-abc123"
  }
}
```

## Extending the Bridge

### Adding a New Transport

```typescript
class WebSocketTransport implements AgentTransport {
  private ws: WebSocket;

  constructor(url: string) {
    this.ws = new WebSocket(url);
  }

  async send(request: AgentRequest): Promise<AgentResponse> {
    return new Promise((resolve, reject) => {
      this.ws.send(JSON.stringify(request));
      this.ws.onmessage = (event) => {
        resolve(JSON.parse(event.data));
      };
    });
  }

  onNotification(callback: (n: AgentNotification) => void): void {
    // Handle notifications
  }

  isConnected(): boolean {
    return this.ws.readyState === WebSocket.OPEN;
  }

  dispose(): void {
    this.ws.close();
  }
}
```

### Adding a New Client

1. Extend `UniversalAgentBridge`
2. Implement `buildContext()` with client-specific logic
3. Override notification handlers for UI updates
4. Choose a transport (stdio, HTTP, WebSocket)

## Benefits

| Feature | Benefit |
|---------|---------|
| **Transport Agnostic** | Switch between stdio, HTTP, WebSocket without changing agent logic |
| **Client Agnostic** | Same protocol for VS Code, IntelliJ, Web, CLI |
| **Separation of Concerns** | Agent logic, protocol, and UI are independent |
| **Extensible** | Easy to add new transports, clients, or agent types |
| **Type Safe** | Full TypeScript types for all protocol messages |
| **Debuggable** | JSON-RPC messages can be logged and inspected |

## Future Enhancements

1. **WebSocket Transport**: For real-time bidirectional communication
2. **Authentication**: Add API key or token-based auth for HTTP transport
3. **Compression**: Gzip compression for large context payloads
4. **Batch Requests**: Support multiple requests in a single message
5. **Progress API**: Standardized progress reporting for long-running tasks
6. **Cancellation Tokens**: Better cancellation support across transports

## Testing

```bash
# Compile TypeScript
npm run compile

# Run tests
npm test
```

## File Structure

```
vscode-app/src/bridge/
├── Protocol.ts              # JSON-RPC message types
├── Transport.ts             # Transport interface
├── StdioTransport.ts        # Stdio implementation
├── HttpTransport.ts         # HTTP implementation
├── I2VisionUiConfig.ts      # UI configuration types
├── UniversalAgentBridge.ts  # Abstract bridge base class
├── VsCodeAgentBridge.ts     # VS Code implementation
└── index.ts                 # Public exports
```

## Conclusion

The Universal Bridge provides a clean, extensible architecture for agent communication that works across any client and transport. By separating protocol, transport, and UI concerns, it enables:

- **Reusability**: Same bridge code for multiple clients
- **Maintainability**: Clear separation of concerns
- **Flexibility**: Easy to add new transports or clients
- **Interoperability**: Standard JSON-RPC 2.0 protocol

This architecture future-proofs i2-Vision for expansion to IntelliJ, web apps, and other platforms while maintaining a single, unified agent engine.
