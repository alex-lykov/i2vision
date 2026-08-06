# AgentBridge Refactor Plan

**Date:** 2026-08-06  
**Status:** Planning (re-scoped after code analysis)  
**Goal:** Split `src/agent/AgentBridge.ts` (166 KB / 3,507 lines) into smaller, maintainable modules so automated tools (`apply_edits`, `write_file`) can operate on them reliably.

---

## Problem

- `AgentBridge.ts` is **166 KB / 3,507 lines** — well over the 50 KB limit for `apply_edits` and `write_file`.
- A one-line fix (changing `await` to `.then()` in the constructor) is currently impossible with available tooling.
- Repeated edit attempts have caused partial file corruption requiring `revert_file`.

---

## Actual Code Structure (from analysis)

The file has **66 methods / 50+ fields** across 3,507 lines. The two biggest problems:

| Method | Lines | % of File | Issue |
|--------|-------|-----------|-------|
| `executeAgentLoop()` | **1,102** | 31.4% | God function: interleaves state machine, tools, tokens, session, build, search, strategy rotation in one async generator |
| `executeToolLegacy()` | **621** | 17.7% | Legacy switch-statement duplicating `ToolRegistry.executeTool()` — kept for backward compat |

Together these two methods are **49% of the file** and the main reason the file can't be split into tool-editable chunks.

### Full method inventory by logical group

| Group | Methods | Est. Lines | % |
|-------|---------|-----------|----|
| A. Core Orchestration | `process()`, `processStreaming()`, `executeAgentLoop()`, `chunksToResponse()`, `getTools()`, `detectTaskType()`, `loadEagerContext()`, `mergeContextProfiles()`, `injectContextIntoPrompt()`, `buildSystemPrompt()` | ~1,160 | 33% |
| B. Tool Execution Pipeline | `executeTool()`, `executeToolLegacy()`, `executeToolRateLimited()`, `executeToolWithRetry()`, `processToolQueue()`, `monitorToolExecution()`, `validateToolResult()`, `runCommandWithTimeout()`, `isDestructiveCommand()`, `isBuildCommand()`, `classifyCommand()`, `readFileCached()`, `invalidateFileCache()` | ~1,030 | 29% |
| C. Build & Compilation | `findBuildFile()`, `extractBuildTasks()`, `extractCompilationErrors()`, `formatBuildResult()`, `formatGitStatus()`, `getWorkspaceKey()` | ~115 | 3% |
| D. Session & Context Mgmt | `initialize()`, `restoreSessionState()`, `getSessionState()`, `setWorkspaceRoot()`, `updateConfig()`, `setSessionManager()`, `getSessionManager()`, `isSessionError()`, `isContextExhaustionError()`, `injectSimplifiedToolPrompt()` | ~270 | 8% |
| E. Conversation / Token | `estimateTokens()`, `summarizeConversation()`, `trimMessagesToBudget()` | ~85 | 2% |
| F. LLM Provider Interface | `callLLM()`, `formatToolsForSystemPrompt()`, `extractReasoning()`, `extractFinalResponse()`, `detectProxyMisbehavior()` | ~58 | 2% |
| G. Search Analysis | `normalizeSearchPattern()`, `findSimilarSearch()`, `recordSearchPattern()` | ~38 | 1% |
| H. State Machine | No dedicated methods — 20+ inline `this.stateMachine.*` calls inside `executeAgentLoop()` | ~300 | 9% |
| I. Utility & Diagnostics | `log()`, `logStateTransition()`, `logToolExecution()`, `emitProgress()`, `getToolExecutionStats()`, `getCurrentToolStatus()`, `getToolExecutionReport()`, `dispose()` | ~90 | 3% |
| J. Terminal Helpers | `generateTerminalName()`, `resolvePath()`, `resolveImportPath()` | ~15 | <1% |
| K. Public Accessors | `getConfig()`, `getState()`, `setLayer()`, `getLayer()` | ~9 | <1% |
| *Interfaces/Types + Imports/Fields* | | ~280 | 8% |

### Cross-group dependencies (tight coupling)

```
executeAgentLoop() [A]  --calls-->  everything:
  → getTools() [A self], buildSystemPrompt() [A self]
  → estimateTokens(), trimMessagesToBudget(), summarizeConversation() [E]
  → callLLM(), formatToolsForSystemPrompt(), extractReasoning(), extractFinalResponse(), detectProxyMisbehavior() [F]
  → executeToolRateLimited() → executeToolWithRetry() → executeTool() [B]
  → isSessionError(), isContextExhaustionError() [D]
  → findSimilarSearch(), recordSearchPattern(), normalizeSearchPattern() [G]
  → this.stateMachine.transition(), this.stateMachine.classifyIntent() etc. [H]
  → log(), logStateTransition(), emitProgress() [I]

executeTool() [B]  --calls-->  [B self], [D], [I]
executeToolLegacy() [B]  --calls-->  [C build], [J terminal], [I]
executeToolWithRetry() [B]  --calls-->  [D session], [I]
restoreSessionState() [D]  --calls-->  [I]
```

**Key dependency insight:** `executeAgentLoop()` is the hub — every other group is a spoke. This means we can extract spokes independently as long as the hub gets thin interface accessors instead of direct method calls.

---

## Revised Module Breakdown (re-scoped based on analysis)

| New File | Responsibility | Est. Size | Extracts From Groups |
|----------|---------------|-----------|---------------------|
| `AgentBridge.ts` *(retained)* | Thin orchestrator: constructor, `process()`, `processStreaming()`, `executeAgentLoop()`, public API | <50 KB | A core only |
| `AgentBridge.ToolPipeline.ts` | Tool dispatch, rate-limiting, retry, queue, validation, monitoring | ~350 lines | B (all but legacy) |
| `AgentBridge.CommandExecutor.ts` | Command running, timeout, destructive guard, caching, build/compile/git helpers | ~300 lines | B leftover + C + J |
| `AgentBridge.SessionManager.ts` | Init, reset, restore, serialize, error detection | ~270 lines | D full |
| `AgentBridge.LLMAdapter.ts` | Provider calls, formatting, parsing, token estimation, conversation trimming | ~150 lines | E + F |
| `AgentBridge.LegacyTools.ts` | `executeToolLegacy()` and its helpers — gate for deletion when ToolRegistry migration complete | ~800 lines | B legacy + C + J |
| `SearchCache.ts` | Search pattern normalization, similarity, recording | ~38 lines | G |
| `AgentBridge.Diagnostics.ts` | Logging, progress events, tool stats, dispose | ~90 lines | I |

### Dependency Graph

```
AgentBridge.ts (hub)
  ├── AgentBridge.SessionManager.ts    ← no deps on other modules (self-contained)
  ├── AgentBridge.LLMAdapter.ts        ← no deps on other modules (self-contained)
  ├── AgentBridge.ToolPipeline.ts      ← depends on AgentBridge.SessionManager (for error detection)
  ├── AgentBridge.CommandExecutor.ts   ← depends on AgentBridge.Diagnostics (for logging)
  ├── AgentBridge.LegacyTools.ts       ← depends on CommandExecutor (for build/terminal helpers)
  ├── SearchCache.ts                   ← no deps (self-contained utility)
  └── AgentBridge.Diagnostics.ts       ← no deps (self-contained utility)
```

No circular dependencies. Each module receives its dependencies via constructor or setter injection from `AgentBridge.ts`.

### Before/After

```
BEFORE:
  src/agent/
    AgentBridge.ts          (166 KB / 3,507 lines)

AFTER:
  src/agent/
    AgentBridge.ts          (<50 KB, thin orchestrator)
    AgentBridge.SessionManager.ts
    AgentBridge.LLMAdapter.ts
    AgentBridge.ToolPipeline.ts
    AgentBridge.CommandExecutor.ts
    AgentBridge.LegacyTools.ts
    AgentBridge.Diagnostics.ts
    SearchCache.ts
    AgentTabManager.ts
    ProxySessionManager.ts
    SessionManager.ts
    ...
```

---

## Phase 0: Regression Test Harness  NEW

**Goal:** Establish a baseline before any code moves, so every phase can verify no behavioral regressions.

- [ ] **Step 1:** Write `AgentBridge.test.ts` — a lightweight smoke test that:
  - Instantiates `AgentBridge` with a mock VSCode extension context, mock CLI, mock `SessionManager` (implementing the `SessionManager` interface), and mock `TerminalManager`
  - Calls `initialize()` and verifies it doesn't throw
  - Calls `process()` with a simple prompt and verifies the async generator yields at least one chunk
  - Calls `getState()`, `getConfig()`, `getToolExecutionStats()`, `getSessionState()` and verifies non-null returns
  - Calls `dispose()` and verifies resources are released
- [ ] **Step 2:** Add `"test:agent"` npm script: `"mocha ./out/test/suite/AgentBridge.test.js"`
- [ ] **Step 3:** `npm run test:agent` → confirm **green** on current code before any extraction
- [ ] **Step 4:** Run `npm run compile` → confirm clean build baseline
- [ ] **Step 5:** Commit baseline as `refactor/agentbridge-phase0-baseline` branch

---

## Git Strategy & Rollback Policy  NEW

**Every phase uses its own branch, merged only after compile + test pass:**

```
refactor/agentbridge-phase0-baseline  ← test harness + clean baseline
refactor/agentbridge-phase1-delete-legacy
refactor/agentbridge-phase2-extract-session
refactor/agentbridge-phase3-extract-llm
refactor/agentbridge-phase4-extract-tool-pipeline
refactor/agentbridge-phase5-extract-command-executor
refactor/agentbridge-phase6-extract-search
refactor/agentbridge-phase7-extract-diagnostics
```

**Rollback procedure per phase (before starting work):**
1. `git stash` any in-progress changes
2. `git checkout -b refactor/agentbridge-phaseN-...` from previous passing branch
3. If phase fails: discard branch, return to previous branch
4. Partial corruption means the phase is at most one branch — `git checkout <previous>` always recovers

**Merge criteria per phase:**
- `npm run compile` passes (no TS errors)
- `npm run test:agent` passes (smoke test green)
- No method signature changes to public API (`process()`, `processStreaming()`, `initialize()`, `dispose()`, `getState()`, `getConfig()`, `getSessionState()`)

---

## Phase 1: Delete Legacy Switch Statement  PRIORITY SHIFTED

**Rationale:** The `executeToolLegacy()` method (621 lines) duplicates `ToolRegistry.executeTool()` and deeply couples with Build & Terminal helpers. Deleting it removes ~800 lines including its helpers (`resolvePath()`, `runCommandWithTimeout()`, `findBuildFile()`, `extractBuildTasks()`, `extractCompilationErrors()`, `isDestructiveCommand()`, `isBuildCommand()`, `classifyCommand()`, `generateTerminalName()`, `formatBuildResult()`, `formatGitStatus()`, `getWorkspaceKey()`, `resolveImportPath()`). This immediately drops AgentBridge.ts from 3,507 to ~2,700 lines (~50 KB reduction).

- [ ] **Step 1:** Verify `executeAgentLoop()` never calls `executeToolLegacy()` — only `executeTool()` via the `executeToolRateLimited()` → `executeToolWithRetry()` chain
- [ ] **Step 2:** Verify all tools handled by `executeToolLegacy()` are also registered in `ToolRegistry`
- [ ] **Step 3:** Remove `executeToolLegacy()` method (lines 2512–3132)
- [ ] **Step 4:** Remove orphaned helpers only used by legacy: `runCommandWithTimeout()`, `formatBuildResult()`, `formatGitStatus()`, `extractCompilationErrors()`, `isDestructiveCommand()`, `isBuildCommand()`, `classifyCommand()`, `generateTerminalName()`, `resolvePath()`, `findBuildFile()`, `extractBuildTasks()`, `getWorkspaceKey()`, `resolveImportPath()`
- [ ] **Step 5:** Remove orphaned state fields: `_pendingFixes`, `_buildFailureCount`, `_fixMode`, `_failedEditAttempts`, `_lastBuildErrors`, `_consecutiveSuccessfulEdits`, `_consecutivePlans`, `_buildFileReadAttempts`, `_hasReadBuildFiles`
- [ ] **Step 6:** `npm run compile` → verify
- [ ] **Step 7:** `npm run test:agent` → verify smoke test still green
- [ ] **Step 8:** Verify AgentBridge.ts is now ~130 KB / ~2,700 lines (still above 50 KB but significantly closer)

---

## Phase 2: Extract SessionManager + LLMAdapter + SearchCache + Diagnostics  

**Strategy:** Extract the four self-contained spoke modules simultaneously in one pass. Since none of these depend on each other, they can be moved in parallel, and the hub (`AgentBridge.ts`) only needs delegating methods added.

### Step 2a: Extract AgentBridge.SessionManager.ts

- [ ] Create `AgentBridge.SessionManager.ts` with class `AgentBridgeSessionManager`
- [ ] Move methods (lines as-is, no refactoring): `initialize()`, `restoreSessionState()`, `getSessionState()`, `setWorkspaceRoot()`, `updateConfig()`, `setSessionManager()`, `getSessionManager()`, `isSessionError()`, `isContextExhaustionError()`, `injectSimplifiedToolPrompt()`
- [ ] Move related state: `sessionManager`, `_sessionState`, `_needsFreshSession`, `_domainResolution`, domain state fields
- [ ] Constructor injection: pass `cli`, `terminalManager`, `settingsManager`, `outputChannel` references
- [ ] Expose `getSessionManager()` and `getSessionState()` as public for hub delegation

### Step 2b: Extract AgentBridge.LLMAdapter.ts

- [ ] Create `AgentBridge.LLMAdapter.ts` with class `AgentBridgeLLMAdapter`
- [ ] Move methods: `callLLM()`, `formatToolsForSystemPrompt()`, `extractReasoning()`, `extractFinalResponse()`, `detectProxyMisbehavior()`, `estimateTokens()`, `summarizeConversation()`, `trimMessagesToBudget()`
- [ ] Move related state: `_lastTokenUsage`, `_lastKnownPromptTokens`, `_lastKnownMessageCount`, `_lastKnownMessageChars`, `_lastProviderCapabilities`
- [ ] Constructor injection: pass `cli` reference
- [ ] Expose all methods as public for hub delegation

### Step 2c: Extract SearchCache.ts

- [ ] Create `SearchCache.ts` with class `SearchCache`
- [ ] Move methods: `normalizeSearchPattern()`, `findSimilarSearch()`, `recordSearchPattern()`
- [ ] Move related state: `_failedSearchCount`, `_lastSearchPattern`, `_previousSearches`, `_searchCache`
- [ ] No constructor deps needed (pure data class)
- [ ] Expose all methods as public

### Step 2d: Extract AgentBridge.Diagnostics.ts

- [ ] Create `AgentBridge.Diagnostics.ts` with class `AgentBridgeDiagnostics`
- [ ] Move methods: `log()`, `logStateTransition()`, `logToolExecution()`, `emitProgress()`, `getToolExecutionStats()`, `getCurrentToolStatus()`, `getToolExecutionReport()`, `dispose()`
- [ ] Move related state: `_toolCallHistory`, `outputChannel`
- [ ] Constructor injection: pass `outputChannel`
- [ ] Expose all methods as public

### Step 2e: Wire Up AgentBridge.ts Hub

- [ ] Add private fields for each new module: `this.sessionMgr`, `this.llmAdapter`, `this.searchCache`, `this.diagnostics`
- [ ] Initialize in constructor after base fields
- [ ] Add thin delegation methods for public API surface (e.g., `getSessionState()` → `return this.sessionMgr.getSessionState()`)
- [ ] **Fix the `await` bug** in the constructor during wiring (replace `await` with `.then()` pattern)
- [ ] Update all internal call sites in `executeAgentLoop()` and remaining methods to use `this.llmAdapter.estimateTokens()` instead of `this.estimateTokens()`, etc.

### Post-Phase-2 Verification

- [ ] `npm run compile` → pass
- [ ] `npm run test:agent` → pass
- [ ] AgentBridge.ts should now be **~50–70 KB / ~1,100–1,400 lines** (down from ~2,700)
- [ ] Every new file is under 30 KB

---

## Phase 3: Extract ToolPipeline Module

- [ ] Create `AgentBridge.ToolPipeline.ts` with class `AgentBridgeToolPipeline`
- [ ] Move methods: `executeTool()`, `executeToolRateLimited()`, `executeToolWithRetry()`, `processToolQueue()`, `monitorToolExecution()`, `validateToolResult()`, `readFileCached()`, `invalidateFileCache()`
- [ ] Move related state: tool execution queue, active tool execution tracking, tool call history, file read cache, rate limit state
- [ ] Constructor injection: pass `toolRegistry`, `sessionMgr` reference (for error detection), `diagnostics` reference (for logging)
- [ ] Wire up delegation in AgentBridge.ts hub
- [ ] `npm run compile` → pass
- [ ] `npm run test:agent` → pass
- [ ] AgentBridge.ts should now be **<50 KB** — finally tool-editable

---

## Phase 4: Shrink executeAgentLoop() (if still needed)

After phases 1–3, `executeAgentLoop()` should be the main remaining mass in AgentBridge.ts. If the file is still above 50 KB:

- [ ] Extract the build-fix cycle logic (build verification, strategy rotation, auto-read heuristics) into `AgentBridge.BuildFixStrategy.ts`
- [ ] Extract the search-optimization logic into `SearchOptimizer.ts` (uses `SearchCache`)
- [ ] Goal: `executeAgentLoop()` should be under 300 lines

---

## Success Criteria

- [ ] `AgentBridge.ts` is under **50 KB** — usable with `apply_edits` and `write_file`
- [ ] Every new file is under **30 KB**
- [ ] `npm run compile` passes at every phase
- [ ] `npm run test:agent` passes at every phase (smoke test green)
- [ ] All existing public API signatures preserved (no behavioral changes)
- [ ] No circular dependencies between modules
- [ ] The `await` bug in the constructor is fixed

---

## Progress Tracker

| Phase | Status | Date Completed | Notes |
|-------|--------|----------------|-------|
| Phase 0: Test Harness | ⬜ Pending | — | Create smoke test + npm script |
| Phase 1: Delete Legacy | ⬜ Pending | — | Removes ~800 lines (executeToolLegacy + helpers) |
| Phase 2: Extract 4 Modules | ⬜ Pending | — | Session, LLM, Search, Diagnostics simultaneously |
| Phase 3: Extract ToolPipeline | ⬜ Pending | — | Final push to get under 50 KB |
| Phase 4: Shrink AgentLoop | ⬜ Pending | — | Only if still above 50 KB after Phase 3 |
