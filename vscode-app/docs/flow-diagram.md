# Tool‑Call Flow – Sequence Diagram

title Tool‑Call Flow (VS Code Extension)

participant User
participant VSCodeUI
participant AgentBridge
participant CLI
participant Provider
participant ToolPipeline
participant ToolRegistry
participant Tool
participant Result

note right of CLI: • If `tool_calls` is empty, raw `content` is parsed by `extractToolCallsFromText` (fallback parser).\n• All tool calls are normalized to the same shape.

note right of ToolPipeline: • Handles rate‑limiting, retries, and execution stats.\n• May queue calls if concurrency limits are reached.


== User initiates a chat ==
User -> VSCodeUI: type prompt & press Enter
VSCodeUI -> AgentBridge: receiveUserInput(userInput)

== AgentBridge prepares request ==
AgentBridge -> CLI: callLLM(messages, tools)
CLI -> Provider: HTTP POST /chat/completions
Provider -> Provider: generate response (may include tool_calls)

== Provider returns response ==
Provider --> CLI: { content, tool_calls, usage }

== CLI handles response ==
alt native tool_calls present
CLI -> CLI: normalizeToolCalls(tool_calls)
else
CLI -> CLI: extractToolCallsFromText(content)
CLI -> CLI: normalizeToolCalls(extracted)
end
CLI --> AgentBridge: LLMResponse { content, toolCalls }

== AgentBridge processes tool calls ==
AgentBridge -> ToolPipeline: executeToolCalls(toolCalls)
ToolPipeline -> ToolRegistry: executeTool(tool)
ToolRegistry -> Tool: invoke handler(arguments)
Tool --> ToolRegistry: result (or error)
ToolRegistry --> ToolPipeline: toolResult
ToolPipeline --> AgentBridge: toolResults

== AgentBridge sends back to UI ==
AgentBridge -> VSCodeUI: displayToolResults(toolResults)
AgentBridge -> VSCodeUI: displayMessage(content)

== Final UI update ==
VSCodeUI -> User: show assistant reply & tool output

