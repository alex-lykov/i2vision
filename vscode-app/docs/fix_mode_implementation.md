# Fix Mode Implementation - Build Failure Auto-Fix

## Overview

Fix mode is an intelligent auto-fix system that activates when a build fails, guiding the agent to fix compilation errors efficiently by reducing tool options and providing explicit guidance.

---

## Problem: Plan Loop After Build Failure

When a build failed, the agent would enter a plan loop:

```
[Build Failed] → Agent should fix errors
[Iter 11] LLM called with 16 tools → Uses search_files
[Iter 12] search_files → No results
[Iter 13] search_files → No results
...
[Iter 48] Finally calls apply_edits (1 time in 50 iterations)
[Iter 50] Safety net reached
```

**Root Cause:** The LLM defaults to learned patterns (`read_file`, `search_files`) and doesn't know to use `apply_edits` without explicit guidance.

---

## Solution: Three-Pronged Fix

### 1. **Fix Mode** - Reduce Tool Options After Build Failure

When a build fails, activate **fix mode** which filters tools to only those needed for fixing:

```typescript
// After build failure:
this._fixMode = true;

// Filter tools to only edit-related ones (excludes run_terminal/run_build)
const fixTools = allTools.filter(t => 
  ['apply_edits', 'read_file', 'write_file', 'get_file_context'].includes(t.function.name)
);

// Result: 16 tools → 4 tools (edit-only)
// Prevents LLM from re-running build before fixes applied
```

**Why edit-only?** The auto-nudge after successful edits tells the user when to rebuild, so `run_terminal` and `run_build` are excluded to prevent premature rebuilds.

---

### 2. **Dynamic Tool Selection** - Call `getTools()` Inside Loop

**Bug:** `getTools()` was called once at the start of `executeAgentLoop()`, but `_fixMode` was set inside the loop.

```typescript
// BEFORE: Bug
async *executeAgentLoop() {
  const tools = this.getTools(contextProfile?.lazy, this._fixMode);  // Called ONCE
  
  while (true) {
    if (buildFailed) {
      this._fixMode = true;  // Too late! tools already selected
    }
    const response = await cli.callLLM(messages, tools);  // Stale tools
  }
}

// AFTER: Fix
async *executeAgentLoop() {
  while (true) {
    const tools = this.getTools(contextProfile?.lazy, this._fixMode);  // Inside loop
    
    if (buildFailed) {
      this._fixMode = true;  // Affects NEXT iteration's tool selection
    }
    const response = await cli.callLLM(messages, tools);  // Current tools
  }
}
```

---

### 3. **Retry Logic** - Prevent Infinite Loops on Failed Edits

If `apply_edits` fails to match the search string, track attempts and skip after 3 failures:

```typescript
// In apply_edits handler:
if (editResult.appliedCount === 0) {
    this._failedEditAttempts++;
    
    if (this._failedEditAttempts >= 3) {
        // Give up on this file after 3 attempts
        this._pendingFixes.shift(); // Remove from pending
        this._failedEditAttempts = 0;
        this._autoNudge = `Failed to edit ${filePath} after 3 attempts. Moving to next file.`;
    } else {
        this._autoNudge = `Edit failed on ${filePath}. Read the file to find the EXACT text, then retry.`;
    }
} else {
    this._failedEditAttempts = 0; // Reset on success
}

// In run_terminal, when build fails:
this._fixMode = true;
this._failedEditAttempts = 0; // Fresh start with new errors
```

---

## Implementation Details

### State Variables

```typescript
private _fixMode: boolean = false;              // Is fix mode active?
private _pendingFixes: string[] = [];           // Files to fix
private _failedEditAttempts: number = 0;        // Retry counter
private _autoNudge: string | null = null;       // Next-step guidance
```

### Fix Mode Activation

```typescript
// In run_terminal handler, when build fails:
if (result.exitCode !== 0 || output.includes('BUILD FAILED')) {
    this._buildFailureCount++;
    
    // ACTIVATE FIX MODE IMMEDIATELY
    this._fixMode = true;
    this._failedEditAttempts = 0; // Reset retry counter
    this.log(`Build failed (failure #${this._buildFailureCount}) - FIX MODE ACTIVATED`);
    
    // Extract files to auto-read
    const errors = this.extractCompilationErrors(output);
    const files = extractFilesFromErrors(errors);
    this._pendingFixes = files.slice(0, 5);
    
    return {
        result: `❌ BUILD FAILED...`,
        error: 'Build failed'
    };
}

// Build succeeded - reset fix mode
this._fixMode = false;
this._buildFailureCount = 0;
```

### Auto-Nudge on Successful Edits

```typescript
// In apply_edits/write_file handlers, after successful edit:
if (this._pendingFixes && this._pendingFixes.length > 0) {
    const nextFile = this._pendingFixes[0];
    this._autoNudge = `You successfully edited ${filePath}. There are still ${this._pendingFixes.length} failing file(s) to fix. Next: ${nextFile}`;
    // Keep fix mode active - more fixes needed
} else {
    // All fixes complete - reset fix mode
    this._fixMode = false;
    this._autoNudge = `Fix applied to ${filePath}. All pending fixes complete. Re-run the build to verify: .\\gradlew compileKotlin`;
}
```

---

## Fix Mode Lifecycle

### Scenario A: Successful Fixes

```
1. Build fails
   → _fixMode = true
   → _pendingFixes = [File1.kt, File2.kt]
   → _failedEditAttempts = 0
   → Tools: 16 → 4

2. Iteration N: LLM called with 4 tools
   → Calls apply_edits on File1.kt
   → Edits applied successfully
   → _failedEditAttempts = 0 (reset)
   → _pendingFixes = [File2.kt] (still pending)
   → Fix mode STAYS active

3. Iteration N+1: LLM called with 4 tools
   → Calls apply_edits on File2.kt
   → Edits applied successfully
   → _failedEditAttempts = 0 (reset)
   → _pendingFixes = [] (all done)
   → Auto-nudge: "Re-run the build"
   → _fixMode = false (reset by auto-nudge)

4. Iteration N+2: LLM called with 16 tools
   → Calls run_terminal with build command
   → Build succeeds
   → Task complete
```

### Scenario B: Failed Edits (Skip Logic)

```
1. Build fails
   → _fixMode = true
   → _pendingFixes = [File1.kt, File2.kt]
   → _failedEditAttempts = 0

2. Iteration N: LLM called with 4 tools
   → Calls apply_edits on File1.kt
   → ❌ Search string doesn't match
   → _failedEditAttempts = 1
   → Auto-nudge: "Edit failed (attempt 1/3). Read the file to find the EXACT text."

3. Iteration N+1: LLM called with 4 tools
   → Calls apply_edits again (different search string)
   → ❌ Still doesn't match
   → _failedEditAttempts = 2
   → Auto-nudge: "Edit failed (attempt 2/3). Read carefully and retry."

4. Iteration N+2: LLM called with 4 tools
   → Calls apply_edits again
   → ❌ Third failure
   → _failedEditAttempts = 3 → TRIGGER SKIP LOGIC
   → _pendingFixes.shift() → [File2.kt]
   → _failedEditAttempts = 0 (reset)
   → Auto-nudge: "Failed after 3 attempts. Moving to next file: File2.kt"

5. Iteration N+3: LLM called with 4 tools
   → Calls apply_edits on File2.kt
   → ✅ Edits applied
   → _pendingFixes = [] (all done)
   → Auto-nudge: "Re-run the build"
   → _fixMode = false

6. Iteration N+4: LLM called with 16 tools
   → Calls run_terminal
   → Build succeeds (File1.kt errors resolved despite failed edit)
   → Task complete
```

---

## Changes Made

| File | Change | Purpose |
|------|--------|---------|
| `AgentBridge.ts` | Added `_fixMode` flag | Track fix mode state |
| `AgentBridge.ts` | Added `_failedEditAttempts` counter | Track retry attempts |
| `AgentBridge.ts` | Moved `getTools()` inside `while(true)` loop | Dynamic tool selection |
| `AgentBridge.ts` | Added `_fixMode = true` in `run_terminal` build failure | Activate on first failure |
| `AgentBridge.ts` | Added retry logic in `apply_edits` handler | Prevent infinite loops |
| `AgentBridge.ts` | Reset counter on success/new build | Fresh starts |
| `AgentBridge.ts` | Reduced fix mode tools to 4 (edit-only) | Prevent premature rebuilds |
| `AgentBridge.ts` | Auto-nudge resets `_fixMode = false` | Allow rebuild after fixes |

---

## Expected Behavior

### Before Fix
```
[Build Failed] → _fixMode = true (but ignored)
[Iter 11] LLM called with 16 tools → Uses search_files
[Iter 12] search_files → No results
[Iter 13] search_files → No results
...
[Iter 48] Finally calls apply_edits (1 time in 50 iterations)
[Iter 50] Safety net reached
```

### After Fix
```
[Build Failed] → _fixMode = true
[Iter 11] LLM called with 4 tools (edit-only)
[Iter 11] ✅ Must call apply_edits (only edit tool available)
[Iter 11] ✅ Edits applied → Auto-nudge: "Re-run the build"
[Iter 11] Auto-nudge resets _fixMode = false
[Iter 12] LLM called with 16 tools → Calls run_terminal
[Build Success] → Task complete
```

---

## Testing Checklist

- [ ] Build failure triggers fix mode immediately
- [ ] Fix mode reduces tools to 4 (apply_edits, read_file, write_file, get_file_context)
- [ ] `getTools()` called inside agent loop (not once at start)
- [ ] Auto-read failing files before LLM acts
- [ ] LLM calls apply_edits (not search_files)
- [ ] Successful edits reset retry counter
- [ ] Failed edits increment counter (max 3)
- [ ] After 3 failures, file skipped and counter reset
- [ ] Auto-nudge suggests rebuild after all fixes complete
- [ ] Build success resets fix mode
- [ ] New build failure resets counter and pending files

---

## Key Insights

### Tool Selection Timing
**Tool selection must happen immediately before each LLM call**, not once at the start of the agent loop. This ensures that state changes (like fix mode activation) are reflected in the tools available to the LLM.

### Edit-Only Fix Mode
Excluding `run_terminal` and `run_build` from fix mode prevents the agent from re-running the build prematurely. The auto-nudge after successful edits tells the user when to rebuild, and also resets `_fixMode = false` so the next LLM call has all tools available.

### Retry Logic
Tracking failed edit attempts prevents the agent from looping infinitely on a file it can't fix. After 3 attempts, the file is skipped and the agent moves to the next pending file.

---

## Future Enhancements

1. **Adaptive Examples**: Generate JSON examples based on actual error messages
2. **Progressive Hinting**: Start vague, get more explicit with each plan detection
3. **Tool Usage Analytics**: Track which tools the LLM actually uses vs ignores
4. **Dynamic Tool Filtering**: Learn which tools are useful for each task type
5. **One-Click Fix**: For common errors, auto-generate the apply_edits call without LLM involvement

---

## References

- [apply_edits Tool Documentation](./apply-edits-tool.md)
- [Terminal Management](./terminal-management.md)
- [Structure Guide](./structure.md)
