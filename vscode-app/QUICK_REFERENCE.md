# Quick Reference: Agent Tool Calling

## 🚀 Quick Start

```bash
# 1. Launch Extension
F5 (in VSCode)

# 2. Open Output Channel
View → Output → i2-Vision

# 3. Create Agent Tab
Ctrl+Shift+P → "i2-Vision: New Coding Agent"

# 4. Test Tool Calling
Send: "Read vscode-app/package.json"

# 5. Check Results
- Output Channel: Look for tool logs
- Agent Tab: Look for tool cards
```

## 🔧 Debug Commands

| Command | Description |
|---------|-------------|
| `i2vision.debugToolCalls` | Run all debugging checks |
| `i2vision.verifyTools` | Verify tool definitions |
| `i2vision.checkAgentConfig` | Check agent configuration |

## 📊 Expected Logs

```
[Agent:xxx] Processing input: "Read vscode-app/package.json"
[Agent:xxx] Calling model: ollama/llama3.2
[Agent:xxx] Passing 7 tools to LLM
[Agent:xxx] LLM response received (234 chars)
[Agent:xxx] Parsing LLM response (234 chars)
[Agent:xxx] Found tool call JSON: {"tool":"read_file","args":{"path":"vscode-app/package.json"}}
[Agent:xxx] Parsed 1 tool calls
[Agent:xxx] Executing tool: read_file with args: {"path":"vscode-app/package.json"}
[Agent:xxx] Tool read_file completed successfully (1234 chars)
[Agent:xxx] Agent completed in 1523ms with 1 iterations
```

## ✅ Expected UI

```
┌─────────────────────────────────────┐
│ i2-Vision Coding Agent              │
├─────────────────────────────────────┤
│ Model: ollama/llama3.2              │
│ Max Iterations: 10                  │
├─────────────────────────────────────┤
│ [User] Read vscode-app/package.json │
│                                     │
│ [Agent] I'll read the file...       │
│                                     │
│ 🛠️ Tools Used (1)                  │
│ ┌─────────────────────────────────┐ │
│ │ read_file ✅ Success            │ │
│ │ Args: {"path": "..."}           │ │
│ │ Result: {...}                   │ │
│ └─────────────────────────────────┘ │
│                                     │
│ ⏱️ 1523ms | Iterations: 1          │
└─────────────────────────────────────┘
```

## 🐛 Common Issues

| Issue | Solution |
|-------|----------|
| No tool cards | Check Output Channel for parsing logs |
| Corrupted emojis | Fixed in code, ensure UTF-8 encoding |
| Tool execution fails | Check file paths are relative to workspace |
| No results shown | Check tool execution logs for errors |

## 📁 Key Files

| File | Purpose |
|------|---------|
| `AgentBridge.ts` | Tool parsing & execution |
| `AgentTabManager.ts` | UI & tool card display |
| `ToolCallDebugger.ts` | Debugging utility |
| `DEBUGGING_TOOL_CALLS.md` | Full debugging guide |
| `AGENT_TOOL_CALLING_FIXES.md` | Complete fix summary |

## 🎯 Test Messages

1. **File Read**: "Read vscode-app/package.json"
2. **Directory List**: "List vscode-app/src directory"
3. **Regex Search**: "Search for 'function' in vscode-app/src"
4. **No Tool**: "What is 2+2?"

## 🔍 Debugging Checklist

- [ ] Output Channel shows "Found tool call JSON"
- [ ] Output Channel shows "Executing tool: xxx"
- [ ] Output Channel shows "Tool xxx completed successfully"
- [ ] Webview shows tool card with ✅ status
- [ ] Tool card shows Args section
- [ ] Tool card shows Result section
- [ ] No JavaScript errors in webview console
- [ ] Emojis display correctly (🛠️ ✅ ❌)

---

**Quick Help**: If stuck, run `i2vision.debugToolCalls` command and check Output Channel.
