# Layered MCP Agent Architecture

## Overview

The system now implements a **Layered MCP Agent Architecture** where tasks are routed to specialized agents, each with their own MCP workspace.

## Architecture Diagram

```
Router Layer
    ├── Idea Agent → Idea Workspace
    ├── Architecture Agent → Architecture Workspace
    ├── Module Agent → Module Workspace
    ├── Test Agent → Test Workspace
    └── Implementation Agent → Implementation Workspace (uses Terminal+Prompt UI)
```

## Components

### 1. Router Layer (`AgentRouter`)

**Location:** `core/orchestrator/router/AgentRouter.kt`

- Analyzes task intent to determine which agent should handle it
- Routes tasks to appropriate specialized agents
- Supports both synchronous and streaming responses

**Task Intent Analysis:**
- **Idea Agent**: "idea", "concept", "brainstorm", "plan", "design" (high-level)
- **Architecture Agent**: "architecture", "structure", "pattern", "system design"
- **Module Agent**: "module", "refactor module", "restructure", "package"
- **Test Agent**: "test", "spec", "coverage", "unit test"
- **Implementation Agent**: Default for code changes, features, fixes

### 2. Specialized Agents

All agents extend `BaseAgent` and have their own MCP workspace:

#### Idea Agent
- **Purpose**: High-level concepts, brainstorming, planning
- **Workspace**: `IdeaWorkspace`
- **Status**: Stub implementation
- **MCP Tools**: `explore_concepts`, `brainstorm`

#### Architecture Agent
- **Purpose**: System design, structure, patterns
- **Workspace**: `ArchitectureWorkspace`
- **Status**: Stub implementation
- **MCP Tools**: `analyze_structure`, `design_patterns`

#### Module Agent
- **Purpose**: Module-level changes and refactoring
- **Workspace**: `ModuleWorkspace`
- **Status**: Stub implementation
- **MCP Tools**: `analyze_modules`, `refactor_module`

#### Test Agent
- **Purpose**: Test generation, execution, coverage
- **Workspace**: `TestWorkspace`
- **Status**: Stub implementation
- **MCP Tools**: `run_tests`, `generate_tests`

#### Implementation Agent
- **Purpose**: Actual code implementation
- **Workspace**: `ImplementationWorkspace` (uses project MCP tools)
- **Status**: **Fully implemented** - uses Terminal+Prompt UI
- **MCP Tools**: All project-specific tools (file_analyzer, code_search, build_runner, etc.)

### 3. MCP Workspace Layer

**Location:** `core/orchestrator/mcp/workspace/`

Each workspace provides:
- Isolated context for its agent type
- Workspace-specific MCP tools
- Tool execution capabilities

**Workspace Types:**
- `IdeaWorkspace`: Concept exploration tools
- `ArchitectureWorkspace`: Design and structure tools
- `ModuleWorkspace`: Module analysis and refactoring tools
- `TestWorkspace`: Test execution and generation tools
- `ImplementationWorkspace`: Full project MCP tools for code implementation

### 4. Integration Points

#### Orchestrator Integration
- `AgentOrchestrator` now initializes the `AgentRouter` with all agents
- `routeTask()` and `routeTaskStreaming()` delegate to the router
- Workspaces are created via `WorkspaceFactory`

#### UI Integration
- **Implementation Agent** uses the existing Terminal+Prompt UI
- `AgentClient` converts `AgentResponseChunk` to `OutputEvent` for UI display
- Streaming responses provide real-time updates in the terminal

## Usage Flow

1. **User Input**: Task entered in Terminal UI
2. **Router Analysis**: `AgentRouter` analyzes task intent
3. **Agent Selection**: Appropriate agent is selected
4. **Workspace Context**: Agent uses its MCP workspace for context
5. **Task Processing**: Agent processes task with workspace tools
6. **Response Streaming**: Results stream back to Terminal UI

## Current Status

✅ **Completed:**
- Router Layer implementation
- All agent stubs (Idea, Architecture, Module, Test)
- Implementation Agent with Terminal UI integration
- MCP Workspace Layer structure
- Orchestrator integration

🔄 **Stub Implementations:**
- Idea Agent (ready for implementation)
- Architecture Agent (ready for implementation)
- Module Agent (ready for implementation)
- Test Agent (ready for implementation)

✅ **Fully Functional:**
- Implementation Agent (uses Terminal+Prompt UI, real model execution)

## Next Steps

1. Implement Idea Agent logic for concept generation
2. Implement Architecture Agent for system design
3. Implement Module Agent for module refactoring
4. Implement Test Agent for test generation
5. Enhance MCP tool execution in workspaces
6. Add agent-specific UI components if needed

## File Structure

```
core/orchestrator/
├── router/
│   └── AgentRouter.kt          # Router layer
├── agents/
│   ├── BaseAgent.kt            # Base agent class
│   ├── IdeaAgent.kt            # Idea agent (stub)
│   ├── ArchitectureAgent.kt    # Architecture agent (stub)
│   ├── ModuleAgent.kt          # Module agent (stub)
│   ├── TestAgent.kt            # Test agent (stub)
│   └── ImplementationAgent.kt  # Implementation agent (fully implemented)
└── mcp/
    ├── workspace/
    │   ├── McpWorkspace.kt     # Workspace interface and implementations
    │   └── WorkspaceFactory.kt # Factory for creating workspaces
    ├── McpIntegration.kt       # MCP integration (existing)
    └── McpStatus.kt            # MCP status (existing)
```
