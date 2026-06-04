/**
 * AgentTabManager - Creates and manages agent tabs
 * 
 * UPDATED: Added real-time progress streaming for tool calls
 */

import * as vscode from 'vscode';
import * as path from 'path';
import * as fs from 'fs';
import * as yaml from 'js-yaml';
import { LocalAgentProvider } from './LocalAgentProvider';
import { LocalI2VisionAgent, VslfcLayer, AgentContext } from './LocalI2VisionAgent';
import { AgentConfig, InteractionRecord, ToolCall, ProgressEvent } from './AgentBridge';

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
        this.closeTab(tab.id);
      });
      
      // Store the tab
      this.tabs.set(tab.id, tab);
      
      // Initialize the webview content
      this.updateWebview(tab);
      
      this.log(`Created ${layer} agent tab: ${tab.id}`);
      
      return tab.id;
    } catch (error: any) {
      this.log(`Error creating tab: ${error.message}`);
      vscode.window.showErrorMessage(`Failed to create agent tab: ${error.message}`);
      throw error;
    }
  }

  /**
   * Handle messages from the webview
   */
  private async handleWebviewMessage(tab: AgentTab, message: any) {
    switch (message.command) {
      case 'sendMessage':
        await this.processUserInput(tab, message.text);
        break;
      case 'openConfig':
        await this.openConfigFile(tab);
        break;
      case 'reloadConfig':
        await this.reloadAgent(tab);
        break;
    }
  }

  /**
   * Process user input through the agent with timeout protection and real-time progress
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
      
      // Call the agent with timeout protection and progress callback
      this.log(`=== DIAGNOSTIC: Calling agent.process() with progress callback ===`);
      
      const AGENT_TIMEOUT_MS = 60000; // 60 second timeout
      
      const agentPromise = tab.agent.process({
        id: `request-${Date.now()}`,
        task: userInput,
        context: context
      }, undefined, (event: ProgressEvent) => {
        // Forward progress events to webview in real-time
        this.log(`Progress event: ${event.type} at iteration ${event.iteration}`);
        tab.panel.webview.postMessage({
          command: 'progress',
          event: event
        });
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
      this.log(`=== DIAGNOSTIC: About to postMessage to webview ===`);
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
      this.log(`=== DIAGNOSTIC: postMessage sent successfully ===`);
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
    } catch (error: any) {
      this.log(`Error reloading agent: ${error.message}`);
      vscode.window.showErrorMessage(`Failed to reload agent: ${error.message}`);
    }
  }

  /**
   * Close a tab
   */
  closeTab(tabId: string): void {
    const tab = this.tabs.get(tabId);
    if (tab) {
      tab.panel.dispose();
      this.tabs.delete(tabId);
      this.log(`Closed tab: ${tab.id}`);
    }
  }

  /**
   * Update webview content
   */
  private updateWebview(tab: AgentTab): void {
    tab.panel.webview.html = this.getWebviewContent(tab.layer, tab.agent.getConfig(), tab.history);
  }

  /**
   * Get webview HTML content
   */
  private getWebviewContent(layer: string, config: AgentConfig, history: InteractionRecord[] = []): string {
    const modelId = config.model.id;
    const maxIterations = config.iterationSettings.maxIterations;
    
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
        .message {
            margin: 10px 0;
            padding: 10px;
            border-radius: 4px;
        }
        .user-message {
            background-color: var(--vscode-editor-selectionBackground);
            border-left: 3px solid var(--vscode-editorCursor-foreground);
        }
        .agent-message {
            background-color: var(--vscode-editor-inactiveSelectionBackground);
            border-left: 3px solid var(--vscode-terminal-ansiBlue);
        }
        .tool-call-message {
            margin: 5px 0;
            padding: 8px;
            border-left: 3px solid var(--vscode-terminal-ansiCyan);
            font-family: var(--vscode-editor-font-family);
            font-size: 13px;
        }
        .tool-call-complete {
            border-left-color: var(--vscode-terminal-ansiGreen);
        }
        .tool-call-error {
            border-left-color: var(--vscode-terminal-ansiRed);
        }
        .progress-indicator {
            display: flex;
            align-items: center;
            gap: 10px;
            margin: 10px 0;
            padding: 10px;
            background: var(--vscode-editor-inactiveSelectionBackground);
            border-radius: 4px;
        }
        .spinner {
            width: 16px;
            height: 16px;
            border: 2px solid var(--vscode-progressBar-background);
            border-top-color: transparent;
            border-radius: 50%;
            animation: spin 1s linear infinite;
        }
        @keyframes spin {
            to { transform: rotate(360deg); }
        }
        .iteration-info {
            font-size: 12px;
            color: var(--vscode-descriptionForeground);
        }
        .thinking {
            color: var(--vscode-descriptionForeground);
            font-style: italic;
        }
        .error {
            background: var(--vscode-inputValidation-errorBackground);
            border: 1px solid var(--vscode-inputValidation-errorBorder);
            color: var(--vscode-errorForeground);
        }
        .config-info {
            font-size: 12px;
            color: var(--vscode-descriptionForeground);
            margin-bottom: 20px;
        }
        .input-row {
            display: flex;
            gap: 10px;
            margin-top: 20px;
            position: sticky;
            bottom: 0;
            background: var(--vscode-editor-background);
            padding: 10px 0;
        }
        #userInput {
            flex: 1;
            padding: 8px 12px;
            border: 1px solid var(--vscode-input-border);
            border-radius: 4px;
            background: var(--vscode-input-background);
            color: var(--vscode-input-foreground);
            font-family: var(--vscode-font-family);
        }
        #userInput:focus {
            outline: 2px solid var(--vscode-focusBorder);
        }
        #sendButton {
            padding: 8px 16px;
            background: var(--vscode-button-background);
            color: var(--vscode-button-foreground);
            border: none;
            border-radius: 4px;
            cursor: pointer;
        }
        #sendButton:hover {
            background: var(--vscode-button-hoverBackground);
        }
        #sendButton:disabled {
            opacity: 0.5;
            cursor: not-allowed;
        }
    </style>
</head>
<body>
    <div class="config-info">
        <strong>Model:</strong> ${modelId} | <strong>Max Iterations:</strong> ${maxIterations}
    </div>
    
    <div id="messages"></div>
    
    <div class="input-row">
        <input type="text" id="userInput" placeholder="Ask me anything about your code..." onkeypress="handleKeyPress(event)">
        <button onclick="sendMessage()" id="sendButton">Send</button>
    </div>

    <script>
        const vscode = acquireVsCodeApi();
        const messagesDiv = document.getElementById('messages');
        const sendButton = document.getElementById('sendButton');
        const userInput = document.getElementById('userInput');
        
        let currentProgressDiv = null;
        
        function handleKeyPress(event) {
            if (event.key === 'Enter' && !event.shiftKey) {
                event.preventDefault();
                sendMessage();
            }
        }
        
        function sendMessage() {
            const text = userInput.value.trim();
            if (!text) return;
            
            // Disable input while processing
            userInput.disabled = true;
            sendButton.disabled = true;
            
            // Add user message to chat
            addMessage('user', text);
            
            // Clear previous progress indicators
            if (currentProgressDiv) {
                currentProgressDiv.remove();
                currentProgressDiv = null;
            }
            
            // Send to extension
            vscode.postMessage({ command: 'sendMessage', text });
            
            // Clear input
            userInput.value = '';
        }
        
        function addMessage(type, content, isHtml = false) {
            const div = document.createElement('div');
            div.className = 'message ' + (type === 'user' ? 'user-message' : 'agent-message');
            
            if (isHtml) {
                div.innerHTML = content;
            } else {
                div.textContent = content;
            }
            
            messagesDiv.appendChild(div);
            div.scrollIntoView({ behavior: 'smooth' });
        }
        
        function addToolCallMessage(toolCall, type) {
            const div = document.createElement('div');
            div.className = 'tool-call-message ' + (type === 'complete' ? 'tool-call-complete' : (toolCall.error ? 'tool-call-error' : ''));
            
            let content = '<strong>' + toolCall.toolName + '</strong>(';
            for (const [key, value] of Object.entries(toolCall.args || {})) {
                content += key + ': ' + JSON.stringify(value) + ', ';
            }
            content = content.replace(/, $/, '') + ')';
            
            if (type === 'complete') {
                content += ' → <em>Completed</em>';
            }
            if (toolCall.error) {
                content += ' → <strong style="color: var(--vscode-errorForeground)">Error: ' + toolCall.error + '</strong>';
            }
            
            div.innerHTML = content;
            messagesDiv.appendChild(div);
            div.scrollIntoView({ behavior: 'smooth' });
        }
        
        function showProgress(message) {
            if (currentProgressDiv) {
                currentProgressDiv.remove();
            }
            
            currentProgressDiv = document.createElement('div');
            currentProgressDiv.className = 'progress-indicator';
            currentProgressDiv.innerHTML = '<div class="spinner"></div><span>' + message + '</span>';
            messagesDiv.appendChild(currentProgressDiv);
            currentProgressDiv.scrollIntoView({ behavior: 'smooth' });
        }
        
        function hideProgress() {
            if (currentProgressDiv) {
                currentProgressDiv.remove();
                currentProgressDiv = null;
            }
        }
        
        // Handle messages from extension
        window.addEventListener('message', event => {
            const message = event.data;
            
            switch (message.command) {
                case 'processing':
                    showProgress('Processing: ' + message.userInput.substring(0, 50) + '...');
                    break;
                    
                case 'progress':
                    const event = message.event;
                    if (event.type === 'thinking') {
                        showProgress(event.message);
                    } else if (event.type === 'tool_start') {
                        if (event.toolCall) {
                            addToolCallMessage(event.toolCall, 'start');
                        }
                    } else if (event.type === 'tool_complete') {
                        if (event.toolCall) {
                            addToolCallMessage(event.toolCall, 'complete');
                        }
                    } else if (event.type === 'iteration_complete') {
                        // Optional: show iteration complete message
                    }
                    break;
                    
                case 'response':
                    hideProgress();
                    
                    let responseHtml = '<div>' + message.response.text.replace(/\\n/g, '<br>') + '</div>';
                    
                    if (message.response.toolCalls && message.response.toolCalls.length > 0) {
                        responseHtml += '<div class="iteration-info">Tools Used: ' + message.response.toolCalls.map(tc => tc.toolName).join(', ') + '</div>';
                    }
                    
                    responseHtml += '<div class="iteration-info">Iterations: ' + message.response.iterations + ' | Duration: ' + message.response.durationMs + 'ms</div>';
                    
                    addMessage('agent', responseHtml, true);
                    
                    // Re-enable input
                    userInput.disabled = false;
                    sendButton.disabled = false;
                    userInput.focus();
                    break;
                    
                case 'error':
                    hideProgress();
                    addMessage('agent', 'Error: ' + message.error, false);
                    userInput.disabled = false;
                    sendButton.disabled = false;
                    break;
                    
                case 'configReloaded':
                    location.reload();
                    break;
            }
        });
    </script>
</body>
</html>`;
  }

  /**
   * Capitalize first letter
   */
  private capitalize(str: string): string {
    return str.charAt(0).toUpperCase() + str.slice(1);
  }

  /**
   * Log a message
   */
  private log(message: string): void {
    const timestamp = new Date().toLocaleTimeString();
    const formatted = `[${timestamp}] [AgentTabManager] ${message}`;
    this.outputChannel.appendLine(formatted);
    console.log(formatted);
  }
}
