# Fix Mode Implementation - Breaking the Plan Loop

## Problem: 27 Consecutive Plan Detections

The agent entered a permanent plan loop where:
- Iterations 1-8: Normal operation with tool calls
- Iterations 9-41: **Every response was "Plan detected."**
- The `apply_edits` tool was registered but **never used**

**Root Cause**: The LLM defaults to learned patterns (`read_file`, `search_files`) and doesn't know to use the new `apply_edits` tool without explicit guidance.

---

## Solution: Three-Pronged Fix

### 1. **Fix Mode** - Reduce Tool Options After Build Failure

When a build fails, activate **fix mode** which filters tools to only those needed for fixing:

```typescript
// After build failure:
this._fixMode = true;

// Filter tools to only fix-related ones
const fixTools = allTools.filter(t => 
  ['apply_edits', 'read_file', 'write_file', 'run_terminal', 'run_build'].includes(t.function.name)
);

// Result: 16 tools → 5 tools
// Prevents LLM from reaching for search_files or list_directory
```

**Benefit**: Eliminates decision paralysis and keeps the LLM focused on fixing.

---

### 2. **Explicit JSON Examples** - Show Exactly What to Output

Instead of vague instructions like "fix the errors", provide the exact JSON structure:

```typescript
const instructionContent = `**USE THIS TOOL NOW:**
Call \`apply_edits\` with this exact JSON structure:

\`\`\`json
{
  "path": "service/project/src/main/kotlin/com/electricity/service/ProjectValidationEngine.kt",
  "edits": [
    {
      "search": "rightRoomId",
      "replace": "rightRoom?.id ?:\\"\\""
    },
    {
      "search": "leftRoomId", 
      "replace": "leftRoom?.id ?:\\"\\""
    }
  ]
}
\`\`\`

Apply the edits NOW. Do not read any more files. Do not search. Just call apply_edits.`;
```

**Benefit**: LLMs are pattern matchers - show them the exact pattern to output.

---

### 3. **Directive Instructions** - Command, Don't Suggest

Replace passive language with active commands:

| Before (Ineffective) | After (Effective) |
|---------------------|-------------------|
| "You should fix the errors" | "Apply the edits NOW" |
| "Consider using apply_edits" | "Call apply_edits with this exact JSON" |
| "You might want to read files" | "Do NOT read any more files" |
| "Try to fix the code" | "Do NOT describe plans - output the tool call JSON directly" |

**Benefit**: Removes ambiguity and prevents the LLM from defaulting to planning mode.

---

## Implementation Details

### Changes Made

| File | Change | Line |
|------|--------|------|
| `AgentBridge.ts` | Added `_fixMode` flag | ~280 |
| `AgentBridge.ts` | Updated `getTools()` signature | ~520 |
| `AgentBridge.ts` | Added fix mode filtering logic | ~650 |
| `AgentBridge.ts` | Activated fix mode after build failure | ~1320 |
| `AgentBridge.ts` | Reset fix mode on build success | ~1950 |
| `AgentBridge.ts` | Enhanced plan detection with JSON examples | ~1450 |

### Fix Mode Lifecycle

```
1. Build fails → this._fixMode = true
2. Next LLM call → getTools(fixMode=true) → 5 tools instead of 16
3. Auto-fix instruction → Includes explicit JSON example
4. LLM calls apply_edits → Edits applied
5. Build succeeds → this._fixMode = false
6. Normal operation resumes → All 16 tools available
```

---

## Plan Detection Enhancement

When the LLM outputs plans instead of tool calls, the nudge now includes:

```typescript
// Extract specific file paths from build error
const files = ['path/to/File.kt', 'path/to/AnotherFile.kt'];

// Build explicit JSON example
nudgeMessage += `
Example of what to call RIGHT NOW:

\`\`\`json
{
  "tool": "apply_edits",
  "args": {
    "path": "${files[0]}",
    "edits": [
      {
        "search": "the broken code",
        "replace": "the fixed code"
      }
    ]
  }
}
\`\`\`

Do NOT read more files. Do NOT search. Call apply_edits with the exact JSON structure above.`;
```

---

## Expected Behavior

### Before Fix
```
[Iter 9] Plan detected
[Iter 10] Plan detected
[Iter 11] Plan detected
...
[Iter 41] Plan detected
→ Agent stuck, user frustration
```

### After Fix
```
[Build Failed] → Fix mode ACTIVATED (16→5 tools)
[Iter 9] Plan detected → Nudge with JSON example
[Iter 10] ✅ apply_edits called with correct JSON
[Iter 10] ✅ Edits applied
[Build Success] → Fix mode DEACTIVATED (5→16 tools)
→ Task complete
```

---

## Testing Checklist

- [ ] Build failure triggers fix mode
- [ ] Fix mode reduces tools to 5 (apply_edits, read_file, write_file, run_terminal, run_build)
- [ ] Auto-fix instruction includes explicit JSON example
- [ ] Plan detection nudge includes JSON example
- [ ] Build success resets fix mode
- [ ] Normal operation resumes with all 16 tools
- [ ] LLM actually calls apply_edits (not just plans)

---

## Future Enhancements

1. **Adaptive Examples**: Generate JSON examples based on actual error messages
2. **Progressive Hinting**: Start vague, get more explicit with each plan detection
3. **Tool Usage Analytics**: Track which tools the LLM actually uses vs ignores
4. **Dynamic Tool Filtering**: Learn which tools are useful for each task type
5. **One-Click Fix**: For common errors, auto-generate the apply_edits call without LLM involvement

---

## References

- [Original Issue: 27 Consecutive Plan Detections](#user-prompt)
- [apply_edits Tool Implementation](./APPLY_EDITS_IMPLEMENTATION.md)
- [apply_edits Documentation](./apply-edits-tool.md)
