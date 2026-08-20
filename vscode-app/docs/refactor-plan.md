# Refactor Plan for VS Code Extension

> **Status:** Superseded. Tool-call extraction now lives in
> `src/providers/3dllm/ThreeDLlmProvider.ts` as an inline 8-strategy parser.
> The old `cliIntegrationRefactored.ts` fallback path described below is no
> longer the implementation location. See `flow-diagram.md` and
> `provider-architecture.md` for the current flow.

## Goal
Restore robust tool-call extraction while keeping the new thinking/streaming features.

## Progress Points (Milestones)
1. **Add fallback parser** – Implement provider-side text tool-call extraction. Current implementation: inline strategies 1..8 in `ThreeDLlmProvider`.
2. **Update response handling** – Normalize and merge extracted tool calls before returning `LLMResponse`.
3. **Unit tests** – Add tests covering:
   - Provider returns empty `tool_calls` but raw text contains a JSON tool call.
   - Provider returns a valid `tool_calls` array (no fallback needed).
4. **Manual verification** – Run a quick chat that issues a `read_file` request and confirm the log shows `Extracted tool calls`.
5. **Documentation** – Update docs to reflect the new flow (see `flow-diagram.md`).
6. **Code review & merge** – Ensure no regression to streaming or thinking features.

## Timeline (example)
- **Day 1** – Implement fallback parser and response merge.
- **Day 2** – Write unit tests and run CI.
- **Day 3** – Manual verification and documentation update.
- **Day 4** – Review, address feedback, and merge.
