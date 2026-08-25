# i2-Vision VSCode Extension Documentation

## Overview

This documentation covers the i2-Vision AI agent extension for VSCode, which provides an intelligent coding assistant with multi-provider LLM support, tool execution, and session management.

## Documentation Structure

```
docs/
├── README.md                         # This file - documentation index
├── roadmap.md                        # Project roadmap
├── guides/                           # User-facing how-to guides
│   ├── agent-implementation.md       # Agent implementation guide
│   ├── context-management.md         # Context management guide
│   ├── context-management-quickref.md# Quick reference
│   ├── deepseek-integration.md       # DeepSeek setup
│   ├── ollama-integration.md         # Ollama setup
│   ├── vscode-provider-model-selection.md # Provider selection
│   ├── deployment.md                 # Deployment guide
│   ├── terminal-management.md        # Terminal management
│   ├── quick-start-terminal.md       # Terminal quick start
│   ├── apply-edits-tool.md           # Apply edits tool
│   └── structure.md                  # Extension structure
├── architecture/                     # System architecture
│   ├── agent-bridge.md               # Hub-and-spoke design
│   ├── tool-system.md                # Tool registry & VSLFC
│   ├── session-management.md         # Session lifecycle
│   ├── agent-session-sequence.md     # Session diagrams
│   ├── flow-diagram.md               # 3D LLM tool-call flow
│   ├── agent-state-machine.md        # State machine specification
│   └── provider-architecture.md      # Multi-provider architecture
├── reference/                        # API reference
│   ├── state-machine-quick-reference.md # State machine quick ref
│   └── documentation-update-status.md   # Doc update tracker
├── concepts/                         # Key concepts
│   ├── provider-rules.md             # Provider rules system
│   └── vslfc-layers.md               # VSLFC architecture (future)
├── migrations/                       # Migration guides
│   └── settings-mvvm.md              # Settings MVVM migration
├── tests/                            # Testing documentation
│   ├── mandatory-test-suite.md       # Test requirements
│   └── agent-tool-calling-test.md    # Agent tool tests
├── adr/                              # Architecture Decision Records
└── archive/                          # Historical documentation
    └── README.md                     # Archive policy & index
```

## Quick Links

### For Users
- [Provider Selection](guides/vscode-provider-model-selection.md) - Choose LLM providers
- [DeepSeek Integration](guides/deepseek-integration.md) - DeepSeek setup
- [Ollama Integration](guides/ollama-integration.md) - Ollama setup
- [Terminal Management](guides/terminal-management.md) - Terminal usage
- [Apply Edits Tool](guides/apply-edits-tool.md) - Code editing guide

### For Developers
- [Agent Bridge](architecture/agent-bridge.md) - Core agent implementation
- [Tool System](architecture/tool-system.md) - Tool registry and execution
- [Session Management](architecture/session-management.md) - LLM sessions
- [Provider Rules](concepts/provider-rules.md) - Editable per-provider rules
- [State Machine](architecture/agent-state-machine.md) - Agent state management
- [State Machine Quick Reference](reference/state-machine-quick-reference.md) - Quick reference

## Key Concepts

### Agent Types

The extension supports multiple agent types following the VSLFC architecture:

| Agent | Purpose |
|-------|---------|
| Vision | UI/UX analysis, screenshot interpretation |
| Structure | Architecture analysis, module relationships |
| Logic | Business logic, algorithms, data flow |
| Flow | Workflow, state machines, process orchestration |
| Code | Source code implementation, refactoring |

### Provider Model

The extension supports multiple LLM providers:

- **Ollama** - Local LLM inference (default)
- **DeepSeek** - DeepSeek AI API
- **Mistral** - Mistral AI API
- **3D LLM** - 3D LLM proxy with session management

Each provider can have custom rules configured via the provider rules system.

### Tool System

Tools are organized in a registry with layer-based filtering:

- **File Tools** - read_file, write_file, search_files, list_directory
- **Edit Tools** - apply_edits, get_file_context
- **Terminal Tools** - run_terminal, run_build, check_terminal_status
- **Git Tools** - git_status, git_diff, git_commit
- **Custom Tools** - User-defined tools via YAML or JavaScript plugins

### Session Management

Providers with session management capabilities (3D LLM, Mistral) support:

- Session persistence across requests
- Context compaction when approaching token limits
- Automatic session reset on errors
- Token usage tracking

## Extension Commands

| Command | Description |
|---------|-------------|
| `i2-Vision: Create Agent` | Create a new agent tab |
| `i2-Vision: Focus Agent` | Focus an existing agent tab |
| `i2-Vision: Settings` | Open settings webview |
| `i2-Vision: Clear History` | Clear conversation history |

## Configuration

### Agent Configuration

Agents are configured in `.vscode/i2vision/agents/*.yaml`:

```yaml
key: coding-agent
agentType: code-assistant
systemPromptTemplate: |
  You are a coding assistant...
model:
  id: qwen2.5-coder:7b
  provider: ollama
iterationSettings:
  maxIterations: 10
toolSelection:
  toolTimeoutSeconds: 30
```

### Provider Rules

Provider-specific rules can be edited via the Settings UI or directly in `.vscode/i2-vision-provider-rules.json`:

```json
{
  "ollama": [
    {
      "id": "max_edits",
      "description": "Maximum apply_edits operations",
      "providerId": "ollama",
      "defaultValue": 50
    }
  ]
}
```

## Development

### Setup

```bash
# Install dependencies
npm install

# Compile TypeScript
npm run compile

# Watch mode
npm run watch
```

### Debugging

1. Press F5 to launch Extension Development Host
2. The extension runs in a new VSCode window
3. Use the Debug Console for logging

### Packaging

```bash
# Install vsce
npm install -g @vscode/vsce

# Package extension
vsce package

# Install from .vsix
code --install-extension i2-vision-vscode-<version>.vsix
```

## Testing

### Manual Testing

See [Manual Test Procedures](tests/manual-tests.md) for detailed test cases.

### Automated Testing

```bash
# Run tests
npm test
```

## Related Documentation

- [Root Project Documentation](../docs/README.md)
- [Architecture Types Documentation](../architecture-types/docs/README.md)
- [Backlog Documentation](../backlog/docs/README.md)

## Contributing

1. Follow existing code style
2. Update documentation for new features
3. Add tests for new functionality
4. Create ADRs for significant architectural decisions

## License

MIT License - see LICENSE file for details
