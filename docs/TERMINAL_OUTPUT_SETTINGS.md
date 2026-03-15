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
| User prompt line | System ">>> [agent] task" | `show_status_bar` |
| Tool call (readable) | `ToolCallDetail` | `show_command_execution` |
| File operations | `FileOp` | `show_file_reads`, `show_file_writes`, `show_file_deletes` |
| Progress | `Progress` | `show_progress_bar` |
| Warnings | `Warning` | `show_warnings` |
| Errors | `Error` | `show_error_details` |
| Debug | `Debug` | `show_raw_json` |
| System (routing, load/unload) | System | `show_status_bar` |
| Decision info | `Decision` | `show_status_symbols` |
| Complete | `Complete` | (always shown) |

## TerminalEventCard

A common UI card component (`TerminalEventCard`) renders each event type in a readable format:
- **ToolCall**: Tool name, params, result, duration (no raw JSON)
- **FileOp**: Operation, path, content preview, bytes written
- **FileDiff**: Path, +/- lines, before/after preview
- **Decision**: Phase, message, details
- **Simple events**: Text with appropriate icon/color

Filtered by UI settings via `TerminalOutputFilter.shouldShow(outputSettingKey, settings)`.

## Functional stubs

- **Visibility**: `TerminalOutputFilter.shouldShow(outputSettingKey, settings)` – if key is null, show; else show when the setting is enabled (missing key → show).
- **Formatting**: `TerminalOutputFilter.formatMessage(message, showTimestamps, timestamp, compactMode)` – optionally prefixes time and adjusts spacing. Terminal.kt uses `displayOptions.showTimestamps` and `displayOptions.compactMode` from the same settings for rendering.

## UI alignment

- **Terminal.kt** (launcher): Consumes `TerminalStateDto` (events) and `TerminalDisplayOptionsDto` (showTimestamps, compactMode) from TerminalViewModel, which gets them from MainViewModel (fed by TerminalSettingsRepository + filter).
- **TerminalSettingsPanel** (ui): Edits the same settings via `TerminalViewModel` (ui) and `TerminalSettingsRepository`; changes flow to MainViewModel via `settingsFlow` and drive filtering and display options.
