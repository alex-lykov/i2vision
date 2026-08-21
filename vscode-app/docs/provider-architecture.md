# Provider Architecture

## Overview

i2-Vision supports multiple LLM backends through a provider abstraction layer. The
core principle is **capability-driven adaptation** — the common agent loop code never
hardcodes provider-specific branches. Instead, each provider declares its capabilities,
and the agent loop adapts its behavior accordingly.

## Architecture Layers

```
┌──────────────────────────────────────────────┐
│              AgentBridge                      │
│  Common flow: iteration loop, tool execution, │
│  state machine, context management            │
│                                                │
│  Reads provider capabilities to adapt:         │
│    capabilities.streaming                     │
│    capabilities.nativeToolCalls               │
│    capabilities.sessionManagement             │
│    capabilities.structuredMessages            │
└──────────────┬───────────────────────────────┘
               │
               ▼
┌──────────────────────────────────────────────┐
│              CLI (cliIntegrationRefactored)    │
│  Dispatches callLLM → providerFactory         │
│  Converts provider response to uniform format │
└──────────────┬───────────────────────────────┘
               │
               ▼
┌──────────────────────────────────────────────┐
│           ProviderFactory                      │
│  modelId → provider resolution                │
│  Config extraction from VSCode settings       │
│  Provider instantiation                       │
└──────────────┬───────────────────────────────┘
               │
               ▼
┌──────────────────────────────────────────────┐
│           LLMProvider (interface)              │
│  callAPI(request) → response                  │
│  validateConfiguration()                      │
│  isAvailable?()                               │
│  listModels?()                                │
└──────────────────────────────────────────────┘
```

## Interface: LLMProvider

**File:** `vscode-app/src/types/provider-types.ts`

```typescript
interface LLMProvider {
  getProviderName(): string;
  validateConfiguration(): void;
  callAPI(request: LLMRequest): Promise<LLMResponse>;
  isAvailable?(): Promise<boolean>;
  listModels?(): Promise<string[]>;
}

interface LLMRequest {
  prompt: string;
  model?: string;
  messages?: LLMMessage[];
  temperature?: number;
  topP?: number;
  maxTokens?: number;
  tools?: any[];
  [key: string]: any;        // Provider-specific extensions
}

interface LLMResponse {
  text: string;
  model: string;
  provider: string;
  usage?: { promptTokens, completionTokens, totalTokens };
  [key: string]: any;        // Provider-specific data (e.g. tool_calls)
}
```

**Key design decision:** `LLMRequest` and `LLMResponse` use `[key: string]: any`
tail-slots for provider-specific parameters (`stream`, `thinking_enabled`,
`search_enabled`, `tool_calls`). This allows incremental migration to typed
extensions without breaking existing callers.

## Session Management

Each provider is paired with a `SessionManager`:

| Provider       | Session Manager        | Behavior |
|----------------|----------------------|----------|
| 3D LLM         | `ProxySessionManager` | Full session management — discovery via `/v1/sessions`, health checks with `/v1/models` fallback, proactive reset at 85 messages / 90 min, message compaction, token tracking |
| Ollama         | `NullSessionManager`  | No-op — stateless provider |
| DeepSeek       | `NullSessionManager`  | No-op — stateless provider |
| Mistral        | `NullSessionManager`  | No-op — stateless provider |

**Interface:** `vscode-app/src/agent/SessionManager.ts:71-119`

The factory at line 165 creates the appropriate manager:

```typescript
function createSessionManager(provider: string, baseUrl?: string): SessionManager {
  switch (provider) {
    case '3d-llm': return new ProxySessionManager();
    default:       return new NullSessionManager(provider);
  }
}
```

`AgentBridge` gates session behavior via two capability flags:

- `sessionManagement` — controls sync, health checks, and fresh-session initialization
- `contextCompaction` — controls `manageMessages()`, `isContextExhausted()`, and `updateTokenUsage()`

Only `3d-llm` has both flags set to `true`. All other providers gate through via
`NullSessionManager` as a safe no-op.

### 3D LLM Session Compaction Flow

The compaction pipeline uses a two-layer defense model — local token-budget trimming
in AgentBridge, and proxy-aware compaction in ProxySessionManager.

```
                         ┌───────────────────┐
                         │   User sends task  │
                         └─────────┬─────────┘
                                   │
                                   ▼
                         ┌───────────────────┐
                         │  AgentBridge.init │
                         │  resetSession()   │  ← POST /reset-session?agent=...
                         │  clear all state  │
                         └─────────┬─────────┘
                                   │
              ╔════════════════════╧════════════════════╗
              ║        PER-ITERATION LOOP               ║
              ║  (gated by caps.contextCompaction)      ║
              ╚════════════════════╤════════════════════╝
                                   │
              ┌────────────────────┼────────────────────┐
              │                    ▼                    │
              │    ┌───────────────────────────────┐    │
              │    │ LAYER 1: AgentBridge local     │    │
              │    │ trimMessagesToBudget()         │    │
              │    │                                │    │
              │    │ Trigger: estimatedTokens       │    │
              │    │   > 80% contextWindow          │    │
              │    │                                │    │
              │    │ Action: keep system + last 8   │    │
              │    │   summarize middle into [Ctx]  │    │
              │    │   invalidate token baseline    │    │
              │    └───────────────┬───────────────┘    │
              │                    │                    │
              │                    ▼                    │
              │    ┌───────────────────────────────┐    │
              │    │ LAYER 2: ProxySessionManager  │    │
              │    │ manageMessages(messages)      │    │
              │    │                               │    │
              │    │ Checks 5 triggers in order:   │    │
              │    │                               │    │
              │    │  ┌─ messages >= 85? ──yes──┐  │    │
              │    │  │  COMPACT (keep last 30)  │  │    │
              │    │  │  summarize old → [Prev]  │  │    │
              │    │  └──────────────────────────┘  │    │
              │    │                                │    │
              │    │  ┌─ tokens >= 57600 (90%)?    │    │
              │    │  │  → set contextExhausted    │    │
              │    │  │  → COMPACT + flag          │    │
              │    │  └────────────────────────────┘  │    │
              │    │                                │    │
              │    │  ┌─ tokens >= 80%? (warn only)│    │
              │    │  │  → log, cooldown 5 min     │    │
              │    │  └────────────────────────────┘  │    │
              │    │                                │    │
              │    │  ┌─ age > 90 min?  (warn only)│    │
              │    │  └────────────────────────────┘  │    │
              │    │                                │    │
              │    │  ┌─ continuations >= 2?        │    │
              │    │  │  → warn only                │    │
              │    │  └────────────────────────────┘  │    │
              │    └───────────────┬───────────────┘    │
              │                    │                    │
              │                    ▼                    │
              │    ┌───────────────────────────────┐    │
              │    │ isContextExhausted()?          │    │
              │    │                               │    │
              │    │ YES → resetSession(token_limit)│   │
              │    │   POST /reset-session          │    │
              │    │   clear all local state        │    │
              │    │   clear token tracking         │    │
              │    └───────────────┬───────────────┘    │
              │                    │                    │
              │                    ▼                    │
              │    ┌───────────────────────────────┐    │
              │    │ POST-LLM: updateTokenUsage()   │    │
              │    │ accumulate prompt+completion   │    │
              │    │ tokens into session totals     │    │
              │    └───────────────────────────────┘    │
              │                                         │
              │    ┌──── OTHER RESET TRIGGERS ────┐     │
              │    │  • misbehavior detection      │     │
              │    │  • forceFreshSession flag     │     │
              │    │  • health check failure       │     │
              │    │  • tool execution errors      │     │
              │    │  • fresh conversation start   │     │
              │    │                               │     │
              │    │  All → resetSession()         │     │
              │    │  clear messages, tokens,      │     │
              │    │  counters, exhaust flags      │     │
              │    └───────────────────────────────┘     │
              │                                         │
              └─────────────────────────────────────────┘
```

#### Compaction Detail

When `compactMessages()` fires (message count ≥ 85 or tokens ≥ 90%):

```
Input:  messages[] (e.g. 52 total: 1 system + 51 user/assistant/tool)
                          │
          1. Extract system prompt
          2. Separate non-system messages
          3. non-system > maxHistoryLength*2 (30)?
             │  YES — proceed         │  NO — return as-is
             ▼                        ▼
          4. Split: recent = last 30, old = remainder
                          │
          5. summarizeConversation(old):
             • Pair messages (user, assistant)
             • Extract: user first 80 chars of line 1
             • Extract: assistant first 80 chars of line 1
             • "Q: <question> → A: <answer>; ..." (max 5 pairs)
                          │
          6. Rebuild:
             [system prompt,
              "[Previous conversation summary: ...]",
              ...recent 30 non-system messages]

Output: compacted messages (e.g. 52 → 32)
```

#### Limits Reference

From `ProxySessionManager.DEFAULT_3D_LLM_LIMITS`:

| Limit | Value | Description |
|-------|-------|-------------|
| `maxMessages` | 100 | Proxy hard cap per session |
| `ttlMs` | 2 hours | Proxy session lifetime |
| `maxHistoryLength` | 15 | Keep last 15 exchanges after compaction |
| `maxHistoryChars` | 10000 | Reserved (not actively enforced) |
| **Auto-reset triggers** | | |
| `messageCount` | 85 | Compact when ≥85 messages (85% of 100) |
| `ageMinutes` | 90 | Warn when session >90 min old |
| `continuationLimit` | 2 | Warn after 2 auto-continuations |
| `tokenUsagePercentage` | 90% | Exhaustion at 90% of context |
| `maxTokenUsage` | 57600 | 90% of 64000 token window |

#### Reset vs Compaction

| Mechanism | Trigger | Action | Side Effects |
|-----------|---------|--------|--------------|
| **Local trim** (`trimMessagesToBudget`) | >80% token budget | Summarize middle, keep last 8 | Invalidates token baseline |
| **Compaction** (`compactMessages`) | ≥85 messages or ≥90% tokens | Keep system + last 30, summarize old | Preserves recent context |
| **Exhaustion** (`isContextExhausted`) | tokenUsage ≥ 57600 | Flag + triggers `resetSession('token_limit')` | Clears all tracking |
| **Full reset** (`resetSession`) | startup / exhaustion / health / misbehavior / errors | POST `/reset-session` + clear all local state | Fresh start, all counters zeroed |

## Provider Dispatch

**File:** `vscode-app/src/providers/ProviderFactory.ts`

Model ID patterns determine the provider:

| Pattern                          | Provider              |
|----------------------------------|-----------------------|
| `mistral-tiny/small/medium/large/embed` | MistralProvider  |
| `ollama:`, `llama`, `vicuna`, `orca`    | OllamaProvider    |
| `deepseek:`, `deepseek-chat/coder`      | DeepSeekProvider  |
| `3dllm:`, `deepseek-web`, `free-deepseek` | ThreeDLlmProvider |
| Anything else                    | ThreeDLlmProvider (fallback) |

**Flow:** `AgentBridge.callLLM()` → `CLI.getProvider()` → `CLI.createProviderForModel()` →
`ProviderFactory.createProvider(modelId, config)`.

## Provider Capability Matrix

| Capability               | ThreeDLlmProvider | MistralProvider | DeepSeekProvider | OllamaProvider |
|--------------------------|-------------------|-----------------|------------------|----------------|
| Streaming                | ✅ Native SSE     | ❌              | ❌               | ❌             |
| Native tool_calls        | ✅ (SSE delta)    | ✅ (choice.message.tool_calls) | ❌ | ❌    |
| Text-based tool extraction | 8 strategies    | —               | —                | —              |
| Structured messages      | ✅                | ❌ (flat prompt) | ❌ (flat prompt) | ❌ (flat prompt) |
| Session management       | ✅ (proxy)        | ❌              | ❌               | ❌             |
| Context compaction       | ✅ (ProxySessionManager) | ❌      | ❌               | ❌             |
| Auth required            | None              | Bearer token    | Bearer token     | None           |
| Chat endpoint            | `/v1/chat/completions` | `/v1/chat/completions` | `/chat/completions` ⚠️ | `/api/generate` (legacy) |
| Health endpoint          | `/v1/models`      | `/v1/models`    | `/v1/models`     | `/api/tags`    |
| Error cooldown           | ✅ (5 min after 3×500) | ❌          | ❌               | ❌             |
| Provider-specific params  | `thinking_enabled`, `search_enabled` | `safe_prompt`, `random_seed` | — | — |

## Tool Call Extraction (ThreeDLlmProvider)

Because the 3D LLM proxy sometimes returns tool calls as raw text rather than
native `tool_calls` in the API response, `ThreeDLlmProvider` has 8 strategies
for extracting tools from text. These are inline parsing strategies in `ThreeDLlmProvider.callAPI()` / `processStreamResponse()`:

1. **`<file_action>` XML** — DeepSeek v4-pro format
2. **JSON in markdown code blocks** — ` ```json { "name": "..." } ``` `
3. **Balance-brace JSON** — Multi-line `{ "name": "..." }` objects
4. **`Calling:` format** — `Calling: tool_name\n{...}`
5. **Inline JSON with tool-revealing keys** — `{"path":..., "edits":[...]}` → infers `apply_edits`
6. **Bare XML tags** — `<search_files pattern="..." />`
7. **Prose tool mentions with attributes** — `<run_terminal command="..." />`
8. **Prose-prefixed JSON** — `Executed: {"name":"run_terminal",...}`

## Current Limitations & Migration Path

### Tool-Call Extraction Ownership

- **Tool-call text extraction is now owned by shared provider helpers in `src/providers/common/toolCalls.ts`.**
- **`normalizeNativeToolCalls()` converts provider-native `tool_calls` into the canonical internal shape.**
- **`extractToolCallFromText()` provides the canonical text fallback; provider-specific legacy fallback may remain for compatibility.**
- **`compactToolResult()` is provider-agnostic and reusable by all providers.**

### Stage 1 (completed): Provider-specific branches in AgentBridge

`AgentBridge` had scattered `if (provider === '3d-llm')` checks for:
- Context length override
- Tool injection into system prompt
- Fresh session flag
- Session manager URL selection

### Stage 2 (completed): Capabilities-driven adaptation

All 5 provider-string checks replaced with capability queries:

```typescript
// LLMProviderCapabilities interface in provider-types.ts:
interface LLMProviderCapabilities {
  streaming: boolean;
  nativeToolCalls: boolean;
  structuredMessages: boolean;
  sessionManagement: boolean;
  contextCompaction: boolean;   // NEW — separates compaction from session
  authRequired: boolean;
  maxContextLength: number;
  chatEndpoint: string;
  healthEndpoint?: string;
}

// AgentBridge reads capabilities instead of provider string:
this.config.model.contextLength = caps.maxContextLength;           // was: provider === '3d-llm'
if (!caps.nativeToolCalls) { inject tools into prompt }            // was: provider === '3d-llm'
this._needsFreshSession = caps.sessionManagement;                 // was: provider === '3d-llm'
if (caps.contextCompaction) { delegate to session manager }       // was: provider === '3d-llm'
```

A `getProviderCapabilities(provider)` helper in `provider-types.ts` resolves
capabilities by provider string without needing a live `LLMProvider` instance,
so `_lastProviderCapabilities` is available from AgentBridge constructor onward.

### Stage 3 (future): ProviderRegistry

When a 5th+ provider is added, replace the if-else chain in `ProviderFactory`
with a registry pattern:

```typescript
class ProviderRegistry {
  private factories = new Map<string, ProviderFactory>();

  register(id: string, factory: ProviderFactory): void;
  getProvider(modelId: string, config: ProviderConfig): LLMProviderAdapter;

  resolveProviderId(modelId: string): string;  // modelId → providerId mapping
}
```

## Adding a New Provider

To add a new provider (e.g., OpenAI, Anthropic, Groq):

1. **Implement `LLMProvider`** — `callAPI()`, `getProviderName()`, `validateConfiguration()`, optionally `isAvailable()` and `listModels()`
2. **Register in `ProviderFactory`** — add model ID patterns in `createProvider()` and a factory method
3. **Add config keys** — in `getProviderConfig()` (`cliIntegrationRefactored.ts:331`)
4. **Update `getAvailableProviders()`** in `ProviderFactory`
5. **No changes to `AgentBridge`** — the agent loop adapts automatically via capabilities (Stage 2+) or receives a `NullSessionManager` for session management

## Prompt Assembly (Layered)

Prompts are assembled from composable, configurable parts instead of repeating all rules/tools on every request.

- `PromptPart` / `PromptAssembler` (`src/agent/prompt/`) build prompt sections as layers.
- `AgentBridge.LLMAdapter.prepareMessages()` injects assembled system/first-user parts only when absent.
- Prompt parts are configurable via `AgentSettings.prompt` (core rules, project context, tool protocol).
- Core rules are sent once (system prompt or first user message); tool protocol only when tools change.
- After a tool round, `compactToolTurnMessages()` trims history to system, first user, the assistant tool-call request, and tool results, avoiding full prompt replays.

## Files Reference

| File | Purpose |
|------|---------|
| `src/types/provider-types.ts` | `LLMProvider`, `LLMRequest`, `LLMResponse`, `LLMMessage` interfaces |
| `src/providers/ProviderFactory.ts` | Factory: modelId → provider instantiation |
| `src/providers/3dllm/ThreeDLlmProvider.ts` | 3D LLM proxy provider (SSE streaming, 8-stage tool extraction) |
| `src/providers/mistral/MistralProvider.ts` | Mistral Cloud API provider |
| `src/providers/deepseek/DeepSeekProvider.ts` | DeepSeek Cloud API provider |
| `src/providers/ollama/OllamaProvider.ts` | Ollama local/cloud provider |
| `src/agent/SessionManager.ts` | `SessionManager` interface, `NullSessionManager`, `createSessionManager()` |
| `src/agent/ProxySessionManager.ts` | Session manager for 3D LLM proxy (health, compaction, reset) |
| `src/cliIntegrationRefactored.ts` | CLI class — dispatches `callLLM` to providers |
| `src/agent/AgentBridge.ts` | Main agent loop — consumes providers and session managers |
