# Shell Command Working Directory Fix

## Problem

The agent's `run_command` tool was failing with Git and other commands because it wasn't consistently using the **workspace root** as the working directory.

### Error Example
```
error: unknown option `cached'
usage: git diff --no-index [<options>] <path> <path>
```

This occurred because Git fell back to `--no-index` mode when not running from within a Git repository directory.

## Root Cause

The `CLI.runCommand()` method was not enforcing the workspace root as the default working directory, causing commands to execute from unpredictable locations.

## Solution

### 1. Updated `cliIntegration.ts`

**Key Changes:**

```typescript
/**
 * Run a shell command
 * 
 * CRITICAL: Always uses workspace root as working directory unless explicitly overridden
 * This ensures Git and other tools work correctly with the project
 */
async runCommand(command: string, workingDir?: string): Promise<{ stdout: string, stderr: string }> {
  // ALWAYS use workspace root if not explicitly provided
  const effectiveWorkingDir = workingDir || this.workspaceRoot;
  
  this.log(`Running command: ${command}`);
  this.log(`Working directory: ${effectiveWorkingDir}`);
  
  try {
    const options = { cwd: effectiveWorkingDir };
    const { stdout, stderr } = await execAsync(command, options);
    return { stdout, stderr };
  } catch (error: any) {
    throw error;
  }
}
```

**Added Methods:**
- `getWorkspaceRoot()` - Returns the authoritative workspace root
- `runGitCommand(args: string[])` - Convenience wrapper for Git commands
- `readFile(filePath: string)` - Read file contents
- `writeFile(filePath: string, content: string)` - Write file contents

### 2. Workspace Root Validation

```typescript
constructor(workspaceRoot: string, outputChannel?: any) {
  this.workspaceRoot = workspaceRoot;
  
  // Validate workspace root
  if (!this.workspaceRoot) {
    this.log('⚠️ WARNING: No workspace folder open - using current directory');
    this.workspaceRoot = process.cwd();
  } else {
    this.log(`Workspace root: ${this.workspaceRoot}`);
  }
  // ...
}
```

## Benefits

| Before | After |
|--------|-------|
| ❌ Commands fail outside Git repo | ✅ Always works from workspace root |
| ❌ Inconsistent behavior | ✅ Predictable, reliable execution |
| ❌ Agent gets stuck in loops | ✅ Commands succeed on first try |
| ❌ No working directory logging | ✅ Full visibility in logs |

## Usage Examples

### Git Commands (Now Work Reliably)
```typescript
// This now works correctly!
await cli.runCommand('git diff --cached --name-only');
// Working directory: D:/proj/AI/i2-vision

// Or use the convenience wrapper
await cli.runGitCommand(['diff', '--cached', '--name-only']);
```

### File Operations
```typescript
// Read file
const content = await cli.readFile('vscode-app/src/extension.ts');

// Write file
await cli.writeFile('output.txt', 'Hello World');

// List files
const files = await cli.listFiles('src', true); // recursive
```

### Shell Commands
```typescript
// Build command
await cli.runCommand('./gradlew build');

// Run tests
await cli.runCommand('npm test', 'vscode-app'); // override working dir
```

## Files Modified

- `vscode-app/src/cliIntegration.ts` - Core CLI integration with workspace-aware commands

## Testing

Verify the fix works:

```bash
cd D:\proj\AI\i2-vision\vscode-app
npm run compile

# Test Git commands
node -e "
const { CLI } = require('./out/cliIntegration.js');
const cli = new CLI('D:/proj/AI/i2-vision');
cli.runCommand('git diff --cached --name-only').then(r => console.log(r.stdout));
"
```

Expected output:
```
vscode-app/src/agent/ToolCardConfig.ts
vscode-app/src/agent/ToolCardFormatter.ts
vscode-app/src/agent/ToolCardManager.ts
```

## Best Practices Going Forward

1. **Always use workspace root** - The CLI constructor captures it, all methods use it
2. **Log working directory** - Every command logs where it's running
3. **Use convenience wrappers** - `runGitCommand()` ensures Git always works
4. **Override only when needed** - Pass `workingDir` only for special cases

## Related Files

- `vscode-app/src/agent/AgentBridge.ts` - Uses CLI for tool execution
- `vscode-app/src/agent/LocalAgentProvider.ts` - Creates CLI instances
- `vscode-app/src/extension.ts` - Initializes workspace root

---

**Status:** ✅ Fixed and compiled successfully
