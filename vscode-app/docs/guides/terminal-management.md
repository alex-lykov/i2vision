# Terminal Management System

## Overview

The unified terminal management system automatically classifies commands as either **short-lived** or **long-running**, routing them to the appropriate execution mode:

- **Short-lived commands** (git, ls, npm test): Execute with 30s timeout, return stdout/stderr
- **Long-running servers** (npm run dev, gradlew run): Run in persistent VS Code terminals with auto-restart on file changes

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    AgentBridge                              │
│  ┌──────────────────────────────────────────────────────┐  │
│  │              run_terminal tool                       │  │
│  └──────────────────────────────────────────────────────┘  │
│                           │                                 │
│                           ▼                                 │
│  ┌──────────────────────────────────────────────────────┐  │
│  │         classifyCommand()                            │  │
│  │  Checks against longRunningPatterns from YAML        │  │
│  └──────────────────────────────────────────────────────┘  │
│                           │                                 │
│              ┌────────────┴────────────┐                   │
│              │                         │                   │
│              ▼                         ▼                   │
│     ┌─────────────────┐      ┌──────────────────┐         │
│     │ Short Command   │      │ Long-Running     │         │
│     │ runCommandWith  │      │ TerminalManager  │         │
│     │ Timeout()       │      │ runInTerminal()  │         │
│     │ (30s timeout)   │      │ (persistent)     │         │
│     └─────────────────┘      └──────────────────┘         │
│              │                         │                   │
│              ▼                         ▼                   │
│     ┌─────────────────┐      ┌──────────────────┐         │
│     │ Return output   │      │ Auto-restart on  │         │
│     │ to LLM          │      │ file changes     │         │
│     └─────────────────┘      └──────────────────┘         │
└─────────────────────────────────────────────────────────────┘
```

## Configuration

### Default Patterns

The default long-running patterns are defined in `conf-agent-core/src/commonMain/resources/default-agent-config.yaml`:

```yaml
execution:
  longRunningPatterns:
    - "run"
    - "serve"
    - "dev"
    - "start"
    - "watch"
    - "nodemon"
    - "vite"
    - "next dev"
    - "spring-boot:run"
    - "jetty:run"
    - "webpack --watch"
    - "tsc --watch"
    - "gulp watch"
    - "grunt watch"
    - "cargo run"
    - "go run"
    - "python -m uvicorn"
    - "poetry run"
    - "./gradlew :app:server:run"
    - "npm run dev"
    - "make run"
```

### Project-Specific Override

Create `.vision-ai/coding-agent.yaml` in your project to customize patterns:

```yaml
# Override long-running patterns for this specific project
execution:
  longRunningPatterns:
    - "./gradlew bootRun"
    - "npm run dev:backend"
    - "npm run dev:frontend"
    - "docker-compose up"
    - "make dev"
```

## Usage

### Automatic Classification

When the agent calls `run_terminal`, the system automatically classifies the command:

```typescript
// Short-lived - returns output immediately
run_terminal("git status")
run_terminal("npm test")
run_terminal("./gradlew build")

// Long-running - starts in persistent terminal with auto-restart
run_terminal("npm run dev")
run_terminal("./gradlew bootRun")
run_terminal("vite")
```

### Manual Terminal Control

The agent can also manually manage terminals:

```typescript
// List all running terminals
list_terminals()
// Returns: "Managed terminals:\n• backend: npm run dev [auto-restart: ON]"

// Stop a specific terminal
kill_terminal("backend")
// Returns: "Terminal "i2-Vision: backend" stopped."
```

## Auto-Restart Behavior

When a long-running terminal is started with `restartOnChanges=true`:

1. **File Watcher**: Watches for changes to source files (`.kt`, `.java`, `.ts`, `.tsx`, `.js`, `.jsx`, `.py`, `.go`, `.rs`, `.cpp`)
2. **Debouncing**: Waits 1 second (configurable) after the last file change
3. **Auto-Restart**: Kills the old terminal and starts a new one with the same command

### Example Flow

```
1. Agent: run_terminal("./gradlew bootRun")
   → TerminalManager: Creates terminal "i2-Vision: gradlew-bootRun"
   → Sets up file watcher for *.kt, *.java files

2. User edits MainService.kt
   → File watcher detects change
   → Debounce timer starts (1s)

3. User saves another file (Utils.kt)
   → File watcher detects change
   → Debounce timer resets

4. After 1s with no changes:
   → TerminalManager kills old terminal
   → Starts new terminal with "./gradlew bootRun"
   → Logs: "Debounced restart triggered for gradlew-bootRun"
```

## Implementation Details

### TerminalManager

Located at `vscode-app/src/agent/TerminalManager.ts`:

- **Singleton pattern**: One manager per extension instance
- **Named terminals**: Each terminal has a unique name for tracking
- **Resource cleanup**: Automatic disposal on extension deactivate
- **Configurable debounce**: Default 1000ms for Gradle projects

### Command Classification

The `classifyCommand()` method checks:

1. Configured `longRunningPatterns` from YAML
2. Heuristics: commands containing "watch", "dev", "server", "serve"

### Safety

- **Blocked commands**: Dangerous commands (rm -rf, format, etc.) are always blocked
- **Timeout**: Short commands have 30s timeout to prevent hangs
- **Terminal isolation**: Each terminal runs in its own process group

## Testing

### Manual Testing

1. Start a server: `run_terminal("npm run dev")`
2. Check terminal list: `list_terminals()`
3. Edit a source file
4. Verify auto-restart happens after debounce
5. Stop the server: `kill_terminal("<name>")`

### Agent Testing

Ask the agent to:
- "Start the development server"
- "What terminals are running?"
- "Stop the backend server"

## Migration from Old Approach

### Before (Manual Classification)

```typescript
// AgentBridge had hardcoded blocked patterns
// Long-running commands were blocked entirely
// User had to run servers manually
```

### After (Unified Approach)

```typescript
// Automatic classification based on YAML config
// Long-running commands use persistent terminals
// Auto-restart on file changes
// Manual control via kill_terminal/list_terminals
```

## Troubleshooting

### Terminal Not Auto-Restarting

1. Check file watcher is set up (look for log: "File watcher set up for terminal...")
2. Verify file extension is watched (.kt, .java, .ts, etc.)
3. Check debounce delay (default 1000ms)

### Command Misclassified

1. Check `longRunningPatterns` in YAML config
2. Add custom pattern if needed
3. Reload agent config

### Resource Leaks

1. Call `terminalManager.dispose()` on extension deactivate
2. Check all terminals are killed: `list_terminals()` should return empty
