# Provider Rules System

## Overview

The provider rules system enables **editable, per-provider rule blocks** that dynamically compose the system prompt. Each LLM provider (Ollama, DeepSeek, Mistral, 3D LLM) can have its own set of customizable rules.

## Architecture

```
Settings UI (Webview)
    ↓
SettingsWebviewHost
    ↓
SettingsViewModel
    ↓
ProviderRulesStore (Persistence)
    ↓
ProviderRulesResolver (Cached Lookup)
    ↓
PromptBuilder (Dynamic Composition)
    ↓
AgentBridge.buildSystemPrompt()
```

## Components

### 1. ProviderRule Interface

```typescript
interface ProviderRule {
  /** Unique identifier (e.g., "max_edits") */
  id: string
  
  /** Human-readable description */
  description: string
  
  /** Provider this rule belongs to */
  providerId: string
  
  /** Default value */
  defaultValue: unknown
  
  /** Optional validation constraints */
  validation?: {
    min?: number
    max?: number
    enumValues?: string[]
  }
}
```

### 2. ProviderRulesStore

**Location**: `src/agent/settings/model/ProviderRulesStore.ts`

**Purpose**: Persistence layer for provider rules

**Features**:
- Loads/saves to `.vscode/i2-vision-provider-rules.json`
- Emits `onDidChange` events
- CRUD operations

**Methods**:
```typescript
getRulesForProvider(providerId: string): ProviderRule[]
setRulesForProvider(providerId: string, rules: ProviderRule[]): Promise<void>
addRule(providerId: string, rule: ProviderRule): Promise<void>
updateRule(providerId: string, ruleId: string, rule: ProviderRule): Promise<void>
deleteRule(providerId: string, ruleId: string): Promise<void>
```

### 3. ProviderRulesResolver

**Location**: `src/agent/settings/resolver/ProviderRulesResolver.ts`

**Purpose**: Cached rule lookup with change notifications

**Features**:
- Caches rules per provider for performance
- Invalidates cache on changes
- Emits `onRulesChanged` events

**Methods**:
```typescript
getRulesForProvider(providerId: string): ProviderRule[]
invalidateCache(): void
onRulesChanged(callback: () => void): Disposable
```

### 4. PromptBuilder

**Location**: `src/agent/settings/builder/PromptBuilder.ts`

**Purpose**: Dynamic prompt composition from rules

**Methods**:
```typescript
// Build from rules
build(options: PromptBuilderOptions): string

// Build with defaults (backward compatibility)
buildWithDefaults(options: PromptBuilderOptions): string
```

**Options**:
```typescript
interface PromptBuilderOptions {
  providerId: string
  templateVariables: Record<string, string>
  systemPromptTemplate: string
  rules: ProviderRule[]
}
```

## Data Flow

### Rule Edit Flow

```
User edits rule in Settings UI
    ↓
SettingsWebviewHost receives message
    ↓
SettingsViewModel.updateProviderRule()
    ↓
ProviderRulesStore.updateRule()
    ↓
Save to disk + emit onDidChange
    ↓
ProviderRulesResolver.invalidateCache()
    ↓
AgentBridge.cachedSystemPrompt = undefined
    ↓
Next buildSystemPrompt() rebuilds with new rules
```

### Provider Change Flow

```
User selects different provider
    ↓
AgentBridge.config.model.provider changes
    ↓
AgentBridge.invalidatePromptCache()
    ↓
Next buildSystemPrompt() uses new provider's rules
```

## Persistence

### File Location

- **Workspace**: `.vscode/i2-vision-provider-rules.json`
- **Global** (no workspace): `<storagePath>/i2-vision-provider-rules.json`

### File Format

```json
{
  "ollama": [
    {
      "id": "max_edits",
      "description": "Maximum apply_edits operations per call",
      "providerId": "ollama",
      "defaultValue": 50
    },
    {
      "id": "build_command",
      "description": "Command to run backend server",
      "providerId": "ollama",
      "defaultValue": "./gradlew :app:server:run"
    }
  ],
  "deepseek": [
    {
      "id": "temperature",
      "description": "Default temperature for DeepSeek",
      "providerId": "deepseek",
      "defaultValue": 0.7
    }
  ]
}
```

## Default Rules

When no provider-specific rules are defined, `PromptBuilder.buildWithDefaults()` uses hardcoded defaults:

```typescript
const defaultRules: ProviderRule[] = [
  {
    id: 'always_use_tools',
    description: 'ALWAYS use tool calls',
    providerId: providerId,
    defaultValue: 'Never describe plans without executing'
  },
  {
    id: 'backend_run_command',
    description: 'FOR "run backend" or "run server"',
    providerId: providerId,
    defaultValue: 'use run_terminal with gradlew :app:server:run (NOT run_build)'
  },
  {
    id: 'compilation_command',
    description: 'FOR compilation',
    providerId: providerId,
    defaultValue: 'use run_build with compileKotlin (source code ONLY, NO tests)'
  },
  {
    id: 'apply_edits_limit',
    description: 'apply_edits',
    providerId: providerId,
    defaultValue: 'MAX 50 edits per call. For large changes, use write_file instead'
  },
  // ... more default rules
]
```

## Change Signals

### Event Flow

```typescript
// ProviderRulesStore emits changes
rulesStore.onDidChange((rules) => {
  // Rules were modified
})

// ProviderRulesResolver forwards changes
resolver.onRulesChanged(() => {
  // Invalidate cache
  this.invalidateCache()
  
  // Notify AgentBridge
  agentBridge.cachedSystemPrompt = undefined
})

// AgentBridge invalidates prompt cache
agentBridge.invalidatePromptCache()
```

### Listener Registration

```typescript
// In AgentBridge constructor
this.disposables.push(
  this.rulesResolver.onRulesChanged(() => {
    this.cachedSystemPrompt = undefined
    this.log('System prompt invalidated due to rules change')
  })
)
```

## Backward Compatibility

### Fallback Behavior

The `buildWithDefaults()` method ensures backward compatibility:

1. **No rules defined** → Use hardcoded default rules
2. **Some rules defined** → Use defined rules only
3. **All rules defined** → Use all custom rules

### Migration

No migration needed - existing configurations continue working:

- Old `config.systemPromptRules` still supported
- Default rules match original behavior
- Users can gradually adopt provider-specific rules

## Usage Examples

### Adding a Custom Rule

```typescript
// Via SettingsViewModel
await viewModel.addProviderRule('ollama', {
  id: 'custom_rule',
  description: 'Custom instruction for Ollama',
  providerId: 'ollama',
  defaultValue: 'Always use TypeScript'
})
```

### Getting Rules for Provider

```typescript
// Via ProviderRulesResolver
const rules = resolver.getRulesForProvider('ollama')
```

### Building Prompt

```typescript
// Via PromptBuilder
const prompt = PromptBuilder.buildWithDefaults({
  providerId: 'ollama',
  templateVariables: { workspaceRoot: '/path/to/project' },
  systemPromptTemplate: 'You are an AI assistant...',
  rules: [...]
})
```

## Security Considerations

### Template Variable Injection

Template variables are interpolated using simple string replacement:

```typescript
prompt = prompt.replace(new RegExp(`\\$\\{${key}\\}`, 'g'), value)
```

**Recommendations**:
- Validate template variable values
- Don't allow user input in variable names
- Consider escaping special characters

### Rule Content Trust

Rule content is not sanitized - trust the user editing rules.

**Recommendations**:
- Add validation for rule content
- Limit rule description length
- Prevent circular references

## Testing

### Unit Tests

```typescript
describe('ProviderRulesStore', () => {
  it('should persist rules to disk', async () => {
    const store = new ProviderRulesStore(context)
    await store.setRulesForProvider('ollama', [rule1, rule2])
    
    const loaded = store.getRulesForProvider('ollama')
    expect(loaded.length).toBe(2)
  })
})

describe('PromptBuilder', () => {
  it('should interpolate template variables', () => {
    const prompt = PromptBuilder.build({
      providerId: 'ollama',
      templateVariables: { name: 'Test' },
      systemPromptTemplate: 'Hello ${name}',
      rules: []
    })
    expect(prompt).toContain('Hello Test')
  })
  
  it('should use defaults when no rules', () => {
    const prompt = PromptBuilder.buildWithDefaults({
      providerId: 'ollama',
      templateVariables: {},
      systemPromptTemplate: '...',
      rules: []
    })
    expect(prompt).toContain('--- RULES ---')
  })
})
```

### Integration Tests

```typescript
describe('Provider Rules Integration', () => {
  it('should rebuild prompt on rule change', async () => {
    const bridge = new AgentBridge(...)
    const initialPrompt = bridge.buildSystemPrompt(vars)
    
    await viewModel.addProviderRule('ollama', newRule)
    
    const newPrompt = bridge.buildSystemPrompt(vars)
    expect(newPrompt).not.toBe(initialPrompt)
  })
  
  it('should use different rules per provider', async () => {
    const ollamaRules = resolver.getRulesForProvider('ollama')
    const deepseekRules = resolver.getRulesForProvider('deepseek')
    
    expect(ollamaRules).not.toEqual(deepseekRules)
  })
})
```

## Related Documentation

- [AgentBridge Architecture](../architecture/agent-bridge.md)
- [Settings MVVM Guide](../guides/settings-mvvm.md)
- [Provider Selection Guide](../guides/provider-selection.md)
- [Settings Reference](../reference/settings-schema.md)
