# 🚨 Quick Debug Checklist - F5 Not Working

## ⚡ 3-Minute Diagnostic

### Step 1: Recompile (30 seconds)
```bash
cd D:\proj\AI\i2-vision\vscode-app
npm run compile
```
**Must see:** No errors

### Step 2: Launch Extension Host (F5)
- Press **F5**
- New VS Code window opens
- **Don't close the original window** ( Debug Console is there)

### Step 3: Run Quick Diagnostic (30 seconds)
In the **new window** (Extension Development Host):

1. Press `Ctrl+Shift+P`
2. Type: `i2vision.quickDiagnostic`
3. Press Enter
4. Check **Output** panel (View -> Output -> i2-Vision)

**Expected output:**
```
=== Quick Diagnostic ===
Workspace: D:\proj\AI\i2-vision
Config file: [OK] D:\proj\AI\i2-vision\.vision-ai\coding-agent.yaml
Extension active: YES
Output channel: [OK] i2-Vision
```

### Step 4: Create Agent Tab (30 seconds)
1. Press `Ctrl+Shift+P`
2. Type: `i2-Vision: New Coding Agent`
3. Press Enter
4. **Check:** Does a new panel appear in the sidebar?

### Step 5: Test Basic Message (30 seconds)
In the agent tab:
1. Type: `What is 2+2?`
2. Press Send
3. **Watch Output panel** for logs

---

## ❌ If Something Fails

### "Command not found" 
**Problem:** Extension didn't activate

**Fix:**
1. Check **Debug Console** in original window
2. Look for red error messages
3. Share the error text

### "Config file: [MISSING]"
**Problem:** Agent config not found

**Fix:**
```bash
# Check if file exists
dir D:\proj\AI\i2-vision\.vision-ai\coding-agent.yaml

# If missing, it's at the wrong location. Check:
dir D:\proj\AI\i2-vision\vscode-app\.vscode\i2vision\agents\coding-agent.yaml
```

### No agent tab appears
**Problem:** AgentTabManager not initializing

**Fix:**
1. Run: `i2vision.debugToolCalls`
2. Check Output for errors
3. Share the full output

### Tool calls not working
**Problem:** Parsing or tool execution issue

**Fix:**
1. Run: `i2vision.checkAgentConfig`
2. Verify all 4 checks show [OK]
3. If MISSING, check `coding-agent.yaml` formatting

---

## 📋 What to Share When Asking for Help

Copy and paste this template:

```
=== Extension Activation ===
[ ] Output shows "i2-Vision extension activated"
[ ] Workspace path correct
[ ] Debug Console errors: YES/NO (paste if YES)

=== Quick Diagnostic ===
[PASTE i2vision.quickDiagnostic output here]

=== Agent Tab ===
[ ] Tab created: YES/NO
[ ] Input visible: YES/NO

=== Test Message ===
Message: "What is 2+2?"
Output logs:
[PASTE Output panel logs here]

=== Config Check ===
[PASTE i2vision.checkAgentConfig output here]
```

---

## 🔍 Where to Look for Errors

### 1. Debug Console (Original Window)
- Shows extension startup errors
- TypeScript runtime errors
- Extension activation failures

### 2. Output Panel -> i2-Vision (New Window)
- Shows extension logs
- Tool call activity
- Configuration status

### 3. Developer Tools (New Window)
- Press `Ctrl+Shift+I` in Extension Host
- Check Console tab
- Shows UI errors, webview issues

---

## ✅ Success Indicators

You know it's working when:

1. ✅ Output shows: `i2-Vision extension activated`
2. ✅ `i2vision.quickDiagnostic` runs without errors
3. ✅ Agent tab appears when created
4. ✅ Sending "What is 2+2?" shows reasoning in Output
5. ✅ Sending "Read package.json" shows tool_call in Output

---

## 🆘 Emergency Reset

If nothing works:

```bash
# 1. Clean build
cd D:\proj\AI\i2-vision\vscode-app
npm run clean
npm run compile

# 2. Reload extension host
# In Extension Host window: Ctrl+Shift+P -> Developer: Reload Window

# 3. Close all VS Code windows
# 4. Reopen main project
# 5. Press F5 again
```
