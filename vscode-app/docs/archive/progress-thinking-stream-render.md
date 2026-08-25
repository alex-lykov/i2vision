# Thinking Stream Render Progress

## Problem
`ThreeDLlmProvider` captures reasoning/thinking stream into `LLMResponse.reasoning`, but the live webview output card never rendered it.

## Root Cause Summary

The live Agent tab UI is rendered by `resources/agent-tab.html`, not `resources/webview.js`.

Three independent breaks prevent the thinking stream from appearing:

1. `AgentBridge.ts` does not yield `LLMResponse.reasoning` as a stream event.
2. `AgentTabManager.ts` sends any `reasoning` chunk using `command: 'thinking'`, which the live webview treats as a transient indicator, not a reasoning card.
3. `agent-tab.html` final `assistant_response` handler ignores `message.reasoning`.

## Next Implementation Steps

1. `src/agent/AgentBridge.ts`
   - After `callLLM()` returns the LLM response, add a missing yield:

   ```ts
   if (llmResponse.reasoning) {
     yield {
       type: 'reasoning',
       reasoning: llmResponse.reasoning,
       timestamp: Date.now(),
     };
   }
   ```

2. `src/agent/AgentTabManager.ts`
   - In `case 'reasoning':`, send `command: 'reasoning'`, not `command: 'thinking'`:

   ```ts
   case 'reasoning':
     this.sendToWebview({
       command: 'reasoning',
       reasoning: chunk.reasoning,
       timestamp: chunk.timestamp,
     });
     accumulatedReasoning += chunk.reasoning;
     break;
   ```

   - Keep existing `case 'thinking':` for the transient spinner/indicator.
   - Verify final assistant message and webview `assistant_response` still include `accumulatedReasoning.trim() || undefined`.

3. `resources/agent-tab.html`
   - Update `case 'assistant_response':` so that when `message.reasoning` is present, a Reasoning section is rendered above the response text.
   - Reuse `appendReasoningCard(message.reasoning)` or add an equivalent persistent section inside the final card.
   - Keep existing `case 'reasoning': appendReasoningCard(message.reasoning)` for the live stream.

4. Non-production paths
   - `resources/webview.js` and `src/webview/components/AgentOutputCard.ts` are not part of this live output-card path. Avoid further patches there for this issue.

## Verification

1. Enable the footer Thinking checkbox.
2. Send a prompt that produces reasoning.
3. Expected:
   - Live reasoning card appears during the stream.
   - Final output card includes a Reasoning section above the response text.
4. Run `npm run compile` and reload the extension host/webview.
