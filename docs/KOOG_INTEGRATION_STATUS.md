# Koog Core Functionalities Integration Status

## Overview

This document tracks the integration of Koog's core functionalities for handling project file context in the coding agent.

## ✅ Completed

### 1. File Access Tools Infrastructure
**Location:** `core/orchestrator/tools/KoogToolRegistryBuilder.kt`

- ✅ Created `KoogToolRegistryBuilder` class
- ✅ Implemented `FileAccessTools` class with:
  - `listDirectory()` - Explore project structure
  - `readFile()` - Read file contents  
  - `regexSearch()` - Search across files with patterns
  - `writeFile()` - Write file contents
- ✅ Defined input/output data classes for all tools
- ✅ Project root-aware path resolution

### 2. MCP Structure Activation
**Location:** `core/orchestrator/AgentOrchestrator.kt`

- ✅ Project loading activates MCP structure
- ✅ Workspaces created for all agent types
- ✅ Agent router initialized with MCP tools

## 🔄 In Progress / Next Steps

### 1. Koog AIAgent Integration with ToolRegistry
**Current Status:** Tools are implemented but not yet integrated with Koog's `AIAgent`

**What's Needed:**
- Update `ImplementationAgent` to use Koog's `AIAgent` with `ToolRegistry`
- Replace current `ModelWrapper.generate()` calls with `AIAgent.run()` that has tool access
- Integrate `KoogToolRegistryBuilder` into agent initialization

**Example Integration:**
```kotlin
// In ImplementationAgent
val toolRegistry = KoogToolRegistryBuilder(contextProvider).build()
val agent = AIAgent(
    promptExecutor = simpleOllamaAIExecutor(),
    llmModel = llmModel,
    toolRegistry = toolRegistry,  // Add tool registry
    systemPrompt = systemPrompt
)
```

### 2. History Compression with Concept-Based Extraction
**Current Status:** Not implemented

**What's Needed:**
- Implement `RetrieveFactsFromHistory` with concepts:
  - `project-structure` - What is the structure of this project?
  - `important-achievements` - What has been achieved during execution?
  - `agent-goal` - What is the primary goal or task?
- Add compression triggers (e.g., `messages.size > 200 || content.length > 200000`)
- Use cheaper models for compression (e.g., GPT-4.1 Mini)
- Handle pattern-breaking during compression

**Reference Pattern:**
```kotlin
val CODE_AGENT_COMPRESSION_STRATEGY = RetrieveFactsFromHistory(
    Concept("project-structure", "What is the structure of this project?", FactType.MULTIPLE),
    Concept("important-achievements", "What has been achieved?", FactType.MULTIPLE),
    Concept("agent-goal", "What is the primary goal?", FactType.SINGLE)
)
```

### 3. Sub-Agent Pattern for Context Isolation
**Current Status:** Not implemented

**What's Needed:**
- Create `FindAgent` sub-agent for codebase exploration
- Use `AIAgentService.fromAgent()` to create agent tools
- Main agent sees only results, not exploration context
- Sub-agent context disappears after returning

**Example Pattern:**
```kotlin
fun createFindAgentTool(): Tool<*, *> {
    return AIAgentService
        .fromAgent(findAgent as GraphAIAgent<String, String>)
        .createAgentTool<String, String>(
            agentName = "__find_in_codebase_agent__",
            agentDescription = "Intelligent micro agent that analyzes code context"
        )
}
```

### 4. Enhanced MCP Integration
**Current Status:** Basic MCP integration exists

**What's Needed:**
- Connect to actual MCP servers using `McpToolRegistryProvider`
- Support stdio transport for MCP servers
- Integrate with IDEs (IntelliJ/Android Studio) via MCP
- Add MCP tool execution in workspaces

**Example:**
```kotlin
val transport = McpToolRegistryProvider.defaultStdioTransport(process)
val toolRegistry = McpToolRegistryProvider.fromTransport(transport)
```

### 5. Agent Memory and Knowledge Retention
**Current Status:** Not implemented

**What's Needed:**
- Implement `AgentMemory` for storing facts
- Persist facts between agent runs
- Share knowledge between multiple agents
- Store project context facts for reuse

### 6. Multiple LLM Provider Support
**Current Status:** ✅ Partially implemented

- ✅ Ollama support via `simpleOllamaAIExecutor()`
- ✅ Model switching infrastructure exists
- 🔄 Need to add support for:
  - OpenAI
  - Anthropic
  - Google Gemini
  - DeepSeek

## Architecture Integration Points

### Current Flow
```
User Input → AgentOrchestrator → ImplementationAgent → ModelWrapper.generate()
```

### Target Flow (Full Koog Integration)
```
User Input → AgentOrchestrator → ImplementationAgent → AIAgent.run() [with ToolRegistry]
    ↓
Tool Calls → FileAccessTools → Project Files
    ↓
History Compression → Concept Extraction → Compressed Context
    ↓
Sub-Agents → Context Isolation → Results Only
```

## Implementation Priority

1. **High Priority:**
   - ✅ File access tools (DONE)
   - 🔄 Integrate ToolRegistry with AIAgent
   - 🔄 Basic history compression

2. **Medium Priority:**
   - Sub-agent pattern for codebase search
   - Enhanced MCP server connections
   - Agent memory for knowledge retention

3. **Low Priority:**
   - Additional LLM providers
   - Advanced compression strategies
   - Multi-agent knowledge sharing

## Files Created/Modified

### New Files
- `core/orchestrator/tools/KoogToolRegistryBuilder.kt` - File access tools infrastructure

### Modified Files
- `core/orchestrator/AgentOrchestrator.kt` - Added MCP structure activation
- `core/orchestrator/agents/ImplementationAgent.kt` - (Needs update for ToolRegistry integration)

## Next Immediate Steps

1. **Update ImplementationAgent** to use Koog's `AIAgent` with `ToolRegistry`
2. **Test file access tools** with actual project loading
3. **Add history compression** with concept-based extraction
4. **Create FindAgent** sub-agent for codebase exploration

## References

- Koog Agents Documentation: https://api.koog.ai/
- MCP Integration: See `docs/MCP_INTEGRATION.md`
- Layered Architecture: See `docs/LAYERED_MCP_ARCHITECTURE.md`
