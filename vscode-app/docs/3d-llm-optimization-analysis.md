# 3D LLM (FreeDeepseekAPI) Tool/Rules/Prompting Optimization Analysis

## 1. Current state

From `flow-diagram.md` and `provider-architecture.md`:

- `ThreeDLlmProvider.callAPI(LLMRequest)` receives the full `messages` array plus tools/session metadata on every agent-loop iteration.
- `sanitizeMessages()` runs before the POST, but the request still carries historical messages, tool definitions, and likely static instructions.
- After tool execution, `AgentBridge` loops back to `callAPI`, which can repeat static rules and large context on every round.
- `nativeToolCalls: true` for the 3D LLM is prompt-emulated by the proxy, so the provider also runs text-based extraction as a fallback.

## 2. Should rules be sent on every message?

No.

Recommended model:

- Put static instructions/rules in a single `system` message at the start of the conversation (or the request-level `system` field).
- Keep tool schemas in the `tools` request field instead of embedding them in message text.
- Avoid re-injecting full rules on each tool-loop iteration. Once the system message is in history, new rounds only need:
  1. assistant `tool_calls` message
  2. tool result message(s)
  3. optional latest user message
- If `ProxySessionManager` supports server-side sessions, use a session ID and keep static context server-side. Send only new turns.

## 3. Why send all content when DeepSeek only requested a tool call?

The current loop likely sends the full conversation + rules + all tool schemas again. Improvements:

- **Incremental continuation:** after the model emits `tool_calls`, execute them and append only:
  - assistant message with `tool_calls`
  - corresponding tool result message(s)
  Then call the API again. Do not re-add rules or tool definitions as message content.
- **Tool result compaction:** for `read_file`, `search_files`, and `list_directory`, return only relevant excerpts/matches/truncated results instead of entire file contents.
- **Dynamic tool filtering:** include only the tools relevant to the current turn, or cache the full static tool catalog outside the message list.

## 4. Prompt/rules organization

Split prompts into three layers:

1. **L1 Static behavior** — VSLFC workflow, output contract, path rules, tool-call syntax.
   - Sent once as `system`.
2. **L2 Active tool catalog** — allowed tools + JSON schemas.
   - Sent as `tools` array once per request; filter to relevant tools when possible.
3. **L3 Turn context** — current user prompt and recent tool results.
   - Sent as `messages` only.

## 5. Tool-call extraction ownership

- Use native `tool_calls` as the primary path when available.
- For text-based fallback, avoid forcing legacy text formats when native tool calling is supported.
- Make the system prompt conditional:
  - If `tools` are supplied and the model supports native calls: instruct the model to use the `tool_calls` field.
  - Otherwise, request one canonical compact JSON form such as `{"tool":"...","arguments":{...}}`.
- This can reduce the 8 fallback extraction strategies to 2–3 canonical strategies, improving parse reliability and reducing confusion.

## 6. Concrete implementation suggestions

- In `ThreeDLlmProvider`:
  - Build the request body with a single `system` message per session.
  - Keep `tools` in the request-level `tools` array, not inside message content.
  - Compact tool results before returning them to the conversation.
- In `AgentBridge`:
  - Preserve the original system message without duplicating rule blocks.
  - Before each `callAPI`, prune/truncate old messages beyond a token budget.
- Log per-request token counts to verify that static overhead stays flat across tool rounds rather than growing linearly.

## 7. Acceptance checks

- System/tool overhead should be roughly constant across multiple tool rounds.
- Tool-call fidelity should improve after removing conflicting legacy text-format instructions.
- `read_file` and `search_files` follow-up calls should show reduced token usage and lower latency.

## 8. Should strategies move to an upper aggregation layer?

Yes, but split by concern.

Move to `src/providers/common/` or `src/agent/tools/`:

- `normalizeToolCalls()` — turn native OpenAI/Mistral/Anthropic-style `tool_calls` into the internal canonical `ToolCall[]`.
- `extractToolCallFromText()` — canonical text fallback (single JSON object, markdown-fenced JSON, or `Calling:` block).
- Tool-result compaction helpers if they are provider-agnostic.

Keep in `ThreeDLlmProvider` only:

- Proxy-specific prompt emulation of native tools (FreeDeepseekAPI quirk).
- Proxy-specific sanitization and endpoint wiring.
- Legacy multi-strategy 1..8 parsing (deprecate in favor of canonical formats).

Recommended module layout:

- `src/providers/common/toolCalls.ts` — normalize + canonical text extractor + shared types.
- `src/providers/common/prompt.ts` — system/rules/tool-schema assembly.
- `src/providers/3dllm/ThreeDLlmProvider.ts` — imports common helpers, keeps proxy-specific behavior.

This lets DeepSeek, Mistral, Ollama, and future providers share the same extraction/normalization path while keeping provider quirks isolated.
