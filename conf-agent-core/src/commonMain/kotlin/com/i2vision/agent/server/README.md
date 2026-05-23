# JSON-RPC Server

This module provides a JSON-RPC 2.0 server that exposes the Kotlin agent to VS Code and other clients.

## Features

- **JSON-RPC 2.0** - Standard protocol for remote procedure calls
- **Session Management** - One agent instance per session with automatic cleanup
- **Streaming** - Server notifications for streaming events via WebSocket
- **Cancellation** - Cancel running requests
- **Configuration** - Dynamic agent configuration management

## Endpoints

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/rpc` | POST | JSON-RPC HTTP endpoint |
| `/ws` | WebSocket | Streaming notifications |
| `/health` | GET | Health check endpoint |

## Quick Start

### Starting the Server

```kotlin
import com.i2vision.agent.*
import com.i2vision.agent.server.*
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    // Create agent provider
    val agentProvider = object : I2VisionAgentProvider {
        override fun listConfigurations(): List<AgentConfig> {
            // Return your configurations
            return listOf(/* ... */)
        }
        
        override fun getConfiguration(configId: String): AgentConfig? {
            return listConfigurations().find { it.id == configId }
        }
        
        override fun updateConfiguration(config: AgentConfig) {
            // Save configuration
        }
        
        override fun createAgent(configId: String, layer: VslfcLayer): I2VisionAgent {
            // Create and return agent instance
            return when (layer) {
                VslfcLayer.KOOG -> I2VisionKoogAgent(configId, /* ... */)
            }
        }
    }
    
    // Create and start server
    val server = agentJsonRpcServer {
        port = 8080
        host = "localhost"
        agentProvider = agentProvider
    }
    
    server.start()
    println("Server running at ${server.serverUrl}")
}
```

## JSON-RPC Methods

### initialize

Initialize a new session.

**Request:**
```json
{
  "jsonrpc": "2.0",
  "method": "initialize",
  "params": {
    "sessionId": "optional-id",
    "configId": "default",
    "layer": "koog"
  },
  "id": 1
}
```

**Response:**
```json
{
  "jsonrpc": "2.0",
  "result": {
    "sessionId": "uuid-here",
    "displayName": "default-KOOG",
    "layer": "KOOG"
  },
  "id": 1
}
```

### process

Execute agent task (blocking).

**Request:**
```json
{
  "jsonrpc": "2.0",
  "method": "process",
  "params": {
    "sessionId": "session-id",
    "task": "What files are in the project?"
  },
  "id": 2
}
```

**Response:**
```json
{
  "jsonrpc": "2.0",
  "result": {
    "answer": "The project contains...",
    "usage": {
      "promptTokens": 100,
      "completionTokens": 50,
      "totalTokens": 150
    }
  },
  "id": 2
}
```

### processStreaming

Execute with streaming (notifications via WebSocket).

**Request:**
```json
{
  "jsonrpc": "2.0",
  "method": "processStreaming",
  "params": {
    "sessionId": "session-id",
    "task": "What files are in the project?"
  },
  "id": 3
}
```

**Response (immediate):**
```json
{
  "jsonrpc": "2.0",
  "result": {
    "status": "started",
    "sessionId": "session-id"
  },
  "id": 3
}
```

**Notifications (via WebSocket):**
```json
{
  "jsonrpc": "2.0",
  "method": "streamChunk",
  "params": {
    "sessionId": "session-id",
    "chunk": {"type": "text", "content": "The project..."}
  }
}
```

```json
{
  "jsonrpc": "2.0",
  "method": "streamComplete",
  "params": {
    "sessionId": "session-id"
  }
}
```

### cancel

Cancel running request.

**Request:**
```json
{
  "jsonrpc": "2.0",
  "method": "cancel",
  "params": {
    "sessionId": "session-id"
  },
  "id": 4
}
```

### ping

Health check.

**Request:**
```json
{
  "jsonrpc": "2.0",
  "method": "ping",
  "id": 5
}
```

**Response:**
```json
{
  "jsonrpc": "2.0",
  "result": {
    "status": "ok",
    "timestamp": 1234567890
  },
  "id": 5
}
```

### listConfigurations

List available agent configs.

**Request:**
```json
{
  "jsonrpc": "2.0",
  "method": "listConfigurations",
  "id": 6
}
```

**Response:**
```json
{
  "jsonrpc": "2.0",
  "result": [
    {
      "id": "default",
      "displayName": "Default Agent",
      "description": "Default configuration",
      "layer": "KOOG"
    }
  ],
  "id": 6
}
```

### getConfiguration

Get specific agent config.

**Request:**
```json
{
  "jsonrpc": "2.0",
  "method": "getConfiguration",
  "params": {
    "configId": "default"
  },
  "id": 7
}
```

### updateConfiguration

Update agent config.

**Request:**
```json
{
  "jsonrpc": "2.0",
  "method": "updateConfiguration",
  "params": {
    "configId": "default",
    "configuration": {
      "displayName": "Updated Name",
      "modelId": "gpt-4"
    }
  },
  "id": 8
}
```

### shutdown

Shutdown session.

**Request:**
```json
{
  "jsonrpc": "2.0",
  "method": "shutdown",
  "params": {
    "sessionId": "session-id"
  },
  "id": 9
}
```

## Error Codes

| Code | Name | Description |
|------|------|-------------|
| -32700 | PARSE_ERROR | Invalid JSON |
| -32600 | INVALID_REQUEST | Invalid JSON-RPC request |
| -32601 | METHOD_NOT_FOUND | Method not found |
| -32602 | INVALID_PARAMS | Invalid method parameters |
| -32603 | INTERNAL_ERROR | Internal error |

## Architecture

```
┌─────────────┐     HTTP/WS     ┌──────────────────┐
│   Client    │ ◄─────────────► │ AgentJsonRpcServer│
│  (VS Code)  │                 │                  │
└─────────────┘                 │ ┌──────────────┐ │
                                │ │SessionManager│ │
                                │ └──────┬───────┘ │
                                │        │         │
                                │ ┌──────▼───────┐ │
                                │ │AgentSession  │ │
                                │ └──────┬───────┘ │
                                │        │         │
                                │ ┌──────▼───────┐ │
                                │ │I2VisionAgent │ │
                                │ └──────────────┘ │
                                └──────────────────┘
```

## Session Management

- Sessions are created on `initialize` request
- Sessions have a 30-minute idle timeout (configurable)
- Idle sessions are automatically cleaned up
- Sessions are disposed when removed or timed out

## Streaming

Streaming uses WebSocket notifications:

1. Client connects to `ws://localhost:8080/ws`
2. Client sends `processStreaming` request via HTTP or WS
3. Server sends `streamChunk` notifications as response arrives
4. Server sends `streamComplete` when done
5. Server sends `streamError` if an error occurs

## Testing

```bash
# Health check
curl http://localhost:8080/health

# Initialize session
curl -X POST http://localhost:8080/rpc \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"initialize","params":{},"id":1}'

# Process task
curl -X POST http://localhost:8080/rpc \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"process","params":{"sessionId":"...","task":"Hello"},"id":2}'
```
