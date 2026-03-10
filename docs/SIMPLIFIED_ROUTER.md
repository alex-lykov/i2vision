# Simplified Router - Always ImplementationAgent

## Overview

The router has been simplified to **always select ImplementationAgent by default**, while maintaining the layered MCP agent architecture structure for future expansion.

## Architecture

```
UI (Terminal)
    ↓ [AgentTypeDto.IMPLEMENTATION (default)]
MainViewModel
    ↓ [Maps to AgentType.IMPLEMENTATION]
AgentClient
    ↓ [Passes to orchestrator]
AgentOrchestrator
    ↓ [Routes with agentType parameter]
AgentRouter
    ↓ [Always uses ImplementationAgent by default]
ImplementationAgent
    ↓ [Uses Terminal+Prompt UI]
ModelWrapper (Ollama)
```

## Key Changes

### 1. Simplified Router (`AgentRouter.kt`)

**Before:** Complex intent analysis to select agent
```kotlin
suspend fun routeTask(task: String, context: TaskContext): AgentResponse {
    val intent = analyzeTaskIntent(task)  // Complex analysis
    return when (intent.agentType) { ... }
}
```

**After:** Simple parameter-based routing, defaults to ImplementationAgent
```kotlin
suspend fun routeTask(
    task: String, 
    context: TaskContext, 
    agentType: AgentType = AgentType.IMPLEMENTATION  // Default
): AgentResponse {
    return when (agentType) {
        AgentType.IMPLEMENTATION -> implementationAgent.process(task, context)
        // Other agents available but not used by default
        ...
    }
}
```

### 2. UI Control

**Terminal UI** shows current agent type:
- Displays: `Agent: IMPLEMENTATION`
- Status: `⚙️ Implementation (Default)`
- Future: Can add dropdown to switch agents

**ViewModel** manages agent selection:
- `TerminalStateDto.selectedAgentType` - defaults to `IMPLEMENTATION`
- `MainViewModel.setSelectedAgentType()` - allows UI to change (future)
- `TerminalViewModel.setAgentType()` - exposes control to UI

### 3. Data Flow

```
User Input
    ↓
TerminalViewModel.submitTask()
    ↓
MainViewModel.submitTask() 
    ↓ [Uses state.selectedAgentType, defaults to IMPLEMENTATION]
AgentLauncher.processTask(task, mode, agentType)
    ↓
AgentClient.processTask(task, mode, agentType)
    ↓ [Maps AgentTypeDto → AgentType]
AgentOrchestrator.routeTaskStreaming(task, sessionId, agentType)
    ↓ [Defaults to IMPLEMENTATION if not specified]
AgentRouter.routeTaskStreaming(task, context, agentType)
    ↓ [Always IMPLEMENTATION by default]
ImplementationAgent.processStreaming()
    ↓
Terminal UI displays response
```

## Benefits

1. **Simplified**: No complex intent analysis - always uses ImplementationAgent
2. **Extensible**: Architecture supports other agents, just not used by default
3. **UI Control**: UI can see and control agent selection (ready for future expansion)
4. **Consistent**: All tasks go through ImplementationAgent with Terminal UI

## Current Status

✅ **Router**: Always defaults to `AgentType.IMPLEMENTATION`
✅ **UI**: Shows current agent type in Terminal
✅ **ViewModel**: Manages agent selection state
✅ **Architecture**: Other agents available but not used

## Future Expansion

When ready to enable other agents:

1. **Add UI Dropdown** in Terminal:
```kotlin
DropdownMenu(...) {
    AgentTypeDto.values().forEach { type ->
        DropdownMenuItem(onClick = { viewModel.setAgentType(type) }) {
            Text(type.name)
        }
    }
}
```

2. **Router will automatically route** to selected agent
3. **No code changes needed** in router - it already supports all agent types

## Files Modified

- `core/orchestrator/router/AgentRouter.kt` - Simplified routing logic
- `core/orchestrator/AgentOrchestrator.kt` - Added agentType parameter
- `launcher/core/AgentClient.kt` - Maps DTO to domain type
- `launcher/core/AgentLauncher.kt` - Added agentType parameter
- `launcher/gui/viewmodel/MainViewModel.kt` - Manages agent selection
- `launcher/gui/components/Terminal.kt` - Shows agent type
- `launcher/gui/data/UiDtos.kt` - Added AgentTypeDto

The system now always uses ImplementationAgent by default, with UI control ready for future expansion.
