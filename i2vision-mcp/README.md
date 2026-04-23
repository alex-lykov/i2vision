# i2vision-mcp

## Purpose

i2vision-mcp provides a Model Context Protocol (MCP) server that exposes i2vision's discovery and context capabilities
to LLMs through a standardized protocol. It enables AI assistants to access code intelligence tools via JSON-RPC 2.0.

## Architecture

The module implements the MCP specification with support for multiple transport layers:

### Core Components

**McpServer** (`server/`)

- Central MCP server implementation
- Tool registration and execution
- JSON-RPC 2.0 request handling
- Server lifecycle management

**JSON-RPC Handler** (`server/JsonRpcHandler`)

- Full JSON-RPC 2.0 protocol implementation
- Request/response handling with error codes
- Notification support (requests without id)
- Batch request handling
- Method dispatch to tool handlers

**Tool Registry** (`tools/`)

- Central tool registration system
- Tool metadata management
- Handler dispatch mechanism
- Tool listing and discovery

### Tool Categories

**Discovery Tools** (`tools/discovery/`)

- `discover_project`: Project structure discovery
- `analyze_file`: Single-file analysis

**Context Tools** (`tools/context/`)

- `get_instant_context`: Instant context retrieval
- `prefetch_context`: Context preloading

**Contract Tools** (`tools/contract/`)

- `validate_contract`: Contract validation
- `list_contracts`: Contract listing

**Intelligence Tools** (`tools/intelligence/`)

- `get_quality_metrics`: Code quality metrics
- `get_related_files`: Related file detection
- `get_complexity_details`: Complexity analysis
- `get_cohesion_details`: Cohesion metrics
- `get_intelligence_report`: Comprehensive intelligence report

**Intelligence Service** (`intelligence/`)

- `IntelligenceService`: Core intelligence data provider
- Quality metrics calculation
- Cross-module import analysis
- Semantic cache integration

### Transport Layers

**Stdio Transport** (`transport/StdioTransport`)

- Stdin/stdout communication for Claude Desktop
- Line-by-line JSON-RPC message handling
- Graceful shutdown on EOF
- Main entry point for desktop integration

**HTTP Transport** (`transport/HttpTransport`)

- HTTP-based communication for remote clients
- Ktor/Netty server implementation
- Configurable host and port
- Health check endpoint
- Main entry point for HTTP clients

## Protocol Support

### JSON-RPC 2.0 Methods

**Standard MCP Methods:**

- `initialize`: Server initialization handshake
- `shutdown`: Server shutdown
- `tools/list`: List available tools
- `tools/call`: Execute a tool

**Direct Tool Methods:**

- Tool names can be called directly as JSON-RPC methods
- Backward compatibility with simple tool invocation

### Error Codes

- `-32700`: Parse error
- `-32600`: Invalid Request
- `-32601`: Method not found
- `-32602`: Invalid params
- `-32603`: Internal error

## Usage

### Stdio Transport (Claude Desktop)

```bash
java -jar i2vision-mcp.jar
```

The server reads JSON-RPC requests from stdin and writes responses to stdout.

### HTTP Transport

```bash
java -Dhttp.port=8080 -Dhttp.host=0.0.0.0 -jar i2vision-mcp.jar
```

POST requests to `http://localhost:8080/mcp` with JSON-RPC body.

### Programmatic Usage

```kotlin
val mcpServer = McpServer(projectRoot)
val transport = StdioTransport(mcpServer)
transport.start()
```

## Tool Response Format

### Success Response

```json
{
  "jsonrpc": "2.0",
  "result": {
    "content": [
      {
        "type": "text",
        "text": "{\"data\": {...}}"
      }
    ],
    "isError": false
  },
  "id": 1
}
```

### Error Response

```json
{
  "jsonrpc": "2.0",
  "error": {
    "code": -32601,
    "message": "Method not found",
    "data": {"method": "unknown_tool"}
  },
  "id": 1
}
```

## Dependencies

- `i2vision-discover`: Discovery engine
- `i2vision-instant`: Context provider
- `index-provider`: Index access
- `link-service`: Link analysis
- `storage-core`: Cache access
- `vslfc-core`: VSLFC structures
- Jackson + Jackson Kotlin: JSON processing
- SnakeYAML: YAML parsing
- Ktor (Netty): HTTP server
- Kotlin Coroutines: Async operations
- SLF4J: Logging

## Configuration

### Server Configuration

```kotlin
val mcpServer = McpServer(projectRoot)
```

### HTTP Transport Configuration

```kotlin
val transport = HttpTransport(
    mcpServer = mcpServer,
    port = 8080,
    host = "0.0.0.0"
)
```

## Integration

### Claude Desktop

Configure in Claude Desktop settings:

```json
{
  "mcpServers": {
    "i2vision": {
      "command": "java",
      "args": ["-jar", "/path/to/i2vision-mcp.jar"]
    }
  }
}
```

### Remote Clients

HTTP endpoint: `POST http://host:port/mcp`
Content-Type: `application/json`

## Security

- No authentication in current implementation
- Recommended to run behind reverse proxy with auth
- Input validation on all parameters
- Error messages don't expose internal details

## Performance

- JSON-RPC parsing with Jackson
- Async tool execution with coroutines
- Context caching via i2vision-instant
- Efficient serialization with kotlinx-serialization
