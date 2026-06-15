# i2-Vision VSCode Extension - Project Summary

## Overview

A powerful AI-powered coding assistant that integrates agent-based automation directly into VSCode. The extension provides configurable AI agents with tool-calling capabilities, real-time streaming, and smart loop detection.

## Current Status: ✅ PRODUCTION READY

### Core Features Implemented

- **Agent System**: 5 pre-configured agents (Coding, Logic, Flow, Structure, Vision)
- **Tool Calling**: 6 tools (read_file, write_file, list_directory, search_files, run_command, get_file_context)
- **Streaming**: Real-time progress updates with chunk-based responses
- **Loop Detection**: Prevents infinite tool call loops with nudge strategy
- **Plan Detection**: Forces tool execution when LLM describes plans
- **Safety**: Path validation, command blocking, workspace isolation

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                     VSCode Extension                        │
├─────────────────────────────────────────────────────────────┤
│  AgentTabManager    │  AgentBridge      │  CLI Integration  │
│  - Webview UI       │  - Loop detection │  - Tool execution │
│  - Message display  │  - Streaming      │  - LLM calls      │
│  - Progress updates │  - Plan detection │  - Context fetch  │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                  Agent Configuration                        │
│  .vscode/i2vision/agents/*.yaml                             │
│  - System prompts                                           │
│  - Model settings                                           │
│  - Tool selection                                           │
│  - Iteration limits                                         │
└─────────────────────────────────────────────────────────────┘
```

## File Structure

```
vscode-app/
├── src/
│   ├── extension.ts              # Entry point
│   ├── agent/                    # Agent module
│   │   ├── AgentBridge.ts        # Core integration
│   │   ├── AgentTabManager.ts    # UI management
│   │   ├── LocalI2VisionAgent.ts # Agent implementation
│   │   └── ToolCard*.ts          # Tool card UI
│   ├── bridge/                   # Protocol layer
│   │   ├── UniversalAgentBridge.ts
│   │   ├── Protocol.ts
│   │   └── Transport.ts
│   └── test/                     # Tests
│
├── .vscode/i2vision/agents/      # Agent configs
│   ├── coding-agent.yaml
│   ├── logic-agent.yaml
│   ├── flow-agent.yaml
│   ├── structure-agent.yaml
│   └── vision-agent.yaml
│
├── docs/                         # Documentation
│   ├── README.md
│   ├── STRUCTURE.md
│   └── AGENT_TOOL_CALLING_TEST.md
│
├── package.json                  # Extension manifest
├── tsconfig.json                 # TypeScript config
├── README.md                     # User docs
├── CHANGELOG.md                  # Version history
└── LICENSE                       # MIT + Commercial
```

## Key Components

### 1. AgentBridge (`src/agent/AgentBridge.ts`)
- Manages agent lifecycle
- Handles tool execution and result linking
- Implements loop detection and plan detection
- Streams responses to UI

### 2. AgentTabManager (`src/agent/AgentTabManager.ts`)
- Creates and manages agent tabs
- Renders messages and tool calls
- Updates progress in real-time
- Handles user input

### 3. LocalI2VisionAgent (`src/agent/LocalI2VisionAgent.ts`)
- Core agent implementation
- Tool call parsing and execution
- Message formatting for Ollama
- Iteration management

### 4. UniversalAgentBridge (`src/bridge/UniversalAgentBridge.ts`)
- Cross-module communication
- Protocol abstraction
- Transport layer (HTTP/Stdio)

## Configuration

### Agent YAML Format

```yaml
key: coding-agent
agentType: code-assistant
name: "Code Agent"
description: "Helps with coding tasks"
systemPromptTemplate: |
  You are a coding assistant...
model:
  id: qwen2.5-coder:7b
  provider: ollama
  baseUrl: http://localhost:11434
iterationSettings:
  maxIterations: 10
  maxToolCallsPerIteration: 5
toolSelection:
  allowedTools:
    - read_file
    - write_file
    - list_directory
    - search_files
    - run_command
    - get_file_context
  toolTimeoutSeconds: 30
```

## Tool Calling Flow

```
User Request
    │
    ▼
┌─────────────┐
│ AgentTab    │
│ Manager     │
└──────┬──────┘
       │
       ▼
┌─────────────┐
│ AgentBridge │─── Loop Detection
└──────┬──────┘
       │
       ▼
┌─────────────┐
│ LocalAgent  │─── Parse Tool Calls
└──────┬──────┘
       │
       ▼
┌─────────────┐
│ CLI         │─── Execute Tools
└──────┬──────┘
       │
       ▼
┌─────────────┐
│ Result      │─── Link with tool_call_id
└──────┬──────┘
       │
       ▼
┌─────────────┐
│ Stream      │─── Update UI
└─────────────┘
```

## Safety Features

### Path Validation
- Blocks paths outside workspace
- Validates absolute vs relative paths
- Prevents directory traversal

### Command Blocking
Blocked patterns:
- `npm run dev`, `npm run start`
- `gradlew run`, `mvn spring-boot:run`
- `python app.py`, `node server.js`
- Any long-running server

### Extension Isolation
- `workspaceRoot` ≠ `extensionRoot`
- Agents operate on workspace, not extension files
- Prevents accidental modification of extension code

## Testing

### Manual Testing
1. Press F5 to launch Extension Development Host
2. Command Palette → `i2-Vision: Create Agent Tab`
3. Select agent (e.g., "Code Agent")
4. Test prompts:
   - "List files in current directory"
   - "Read package.json"
   - "Search for 'export' in *.ts files"

### Agent Tool Testing
See [docs/agent-tool-calling-test.md](docs/agent-tool-calling-test.md) for comprehensive test procedures.

## Development Workflow

### Setup
```bash
cd vscode-app
npm install
```

### Compile
```bash
npm run compile
```

### Debug (F5)
- Launches Extension Development Host
- Breakpoints enabled
- Live reload on changes

### Package
```bash
npm install -g @vscode/vsce
vsce package
# Creates: i2-vision-vscode-1.0.0.vsix
```

### Install
```bash
code --install-extension i2-vision-vscode-1.0.0.vsix
```

## Known Limitations

1. **Ollama Only**: Currently supports Ollama provider only
2. **No MCP**: MCP integration planned for future
3. **Local Agents**: Kotlin agents require local installation
4. **Tool Timeout**: 30-second timeout may be insufficient for large operations

## Future Roadmap

- [ ] MCP (Model Context Protocol) support
- [ ] Remote agent execution
- [ ] Additional LLM providers (OpenAI, Anthropic)
- [ ] Tool result caching
- [ ] Agent collaboration (multi-agent workflows)
- [ ] Enhanced loop detection with ML
- [ ] Custom tool creation UI

## Performance

- **Cold Start**: ~2 seconds
- **Tool Execution**: <1 second (typical)
- **Streaming Latency**: <100ms
- **Memory Usage**: ~50MB (idle), ~150MB (active)

## License

MIT + Commercial (see LICENSE)

## Support

- Documentation: [docs/README.md](docs/README.md)
- Issues: GitHub Issues
- Main Project: [../../docs/README.md](../../docs/README.md)
