# Terminal Output Settings (db, ui, mvvm-dto, flows)

Terminal output is aligned with **TerminalSettingsDefinitions** and **TerminalSettingsState**: visibility and formatting are driven by the database-backed settings and the UI settings panel.

## Structure

- **Database**: `TerminalSettingsDefinitions` (canonical keys from `terminal_settings.properties`), `TerminalSettingsState` (per-session enabled/disabled), `TerminalSettingsRepository`.
- **Filter stubs**: `TerminalOutputFilter` (database) – `shouldShow(outputSettingKey, settings)`, `formatMessage(...)` for timestamps/compact.
- **Mapping**: `TerminalOutputMapping` (launcher) – maps `OutputEvent` / agent output to setting keys.
- **Flow**: MainViewModel loads settings from `TerminalSettingsRepository`, subscribes to `settingsFlow`, filters each terminal event by `outputSettingKey` via `TerminalOutputFilter`, and exposes `terminalDisplayOptions` (e.g. show_timestamps, compact_mode) for the Terminal view.

## Agent output → setting key mapping

| Agent output | OutputEvent / source | Terminal setting key |
|-------------|----------------------|----------------------|
| AI text response | `Standard`, `Success` | `show_ai_responses` |
| User prompt line | ">>> [agent] task" | `show_user_prompts` |
| Tool/MCP call | System "🔧 Calling tool…" / "Using MCP tools" | `show_command_execution` |
| Progress | `Progress` | `show_progress_bar` |
| Warnings | `Warning` | `show_warnings` |
| Errors | `Error` | `show_error_details` |
| Debug | `Debug` | `show_raw_json` |
| System (routing, load/unload) | System | `show_status_bar` |
| Complete | `Complete` | (always shown) |

Additional keys from `terminal_settings.properties` (e.g. `show_file_reads`, `show_token_count`, `show_mcp_connections`) can be wired to new event types or metadata as the agent exposes them.

## Functional stubs

- **Visibility**: `TerminalOutputFilter.shouldShow(outputSettingKey, settings)` – if key is null, show; else show when the setting is enabled (missing key → show).
- **Formatting**: `TerminalOutputFilter.formatMessage(message, showTimestamps, timestamp, compactMode)` – optionally prefixes time and adjusts spacing. Terminal.kt uses `displayOptions.showTimestamps` and `displayOptions.compactMode` from the same settings for rendering.

## UI alignment

- **Terminal.kt** (launcher): Consumes `TerminalStateDto` (events) and `TerminalDisplayOptionsDto` (showTimestamps, compactMode) from TerminalViewModel, which gets them from MainViewModel (fed by TerminalSettingsRepository + filter).
- **TerminalSettingsPanel** (ui): Edits the same settings via `TerminalViewModel` (ui) and `TerminalSettingsRepository`; changes flow to MainViewModel via `settingsFlow` and drive filtering and display options.
