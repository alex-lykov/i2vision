# Context Management Configuration Guide

## Overview

The Context Management system allows you to configure what information the agent receives **before** it starts processing a request. This optimizes performance by loading relevant context eagerly and disabling unnecessary tools.

**Key Benefits:**
- ⚡ **Faster responses** - Pre-load context instead of multiple tool calls
- 🎯 **Task-aware** - Different context for refactor vs debug vs explore
- 💰 **Cost efficient** - Disable unused tools to reduce token usage
- 🔧 **Configurable** - All settings in YAML, no code changes

---

## Architecture

```
User Input → Task Detection → Context Profile → Eager Loading → System Prompt → Agent Loop
                ↓                    ↓              ↓              ↓
           "refactor"          YAML config    File context    LLM sees
           "debug"             merge          Git status      everything
           "explore"                          Project info    upfront
```

---

## Configuration File

Context profiles are defined in your project's YAML configuration:

**File Location:** `.vision-ai/code-agent.yaml`

```yaml
# .vision-ai/code-agent.yaml

context:
  # Default profile for all requests
  default:
    eager:
      currentFile: true
      projectMetadata: false
      gitStatus: false
      gitDiff: false
      relatedFiles: false
      directoryStructure: false
    lazy:
      discovery: true
      fullContext: true
      contractValidation: true
  
  # Task-specific overrides
  tasks:
    refactor:
      eager:
        currentFile: true
        relatedFiles: true
    debug:
      eager:
        currentFile: true
        gitDiff: true
    explore:
      eager:
        projectMetadata: true
        directoryStructure: true
```

---

## Context Profile Structure

### Eager Loading

**Eager context** is loaded **before** the agent starts. This data is injected directly into the system prompt.

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `currentFile` | boolean | `false` | Load symbols, classes, functions from open file |
| `projectMetadata` | boolean | `false` | Load module count, architecture pattern |
| `gitStatus` | boolean | `false` | Load working tree status (modified files) |
| `gitDiff` | boolean | `false` | Load recent changes (for debugging) |
| `relatedFiles` | boolean | `false` | Load files imported by current file (max 5) |
| `directoryStructure` | boolean | `false` | Load top-level directory listing (max 50) |

**Performance Impact:**

| Field | Load Time | Token Cost |
|-------|-----------|------------|
| `currentFile` | ~50ms | ~500 tokens |
| `relatedFiles` | ~200ms | ~2000 tokens |
| `projectMetadata` | ~100ms | ~300 tokens |
| `gitStatus` | ~50ms | ~200 tokens |
| `gitDiff` | ~100ms | ~1000 tokens |
| `directoryStructure` | ~50ms | ~400 tokens |

### Lazy Loading

**Lazy context** controls which **optional tools** are available to the agent. Disabled tools are not sent to the LLM.

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `discovery` | boolean | `true` | Enable `i2vision_discover` tool |
| `fullContext` | boolean | `true` | Enable `i2vision_get_context` tool |
| `contractValidation` | boolean | `true` | Enable `i2vision_validate_contracts` tool |

**Note:** Core tools (`read_file`, `list_directory`, etc.) are always enabled. Only optional i2vision tools can be disabled.

---

## Task-Specific Profiles

Task profiles **override** the default profile for specific query types.

### Task Detection

The agent auto-detects task type from user input using pattern matching:

| Task Type | Trigger Words | Example Queries |
|-----------|---------------|-----------------|
| `refactor` | `refactor`, `rename`, `extract`, `move`, `restructure` | "Refactor AuthService", "Extract this method" |
| `debug` | `debug`, `fix`, `bug`, `error`, `crash`, `fail` | "Why does this crash?", "Fix the null pointer" |
| `explore` | `explain`, `what`, `how`, `explore`, `find`, `show` | "What does this project do?", "Show me the structure" |
| `create` | `write`, `create`, `add`, `implement`, `build`, `new` | "Add a new endpoint", "Create a service" |
| `test` | `test`, `spec`, `unit`, `integration`, `coverage` | "Write unit tests", "Add integration tests" |
| `default` | (no match) | "Make it better", "Improve this" |

### Profile Merging

Task profiles **merge** with defaults, not replace:

```yaml
context:
  default:
    eager:
      currentFile: true      # ← Always loaded
      projectMetadata: false # ← Not loaded by default
    lazy:
      discovery: true        # ← Always enabled
  
  tasks:
    explore:
      eager:
        projectMetadata: true  # ← Override: enable for explore
```

**Result for "explore" queries:**
- `currentFile: true` (from default)
- `projectMetadata: true` (from explore override)
- `discovery: true` (from default)

---

## Usage Examples

### Example 1: Lightweight Default (Recommended)

Minimal context for fast responses:

```yaml
context:
  default:
    eager:
      currentFile: true
      projectMetadata: false
      gitStatus: false
      gitDiff: false
      relatedFiles: false
      directoryStructure: false
    lazy:
      discovery: true
      fullContext: true
      contractValidation: true
  
  tasks: {}  # No task-specific overrides
```

**Best for:** General development, quick queries

---

### Example 2: Debug-Focused

Pre-load git diff for debugging sessions:

```yaml
context:
  default:
    eager:
      currentFile: true
      gitDiff: true  # ← See recent changes
    lazy:
      discovery: false  # ← Disable if not needed
  
  tasks:
    debug:
      eager:
        gitStatus: true  # ← Also show working tree
```

**Best for:** Bug fixing, troubleshooting

---

### Example 3: Refactoring Power User

Load related files for comprehensive context:

```yaml
context:
  default:
    eager:
      currentFile: true
      relatedFiles: true  # ← Load imports (max 5)
    lazy:
      discovery: true
  
  tasks:
    refactor:
      eager:
        projectMetadata: true  # ← Understand architecture
        directoryStructure: true  # ← See file organization
```

**Best for:** Large refactoring, architectural changes

**Warning:** `relatedFiles: true` can load 5+ files. Keep default `false`.

---

### Example 4: Project Exploration

Optimized for understanding new codebases:

```yaml
context:
  default:
    eager:
      projectMetadata: true
      directoryStructure: true
    lazy:
      discovery: true
      fullContext: true
  
  tasks:
    explore:
      eager:
        currentFile: false  # ← No file open during exploration
```

**Best for:** Onboarding, codebase exploration

---

### Example 5: Cost-Optimized (Production)

Minimize token usage for production deployments:

```yaml
context:
  default:
    eager:
      currentFile: true
      projectMetadata: false
      gitStatus: false
      gitDiff: false
      relatedFiles: false
      directoryStructure: false
    lazy:
      discovery: false  # ← Disable expensive tools
      fullContext: false
      contractValidation: false
  
  tasks: {}
```

**Best for:** Production, high-volume usage

**Savings:** ~30% reduction in token usage

---

## How It Works

### 1. Task Detection

```typescript
const taskType = detectTaskType(userInput);
// "Refactor AuthService" → "refactor"
```

### 2. Profile Merging

```typescript
const profile = mergeContextProfiles(
  config.context.default,
  config.context.tasks[taskType]
);
```

### 3. Eager Loading

```typescript
const context = await loadEagerContext(profile, currentFile);
// - Loads file symbols
// - Resolves imports to files
// - Runs git commands
// - Lists directories
```

### 4. Context Injection

```typescript
const enhancedPrompt = injectContextIntoPrompt(systemPrompt, context);
// Appends:
// --- LOADED CONTEXT (EAGER) ---
// [CURRENT FILE: src/Service.kt]
// Symbols: {...}
// [PROJECT METADATA]
// Architecture: multi-module
// --- END LOADED CONTEXT ---
```

### 5. Tool Filtering

```typescript
const tools = getTools(profile.lazy);
// Removes disabled tools from LLM request
```

---

## Performance Guidelines

### Fast (< 100ms overhead)

```yaml
eager:
  currentFile: true  # Only load open file
```

**Total load time:** ~50ms  
**Token cost:** ~500 tokens

### Moderate (< 300ms overhead)

```yaml
eager:
  currentFile: true
  projectMetadata: true
  gitStatus: true
```

**Total load time:** ~200ms  
**Token cost:** ~1000 tokens

### Comprehensive (< 500ms overhead)

```yaml
eager:
  currentFile: true
  relatedFiles: true
  projectMetadata: true
  directoryStructure: true
```

**Total load time:** ~400ms  
**Token cost:** ~3000 tokens

### ⚠️ Avoid This (Expensive)

```yaml
eager:
  currentFile: true
  relatedFiles: true  # Loads 5 files
  projectMetadata: true
  gitDiff: true       # Large diff
  directoryStructure: true
  gitStatus: true
```

**Total load time:** ~600ms+  
**Token cost:** ~5000+ tokens

---

## Troubleshooting

### Issue: Context Not Loading

**Symptoms:**
- Logs show `Eager context loaded: currentFile=false`
- Agent calls `read_file` for current file

**Causes:**
1. No `currentFile` in template variables
2. File doesn't exist
3. YAML config missing

**Fix:**
```yaml
context:
  default:
    eager:
      currentFile: true  # ← Ensure this is set
```

---

### Issue: Too Much Context (Slow)

**Symptoms:**
- Agent takes 5+ seconds to respond
- Logs show many files loaded

**Causes:**
- `relatedFiles: true` on central utility file
- `directoryStructure: true` on large project

**Fix:**
```yaml
context:
  default:
    eager:
      relatedFiles: false  # ← Disable by default
      directoryStructure: false
```

---

### Issue: Agent Missing Tools

**Symptoms:**
- "Unknown tool: i2vision_discover" error
- Tool not in available tools list

**Causes:**
- Tool disabled in lazy profile

**Fix:**
```yaml
context:
  default:
    lazy:
      discovery: true  # ← Re-enable tool
```

---

### Issue: Task Detection Wrong

**Symptoms:**
- "fix it" detected as `default` instead of `debug`
- Wrong profile loaded

**Causes:**
- Query too short or ambiguous
- Pattern not matched

**Fix:**
- Be more explicit: "debug: fix the null pointer"
- Or add custom pattern to `detectTaskType()` in code

---

## Best Practices

### ✅ Do

- Start with minimal default profile
- Enable `relatedFiles` only for refactor tasks
- Disable unused tools in production
- Test with real queries before deploying

### ❌ Don't

- Enable all eager fields by default
- Load `relatedFiles` for every request
- Forget to test task detection
- Ignore token cost implications

---

## Monitoring

Check the Output Channel for context loading logs:

```
[AgentBridge] Task type detected: "refactor"
[AgentBridge] Context profile loaded: eager=3, lazy=3
[AgentBridge] Eager context loaded: currentFile=true, project=false, git=false
[AgentBridge] Starting agent loop with max 10 iterations, 13 tools
```

**Key Metrics:**
- `eager=N` - Number of eager fields enabled
- `lazy=N` - Number of lazy fields enabled
- `currentFile=true/false` - Whether file context loaded
- `tools=N` - Number of tools available to LLM

---

## Related Documents

- [Agent Configuration Reference](../reference/agent-config.md)
- [Agent Implementation Guide](../guides/agent-implementation.md)
- [Tool API Reference](../reference/tools.md)
- [Performance Tuning Guide](../guides/performance-tuning.md)

---

## Changelog

| Version | Date | Changes |
|---------|------|---------|
| 1.0.0 | 2026-01-01 | Initial context management implementation |
| 1.0.1 | 2026-01-02 | Added task-specific profiles, improved documentation |
