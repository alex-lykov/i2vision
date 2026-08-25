# VSLFC Layers (Future Enhancement)

## Overview

VSLFC (Vision-Structure-Logic-Flow-Code) is a **planned** architecture for organizing AI agent capabilities by architectural layer. This document describes the intended design; full implementation is on the roadmap.

## Vision

The VSLFC architecture recognizes that different AI tasks require different levels of system access and architectural understanding:

```
Vision    → UI/UX analysis, screenshot interpretation
  ↓
Structure → Architecture analysis, module relationships
  ↓
Logic     → Business logic, algorithms, data flow
  ↓
Flow      → Workflow, state machines, process orchestration
  ↓
Code      → Source code implementation, refactoring
```

## Planned Benefits

### 1. Context Relevance

Each layer gets appropriate context:
- **Vision**: Screenshots, CSS, design tokens
- **Structure**: Module graph, dependencies, architecture patterns
- **Logic**: Domain models, use cases, business rules
- **Flow**: Process definitions, state machines, workflows
- **Code**: Source files, tests, build configurations

### 2. Tool Filtering

Tools are filtered by layer to prevent inappropriate operations:

| Layer | Allowed Tools | Restricted Tools |
|-------|--------------|------------------|
| Vision | read_file, search_files | write_file, run_terminal |
| Structure | read_file, list_directory | apply_edits, run_build |
| Logic | read_file, write_file | run_terminal, git_commit |
| Flow | read/write, terminal | apply_edits (limited) |
| Code | All tools | None |

### 3. Prompt Specialization

Each layer has specialized system prompts:
- **Vision**: Focus on UI patterns, accessibility, design systems
- **Structure**: Focus on modularity, coupling, cohesion
- **Logic**: Focus on correctness, edge cases, domain accuracy
- **Flow**: Focus on reliability, error handling, recovery
- **Code**: Focus on cleanliness, performance, maintainability

## Current Status

### Implemented

- ✅ Tool registry with category organization
- ✅ Layer field in tool definitions (not yet enforced)
- ✅ Agent type configuration (vision-agent, structure-agent, etc.)

### Not Yet Implemented

- ❌ Layer-based tool filtering
- ❌ Layer-specific context loading
- ❌ Layer-specific prompt templates
- ❌ Cross-layer collaboration protocols

## Planned Implementation

### Phase 1: Tool Filtering

```typescript
// Future: Filter tools by layer
getLLMTools(layer: VslfcLayer): LLMTool[] {
  return this.tools.filter(tool => {
    if (!tool.layer) return true // No restriction
    return this.isLayerAccessible(tool.layer, layer)
  })
}

isLayerAccessible(toolLayer: VslfcLayer, agentLayer: VslfcLayer): boolean {
  // Agent can access tools at same or lower layers
  const layerOrder = ['vision', 'structure', 'logic', 'flow', 'code']
  return layerOrder.indexOf(toolLayer) <= layerOrder.indexOf(agentLayer)
}
```

### Phase 2: Context Loading

```typescript
// Future: Load layer-specific context
async loadContext(layer: VslfcLayer): Promise<LayerContext> {
  switch (layer) {
    case 'vision':
      return loadVisionContext() // Screenshots, CSS, etc.
    case 'structure':
      return loadStructureContext() // Module graph, etc.
    // ... other layers
  }
}
```

### Phase 3: Prompt Templates

```typescript
// Future: Layer-specific prompts
const layerPrompts: Record<VslfcLayer, string> = {
  vision: 'You are a UI/UX analysis expert...',
  structure: 'You are a software architecture expert...',
  logic: 'You are a business logic expert...',
  flow: 'You are a process orchestration expert...',
  code: 'You are a coding expert...'
}
```

## Agent Types

Each VSLFC layer corresponds to an agent type:

| Agent Type | Layer | Purpose |
|------------|-------|---------|
| vision-agent | Vision | UI/UX analysis, design review |
| structure-agent | Structure | Architecture analysis, refactoring plans |
| logic-agent | Logic | Business logic, domain modeling |
| flow-agent | Flow | Workflow orchestration, process automation |
| code-agent | Code | Implementation, bug fixes, tests |

## Configuration

### Agent Configuration (Future)

```yaml
# .vision-ai/vision-agent.yaml
key: vision-agent
agentType: vision
layer: vision
systemPromptTemplate: |
  You are a UI/UX analysis expert...
allowedTools:
  - read_file
  - search_files
  - list_directory
context:
  loadScreenshots: true
  loadCSS: true
  loadDesignTokens: true
```

## Related Concepts

### Domain Detection

The planned domain detector would identify which layer(s) a task belongs to:

```typescript
// Future: Detect task domain
detectDomain(task: string): DomainResolution {
  // "Fix the login button" → vision
  // "Refactor the module structure" → structure
  // "Implement the payment flow" → flow
  // "Add unit tests" → code
}
```

### Multi-Agent Collaboration

Future: Multiple agents at different layers collaborate:

```
User Request: "Add a new payment feature"
    ↓
Structure Agent → Design module structure
    ↓
Logic Agent → Define domain models
    ↓
Flow Agent → Orchestrate payment workflow
    ↓
Code Agent → Implement source code
    ↓
Vision Agent → Design UI components
```

## Current Workarounds

Until VSLFC is fully implemented:

1. **Tool Categories**: Tools are organized by category (file, edit, terminal, git, build)
2. **Agent Types**: Different agents can be configured with different tool sets
3. **Manual Filtering**: Users can configure `toolSelection.requiredToolsForModification`

## Tracking

- **Issue**: [Link to GitHub issue]
- **Milestone**: [Link to milestone]
- **ADR**: [Link to Architecture Decision Record when created]

## Related Documentation

- [Tool System Architecture](../architecture/tool-system.md)
- [AgentBridge Architecture](../architecture/agent-bridge.md)
- [Agent Configuration Reference](../reference/agent-config.md)
- [Roadmap](../roadmap.md)
