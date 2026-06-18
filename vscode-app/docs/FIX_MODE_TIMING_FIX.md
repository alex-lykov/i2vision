# Fix Mode Timing Fix - Activation at Build Failure

## Problem: Fix Mode Never Activated

From the logs:
```
[9:38:49 AM] [AgentBridge] Auto-fix: Queued 3 unique files (failure #5)
[9:38:49 AM] [AgentBridge] [Iter 11] Calling LLM...
[9:38:49 AM] [CLI] Including 16 tools in request  ← Should be 5 tools!
```

**Root Cause**: `getTools()` was called **once** at the start of `executeAgentLoop()`, but `_fixMode` was set to `true` **inside** the loop during auto-fix processing.

```typescript
// BEFORE: Bug
async *executeAgentLoop() {
  const tools = this.getTools(contextProfile?.lazy, this._fixMode);  // Called ONCE, _fixMode=false
  
  while (true) {
    iteration++;
    
    if (this._pendingFixes && this._pendingFixes.length > 0) {
      // Auto-fix processing
      this._fixMode = true;  // Too late! tools already selected
    }
    
    // LLM call uses stale tools list (16 tools, not 5)
    const response = await cli.callLLM(messages, tools);
  }
}
```

---

## Solution: Call `getTools()` Inside the Loop

```typescript
// AFTER: Fix
async *executeAgentLoop() {
  while (true) {
    iteration++;
    
    // Get tools INSIDE the loop - picks up latest _fixMode state
    const tools = this.getTools(contextProfile?.lazy, this._fixMode);
    
    if (this._pendingFixes && this._pendingFixes.length > 0) {
      // Auto-fix processing
      this._fixMode = true;  // Will affect NEXT iteration's tool selection
    }
    
    // LLM call uses CURRENT tools list (5 tools if fixMode=true)
    const response = await cli.callLLM(messages, tools);
  }
}
```

---

## Additional Fix: Activate Fix Mode at Build Failure Detection

The first opportunity to activate fix mode is in the `run_terminal` handler when a build fails:

```typescript
case 'run_terminal': {
  if (this.isBuildCommand(command)) {
    const result = await this.runCommandWithTimeout(command, timeout, workingDir);
    
    if (result.exitCode !== 0 || output.includes('BUILD FAILED')) {
      this._buildFailureCount++;
      
      // ACTIVATE FIX MODE IMMEDIATELY - affects next LLM call
      this._fixMode = true;
      this.log(`Build failed (failure #${this._buildFailureCount}) - FIX MODE ACTIVATED`);
      
      // Extract files to auto-read...
      this._pendingFixes = Array.from(cleanPaths).slice(0, 5);
      
      return { result: `❌ BUILD FAILED...` };
    }
    
    // Build succeeded - reset fix mode
    this._fixMode = false;
  }
}
```

---

## Changes Made

| File | Change | Purpose |
|------|--------|---------|
| `AgentBridge.ts` | Moved `getTools()` call inside `while(true)` loop | Ensures fix mode changes take effect immediately |
| `AgentBridge.ts` | Added `_fixMode = true` in `run_terminal` build failure handler | Activates fix mode at first build failure |
| `AgentBridge.ts` | Removed duplicate `_buildFailureCount++` from auto-fix section | Counter already incremented in `run_terminal` |
| `AgentBridge.ts` | Removed duplicate `_fixMode = true` from auto-fix section | Already set in `run_terminal` |

---

## Expected Behavior After Fix

### Before (50 Iterations, 1 apply_edits Call)
```
[Build Failed] → _fixMode = true (but ignored)
[Iter 11] LLM called with 16 tools → Uses search_files
[Iter 12] search_files → No results
[Iter 13] search_files → No results
...
[Iter 48] Finally calls apply_edits (1 time in 50 iterations)
[Iter 50] Safety net reached
```

### After (Immediate Fix Mode Activation)
```
[Build Failed] → _fixMode = true
[Iter 11] LLM called with 5 tools (apply_edits, read_file, write_file, run_terminal, run_build)
[Iter 11] ✅ Must call apply_edits (only fix-related tool available)
[Iter 11] ✅ Edits applied
[Build Success] → _fixMode = false
→ TASK COMPLETE
```

---

## Key Insight

**Tool selection must happen immediately before each LLM call**, not once at the start of the agent loop. This ensures that state changes (like fix mode activation) are reflected in the tools available to the LLM.

---

## Testing

To verify the fix works:

1. **Trigger a build failure** (introduce a compilation error)
2. **Watch the logs** for:
   ```
   [Build failed (failure #1) - FIX MODE ACTIVATED]
   [Fix mode active: reduced from 16 to 5 tools]
   ```
3. **Verify the LLM only has 5 tools** in the next iteration
4. **Confirm `apply_edits` is called** instead of endless `search_files`

---

## Related Documentation

- [Fix Mode Implementation](./FIX_MODE_IMPLEMENTATION.md)
- [apply_edits Tool Implementation](./APPLY_EDITS_IMPLEMENTATION.md)
- [apply_edits Documentation](./apply-edits-tool.md)
