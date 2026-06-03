/**
 * AgentBridge - Bridge between VSCode extension and conf-agent-core
 * 
 * This class wraps the agent core functionality and provides a clean API
 * for the AgentTabManager to interact with configured agents.
 * 
 * UPDATED: Fixed tool call parsing, improved logging, better error handling
 * DIAGNOSTIC: Added detailed logging to trace response flow
 * DIAGNOSTIC v2: Added try-catch logging around executeAgentLoop
 * DIAGNOSTIC v3: Added timeout wrapper around agent.process()
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
   * Process user input through the agent with timeout protection
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
      
      // Call the agent with timeout protection
      this.log(`=== DIAGNOSTIC: Calling agent.process() ===`);
      
      const AGENT_TIMEOUT_MS = 60000; // 60 second timeout
      const agentPromise = tab.agent.process({
        id: `request-${Date.now()}`,
        task: userInput,
        context: context
      });
      
      const timeoutPromise = new Promise<never>((_, reject) => {
        setTimeout(() => {
          reject(new Error(`agent.process() timed out after ${AGENT_TIMEOUT_MS}ms`));
        }, AGENT_TIMEOUT_MS);
      });
      
      const response = await Promise.race([agentPromise, timeoutPromise]);
      
      this.log(`=== DIAGNOSTIC: agent.process() returned ===`);
      this.log(`=== DIAGNOSTIC: Response object keys: ${Object.keys(response).join(', ')} `);
      this.log(`=== DIAGNOSTIC: Response type: ${typeof response}`);
      this.log(`=== DIAGNOSTIC: Response.finalText type: ${typeof response.finalText}`);
      this.log(`=== DIAGNOSTIC: Response.toolCalls type: ${typeof response.toolCalls}`);
      this.log(`=== DIAGNOSTIC: About to build postMessage payload`);
      this.log(`Response finalText length: ${response.finalText?.length || 0}`);
      this.log(`Response toolCalls count: ${response.toolCalls?.length || 0}`);
      this.log(`Response success: ${response.success}`);
      
      // Record the interaction
      const record: InteractionRecord = {
        timestamp: Date.now(),
        userInput,
        agentResponse: response.finalText || 'No response',
        toolCalls: response.toolCalls?.map(tc => ({
          toolName: tc.toolName,
          args: tc.args || {}
        })) || [],
        iterations: response.iterations,
        durationMs: Date.now() - startTime
      };
      
      tab.history.push(record);
      this.log(`Interaction recorded: ${record.iterations} iterations, ${record.durationMs}ms`);
      this.log(`Tool calls: ${response.toolCalls?.map(tc => tc.toolName).join(', ') || 'none'}`);
      
      // Update webview with result - include full tool call data
      this.log(`=== DIAGNOSTIC: About to postMessage to webview`);
      this.log(`=== DIAGNOSTIC: response.finalText length: ${response.finalText?.length || 0}`);
      this.log(`=== DIAGNOSTIC: response.toolCalls count: ${response.toolCalls?.length || 0}`);
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
      this.log(`=== DIAGNOSTIC: postMessage sent successfully`);
    } catch (error: any) {
      this.log(`Error processing input: ${error.message}`);
      this.log(`Stack trace: ${error.stack}`);
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
  getActiveTabs(): string[] {
    return Array.from(this.tabs.keys());
  }

  /**
   * Get tab details
   */
  getTab(tabId: string): AgentTab | undefined {
    return this.tabs.get(tabId);
  }

  /**
   * Log a message to the output channel
   */
  private log(message: string) {
    const timestamp = new Date().toISOString().split('T')[1].split('.')[0];
    this.outputChannel.appendLine(`[AgentTabManager] ${message}`);
  }

  /**
   * Capitalize a string
   */
  private capitalize(str: string): string {
    return str.charAt(0).toUpperCase() + str.slice(1);
  }

  /**
   * Get webview content for the agent panel
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
      color: var(--vscode-foreground);
      background-color: var(--vscode-editor-background);
    }
    .chat-container {
      max-width: 800px;
      margin: 0 auto;
    }
    .input-area {
      display: flex;
      gap: 10px;
      margin-bottom: 20px;
    }
    .input-area input {
      flex: 1;
      padding: 8px 12px;
      border: 1px solid var(--vscode-input-border);
      background-color: var(--vscode-input-background);
      color: var(--vscode-input-foreground);
      border-radius: 4px;
    }
    .input-area button {
      padding: 8px 16px;
      background-color: var(--vscode-button-background);
      color: var(--vscode-button-foreground);
      border: none;
      border-radius: 4px;
      cursor: pointer;
    }
    .input-area button:hover {
      background-color: var(--vscode-button-hoverBackground);
    }
    .input-area button:disabled {
      opacity: 0.5;
      cursor: not-allowed;
    }
    .messages {
      border: 1px solid var(--vscode-panel-border);
      border-radius: 4px;
      padding: 15px;
      min-height: 300px;
      max-height: 600px;
      overflow-y: auto;
    }
    .message {
      margin-bottom: 15px;
      padding: 10px;
      border-radius: 4px;
    }
    .message.user {
      background-color: var(--vscode-input-background);
      border-left: 3px solid var(--vscode-button-background);
    }
    .message.agent {
      background-color: var(--vscode-editor-inactiveSelectionBackground);
      border-left: 3px solid var(--vscode-editor-foreground);
    }
    .message.error {
      background-color: var(--vscode-inputValidation-errorBackground);
      border-left: 3px solid var(--vscode-errorForeground);
    }
    .message-meta {
      font-size: 0.8em;
      color: var(--vscode-descriptionForeground);
      margin-top: 5px;
    }
    .config-info {
      margin-bottom: 15px;
      padding: 10px;
      background-color: var(--vscode-editor-inactiveSelectionBackground);
      border-radius: 4px;
      font-size: 0.9em;
    }
    .config-info strong {
      color: var(--vscode-button-background);
    }
    .toolbar {
      display: flex;
      gap: 10px;
      margin-bottom: 15px;
    }
    .toolbar button {
      padding: 6px 12px;
      background-color: var(--vscode-button-secondaryBackground);
      color: var(--vscode-button-secondaryForeground);
      border: none;
      border-radius: 4px;
      cursor: pointer;
      font-size: 0.9em;
    }
    .toolbar button:hover {
      background-color: var(--vscode-button-secondaryHoverBackground);
    }
    .tool-call {
      background-color: var(--vscode-editor-selectionBackground);
      padding: 8px;
      border-radius: 4px;
      margin-top: 8px;
      font-family: var(--vscode-editor-font-family);
      font-size: 0.85em;
    }
  </style>
</head>
<body>
  <div class="chat-container">
    <div class="config-info">
      <strong>Agent:</strong> ${this.capitalize(layer)} Agent (Ollama ${config.model.id})<br>
      <strong>Max Iterations:</strong> ${config.iterationSettings.maxIterations}
    </div>
    
    <div class="toolbar">
      <button id="clearBtn">Clear History</button>
      <button id="reloadBtn">Reload Config</button>
      <button id="configBtn">Open Config</button>
    </div>
    
    <div class="messages" id="messages">
      <div class="message agent">
        <div class="message-content">Hello! I'm your ${this.capitalize(layer)} Agent. How can I help you with VSLFC today?</div>
      </div>
    </div>
    
    <div class="input-area">
      <input type="text" id="userInput" placeholder="Type your message..." />
      <button id="sendBtn">Send</button>
    </div>
  </div>

  <script>
    const vscode = acquireVsCodeApi();
    const messagesDiv = document.getElementById('messages');
    const userInput = document.getElementById('userInput');
    const sendBtn = document.getElementById('sendBtn');
    const clearBtn = document.getElementById('clearBtn');
    const reloadBtn = document.getElementById('reloadBtn');
    const configBtn = document.getElementById('configBtn');

    let isProcessing = false;

    function addMessage(content, type, meta = null) {
      const msgDiv = document.createElement('div');
      msgDiv.className = 'message ' + type;
      
      const contentDiv = document.createElement('div');
      contentDiv.className = 'message-content';
      contentDiv.textContent = content;
      msgDiv.appendChild(contentDiv);
      
      if (meta) {
        const metaDiv = document.createElement('div');
        metaDiv.className = 'message-meta';
        metaDiv.textContent = meta;
        msgDiv.appendChild(metaDiv);
      }
      
      messagesDiv.appendChild(msgDiv);
      messagesDiv.scrollTop = messagesDiv.scrollHeight;
    }

    function addToolCall(toolCall) {
      const toolDiv = document.createElement('div');
      toolDiv.className = 'tool-call';
      toolDiv.innerHTML = '<strong>Tool:</strong> ' + toolCall.toolName + 
                          '<br><strong>Args:</strong> ' + JSON.stringify(toolCall.arguments, null, 2);
      messagesDiv.appendChild(toolDiv);
      messagesDiv.scrollTop = messagesDiv.scrollHeight;
    }

    sendBtn.addEventListener('click', sendMessage);
    userInput.addEventListener('keypress', (e) => {
      if (e.key === 'Enter') sendMessage();
    });

    clearBtn.addEventListener('click', () => {
      vscode.postMessage({ command: 'clearHistory' });
      messagesDiv.innerHTML = '<div class="message agent"><div class="message-content">History cleared. How can I help?</div></div>';
    });

    reloadBtn.addEventListener('click', () => {
      vscode.postMessage({ command: 'reloadConfig' });
    });

    configBtn.addEventListener('click', () => {
      vscode.postMessage({ command: 'openConfig' });
    });

    function sendMessage() {
      const text = userInput.value.trim();
      if (!text || isProcessing) return;
      
      isProcessing = true;
      sendBtn.disabled = true;
      userInput.disabled = true;
      
      addMessage(text, 'user');
      userInput.value = '';
      
      vscode.postMessage({
        command: 'sendMessage',
        text: text
      });
    }

    window.addEventListener('message', event => {
      const message = event.data;
      
      switch (message.command) {
        case 'processing':
          addMessage('Processing: ' + message.userInput, 'agent');
          break;
          
        case 'response':
          isProcessing = false;
          sendBtn.disabled = false;
          userInput.disabled = false;
          
          if (message.response.text) {
            const meta = message.response.iterations + ' iterations, ' + 
                        message.response.durationMs + 'ms';
            addMessage(message.response.text, 'agent', meta);
          }
          
          // Display tool calls if present
          if (message.response.toolCalls && message.response.toolCalls.length > 0) {
            message.response.toolCalls.forEach(tc => addToolCall(tc));
          }
          break;
          
        case 'error':
          isProcessing = false;
          sendBtn.disabled = false;
          userInput.disabled = false;
          addMessage('Error: ' + message.error, 'error');
          break;
          
        case 'updateHistory':
          messagesDiv.innerHTML = '';
          message.history.forEach(record => {
            addMessage(record.userInput, 'user');
            const meta = record.iterations + ' iterations, ' + record.durationMs + 'ms';
            addMessage(record.agentResponse, 'agent', meta);
            if (record.toolCalls && record.toolCalls.length > 0) {
              record.toolCalls.forEach(tc => addToolCall({toolName: tc, arguments: {}}));
            }
          });
          break;
          
        case 'configReloaded':
          vscode.showInformationMessage('Configuration reloaded from disk');
          break;
      }
    });
  </script>
</body>
</html>`;
  }
}
