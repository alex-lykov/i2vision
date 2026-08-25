# DeepSeek Streaming Fix Plan

## Problem

The extension UI shows `Processing...` but no streamed tokens, even though the
FreeDeepseekAPI proxy supports OpenAI-compatible SSE streaming and DeepSeek's own
chat UI streams.

## Root cause

The actual LLM path used by AgentBridge is the provider layer, specifically
`vscode-app/src/providers/3dllm/ThreeDLlmProvider.ts`. When the request has
`stream: true`, `ThreeDLlmProvider.callAPI()` reads the entire SSE response
internally into a single `LLMResponse`, then returns that object.

AgentBridge checks the returned value with
`typeof (rawResponse as any)[Symbol.asyncIterator] === 'function'`.
Because `callAPI()` returns a plain Promise/object, AgentBridge takes the
non-streaming branch. The UI therefore waits for the entire completion and
shows only `Processing...` until the final response arrives.

This means the proxy was not the problem. `curl -N` confirms the running
FreeDeepseekAPI endpoint returns:

- `Content-Type: text/event-stream`
- `data: {"choices":[{"delta":{"content":"..."}}]}`
- `data: {"choices":[{"delta":{},"finish_reason":"stop"}]}`
- `data: [DONE]`

## Fix steps

1. Keep `ThreeDLlmProvider` capability `streaming: true`.
2. Change `ThreeDLlmProvider.callAPI()` to return
   `Promise<LLMResponse | AsyncGenerator<any>>`.
3. Add `stream3DLlmResponseAsync()` that returns an `AsyncGenerator<any>`
   and yields incremental `{ text: delta.content, done: false }` chunks while
   reading the SSE stream.
4. At `[DONE]` / `finish_reason`, yield a final
   `{ text: '', done: true, tool_calls: [], tokenUsage? }` chunk.
5. Keep the existing non-streaming JSON path unchanged for `stream: false`.
6. Ensure AgentBridge sees the async iterator and renders live token updates.

## Verification

- Send a prompt using the 3D LLM provider.
- Expected: `Processing...` card is followed by live streamed text.
- `npm run compile` in `vscode-app` must pass.
