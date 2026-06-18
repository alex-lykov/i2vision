# VSCode Extension Documentation

## Guides

- [**Extension Structure**](structure.md) - Directory layout and key files
- [**Agent Tool Calling Test**](agent-tool-calling-test.md) - How to test agent tool calls
- [**apply_edits Tool**](apply-edits-tool.md) - Modern structured editing with retry logic
- [**Fix Mode**](FIX_MODE_IMPLEMENTATION.md) - Build failure auto-fix workflow

## Architecture

- **AgentBridge**: Core agent integration with loop detection, fix mode, and streaming
- **AgentTabManager**: Webview UI for agent interactions
- **CLI Integration**: Tool execution via command-line interface
- **File System Provider**: Virtual file system for agent tabs

## Key Features

### Agent Loop
- **Max Iterations**: 10 iterations per task
- **Loop Detection**: Tracks repeated tool calls, nudges LLM to try different approaches
- **Plan Detection**: Identifies when LLM describes plans instead of executing tools
- **Force Completion**: Stops after 4+ repeated tool calls

### Tool Calling
- **7 Tools**: read_file, write_file, apply_edits, list_directory, search_files, run_terminal, run_build
- **Tool Result Linking**: Uses `tool_call_id` for proper OpenAI/Ollama compatibility
- **Blocked Commands**: Prevents long-running servers (npm run dev, gradlew run, etc.)
- **Path Resolution**: Workspace-aware relative path handling

### Fix Mode (Auto-Fix Workflow)
- **Immediate Activation**: Activates on first build failure
- **Edit-Only Tools**: Filters to 4 tools (apply_edits, read_file, write_file, get_file_context)
- **Auto-Read**: Automatically reads all failing files before LLM acts
- **Retry Logic**: Failed edits retry up to 3 times before skipping the file
- **Guided Rebuild**: Auto-nudge suggests when to re-run the build

### Streaming
- **Real-Time Progress**: Shows tool calls as they execute
- **Chunk-Based**: Streams text and tool events separately
- **Fallback Support**: Falls back to non-streaming if needed

### Safety
- **Path Validation**: Blocks paths outside workspace
- **Extension Isolation**: workspaceRoot ≠ extensionRoot enforced
- **Command Blocking**: Long-running servers blocked
- **Error Handling**: DIRECTORY_NOT_FOUND, FILE_NOT_FOUND handled gracefully

## Configuration

Agents are configured in `.vscode/i2vision/agents/*.yaml`. See main docs for configuration reference.

## Development

See [structure.md](structure.md) for development workflow and packaging instructions.

## Related Documentation

- [Main Project Documentation](../../docs/README.md)
- [Agent Implementation Guide](../../docs/guides/agent-implementation.md)
- [Architecture Decisions](../../docs/adr/)
