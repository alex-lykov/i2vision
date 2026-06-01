# Quick Start: Using i2vision Commands

## ✅ Commands Available

The following commands have been successfully added to the extension:

1. **`i2vision.init`** - Initialize VSLFC project structure
2. **`i2vision_discover`** - Run full VSLFC discovery via agent

---

## 🚀 Command 1: i2vision.init

### How to Use

#### Step 1: Reload VSCode Extension

After the extension rebuilds, you need to reload VSCode:

1. Press `Ctrl+Shift+P` (Windows/Linux) or `Cmd+Shift+P` (macOS)
2. Type: `Developer: Reload Window`
3. Press Enter

#### Step 2: Run i2vision.init

**Method 1: Command Palette**
```
1. Press Ctrl+Shift+P
2. Type: "i2vision.init"
3. Select: "i2vision.init: Initialize VSLFC Structure (i2vision.init)"
4. Press Enter
```

**Method 2: Search by Title**
```
1. Press Ctrl+Shift+P
2. Type: "Initialize VSLFC"
3. Select the command
4. Press Enter
```

#### Step 3: Verify

After running the command, check your project root:

```
your-project/
└── .vision-ai/          ← Should be created
    ├── .version
    ├── vision/
    ├── code/
    ├── logic/
    ├── structure/
    ├── flow/
    ├── data/
    ├── api/
    └── config/
        └── cli.yaml
```

### What the Command Does

When you run `i2vision.init`:

1. ✅ Creates `.vision-ai` directory in project root
2. ✅ Creates 7 layer directories (vision, code, logic, structure, flow, data, api)
3. ✅ Generates agent config templates for each layer
4. ✅ Generates contract templates for each layer
5. ✅ Creates control-plane directories (config, clusters, overrides, etc.)
6. ✅ Creates CLI configuration file (`.vision-ai/config/cli.yaml`)
7. ✅ Creates requirements directory for vision layer
8. ✅ Writes version file (`.vision-ai/.version`)

---

## 🔍 Command 2: i2vision_discover (Agent Tool)

### How to Use

The `i2vision_discover` tool is available to the **Code Agent** for autonomous project discovery.

#### Via Agent Chat

Open the i2-Vision agent panel and use these prompts:

**Full Discovery:**
```
Run i2vision_discover on this project with intent: full_discovery
```

**Quick Overview:**
```
Use i2vision_discover to get a quick overview of the project
```

**Architecture Audit:**
```
Run i2vision_discover with intent: architecture_audit
```

**Flow Mapping:**
```
Discover all flows using i2vision_discover
```

#### Tool Parameters

| Parameter | Required | Description |
|-----------|----------|-------------|
| `path`    | Yes      | Project root path (default: current workspace) |
| `intent`  | No       | Discovery intent: `full_discovery`, `quick_overview`, `architecture_audit`, or `flow_mapping` |

### What It Does

When the agent executes `i2vision_discover`:

1. ✅ Invokes the i2vision CLI discovery engine
2. ✅ Analyzes architecture patterns across all modules
3. ✅ Identifies business rules and logic flows
4. ✅ Maps components and their relationships
5. ✅ Returns structured JSON results to the agent
6. ✅ Agent presents findings in natural language

### Example Agent Response

```
I've run a full VSLFC discovery on your project. Here's what I found:

**Architecture:**
- 12 modules detected
- Layer distribution: Vision (3), Code (5), Logic (4)

**Key Components:**
- AgentBridge: Handles tool execution
- CLI Integration: Runs discovery commands
- Storage Core: Manages VSLFC artifacts

**Flows Detected:**
- Request processing flow
- Discovery pipeline
- Agent communication flow
```

---

## 🛠️ Troubleshooting

### i2vision.init Command Not Showing

If `i2vision.init` doesn't appear:

1. **Check extension is running**:
   - Look for "i2-Vision" in the Activity Bar (left sidebar)
   - Check Output panel → "i2-Vision" channel

2. **Verify package.json**:
   ```bash
   # In vscode-app directory
   type package.json | findstr "i2vision.init"
   ```
   Should show:
   - `"onCommand:i2vision.init"` in activationEvents
   - `"command": "i2vision.init"` in contributes.commands

3. **Rebuild extension**:
   ```bash
   cd D:\proj\AI\i2-vision\vscode-app
   npm run compile
   ```

4. **Check compiled output**:
   ```bash
   type out\extension.js | findstr "i2vision.init"
   ```

### i2vision_discover Not Being Used

If the agent doesn't use `i2vision_discover`:

1. **Be explicit in your prompt**:
   - ✅ "Run i2vision_discover on this project"
   - ❌ "Discover this project" (too vague)

2. **Check tool availability**:
   ```bash
   type out\agent\AgentBridge.js | findstr "i2vision_discover"
   ```
   Should show both tool definition and handler

3. **Verify CLI integration**:
   - Ensure `cliIntegration.ts` has `runDiscovery()` method
   - Check Output panel for CLI execution logs

### Extension Not Activating

If the extension doesn't activate:

1. Open a project folder in VSCode
2. Check Output panel → Select "i2-Vision" from dropdown
3. Look for: "i2-Vision extension is now active"

### Permission Errors

If you see permission errors:

1. Make sure you have write access to the project directory
2. Run VSCode as Administrator (Windows) if needed
3. Check the Output channel for specific error messages

---

## 📚 Next Steps

After initialization:

1. **Configure CLI**: Edit `.vision-ai/config/cli.yaml` with your CLI JAR path
2. **Customize Agents**: Edit layer-specific agent configs
3. **Define Contracts**: Update contract files for your project needs
4. **Add Requirements**: Create requirement files in `.vision-ai/vision/requirements/`
5. **Run Discovery**: Use agent with `i2vision_discover` to analyze architecture

---

## 📖 Related Documentation

- 📄 [DOC-7: How to Run i2vision.init - User Guide](../backlog/docs/DOC-7.md)
- 📄 [RolloutManager Implementation](../storage-core/src/main/kotlin/com/i2vision/storage/impl/RolloutManager.kt)
- 📄 [AgentBridge Source](./src/agent/AgentBridge.ts)
- 📄 [CLI Integration](./src/cliIntegration.ts)
