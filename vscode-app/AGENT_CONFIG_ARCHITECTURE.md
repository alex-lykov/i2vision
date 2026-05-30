# Agent Configuration Architecture

## 📐 Overview

The i2-Vision agent system uses a **layered configuration approach**:

1. **Base defaults** - Single source of truth in `conf-agent-core`
2. **Layer overrides** - Workspace-specific customizations
3. **Runtime merging** - Final config = defaults + overrides

## 📁 File Structure

```
conf-agent-core/src/commonMain/resources/
  └── default-agent-config.yaml    ← Global template with all defaults documented

{workspace}/.vision-ai/
  ├── coding-agent.yaml            ← CODE layer overrides
  ├── logic-agent.yaml             ← LOGIC layer overrides
  ├── flow-agent.yaml              ← FLOW layer overrides
  └── structure-agent.yaml         ← STRUCTURE layer overrides
```

## 🔄 Configuration Flow

```
┌─────────────────────────────────────────────────────────────┐
│  1. Extension Startup                                       │
│     └─> Load default-agent-config.yaml from resources       │
└─────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────┐
│  2. Agent Creation (per layer)                              │
│     └─> Check {workspace}/.vision-ai/{layer}-agent.yaml     │
│     └─> If exists: Load and merge with defaults             │
│     └─> If missing: Use defaults only                       │
└─────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────┐
│  3. Agent Execution                                         │
│     └─> Use merged configuration                            │
│     └─> Cache cleared on each agent creation                │
└─────────────────────────────────────────────────────────────┘
```

## 🧩 Configuration Merging

### Merge Strategy

- **Deep merge** for nested objects (model, llm, execution, etc.)
- **Shallow merge** for top-level fields
- **Layer overrides take precedence** over defaults

### Example

**default-agent-config.yaml:**
```yaml
model:
  id: "qwen3:4b"
  provider: "ollama"
  temperature: 0.7
  topP: 0.9

execution:
  enableBuildVerification: true
  buildCommand: "./gradlew build"
  fileOperations:
    mode: "shell"
    shell:
      executable: "bash"
```

**coding-agent.yaml (overrides only):**
```yaml
model:
  id: "minimax-m2.1:cloud"
  temperature: 0.2

execution:
  buildCommand: "gradlew.bat compileKotlin"
  fileOperations:
    mode: "direct"
    shell:
      executable: "powershell"
```

**Final Merged Config:**
```yaml
model:
  id: "minimax-m2.1:cloud"    # ← Overridden
  provider: "ollama"           # ← From default
  temperature: 0.2             # ← Overridden
  topP: 0.9                    # ← From default

execution:
  enableBuildVerification: true              # ← From default
  buildCommand: "gradlew.bat compileKotlin"  # ← Overridden
  fileOperations:
    mode: "direct"                           # ← Overridden
    shell:
      executable: "powershell"               # ← Overridden
```

## 📝 Configuration Sections

| Section | Purpose | Key Fields |
|---------|---------|------------|
| **Identity** | Agent identification | `key`, `agentType`, `version` |
| **Prompt** | System prompt & variables | `systemPromptTemplate`, `templateVariables` |
| **Model** | LLM configuration | `id`, `provider`, `temperature`, `topP` |
| **LLM Behavior** | Timeouts & retries | `timeoutSeconds`, `maxRetries` |
| **Formatting Rules** | Output parsing | `reasoningHeader`, `toolCallHeader`, `eosMarker` |
| **Iteration** | Loop control | `maxIterations`, `maxConsecutiveToolCalls` |
| **Tool Selection** | Tool discovery | `requiredToolsForModification`, `defaultRelevanceThreshold` |
| **Safety** | Path restrictions | `blockGeneratedPaths`, `modificationKeywords` |
| **Parsing** | Response parsing | `enabledParsers`, `headerPattern`, `toolCallPattern` |
| **Execution** | Build & file ops | `enableBuildVerification`, `buildCommand`, `fileOperations` |
| **Streaming** | Response streaming | `enabled`, `fallbackToNonStreaming` |
| **MCP** | Model Context Protocol | `enabled`, `allowedToolPrefixes` |

## 🛠️ Code Components

### LocalAgentProvider

**Location:** `vscode-app/src/agent/LocalAgentProvider.ts`

**Responsibilities:**
- Load default config from extension resources
- Load layer-specific configs from workspace
- Merge configs (defaults + overrides)
- Manage config cache
- Create LocalI2VisionAgent instances

**Key Methods:**
```typescript
initialize(): Promise<void>           // Load default config
createAgent(layer: VslfcLayer): Promise<LocalI2VisionAgent>
loadConfigForLayer(layer: VslfcLayer): Promise<AgentConfig>
mergeConfigs(defaults: AgentConfig, overrides: AgentConfig): AgentConfig
clearConfigCache(): void
```

### AgentConfig Interface

**Location:** `vscode-app/src/agent/AgentBridge.ts`

Type-safe interface matching the YAML structure.

## 🚀 Roll-Out Procedure

See: `.vision-ai/ROLL_OUT_PROCEDURE.md`

**Quick Start:**
```bash
# 1. Create .vision-ai directory
mkdir {workspace}/.vision-ai

# 2. Copy templates
cp .vision-ai/coding-agent.yaml {workspace}/.vision-ai/

# 3. Customize for your project
# Edit {workspace}/.vision-ai/coding-agent.yaml

# 4. Reload extension
# Ctrl+Shift+P → "Developer: Reload Window"
```

## 🧪 Testing

### Verify Config Loading

1. Open Output Channel (View → Output → i2-Vision)
2. Reload extension
3. Look for:
   ```
   [LocalAgentProvider] Default configuration loaded successfully
   [LocalAgentProvider] Loaded layer config from .../.vision-ai/coding-agent.yaml
   [LocalAgentProvider] Created agent: coding-agent-v1 (Coding Agent)
   ```

### Test Model Changes

1. Edit `.vision-ai/coding-agent.yaml`:
   ```yaml
   model:
     id: "qwen3:8b"  # Change model
   ```

2. Reload extension
3. Run a simple task
4. Verify output shows correct model

### Test Cache Clearing

1. Edit config file
2. Trigger agent creation (run any task)
3. Check output shows:
   ```
   [LocalAgentProvider] Config cache cleared: 1 entries removed
   [LocalAgentProvider] Loaded layer config from ...
   ```

## 🔧 Troubleshooting

### Config Not Found

**Error:** `Config file not found at .../.vision-ai/coding-agent.yaml`

**Solution:** Create the file or use defaults (no action needed)

### YAML Parse Error

**Error:** `Failed to parse YAML: ...`

**Solution:**
1. Validate YAML syntax (use online validator)
2. Check indentation (2 spaces, no tabs)
3. Check quotes around strings with special chars

### Model Not Available

**Error:** `model not found: minimax-m2.1:cloud`

**Solution:**
```bash
# Pull the model
ollama pull minimax-m2.1:cloud

# Or change to available model
# Edit .vision-ai/coding-agent.yaml
model:
  id: "qwen3:4b"
```

### Config Changes Not Applied

**Symptom:** Agent uses old config after editing YAML

**Solution:**
1. Reload extension (clears cache)
2. Check output channel for "Config cache cleared"
3. Verify file path is correct

## 📚 Related Documentation

- **Default Config Template:** `conf-agent-core/src/commonMain/resources/default-agent-config.yaml`
- **Roll-Out Procedure:** `.vision-ai/ROLL_OUT_PROCEDURE.md`
- **Layer Templates:** `.vision-ai/{layer}-agent.yaml`
- **Provider Implementation:** `vscode-app/src/agent/LocalAgentProvider.ts`

---

*Last updated: 2026-05-21*

