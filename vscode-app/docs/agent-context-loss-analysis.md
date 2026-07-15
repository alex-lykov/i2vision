# Agent Context Loss - Root Cause Analysis

## Executive Summary

The agent loses context between conversation turns due to **stateless architecture** combined with **incomplete state persistence**. When a conversation is resumed, a new agent instance is created with no memory of previous exploration, cached results, or runtime state.

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────┐
│  VSCode Webview                                             │
│  - Displays conversation                                    │
│  - Sends user_input, receives responses                     │
└─────────────────────────────────────────────────────────────┘
                          ↕
┌─────────────────────────────────────────────────────────────┐
│  AgentTabManager.ts                                         │
│  - Manages tabs (Map<string, AgentTabState>)               │
│  - Persists to: .vision-ai/history/{tabId}.json            │
│  - Creates new LocalI2VisionAgent on resume                │
└─────────────────────────────────────────────────────────────┘
                          ↕
┌─────────────────────────────────────────────────────────────┐
│  AgentBridge.ts                                             │
│  - Executes agent loop                                      │
│  - State machine: Intent → Plan → Constraints → Execute    │
│  - Runtime state: _autoReadFiles, _domainResolution, etc.  │
│  - ALL LOST ON AGENT RECREATION                            │
└─────────────────────────────────────────────────────────────┘
                          ↕
┌─────────────────────────────────────────────────────────────┐
│  CLI (Kotlin)                                               │
│  - File search, directory listing                           │
│  - Architecture cache: .vision-ai/cache/architecture.json  │
└─────────────────────────────────────────────────────────────┘
```

---

## Critical Findings

### 1. Agent Recreation Destroys All Runtime State

**Location:** `AgentTabManager.ts:637-640`

```typescript
async resumeConversation(conversationId: string): Promise<string> {
  this.log('Resuming conversation: ' + conversationId);
  return await this.createTab('vision', conversationId);  // ← Creates NEW agent
}
```

**Location:** `AgentTabManager.ts:160-182`

```typescript
async createTab(layer: string, conversationId?: string): Promise<string> {
  const agent = await this.agentProvider.createAgent(layerEnum);  // ← NEW instance
  const tabState: AgentTabState = { 
    tabId, 
    agent, 
    layer, 
    history: [],           // ← History loaded separately
    accumulatedToolCalls: [],
    // ... NO SESSION STATE FIELD
  };
  this.currentAgentBridge = new AgentBridge(...);  // ← NEW bridge
  await this.currentAgentBridge.initialize();
  // ...
}
```

**Problem:** Every resume creates a fresh `AgentBridge` with:
- Empty `_autoReadFiles: Set<string>`
- Empty `_fileSnapshots: Map<string, string>`
- Empty `_previousSearches: string[]`
- Reset `_failedSearchCount: number`
- No `_domainResolution` cache
- Reset state machine

---

### 2. Message History NOT Injected into LLM Context

**Location:** `AgentTabManager.ts:184-194`

```typescript
private async loadConversationData(tabId: string): Promise<void> {
  const saved = await this.historyManager.load(tabId);
  // ...
  tabState.history = saved.messages;  // ← Stored in tab state ONLY
  // ...
}
```

**Location:** `AgentBridge.ts:518-521`

```typescript
async *executeAgentLoop(userInput: string, systemPrompt: string, ...) {
  // ...
  let messages: LLMMessage[] = [
    { role: 'system', content: contextEnhancedPrompt },
    { role: 'user', content: userInput }  // ← ONLY current input
  ];
  // NO loading of tabState.history into messages!
}
```

**Problem:** The conversation history is:
- ✅ Stored in `tabState.history`
- ✅ Sent to webview for display
- ❌ **NEVER injected into the LLM `messages` array**

The LLM only sees the current `userInput`, not the conversation history.

---

### 3. No Search/Exploration Caching

**Location:** `AgentBridge.ts:179-184`

```typescript
private _failedSearchCount: number = 0;
private _lastSearchPattern: string | null = null;
private _previousSearches: string[] = [];  // ← In-memory ONLY
```

**Problem:** These arrays track exploration to prevent loops, but:
- Not persisted to `SavedConversation`
- Not restored on resume
- Agent can re-execute identical searches indefinitely

**Evidence from logs:**
```
[11:42:09 AM] list_directory: 11 files found
[11:42:15 AM] list_directory: 11 files found  
[11:42:18 AM] list_directory: 11 files found
[11:42:22 AM] list_directory: 11 files found
[11:42:25 AM] list_directory: 444 files found (recursive)
[11:42:31 AM] Pattern detected: 5 consecutive exploration calls
```

---

### 4. Domain Detection Resets on Every Turn

**Location:** `AgentBridge.ts:493-498`

```typescript
async *executeAgentLoop(...) {
  // DOMAIN DETECTION: Runs FRESH every turn
  this._domainResolution = this.domainDetector.resolveDomain(userInput);
  this._suggestedDirectories = this._domainResolution.suggestedDirectories;
  
  if (this._domainResolution.primaryDomain !== 'unknown' ...) {
    this.log(`Domain resolved: ${this._domainResolution.primaryDomain}...`);
  }
  // ...
}
```

**Problem:** Domain resolution is:
- Computed per-request (not cached)
- Not persisted across resume
- Can return different results (`frontend (40%)` → `default`)

---

### 5. SavedConversation Schema Missing Session State

**Location:** `ConversationHistoryManager.ts:29-37`

```typescript
export interface SavedConversation {
  id: string;
  workspace: string;
  layer: string;
  messages: ChatMessage[];  // ← Only messages saved
  createdAt: number;
  updatedAt: number;
  contextTitle?: string;
  // NO sessionState field!
}
```

**Problem:** The schema has no field for:
- `visitedPaths: string[]`
- `searchCache: { query: string, results: any[], timestamp: number }[]`
- `resolvedDomain: DomainResolution`
- `workingDirectory: string`
- `toolFilter: 'all' | 'action_only'`

---

## Fix Priority Matrix

| Priority | Issue | Impact | Effort |
|----------|-------|--------|--------|
| **P0** | Inject message history into LLM | Critical - LLM has no context | Low |
| **P0** | Add `sessionState` to SavedConversation | Critical - State lost on resume | Medium |
| **P1** | Cache directory scan results | High - Token waste | Low |
| **P1** | Cache search results by query hash | High - Token waste | Low |
| **P1** | Persist `visitedPaths` in session state | High - Prevents re-exploration | Medium |
| **P2** | Persist domain resolution | Medium - Inconsistent behavior | Low |
| **P2** | Persist tool filter state | Medium - Mode resets | Low |
| **P3** | Context compression | Low - Optimization | High |

---

## Recommended Implementation Order

### Phase 1: Critical (P0)

1. **Add `sessionState` field to `SavedConversation`**
   ```typescript
   interface SavedConversation {
     // ... existing fields
     sessionState?: {
       visitedPaths: string[];
       searchCache: Array<{ query: string; results: string[]; timestamp: number }>;
       resolvedDomain?: { primaryDomain: string; confidence: number; suggestedDirectories: string[] };
       workingDirectory?: string;
       toolFilter?: 'all' | 'action_only';
       forceActionMode?: boolean;
     };
   }
   ```

2. **Add `sessionState` to `AgentTabState`**
   ```typescript
   interface AgentTabState {
     // ... existing fields
     sessionState?: AgentSessionState;
   }
   ```

3. **Inject message history into LLM context**
   ```typescript
   // In AgentBridge.executeAgentLoop()
   async *executeAgentLoop(userInput: string, systemPrompt: string, history?: ChatMessage[]) {
     let messages: LLMMessage[] = [
       { role: 'system', content: contextEnhancedPrompt }
     ];
     
     // ADD: Inject conversation history
     if (history && history.length > 0) {
       for (const msg of history) {
         messages.push({
           role: msg.role,
           content: msg.content,
           tool_calls: msg.toolCalls?.map(tc => ({
             id: `call_${Date.now()}`,
             type: 'function',
             function: { name: tc.toolName, arguments: JSON.stringify(tc.args) }
           }))
         });
       }
     }
     
     messages.push({ role: 'user', content: userInput });
   }
   ```

4. **Pass history from AgentTabManager to AgentBridge**
   ```typescript
   // In AgentTabManager.processUserInput()
   const streamGenerator = this.currentAgentBridge.processStreaming(
     userInput, 
     currentFile,
     tabState.history  // ← Pass history
   );
   ```

### Phase 2: High Impact (P1)

5. **Implement search result caching in CLI**
   - Cache key: `hash(query + workspaceRoot)`
   - Store in `sessionState.searchCache`
   - Invalidate on file changes

6. **Track visited paths**
   - Add to `sessionState.visitedPaths` on each `list_directory`
   - Check before scanning

7. **Persist domain resolution**
   - Store `sessionState.resolvedDomain`
   - Skip re-detection if recent (< 5 min)

### Phase 3: Polish (P2-P3)

8. **Add context compression**
   - Summarize turns >10 when context >70%

9. **Improve exploration loop detector**
   - Detect at 3 calls (not 5)
   - Warn user at 4

---

## Files to Modify

| File | Changes | Priority |
|------|---------|----------|
| `ConversationHistoryManager.ts` | Add `sessionState` to `SavedConversation` | P0 |
| `AgentTabManager.ts` | Add `sessionState` to `AgentTabState`, pass to bridge | P0 |
| `AgentBridge.ts` | Inject history into LLM, restore session state | P0 |
| `DomainDetector.ts` | Add result caching | P2 |
| `CLI.ts` | Add search caching | P1 |

---

## Testing Checklist

- [ ] Resume conversation → LLM sees full history
- [ ] Resume conversation → same search not re-executed
- [ ] Resume conversation → domain stays consistent
- [ ] Resume conversation → visited paths remembered
- [ ] Token usage accurate after resume
- [ ] No exploration loops after resume
