/**
 * AgentTabManager - Creates and manages agent tabs
 * 
 * UPDATED: Added real-time progress streaming for tool calls
 * UPDATED: Added stop/cancel control with combined send/stop button
 * UPDATED: Display tool results in real-time
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
  cancelToken?: vscode.CancellationTokenSource;
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
      case 'stopAgent':
        await this.stopAgent(tab);
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
   * Stop/cancel the currently running agent
   */
  private async stopAgent(tab: AgentTab): Promise<void> {
    if (!tab.isProcessing) {
      this.log('Agent is not processing, nothing to stop');
      return;
    }
    
    this.log('Stopping agent...');
    
    // Cancel the token
    if (tab.cancelToken) {
      tab.cancelToken.cancel();
      tab.cancelToken.dispose();
      tab.cancelToken = undefined;
    }
    
    // Update UI
    tab.panel.webview.postMessage({
      command: 'stopped'
    });
    
    tab.isProcessing = false;
    this.log('Agent stopped');
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
    
    // Create cancellation token
    tab.cancelToken = new vscode.CancellationTokenSource();
    
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
      
      // Check if it was a cancellation
      if (error.message === 'cancelled' || (tab.cancelToken && tab.cancelToken.token.isCancellationRequested)) {
        tab.panel.webview.postMessage({
          command: 'stopped'
        });
      } else {
        tab.panel.webview.postMessage({
          command: 'error',
          error: String(error)
        });
      }
    } finally {
      // Clean up cancellation token
      if (tab.cancelToken) {
        tab.cancelToken.dispose();
        tab.cancelToken = undefined;
      }
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
   * Close an agent tab
   */
  async closeTab(tabId: string): Promise<void> {
    const tab = this.tabs.get(tabId);
    if (!tab) {
      this.log(`Tab ${tabId} not found`);
      return;
    }
    
    this.log(`Closing tab: ${tabId}`);
    
    // Cancel any ongoing processing
    if (tab.cancelToken) {
      tab.cancelToken.cancel();
      tab.cancelToken.dispose();
    }
    
    // Dispose the agent
    await tab.agent.dispose();
    
    // Remove from tracking
    this.tabs.delete(tabId);
    
    this.log(`Tab closed: ${tabId}`);
  }

  /**
   * Update webview HTML content
   */
  private updateWebview(tab: AgentTab) {
    const modelId = this.provider.getConfig(tab.layer).model.id;
    const maxIterations = this.provider.getConfig(tab.layer).iterationSettings.maxIterations;
    
    tab.panel.webview.html = this.getWebviewContent(tab, modelId, maxIterations);
  }

  /**
   * Get webview HTML content
   */
  private getWebviewContent(tab: AgentTab, modelId: string, maxIterations: number): string {
    return `<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>i2-Vision ${this.capitalize(tab.layer)} Agent</title>
    <style>
        :root {
            --vscode-font-family: var(--vscode-font-family, 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif);
            --vscode-foreground: var(--vscode-foreground, #333);
            --vscode-editor-background: var(--vscode-editor-background, #fff);
            --vscode-input-background: var(--vscode-input-background, #f0f0f0);
            --vscode-input-foreground: var(--vscode-input-foreground, #333);
            --vscode-button-background: var(--vscode-button-background, #007acc);
            --vscode-button-foreground: var(--vscode-button-foreground, #fff);
            --vscode-button-hoverBackground: var(--vscode-button-hoverBackground, #005a9e);
            --vscode-focusBorder: var(--vscode-focusBorder, #007acc);
            --vscode-errorForeground: var(--vscode-errorForeground, #f00);
            --vscode-descriptionForeground: var(--vscode-descriptionForeground, #666);
        }
        
        body {
            font-family: var(--vscode-font-family);
            color: var(--vscode-foreground);
            background: var(--vscode-editor-background);
            margin: 0;
            padding: 10px;
            display: flex;
            flex-direction: column;
            height: 100vh;
            box-sizing: border-box;
        }
        
        .config-info {
            font-size: 12px;
            color: var(--vscode-descriptionForeground);
            margin-bottom: 10px;
            padding: 5px;
            border-bottom: 1px solid var(--vscode-editor-background);
        }
        
        #messages {
            flex: 1;
            overflow-y: auto;
            margin-bottom: 10px;
            padding: 10px;
            border: 1px solid var(--vscode-editor-background);
            border-radius: 4px;
        }
        
        .message {
            margin-bottom: 10px;
            padding: 8px 12px;
            border-radius: 4px;
            max-width: 90%;
        }
        
        .user-message {
            background: var(--vscode-button-background);
            color: var(--vscode-button-foreground);
            margin-left: auto;
            text-align: right;
        }
        
        .agent-message {
            background: var(--vscode-input-background);
            color: var(--vscode-input-foreground);
            margin-right: auto;
        }
        
        .tool-call-message {
            font-family: monospace;
            font-size: 11px;
            padding: 4px 8px;
            margin: 2px 0;
            background: rgba(0, 0, 0, 0.05);
            border-left: 3px solid var(--vscode-button-background);
            border-radius: 2px;
        }
        
        .tool-call-complete {
            border-left-color: #28a745;
            background: rgba(40, 167, 69, 0.1);
        }
        
        .tool-call-error {
            border-left-color: var(--vscode-errorForeground);
            background: rgba(255, 0, 0, 0.1);
        }
        
        .tool-result {
            font-family: monospace;
            font-size: 10px;
            padding: 4px 8px;
            margin: 2px 0 2px 12px;
            background: rgba(0, 0, 0, 0.03);
            border-left: 2px solid #28a745;
            border-radius: 2px;
            color: var(--vscode-descriptionForeground);
            max-height: 200px;
            overflow-y: auto;
            white-space: pre-wrap;
            word-break: break-all;
        }
        
        .progress-indicator {
            display: flex;
            align-items: center;
            gap: 8px;
            padding: 8px;
            color: var(--vscode-descriptionForeground);
            font-style: italic;
        }
        
        .spinner {
            width: 12px;
            height: 12px;
            border: 2px solid var(--vscode-button-background);
            border-top-color: transparent;
            border-radius: 50%;
            animation: spin 1s linear infinite;
        }
        
        @keyframes spin {
            to { transform: rotate(360deg); }
        }
        
        .input-row {
            display: flex;
            gap: 8px;
            align-items: center;
        }
        
        #userInput {
            flex: 1;
            padding: 8px 12px;
            border: 1px solid var(--vscode-editor-background);
            border-radius: 4px;
            background: var(--vscode-input-background);
            color: var(--vscode-input-foreground);
            font-family: var(--vscode-font-family);
        }
        
        #userInput:focus {
            outline: 2px solid var(--vscode-focusBorder);
        }
        
        #actionButton {
            padding: 8px 16px;
            background: var(--vscode-button-background);
            color: var(--vscode-button-foreground);
            border: none;
            border-radius: 4px;
            cursor: pointer;
            min-width: 80px;
            font-weight: 600;
        }
        
        #actionButton:hover {
            background: var(--vscode-button-hoverBackground);
        }
        
        #actionButton:disabled {
            opacity: 0.5;
            cursor: not-allowed;
        }
        
        #actionButton.stop-button {
            background: #dc3545;
        }
        
        #actionButton.stop-button:hover {
            background: #c82333;
        }
        
        .iteration-info {
            font-size: 11px;
            color: var(--vscode-descriptionForeground);
            margin-top: 8px;
            padding-top: 8px;
            border-top: 1px solid rgba(0, 0, 0, 0.1);
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
        <button onclick="toggleAction()" id="actionButton">Send</button>
    </div>

    <script>
        const vscode = acquireVsCodeApi();
        const messagesDiv = document.getElementById('messages');
        const actionButton = document.getElementById('actionButton');
        const userInput = document.getElementById('userInput');
        
        let currentProgressDiv = null;
        let isProcessing = false;
        
        function handleKeyPress(event) {
            if (event.key === 'Enter' && !event.shiftKey) {
                event.preventDefault();
                if (!isProcessing) {
                    sendMessage();
                }
            }
        }
        
        function toggleAction() {
            if (isProcessing) {
                stopAgent();
            } else {
                sendMessage();
            }
        }
        
        function sendMessage() {
            const text = userInput.value.trim();
            if (!text) return;
            
            // Set processing state
            isProcessing = true;
            updateActionButton();
            
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
        
        function stopAgent() {
            vscode.postMessage({ command: 'stopAgent' });
        }
        
        function updateActionButton() {
            if (isProcessing) {
                actionButton.textContent = 'Stop';
                actionButton.className = 'stop-button';
            } else {
                actionButton.textContent = 'Send';
                actionButton.className = '';
            }
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
                content += ' ✓ <em>Completed</em>';
            }
            if (toolCall.error) {
                content += ' ✗ <strong style="color: var(--vscode-errorForeground)">Error: ' + toolCall.error + '</strong>';
            }
            
            div.innerHTML = content;
            messagesDiv.appendChild(div);
            div.scrollIntoView({ behavior: 'smooth' });
        }
        
        function addToolResult(toolName, result) {
            if (!result || result.trim() === '') return;
            
            const div = document.createElement('div');
            div.className = 'tool-result';
            
            // Truncate long results
            let displayResult = result;
            if (result.length > 500) {
                displayResult = result.substring(0, 500) + '... (truncated)';
            }
            
            div.textContent = '↳ ' + toolName + ' result: ' + displayResult;
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
                            // Display tool result
                            if (event.toolCall.result) {
                                addToolResult(event.toolCall.toolName, event.toolCall.result);
                            }
                        }
                    } else if (event.type === 'iteration_complete') {
                        // Optional: show iteration complete message
                    }
                    break;
                    
                case 'response':
                    hideProgress();
                    
                    let responseHtml = '';
                    
                    // Show final text if available
                    if (message.response.text && message.response.text.trim() !== '') {
                        responseHtml = '<div>' + message.response.text.replace(/\\n/g, '<br>') + '</div>';
                    } else if (message.response.toolCalls && message.response.toolCalls.length > 0) {
                        // No text but tool calls were made - explain what happened
                        responseHtml = '<div><em>Completed tool operations:</em></div>';
                    } else {
                        responseHtml = '<div><em>No response generated.</em></div>';
                    }
                    
                    if (message.response.toolCalls && message.response.toolCalls.length > 0) {
                        responseHtml += '<div class="iteration-info">Tools Used: ' + message.response.toolCalls.map(tc => tc.toolName).join(', ') + '</div>';
                    }
                    
                    responseHtml += '<div class="iteration-info">Iterations: ' + message.response.iterations + ' | Duration: ' + message.response.durationMs + 'ms</div>';
                    
                    if (message.response.success !== undefined) {
                        responseHtml += '<div class="iteration-info">Status: ' + (message.response.success ? '✓ Success' : '✗ Failed') + '</div>';
                    }
                    
                    addMessage('agent', responseHtml, true);
                    
                    // Reset processing state
                    isProcessing = false;
                    updateActionButton();
                    userInput.disabled = false;
                    userInput.focus();
                    break;
                    
                case 'stopped':
                    hideProgress();
                    addMessage('agent', '<strong>⏹ Stopped by user</strong>', true);
                    isProcessing = false;
                    updateActionButton();
                    userInput.disabled = false;
                    userInput.focus();
                    break;
                    
                case 'error':
                    hideProgress();
                    addMessage('agent', 'Error: ' + message.error, false);
                    isProcessing = false;
                    updateActionButton();
                    userInput.disabled = false;
                    break;
                    
                case 'configReloaded':
                    location.reload();
                    break;
            }
        });
        
        // Initialize button state
        updateActionButton();
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
   * Log message to output channel
   */
  private log(message: string): void {
    this.outputChannel.appendLine(`[AgentTabManager] ${message}`);
  }

  /**
   * Dispose all tabs and resources
   */
  async dispose(): Promise<void> {
    this.log('Disposing AgentTabManager...');
    
    // Close all tabs
    for (const tab of this.tabs.values()) {
      await this.closeTab(tab.id);
    }
    
    // Dispose provider
    await this.provider.dispose();
    
    this.log('AgentTabManager disposed');
  }
}
