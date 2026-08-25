# Agent Context Loss - Problem Analysis & Task List

Generated: 2026-07-02

## Summary

The vision/code agent loses context between conversation turns and after resume due to:
1. Stateless agent architecture (new instance on every resume)
2. No caching of exploration results (repeated scans/searches)
3. Message history not properly injected into LLM context
4. Domain/tool filter state resets on resume

---

## P1: Critical (Context Loss)

- [ ] **Persist working directory in conversation state**
  - Current: Agent defaults to wrong workspace on resume
  - Fix: Store `working_directory` in tab JSON, restore on agent creation

- [ ] **Inject full message history into LLM context**
  - Current: Messages stored but not passed to LLM (message count resets to "2 msg")
  - Fix: Ensure `messages` array in LLM request includes all loaded history

- [ ] **Add session state that survives agent recreation**
  - Current: New agent ID = fresh state (`agent-VISION-xxx` created on every resume)
  - Fix: Create `SessionState` object persisted in tab, passed to new agent instances

## P2: High (Token Waste)

- [ ] **Cache directory scan results**
  - Current: `list_directory(".")` called 5 times with identical results
  - Fix: Cache by `(path, recursive)` key, invalidate on file changes

- [ ] **Cache search results by query hash**
  - Current: Same grep/search queries re-executed after resume
  - Fix: Store `{ query, results, timestamp }` in conversation state

- [ ] **Track visited paths per session**
  - Current: No memory of explored directories
  - Fix: Maintain `visited_paths: Set<string>` in session state

## P3: Medium (Inconsistent Behavior)

- [ ] **Persist domain resolution result**
  - Current: `frontend (40%)` → `default` after resume
  - Fix: Store `resolved_domain` in tab state, skip re-detection if recent

- [ ] **Preserve tool filter state**
  - Current: `action_only` mode resets to `all` on resume
  - Fix: Store `tool_filter` and `force_action_mode` flags in session state

- [ ] **Fix project type detection**
  - Current: Scans Kotlin project when issue is TypeScript/React
  - Fix: Multi-root workspace support or domain-based project switching

## P4: Low (Optimization)

- [ ] **Implement context compression**
  - Current: 50% context usage with no summarization
  - Fix: Summarize turns >10 when context >70%

- [ ] **Add exploration loop detector earlier**
  - Current: Forces synthesis after 5 repeated calls
  - Fix: Detect at 3 calls, warn user at 4

- [ ] **Log agent state transitions**
  - Current: No visibility into state machine changes
  - Fix: Add debug logging for state transitions

---

## Files to Investigate

| File | Purpose | Priority |
|------|---------|----------|
| `.vision-ai/tabs/tab-*.json` | Conversation storage | P1 |
| `LocalAgentProvider.ts` | Agent creation | P1 |
| `AgentBridge.ts` | LLM context building | P1 |
| `AgentTabManager.ts` | Tab state management | P2 |
| `DomainResolver.ts` | Domain detection | P3 |
| `CLI.ts` | File search/scan | P2 |

---

## Investigation Checklist

- [ ] Find conversation storage format
- [ ] Trace agent creation flow
- [ ] Trace LLM message injection
- [ ] Find domain resolver implementation
- [ ] Find tool filter logic
- [ ] Find exploration loop detector
