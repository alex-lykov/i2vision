/**
 * AgentTabManager - Manages agent tabs in VSCode
 * 
 * Creates and manages webview panels for each agent instance,
 * using LocalAgentProvider to create agents dynamically.
 * 
 * Updated for Option C: Uses LocalAgentProvider instead of direct AgentBridge
 * FIXED: Tool card display with proper emoji encoding and better tool result visualization
 */

import * as vscode from 'vscode';
import * as path from 'path';
import * as fs from 'fs';
import * as yaml from 'js-yaml';
import { LocalAgentProvider } from './LocalAgentProvider';
import { LocalI2VisionAgent, VslfcLayer, AgentContext } from './LocalI2VisionAgent';
import { AgentConfig, InteractionRecord, ToolCall } from './AgentBridge';

/**
 * Agent tab representation
 */
interface AgentTab {
  id: string;
  layer: 'vision' | 'structure' | 'logic' | 'flow' | 'code';
  agent: LocalI2VisionAgent;
  panel: vscode.WebviewPanel;
  history: InteractionRecord[];
  isProcessing: boolean;
}

/**
 * AgentTabManager - Creates and manages agent tabs
 */
export class AgentTabManager {
  private tabs: Map<string, AgentTab> = new Map();
  private provider: LocalAgentProvider;
  private outputChannel: vscode.OutputChannel;

  constructor(
    private context: vscode.ExtensionContext,
    outputChannel: vscode.OutputChannel
  ) {
    this.outputChannel = outputChannel;
    
    // Create the local agent provider
    this.provider = new LocalAgentProvider(context, outputChannel);
    
    this.log('AgentTabManager initialized with LocalAgentProvider');
  }

  /**
   * Initialize the manager and provider
   */
  async initialize(): Promise<void> {
    await this.provider.initialize();
    this.log('AgentTabManager initialization complete');
  }

  /**
   * Clear the config cache to force reload from disk
   */
  clearConfigCache(): void {
    this.provider.clearConfigCache();
    this.log('Config cache cleared - next agent creation will reload from disk');
  }

  /**
   * Create a new agent tab for a specific VSLFC layer
   */
  async createTab(layer: 'vision' | 'structure' | 'logic' | 'flow' | 'code'): Promise<string> {
    this.log(`Creating ${layer} agent tab...`);
    
    try {
      // Create the agent using the provider
      const vslfcLayer = VslfcLayer[layer.toUpperCase() as keyof typeof VslfcLayer];
      const agent = await this.provider.createAgent(vslfcLayer);
      
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
        id: agent.id,
        layer,
        agent,
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
      panel.webview.html = this.getWebviewContent(layer, agent.getConfig());
      
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
        await this.reloadAgent(tab);
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
      
      // Prepare agent request
      const context: AgentContext = {
        workspaceRoot: vscode.workspace.workspaceFolders?.[0]?.uri.fsPath || '',
        currentFile,
        sessionId: tab.id
      };
      
      // Call the agent
      const response = await tab.agent.process({
        id: `request-${Date.now()}`,
        task: userInput,
        context: context
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
      this.log(`Tool calls: ${response.toolCalls?.map(tc => tc.toolName).join(', ') || 'none'}`);
      
      // Update webview with result - include full tool call data
      tab.panel.webview.postMessage({
        command: 'response',
        response: {
          text: response.finalText,
          toolCalls: response.toolCalls, // Send full tool call objects, not just names
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
    const configPath = path.join(
      vscode.workspace.workspaceFolders?.[0]?.uri.fsPath || '',
      '.vision-ai',
      `${tab.layer.toLowerCase()}-agent.yaml`
    );
    
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
   * Reload agent with new configuration
   */
  private async reloadAgent(tab: AgentTab) {
    try {
      // Clear the config cache to force reload from disk
      this.provider.clearConfigCache();
      this.log('Config cache cleared before reload');
      
      // Create a new agent with the same layer (will load fresh config from disk)
      const vslfcLayer = VslfcLayer[tab.layer.toUpperCase() as keyof typeof VslfcLayer];
      const newAgent = await this.provider.createAgent(vslfcLayer);
      
      // Dispose old agent
      await tab.agent.dispose();
      
      // Replace with new agent
      tab.agent = newAgent;
      
      this.log(`Reloaded agent for tab: ${tab.id}`);
      this.log(`New config - Model: ${newAgent.getConfig().model.id}, Max Iterations: ${newAgent.getConfig().iterationSettings.maxIterations}`);
      
      tab.panel.webview.postMessage({
        command: 'configReloaded',
        config: {
          model: newAgent.getConfig().model.id,
          maxIterations: newAgent.getConfig().iterationSettings.maxIterations
        }
      });
      
      vscode.window.showInformationMessage(`${this.capitalize(tab.layer)} Agent configuration reloaded from disk`);
    } catch (error: any) {
      this.log(`Error reloading agent: ${error.message}`);
      vscode.window.showErrorMessage(`Failed to reload agent: ${error.message}`);
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
  private async closeTab(tabId: string) {
    const tab = this.tabs.get(tabId);
    if (tab) {
      this.log(`Closing tab: ${tabId}`);
      try {
        await tab.agent.dispose();
      } catch (error: any) {
        this.log(`Error disposing agent: ${error.message}`);
      }
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
   * Get webview HTML content
   */
  private getWebviewContent(layer: string, config: AgentConfig): string {
    return `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>i2-Vision ${this.capitalize(layer)} Agent</title>
  <style>
    body {
      font-family: var(--vscode-font-family);
      padding: 20px;
      background-color: var(--vscode-editor-background);
      color: var(--vscode-editor-foreground);
    }
    .chat-container {
      display: flex;
      flex-direction: column;
      height: calc(100vh - 100px);
    }
    .messages {
      flex: 1;
      overflow-y: auto;
      border: 1px solid var(--vscode-widget-border);
      padding: 10px;
      margin-bottom: 10px;
    }
    .message {
      margin-bottom: 10px;
      padding: 8px;
      border-radius: 4px;
    }
    .message.user {
      background-color: var(--vscode-input-background);
      margin-left: 20%;
    }
    .message.agent {
      background-color: var(--vscode-editor-inactiveSelectionBackground);
      margin-right: 20%;
    }
    .input-area {
      display: flex;
      gap: 10px;
    }
    textarea {
      flex: 1;
      resize: none;
      height: 60px;
      background-color: var(--vscode-input-background);
      color: var(--vscode-input-foreground);
      border: 1px solid var(--vscode-input-border);
      padding: 8px;
    }
    button {
      padding: 8px 16px;
      background-color: var(--vscode-button-background);
      color: var(--vscode-button-foreground);
      border: none;
      cursor: pointer;
    }
    button:hover {
      background-color: var(--vscode-button-hoverBackground);
    }
    button:disabled {
      opacity: 0.5;
      cursor: not-allowed;
    }
    .status {
      font-size: 12px;
      color: var(--vscode-descriptionForeground);
      margin-bottom: 10px;
    }
    .tool-calls {
      margin-top: 10px;
      padding: 10px;
      background-color: var(--vscode-editor-selectionBackground);
      border-radius: 4px;
      border-left: 3px solid var(--vscode-button-background);
    }
    .tool-calls h4 {
      margin: 0 0 8px 0;
      font-size: 13px;
      color: var(--vscode-button-foreground);
    }
    .tool-call {
      margin-bottom: 8px;
      padding: 6px;
      background-color: var(--vscode-editor-background);
      border-radius: 3px;
      font-size: 12px;
    }
    .tool-call-header {
      display: flex;
      align-items: center;
      gap: 6px;
      margin-bottom: 4px;
    }
    .tool-name {
      font-weight: bold;
      color: var(--vscode-textLink-foreground);
    }
    .tool-status {
      font-size: 11px;
      padding: 2px 6px;
      border-radius: 3px;
    }
    .tool-status.success {
      background-color: rgba(0, 255, 0, 0.2);
      color: #4caf50;
    }
    .tool-status.error {
      background-color: rgba(255, 0, 0, 0.2);
      color: #f44336;
    }
    .tool-args, .tool-result {
      margin-top: 4px;
      font-family: monospace;
      font-size: 11px;
      white-space: pre-wrap;
      word-break: break-all;
    }
    .tool-args {
      color: var(--vscode-descriptionForeground);
    }
    .tool-result {
      color: var(--vscode-editor-foreground);
      background-color: var(--vscode-editor-selectionBackground);
      padding: 4px;
      border-radius: 2px;
    }
    .reasoning {
      margin-bottom: 10px;
      white-space: pre-wrap;
    }
    .iteration-info {
      font-size: 11px;
      color: var(--vscode-descriptionForeground);
      margin-top: 5px;
    }
  </style>
</head>
<body>
  <h2>i2-Vision ${this.capitalize(layer)} Agent</h2>
  <div class="status">
    Model: ${config.model.id} | Max Iterations: ${config.iterationSettings.maxIterations}
  </div>
  <div class="chat-container">
    <div class="messages" id="messages"></div>
    <div class="input-area">
      <textarea id="input" placeholder="Ask the agent to analyze, explain, or modify code..."></textarea>
      <button id="send" onclick="sendMessage()">Send</button>
      <button id="clear" onclick="clearHistory()">Clear</button>
      <button id="reload" onclick="reloadConfig()">♻️ Reload</button>
      <button id="config" onclick="openConfig()">Config</button>
    </div>
  </div>
  <script>
    const vscode = acquireVsCodeApi();
    const messagesEl = document.getElementById('messages');
    const inputEl = document.getElementById('input');
    const sendBtn = document.getElementById('send');
    
    let isProcessing = false;
    
    function sendMessage() {
      const text = inputEl.value.trim();
      if (!text || isProcessing) return;
      
      isProcessing = true;
      sendBtn.disabled = true;
      
      // Add user message to UI
      addMessage(text, 'user');
      
      // Send to extension
      vscode.postMessage({ command: 'sendMessage', text });
      inputEl.value = '';
    }
    
    function addMessage(text, type, toolCalls) {
      const div = document.createElement('div');
      div.className = 'message ' + type;
      
      if (type === 'agent' && toolCalls && toolCalls.length > 0) {
        // Split reasoning from tool results if present
        let reasoning = text;
        const toolResultsIndex = text.indexOf('\\n\\nTool results:\\n');
        if (toolResultsIndex !== -1) {
          reasoning = text.substring(0, toolResultsIndex);
        }
        
        let html = '<div class="reasoning">' + escapeHtml(reasoning) + '</div>';
        html += '<div class="tool-calls"><h4>🛠️ Tools Used (' + toolCalls.length + ')</h4>';
        
        for (const tc of toolCalls) {
          html += '<div class="tool-call">';
          html += '<div class="tool-call-header">';
          html += '<span class="tool-name">' + escapeHtml(tc.toolName) + '</span>';
          if (tc.error) {
            html += '<span class="tool-status error">❌ Error</span>';
          } else {
            html += '<span class="tool-status success">✅ Success</span>';
          }
          html += '</div>';
          
          if (tc.args && Object.keys(tc.args).length > 0) {
            html += '<div class="tool-args"><strong>Args:</strong> ' + escapeHtml(JSON.stringify(tc.args, null, 2)) + '</div>';
          }
          
          if (tc.result) {
            const preview = tc.result.length > 500 ? tc.result.substring(0, 500) + '... (truncated)' : tc.result;
            html += '<div class="tool-result"><strong>Result:</strong> ' + escapeHtml(preview) + '</div>';
          }
          
          if (tc.error) {
            html += '<div class="tool-result" style="color: #f44336;"><strong>Error:</strong> ' + escapeHtml(tc.error) + '</div>';
          }
          
          html += '</div>';
        }
        
        html += '</div>';
        div.innerHTML = html;
      } else {
        div.textContent = text || '';
      }
      
      messagesEl.appendChild(div);
      messagesEl.scrollTop = messagesEl.scrollHeight;
    }
    
    function escapeHtml(text) {
      if (!text) return '';
      const div = document.createElement('div');
      div.textContent = text;
      return div.innerHTML;
    }
    
    function clearHistory() {
      vscode.postMessage({ command: 'clearHistory' });
      messagesEl.innerHTML = '';
    }
    
    function openConfig() {
      vscode.postMessage({ command: 'openConfig' });
    }
    
    function reloadConfig() {
      vscode.postMessage({ command: 'reloadConfig' });
    }
    
    // Handle messages from extension
    window.addEventListener('message', event => {
      const message = event.data;
      
      switch (message.command) {
        case 'processing':
          addMessage('Processing...', 'agent', null);
          break;
          
        case 'response':
          isProcessing = false;
          sendBtn.disabled = false;
          if (message.response.text || (message.response.toolCalls && message.response.toolCalls.length > 0)) {
            addMessage(message.response.text, 'agent', message.response.toolCalls);
          }
          // Show iteration info
          const infoDiv = document.createElement('div');
          infoDiv.className = 'iteration-info';
          infoDiv.textContent = '⏱️ ' + message.response.durationMs + 'ms | Iterations: ' + message.response.iterations;
          messagesEl.appendChild(infoDiv);
          messagesEl.scrollTop = messagesEl.scrollHeight;
          break;
          
        case 'error':
          isProcessing = false;
          sendBtn.disabled = false;
          addMessage('Error: ' + message.error, 'agent', null);
          break;
          
        case 'configReloaded':
          // Update the status bar with new config
          const statusEl = document.querySelector('.status');
          if (statusEl && message.config) {
            statusEl.textContent = 'Model: ' + message.config.model + ' | Max Iterations: ' + message.config.maxIterations;
          }
          console.log('Config reloaded:', message.config);
          break;
          
        case 'updateHistory':
          messagesEl.innerHTML = '';
          for (const record of message.history) {
            addMessage(record.userInput, 'user');
            addMessage(record.agentResponse, 'agent', null);
          }
          break;
      }
    });
    
    // Enter to send, Shift+Enter for new line
    inputEl.addEventListener('keydown', e => {
      if (e.key === 'Enter' && !e.shiftKey) {
        e.preventDefault();
        sendMessage();
      }
    });
  </script>
</body>
</html>`;
  }
}
