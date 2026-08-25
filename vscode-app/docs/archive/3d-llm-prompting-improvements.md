# 3D-LLM (DeepSeek Proxy) Tools / Rules / Prompting Improvements

Status: analysis & recommendations (not yet implemented)
Source docs reviewed: `flow-diagram.md`, `provider-architecture.md`, `refactor-plan.md`

## Context observed

- `ThreeDLlmProvider` owns request sanitization, timeout/abort, server-error cooldown,
  and text-based tool-call extraction (inline JSON, XML tags, prose mentions,
  `Calling:` blocks, and legacy formats).
- The proxy emulates `nativeToolCalls` via prompting; the provider still falls back to
  text extraction when structured `tool_calls` are absent.
- `flow-diagram.md` shows AgentBridge looping back to `callAPI` for each tool round.

## Open questions from the flow

### 1. Do we need to send rules on every message?

No. Rules should be split by lifetime:

- **Static rules** (tool-call syntax, output format, safety constraints) belong in the
  system prompt and should be sent **once** per conversation turn/request, not
  prepended to every user/assistant/tool message.
- **Per-round rules** (what to do with *this* tool result) should be a one-line
  instruction attached to the current tool-result message only.

Risk if we keep sending full rules every message: token bloat, the model treating
rules as repeated user content, and degraded attention to the actual task.

### 2. DeepSeek requested a tool call — why not send just the needed content?

Agreed. After a tool executes, the next API call should contain:

1. The original user request (or a compacted summary).
2. The assistant tool-call message.
3. A `tool`-role message with **only the requested content**, plus a short header
   such as `tool_id`, `tool_name`, and `status`.

It should **not** resend:
- the full static rules block,
- unrelated earlier tool results,
- the full tool schema list if it is unchanged (send schema once with the request, not with each result),
- huge outputs that were already summarized/truncated.

## Recommended improvements

### A. Prompt assembly tiers

Build messages as a three-tier structure:

```text
[system]      static rules + current tool schemas + conversation budget guidance
[history]     user/assistant turns (compacted as needed)
[current]      latest assistant tool_call + tool result delta
```

Rules live in `[system]` and are not repeated elsewhere.

### B. Tool-result deltas, not full history

For each tool round, send a delta package:

```json
{
  "tool_call_id": "...",
  "tool_name": "read_file",
  "status": "ok",
  "requested_content": "...truncated/summarized...",
  "omit_before_this": true
}
```

- `requested_content` should be the tool output with a max token cap (e.g. 8–16k
  tokens for code files). Truncate from the middle or use a line-range window, and
  append `[truncated N lines]`.
- When a result is too large to include fully, send a summary plus a fingerprint
  (path + range + hash) so the model can request a specific slice next.

### C. Rule compression

- Keep the system prompt under a hard budget (e.g. 2–4k tokens).
- Emit the tool-call syntax spec only when the conversation may need tools; when no
  tools are enabled, omit that block entirely.
- Use a short JSON/XML template example instead of prose paragraphs for tool-call
  format instructions — the model follows examples more reliably than prose.

### D. Request sanitization (provider already owns this)

Extend sanitization to also:

- detect duplicate system messages and collapse them,
- strip repeated rule blocks from non-system messages,
- replace consecutive same-role messages with merged content (many proxies reject
  or mishandle consecutive roles),
- enforce `max_tokens` on tool results before they enter the request.

### E. Tool-call extraction tightening

Text extraction has many strategies (1..8). To reduce false positives:

- score each candidate and accept only the highest-confidence match per assistant turn,
- prefer structured markers (`<tool_call>`, `Calling:`, inline JSON) over prose-mention
  heuristics,
- keep extraction **provider-side** (as already decided) so the orchestration layer
  sees only normalized tool calls,
- log `strategy_id` and `confidence` for every extraction so regressions are visible.

### F. Context compaction

When history exceeds a budget:

- summarize older tool results instead of dropping the user request,
- always keep the original user ask verbatim,
- keep only the most recent N tool calls fully, summarize the rest.

## Suggested next milestones

1. Instrument `ThreeDLlmProvider` to log per-request token counts by message role.
2. Move static rules to a single system block; remove per-message rule duplication.
3. Implement tool-result delta packaging with truncation.
4. Add sanitizer rules for consecutive-role merge and duplicate-system collapse.
5. Add extraction confidence logging (`strategy_id`, `confidence`).

## Assumptions

- The proxy is prompt-emulated tool calling (per `flow-diagram.md` notes), so
  instructions/format examples have more impact than native API schemas.
- The orchestration layer (AgentBridge/ToolPipeline) passes full history today;
  the exact call shape should be verified in `ThreeDLlmProvider` before
  implementing delta packaging.
