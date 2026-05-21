/**
 * AgentTabManager - Manages agent tabs in VSCode
 * 
 * Creates and manages webview panels for each agent instance,
 * loading configuration from YAML files and bridging to the agent core.
 */

import * as vscode from 'vscode';
import * as path from 'path';
import * as fs from 'fs';
import * as yaml from 'js-yaml';
import { AgentBridge, AgentConfig, InteractionRecord } from './AgentBridge';

/**
 * Agent tab representation
 */
interface AgentTab {
  id: string;
  layer: 'vision' | 'structure' | 'logic' | 'flow' | 'code';
  config: AgentConfig;
  bridge: AgentBridge;
  panel: vscode.WebviewPanel;
  history: InteractionRecord[];
  isProcessing: boolean;
}

/**
 * AgentTabManager - Creates and manages agent tabs
 */
export class AgentTabManager {
  private tabs: Map<string, AgentTab> = new Map();
  private configPath: string;
  private outputChannel: vscode.OutputChannel;

  constructor(
    private context: vscode.ExtensionContext,
    outputChannel: vscode.OutputChannel
  ) {
    this.outputChannel = outputChannel;
    
    this.configPath = path.join(
      vscode.workspace.workspaceFolders?.[0]?.uri.fsPath || '',
      '.vscode', 'i2vision', 'agents'
    );
    
    this.log('AgentTabManager initialized');
    this.log(`Config path: ${this.configPath}`);
  }

  /**
   * Create a new agent tab for a specific VSLFC layer
   */
  async createTab(layer: 'vision' | 'structure' | 'logic' | 'flow' | 'code'): Promise<string> {
    this.log(`Creating ${layer} agent tab...`);
    
    try {
      // Load the YAML configuration
      const configPath = path.join(this.configPath, `${layer}-agent.yaml`);
      const config = await this.loadConfig(configPath);
      
      if (!config) {
        throw new Error(`Configuration file not found: ${configPath}`);
      }
      
      // Create the agent bridge
      const bridge = new AgentBridge(config, this.outputChannel);
      await bridge.initialize();
      
      // Create the webview panel
      const panel = vscode.window.createWebviewPanel(
        `i2vision-agent-${layer}`,
        `i2-Vision ${this.capitalize(layer)} Agent`,
        vscode.ViewColumn.Two,
        {
          enableScripts: true,
          retainContextWhenHidden: true,
          localResourceRoots: [vscode.Uri.file(path.join(this.context.extensionPath, 'resources'))]
        }
      );
      
      const tab: AgentTab = {
        id: `${layer}-${Date.now()}`,
        layer,
        config,
        bridge,
        panel,
        history: [],
        isProcessing: false
      };
      
      // Set up webview communication
      panel.webview.onDidReceiveMessage(async (message) => {
        await this.handleWebviewMessage(tab, message);
      });
      
      // Set up panel disposal
      panel.onDidDispose(() => {
        this.log(`Tab disposed: ${tab.id}`);
        this.closeTab(tab.id);
      });
      
      // Set initial HTML
      panel.webview.html = this.getWebviewContent(layer, config);
      
      this.tabs.set(tab.id, tab);
      this.log(`Created ${layer} agent tab: ${tab.id}`);
      
      return tab.id;
    } catch (error: any) {
      this.log(`Error creating ${layer} agent tab: ${error.message}`);
      vscode.window.showErrorMessage(`Failed to create ${layer} agent: ${error.message}`);
      throw error;
    }
  }

  /**
   * Load agent configuration from YAML file
   */
  private async loadConfig(configPath: string): Promise<AgentConfig | null> {
    try {
      if (!fs.existsSync(configPath)) {
        // Create default config if it doesn't exist
        await this.createDefaultConfig(configPath);
      }
      
      const yamlContent = fs.readFileSync(configPath, 'utf8');
      const config = yaml.load(yamlContent) as AgentConfig;
      
      this.log(`Loaded config from: ${configPath}`);
      this.log(`Agent key: ${config.key}, type: ${config.agentType}`);
      
      return config;
    } catch (error: any) {
      this.log(`Error loading config: ${error.message}`);
      throw new Error(`Failed to load agent configuration: ${error.message}`);
    }
  }

  /**
   * Create default configuration file if it doesn't exist
   */
  private async createDefaultConfig(configPath: string): Promise<void> {
    const dir = path.dirname(configPath);
    
    if (!fs.existsSync(dir)) {
      fs.mkdirSync(dir, { recursive: true });
      this.log(`Created config directory: ${dir}`);
    }
    
    // Copy from the template if available
    const templatePath = path.join(this.context.extensionPath, '.vscode', 'i2vision', 'agents', 'coding-agent.yaml');
    
    if (fs.existsSync(templatePath)) {
      fs.copyFileSync(templatePath, configPath);
      this.log(`Created default config from template: ${configPath}`);
    } else {
      this.log(`No template found at: ${templatePath}`);
    }
  }

  /**
   * Handle messages from the webview
   */
  private async handleWebviewMessage(tab: AgentTab, message: any) {
    this.log(`Webview message: ${message.command}`);
    
    switch (message.command) {
      case 'sendMessage':
        await this.processUserInput(tab, message.text);
        break;
        
      case 'openConfig':
        await this.openConfigFile(tab);
        break;
        
      case 'clearHistory':
        tab.history = [];
        this.updateWebview(tab);
        break;
        
      case 'reloadConfig':
        await this.reloadConfig(tab);
        break;
    }
  }

  /**
   * Process user input through the agent
   */
  private async processUserInput(tab: AgentTab, userInput: string) {
    if (tab.isProcessing) {
      this.log('Agent is already processing, ignoring input');
      return;
    }
    
    tab.isProcessing = true;
    const startTime = Date.now();
    
    this.log(`Processing user input (${userInput.length} chars)`);
    
    // Update webview to show processing state
    tab.panel.webview.postMessage({
      command: 'processing',
      userInput
    });
    
    try {
      // Get current file context
      const currentFile = vscode.window.activeTextEditor?.document.uri.fsPath;
      
      // Call the agent bridge
      const response = await tab.bridge.process(userInput, {
        currentFile,
        projectName: vscode.workspace.workspaceFolders?.[0]?.name,
        task: userInput
      });
      
      // Record the interaction
      const record: InteractionRecord = {
        timestamp: Date.now(),
        userInput,
        agentResponse: response.finalText || 'No response',
        toolCalls: response.toolCalls?.map(tc => tc.toolName) || [],
        iterations: response.iterations,
        durationMs: Date.now() - startTime
      };
      
      tab.history.push(record);
      this.log(`Interaction recorded: ${record.iterations} iterations, ${record.durationMs}ms`);
      
      // Update webview with result
      tab.panel.webview.postMessage({
        command: 'response',
        response: {
          text: response.finalText,
          toolCalls: response.toolCalls,
          iterations: response.iterations,
          durationMs: response.durationMs,
          success: response.success
        }
      });
    } catch (error: any) {
      this.log(`Error processing input: ${error.message}`);
      tab.panel.webview.postMessage({
        command: 'error',
        error: String(error)
      });
    } finally {
      tab.isProcessing = false;
    }
  }

  /**
   * Open the configuration file for editing
   */
  private async openConfigFile(tab: AgentTab) {
    const configPath = path.join(this.configPath, `${tab.layer}-agent.yaml`);
    
    try {
      const doc = await vscode.workspace.openTextDocument(configPath);
      await vscode.window.showTextDocument(doc);
      this.log(`Opened config file: ${configPath}`);
    } catch (error: any) {
      this.log(`Error opening config file: ${error.message}`);
      vscode.window.showErrorMessage(`Could not open config file: ${error.message}`);
    }
  }

  /**
   * Reload configuration from file
   */
  private async reloadConfig(tab: AgentTab) {
    try {
      const configPath = path.join(this.configPath, `${tab.layer}-agent.yaml`);
      const newConfig = await this.loadConfig(configPath);
      
      if (newConfig) {
        tab.config = newConfig;
        
        // Reinitialize the bridge with new config
        tab.bridge.dispose();
        tab.bridge = new AgentBridge(newConfig, this.outputChannel);
        await tab.bridge.initialize();
        
        this.log(`Reloaded config for tab: ${tab.id}`);
        
        tab.panel.webview.postMessage({
          command: 'configReloaded',
          config: {
            model: newConfig.model.id,
            maxIterations: newConfig.iterationSettings.maxIterations
          }
        });
        
        vscode.window.showInformationMessage(`${this.capitalize(tab.layer)} Agent configuration reloaded`);
      }
    } catch (error: any) {
      this.log(`Error reloading config: ${error.message}`);
      vscode.window.showErrorMessage(`Failed to reload configuration: ${error.message}`);
    }
  }

  /**
   * Update the webview with current history
   */
  private updateWebview(tab: AgentTab) {
    tab.panel.webview.postMessage({
      command: 'updateHistory',
      history: tab.history
    });
  }

  /**
   * Close and dispose an agent tab
   */
  private closeTab(tabId: string) {
    const tab = this.tabs.get(tabId);
    if (tab) {
      this.log(`Closing tab: ${tabId}`);
      tab.bridge.dispose();
      this.tabs.delete(tabId);
    }
  }

  /**
   * Get all active tabs
   */
  getActiveTabs(): AgentTab[] {
    return Array.from(this.tabs.values());
  }

  /**
   * Get tab by ID
   */
  getTab(tabId: string): AgentTab | undefined {
    return this.tabs.get(tabId);
  }

  /**
   * Log a message
   */
  private log(message: string): void {
    this.outputChannel.appendLine(`[AgentTabManager] ${message}`);
  }

  /**
   * Capitalize first letter
   */
  private capitalize(s: string): string {
    return s.charAt(0).toUpperCase() + s.slice(1);
  }

  /**
   * Generate webview HTML for agent chat interface
   */
  private getWebviewContent(layer: string, config: AgentConfig): string {
    return `<!DOCTYPE html>
<html>
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>${this.capitalize(layer)} Agent</title>
  <style>
    :root {
      --vscode-font-family: ${this.getVsCodeFontFamily()};
      --vscode-font-size: 13px;
      --vscode-foreground: #cccccc;
      --vscode-background: #1e1e1e;
      --vscode-input-background: #3c3c3c;
      --vscode-input-foreground: #cccccc;
      --vscode-button-background: #0e639c;
      --vscode-button-foreground: #ffffff;
      --vscode-textBlockQuote-background: #2d2d2d;
      --vscode-titleBar-activeBackground: #323233;
      --vscode-badge-background: #0e639c;
      --vscode-badge-foreground: #ffffff;
      --vscode-panel-border: #454545;
      --vscode-textLink-foreground: #3794ff;
      --vscode-errorForeground: #f48771;
      --vscode-successForeground: #89d185;
    }
    
    * {
      box-sizing: border-box;
    }
    
    body { 
      font-family: var(--vscode-font-family);
      font-size: var(--vscode-font-size);
      color: var(--vscode-foreground);
      background: var(--vscode-background);
      padding: 0;
      margin: 0;
      display: flex;
      flex-direction: column;
      height: 100vh;
      overflow: hidden;
    }
    
    .config-bar {
      display: flex;
      justify-content: space-between;
      align-items: center;
      padding: 6px 12px;
      background: var(--vscode-titleBar-activeBackground);
      font-size: 0.85em;
      border-bottom: 1px solid var(--vscode-panel-border);
      flex-shrink: 0;
    }
    
    .config-info {
      display: flex;
      gap: 12px;
      align-items: center;
    }
    
    .config-actions {
      display: flex;
      gap: 6px;
    }
    
    button {
      padding: 4px 10px;
      background: var(--vscode-button-background);
      color: var(--vscode-button-foreground);
      border: none;
      border-radius: 2px;
      cursor: pointer;
      font-size: 0.85em;
    }
    
    button:hover {
      opacity: 0.9;
    }
    
    button:disabled {
      opacity: 0.5;
      cursor: not-allowed;
    }
    
    #chat-container {
      flex: 1;
      overflow-y: auto;
      padding: 12px;
      display: flex;
      flex-direction: column;
      gap: 8px;
    }
    
    .message {
      padding: 10px 12px;
      border-radius: 6px;
      max-width: 85%;
      line-height: 1.4;
      white-space: pre-wrap;
      word-wrap: break-word;
    }
    
    .user-message {
      background: var(--vscode-textBlockQuote-background);
      margin-left: auto;
      border-bottom-right-radius: 2px;
    }
    
    .agent-message {
      background: var(--vscode-background);
      margin-right: auto;
      border: 1px solid var(--vscode-panel-border);
      border-bottom-left-radius: 2px;
    }
    
    .agent-message.error {
      border-color: var(--vscode-errorForeground);
      background: rgba(244, 135, 113, 0.1);
    }
    
    .tool-call {
      font-size: 0.85em;
      color: var(--vscode-textLink-foreground);
      margin-top: 6px;
      padding-top: 6px;
      border-top: 1px solid var(--vscode-panel-border);
    }
    
    .iteration-info {
      font-size: 0.75em;
      color: var(--vscode-foreground);
      opacity: 0.7;
      margin-top: 4px;
      display: flex;
      gap: 8px;
    }
    
    .iteration-badge {
      background: var(--vscode-badge-background);
      color: var(--vscode-badge-foreground);
      padding: 2px 6px;
      border-radius: 3px;
      font-size: 0.8em;
    }
    
    #input-container {
      display: flex;
      padding: 12px;
      border-top: 1px solid var(--vscode-panel-border);
      background: var(--vscode-background);
      flex-shrink: 0;
      gap: 8px;
    }
    
    #user-input {
      flex: 1;
      padding: 8px 12px;
      background: var(--vscode-input-background);
      color: var(--vscode-input-foreground);
      border: 1px solid var(--vscode-panel-border);
      border-radius: 4px;
      font-family: inherit;
      font-size: inherit;
    }
    
    #user-input:focus {
      outline: 1px solid var(--vscode-button-background);
    }
    
    .processing-indicator {
      display: inline-block;
      width: 8px;
      height: 8px;
      border: 2px solid var(--vscode-foreground);
      border-top-color: transparent;
      border-radius: 50%;
      animation: spin 1s linear infinite;
      margin-right: 8px;
    }
    
    @keyframes spin {
      to { transform: rotate(360deg); }
    }
    
    .empty-state {
      text-align: center;
      color: var(--vscode-foreground);
      opacity: 0.5;
      padding: 40px 20px;
    }
    
    .empty-state h3 {
      margin: 0 0 8px 0;
      font-weight: normal;
    }
    
    .empty-state p {
      margin: 0;
      font-size: 0.9em;
    }
  </style>
</head>
<body>
  <div class="config-bar">
    <div class="config-info">
      <span>🔧 ${layer.toUpperCase()} Agent</span>
      <span class="iteration-badge">Model: ${config.model.id}</span>
      <span class="iteration-badge">Max: ${config.iterationSettings.maxIterations} iter</span>
    </div>
    <div class="config-actions">
      <button onclick="openConfig()">⚙️ Config</button>
      <button onclick="reloadConfig()">🔄 Reload</button>
      <button onclick="clearHistory()">🗑️ Clear</button>
    </div>
  </div>
  
  <div id="chat-container">
    <div class="empty-state">
      <h3>Welcome to the ${this.capitalize(layer)} Agent</h3>
      <p>Ask me to analyze code, make changes, or explore the project</p>
    </div>
  </div>
  
  <div id="input-container">
    <input 
      type="text" 
      id="user-input" 
      placeholder="Ask the agent to do something..." 
      onkeypress="if(event.key==='Enter') sendMessage()"
      autocomplete="off"
    />
    <button id="send-btn" onclick="sendMessage()">Send</button>
  </div>
  
  <script>
    const vscode = acquireVsCodeApi();
    let isProcessing = false;
    
    function sendMessage() {
      if (isProcessing) return;
      
      const input = document.getElementById('user-input');
      const text = input.value.trim();
      if (!text) return;
      
      isProcessing = true;
      input.value = '';
      input.disabled = true;
      document.getElementById('send-btn').disabled = true;
      
      // Add user message to chat
      addMessage('user', text);
      
      // Show processing indicator
      addProcessingMessage();
      
      // Send to extension
      vscode.postMessage({ command: 'sendMessage', text });
    }
    
    function openConfig() {
      vscode.postMessage({ command: 'openConfig' });
    }
    
    function reloadConfig() {
      vscode.postMessage({ command: 'reloadConfig' });
    }
    
    function clearHistory() {
      vscode.postMessage({ command: 'clearHistory' });
      document.getElementById('chat-container').innerHTML = \`
        <div class="empty-state">
          <h3>History cleared</h3>
          <p>Start a new conversation</p>
        </div>
      \`;
    }
    
    function addMessage(type, text, toolCalls, isError) {
      const container = document.getElementById('chat-container');
      
      // Remove empty state if present
      const emptyState = container.querySelector('.empty-state');
      if (emptyState) {
        emptyState.remove();
      }
      
      const div = document.createElement('div');
      div.className = 'message ' + type + '-message' + (isError ? ' error' : '');
      div.textContent = text;
      
      if (toolCalls && toolCalls.length > 0) {
        const toolsDiv = document.createElement('div');
        toolsDiv.className = 'tool-call';
        toolsDiv.textContent = '🔧 Tools: ' + toolCalls.join(', ');
        div.appendChild(toolsDiv);
      }
      
      container.appendChild(div);
      container.scrollTop = container.scrollHeight;
    }
    
    function addProcessingMessage() {
      const container = document.getElementById('chat-container');
      const div = document.createElement('div');
      div.className = 'message agent-message';
      div.id = 'processing-message';
      div.innerHTML = '<span class="processing-indicator"></span>Processing...';
      container.appendChild(div);
      container.scrollTop = container.scrollHeight;
    }
    
    function removeProcessingMessage() {
      const processingMsg = document.getElementById('processing-message');
      if (processingMsg) {
        processingMsg.remove();
      }
    }
    
    function addIterationInfo(iterations, durationMs) {
      const container = document.getElementById('chat-container');
      const lastMessage = container.lastElementChild;
      
      if (lastMessage && lastMessage.classList.contains('agent-message')) {
        const info = document.createElement('div');
        info.className = 'iteration-info';
        info.innerHTML = \`
          <span class="iteration-badge">\${iterations} iterations</span>
          <span>\${durationMs}ms</span>
        \`;
        lastMessage.appendChild(info);
      }
    }
    
    // Handle messages from extension
    window.addEventListener('message', event => {
      const message = event.data;
      
      switch (message.command) {
        case 'processing':
          // Already showing processing indicator
          break;
          
        case 'response':
          removeProcessingMessage();
          
          if (message.response.success) {
            const toolNames = message.response.toolCalls?.map(tc => tc.toolName);
            addMessage('agent', message.response.text, toolNames, false);
            addIterationInfo(message.response.iterations, message.response.durationMs);
          } else {
            addMessage('agent', message.response.text, null, true);
          }
          
          isProcessing = false;
          const input = document.getElementById('user-input');
          input.disabled = false;
          document.getElementById('send-btn').disabled = false;
          input.focus();
          break;
          
        case 'error':
          removeProcessingMessage();
          addMessage('agent', '❌ Error: ' + message.error, null, true);
          isProcessing = false;
          const input2 = document.getElementById('user-input');
          input2.disabled = false;
          document.getElementById('send-btn').disabled = false;
          break;
          
        case 'configReloaded':
          // Update the config bar
          const badges = document.querySelectorAll('.iteration-badge');
          if (badges.length > 1) {
            badges[1].textContent = 'Model: ' + message.config.model;
            badges[2].textContent = 'Max: ' + message.config.maxIterations + ' iter';
          }
          break;
      }
    });
  </script>
</body>
</html>`;
  }

  /**
   * Get VSCode font family for webview
   */
  private getVsCodeFontFamily(): string {
    return "system-ui, -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Oxygen, Ubuntu, Cantarell, 'Open Sans', 'Helvetica Neue', sans-serif";
  }
}
