# 🔍 i2-Vision Extension Debugging Guide

## Problem: F5 Launch Shows Same Issue

When pressing F5 to launch the Extension Development Host and the issue persists, follow this systematic debugging approach.

---

## ✅ Pre-Launch Checklist

### 1. Verify Compilation
```bash
cd D:\proj\AI\i2-vision\vscode-app
npm run compile
```
**Expected:** No errors, clean compilation

### 2. Check Debug Commands Are Registered
The following commands should be available:
- `i2vision.debugToolCalls` - Run all debugging checks
- `i2vision.verifyTools` - Verify tool definitions
- `i2vision.checkAgentConfig` - Check agent configuration
- `i2vision.reloadAgentConfig` - Clear config cache

### 3. Verify Agent Config Exists
Check: `vscode-app/.vscode/i2vision/agents/coding-agent.yaml`
- ✅ File exists
- ✅ YAML is valid
- ✅ Tool parsing rules are correct

---

## 🚀 Step-by-Step Debugging

### Step 1: Launch Extension Host (F5)

1. Press **F5** in VS Code
2. **New VS Code window opens** (Extension Development Host)
3. Watch the **Debug Console** in the original window for errors

### Step 2: Open Output Channel

In the **Extension Development Host** window:
1. Press `Ctrl+Shift+P` (Command Palette)
2. Type: `View: Toggle Output`
3. In the Output dropdown, select: **i2-Vision**

**Look for:**
```
i2-Vision extension activated
Workspace: D:\proj\AI\i2-vision
```

### Step 3: Run Debug Commands

Press `Ctrl+Shift+P` and run each command:

#### 3.1: Verify Tools
```
i2vision.verifyTools
```
**Expected Output:**
```
=== Tool Definitions Verification ===
Expected tools available to LLM:
  ✓ i2vision_discover
  ✓ i2vision_get_context
  ✓ read_file
  ✓ list_directory
  ✓ regex_search
  ✓ write_file
  ✓ edit_file
```

#### 3.2: Check Agent Config
```
i2vision.checkAgentConfig
```
**Expected Output:**
```
=== Configuration Check ===
✓ Tool call pattern: Found
✓ Tool call header: Found
✓ Reasoning header: Found
✓ EOS marker: Found
```

#### 3.3: Run All Debug Checks
```
i2vision.debugToolCalls
```
**Expected:** Comprehensive output showing all checks passed

### Step 4: Create Agent Tab

1. Press `Ctrl+Shift+P`
2. Run: `i2-Vision: New Coding Agent`
3. A new agent tab should appear in the sidebar

### Step 5: Test Tool Calling

In the agent tab, send this message:
```
Read vscode-app/package.json
```

**Watch for:**
1. **Output Channel** logs showing:
   - LLM response received
   - Tool call parsed
   - Tool executed
   - Result returned

2. **Agent Tab UI** showing:
   - Reasoning text
   - Tool card (if tool was called)
   - File content (if read_file succeeded)

---

## 🐛 Common Issues & Solutions

### Issue 1: Extension Doesn't Activate

**Symptoms:**
- No "i2-Vision extension activated" in Output
- Commands not available in Command Palette

**Debug:**
1. Check **Debug Console** for errors
2. Look at `extension.ts` line 27 (`activate` function)
3. Add temporary log:
   ```typescript
   outputChannel.appendLine('DEBUG: activate() called');
   ```

### Issue 2: Commands Not Registered

**Symptoms:**
- `i2vision.debugToolCalls` not found in Command Palette

**Debug:**
1. Check `extension.ts` line 228:
   ```typescript
   registerDebugCommands(context, outputChannel, agentManager);
   ```
2. Verify no errors before this line
3. Add log in `ToolCallDebugger.ts`:
   ```typescript
   outputChannel.appendLine('DEBUG: registerDebugCommands called');
   ```

### Issue 3: Agent Tab Not Created

**Symptoms:**
- Command runs but no tab appears

**Debug:**
1. Check Output Channel for errors
2. Verify `AgentTabManager` initialization
3. Check tree view provider is registered

### Issue 4: Tool Calls Not Parsed

**Symptoms:**
- LLM responds but no tool card appears
- Output shows raw LLM response

**Debug:**
1. Check parsing patterns in `coding-agent.yaml`:
   ```yaml
   formattingRules:
     reasoningHeader: "reasoning:"
     toolCallHeader: "tool_call:"
     eosMarker: "EOS"
   parsing:
     headerPattern: "^(reasoning|tool_call|EOS|Observation)\\s*:?\\s*"
     toolCallPattern: "\\{\\s*\"tool\"\\s*:\\s*\"[A-Za-z0-9_]+\""
   ```

2. Add parsing logs in the parser code
3. Test with simple message: "What is 2+2?" (no tool needed)

### Issue 5: Config Not Loaded

**Symptoms:**
- `i2vision.checkAgentConfig` shows MISSING items

**Debug:**
1. Verify file path: `vscode-app/.vscode/i2vision/agents/coding-agent.yaml`
2. Check YAML syntax (use online validator)
3. Verify file is not in `.gitignore`

---

## 📊 Debug Output Template

When reporting issues, include:

```
=== Extension Activation ===
[ ] Output shows "i2-Vision extension activated"
[ ] Workspace path is correct
[ ] No errors in Debug Console

=== Debug Commands ===
[ ] i2vision.verifyTools: PASSED/FAILED
[ ] i2vision.checkAgentConfig: PASSED/FAILED
[ ] i2vision.debugToolCalls: PASSED/FAILED

=== Agent Tab ===
[ ] Tab created successfully
[ ] Input field visible
[ ] Send button works

=== Tool Calling Test ===
Message sent: "Read vscode-app/package.json"
Output Channel logs:
[paste logs here]

Agent Tab UI:
[ ] Reasoning shown
[ ] Tool card shown
[ ] File content shown
```

---

## 🔧 Advanced Debugging

### Enable Verbose Logging

Add to `extension.ts` activate():
```typescript
outputChannel.appendLine('=== DEBUG: Extension Starting ===');
outputChannel.appendLine(`Extension path: ${context.extensionPath}`);
outputChannel.appendLine(`Workspace: ${workspaceRoot}`);
```

### Watch Tool Call Parsing

In the agent's message handler, add:
```typescript
outputChannel.appendLine(`DEBUG: LLM Response:\n${response}`);
outputChannel.appendLine(`DEBUG: Parsed tools: ${tools.length}`);
```

### Test Tool Execution Directly

Create a test command:
```typescript
const testCmd = vscode.commands.registerCommand('i2vision.testReadFile', async () => {
    const content = await fileSystem.readFile('vscode-app/package.json');
    outputChannel.appendLine(`File content: ${content?.substring(0, 200)}`);
});
```

---

## 📝 Next Steps

1. **Run the checklist above**
2. **Capture Output Channel logs**
3. **Share specific error messages**
4. **Note which step fails**

This will help identify the exact failure point quickly.
