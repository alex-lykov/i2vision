# 🧪 Agent Tool Calling Test Guide

## What Was Fixed

### Problem
The agent was responding with reasoning but **not making tool calls** because:
1. System prompt didn't include formatting instructions
2. LLM didn't know the required `tool_call:` format
3. Parsing logic wasn't logging enough details for debugging

### Solution
✅ **Added formatting instructions** to system prompt automatically  
✅ **Enhanced parsing** with detailed logging  
✅ **Improved error handling** for malformed JSON  

---

## 🔧 Changes Made

### 1. AgentBridge.ts - System Prompt Builder
```typescript
// Now appends formatting rules to every system prompt
prompt += '\n\n' + this.config.formattingRules.rules;
prompt += '\nAlways use this format: reasoning: <text> | tool_call: {"tool":"...","args":{...}} | EOS';
prompt += '\nIf no tool is needed, just output: reasoning: <text> | EOS';
```

### 2. AgentBridge.ts - Response Parser
```typescript
// Added detailed logging
this.log(`Parsing LLM response (${response.length} chars)`);
this.log(`Found tool call JSON: ${jsonStr.substring(0, 100)}`);
this.log(`Parsed ${toolCalls.length} tool calls, reasoning: ${reasoning.substring(0, 100)}...`);
```

### 3. AgentTabManager.ts - UI Display
- Added tool call visualization with args/results
- Shows success/error status for each tool
- Displays tool result previews

---

## 🚀 How to Test

### Step 1: Reload VSCode Extension
1. Press `Ctrl+Shift+P`
2. Select **"Developer: Reload Window"**

### Step 2: Create Agent Tab
1. Press `Ctrl+Shift+P`
2. Type: `i2-Vision: Create Agent Tab`
3. Select **"Code Agent"**

### Step 3: Test Tool Calls

#### Test 1: File Reading ✅
**Prompt:**
```
Read the file vscode-app/src/extension.ts and tell me what it does
```

**Expected LLM Response Format:**
```
reasoning: I need to read the extension.ts file to understand its purpose.
tool_call: {"tool":"read_file","args":{"path":"vscode-app/src/extension.ts"}}
EOS
```

**Expected UI Display:**
```
┌─────────────────────────────────────────┐
│ I need to read the extension.ts file... │
├─────────────────────────────────────────┤
│ 🛠️ Tools Used (1)                      │
│ read_file ✅ Success                    │
│ Args: {"path":"vscode-app/src/ext..."} │
│ Result: [file content preview]         │
└─────────────────────────────────────────┘
```

#### Test 2: Directory Listing ✅
**Prompt:**
```
List the contents of the vscode-app/src directory
```

**Expected:** `list_directory` tool call

#### Test 3: Code Search ✅
**Prompt:**
```
Search for all TypeScript files containing "Agent" in vscode-app/src
```

**Expected:** `regex_search` tool call

#### Test 4: Architecture Discovery ✅
**Prompt:**
```
Analyze this project's architecture using i2vision_discover
```

**Expected:** `i2vision_discover` tool call

---

## 📋 Debugging Checklist

### Check Output Channel (View → Output → "i2-Vision")

**✅ Success Indicators:**
```
[Agent:coding-agent] System prompt built (523 chars)
[Agent:coding-agent] Calling model: qwen2.5-coder:7b
[Agent:coding-agent] Passing 7 tools to LLM
[Agent:coding-agent] Parsing LLM response (245 chars)
[Agent:coding-agent] Found tool call JSON: {"tool":"read_file","args":{"path":"vscode-app/src/extension.ts"}}
[Agent:coding-agent] Parsed 1 tool calls, reasoning: I need to read...
[Agent:coding-agent] Executing tool: read_file
[AgentTabManager] Interaction recorded: 1 iterations, 2340ms
```

**❌ Problem Indicators:**
```
[Agent:coding-agent] Failed to parse tool call: Unexpected token...
[Agent:coding-agent] Found 0 tool calls
[Agent:coding-agent] Model response doesn't match expected format
```

### Common Issues & Fixes

| Issue | Cause | Fix |
|-------|-------|-----|
| No tool calls | LLM ignores format | ✅ Fixed: Added formatting instructions |
| Parse errors | Malformed JSON | Check `toolCallPattern` in YAML |
| Tool not found | Wrong tool name | Verify tool name matches `executeTool()` switch |
| Timeout | Model too slow | Increase `llm.timeoutSeconds` in YAML |

---

## 🎯 Expected Behavior

### When Agent Uses Tools
1. **UI shows:** "Processing..." message
2. **Output logs:** "Passing 7 tools to LLM"
3. **LLM responds:** With `tool_call:` format
4. **Parser extracts:** Tool name + args
5. **Tool executes:** Via CLI integration
6. **UI displays:** Tool results with status

### When Agent Doesn't Need Tools
1. **UI shows:** "Processing..." message
2. **LLM responds:** Just reasoning (no tool_call)
3. **UI displays:** Reasoning text only

---

## 📊 Test Results Template

Copy this and fill in after testing:

```markdown
## Test Results

### Test 1: File Reading
- [ ] Agent tab created
- [ ] Prompt sent
- [ ] Tool call detected in logs
- [ ] Tool executed successfully
- [ ] UI displays tool result

### Test 2: Directory Listing
- [ ] Tool call detected
- [ ] Result displayed

### Test 3: Code Search
- [ ] Tool call detected
- [ ] Result displayed

### Test 4: Discovery
- [ ] Tool call detected
- [ ] Result displayed

### Output Channel Logs
Paste relevant log lines here:

```

---

## 🔍 Advanced Debugging

### Enable Verbose Logging
Add this to `AgentBridge.ts`:
```typescript
this.log(`LLM Response: ${response}`);  // Full response for debugging
```

### Check Model Loading
```bash
# In terminal
ollama list
# Verify qwen2.5-coder:7b is loaded
```

### Test CLI Directly
```bash
# Test read_file tool
node cli.js read_file vscode-app/src/extension.ts
```

---

## 📝 Next Steps After Testing

1. ✅ **If tests pass:** Agent tool calling is working!
2. ❌ **If no tool calls:** Check model output format
3. ❌ **If parse errors:** Adjust `toolCallPattern` in YAML
4. ❌ **If tool fails:** Check `executeTool()` implementation

---

**Ready to test!** Reload the extension and try the prompts above. 🚀
