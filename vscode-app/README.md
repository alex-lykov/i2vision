# i2-Vision VSCode Extension

AI-powered coding assistant with agent-based automation, integrated directly into VSCode.

## Features

- **5 Pre-configured Agents**: Coding, Logic, Flow, Structure, Vision
- **6 Built-in Tools**: read_file, write_file, list_directory, search_files, run_command, get_file_context
- **Real-time Streaming**: Watch tool calls execute in real-time
- **Smart Loop Detection**: Prevents infinite loops with intelligent nudging
- **Plan Detection**: Forces tool execution when LLM describes plans
- **Safety First**: Path validation, command blocking, workspace isolation

## Requirements

- **VSCode**: 1.80.0 or higher
- **Ollama**: Required for LLM inference
- **Node.js**: 18.x or higher

### Install Ollama

```bash
# Windows
winget install Ollama.Ollama

# macOS
brew install ollama

# Linux
curl -fsSL https://ollama.com/install.sh | sh
```

### Pull Required Models

```bash
ollama pull qwen2.5-coder:7b
ollama pull qwen2.5:7b
```

## Installation

### From VSIX (Recommended)

1. Download `i2-vision-vscode-1.0.0.vsix`
2. Open VSCode
3. Extensions → ⋯ (More Actions) → Install from VSIX
4. Select the `.vsix` file
5. Reload VSCode

### From Source

```bash
cd vscode-app
npm install
npm run compile
npm run package
# Installs the extension automatically
```

## Quick Start

1. **Open Command Palette**: `Ctrl+Shift+P` (Windows/Linux) or `Cmd+Shift+P` (macOS)
2. **Create Agent Tab**: Select `i2-Vision: Create Agent Tab`
3. **Choose Agent**: Select from Coding, Logic, Flow, Structure, or Vision
4. **Start Chatting**: Ask the agent to help with your task

### Example Prompts

```
"List all TypeScript files in the current directory"
"Read the package.json file"
"Search for 'export function' in all .ts files"
"Create a new file called test.ts with a hello world function"
"Run npm test and show me the results"
```

## Agents

### Coding Agent
- **Model**: qwen2.5-coder:7b
- **Purpose**: Code generation, refactoring, debugging
- **Tools**: All file operations, command execution

### Logic Agent
- **Model**: qwen2.5:7b
- **Purpose**: Problem solving, algorithm design
- **Tools**: Read/search operations, analysis

### Flow Agent
- **Model**: qwen2.5:7b
- **Purpose**: Workflow automation, task orchestration
- **Tools**: Command execution, file operations

### Structure Agent
- **Model**: qwen2.5:7b
- **Purpose**: Architecture analysis, project structure
- **Tools**: Directory listing, search, context retrieval

### Vision Agent
- **Model**: qwen2.5-coder:7b
- **Purpose**: Visual understanding, UI analysis
- **Tools**: File operations, search

## Configuration

Agents are configured in `.vscode/i2vision/agents/*.yaml`. You can customize:

- System prompts
- Model selection
- Iteration limits
- Tool selection
- Timeouts

### Example Configuration

```yaml
key: my-custom-agent
agentType: code-assistant
name: "My Custom Agent"
description: "Custom coding assistant"
systemPromptTemplate: |
  You are a helpful coding assistant...
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
  toolTimeoutSeconds: 30
```

## Commands

| Command | Description |
|---------|-------------|
| `i2-Vision: Create Agent Tab` | Create a new agent chat tab |
| `i2-Vision: Focus Agent Tab` | Focus an existing agent tab |
| `i2-Vision: Clear Agent History` | Clear chat history in current tab |

## Tool Calling

The extension supports 6 built-in tools:

### read_file
```
Read the contents of a file

Parameters:
- path: string (relative to workspace root)
```

### write_file
```
Write content to a file

Parameters:
- path: string (relative to workspace root)
- content: string
```

### list_directory
```
List files and directories

Parameters:
- path: string (relative to workspace root, default: ".")
- recursive: boolean (default: false)
```

### search_files
```
Search for a pattern in files

Parameters:
- pattern: string (regex)
- path: string (optional, default: workspace root)
- file_pattern: string (optional, e.g., "*.ts")
```

### run_command
```
Execute a terminal command

Parameters:
- command: string
- working_dir: string (optional, default: workspace root)

Note: Long-running servers are blocked (npm run dev, gradlew run, etc.)
```

### get_file_context
```
Get context around a specific location in a file

Parameters:
- file: string
- line: number
- context_lines: number (default: 10)
```

## Safety Features

### Path Validation
- Blocks paths outside workspace
- Prevents directory traversal attacks
- Validates absolute vs relative paths

### Command Blocking
Blocked command patterns:
- `npm run dev`, `npm run start`
- `gradlew run`, `mvn spring-boot:run`
- `python app.py`, `node server.js`
- Any long-running server process

### Workspace Isolation
- Agents operate on workspace files only
- Extension files are protected
- Prevents accidental modification of extension code

## Troubleshooting

### Agent Not Responding
1. Check Ollama is running: `ollama list`
2. Verify model is pulled: `ollama pull qwen2.5-coder:7b`
3. Check Ollama server: `http://localhost:11434`

### Tool Calls Failing
1. Verify file paths are relative to workspace root
2. Check file permissions
3. Ensure commands don't match blocked patterns

### Loop Detection Triggered
- Agent detected repeated tool calls
- Try a different approach or be more specific in your request
- Agent will automatically nudge the LLM to try alternatives

### Plan Detection Triggered
- LLM described a plan instead of executing tools
- Agent will force tool execution
- Be direct in your requests: "Read file X" not "I should read file X"

## Development

See [docs/STRUCTURE.md](docs/STRUCTURE.md) for development workflow.

### Quick Start

```bash
cd vscode-app
npm install
npm run compile
# Press F5 to debug
```

### Packaging

```bash
npm install -g @vscode/vsce
vsce package
# Creates: i2-vision-vscode-1.0.0.vsix
```

## Documentation

- **[Extension Docs](docs/README.md)** - Extension-specific documentation
- **[Structure Guide](docs/STRUCTURE.md)** - File layout and architecture
- **[Tool Testing](docs/AGENT_TOOL_CALLING_TEST.md)** - How to test agent tools
- **[Main Project Docs](../docs/README.md)** - Core i2-vision documentation

## Changelog

See [CHANGELOG.md](CHANGELOG.md) for version history.

## License

MIT + Commercial (see [LICENSE](LICENSE))

## Support

- **Issues**: GitHub Issues
- **Discussions**: GitHub Discussions
- **Documentation**: [docs/README.md](docs/README.md)
