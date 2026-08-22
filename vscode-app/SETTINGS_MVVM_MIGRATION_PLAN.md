# Settings MVVM Migration Plan

> Goal: refactor the agent settings implementation into an extensible MVVM architecture with user-manageable options.

## 1. Current State

Relevant files today:

- `src/agent/SettingsPanel.ts`
  - Owns VSCode webview panel lifecycle.
  - Builds a large hardcoded HTML string.
  - Handles save/reset/import/export messages.
  - Previously had duplicate `onDidReceiveMessage` registration, already fixed by registering in the constructor exactly once.

- `src/agent/AgentSettings.ts`
  - Contains `AgentSettings` data shape.
  - Contains `AgentSettingsManager` persistence logic.
  - Contains `validateSettings` validation logic.

Problems:

- Adding a new setting requires changes in the model type, HTML template, webview JavaScript, and validation code.
- The UI is tightly coupled to a specific settings shape.
- User-defined settings are not supported.
- Business logic is hard to unit test because it lives in the VSCode panel class.
- There is no single source of truth for option metadata.

## 2. Target Architecture

```text
src/agent/settings/
├── model/
│   ├── SettingDescriptor.ts
│   ├── SettingValue.ts
│   ├── SettingsSchema.ts
│   └── SettingsStore.ts
├── viewmodel/
│   ├── SettingsViewModel.ts
│   ├── SettingSectionViewModel.ts
│   └── SettingItemViewModel.ts
├── view/
│   ├── SettingsPanel.ts
│   ├── settingsViewBuilder.ts
│   └── settingsWebviewProtocol.ts
├── registry/
│   ├── SettingsRegistry.ts
│   ├── builtinSettings.ts
│   └── customSettingsLoader.ts
└── validation/
    └── SettingValidators.ts
```

Responsibilities:

- Model: option descriptors, runtime values, schema, persistence.
- ViewModel: observable state, commands, validation results, dirty tracking.
- View: VSCode webview host plus generic schema-driven renderer.
- Registry: built-in and custom option registration.
- Validation: reusable validators referenced by descriptors.

## 3. Key Principles

1. Descriptor-driven: every setting is described by metadata, not hardcoded UI.
2. Thin webview host: `SettingsPanel` only manages the panel and message bridge.
3. User-extensible: custom settings can be loaded from workspace config files.
4. Testable: viewmodel and registry are plain TypeScript, testable without VSCode.
5. Incremental migration: existing behavior must remain intact until cutover.

## 4. Data Model

### `SettingDescriptor`

```ts
export interface SettingDescriptor {
  key: string;
  type: 'boolean' | 'number' | 'string' | 'enum' | 'range';
  defaultValue: unknown;
  label: string;
  description?: string;
  group: string;
  order: number;
  validator?: SettingValidator;
  enumValues?: ReadonlyArray<{ value: string; label: string }>;
  min?: number;
  max?: number;
  step?: number;
  scope?: 'workspace' | 'global';
  visibility?: 'basic' | 'advanced';
  tags?: string[];
}
```

### `SettingValue`

```ts
export interface SettingValue {
  key: string;
  value: unknown;
  isDirty: boolean;
  validationErrors: string[];
  isValid: boolean;
}
```

### `SettingsSchema`

```ts
export interface SettingsSection {
  id: string;
  title: string;
  order: number;
  settings: SettingDescriptor[];
}

export interface SettingsSchema {
  sections: SettingsSection[];
}
```

## 5. ViewModel API

### `SettingsViewModel`

```ts
export class SettingsViewModel {
  readonly state: {
    schema: SettingsSchema;
    values: Record<string, SettingValue>;
    isDirty: boolean;
    isSaving: boolean;
  };

  getValue(key: string): SettingValue | undefined;
  setValue(key: string, value: unknown): void;
  resetGroup(groupId: string): void;
  resetAll(): void;
  save(): Promise<void>;
  exportJson(): string;
  importJson(json: string): Promise<void>;

  onDidChange: vscode.Event<void>;
  onDidSave: vscode.Event<void>;
  onDidValidationChange: vscode.Event<void>;
}
```

### `SettingItemViewModel`

```ts
export class SettingItemViewModel {
  readonly descriptor: SettingDescriptor;
  readonly value: SettingValue;

  update(newValue: unknown): void;
  reset(): void;
}
```

### `SettingSectionViewModel`

```ts
export class SettingSectionViewModel {
  readonly id: string;
  readonly title: string;
  readonly items: SettingItemViewModel[];
}
```

## 6. View Protocol

Webview-to-host messages:

```ts
type SettingsWebviewMessage =
  | { type: 'settingChanged'; key: string; value: unknown }
  | { type: 'resetGroup'; group: string }
  | { type: 'resetAll' }
  | { type: 'save' }
  | { type: 'export' }
  | { type: 'import'; json: string }
  | { type: 'close' };
```

Host-to-webview messages:

```ts
type SettingsHostMessage =
  | { type: 'settingsState'; state: SettingsState }
  | { type: 'settingsSaved' }
  | { type: 'settingsError'; message: string };
```

## 7. Migration Phases

### Phase 0: Baseline verification

Tasks:

- Ensure current `npm run compile` passes.
- Run existing settings UI manually to confirm current behavior.
- Record current settings keys and defaults.

Exit criteria:

- Current build is green.
- Current behavior is understood.

### Phase 1: Create model layer

Files to add:

- `src/agent/settings/model/SettingDescriptor.ts`
- `src/agent/settings/model/SettingValue.ts`
- `src/agent/settings/model/SettingsSchema.ts`
- `src/agent/settings/model/SettingsStore.ts`

Tasks:

- Define types above.
- Implement `SettingsStore` around the existing `AgentSettingsManager`.
- Keep `AgentSettingsManager` as initial storage backend.

Exit criteria:

- Model types compile.
- `SettingsStore` can load and save existing `AgentSettings` values.

### Phase 2: Create registry

Files to add:

- `src/agent/settings/registry/SettingsRegistry.ts`
- `src/agent/settings/registry/builtinSettings.ts`
- `src/agent/settings/registry/customSettingsLoader.ts`

Tasks:

- Implement `SettingsRegistry` with register/unregister/getSchema.
- Move every current hardcoded setting into `builtinSettings.ts` as descriptors.
- Implement custom settings loader that reads `$workspace/.vscode/i2vision/settings.json` or `$workspace/.vscode/i2vision/custom-settings.json`.
- Convert custom JSON sections into descriptors.

Exit criteria:

- All current settings are registered as descriptors.
- Custom JSON can register additional settings.

### Phase 3: Create validation module

Files to add:

- `src/agent/settings/validation/SettingValidators.ts`

Tasks:

- Extract current `validateSettings` rules into composable validators:
  - `minMaxValidator`
  - `enumValidator`
  - `booleanValidator`
  - `stringValidator`
- Allow descriptors to reference validators.

Exit criteria:

- Existing validation behavior is preserved.
- New descriptors can attach validators.

### Phase 4: Create viewmodels

Files to add:

- `src/agent/settings/viewmodel/SettingItemViewModel.ts`
- `src/agent/settings/viewmodel/SettingSectionViewModel.ts`
- `src/agent/settings/viewmodel/SettingsViewModel.ts`

Tasks:

- Implement VM state and commands as specified above.
- Connect VM to registry and store.
- Emit change events on updates.

Exit criteria:

- VMs are testable in plain TypeScript.
- Save/import/export/reset work through VM without webview.

### Phase 5: Create generic view renderer

Files to add:

- `src/agent/settings/view/settingsViewBuilder.ts`
- `src/agent/settings/view/settingsWebviewProtocol.ts`

Tasks:

- Build HTML generator that consumes `SettingsSchema` and current values.
- Implement control renderer:
  - boolean -> checkbox
  - number/range -> slider or number input
  - enum -> dropdown
  - string -> text input
- Generate webview JavaScript that posts generic messages only.

Exit criteria:

- Renderer emits usable HTML for all built-in descriptors.
- No per-setting HTML blocks remain.

### Phase 6: Rewrite `SettingsPanel` and cutover

Files to modify:

- `src/agent/settings/view/SettingsPanel.ts`
- Optionally remove old `src/agent/SettingsPanel.ts` usage from `extension.ts`.

Tasks:

- Create new panel host that:
  - creates webview
  - instantiates `SettingsViewModel`
  - registers `onDidReceiveMessage` once
  - forwards messages to VM commands
  - listens to VM events and posts state updates
- Update extension command registration to open new panel.
- Remove old monolithic HTML generation or archive it.

Exit criteria:

- Settings panel works with new architecture.
- Save/reset/import/export still function.
- Build passes and tests are added.

## 8. User-Manageable Options

Example `$workspace/.vscode/i2vision/settings.json`:

```json
{
  "sections": [
    {
      "id": "company",
      "title": "Company",
      "settings": [
        {
          "key": "company.enableFoo",
          "type": "boolean",
          "default": false,
          "label": "Enable Foo",
          "description": "Company-specific behavior"
        }
      ]
    }
  ]
}
```

Behavior:

- Loaded at extension activation or when settings panel opens.
- Custom options are appended to the schema.
- Custom values use the same persistence layer as built-in values.
- Invalid custom descriptors are skipped and reported in the UI or output channel.

## 9. Test Plan

Unit tests:

- `SettingsRegistry` register/unregister/ordering.
- `SettingsStore` load/save roundtrip.
- `SettingValidators` each rule.
- `SettingsViewModel`:
  - setValue marks dirty
  - invalid values prevent save
  - resetGroup resets only group
  - resetAll resets all
  - importJson updates state
  - exportJson returns current state
- `settingsViewBuilder`:
  - renders each control type
  - escapes labels and descriptions

Integration tests:

- Custom settings file registers additional options.
- Webview protocol messages map to VM commands.
- Panel lifecycle disposes event listeners.

Manual tests:

- Open settings panel.
- Modify built-in setting.
- Modify custom setting.
- Save and reload extension host.
- Reset group and verify unchanged groups remain.
- Export and import a JSON blob.

## 10. Risks and Mitigations

| Risk | Mitigation |
|---|---|
| Existing settings lost during migration | Keep `AgentSettingsManager` as storage backend initially; add fallback reads |
| Custom JSON malformed | Graceful error handling and reporting |
| Large schema rendering slow | Render lazily or paginate advanced settings |
| VSCode message contract mismatch | Typed protocol helpers |
| Duplicate listener regression | Register listener in constructor exactly once |
| Scope creep from user-defined settings | Keep custom settings limited to descriptor JSON, no code execution |

## 11. Rollback Plan

- Keep old `AgentSettings.ts` and `SettingsPanel.ts` until Phase 6 succeeds.
- Use a feature flag to switch between old and new panel.
- If critical issue occurs, point command registration back to old `SettingsPanel.show`.
- Revert commits phase by phase.

## 12. Acceptance Criteria

- Every current settings option is represented by a descriptor.
- `SettingsPanel` no longer contains per-setting HTML blocks.
- New options require only a descriptor, no view changes.
- Custom settings can be added through workspace JSON without recompiling.
- Unit tests cover viewmodel, registry, validators, and renderer.
- `npm run compile` succeeds.
- Manual smoke test passes for save/reset/import/export.
