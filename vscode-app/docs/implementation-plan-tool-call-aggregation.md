# Implementation Plan: Provider-Agnostic Tool-Call Aggregation

## Decision

Use a **new file** rather than `refactor-plan.md`.

`refactor-plan.md` is already marked superseded and currently describes the earlier
provider-local fallback-parser work. Mixing this larger extraction into that file
would create stale, conflicting milestones. This new plan becomes the canonical
refactor document; `refactor-plan.md` should only link here.

## Goal

Move provider-agnostic tool-call extraction/normalization out of
`ThreeDLlmProvider.ts` into shared helpers under `src/providers/common/`, so
DeepSeek, Mistral, Ollama, and future providers reuse the same canonical path.

## Target layout

```
vscode-app/src/providers/common/
  toolCalls.ts        # canonical types + normalize + text extraction
  prompt.ts           # optional later: system/rules/tool-catalog assembly
  toolCalls.test.ts   # pure unit tests
```

Existing provider files remain responsible only for transport, endpoint quirks,
streaming, sanitization, and error handling.

## Phases

### Phase 1: Create shared canonical types and pure helpers

New: `vscode-app/src/providers/common/toolCalls.ts`

Exports:

- `CanonicalToolCall`
  ```ts
  interface CanonicalToolCall {
    id?: string;
    name: string;
    arguments: Record<string, unknown>;
  }
  ```
- `normalizeNativeToolCalls(raw: unknown): CanonicalToolCall[]`
  - Handles OpenAI-style `{ id, function: { name, arguments } }`.
  - Handles Mistral-style `{ id, function: { name, arguments } }`.
  - Parses string arguments safely via `JSON.parse` and falls back to `{}`.
- `extractToolCallFromText(content: string): CanonicalToolCall[]`
  - Strategy A: single JSON object `{"name":"...","arguments":{...}}`
  - Strategy B: fenced JSON ` ```json ... ``` `
  - Strategy C: `Calling: <name>` followed by JSON arguments block
  - Returns `[]` if no canonical match.
- `compactToolResult(content: string, opts?): string`
  - Truncate with stable markers; provider-agnostic.

### Phase 2: Add unit tests before provider refactor

New: `vscode-app/src/providers/common/toolCalls.test.ts`

Cases:

- native OpenAI `tool_calls` normalize correctly.
- native Mistral `tool_calls` normalize correctly.
- malformed `arguments` becomes `{}` rather than throwing.
- plain JSON object in text is extracted.
- fenced JSON in text is extracted.
- `Calling:` block is extracted.
- prose without tool call returns `[]`.
- `compactToolResult` truncates large content.

### Phase 3: Refactor `ThreeDLlmProvider`

File: `vscode-app/src/providers/3dllm/ThreeDLlmProvider.ts`

Changes:

1. Import `normalizeNativeToolCalls`, `extractToolCallFromText`,
   `CanonicalToolCall` from `../common/toolCalls`.
2. Replace the native `tool_calls` normalization path with
   `normalizeNativeToolCalls(responseChoices)`.
3. Replace the default text fallback with `extractToolCallFromText(content)`.
4. Keep legacy multi-strategy parsing, but move it behind a provider-local flag
   or `legacyFallback` so new providers do not inherit it.
5. Keep proxy-specific sanitization, request assembly, SSE parsing, timeout,
   cooldown, and error classification in this file.

Acceptance:

- `ThreeDLlmProvider` behavior is unchanged for current FreeDeepseekAPI calls.
- Log `Extracted tool calls` still appears for text fallback.
- No new imports from the agent layer into `providers/common`.

### Phase 4: Reuse in other providers

- `MistralProvider.ts`: replace local native normalization with
  `normalizeNativeToolCalls`.
- `DeepSeekProvider.ts` and `OllamaProvider.ts`: if/when text tool-call support
  is enabled, call `extractToolCallFromText` directly instead of duplicating
  parsing logic.
- `ProviderFactory.ts` remains unchanged; shared helpers are plain modules.

### Phase 5: Optional common prompt assembly

New: `vscode-app/src/providers/common/prompt.ts`

Exports:

- `buildSystemPrompt(baseRules: string, providerHints?: string): string`
- `buildToolCatalog(tools: ToolDefinition[]): ToolDefinition[]`
- `buildIncrementalMessages(history, toolResults): ChatMessage[]`

Purpose:

- Keep static VSLFC rules in one system message per session.
- Keep tool schemas in the request-level `tools` field, not in message content.
- Avoid re-sending rules or full tool catalogs on every tool-loop iteration.

This phase is separate because it affects request construction across providers
and requires more end-to-end validation.

### Phase 6: Documentation updates

- Update `flow-diagram.md` to show shared normalization/extraction from
  `src/providers/common/toolCalls.ts`.
- Update `provider-architecture.md` Tool-Call Extraction Ownership section to
  state that ownership is now shared, with provider-specific legacy fallback
  optional.
- Add a link in `refactor-plan.md` to this new plan.

## Validation

Run source compilation only:

```powershell
.\gradlew compileKotlin
```

No Gradle/Kotlin changes are expected; this plan is TypeScript-only.
Use `npm`/extension test commands only if explicitly requested for tests.

## Out of scope

- Changing the FreeDeepseekAPI proxy itself.
- Rewriting the full agent loop or ToolPipeline.
- Removing all legacy extraction strategies in the same PR; they are isolated
  and deprecated incrementally.
