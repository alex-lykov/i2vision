# i2-Vision Agent Settings Guide

## Overview

The i2-Vision Agent can be configured through a comprehensive settings system that controls streaming behavior, terminal management, build verification, UI preferences, and agent behavior.

## Accessing Settings

### Method 1: Command Palette
1. Press `Ctrl+Shift+P` (Windows/Linux) or `Cmd+Shift+P` (Mac)
2. Type **"i2-Vision: Open Settings"**
3. Press Enter

### Method 2: Command
Run the command: `i2vision.settings`

## Settings Categories

### 📡 Streaming

Control how agent responses are streamed to the UI.

| Setting | Type | Default | Description |
|---------|------|---------|-------------|
| **Enable Streaming** | boolean | `true` | Stream agent responses in real-time |
| **Show Thinking Indicator** | boolean | `true` | Display spinner while agent is thinking |
| **Chunk Size** | number | `50` | Characters per streaming chunk (10-500) |
| **Chunk Delay** | number | `20ms` | Delay between chunks (0-1000ms) |

**Use Cases:**
- Increase chunk size for faster streaming on fast connections
- Decrease chunk delay for smoother text appearance
- Disable thinking indicator for minimal UI

---

### 💻 Terminal

Manage terminal behavior for command execution.

| Setting | Type | Default | Description |
|---------|------|---------|-------------|
| **Show Output in Webview** | boolean | `true` | Display terminal output in agent timeline |
| **Auto-Close Delay** | number | `5000ms` | Delay before closing short-lived terminals |
| **Preserve Terminals** | boolean | `false` | Don't auto-close terminals after execution |
| **Max Terminal History** | number | `1000` | Maximum lines to keep in terminal history |

**Use Cases:**
- Increase auto-close delay to review command output longer
- Enable "Preserve Terminals" for debugging long-running processes
- Increase history limit for commands with verbose output

---

### 🔨 Build

Configure build command execution and error handling.

| Setting | Type | Default | Description |
|---------|------|---------|-------------|
| **Build Timeout** | number | `120s` | Maximum build duration in seconds (10-600) |
| **Capture Output** | boolean | `true` | Capture and display build output |
| **Show Errors Prominently** | boolean | `true` | Highlight build errors in red |
| **Extract File References** | boolean | `true` | Extract file paths from error messages |

**Use Cases:**
- Increase timeout for large projects with slow builds
- Disable error extraction if custom build tools are used
- Enable prominent errors for quick issue identification

---

### 🎨 User Interface

Customize the agent UI appearance and behavior.

| Setting | Type | Default | Description |
|---------|------|---------|-------------|
| **Show Token Usage** | boolean | `true` | Display token consumption meter |
| **Show Duration** | boolean | `true` | Display execution time for responses |
| **Show Tool Cards** | boolean | `true` | Display tool execution as cards |
| **Collapse Old Tool Cards** | boolean | `true` | Auto-collapse older tool cards |
| **Max Visible Tool Cards** | number | `5` | Maximum expanded tool cards (1-10) |
| **Theme** | string | `"auto"` | Color theme: `"auto"`, `"light"`, `"dark"` |

**Use Cases:**
- Disable token usage for cleaner UI
- Increase max visible tool cards for detailed tool tracking
- Set theme to override VSCode theme

---

### 🤖 Agent Behavior

Control agent loop and conversation management.

| Setting | Type | Default | Description |
|---------|------|---------|-------------|
| **Max Iterations** | number | `10` | Maximum agent loop iterations (1-50) |
| **Enable Loop Detection** | boolean | `true` | Detect and prevent infinite loops |
| **Auto-Save Conversation** | boolean | `true` | Automatically save conversation history |
| **Conversation History Limit** | number | `50` | Max messages to keep in history (10-200) |

**Use Cases:**
- Increase max iterations for complex multi-step tasks
- Disable loop detection for repetitive but intentional tasks
- Reduce history limit to save disk space

---

### 🧠 Model

Configure default AI model settings.

| Setting | Type | Default | Description |
|---------|------|---------|-------------|
| **Default Provider** | string | `"ollama"` | Provider: `"ollama"` or `"deepseek"` |
| **Default Model** | string | `"llama3.2:3b"` | Model name to use by default |
| **Context Length** | number | `8192` | Model context window size (1024-128000) |
| **Max Output Tokens** | number | `4096` | Maximum tokens in response (100-32000) |
| **Temperature** | number | `0.7` | Model creativity (0-2) |
| **Top P** | number | `0.9` | Nucleus sampling parameter (0-1) |

**Use Cases:**
- Lower temperature (0.2-0.5) for deterministic code generation
- Higher temperature (1.0-1.5) for creative brainstorming
- Increase context length for large codebase analysis

---

### ⚡ Advanced

Developer and debugging options.

| Setting | Type | Default | Description |
|---------|------|---------|-------------|
| **Debug Logging** | boolean | `false` | Enable verbose debug logging |
| **Log Tool Calls** | boolean | `true` | Log all tool invocations |
| **Log LLM Requests** | boolean | `false` | Log raw LLM API requests/responses |
| **Enable Experimental Features** | boolean | `false` | Try out new unstable features |

**Use Cases:**
- Enable debug logging when reporting issues
- Log LLM requests for API debugging
- Enable experimental features to test upcoming functionality

---

## Settings File Location

Settings are stored in:
```
<workspace-root>/.vscode/i2-vision-settings.json
```

If no workspace is open, settings are stored in:
```
<extension-storage-path>/i2-vision-settings.json
```

## Export/Import Settings

### Export Settings
1. Open Settings Panel
2. Click **"📤 Export"**
3. Settings JSON is copied to clipboard
4. Paste and save to share with team

### Import Settings
1. Open Settings Panel
2. Click **"📥 Import"**
3. Paste settings JSON
4. Click **"💾 Save Settings"**

## Reset to Defaults

To reset all settings:
1. Open Settings Panel
2. Click **"🔄 Reset to Defaults"**
3. Confirm the action

## Example Settings File

```json
{
  "streaming": {
    "enabled": true,
    "chunkSize": 50,
    "chunkDelayMs": 20,
    "showThinkingIndicator": true
  },
  "terminal": {
    "autoCloseDelayMs": 5000,
    "showOutputInWebview": true,
    "preserveTerminals": false,
    "maxTerminalHistory": 1000
  },
  "build": {
    "timeoutSeconds": 120,
    "captureOutput": true,
    "showErrorsProminently": true,
    "extractFileReferences": true
  },
  "ui": {
    "showTokenUsage": true,
    "showDuration": true,
    "showToolCards": true,
    "collapseOldToolCards": true,
    "maxVisibleToolCards": 5,
    "theme": "auto"
  },
  "agent": {
    "maxIterations": 10,
    "maxConsecutiveToolCalls": 5,
    "enableLoopDetection": true,
    "autoSaveConversation": true,
    "conversationHistoryLimit": 50
  },
  "model": {
    "defaultProvider": "ollama",
    "defaultModel": "llama3.2:3b",
    "contextLength": 8192,
    "maxOutputTokens": 4096,
    "temperature": 0.7,
    "topP": 0.9
  },
  "advanced": {
    "debugLogging": false,
    "logToolCalls": true,
    "logLLMRequests": false,
    "enableExperimentalFeatures": false
  }
}
```

## Validation

Settings are automatically validated when saved. Invalid values will show error messages.

**Validation Rules:**
- Chunk size: 10-500 characters
- Chunk delay: 0-1000ms
- Terminal auto-close: 0-60000ms
- Build timeout: 10-600 seconds
- Max iterations: 1-50
- Context length: 1024-128000 tokens
- Temperature: 0-2
- Top P: 0-1

## Troubleshooting

### Settings Not Saving
- Check file permissions in `.vscode/` directory
- Ensure JSON syntax is valid
- Try "Reset to Defaults" and reconfigure

### Settings Not Applying
- Restart VSCode after changing advanced settings
- Check Output Channel for errors
- Verify settings file exists and is readable

### Import Fails
- Ensure JSON is valid (use JSONLint)
- Check that all required fields are present
- Verify values are within valid ranges

## Team Configuration

Share settings across your team:

1. Configure settings on one machine
2. Export settings (📤 Export)
3. Commit `.vscode/i2-vision-settings.json` to version control
4. Team members import settings (📥 Import)

**Tip:** Add to `.gitignore` if you want per-user settings:
```
.vscode/i2-vision-settings.json
```
