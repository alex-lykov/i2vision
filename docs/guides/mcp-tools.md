# MCP Toolset

## Overview

MCP (Model Context Protocol) integration enables bidirectional communication between i2vision and external tools/LLMs. It provides standardized operations for synchronization and validation.

---

## Core Operations

### ai_link
Create cross-layer semantic links.

**Purpose:** Connect related elements across VSLFC layers

**Input:**
```json
{
  "source": {
    "layer": "CODE",
    "element": "AgentOrchestrator.kt",
    "type": "class"
  },
  "target": {
    "layer": "STRUCTURE",
    "element": "orchestrator-component",
    "type": "component"
  },
  "relationship": "implements",
  "confidence": 0.95
}
```

**Output:**
```json
{
  "link_id": "link-123",
  "created": true,
  "confidence": 0.95
}
```

---

### ai_sync
Synchronize clusters and layers.

**Purpose:** Keep all VSLFC layers in sync

**Input:**
```json
{
  "direction": "code_to_vision",  # or vision_to_code
  "cluster": "orchestrator",
  "layers": ["code", "flow", "logic", "structure", "vision"],
  "force": false
}
```

**Output:**
```json
{
  "synced": true,
  "layers_updated": 5,
  "changes": [
    {
      "layer": "structure",
      "updated_items": 3,
      "conflicts": 0
    }
  ],
  "timestamp": "2026-04-08T14:30:00Z"
}
```

---

### ai_validate
Validate layer consistency and contracts.

**Purpose:** Check VSLFC integrity and contract compliance

**Input:**
```json
{
  "cluster": "orchestrator",
  "layer": "structure",
  "check_contracts": true,
  "check_consistency": true
}
```

**Output:**
```json
{
  "valid": true,
  "issues": [
    {
      "type": "missing_documentation",
      "element": "DiscoveryPipeline",
      "severity": "warning"
    }
  ],
  "confidence": 0.92
}
```

---

### ai_discover
Run discovery operation on code.

**Purpose:** Discover and populate layers from code

**Input:**
```json
{
  "cluster": "orchestrator",
  "direction": "code_to_vision",
  "depth": "full",
  "layers": ["code", "flow", "logic", "structure"],
  "enhance_with_llm": true
}
```

**Output:**
```json
{
  "discovered": true,
  "cluster": "orchestrator",
  "results": {
    "code": {
      "elements_found": 12,
      "confidence": 1.0
    },
    "flow": {
      "elements_found": 8,
      "confidence": 0.85
    },
    "logic": {
      "elements_found": 5,
      "confidence": 0.7
    },
    "structure": {
      "elements_found": 4,
      "confidence": 0.9
    }
  },
  "timestamp": "2026-04-08T14:30:00Z"
}
```

---

### ai_search
Search across VSLFC artifacts.

**Purpose:** Find elements by name, type, or relationship

**Input:**
```json
{
  "query": "AgentOrchestrator",
  "layers": ["code", "structure", "vision"],
  "type": "class",
  "limit": 10
}
```

**Output:**
```json
{
  "results": [
    {
      "layer": "CODE",
      "element": "AgentOrchestrator.kt",
      "type": "class",
      "location": "core/orchestrator/src/.../AgentOrchestrator.kt",
      "confidence": 1.0
    },
    {
      "layer": "STRUCTURE",
      "element": "orchestrator-component",
      "type": "component",
      "confidence": 0.95
    }
  ],
  "total": 2
}
```

---

## Integration Points

### With Orchestrator
```kotlin
val orchestrator = Orchestrator(...)
val mcp = MCPServer(orchestrator)
mcp.start(port = 3001)
```

**Endpoint:** `http://localhost:3001/mcp/`

### With External Tools
Any tool can invoke MCP operations:

```bash
curl -X POST http://localhost:3001/mcp/ai_sync \
  -H "Content-Type: application/json" \
  -d '{"direction": "code_to_vision", "cluster": "orchestrator"}'
```

### With LLMs
Claude, ChatGPT, etc. can use via tool calling:

```json
{
  "type": "tool_use",
  "name": "ai_sync",
  "input": {
    "direction": "code_to_vision",
    "cluster": "orchestrator"
  }
}
```

---

## Protocol Details

### Request Format
```json
{
  "operation": "ai_link",  # Operation name
  "params": { ... },       # Operation parameters
  "options": {
    "timeout": 30,
    "streaming": false
  }
}
```

### Response Format
```json
{
  "success": true,
  "operation": "ai_link",
  "result": { ... },       # Operation result
  "metadata": {
    "duration_ms": 245,
    "timestamp": "2026-04-08T14:30:00Z"
  }
}
```

### Error Response
```json
{
  "success": false,
  "error": {
    "code": "VALIDATION_ERROR",
    "message": "Invalid cluster name",
    "details": { ... }
  }
}
```

---

## Streaming

### Supported Operations
- `ai_discover` - Stream discovery progress
- `ai_sync` - Stream sync updates
- `ai_validate` - Stream validation checks

### Streaming Example
```
curl -N http://localhost:3001/mcp/ai_discover?stream=true
```

**Output (newline-delimited JSON):**
```
{"event": "started", "cluster": "orchestrator"}
{"event": "discovering_code", "progress": 0.2}
{"event": "discovering_flow", "progress": 0.4}
{"event": "discovering_logic", "progress": 0.6}
{"event": "discovering_structure", "progress": 0.8}
{"event": "completed", "results": {...}}
```

---

## Configuration

### Server Configuration
Server configuration is managed through environment variables and module-specific configuration files. Refer to the MCP Integration guide for setup details.

---

## Usage Patterns

### Pattern 1: Sync Before Analyze
```
→ ai_sync (ensure fresh data)
→ ai_discover (get latest artifacts)
→ ai_validate (check consistency)
```

### Pattern 2: Search and Link
```
→ ai_search (find elements)
→ ai_link (create relationships)
→ ai_validate (verify links)
```

### Pattern 3: Full Analysis
```
→ ai_discover (populate layers)
→ ai_sync (synchronize)
→ ai_validate (check contracts)
→ Output results
```

---

## Error Handling

### Common Errors

| Error | Cause | Solution |
|-------|-------|----------|
| CLUSTER_NOT_FOUND | Invalid cluster name | Check cluster name |
| VALIDATION_ERROR | Invalid parameters | Check parameter types |
| SYNC_CONFLICT | Conflicting changes | Resolve conflicts manually |
| TIMEOUT | Operation took too long | Increase timeout or reduce scope |
| AUTHENTICATION_FAILED | Invalid token | Check authentication settings |

---

## Performance Considerations

### Optimization Tips

1. **Use streaming** - For long operations
2. **Set appropriate timeout** - Balance reliability vs wait time
3. **Batch operations** - Group related operations
4. **Cache results** - Avoid redundant operations
5. **Limit scope** - Operate on single cluster/layer when possible

### Monitoring

Track:
- Operation duration
- Success/failure rates
- Error types
- Streaming throughput
- Resource usage

---

## Security

### Authentication
- Optional token-based auth
- Header: `X-API-Token`
- Configure in mcp-server.yaml

### Rate Limiting
- Configurable per-minute limit
- Per-IP or global
- Returns 429 Too Many Requests

### CORS
- Configurable origins
- Default: localhost only
- Production: restrict to known hosts

---

## Extensibility

### Adding Operations

1. Define operation interface
2. Implement handler
3. Register in MCP server
4. Document parameters and results
5. Add configuration option

### Custom Tools

Wrap MCP operations for specific use cases:

```kotlin
class ProjectSyncTool : ToolInterface {
    override suspend fun execute(params: Map<String, Any>): ToolResult {
        val mcp = MCPClient()
        val result = mcp.invoke("ai_sync", params)
        // Process result
        return result
    }
}
```

