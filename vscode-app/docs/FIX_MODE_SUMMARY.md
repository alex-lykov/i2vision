# Fix Mode - Build Failure Auto-Fix Workflow

## Overview

Fix mode is an intelligent auto-fix system that activates when a build fails, guiding the agent to fix compilation errors efficiently.

---

## How It Works

### 1. Build Failure Detection
```
[Build Command] → ❌ BUILD FAILED
  → _fixMode = true (activated)
  → _pendingFixes = [File1.kt, File2.kt] (extracted from error output)
  → Tools reduced: 16 → 4 (edit-only)
```

### 2. Edit-Only Tool Selection
When fix mode is active, the LLM only has access to:
- `apply_edits` - Apply targeted edits to files
- `read_file` - Read file contents
- `write_file` - Write complete file content
- `get_file_context` - Get file symbol context

**Excluded tools:** `run_terminal`, `run_build` (prevents premature rebuilds)

### 3. Auto-Read Failing Files
The system automatically reads all failing files before the LLM acts:
```
[AUTO-FIX] Reading file: File1.kt
[AUTO-FIX] Reading file: File2.kt
[LLM receives file contents + fix instruction]
```

### 4. Guided Fix Workflow
```
Iteration N:   LLM calls apply_edits → File1.kt fixed
Iteration N+1: LLM calls apply_edits → File2.kt fixed
Iteration N+2: Auto-nudge: "Re-run build" → _fixMode = false
Iteration N+3: LLM calls run_terminal → Build succeeds ✅
```

---

## Key Features

### ✅ Immediate Activation
Fix mode activates at build failure detection, not after multiple failures.

### ✅ Edit-Only Tools
Prevents the agent from re-running the build before fixes are applied.

### ✅ Auto-Nudge System
After successful edits, the system guides the next step:
```
"Fix applied to File.kt. All pending fixes complete. 
Re-run the build to verify: .\gradlew compileKotlin"
```

### ✅ Retry Logic (3 Attempts Max)
If `apply_edits` fails to match:
- **Attempt 1:** "Read the file to find the EXACT text, then retry"
- **Attempt 2:** "Read carefully and retry"
- **Attempt 3:** Skip file, move to next pending file

### ✅ State Reset on Success
- Successful edits → Reset retry counter
- Build success → Reset fix mode, clear pending fixes
- New build failure → Fresh start with new errors

---

## Fix Mode Lifecycle

### Scenario A: Successful Fixes
```
1. Build fails → _fixMode = true, _pendingFixes = [File1.kt, File2.kt]
2. apply_edits File1.kt → ✅ Success
3. apply_edits File2.kt → ✅ Success
4. Auto-nudge: "Re-run build" → _fixMode = false
5. Build succeeds → Done
```

### Scenario B: Failed Edits (Skip Logic)
```
1. Build fails → _pendingFixes = [File1.kt, File2.kt]
2. apply_edits File1.kt → ❌ Attempt 1/3
3. apply_edits File1.kt → ❌ Attempt 2/3
4. apply_edits File1.kt → ❌ Attempt 3/3 → SKIP FILE
5. apply_edits File2.kt → ✅ Success
6. Auto-nudge: "Re-run build" → _fixMode = false
7. Build succeeds → Done
```

---

## Technical Implementation

### State Variables
```typescript
private _fixMode: boolean = false;              // Is fix mode active?
private _pendingFixes: string[] = [];           // Files to fix
private _failedEditAttempts: number = 0;        // Retry counter
private _autoNudge: string | null = null;       // Next-step guidance
```

### Tool Filtering
```typescript
if (fixMode) {
  const fixTools = allTools.filter(t => 
    ['apply_edits', 'read_file', 'write_file', 'get_file_context'].includes(t.function.name)
  );
  return fixTools; // 4 tools instead of 16
}
```

### Dynamic Tool Selection
`getTools()` is called **inside the agent loop** (not once at start), ensuring fix mode changes take effect immediately.

---

## User Experience

### What the User Sees
1. **Build fails** → Red error message with compilation errors
2. **Agent reads files** → Log shows auto-read progress
3. **Agent applies fixes** → Edit confirmations shown
4. **Agent suggests rebuild** → Clear instruction to re-run build
5. **Build succeeds** → Green success message

### What Happens Behind the Scenes
- Fix mode activated/deactivated automatically
- Tools filtered to prevent premature rebuilds
- Failed edits tracked with retry logic
- State reset on success/failure

---

## Configuration

Fix mode is **automatic** - no configuration required. It activates on any build failure and deactivates on success.

### Customization Points
- **Retry attempts:** Currently 3 (hardcoded)
- **Edit-only tools:** Configured in `getTools()` method
- **Auto-nudge messages:** Customizable in `apply_edits`/`write_file` handlers

---

## Testing

To verify fix mode works:

1. **Introduce a compilation error** in a Kotlin/Java file
2. **Run build** via agent: `./gradlew compileKotlin`
3. **Watch logs** for:
   ```
   [Build failed (failure #1) - FIX MODE ACTIVATED]
   [Fix mode active: reduced from 16 to 4 tools (edit-only)]
   [AUTO-FIX] Reading file: File.kt
   ```
4. **Verify LLM calls `apply_edits`** (not `search_files`)
5. **Confirm build succeeds** after fixes applied

---

## Related Documentation

- [Fix Mode Timing Fix](./FIX_MODE_TIMING_FIX.md) - Full technical details
- [apply_edits Tool](./apply-edits-tool.md) - Edit tool documentation
- [Auto-Fix Workflow](./AUTO_FIX_WORKFLOW.md) - Detailed workflow specs

---

## Version

- **Introduced:** v1.0.0
- **Last Updated:** 2024
- **Status:** Production Ready
