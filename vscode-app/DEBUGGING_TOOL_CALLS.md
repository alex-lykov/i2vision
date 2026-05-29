# Debugging Guide: Agent Tool Calling & Display

This guide explains how to debug the agent's tool calling mechanism and tool card display in the i2-Vision VSCode extension.

## 📋 Overview

The tool calling flow consists of these components:

1. **AgentBridge** (`AgentBridge.ts`) - Parses LLM responses and executes tools
2. **LocalI2VisionAgent** (`LocalI2VisionAgent.ts`) - Wraps AgentBridge for VSLFC layers
3. **AgentTabManager** (`AgentTabManager.ts`) - Manages webview UI and displays tool cards
4. **Webview HTML** - Embedded in AgentTabManager, renders tool cards

## 🔧 Key Changes Made

### 1. AgentBridge.ts - Improved Tool Parsing
- **Multiple parsing strategies**: Config pattern → Direct pattern → Generic JSON search
- **Better logging**: Every step is logged to output channel
- **Enhanced error handling**: Clear error messages for tool execution failures
- **Fixed JSON escaping**: Corrected example in system prompt (removed backslash escape)

### 2. AgentTabManager.ts - Fixed Tool Card Display
- **Fixed emoji encoding**: Replaced corrupted Unicode with proper emojis (🛠️ ✅ ❌)
- **Full tool data**: Now passes complete `ToolCall` objects instead of just names
- **Better result preview**: Increased character limit from 200 to 500
- **Added iteration info**: Shows duration and iteration count
- **Enhanced logging**: Logs tool call names for debugging

## 🐛 Common Issues & Solutions

### Issue 1: Tool Calls Not Being Detected

**Symptoms**: Agent responds but no tool cards appear

**Debug Steps**:
1. Open Output Channel (`View → Output → i2-Vision`)
2. Look for these log messages:
   ```
   [Agent:xxx] Parsing LLM response (xxx chars)
   [Agent:xxx] Found tool call JSON: {...}
   [Agent:xxx] Parsed X tool calls
   ```
3. If no tool calls found, check:
   - LLM response format matches expected pattern
   - System prompt formatting instructions are clear
   - Tool definitions are correct

**Solution**:
- Check the LLM response format in logs
- Verify the `toolCallPattern` in agent config YAML
- Try manual test with exact format from system prompt

### Issue 2: Tool Cards Show But No Results

**Symptoms**: Tool cards appear but result section is empty

**Debug Steps**:
1. Check logs for tool execution:
   ```
   [Agent:xxx] Executing tool: read_file with args: {...}
   [Agent:xxx] Tool read_file completed successfully (xxx chars)
   ```
2. Look for errors:
   ```
   [Agent:xxx] Tool read_file failed: File not found
   ```

**Solution**:
- Verify file paths are correct (relative to workspace root)
- Check CLI integration is working
- Ensure workspace is properly opened

### Issue 3: Corrupted Emojis or Characters

**Symptoms**: Tool card shows `dY>��,?` instead of 🔧

**Solution**:
- ✅ **FIXED** - Updated AgentTabManager.ts with proper Unicode emojis
- If issue persists, check file encoding (should be UTF-8)

### Issue 4: Tool Execution Errors

**Symptoms**: Tool card shows red "❌ Error" status

**Debug Steps**:
1. Check error message in tool card
2. Look in Output Channel for detailed error:
   ```
   [Agent:xxx] Tool xxx failed: [error message]
   ```

**Common Errors**:
- `File not found` → Check path is relative to workspace root
- `Unknown tool` → Tool name doesn't match switch case
- `Permission denied` → File system access issue

## 🧪 Testing Tool Calls

### Manual Test 1: Simple File Read

Send this message to the agent:
```
Read the file vscode-app/src/extension.ts and tell me what it does
```

**Expected behavior**:
1. Agent should call `read_file` tool
2. Tool card should show:
   - Tool name: `read_file`
   - Args: `{"path": "vscode-app/src/extension.ts"}`
   - Status: ✅ Success
   - Result: File contents (truncated to 500 chars)

### Manual Test 2: Directory Listing

Send this message:
```
List the contents of the vscode-app/src directory
```

**Expected behavior**:
1. Agent should call `list_directory` tool
2. Tool card should show directory contents

### Manual Test 3: Regex Search

Send this message:
```
Search for all functions named "process" in vscode-app/src
```

**Expected behavior**:
1. Agent should call `regex_search` tool
2. Tool card should show search results

## 📊 Monitoring Tool Calls

### Output Channel Logs

Key log patterns to watch:

```
[Agent:xxx] Processing input: "..."
[Agent:xxx] Calling model: ollama/llama3.2
[Agent:xxx] Passing 7 tools to LLM
[Agent:xxx] LLM response received (xxx chars): ...
[Agent:xxx] Parsing LLM response (xxx chars)
[Agent:xxx] Found tool call JSON: {...}
[Agent:xxx] Parsed 1 tool calls
[Agent:xxx] Executing tool: read_file with args: {...}
[Agent:xxx] Tool read_file completed successfully (xxx chars)
[Agent:xxx] Agent completed in 1234ms with 1 iterations
```

### Webview Developer Tools

To debug webview rendering:
1. Open agent tab
2. Press `Ctrl+Shift+P` → `Developer: Toggle Developer Tools`
3. Check Console for JavaScript errors
4. Inspect tool card HTML structure

## 🔍 Code Locations

### Tool Parsing Logic
**File**: `vscode-app/src/agent/AgentBridge.ts`
**Method**: `parseLLMResponse()`
**Lines**: ~585-645

### Tool Execution Logic
**File**: `vscode-app/src/agent/AgentBridge.ts`
**Method**: `executeTool()`
**Lines**: ~647-680

### Tool Card Rendering
**File**: `vscode-app/src/agent/AgentTabManager.ts`
**Method**: `getWebviewContent()` (embedded JavaScript)
**Lines**: ~494-520 (addMessage function)

### Tool Data Flow
**File**: `vscode-app/src/agent/AgentTabManager.ts`
**Method**: `processUserInput()`
**Lines**: ~175-197

## 🛠️ Debugging Checklist

When tool calling isn't working:

- [ ] Output Channel shows "Found tool call JSON"
- [ ] Output Channel shows "Executing tool: xxx"
- [ ] Output Channel shows "Tool xxx completed successfully"
- [ ] Webview shows tool card with ✅ status
- [ ] Tool card shows Args section
- [ ] Tool card shows Result section
- [ ] No JavaScript errors in webview console
- [ ] Emojis display correctly (🛠️ ✅ ❌)

## 📝 Configuration

Agent configuration files are in:
```
.vscode/i2vision/agents/
  - coding-agent.yaml
  - vision-agent.yaml
  - structure-agent.yaml
  - logic-agent.yaml
  - flow-agent.yaml
```

Key settings to check:
```yaml
parsing:
  toolCallPattern: "tool_call:\\s*(\\{[^}]+\\})"  # Must match LLM output format
  
formattingRules:
  toolCallHeader: "tool_call:"
  reasoningHeader: "reasoning:"
  eosMarker: "EOS"
```

## 🚀 Quick Start Debugging

1. **Open Output Channel**: `View → Output → i2-Vision`
2. **Create Agent Tab**: Click "New Coding Agent" command
3. **Send Test Message**: "Read vscode-app/package.json"
4. **Watch Logs**: Look for tool parsing and execution messages
5. **Check UI**: Verify tool card appears with correct data
6. **Inspect Errors**: If fails, check error messages in logs

## 📞 Support

If issues persist after following this guide:

1. Check Output Channel logs for detailed errors
2. Verify agent configuration YAML is valid
3. Ensure Ollama/LLM backend is running
4. Test CLI commands manually
5. Check VSCode Developer Tools for webview errors

---

**Last Updated**: 2024
**Version**: 1.0
