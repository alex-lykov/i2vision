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

`AgentBridge` always calls the session manager methods — `NullSessionManager`
is a safe no-op, so no conditional checks are needed in the agent loop.

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
| Auth required            | None              | Bearer token    | Bearer token     | None           |
| Chat endpoint            | `/v1/chat/completions` | `/v1/chat/completions` | `/chat/completions` ⚠️ | `/api/generate` (legacy) |
| Health endpoint          | `/v1/models`      | `/v1/models`    | `/v1/models`     | `/api/tags`    |
| Error cooldown           | ✅ (5 min after 3×500) | ❌          | ❌               | ❌             |
| Provider-specific params  | `thinking_enabled`, `search_enabled` | `safe_prompt`, `random_seed` | — | — |

## Tool Call Extraction (ThreeDLlmProvider)

Because the 3D LLM proxy sometimes returns tool calls as raw text rather than
native `tool_calls` in the API response, `ThreeDLlmProvider` has 8 strategies
for extracting tools from text. These live in `extractToolCallsFromText()`:

1. **`<file_action>` XML** — DeepSeek v4-pro format
2. **JSON in markdown code blocks** — ` ```json { "name": "..." } ``` `
3. **Balance-brace JSON** — Multi-line `{ "name": "..." }` objects
4. **`Calling:` format** — `Calling: tool_name\n{...}`
5. **Inline JSON with tool-revealing keys** — `{"path":..., "edits":[...]}` → infers `apply_edits`
6. **Bare XML tags** — `<search_files pattern="..." />`
7. **Prose tool mentions with attributes** — `<run_terminal command="..." />`
8. **Prose-prefixed JSON** — `Executed: {"name":"run_terminal",...}`

## Current Limitations & Migration Path

### Stage 1 (current): Provider-specific branches in AgentBridge

`AgentBridge` has scattered `if (provider === '3d-llm')` checks for:
- Context length override (line 438)
- Tool injection into system prompt (line 858)
- Fresh session flag (line 324)
- Session manager URL selection (lines 300–307)

### Stage 2 (planned): Capabilities-driven adaptation

Replace hardcoded provider checks with capability queries:

```typescript
// Add to LLMProvider interface:
interface LLMProviderCapabilities {
  streaming: boolean;
  nativeToolCalls: boolean;
  structuredMessages: boolean;
  sessionManagement: boolean;
  authRequired: boolean;
  maxContextLength: number;
  chatEndpoint: string;
  healthEndpoint?: string;
}

// AgentBridge reads capabilities instead of provider string:
this.config.model.contextLength = provider.getCapabilities().maxContextLength;
if (!provider.getCapabilities().nativeToolCalls) {
  contextEnhancedPrompt += formatToolsForSystemPrompt(tools);
}
```

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
