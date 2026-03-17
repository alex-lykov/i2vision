# Intents

## Overview

Intents are high-level user goals that guide discovery through the i2vision layered architecture. The Intent system replaces the previous Strategy-based approach with a more flexible, intent-driven framework that adapts to user needs.

---

## Intent Goals

| Intent Goal | Description | Use Case |
|-------------|-------------|----------|
| **full_discovery** | Complete discovery of all layers (Code → Flow → Logic → Structure → Vision) | Initial project understanding |
| **refactoring** | Focus on refactoring opportunities and code quality improvements | Planning refactoring work |
| **audit** | Audit architecture, dependencies, and identify issues | Quality assessment |
| **flow_mapping** | Map flows and interactions across the system | Understanding system behavior |
| **doc_generation** | Generate comprehensive documentation | Documentation projects |
| **quick_overview** | Quick overview for exploration | Rapid project exploration |

---

## Intent Parameters

### Focus (Layers)

| Focus | Description |
|-------|-------------|
| **vision** | Vision layer - requirements, goals, constraints |
| **structure** | Structure layer - components, dependencies |
| **logic** | Logic layer - business rules, state machines |
| **flow** | Flow layer - interactions, sequences |
| **code** | Code layer - implementation, tests |

### Depth

| Depth | Description |
|-------|-------------|
| **browse** | Quick scan, minimal analysis |
| **standard** | Balanced analysis (default) |
| **deep** | Comprehensive analysis with LLM enhancement |

### Quality

| Quality | Description |
|---------|-------------|
| **quality** | High quality, strict filtering |
| **balanced** | Balanced approach (default) |
| **quantity** | Maximum discovery, minimal filtering |

---

## Running Intents

### Via CLI

```bash
cli.StrategyRunner --intent=<goal> [--focus=<layers>] [--depth=<depth>] [--quality=<quality>]
```

**Examples:**

Full discovery with standard depth:
```bash
cli.StrategyRunner --intent=full_discovery --depth=standard --verbose
```

Refactoring focused on code layer:
```bash
cli.StrategyRunner --intent=refactoring --focus=code --depth=deep
```

Quick overview:
```bash
cli.StrategyRunner --intent=quick_overview --depth=browse
```

Audit with quality focus:
```bash
cli.StrategyRunner --intent=audit --quality=quality
```

### Additional Flags

| Flag | Description |
|------|-------------|
| `--project=<path>` | Project root directory |
| `--module=<module>` | Target module to focus on |
| `--use-llm` | Enable LLM enhancement |
| `--export-docs` | Export documentation |
| `--verbose` | Verbose output |
| `--no-fail` | Don't fail on health < 50% |

---

## Intent Resolution

Intents are resolved to `DiscoveryStrategy` via `IntentResolutionEngine`:

1. **Parse Intent** - Extract goal, focus, depth, quality from CLI arguments
2. **Validate Intent** - Ensure all required parameters are valid
3. **Resolve to Strategy** - Map intent to appropriate DiscoveryStrategy configuration
4. **Execute Discovery** - Run DiscoveryPipeline with resolved strategy

The resolution engine uses mapping rules to convert high-level intents to concrete discovery parameters (max call depth, quality gates, link generation settings, etc.).

---

## Feature Flags

Intent system behavior can be controlled via environment variables:

| Flag | Description | Default |
|------|-------------|---------|
| `I2VISION_INTENT_ENABLED` | Enable Intent system | `true` |
| `I2VISION_INTENT_LOGGING` | Enable Intent resolution logging | `false` |
| `I2VISION_INTENT_STRICT` | Enable strict validation | `false` |
| `I2VISION_INTENT_ONLY` | Only allow Intent-based discovery | `false` |

---

## Migration from Strategies

The Intent system replaces the previous Strategy-based approach:

| Old Strategy-based Command | New Intent-based Command |
|---------------------------|--------------------------|
| `--strategyFile=vlsfc-discovery.yaml --scope=project --depth=deep` | `--intent=full_discovery --depth=deep` |
| `--strategyFile=vlsfc-discovery.yaml --scope=module --module_path=core/orchestrator` | `--intent=full_discovery --module=core/orchestrator` |
| `--strategyFile=vlsfc-discovery.yaml --depth=browse` | `--intent=quick_overview --depth=browse` |

See [Intent Migration Guide](./intent-migration-guide.md) for detailed migration instructions.

---

## Best Practices

1. **Start with quick_overview** - Get a quick understanding of the project
2. **Use appropriate depth** - Use `browse` for quick scans, `standard` for routine work, `deep` for comprehensive analysis
3. **Focus on relevant layers** - Use `--focus` to target specific layers when you know what you need
4. **Set quality appropriately** - Use `quality` for production analysis, `quantity` for exploration
5. **Enable LLM for deep analysis** - Use `--use-llm` with `--depth=deep` for enhanced analysis

---

## Common Workflows

### Initial Project Understanding
```bash
cli.StrategyRunner --intent=quick_overview --depth=browse --verbose
cli.StrategyRunner --intent=full_discovery --depth=standard --verbose
```

### Refactoring Preparation
```bash
cli.StrategyRunner --intent=refactoring --focus=code --depth=deep --verbose
```

### Quality Audit
```bash
cli.StrategyRunner --intent=audit --quality=quality --depth=standard --verbose
```

### Flow Analysis
```bash
cli.StrategyRunner --intent=flow_mapping --focus=flow --depth=deep --verbose
```

### Documentation Generation
```bash
cli.StrategyRunner --intent=doc_generation --export-docs --verbose
```

---

## Intent vs Strategy Comparison

| Aspect | Strategy-based | Intent-based |
|--------|---------------|--------------|
| **Configuration** | YAML files with complex parameters | Simple CLI flags |
| **Flexibility** | Fixed strategies per YAML file | Dynamic intent resolution |
| **User Experience** | Requires knowledge of strategy files | Natural language-like goals |
| **Adaptability** | Static configuration | Adapts to user needs |
| **Maintenance** | Multiple YAML files to maintain | Single resolution engine |

---

## Implementation Details

### DiscoveryIntent Data Class

Located in `intent-parser/src/main/kotlin/com/i2vision/intent/DiscoveryIntent.kt`

```kotlin
data class DiscoveryIntent(
    val goal: IntentGoal,
    val focus: List<IntentFocus>,
    val depth: IntentDepth,
    val quality: IntentQuality
)
```

### IntentResolutionEngine

Located in `intent-parser/src/main/kotlin/com/i2vision/intent/IntentResolutionEngine.kt`

Converts `DiscoveryIntent` to `DiscoveryStrategy` using mapping rules and heuristics.

### IntentFeatureFlags

Located in `intent-parser/src/main/kotlin/com/i2vision/intent/IntentFeatureFlags.kt`

Controls Intent system behavior via environment variables.

---

## Documentation

- [Intent Migration Guide](../dev-docs/intent-migration-guide.md) - Detailed migration instructions
- [Adaptive Discovery Refactoring Plan](../dev-docs/adaptive-discovery-refactoring-plan.md) - Implementation details
- [Test Plan](../dev-docs/TEST_PLAN_DISCOVERY.md) - Testing procedures
