# i2-vision Agent Settings — MVVM Migration Plan

## Status: IN PROGRESS

## 1. Problem Statement

Current settings UI (`src/agent/SettingsPanel.ts`) is a monolithic webview host:

- HTML generation is inline and tightly coupled to `AgentSettings`
- Message handling and rendering logic are mixed in one class
- Adding a new setting requires editing at least 4 places: interface, defaults, HTML string, and save/load logic
- No reusable descriptor model; UI cannot be generated from metadata
- Hard to unit test without instantiating a real VS Code webview panel
- No user-extensible custom options beyond hard-coded settings

## 2. Goal

Refactor the settings system into an extensible, descriptor-driven MVVM architecture with:

- **Model**: typed descriptors, schema, persistent store
- **ViewModel**: reactive, UI-agnostic state and actions
- **View**: thin webview host + metadata-driven HTML renderer
- **Registry**: built-in + user-defined settings sections and descriptors
- **Protocol**: typed webview ↔ extension-host messages
- **Migration path**: incremental, preserving existing settings data

## 3. Target Directory Structure

```
 vscode-app/src/agent/settings/
 ├── model/
 │   ├── SettingDescriptor.ts
 │   ├── SettingValue.ts
 │   ├── SettingsSchema.ts
 │   └── SettingsStore.ts
 ├── registry/
 │   ├── SettingsRegistry.ts
 │   ├── builtinSettings.ts
 │   └── customSettingsLoader.ts
 ├── validation/
 │   └── SettingValidators.ts
 ├── viewmodel/
 │   ├── SettingItemViewModel.ts
 │   ├── SettingSectionViewModel.ts
 │   └── SettingsViewModel.ts
 ├── view/
 │   ├── settingsWebviewProtocol.ts
 │   ├── settingsViewBuilder.ts
 │   └── SettingsWebviewHost.ts
 └── index.ts
```

## 4. Key Concepts

### 4.1 SettingDescriptor

Every user-manageable setting is described by a single descriptor:

```ts
interface SettingDescriptor {
  key: string;
  section: string;
  label: string;
  description?: string;
  type: 'boolean' | 'number' | 'string' | 'enum' | 'integer';
  defaultValue: SettingValue;
  validation?: {
    min?: number;
    max?: number;
    step?: number;
    pattern?: string;
    enumValues?: string[];
    required?: boolean;
  };
  ui?: {
    order?: number;
    group?: string;
    placeholder?: string;
  };
}
```

### 4.2 SettingsSchema

A schema is a section name + ordered descriptors. The UI is rendered entirely from schemas.

### 4.3 SettingsStore

Owns the current settings object and persistence:

- loads defaults + workspace JSON + VS Code configuration overrides
- exposes `getValue`, `setValue`, `reset`, `onDidChange`
- writes `.vscode/i2-vision-settings.json`
- keeps legacy `AgentSettingsManager` data compatible

### 4.4 SettingsViewModel

- receives schema(s) and store
- builds section/item view models
- exposes `sections: SettingSectionViewModel[]`
- handles `setValue`, `resetAll`, `export`, `import`
- emits change event for the view

### 4.5 View

- `settingsWebviewProtocol.ts`: typed message types (`saveSettings`, etc.)
- `settingsViewBuilder.ts`: takes view model snapshot and returns HTML
- `SettingsWebviewHost.ts`: thin VS Code webview panel wrapper; registers one `onDidReceiveMessage` listener and delegates to the view model

## 5. Migration Phases

### Phase 0 — Baseline (DONE)
- Commit `2c99e3e` fixed duplicate listener registration in `SettingsPanel`.

### Phase 1 — Model + Store (DONE)
- Add `SettingDescriptor`, `SettingValue`, `SettingsSchema`, `SettingsStore`.
- Compiles successfully with existing code untouched.

### Phase 2 — Registry + Built-in Settings (DONE)
- Add `SettingsRegistry`, `builtinSettings.ts`.
- All current `AgentSettings` fields are covered by descriptors.

### Phase 3 — Validation + ViewModels (DONE)
- Add `SettingValidators`, `SettingItemViewModel`, `SettingSectionViewModel`, `SettingsViewModel`.

### Phase 4 — View Protocol + HTML Builder (DONE)
- Add `settingsWebviewProtocol.ts`, `settingsViewBuilder.ts`.

### Phase 5 — Webview Host + Cutover (IN PROGRESS)
- Add `SettingsWebviewHost.ts` (thin wrapper).
- Update `src/agent/SettingsPanel.ts` to delegate rendering and message handling to the new MVVM components, or replace with new host.
- Update `src/extension.ts` command `i2vision.settings` to use `SettingsWebviewHost`.

### Phase 6 — User-Manageable Custom Settings + Validation Cleanup
- Add `customSettingsLoader.ts` reading `customSettings` section from a workspace JSON file.
- Support user-defined descriptors without recompiling.
- Add migration doc and tests.

## 6. User-Manageable Options Format

Create `.vscode/i2-vision-settings.json` with an optional `customSettings` key:

```json
{
  "streaming": { "chunkSize": 120 },
  "customSettings": [
    {
      "section": "My Custom",
      "key": "myFeature.enabled",
      "label": "Enable My Feature",
      "type": "boolean",
      "defaultValue": false
    }
  ]
}
```

`customSettingsLoader` merges these descriptors into the registry after built-ins.

## 7. Testing Strategy

- Unit test view models with a fake store — no VS Code UI required.
- Unit test validators for each type/range.
- Unit test persistence merging: defaults < JSON < VS Code config.
- Manual test: open settings, modify, save, reload VS Code.

## 8. Risks & Mitigations

| Risk | Mitigation |
|---|---|
| Breaking existing persisted settings | Keep same JSON file name and deep merge |
| Webview HTML regressions | Keep old `getHtmlContent` until new builder is visually verified |
| Message listener duplication | Host registers exactly once; tests assert count |
| Custom descriptor corruption | Validate and ignore invalid entries, log warning |

## 9. Rollback Plan

- Old `SettingsPanel.ts` remains available until cutover.
- `git revert` individual phases.
- Persistence format is unchanged, so older extension versions can read saved settings.

## 10. Acceptance Criteria

- All existing settings editable via new MVVM UI
- No duplicate message listener registrations
- Settings persist to `.vscode/i2-vision-settings.json`
- Custom descriptors can be added via workspace JSON without extension recompilation
- `tsc -p ./` passes after every phase
- Existing VS Code configuration overrides continue to work
