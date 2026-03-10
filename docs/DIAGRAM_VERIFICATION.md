# Flow Diagram Verification

## ✅ All Participants Exist

All participants in the diagram are actual classes/components in the codebase:

1. ✅ **Main.kt** - `launcher/src/main/kotlin/Main.kt` (contains `main()` function)
2. ✅ **AgentLauncherImpl** - `launcher/src/main/kotlin/Main.kt` (class)
3. ✅ **ConfigLoader** - `core/config/src/main/kotlin/com/alyk/ai/koog/config/Config.kt` (object)
4. ✅ **UnifiedModelManager** - `launcher/src/main/kotlin/core/UnifiedModelManager.kt`
5. ✅ **ModelRegistry** - `models/wrappers/src/main/kotlin/com/alyk/ai/koog/models/wrappers/ModelRegistry.kt`
6. ✅ **CloudModelRegistry** - `models/cloud/src/main/kotlin/com/alyk/ai/koog/models/cloud/CloudModelRegistry.kt`
7. ✅ **AgentOrchestrator** - `core/orchestrator/src/main/kotlin/com/alyk/ai/koog/core/orchestrator/AgentOrchestrator.kt`
8. ✅ **AgentRouter** - `core/orchestrator/src/main/kotlin/com/alyk/ai/koog/core/orchestrator/router/AgentRouter.kt`
9. ✅ **ImplementationAgent** - `core/orchestrator/src/main/kotlin/com/alyk/ai/koog/core/orchestrator/agents/ImplementationAgent.kt`
10. ✅ **ContextProvider** - `context/src/main/kotlin/com/alyk/ai/koog/context/provider/ContextProvider.kt`
11. ✅ **HierarchyBuilder** - `context/src/main/kotlin/com/alyk/ai/koog/context/hierarchy/HierarchyBuilder.kt`
12. ✅ **DecisionEngine** - `switching/src/main/kotlin/com/alyk/ai/koog/switching/decision/DecisionEngine.kt`
13. ✅ **ModelSwitchControl** - `switching/src/main/kotlin/com/alyk/ai/koog/switching/decision/ModelSwitchControl.kt`
14. ✅ **PerformanceMonitor** - `switching/src/main/kotlin/com/alyk/ai/koog/switching/monitor/PerformanceMonitor.kt`
15. ✅ **AgentClient** - `launcher/src/main/kotlin/core/AgentClient.kt`
16. ✅ **McpIntegration** - `core/orchestrator/src/main/kotlin/com/alyk/ai/koog/core/orchestrator/mcp/McpIntegration.kt`
17. ✅ **WorkspaceFactory** - `core/orchestrator/src/main/kotlin/com/alyk/ai/koog/core/orchestrator/mcp/workspace/WorkspaceFactory.kt`
18. ✅ **ToolRegistry** - From Koog library (`ai.koog.agents.core.tools.ToolRegistry`)
19. ✅ **ToolUsageTracker** - `core/orchestrator/src/main/kotlin/com/alyk/ai/koog/core/orchestrator/tools/ToolUsageTracker.kt`
20. ✅ **KoogToolRegistryBuilder** - `core/orchestrator/src/main/kotlin/com/alyk/ai/koog/core/orchestrator/tools/KoogToolRegistryBuilder.kt`

## ⚠️ Flow Discrepancies Found

### Issue 1: Config Loading Location
**Diagram shows:**
```
Launcher -> Config: load configuration
```

**Actual code:**
- `ConfigLoader.load()` is called in `Main.kt` (line 30), not inside `AgentLauncherImpl.initialize()`
- The `config` is already loaded when `AgentLauncherImpl` is created
- `initialize()` receives `Config` as a parameter

**Fix needed:** Update diagram to show Config loading happens in Main.kt before creating Launcher.

### Issue 2: Component Creation Order
**Diagram shows:**
```
Launcher -> ModelManager: create UnifiedModelManager
Launcher -> LocalRegistry: create ModelRegistry
Launcher -> CloudRegistry: create CloudModelRegistry
```

**Actual code:**
- These are created as properties in `AgentLauncherImpl` constructor/init
- They're created before `initialize()` is called
- The order in the diagram is correct, but they're created earlier (as class properties)

**Fix needed:** Clarify that these are created during AgentLauncherImpl construction, not during initialize().

### Issue 3: Model Selection Timing
**Diagram shows:**
```
Launcher -> ModelManager: scanAllModels()
...
Launcher -> Orchestrator: initialize(projectPath)
```

**Actual code:**
- `scanAllModels()` is called inside `initialize()`
- Model selection (smallest model) happens inside `initialize()`
- `orchestrator.initialize()` is called after model selection

**Fix needed:** The flow is correct, but the model selection step should be more explicit.

## 📝 Recommended Updates

1. **Add Config loading step in Main.kt** before creating Launcher
2. **Clarify component creation** happens during AgentLauncherImpl construction
3. **Add explicit model selection step** showing smallest model selection
4. **Update participant name** from "Main.kt" to "Main (launcher)" for clarity
