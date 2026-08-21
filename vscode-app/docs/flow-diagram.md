# 3D LLM Tool-Call and Extraction Flow

This diagram reflects the current implementation in `src/providers/3dllm/ThreeDLlmProvider.ts`.
The old CLI-based extraction path (`cliIntegration.ts`) has been replaced by provider-level
multi-strategy parsing.

```mermaid
sequenceDiagram
    participant User
    participant VSCodeUI
    participant AgentBridge
    participant ProviderFactory
    participant ThreeDLlmProvider
    participant ProxySessionManager
    participant ThreeDLLM as 3D LLM Proxy (FreeDeepseekAPI)
    participant ToolPipeline
    participant ToolRegistry
    participant Tool

    User -> VSCodeUI: send prompt
    VSCodeUI -> AgentBridge: runAgentLoop(userPrompt)
    AgentBridge -> AgentBridge: dispatch state transitions / loop detection

    AgentBridge -> ProviderFactory: createProvider(modelId)
    ProviderFactory --> AgentBridge: LLMProvider

    AgentBridge -> ThreeDLlmProvider: callAPI(LLMRequest)
    note right of ThreeDLlmProvider: request carries messages, tools,<br/>stream, thinking/search, user/session

    ThreeDLlmProvider -> ThreeDLlmProvider: sanitizeMessages()
    ThreeDLlmProvider -> ThreeDLLM: POST /v1/chat/completions (stream)

    loop SSE stream
        ThreeDLLM --> ThreeDLlmProvider: streamed chunk
        ThreeDLlmProvider -> ThreeDLlmProvider: accumulate content / usage
    end

    alt HTTP OK
        ThreeDLlmProvider -> ThreeDLlmProvider: processStreamResponse()

        alt native structured tool_calls present
            ThreeDLlmProvider -> ThreeDLlmProvider: normalize native tool_calls
        else text-based tool call
            ThreeDLlmProvider -> ThreeDLlmProvider: run multi-strategy extraction (strategies 1..8)
            ThreeDLlmProvider -> ThreeDLlmProvider: normalize extracted tool calls
        end

        ThreeDLlmProvider --> AgentBridge: LLMResponse { content, toolCalls }
    else HTTP / network failure
        ThreeDLlmProvider -> ThreeDLlmProvider: recordServerError() / classify error
        ThreeDLlmProvider --> AgentBridge: error response
    end

    alt toolCalls present
        AgentBridge -> ToolPipeline: executeToolCalls(toolCalls)
        ToolPipeline -> ToolRegistry: executeTool(tool)
        ToolRegistry -> Tool: invoke handler(arguments)
        Tool --> ToolRegistry: result (or error)
        ToolRegistry --> ToolPipeline: toolResult
        ToolPipeline --> AgentBridge: toolResults

        AgentBridge -> VSCodeUI: displayToolResults(toolResults)
        note right of AgentBridge: if more tool rounds remain,<br/>AgentBridge loops back to callAPI
    else no tool calls
        AgentBridge -> VSCodeUI: displayMessage(content)
    end

    VSCodeUI -> User: show assistant reply and tool output
```

## Notes

- `nativeToolCalls: true` for 3D LLM is prompt-emulated by the proxy; the provider
  still runs text-based extraction as a fallback when structured `tool_calls` are absent.
- The provider performs request sanitization before sending messages to the proxy.
- Tool-call extraction now uses shared helpers in `src/providers/common/toolCalls.ts`:
  native `tool_calls` normalization, canonical text fallback extraction, and tool-result compaction.
  Legacy proxy-specific fallback remains for 3D LLM compatibility.
- Prompt assembly is layered via `PromptAssembler` / `PromptPart` before messages reach the
  provider. Core rules, project context, and tool protocol are injected only when needed,
  avoiding rule/tool-catalog duplication on every request.
- Timeout / abort and server-error cooldown are handled inside `ThreeDLlmProvider`.
