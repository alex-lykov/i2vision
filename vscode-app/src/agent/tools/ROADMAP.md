# Tool Registry System - Enhancement Roadmap

## Completed ✅

### Phase 1: Core Infrastructure
- [x] Create `ToolRegistry` class for declarative tool management
- [x] Define `ToolDefinition` interface with handler, metadata, and categories
- [x] Create `ToolContext` interface for tool execution
- [x] Migrate all built-in tools to registry:
  - [x] FileTools (8 tools: list_directory, read_file, write_file, search_files, get_file_context, revert_file, revert_all, list_snapshots)
  - [x] GitTools (5 tools: git_status, git_diff, git_log, git_branch, git_commit)
  - [x] TerminalTools (5 tools: run_terminal, kill_terminal, list_terminals, terminal_status, kill_port)
  - [x] EditTools (1 tool: apply_edits)
  - [x] BuildTools (1 tool: run_build)
- [x] Integrate `ToolRegistry` into `AgentBridge`
- [x] Replace `getTools()` to use registry
- [x] Replace `executeTool()` to delegate to registry

### Phase 2: UI Enhancements - Backend
- [x] Add category icon mappings (file, git, build, terminal, edit)
- [x] Create `ToolUiConfig` interface for UI customization
- [x] Add category badge support
- [x] Add collapsible tool descriptions (tooltip on hover)
- [x] Integrate `ToolRegistry` with `ToolCardFormatter`
- [x] Add UI configuration to `ToolCardConfig`
- [x] Add VS Code settings for UI customization

### Phase 6: Enhanced Tool Card UI - WebView ✅
- [x] Create `injectToolCardStyles()` function for enhanced CSS
- [x] Add category badges with Codicon icons in webview
- [x] Add color-coded category badges (file=blue, git=purple, build=orange, terminal=green, edit=yellow)
- [x] Add help icon (?) with tooltip showing tool description
- [x] Update `createToolCard()` to include category metadata
- [x] Add `getToolCategory()` helper function
- [x] Add `TOOL_DESCRIPTIONS` constant for tooltips
- [x] Implement compact mode styling (CSS class)
- [x] Style success/error states with colored left border
- [x] Enhanced tool call header layout with proper spacing

### Phase 3: YAML Configuration System ✅
**Priority:** High  
**Estimated Effort:** 3 days  
**Status:** COMPLETED

- [x] Create `ToolConfigLoader` class for YAML parsing
- [x] Define YAML schema (`ToolYamlConfig` interface)
- [x] Implement YAML parser for `.vision-ai/tools.yaml`
- [x] Add tool enable/disable per project (`disabled` list)
- [x] Support custom tool definitions from YAML (`custom` array)
- [x] Add tool timeout configuration (`overrides` section)
- [x] Support layer-specific tool filtering (`layers` section)
- [x] Implement hot-reload on file changes (FileSystemWatcher)
- [x] Integrate `ToolConfigLoader` into `AgentBridge`
- [x] Add `disabledTools` set to `ToolRegistry`
- [x] Add `disableTool()` and `enableTool()` methods

**Example YAML:**
```yaml
# .vision-ai/tools.yaml
tools:
  disabled:
    - git_commit      # Disable for this project
    - write_file      # Read-only mode
  
  custom:
    - name: deploy_to_staging
      description: Deploy the application to staging
      command: "./deploy.sh staging"
      timeout: 120000
      category: terminal
      requiresConfirmation: true
  
  overrides:
    run_build:
      timeoutMs: 180000
    apply_edits:
      requiresConfirmation: true

layers:
  VISION:
    enabled: [read_file, list_directory, search_files]
  CODE:
    enabled: all
```

---

## Pending Enhancements

### Phase 3: YAML Configuration System
**Priority:** High  
**Estimated Effort:** 3 days

- [ ] Create YAML schema for tool configuration
- [ ] Implement YAML parser for `.vision-ai/tools.yaml`
- [ ] Add tool enable/disable per project
- [ ] Support custom tool definitions from YAML
- [ ] Add tool timeout configuration from YAML
- [ ] Support layer-specific tool filtering from YAML
- [ ] Add confirmation requirements from YAML
- [ ] Hot-reload configuration on file changes

**Example YAML:**
```yaml
# .vision-ai/tools.yaml
tools:
  disabled:
    - git_commit      # Disable for this project
    - write_file      # Read-only mode
  
  custom:
    - name: deploy_to_staging
      description: Deploy the application to staging
      command: "./deploy.sh staging"
      timeout: 120000
      category: terminal
      requiresConfirmation: true
  
  overrides:
    run_build:
      timeoutMs: 180000
    apply_edits:
      maxEdits: 100

layers:
  VISION:
    enabled: [read_file, list_directory, search_files]
  CODE:
    enabled: all
```

---

### Phase 4: Layer-Specific Tool Filtering
**Priority:** Medium  
**Estimated Effort:** 2 days

- [ ] Implement `enabledPerLayer` filtering in `ToolRegistry`
- [ ] Add layer detection from agent type
- [ ] Create layer-specific tool presets
- [ ] Add UI indicator for current layer
- [ ] Allow layer switching in agent settings
- [ ] Document layer conventions:
  - `VISION`: UI components, views, templates
  - `STRUCTURE`: Models, entities, interfaces
  - `LOGIC`: Services, business logic, utilities
  - `FLOW`: Controllers, routers, middleware
  - `CODE`: Default, all tools available

---

### Phase 5: Custom Tool Loader
**Priority:** Medium  
**Estimated Effort:** 4 days

- [ ] Create plugin system for custom tools
- [ ] Support JavaScript/TypeScript tool definitions
- [ ] Add tool validation and sandboxing
- [ ] Create custom tool template generator
- [ ] Add custom tool documentation generator
- [ ] Support tool composition (combine multiple tools)
- [ ] Add custom tool debugging utilities

**Example Custom Tool:**
```typescript
// .vision-ai/tools/custom/deploy.ts
import { ToolDefinition } from 'agent/tools';

export const deployTool: ToolDefinition = {
  name: 'deploy_to_production',
  description: 'Deploy the application to production environment',
  category: 'terminal',
  isReadOnly: false,
  requiresConfirmation: true,
  confirmationMessage: '⚠️ This will deploy to PRODUCTION. Continue?',
  parameters: { /* ... */ },
  async handler(args, ctx) {
    // Custom deployment logic
  }
};
```

---

### Phase 6: Enhanced Tool Card UI
**Priority:** High  
**Estimated Effort:** 5 days

- [ ] **Category Icons in Tool Cards**
  - [ ] Render Codicon based on tool category
  - [ ] Color-code by category (file=blue, git=purple, etc.)
  - [ ] Icon size configuration (small/medium/large)
  
- [ ] **Tool Description Tooltips**
  - [ ] Add `?` help icon next to tool name
  - [ ] Show full description on hover
  - [ ] Show parameter descriptions in tooltip
  - [ ] Show examples in tooltip
  
- [ ] **Category Badges**
  - [ ] Display category badge (FILE, GIT, BUILD, etc.)
  - [ ] Color-coded badges
  - [ ] Toggle badge visibility
  
- [ ] **Configurable Options Per Category**
  - [ ] File operations: show file tree view
  - [ ] Git operations: show diff viewer
  - [ ] Terminal: show terminal inline
  - [ ] Build: show error list
  - [ ] Edit: show before/after diff
  
- [ ] **Compact Mode**
  - [ ] Minimal UI for power users
  - [ ] Hide descriptions, show only icons
  - [ ] Collapse all by default
  
- [ ] **Confirmation Dialogs**
  - [ ] Modal for destructive operations
  - [ ] Show `confirmationMessage` from tool definition
  - [ ] Require explicit user approval
  - [ ] Add "Don't ask again" option

---

### Phase 7: Tool Analytics & Monitoring
**Priority:** Low  
**Estimated Effort:** 3 days

- [ ] Track tool usage statistics
- [ ] Measure average execution time per tool
- [ ] Track failure rates
- [ ] Identify slow tools
- [ ] Generate usage reports
- [ ] Suggest tool optimizations
- [ ] Detect tool usage patterns

---

### Phase 8: Tool Testing Framework
**Priority:** Medium  
**Estimated Effort:** 4 days

- [ ] Create mock `ToolContext` for testing
- [ ] Add tool handler unit test templates
- [ ] Create integration test suite
- [ ] Add tool performance benchmarks
- [ ] Create tool validation utilities
- [ ] Add regression test suite
- [ ] Document testing best practices

**Example Test:**
```typescript
import { createMockToolContext } from 'agent/tools/testing';
import { fileTools } from 'agent/tools/builtin/FileTools';

describe('read_file tool', () => {
  it('should read existing file', async () => {
    const ctx = createMockToolContext({
      files: { '/test.txt': 'hello world' }
    });
    const tool = fileTools.find(t => t.name === 'read_file')!;
    const result = await tool.handler({ path: 'test.txt' }, ctx);
    expect(result.result).toBe('hello world');
  });
});
```

---

### Phase 9: Tool Discovery & Documentation
**Priority:** Medium  
**Estimated Effort:** 3 days

- [ ] Create interactive tool browser in webview
- [ ] Show all available tools with descriptions
- [ ] Filter tools by category
- [ ] Search tools by name/description
- [ ] Show tool usage examples
- [ ] Generate tool documentation automatically
- [ ] Add "recently used tools" section
- [ ] Add "recommended tools" based on task type

---

### Phase 10: Advanced Features
**Priority:** Low  
**Estimated Effort:** 6 days

- [ ] **Tool Chaining**
  - [ ] Define tool pipelines (execute A, then B with result)
  - [ ] Support conditional tool execution
  - [ ] Add tool composition operators
  
- [ ] **Tool Versioning**
  - [ ] Support multiple tool versions
  - [ ] Allow rollback to previous versions
  - [ ] Track tool changes over time
  
- [ ] **Tool Marketplace**
  - [ ] Share custom tools online
  - [ ] Import tools from marketplace
  - [ ] Rate and review tools
  
- [ ] **AI-Assisted Tool Creation**
  - [ ] Generate tool scaffolding from description
  - [ ] Suggest tool improvements
  - [ ] Auto-generate tool documentation

---

## Configuration Reference

### VS Code Settings

```jsonc
{
  // Tool card visibility
  "i2vision.toolCards.showTools": "all",  // all | failed_only | none
  
  // Default display options
  "i2vision.toolCards.defaultMaxLines": 20,
  "i2vision.toolCards.defaultMaxChars": 3000,
  "i2vision.toolCards.defaultFoldThreshold": 10,
  "i2vision.toolCards.defaultFoldDefault": "collapsed",
  "i2vision.toolCards.defaultFormat": "raw",
  "i2vision.toolCards.defaultShowArgs": true,
  "i2vision.toolCards.defaultShowDuration": true,
  "i2vision.toolCards.defaultCollapseOnSuccess": true,
  
  // UI customization
  "i2vision.toolCards.ui.showCategoryIcon": true,
  "i2vision.toolCards.ui.showDescriptionTooltip": true,
  "i2vision.toolCards.ui.showCategoryBadge": true,
  "i2vision.toolCards.ui.iconSize": "medium",  // small | medium | large
  "i2vision.toolCards.ui.compactMode": false,
  
  // Per-tool overrides
  "i2vision.toolCards.perToolConfig": {
    "list_directory": {
      "format": "tree",
      "collapseOnSuccess": false
    },
    "read_file": {
      "maxLines": 50,
      "syntaxHighlight": true
    }
  }
}
```

---

## Architecture Benefits

| Feature | Before | After |
|---------|--------|-------|
| **Add new tool** | Edit 3 places in AgentBridge | Create 1 file in `tools/builtin/` |
| **Disable tool** | Not possible | Remove from registry or YAML config |
| **Tool filtering** | Hardcoded arrays | `enabledPerLayer` property |
| **Testing** | Mock entire AgentBridge | Mock `ToolContext` only |
| **UI customization** | Limited | Full config-driven UI |
| **Custom tools** | Not supported | YAML or JS plugin system |
| **Tool metadata** | None | Category, icon, description, timeout |

---

## Migration Checklist

For teams migrating from the old switch statement approach:

- [ ] Install updated extension with ToolRegistry
- [ ] Review existing custom tools (if any)
- [ ] Migrate custom tools to `ToolDefinition` format
- [ ] Create `.vision-ai/tools.yaml` for project-specific config
- [ ] Update tests to use new mock `ToolContext`
- [ ] Configure UI preferences in VS Code settings
- [ ] Document any disabled tools for team members

---

## Support & Documentation

- **API Reference**: `src/agent/tools/ToolTypes.ts`
- **Registry Usage**: `src/agent/tools/ToolRegistry.ts`
- **Built-in Tools**: `src/agent/tools/builtin/`
- **UI Configuration**: `src/agent/ToolCardConfig.ts`
- **Examples**: See existing tool implementations in `builtin/` folder

---

*Last updated: 2026-06-25*
