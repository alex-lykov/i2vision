# VSCode Extension Structure

## Directory Layout

```
vscode-app/
+-- src/                          # Source code
¦   +-- extension.ts              # Extension entry point
¦   +-- cliIntegration.ts         # CLI wrapper for agent tools
¦   +-- fileSystemIntegration.ts  # VSCode FileSystemProvider
¦   +-- treeViewProvider.ts       # Sidebar tree view
¦   +-- agent/                    # Agent-related code
¦   ¦   +-- AgentBridge.ts        # Bridge between VSCode and agent core (hub)
¦   ¦   +-- AgentBridge.CommandExecutor.ts    # Command validation, build/terminal/Git execution
¦   ¦   +-- AgentBridge.Diagnostics.ts       # Diagnostics logging and structured data
¦   ¦   +-- AgentBridge.LegacyTools.ts       # Legacy tool wrappers and name mapping
¦   ¦   +-- AgentBridge.LLMAdapter.ts        # Provider capability detection and model config
¦   ¦   +-- AgentBridge.SearchCache.ts       # TTL-based search result caching
¦   ¦   +-- AgentBridge.SessionManagerBridge.ts  # Session error classification
¦   ¦   +-- AgentBridge.ToolPipeline.ts      # Tool execution, retry, rate-limit, stats
¦   ¦   +-- AgentTabManager.ts    # Manages agent tab UI
¦   +-- bridge/                   # Integration bridges
¦   ¦   +-- UniversalBridge.ts    # Cross-module communication
¦   +-- test/                     # Test files
¦       +-- extension.test.ts
¦
+-- .vscode/                      # VSCode configuration
¦   +-- launch.json               # Debug configurations
¦   +-- settings.json             # Workspace settings
¦   +-- tasks.json                # Build tasks
¦   +-- i2vision/                 # Extension-specific config
¦       +-- agents/               # Agent YAML configurations
¦           +-- coding-agent.yaml
¦           +-- logic-agent.yaml
¦           +-- flow-agent.yaml
¦           +-- structure-agent.yaml
¦           +-- vision-agent.yaml
¦
+-- resources/                    # Static resources (icons, images)
+-- scripts/                      # Build and utility scripts
¦   +-- copy-resources.js         # Resource copying script
¦
+-- package.json                  # Extension manifest
+-- tsconfig.json                 # TypeScript configuration
+-- .gitignore                    # Git ignore rules
+-- .vscodeignore                 # VSCE packaging ignore
+-- LICENSE                       # License file
+-- README.md                     # User-facing documentation
+-- CHANGELOG.md                  # Version history
```

## Key Files

### `extension.ts`
Main entry point. Activates extension, registers commands, initializes providers.

### `AgentBridge.ts`
Bridges VSCode extension with agent core. Hub module delegating to spoke modules. Handles:
- Agent lifecycle
- Tool execution (delegated to `AgentBridge.ToolPipeline.ts`)
- Streaming responses
- Loop detection
- Plan detection
- Diagnostics (delegated to `AgentBridge.Diagnostics.ts`)
- LLM adapter configuration (delegated to `AgentBridge.LLMAdapter.ts`)
- Search caching (delegated to `AgentBridge.SearchCache.ts`)
- Session error handling (delegated to `AgentBridge.SessionManagerBridge.ts`)
- Command execution, build verification, Git operations (delegated to `AgentBridge.CommandExecutor.ts`)
- Legacy tool mapping (delegated to `AgentBridge.LegacyTools.ts`)

### `AgentTabManager.ts`
Manages agent tab UI in webview. Handles:
- Message display
- Tool call visualization
- Progress updates
- User input

### `cliIntegration.ts`
Wrapper around CLI commands. Provides:
- LLM calls via Ollama
- File operations
- Command execution
- Context retrieval

## Agent Configuration

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

## Development Workflow

1. **Edit Code**: Modify files in `src/`
2. **Compile**: `npm run compile` or F5 to debug
3. **Test**: Use Extension Development Host
4. **Package**: `vsce package` creates `.vsix`

## Testing

### Manual Testing
1. Press F5 to launch Extension Development Host
2. Create agent tab via Command Palette
3. Test tool calls with various prompts

### Agent Tool Testing
See `docs/agent-tool-calling-test.md` for detailed test procedures.

## Packaging

```bash
# Install vsce
npm install -g @vscode/vsce

# Package extension
vsce package

# Install from .vsix
code --install-extension i2-vision-vscode-1.0.0.vsix
```

## Related Documentation

- [Main Documentation](../vscode-app/docs/README.md)
- [Agent Implementation Guide](../vscode-app/docs/guides/agent-implementation.md)
- [Agent Configuration Reference](../vscode-app/docs/reference/agent-config.md)

