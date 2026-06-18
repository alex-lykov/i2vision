# `apply_edits` Implementation Summary

## 🎯 Problem Solved

The classic **`edit_file` vs `write_file` dilemma** in AI coding agents:

- **`edit_file`**: String matching can fail, multiple matches cause issues, one tool call per edit
- **`write_file`**: Must regenerate entire file, but atomic and reliable

**Modern Solution**: Model provides structured edits → System validates and applies batch

---

## ✅ What Was Implemented

### 1. Core Library: `ApplyEditsTool.ts`

**Location**: `vscode-app/src/agent/ApplyEditsTool.ts`

**Key Functions**:
- `applyEditsToContent(content, edits)` - Pure function that applies edits
- `validateEdits(content, edits)` - Pre-flight validation
- `getContextAroundLine(content, line, contextLines)` - Error context helper
- `formatEditFailure(failure, filePath)` - User-friendly error messages

**Validation Rules**:
- ✅ Search string must appear **exactly once**
- ✅ Reports occurrence count on failure
- ✅ Continues applying remaining edits after failures
- ✅ Provides actionable suggestions for retry

---

### 2. Tool Definition: `AgentBridge.ts`

**Added Tool** (line ~680):
```typescript
{
  name: 'apply_edits',
  description: 'Apply one or more targeted edits to an existing file',
  parameters: {
    path: string,
    edits: [
      { search: string, replace: string, lineHint?: number }
    ]
  }
}
```

**Handler Implementation** (line ~1970):
```typescript
case 'apply_edits': {
  const filePath = this.resolvePath(toolCall.args.path);
  const edits: EditOperation[] = toolCall.args.edits;
  
  // Read current content
  const currentContent = await this.cli.readFile(filePath);
  
  // Apply edits using structured edit tool
  const editResult = applyEditsToContent(currentContent, edits);
  
  // Write modified content (if any edits succeeded)
  if (editResult.appliedCount > 0) {
    await this.cli.writeFile(filePath, editResult.finalContent);
  }
  
  // Report results with detailed failures
  return { result: formatResult(editResult) };
}
```

---

### 3. Tool Registration: `LocalI2VisionAgent.ts`

**Updated** (line ~320):
```typescript
const tools = [
  'list_directory',
  'read_file',
  'write_file',
  'apply_edits',  // ✅ Added
  'search_files'
];
```

---

### 4. Display Configuration: `ToolCardConfig.ts`

**Added** (line ~155):
```typescript
'apply_edits': {
    maxLines: 15,
    maxChars: 2000,
    foldThreshold: 5,
    foldDefault: 'collapsed',
    format: 'markdown',
    showLineNumbers: false,
    syntaxHighlight: false,
    showArgs: true,
    showDuration: true,
    collapseOnSuccess: false  // Show failures prominently
}
```

---

### 5. Auto-Fix Integration: `AgentBridge.ts`

**Updated Instructions** (line ~1310):
```
**USE THESE TOOLS:**
- write_file — Replace entire file with fixed code (best for large changes)
- apply_edits — Apply targeted edits to specific lines (best for small fixes, 1-5 lines)
```

**Updated Read File Response** (line ~1900):
```
[Already read during auto-fix workflow. Content is available in previous tool results. 
Focus on proposing fixes using write_file or apply_edits.]
```

---

### 6. Test Suite: `ApplyEditsTool.test.ts`

**Location**: `vscode-app/src/test/suite/ApplyEditsTool.test.ts`

**Coverage**:
- ✅ Single edit success
- ✅ Multiple edits in sequence
- ✅ NOT_FOUND failure
- ✅ MULTIPLE_MATCHES failure
- ✅ Partial failure with continuation
- ✅ Pre-flight validation
- ✅ No-op detection (search === replace)
- ✅ Context extraction
- ✅ Edge cases (empty content, empty search)
- ✅ Indentation preservation

**Run Tests**:
```bash
npm run test:unit
```

---

### 7. Documentation: `apply-edits-tool.md`

**Location**: `vscode-app/docs/apply-edits-tool.md`

**Includes**:
- Overview and rationale
- Tool definition
- Usage examples (single, multiple, partial failure)
- When to use `apply_edits` vs `write_file`
- Implementation details
- Integration with auto-fix workflow
- Future enhancements

---

## 🔄 Workflow Comparison

### Before (Old Pattern)
```
User: "Fix the compilation errors"
LLM: "I'll use edit_file to fix line 507"
  → Calls edit_file with search="rightRoomId"
  → ❌ FAILS: Found 3 occurrences
  → LLM confused, retries same edit
  → Loop detected, agent stops
```

### After (Modern Pattern)
```
User: "Fix the compilation errors"
LLM: "I'll apply targeted edits"
  → Calls apply_edits with:
    [
      { search: "val rightRoomId = rightRoom?.id", 
        replace: "val rightRoomId = rightRoom?.id ?: \"\"" }
    ]
  → ✅ System validates uniqueness
  → ✅ Applies edit
  → Returns: "Applied 1/1 edits"
  → Auto-nudge: "Re-run build to verify"
```

---

## 📊 Benefits

| Benefit | Impact |
|---------|--------|
| **No more tool choice confusion** | LLM uses one tool for all edits |
| **Validation before application** | Fails fast with clear errors |
| **Batch editing** | Multiple edits in one call |
| **Partial success handling** | Continues after individual failures |
| **Actionable error messages** | LLM can retry with corrected search strings |
| **Auto-fix integration** | Works seamlessly with build failure workflow |

---

## 🧪 Testing Status

```
✅ TypeScript compilation: PASSED
✅ Unit tests: WRITTEN (11 test cases)
✅ Integration: INTEGRATED with AgentBridge
✅ Documentation: COMPLETE
```

**To run tests** (when VSCode test runner is available):
```bash
npm run test:unit
```

---

## 🚀 Usage Guidelines for LLM

### ✅ Good Use Cases
```json
{
  "tool": "apply_edits",
  "args": {
    "path": "src/main.kt",
    "edits": [
      { "search": "rightRoomId", "replace": "rightRoom?.id ?: \"\"" },
      { "search": "leftRoomId", "replace": "leftRoom?.id ?: \"\"" }
    ]
  }
}
```

### ❌ Bad Use Cases
```json
// Too vague - search string appears many times
{
  "tool": "apply_edits",
  "args": {
    "path": "src/main.kt",
    "edits": [
      { "search": "id", "replace": "identifier" }  // ❌ Will fail: MULTIPLE_MATCHES
    ]
  }
}

// Better: include more context
{
  "tool": "apply_edits",
  "args": {
    "path": "src/main.kt",
    "edits": [
      { 
        "search": "val rightRoomId = rightRoom?.id",
        "replace": "val rightRoomId = rightRoom?.id ?: \"\""
      }
    ]
  }
}
```

---

## 🔮 Future Enhancements

1. **AST-Based Editing** - Parse code into AST for robust matching
2. **Fuzzy Matching** - Allow approximate matches with confidence scores
3. **Multi-Line Edits** - Support for block-level search/replace
4. **Diff Preview** - Show unified diff before applying
5. **Undo Stack** - Track edits for rollback capability

---

## 📚 References

- [Cursor Editing Pattern](https://cursor.sh)
- [Claude Code Tool Design](https://claude.ai/code)
- [GitHub Copilot Workspace](https://github.com/features/copilot)
- [Internal Documentation](./apply-edits-tool.md)

---

## 📝 Files Modified

| File | Changes |
|------|---------|
| `src/agent/ApplyEditsTool.ts` | ✅ NEW - Core editing library |
| `src/agent/AgentBridge.ts` | ✅ Added tool definition + handler |
| `src/agent/LocalI2VisionAgent.ts` | ✅ Registered in available tools |
| `src/agent/ToolCardConfig.ts` | ✅ Display configuration |
| `src/test/suite/ApplyEditsTool.test.ts` | ✅ NEW - Test suite |
| `docs/apply-edits-tool.md` | ✅ NEW - User documentation |
| `docs/APPLY_EDITS_IMPLEMENTATION.md` | ✅ NEW - This file |
| `package.json` | ✅ Added test scripts |

**Total**: 8 files (2 new libraries, 4 modifications, 2 documentation)
