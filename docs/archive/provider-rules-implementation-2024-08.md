# Provider-Scoped Rules Implementation

## Overview

This document describes the implementation of editable per-provider rules with dynamic prompt composition from the selected provider.

## Architecture

### Data Model

**ProviderRule** (`src/agent/settings/model/ProviderRule.ts`)
```typescript
interface ProviderRule {
  id: string;           // Unique identifier (e.g., "maxTokens")
  description: string;  // Human-readable description
  providerId: string;   // Provider ID (e.g., "ollama", "deepseek")
  defaultValue: unknown; // Default value for the rule
  validation?: {        // Optional validation constraints
    min?: number;
    max?: number;
    enumValues?: string[];
  };
}
```

**ProviderRules** (`src/agent/settings/model/ProviderRules.ts`)
```typescript
interface ProviderRules {
  [providerId: string]: ProviderRule[];  // Map of providerId → rules
}
```

### Components

#### 1. ProviderRulesStore
**Location:** `src/agent/settings/model/ProviderRulesStore.ts`

Persistence layer for provider-specific rules. Features:
- Loads/saves rules to `.vscode/i2-vision-provider-rules.json`
- Emits `onDidChange` events when rules are modified
- CRUD operations: `getRulesForProvider`, `setRulesForProvider`, `addRule`, `updateRule`, `deleteRule`

#### 2. ProviderRulesResolver
**Location:** `src/agent/settings/resolver/ProviderRulesResolver.ts`

Resolver that provides a clean API for `AgentBridge` to retrieve rules:
- Caches rules per provider for performance
- `getRulesForProvider(providerId)` - returns cached rules
- `invalidateCache()` - clears cache when rules change
- `onRulesChanged(callback)` - subscribe to rule changes

#### 3. PromptBuilder
**Location:** `src/agent/settings/builder/PromptBuilder.ts`

Assembles system prompts from provider-specific rules:
- `build(options)` - builds prompt from template + rules
- `buildWithDefaults(options)` - uses hardcoded defaults if no rules defined
- Interpolates template variables (`${variableName}`)
- Appends rules section after template

#### 4. SettingsViewModel (Updated)
**Location:** `src/agent/settings/viewmodel/SettingsViewModel.ts`

Extended with:
- `ProviderRulesStore` integration
- Change signals: `onRulesChanged`, `onProviderChanged`
- CRUD methods: `getProviderRules`, `setProviderRules`, `addProviderRule`, `updateProviderRule`, `deleteProviderRule`

#### 5. SettingsWebviewHost (Updated)
**Location:** `src/agent/settings/view/SettingsWebviewHost.ts`

Extended with message handlers for:
- `getProviderRules` - fetch rules for a provider
- `setProviderRules` - replace all rules
- `addProviderRule` - add single rule
- `updateProviderRule` - update existing rule
- `deleteProviderRule` - remove rule

#### 6. AgentBridge (Updated)
**Location:** `src/agent/AgentBridge.ts`

Changes:
- Added `promptBuilder: PromptBuilder` field
- Added `rulesResolver: ProviderRulesResolver` field
- Added `cachedSystemPrompt` for performance
- Updated `buildSystemPrompt()` to use `PromptBuilder.buildWithDefaults()`
- Added `invalidatePromptCache()` method
- Wired up change signals in constructor
- Updated `dispose()` to clean up disposables

## Data Flow

```
User edits rules in Settings UI
        ↓
SettingsWebviewHost receives message
        ↓
SettingsViewModel.setProviderRules()
        ↓
ProviderRulesStore.setRulesForProvider()
        ↓
ProviderRulesStore saves to disk + emits onDidChange
        ↓
ProviderRulesResolver.invalidateCache() + fires onRulesChanged
        ↓
AgentBridge.cachedSystemPrompt = undefined
        ↓
Next call to buildSystemPrompt() rebuilds from rules
```

## Provider Change Flow

```
User selects different provider
        ↓
AgentBridge.config.model.provider changes
        ↓
AgentBridge.invalidatePromptCache() called
        ↓
Next call to buildSystemPrompt() uses new provider's rules
```

## Backward Compatibility

The `PromptBuilder.buildWithDefaults()` method provides backward compatibility:
- If no provider-specific rules are defined, uses hardcoded default rules
- Default rules match the original `buildSystemPrompt()` behavior
- Existing configurations continue to work without migration

## File Locations

| Component | File |
|-----------|------|
| ProviderRule interface | `vscode-app/src/agent/settings/model/ProviderRule.ts` |
| ProviderRules interface | `vscode-app/src/agent/settings/model/ProviderRules.ts` |
| ProviderRulesStore | `vscode-app/src/agent/settings/model/ProviderRulesStore.ts` |
| ProviderRulesResolver | `vscode-app/src/agent/settings/resolver/ProviderRulesResolver.ts` |
| PromptBuilder | `vscode-app/src/agent/settings/builder/PromptBuilder.ts` |
| SettingsViewModel | `vscode-app/src/agent/settings/viewmodel/SettingsViewModel.ts` |
| SettingsWebviewHost | `vscode-app/src/agent/settings/view/SettingsWebviewHost.ts` |
| AgentBridge | `vscode-app/src/agent/AgentBridge.ts` |

## Persistence

Rules are persisted to:
- Workspace: `.vscode/i2-vision-provider-rules.json` (if workspace is open)
- Global: `<storagePath>/i2-vision-provider-rules.json` (no workspace)

## Change Signals

| Signal | Emitter | Purpose |
|--------|---------|---------|
| `onRulesChanged` | `ProviderRulesStore` | Rules were modified |
| `onRulesChanged` | `SettingsViewModel` | Forwarded from store |
| `onProviderChanged` | `SettingsViewModel` | Active provider changed |
| Cache invalidation | `AgentBridge` | Prompt rebuild triggered |

## Testing Checklist

- [ ] Unit test: `ProviderRulesStore` CRUD operations
- [ ] Unit test: `PromptBuilder.build()` with various rule sets
- [ ] Unit test: `PromptBuilder.buildWithDefaults()` fallback
- [ ] Integration test: Rule edit → prompt rebuild
- [ ] Integration test: Provider switch → prompt rebuild
- [ ] Integration test: Settings persistence across reloads
- [ ] Security test: Template variable injection prevention

## Future Enhancements

1. **Rule validation UI** - Show validation errors in real-time
2. **Rule templates** - Predefined rule sets for common scenarios
3. **Rule import/export** - Share rule configurations
4. **Rule versioning** - Track changes over time
5. **Provider-specific templates** - Different prompt templates per provider

## Security Considerations

- Template variables are interpolated using simple string replacement
- Rule content is not sanitized - trust the user editing rules
- Consider adding validation for rule content in future iterations
