# `apply_edits` Tool - Modern Structured Editing

## Overview

The `apply_edits` tool implements the **modern pattern** for AI code editing, solving the classic `edit_file` vs `write_file` dilemma. Instead of forcing the LLM to choose between tools, the model provides structured edit operations, and the system handles validation and application.

## Why This Approach?

### Traditional Problems

| Problem | `edit_file` | `write_file` | `apply_edits` Solution |
|---------|-------------|--------------|------------------------|
| **String matching fails** | ❌ Common | ✅ N/A | ✅ Validates before applying |
| **Multiple matches** | ❌ Silent failure | ✅ N/A | ✅ Reports exact occurrence count |
| **Batch editing** | ❌ One call per edit | ✅ Full file | ✅ Multiple edits in one call |
| **Partial failures** | ❌ All-or-nothing | ✅ N/A | ✅ Continues after failures |
| **LLM confusion** | ❌ Must choose tool | ❌ Must choose tool | ✅ Single tool for all edits |

## Tool Definition

```typescript
{
  name: 'apply_edits',
  description: 'Apply one or more targeted edits to an existing file',
  parameters: {
    path: string,        // File path (relative to workspace root)
    edits: [
      {
        search: string,  // Exact text to find (must be unique)
        replace: string, // Replacement text
        lineHint?: number // Optional: approximate line number
      }
    ]
  }
}
```

## Usage Examples

### Example 1: Single Variable Rename

**User Request:** "Rename `rightRoomId` to use optional chaining"

**LLM Tool Call:**
```json
{
  "tool": "apply_edits",
  "args": {
    "path": "src/main.kt",
    "edits": [
      {
        "search": "rightRoomId",
        "replace": "rightRoom?.id ?: \"\""
      }
    ]
  }
}
```

**Result:**
```
✅ Applied 1/1 edits to src/main.kt
```

---

### Example 2: Multiple Related Fixes

**User Request:** "Fix both room ID references"

**LLM Tool Call:**
```json
{
  "tool": "apply_edits",
  "args": {
    "path": "ProjectValidationEngine.kt",
    "edits": [
      {
        "search": "rightRoomId",
        "replace": "rightRoom?.id ?: \"\""
      },
      {
        "search": "leftRoomId",
        "replace": "leftRoom?.id ?: \"\""
      }
    ]
  }
}
```

**Result:**
```
✅ Applied 2/2 edits to ProjectValidationEngine.kt
```

---

### Example 3: Partial Failure with Recovery

**User Request:** "Update all three constants"

**LLM Tool Call:**
```json
{
  "tool": "apply_edits",
  "args": {
    "path": "config.ts",
    "edits": [
      { "search": "const MAX = 100;", "replace": "const MAX = 200;" },
      { "search": "const MISSING = 0;", "replace": "const MISSING = 1;" }, // Doesn't exist
      { "search": "const MIN = 10;", "replace": "const MIN = 5;" }
    ]
  }
}
```

**Result:**
```
✅ Applied 2/3 edits to config.ts

⚠️ 1 edit(s) failed:
❌ Edit failed: Search text not found in config.ts
   Search: "const MISSING = 0;"
   💡 Check if the text was already modified by a previous edit, or verify the exact whitespace/indentation
```

**LLM Can Retry:** The model sees exactly what failed and can retry with corrected search strings.

---

### Example 4: Build Failure Auto-Fix

**Context:** Build fails with compilation errors in multiple files

**System Auto-Read:**
```
[AUTO-FIX] Reading file: InMemoryConductorRepository.kt
[AUTO-FIX] Reading file: ProjectValidationEngine.kt
```

**LLM Tool Call:**
```json
{
  "tool": "apply_edits",
  "args": {
    "path": "InMemoryConductorRepository.kt",
    "edits": [
      {
        "search": "class InMemoryConductorRepository : ConductorRepository {",
        "replace": "abstract class InMemoryConductorRepository : ConductorRepository {"
      }
    ]
  }
}
```

**Result:**
```
✅ Applied 1/1 edits to InMemoryConductorRepository.kt
[AUTO-NUDGE] Fix applied. Re-run the build to verify: .\gradlew compileKotlin
```

---

## When to Use `apply_edits` vs `write_file`

| Use `apply_edits` | Use `write_file` |
|-------------------|------------------|
| ✅ 1-5 line changes | ✅ Large rewrites (>10 lines) |
| ✅ Targeted fixes | ✅ New file creation |
| ✅ Variable/function renames | ✅ Complete file regeneration |
| ✅ Import additions | ✅ When search strings are ambiguous |
| ✅ Small bug fixes | ✅ When context is unclear |

**Rule of Thumb:** If the LLM can describe the change as "change X to Y", use `apply_edits`. If it needs to regenerate the whole file, use `write_file`.

---

## Implementation Details

### Validation Flow

```
1. Read file content
2. For each edit:
   a. Count occurrences of search string
   b. If 0 → NOT_FOUND failure
   c. If >1 → MULTIPLE_MATCHES failure
   d. If 1 → Apply edit
3. Write modified content (if any edits succeeded)
4. Report results (successes + failures)
```

### Error Messages

The tool provides **actionable** error messages:

- **NOT_FOUND**: Suggests checking previous edits or whitespace
- **MULTIPLE_MATCHES**: Suggests adding more context (3-5 lines)
- **ALREADY_APPLIED**: Indicates no-op (search === replace)

### Safety Features

1. **Uniqueness Validation**: Prevents accidental mass-replacements
2. **Atomic Writes**: Only writes if at least one edit succeeds
3. **Failure Reporting**: Exact count of occurrences for debugging
4. **Line Hints**: Optional line numbers for better error messages

---

## Integration with Auto-Fix Workflow

The `apply_edits` tool integrates seamlessly with the existing auto-fix workflow:

```
1. Build fails → Extract failing files
2. Auto-read failing files
3. LLM receives errors + file content
4. LLM calls apply_edits with targeted fixes
5. System validates and applies edits
6. Auto-nudge suggests re-building
7. Repeat if build still fails
```

**Example Auto-Fix Instruction:**
```
**USE THESE TOOLS:**
- write_file — Replace entire file with fixed code (best for large changes)
- apply_edits — Apply targeted edits to specific lines (best for small fixes, 1-5 lines)

**DO NOT:**
- ❌ Re-run the build (it will fail again)
- ❌ Just read files (you already have the content)
- ❌ Describe plans (take action instead)
```

---

## Fix Mode Integration

When a build fails, the agent enters **fix mode** to guide efficient error resolution:

### Fix Mode Behavior

1. **Immediate Activation**: Fix mode activates on first build failure
2. **Edit-Only Tools**: LLM only has access to `apply_edits`, `read_file`, `write_file`, `get_file_context`
3. **Auto-Read**: All failing files are read automatically before LLM acts
4. **Guided Workflow**: After successful edits, agent suggests when to rebuild
5. **Retry Logic**: Failed edits retry up to 3 times before skipping the file

### Fix Mode Lifecycle

```
Scenario A: Successful Fixes
1. Build fails → Fix mode activated, tools reduced 16→4
2. apply_edits File1.kt → ✅ Success
3. apply_edits File2.kt → ✅ Success
4. Auto-nudge: "Re-run build" → Fix mode deactivated
5. Build succeeds → Done

Scenario B: Failed Edits (Skip Logic)
1. Build fails → Fix mode activated
2. apply_edits File1.kt → ❌ Attempt 1/3
3. apply_edits File1.kt → ❌ Attempt 2/3
4. apply_edits File1.kt → ❌ Attempt 3/3 → Skip file
5. apply_edits File2.kt → ✅ Success
6. Auto-nudge: "Re-run build" → Fix mode deactivated
7. Build succeeds → Done
```

### Retry Logic

If `apply_edits` fails to match the search string:
- **Attempt 1-2:** Agent nudges: "Read the file to find the EXACT text, then retry"
- **Attempt 3:** Agent skips the file and moves to the next pending file

This prevents infinite loops on unfixable files.

---

## Testing

Run the test suite:

```bash
npm test -- ApplyEditsTool
```

**Test Coverage:**
- ✅ Single edit success
- ✅ Multiple edits in sequence
- ✅ NOT_FOUND failure
- ✅ MULTIPLE_MATCHES failure
- ✅ Partial failure with continuation
- ✅ Pre-flight validation
- ✅ No-op detection
- ✅ Context extraction
- ✅ Edge cases (empty content, empty search)
- ✅ Indentation preservation

---

## Future Enhancements

1. **AST-Based Editing**: Parse code into AST for more robust matching
2. **Fuzzy Matching**: Allow approximate matches with confidence scores
3. **Multi-Line Edits**: Support for block-level search/replace
4. **Diff Preview**: Show unified diff before applying
5. **Undo Stack**: Track edits for rollback capability

---

## References

- [Cursor Editing Pattern](https://cursor.sh)
- [Claude Code Tool Design](https://claude.ai/code)
- [GitHub Copilot Workspace](https://github.com/features/copilot)
- [Fix Mode Implementation](fix_mode_implementation.md)
