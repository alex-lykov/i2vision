# Changelog

## [1.0.0] - 2024

### Added
- Agent-based AI assistance with configurable agents
- Real-time streaming progress updates
- Tool calling: read_file, write_file, list_directory, search_files, run_command, get_file_context
- Smart loop detection with nudge strategy
- Plan detection to force tool execution
- Blocked commands for long-running servers
- Workspace-aware path resolution
- Extension isolation (workspaceRoot ≠ extensionRoot)

### Changed
- Removed `tool_calls` from assistant messages (Ollama compatibility)
- Added `tool_call_id` linking for proper tool result association
- Simplified error messages and removed temporary comments
- Improved documentation structure

### Fixed
- Ollama 400 errors from incompatible message format
- Infinite tool call loops
- Plan-only responses without execution
- Directory not found handling
- Empty result formatting
