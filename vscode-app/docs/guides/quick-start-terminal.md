# Quick Start: Terminal Management

## What Changed?

The agent now automatically distinguishes between:
- **Short commands** (git, ls, tests): Run with 30s timeout, return output
- **Long-running servers** (npm run dev, gradlew run): Run in persistent terminals with auto-restart

## For Users

### Nothing Changes - It Just Works!

When you ask the agent to:
- "Start the dev server" → Runs in a persistent terminal
- "Run the tests" → Executes and returns output
- "Build the project" → Executes and returns output

### Manual Control (Optional)

```
list_terminals()          # See what's running
kill_terminal("name")     # Stop a specific terminal
```

## For Developers

### Customize Long-Running Patterns

Create `.vision-ai/coding-agent.yaml` in your project:

```yaml
execution:
  longRunningPatterns:
    - "./gradlew bootRun"
    - "npm run dev:backend"
    - "docker-compose up"
```

### How It Works

1. Agent calls `run_terminal("npm run dev")`
2. System checks command against `longRunningPatterns`
3. If match → Starts persistent terminal with auto-restart
4. If no match → Runs with 30s timeout

### Auto-Restart

When a long-running terminal is started:
- Watches source files (`.kt`, `.java`, `.ts`, `.tsx`, etc.)
- Waits 1 second after last file change
- Automatically restarts the terminal

## Testing

### Quick Test

1. Ask agent: "Start the development server"
2. Check VS Code terminal panel - terminal should appear
3. Edit a source file
4. Watch terminal auto-restart after 1 second

### Manual Control Test

1. Ask agent: "What terminals are running?"
2. Ask agent: "Stop the backend server"
3. Verify terminal closes

## Troubleshooting

**Terminal not auto-restarting?**
- Check file extension is watched (.kt, .java, .ts, etc.)
- Look for "File watcher set up" log in output channel

**Command misclassified?**
- Add custom pattern to `.vision-ai/coding-agent.yaml`
- Reload agent config

**Too many terminals?**
- Use `list_terminals()` to see what's running
- Use `kill_terminal("<name>")` to stop

## See Also

- [Full Documentation](terminal-management.md)
- [Implementation Summary](implementation-summary.md)
