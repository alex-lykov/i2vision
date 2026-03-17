# MCP Integration

## Overview

i2vision provides Model Context Protocol (MCP) integration to give LLMs instant access to project context, architecture, and code understanding through a standard JSON-RPC 2.0 interface.

## i2vision MCP Server

The `i2vision-mcp` module implements a full MCP server that can be used with Claude Desktop, Cursor, Continue.dev, and other MCP-compatible clients.

### Available MCP Tools

- `discover_project` - Full VSLFC discovery of the project
- `get_instant_context` - Context for any file or directory
- `get_quality_metrics` - Cohesion, coupling, complexity analysis
- `validate_contract` - Validate layer contracts
- `suggest_refactoring` - Get architectural improvement suggestions
- `get_related_files` - Proactive context suggestions

## Setup

### Claude Desktop / Cursor

```json
{
  "mcpServers": {
    "i2vision": {
      "command": "java",
      "args": ["-jar", "i2vision-mcp/build/libs/i2vision-mcp.jar", "--stdio"]
    }
  }
}
```

### Building the MCP Server

```bash
# Build the MCP server
./gradlew :i2vision-mcp:build

# Run the MCP server
./gradlew :i2vision-mcp:run --args="--stdio"
```

## Architecture

The MCP server is built using:
- **Koog Agents** (`ai.koog:koog-agents:0.6.3`) - AI agent framework for tool registry
- **conf-agent-core** - YAML-configurable agent framework
- **Ktor** - For HTTP transport (optional, stdio is default)

## Technical Details

### Tool Registration

Tools are registered using the Koog Agents tool registry:

```kotlin
import ai.koog.agents.core.tools.ToolRegistry
```

### MCP Protocol

- **Transport**: Stdio (default) or HTTP
- **Protocol**: JSON-RPC 2.0
- **Tools**: 6+ tools for discovery, context, and quality metrics

## See Also

- [MCP Tools Guide](mcp-tools.md) - Detailed MCP tool reference
- [Instant Context Guide](instant-context.md) - Task-aware context optimization
- [MCP Server Flow Diagram](../diagrams/mcp-server-flow.sd) - MCP server architecture
