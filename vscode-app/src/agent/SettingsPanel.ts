/**
 * SettingsPanel - VSCode webview panel for agent settings
 * 
 * Provides a UI for configuring agent options:
 * - Streaming settings
 * - Terminal behavior
 * - Build configuration
 * - UI preferences
 * - Agent behavior
 * - Model settings
 */

import * as vscode from 'vscode';
import { AgentSettingsManager, AgentSettings, validateSettings } from './AgentSettings';

export class SettingsPanel {
  private static currentPanel: SettingsPanel | undefined;
  private static readonly viewType = 'i2visionSettings';
  
  private readonly panel: vscode.WebviewPanel;
  private readonly context: vscode.ExtensionContext;
  private readonly settingsManager: AgentSettingsManager;
  private disposables: vscode.Disposable[] = [];
  
  private constructor(context: vscode.ExtensionContext, settingsManager: AgentSettingsManager) {
    this.context = context;
    this.settingsManager = settingsManager;
    
    this.panel = vscode.window.createWebviewPanel(
      SettingsPanel.viewType,
      'i2-Vision Agent Settings',
      vscode.ViewColumn.One,
      {
        enableScripts: true,
        retainContextWhenHidden: true,
        localResourceRoots: [
          vscode.Uri.file(context.extensionPath)
        ]
      }
    );
    
    this.panel.onDidDispose(() => this.dispose(), null, this.disposables);
    this.updateWebview();
  }
  
  /**
   * Show settings panel
   */
  public static show(context: vscode.ExtensionContext): void {
    const settingsManager = AgentSettingsManager.getInstance(context);
    
    if (SettingsPanel.currentPanel) {
      SettingsPanel.currentPanel.panel.reveal(vscode.ViewColumn.One);
      return;
    }
    
    SettingsPanel.currentPanel = new SettingsPanel(context, settingsManager);
  }
  
  /**
   * Update webview content
   */
  private updateWebview(): void {
    const settings = this.settingsManager.getSettings();
    
    this.panel.webview.html = this.getHtmlContent(settings);
    
    // Handle messages from webview
    this.panel.webview.onDidReceiveMessage(async (message) => {
      switch (message.type) {
        case 'saveSettings':
          await this.handleSaveSettings(message.settings);
          break;
          
        case 'resetSettings':
          await this.handleResetSettings();
          break;
          
        case 'exportSettings':
          this.handleExportSettings();
          break;
          
        case 'importSettings':
          this.handleImportSettings(message.settings);
          break;
      }
    }, null, this.disposables);
  }
  
  /**
   * Handle save settings
   */
  private async handleSaveSettings(newSettings: Partial<AgentSettings>): Promise<void> {
    // Validate settings
    const validation = validateSettings(newSettings);
    
    if (!validation.valid) {
      vscode.window.showErrorMessage(`Invalid settings: ${validation.errors.join(', ')}`);
      return;
    }
    
    await this.settingsManager.updateSettings(newSettings);
    vscode.window.showInformationMessage('i2-Vision settings saved successfully');
  }
  
  /**
   * Handle reset settings
   */
  private async handleResetSettings(): Promise<void> {
    const confirm = await vscode.window.showWarningMessage(
      'Reset all settings to defaults?',
      { modal: true },
      'Reset'
    );
    
    if (confirm === 'Reset') {
      await this.settingsManager.resetToDefaults();
      this.updateWebview();
    }
  }
  
  /**
   * Handle export settings
   */
  private handleExportSettings(): void {
    const settings = this.settingsManager.getSettings();
    const settingsJson = JSON.stringify(settings, null, 2);
    
    vscode.env.clipboard.writeText(settingsJson);
    vscode.window.showInformationMessage('Settings copied to clipboard');
  }
  
  /**
   * Handle import settings
   */
  private handleImportSettings(newSettings: AgentSettings): void {
    const validation = validateSettings(newSettings);
    
    if (!validation.valid) {
      vscode.window.showErrorMessage(`Invalid settings: ${validation.errors.join(', ')}`);
      return;
    }
    
    this.settingsManager.updateSettings(newSettings);
    this.updateWebview();
  }
  
  /**
   * Get HTML content for webview
   */
  private getHtmlContent(settings: AgentSettings): string {
    return `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>i2-Vision Agent Settings</title>
  <style>
    * { box-sizing: border-box; margin: 0; padding: 0; }
    body {
      font-family: var(--vscode-font-family);
      font-size: var(--vscode-font-size);
      color: var(--vscode-foreground);
      background-color: var(--vscode-editor-background);
      padding: 20px;
      line-height: 1.6;
    }
    
    h1 {
      font-size: 1.5em;
      margin-bottom: 20px;
      padding-bottom: 10px;
      border-bottom: 1px solid var(--vscode-editorWidget-border);
    }
    
    h2 {
      font-size: 1.2em;
      margin: 20px 0 10px 0;
      color: var(--vscode-foreground);
    }
    
    .section {
      background-color: var(--vscode-editorWidget-background);
      border: 1px solid var(--vscode-editorWidget-border);
      border-radius: 6px;
      padding: 15px;
      margin-bottom: 15px;
    }
    
    .setting-row {
      display: flex;
      justify-content: space-between;
      align-items: center;
      padding: 10px 0;
      border-bottom: 1px solid var(--vscode-editorWidget-border);
    }
    
    .setting-row:last-child {
      border-bottom: none;
    }
    
    .setting-label {
      flex: 1;
    }
    
    .setting-description {
      font-size: 0.85em;
      color: var(--vscode-descriptionForeground);
      margin-top: 4px;
    }
    
    .setting-control {
      flex: 0 0 200px;
    }
    
    input[type="text"],
    input[type="number"],
    select {
      width: 100%;
      padding: 6px 10px;
      border: 1px solid var(--vscode-input-border);
      border-radius: 4px;
      background-color: var(--vscode-input-background);
      color: var(--vscode-input-foreground);
      font-family: var(--vscode-font-family);
      font-size: var(--vscode-font-size);
    }
    
    input[type="checkbox"] {
      width: 18px;
      height: 18px;
      cursor: pointer;
    }
    
    input[type="range"] {
      width: 100%;
      cursor: pointer;
    }
    
    .range-value {
      text-align: right;
      font-size: 0.85em;
      color: var(--vscode-descriptionForeground);
      margin-top: 4px;
    }
    
    .button-row {
      display: flex;
      gap: 10px;
      margin-top: 20px;
      padding-top: 20px;
      border-top: 1px solid var(--vscode-editorWidget-border);
    }
    
    .btn {
      padding: 8px 16px;
      border: none;
      border-radius: 4px;
      cursor: pointer;
      font-family: var(--vscode-font-family);
      font-size: 0.9em;
      font-weight: 500;
      transition: all 0.2s ease;
    }
    
    .btn-primary {
      background-color: var(--vscode-button-background);
      color: var(--vscode-button-foreground);
    }
    
    .btn-primary:hover {
      background-color: var(--vscode-button-hoverBackground);
    }
    
    .btn-secondary {
      background-color: var(--vscode-button-secondaryBackground);
      color: var(--vscode-button-secondaryForeground);
    }
    
    .btn-secondary:hover {
      opacity: 0.9;
    }
    
    .btn-danger {
      background-color: var(--vscode-errorForeground);
      color: white;
    }
    
    .btn-danger:hover {
      opacity: 0.9;
    }
    
    .badge {
      display: inline-block;
      padding: 2px 6px;
      border-radius: 3px;
      font-size: 0.75em;
      font-weight: 600;
      margin-left: 8px;
    }
    
    .badge-default {
      background-color: var(--vscode-descriptionForeground);
      color: white;
    }
    
    .toast {
      position: fixed;
      bottom: 20px;
      right: 20px;
      padding: 12px 20px;
      background-color: var(--vscode-editorWidget-background);
      border: 1px solid var(--vscode-editorWidget-border);
      border-radius: 6px;
      box-shadow: 0 4px 12px rgba(0, 0, 0, 0.3);
      z-index: 1000;
      animation: slideIn 0.3s ease;
    }
    
    @keyframes slideIn {
      from {
        transform: translateX(100%);
        opacity: 0;
      }
      to {
        transform: translateX(0);
        opacity: 1;
      }
    }
  </style>
</head>
<body>
  <h1>⚙️ i2-Vision Agent Settings</h1>
  
  <!-- Streaming Settings -->
  <div class="section">
    <h2>📡 Streaming</h2>
    
    <div class="setting-row">
      <div class="setting-label">
        <div>Enable Streaming</div>
        <div class="setting-description">Stream agent responses in real-time</div>
      </div>
      <div class="setting-control">
        <input type="checkbox" id="streaming.enabled" ${settings.streaming.enabled ? 'checked' : ''}>
      </div>
    </div>
    
    <div class="setting-row">
      <div class="setting-label">
        <div>Show Thinking Indicator</div>
        <div class="setting-description">Display spinner while agent is thinking</div>
      </div>
      <div class="setting-control">
        <input type="checkbox" id="streaming.showThinkingIndicator" ${settings.streaming.showThinkingIndicator ? 'checked' : ''}>
      </div>
    </div>
    
    <div class="setting-row">
      <div class="setting-label">
        <div>Chunk Size</div>
        <div class="setting-description">Characters per streaming chunk</div>
      </div>
      <div class="setting-control">
        <input type="range" id="streaming.chunkSize" min="10" max="500" value="${settings.streaming.chunkSize}">
        <div class="range-value">${settings.streaming.chunkSize} chars</div>
      </div>
    </div>
    
    <div class="setting-row">
      <div class="setting-label">
        <div>Chunk Delay</div>
        <div class="setting-description">Delay between chunks (milliseconds)</div>
      </div>
      <div class="setting-control">
        <input type="range" id="streaming.chunkDelayMs" min="0" max="500" value="${settings.streaming.chunkDelayMs}">
        <div class="range-value">${settings.streaming.chunkDelayMs} ms</div>
      </div>
    </div>
  </div>
  
  <!-- Terminal Settings -->
  <div class="section">
    <h2>💻 Terminal</h2>
    
    <div class="setting-row">
      <div class="setting-label">
        <div>Show Output in Webview</div>
        <div class="setting-description">Display terminal output in agent timeline</div>
      </div>
      <div class="setting-control">
        <input type="checkbox" id="terminal.showOutputInWebview" ${settings.terminal.showOutputInWebview ? 'checked' : ''}>
      </div>
    </div>
    
    <div class="setting-row">
      <div class="setting-label">
        <div>Auto-Close Delay</div>
        <div class="setting-description">Delay before closing short-lived terminals (ms)</div>
      </div>
      <div class="setting-control">
        <input type="range" id="terminal.autoCloseDelayMs" min="0" max="30000" step="1000" value="${settings.terminal.autoCloseDelayMs}">
        <div class="range-value">${settings.terminal.autoCloseDelayMs / 1000}s</div>
      </div>
    </div>
    
    <div class="setting-row">
      <div class="setting-label">
        <div>Preserve Terminals</div>
        <div class="setting-description">Don't auto-close terminals after execution</div>
      </div>
      <div class="setting-control">
        <input type="checkbox" id="terminal.preserveTerminals" ${settings.terminal.preserveTerminals ? 'checked' : ''}>
      </div>
    </div>
    
    <div class="setting-row">
      <div class="setting-label">
        <div>Max Terminal History</div>
        <div class="setting-description">Maximum lines to keep in terminal history</div>
      </div>
      <div class="setting-control">
        <input type="number" id="terminal.maxTerminalHistory" min="100" max="10000" value="${settings.terminal.maxTerminalHistory}">
      </div>
    </div>
  </div>
  
  <!-- Build Settings -->
  <div class="section">
    <h2>🔨 Build</h2>
    
    <div class="setting-row">
      <div class="setting-label">
        <div>Build Timeout</div>
        <div class="setting-description">Maximum build duration in seconds</div>
      </div>
      <div class="setting-control">
        <input type="range" id="build.timeoutSeconds" min="10" max="600" step="10" value="${settings.build.timeoutSeconds}">
        <div class="range-value">${settings.build.timeoutSeconds}s</div>
      </div>
    </div>
    
    <div class="setting-row">
      <div class="setting-label">
        <div>Capture Output</div>
        <div class="setting-description">Capture and display build output</div>
      </div>
      <div class="setting-control">
        <input type="checkbox" id="build.captureOutput" ${settings.build.captureOutput ? 'checked' : ''}>
      </div>
    </div>
    
    <div class="setting-row">
      <div class="setting-label">
        <div>Show Errors Prominently</div>
        <div class="setting-description">Highlight build errors in red</div>
      </div>
      <div class="setting-control">
        <input type="checkbox" id="build.showErrorsProminently" ${settings.build.showErrorsProminently ? 'checked' : ''}>
      </div>
    </div>
    
    <div class="setting-row">
      <div class="setting-label">
        <div>Extract File References</div>
        <div class="setting-description">Extract file paths from error messages</div>
      </div>
      <div class="setting-control">
        <input type="checkbox" id="build.extractFileReferences" ${settings.build.extractFileReferences ? 'checked' : ''}>
      </div>
    </div>
  </div>
  
  <!-- UI Settings -->
  <div class="section">
    <h2>🎨 User Interface</h2>
    
    <div class="setting-row">
      <div class="setting-label">
        <div>Show Token Usage</div>
        <div class="setting-description">Display token consumption meter</div>
      </div>
      <div class="setting-control">
        <input type="checkbox" id="ui.showTokenUsage" ${settings.ui.showTokenUsage ? 'checked' : ''}>
      </div>
    </div>
    
    <div class="setting-row">
      <div class="setting-label">
        <div>Show Duration</div>
        <div class="setting-description">Display execution time for responses</div>
      </div>
      <div class="setting-control">
        <input type="checkbox" id="ui.showDuration" ${settings.ui.showDuration ? 'checked' : ''}>
      </div>
    </div>
    
    <div class="setting-row">
      <div class="setting-label">
        <div>Show Tool Cards</div>
        <div class="setting-description">Display tool execution as cards in timeline</div>
      </div>
      <div class="setting-control">
        <input type="checkbox" id="ui.showToolCards" ${settings.ui.showToolCards ? 'checked' : ''}>
      </div>
    </div>
    
    <div class="setting-row">
      <div class="setting-label">
        <div>Collapse Old Tool Cards</div>
        <div class="setting-description">Auto-collapse older tool cards</div>
      </div>
      <div class="setting-control">
        <input type="checkbox" id="ui.collapseOldToolCards" ${settings.ui.collapseOldToolCards ? 'checked' : ''}>
      </div>
    </div>
    
    <div class="setting-row">
      <div class="setting-label">
        <div>Theme</div>
        <div class="setting-description">Color theme for agent UI</div>
      </div>
      <div class="setting-control">
        <select id="ui.theme">
          <option value="auto" ${settings.ui.theme === 'auto' ? 'selected' : ''}>Auto (Follow VSCode)</option>
          <option value="light" ${settings.ui.theme === 'light' ? 'selected' : ''}>Light</option>
          <option value="dark" ${settings.ui.theme === 'dark' ? 'selected' : ''}>Dark</option>
        </select>
      </div>
    </div>
  </div>
  
  <!-- Agent Behavior -->
  <div class="section">
    <h2>🤖 Agent Behavior</h2>
    
    <div class="setting-row">
      <div class="setting-label">
        <div>Max Iterations</div>
        <div class="setting-description">Maximum agent loop iterations</div>
      </div>
      <div class="setting-control">
        <input type="range" id="agent.maxIterations" min="1" max="50" value="${settings.agent.maxIterations}">
        <div class="range-value">${settings.agent.maxIterations}</div>
      </div>
    </div>
    
    <div class="setting-row">
      <div class="setting-label">
        <div>Enable Loop Detection</div>
        <div class="setting-description">Detect and prevent infinite loops</div>
      </div>
      <div class="setting-control">
        <input type="checkbox" id="agent.enableLoopDetection" ${settings.agent.enableLoopDetection ? 'checked' : ''}>
      </div>
    </div>
    
    <div class="setting-row">
      <div class="setting-label">
        <div>Auto-Save Conversation</div>
        <div class="setting-description">Automatically save conversation history</div>
      </div>
      <div class="setting-control">
        <input type="checkbox" id="agent.autoSaveConversation" ${settings.agent.autoSaveConversation ? 'checked' : ''}>
      </div>
    </div>
  </div>
  
  <!-- Model Settings -->
  <div class="section">
    <h2>🧠 Model</h2>
    
    <div class="setting-row">
      <div class="setting-label">
        <div>Default Provider</div>
        <div class="setting-description">Default AI provider</div>
      </div>
      <div class="setting-control">
        <select id="model.defaultProvider">
          <option value="ollama" ${settings.model.defaultProvider === 'ollama' ? 'selected' : ''}>Ollama (Local)</option>
          <option value="deepseek" ${settings.model.defaultProvider === 'deepseek' ? 'selected' : ''}>DeepSeek (Cloud)</option>
        </select>
      </div>
    </div>
    
    <div class="setting-row">
      <div class="setting-label">
        <div>Default Model</div>
        <div class="setting-description">Default model name</div>
      </div>
      <div class="setting-control">
        <input type="text" id="model.defaultModel" value="${settings.model.defaultModel}">
      </div>
    </div>
    
    <div class="setting-row">
      <div class="setting-label">
        <div>Temperature</div>
        <div class="setting-description">Model creativity (0 = deterministic, 2 = creative)</div>
      </div>
      <div class="setting-control">
        <input type="range" id="model.temperature" min="0" max="2" step="0.1" value="${settings.model.temperature}">
        <div class="range-value">${settings.model.temperature}</div>
      </div>
    </div>
  </div>
  
  <!-- Advanced Settings -->
  <div class="section">
    <h2>⚡ Advanced</h2>
    
    <div class="setting-row">
      <div class="setting-label">
        <div>Debug Logging</div>
        <div class="setting-description">Enable verbose debug logging</div>
      </div>
      <div class="setting-control">
        <input type="checkbox" id="advanced.debugLogging" ${settings.advanced.debugLogging ? 'checked' : ''}>
      </div>
    </div>
    
    <div class="setting-row">
      <div class="setting-label">
        <div>Log Tool Calls</div>
        <div class="setting-description">Log all tool invocations</div>
      </div>
      <div class="setting-control">
        <input type="checkbox" id="advanced.logToolCalls" ${settings.advanced.logToolCalls ? 'checked' : ''}>
      </div>
    </div>
    
    <div class="setting-row">
      <div class="setting-label">
        <div>Enable Experimental Features</div>
        <div class="setting-description">Try out new features (may be unstable)</div>
      </div>
      <div class="setting-control">
        <input type="checkbox" id="advanced.enableExperimentalFeatures" ${settings.advanced.enableExperimentalFeatures ? 'checked' : ''}>
      </div>
    </div>
  </div>
  
  <!-- Action Buttons -->
  <div class="button-row">
    <button class="btn btn-primary" onclick="saveSettings()">💾 Save Settings</button>
    <button class="btn btn-secondary" onclick="exportSettings()">📤 Export</button>
    <button class="btn btn-secondary" onclick="importSettings()">📥 Import</button>
    <button class="btn btn-danger" onclick="resetSettings()">🔄 Reset to Defaults</button>
  </div>
  
  <script>
    const vscode = acquireVsCodeApi();
    
    // Update range value displays
    document.querySelectorAll('input[type="range"]').forEach(input => {
      input.addEventListener('input', (e) => {
        const valueDisplay = e.target.nextElementSibling;
        if (valueDisplay && valueDisplay.classList.contains('range-value')) {
          const value = e.target.value;
          if (e.target.id.includes('DelayMs')) {
            valueDisplay.textContent = \`\${value / 1000}s\`;
          } else if (e.target.id.includes('timeoutSeconds')) {
            valueDisplay.textContent = \`\${value}s\`;
          } else {
            valueDisplay.textContent = value;
          }
        }
      });
    });
    
    function saveSettings() {
      const settings = {
        streaming: {
          enabled: document.getElementById('streaming.enabled').checked,
          chunkSize: parseInt(document.getElementById('streaming.chunkSize').value),
          chunkDelayMs: parseInt(document.getElementById('streaming.chunkDelayMs').value),
          showThinkingIndicator: document.getElementById('streaming.showThinkingIndicator').checked
        },
        terminal: {
          showOutputInWebview: document.getElementById('terminal.showOutputInWebview').checked,
          autoCloseDelayMs: parseInt(document.getElementById('terminal.autoCloseDelayMs').value),
          preserveTerminals: document.getElementById('terminal.preserveTerminals').checked,
          maxTerminalHistory: parseInt(document.getElementById('terminal.maxTerminalHistory').value)
        },
        build: {
          timeoutSeconds: parseInt(document.getElementById('build.timeoutSeconds').value),
          captureOutput: document.getElementById('build.captureOutput').checked,
          showErrorsProminently: document.getElementById('build.showErrorsProminently').checked,
          extractFileReferences: document.getElementById('build.extractFileReferences').checked
        },
        ui: {
          showTokenUsage: document.getElementById('ui.showTokenUsage').checked,
          showDuration: document.getElementById('ui.showDuration').checked,
          showToolCards: document.getElementById('ui.showToolCards').checked,
          collapseOldToolCards: document.getElementById('ui.collapseOldToolCards').checked,
          theme: document.getElementById('ui.theme').value
        },
        agent: {
          maxIterations: parseInt(document.getElementById('agent.maxIterations').value),
          enableLoopDetection: document.getElementById('agent.enableLoopDetection').checked,
          autoSaveConversation: document.getElementById('agent.autoSaveConversation').checked
        },
        model: {
          defaultProvider: document.getElementById('model.defaultProvider').value,
          defaultModel: document.getElementById('model.defaultModel').value,
          temperature: parseFloat(document.getElementById('model.temperature').value)
        },
        advanced: {
          debugLogging: document.getElementById('advanced.debugLogging').checked,
          logToolCalls: document.getElementById('advanced.logToolCalls').checked,
          enableExperimentalFeatures: document.getElementById('advanced.enableExperimentalFeatures').checked
        }
      };
      
      vscode.postMessage({ type: 'saveSettings', settings });
      showToast('Settings saved!');
    }
    
    function resetSettings() {
      vscode.postMessage({ type: 'resetSettings' });
    }
    
    function exportSettings() {
      vscode.postMessage({ type: 'exportSettings' });
    }
    
    function importSettings() {
      vscode.window.showInputBox({
        prompt: 'Paste settings JSON',
        placeHolder: '{"streaming": {...}}'
      }).then(input => {
        if (input) {
          try {
            const settings = JSON.parse(input);
            vscode.postMessage({ type: 'importSettings', settings });
          } catch (e) {
            vscode.window.showErrorMessage('Invalid JSON');
          }
        }
      });
    }
    
    function showToast(message) {
      const toast = document.createElement('div');
      toast.className = 'toast';
      toast.textContent = message;
      document.body.appendChild(toast);
      
      setTimeout(() => {
        toast.remove();
      }, 3000);
    }
    
    window.addEventListener('message', (event) => {
      const message = event.data;
      if (message.type === 'settingsUpdated') {
        showToast('Settings updated');
      }
    });
  </script>
</body>
</html>`;
  }
  
  /**
   * Dispose panel
   */
  public dispose(): void {
    SettingsPanel.currentPanel = undefined;
    this.panel.dispose();
    
    while (this.disposables.length) {
      const disposable = this.disposables.pop();
      if (disposable) {
        disposable.dispose();
      }
    }
  }
}
