# Agent Session Sequence Flow

This document describes the complete lifecycle of an i2-Vision agent session, from tab creation through user input processing to persistence and cleanup.

## Legend

| Symbol | Meaning |
|--------|---------|
| `->`  | Synchronous call |
| `-->>` | Return value |
| `->>` | Asynchronous/fire-and-forget |
| `activate` / `deactivate` | Object lifetime scope |

---

## 1. Tab Creation Flow

```mermaid
sequenceDiagram
    autonumber
    participant User
    participant VSCode as VSCode Extension
    participant ATM as AgentTabManager
    participant LAP as LocalAgentProvider
    participant LIA as LocalI2VisionAgent
    participant AB as AgentBridge
    participant CM as ContextMeter
    participant CHM as ConversationHistoryManager
    participant WebView

    User->>VSCode: Command: New Vision/Structure/Logic/Flow/Coding Agent
    VSCode->>ATM: createTab(layer, conversationId?)
    activate ATM

    ATM->>LAP: createAgent(layerEnum)
    activate LAP
    LAP->>LIA: new LocalI2VisionAgent(config)
    activate LIA
    LIA-->>LAP: agent instance
    deactivate LIA
    LAP-->>ATM: agent
    deactivate LAP

    ATM->>CM: configure(provider, model, contextLength)
    activate CM
    Note over CM: Set provider-specific limits<br/>(Ollama: unlimited, 3D LLM: 100 msg / 2h TTL)
    CM-->>ATM: ok
    deactivate CM

    ATM->>ATM: tabState = { tabId, agent, layer, history[],<br/>accumulatedToolCalls[], isActive, workspaceRoot, contextMeter }
    ATM->>ATM: tabs.set(tabId, tabState)

    ATM->>AB: new AgentBridge(config, outputChannel, extensionPath, workspaceRoot, settings)
    activate AB
    AB->>AB: initialize()
    Note over AB: Load tool registry, state machine,<br/>domain detector, terminal manager
    AB-->>ATM: bridge ready
    deactivate AB

    alt conversationId provided (resume)
        ATM->>CHM: load(tabId)
        CHM-->>ATM: SavedConversation
        ATM->>ATM: tabState.history = messages
        ATM->>ATM: tabState.sessionState = sessionState
        ATM->>ATM: sendLoadedConversation(tabId)
        ATM->>WebView: context_title + restored_message(s)
        ATM->>WebView: token_usage (estimated from messages)
    else new tab
        ATM->>WebView: empty agent tab
    end

    ATM-->>VSCode: tabId
    deactivate ATM
```

---

## 2. User Input Processing Flow

```mermaid
sequenceDiagram
    autonumber
    participant User
    participant WebView
    participant ATM as AgentTabManager
    participant AB as AgentBridge
    participant SM as StateMachine
    participant DD as DomainDetector
    participant LLM as LLM Provider<br/>(Ollama / DeepSeek / 3D LLM)
    participant TR as ToolRegistry
    participant CM as ContextMeter
    participant CHM as ConversationHistoryManager

    User->>WebView: Type message + Send
    WebView->>ATM: user_input(content, currentFile)
    activate ATM

    Note over ATM: isProcessing = true
    ATM->>ATM: history.push(userMessage)
    ATM->>WebView: user_message

    ATM->>AB: processStreaming(userInput, currentFile, history, sessionState)
    activate AB
    AB->>AB: restoreSessionState(sessionState)
    Note over AB: Restore visitedPaths, searchCache,<br/>domainResolution, toolFilter

    AB->>AB: buildSystemPrompt(templateVars)
    AB->>DD: resolveDomain(userInput)
    DD-->>AB: DomainResolution
    AB->>AB: injectDomainHint(systemPrompt)

    AB->>AB: detectTaskType(userInput)
    AB->>AB: loadEagerContext(contextProfile, currentFile)
    AB->>AB: injectContextIntoPrompt(systemPrompt, loadedContext)

    AB->>AB: buildMessages(systemPrompt, history, userInput)
    Note over AB: [system, history..., user]

    AB->>AB: executeAgentLoop(messages, options, history)
    activate AB

    loop While iteration < maxIterations
        AB->>SM: dispatch(USER_INPUT)
        AB->>SM: dispatch(INTENT_CLASSIFIED)
        AB->>SM: dispatch(PLAN_GENERATED)
        AB->>SM: dispatch(CONSTRAINTS_CHECKED)

        AB->>LLM: callLLM(modelId, messages, tools)
        activate LLM
        LLM-->>AB: LLMResponse { content, toolCalls }
        deactivate LLM

        alt Tool calls present
            AB->>TR: execute(toolName, args)
            activate TR
            TR-->>AB: ToolResult
            deactivate TR
            AB->>WebView: tool_start / tool_complete (via ATM relay)
            AB->>SM: dispatch(TOOL_EXECUTED)
            AB->>AB: messages.push(tool_result)
        else No tools (synthesis)
            AB->>SM: dispatch(SYNTHESIS_STARTED)
            AB->>AB: finalResponse = content
            AB->>SM: dispatch(OUTPUT_VERIFIED)
            break
        end
    end

    AB->>AB: getSessionState()
    AB-->>AB: AgentSessionState
    AB-->>ATM: yield done { tokenUsage }
    deactivate AB

    ATM->>CM: recordTokenUsage(prompt, completion, total)
    ATM->>CM: recordLatency(durationMs)
    ATM->>CM: getUsageSummary()
    CM-->>ATM: ContextUsageSummary

    alt warnings present
        ATM->>ATM: log warnings
    end

    ATM->>WebView: token_usage (legacy)
    ATM->>WebView: context_meter_update { summary }

    ATM->>ATM: history.push(assistantMessage)
    ATM->>ATM: tabState.sessionState = bridge.getSessionState()

    alt autoSave enabled
        ATM->>CHM: save(tabId, messages, layer, sessionState)
    end

    ATM->>WebView: assistant_response
    Note over ATM: isProcessing = false
    deactivate ATM
```

---

## 3. Context Meter Data Flow

```mermaid
sequenceDiagram
    autonumber
    participant WebView
    participant ATM as AgentTabManager
    participant CM as ContextMeter
    participant CLI as CLI (llm-client)

    Note over CM: Per-tab singleton, configured on tab creation

    User->>WebView: Send message
    WebView->>ATM: user_input
    activate ATM

    ATM->>CLI: callLLM(model, messages, stream=true)
    CLI->>CLI: Provider-specific HTTP call
    CLI-->>ATM: AsyncGenerator<LLMChunk>

    loop Each chunk
        ATM->>WebView: streaming_text / reasoning / tool_start / tool_complete
    end

    ATM->>CLI: chunk done { tokenUsage }
    CLI-->>ATM: tokenUsage: { prompt, completion, total }

    ATM->>CM: recordTokenUsage(tokenUsage)
    Note over CM: Update internal state,<br/>push to tokenHistory[]

    ATM->>CM: recordLatency(Date.now() - startTime)
    Note over CM: Rolling avg, min/max tracking

    ATM->>CM: getUsageSummary()
    activate CM
    CM->>CM: Calculate percentages
    CM->>CM: Determine status (healthy/warning/critical/exhausted)
    CM->>CM: Build warnings array
    CM-->>ATM: ContextUsageSummary
    deactivate CM

    ATM->>WebView: context_meter_update { summary }
    Note over WebView: Render: status bar, token bar,<br/>message bar, TTL bar, latency, warnings
    deactivate ATM
```

---

## 4. Provider Change Flow

```mermaid
sequenceDiagram
    autonumber
    participant User
    participant WebView
    participant ATM as AgentTabManager
    participant LAP as LocalAgentProvider
    participant CM as ContextMeter
    participant AB as AgentBridge

    User->>WebView: Select new provider from dropdown
    WebView->>ATM: change_provider(provider)
    activate ATM

    ATM->>LAP: getConfig(layer)
    LAP-->>ATM: AgentConfig

    ATM->>ATM: config.model.provider = provider
    ATM->>ATM: config.model.id = defaultModel(provider)
    Note over ATM: ollama -> llama3.2:3b<br/>deepseek -> deepseek-chat<br/>3d-llm -> deepseek-web-v3

    ATM->>LAP: updateConfig(layer, { provider, model })

    ATM->>CM: configure(provider, model, contextLength)
    activate CM
    Note over CM: Reset token history, session metrics<br/>Load new provider limits
    CM-->>ATM: ok
    deactivate CM

    ATM->>AB: Re-initialized implicitly via config
    Note over AB: Next processStreaming will use new provider

    ATM->>WebView: provider_changed { provider, model }
    ATM->>WebView: models_list { models, currentModel, currentProvider }
    deactivate ATM
```

---

## 5. Tab Close / Cleanup Flow

```mermaid
sequenceDiagram
    autonumber
    participant User
    participant VSCode as VSCode Extension
    participant ATM as AgentTabManager
    participant CHM as ConversationHistoryManager
    participant LIA as LocalI2VisionAgent
    participant AB as AgentBridge
    participant WebView

    User->>VSCode: Close agent tab / Dispose
    VSCode->>ATM: closeTab(tabId)
    activate ATM

    ATM->>ATM: tab = tabs.get(tabId)

    alt history.length > 0
        ATM->>CHM: save(tabId, history, layer, sessionState)
        CHM-->>ATM: persisted to .vision-ai/history/{tabId}.json
    end

    ATM->>LIA: dispose()
    activate LIA
    LIA->>LIA: Cancel pending requests
    LIA->>AB: dispose()
    deactivate LIA

    ATM->>ATM: tabs.delete(tabId)
    ATM->>WebView: tab closed (implicit)
    deactivate ATM

    alt dispose() called (extension shutdown)
        VSCode->>ATM: dispose()
        activate ATM
        ATM->>ATM: stopAutoSaveTimer()
        loop For each tab
            ATM->>CHM: save(tabId, history, layer, sessionState)
            ATM->>LIA: dispose()
        end
        ATM->>AB: dispose() if currentAgentBridge
        ATM->>WebView: dispose panel
        deactivate ATM
    end
```

---

## 6. Streaming Chunk Types

```mermaid
graph LR
    AB[AgentBridge<br/>executeAgentLoop]
    ATM[AgentTabManager<br/>processUserInput]
    WV[WebView

    AB -->|AgentChunk| ATM
    ATM -->|WebView message| WV

    subgraph AgentChunk Types
        C1["reasoning: string"]
        C2["tool_call_started: { toolName, args }"]
        C3["tool_call_completed: { toolName, result }"]
        C4["text: string"]
        C5["done: { tokenUsage? }"]
        C6["error: string"]
        C7["thinking: string"]
    end

    subgraph WebView Commands
        W1["reasoning"]
        W2["tool_start"]
        W3["tool_complete"]
        W4["streaming_text"]
        W5["token_usage"]
        W6["context_meter_update"]
        W7["error"]
        W8["thinking"]
        W9["assistant_response"]
    end

    C1 --> W1
    C2 --> W2
    C3 --> W3
    C4 --> W4
    C5 --> W5
    C5 --> W6
    C6 --> W7
    C7 --> W8
```

---

## 7. Data Persistence Format

```mermaid
classDiagram
    class SavedConversation {
        +string id
        +string workspace
        +string layer
        +ChatMessage[] messages
        +number createdAt
        +number updatedAt
        +string contextTitle
        +AgentSessionState sessionState
    }

    class ChatMessage {
        +"user" | "assistant" role
        +string content
        +ToolCall[] toolCalls
        +number timestamp
    }

    class AgentSessionState {
        +string[] visitedPaths
        +SearchCacheEntry[] searchCache
        +ResolvedDomain resolvedDomain
        +string workingDirectory
        +"all" | "action_only" toolFilter
        +boolean forceActionMode
        +number failedSearchCount
        +string lastSearchPattern
    }

    SavedConversation "1" --> "0..*" ChatMessage
    SavedConversation "1" --> "0..1" AgentSessionState
```

---

## 8. State Transitions

```mermaid
stateDiagram-v2
    [*] --> Idle: createTab()

    Idle --> Processing: processUserInput()
    Processing --> Idle: response done / error
    Processing --> Idle: stopAgent() / cancel

    Idle --> Resumed: loadConversationData()
    Resumed --> Idle: sendLoadedConversation()

    Idle --> ConfigChanged: changeProvider()
    ConfigChanged --> Idle: provider_changed event

    Idle --> Closing: closeTab()
    Closing --> [*]: dispose()

    state Processing {
        [*] --> Intent: iteration 1
        Intent --> Plan: classify intent
        Plan --> Constraints: generate plan
        Constraints --> Sequence: check constraints
        Sequence --> Execute: tool calls ready
        Execute --> Verify: tools executed
        Verify --> Output: synthesis
        Output --> [*]: final response

        Execute --> Sequence: more tools needed
        Verify --> Constraints: build errors
    }

    note right of Processing
        ContextMeter tracks:
        - token usage per iteration
        - latency per call
        - retry count
        - message count
    end note
```

---

## 9. Provider-Specific Context Limits

```mermaid
graph LR
    subgraph Ollama
        O1["Max tokens: configurable"]
        O2["Messages: unlimited"]
        O3["TTL: unlimited"]
    end

    subgraph DeepSeek
        D1["Max tokens: 64K"]
        D2["Messages: unlimited"]
        D3["TTL: unlimited"]
    end

    subgraph "3D LLM"
        T1["Max tokens: 64K"]
        T2["Messages: 100"]
        T3["TTL: 2 hours"]
        T4["Max prompt: 80K chars"]
    end
```

| Provider | Context Meter Shows |
|----------|---------------------|
| Ollama | Tokens + Latency only |
| DeepSeek | Tokens + Latency only |
| 3D LLM | Tokens + Messages + TTL + Latency |

---

## File Responsibilities

| File | Responsibility |
|------|---------------|
| `AgentTabManager.ts` | Tab lifecycle, user input routing, webview coordination, proxy health polling |
| `AgentBridge.ts` | LLM calls, tool execution, state machine, session state, retry logic |
| `LocalI2VisionAgent.ts` | In-process agent instance, request processing |
| `LocalAgentProvider.ts` | Config loading, agent factory |
| `ConversationHistoryManager.ts` | File-based persistence (.vision-ai/history/*.json) |
| `ContextMeter.ts` | Token/session/latency tracking, limit warnings |
| `SessionManager.ts` | Interface for provider-specific session lifecycle |
| `ProxySessionManager.ts` | 3D LLM session sync, proactive reset, health checks |
| `ToolResultCompressor.ts` | Proactive compression of tool outputs before LLM send |
| `cliIntegration.ts` | HTTP LLM clients (Ollama, DeepSeek, 3D LLM) |
| `webview.js` | WebView UI, message handlers, context meter rendering |
| `agent-tab.html` | HTML template with placeholders for provider/model, proxy dashboard panel |
