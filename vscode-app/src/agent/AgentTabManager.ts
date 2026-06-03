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
    } catch (error: any) {
      this.log(`Error reloading agent: ${error.message}`);
      vscode.window.showErrorMessage(`Failed to reload agent: ${error.message}`);
    }
  }

  /**
   * Close a tab and dispose resources
   */
  closeTab(tabId: string): void {
    const tab = this.tabs.get(tabId);
    if (tab) {
      tab.panel.dispose();
      this.tabs.delete(tabId);
      this.log(`Closed tab: ${tabId}`);
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
        :root {
            --vscode-font-family: var(--vscode-editor-font-family, 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif);
            --vscode-font-size: var(--vscode-editor-font-size, 13px);
            --vscode-foreground: var(--vscode-editor-foreground, #cccccc);
            --vscode-background: var(--vscode-editor-background, #1e1e1e);
            --vscode-input-background: var(--vscode-input-background, #3c3c3c);
            --vscode-input-foreground: var(--vscode-input-foreground, #cccccc);
            --vscode-button-background: var(--vscode-button-background, #0e639c);
            --vscode-button-foreground: var(--vscode-button-foreground, #ffffff);
            --vscode-descriptionForeground: var(--vscode-descriptionForeground, #cccccc99);
            --vscode-textLink-foreground: var(--vscode-textLink-foreground, #3794ff);
            --vscode-textCodeBlock-background: var(--vscode-textCodeBlock-background, #2d2d2d);
            --vscode-widget-border: var(--vscode-widget-border, #454545);
        }
        
        * {
            box-sizing: border-box;
            margin: 0;
            padding: 0;
        }
        
        body {
            font-family: var(--vscode-font-family);
            font-size: var(--vscode-font-size);
            color: var(--vscode-foreground);
            background-color: var(--vscode-background);
            padding: 20px;
            line-height: 1.6;
        }
        
        .header {
            margin-bottom: 20px;
            padding-bottom: 15px;
            border-bottom: 1px solid var(--vscode-widget-border);
        }
        
        .header h1 {
            font-size: 18px;
            font-weight: 600;
            margin-bottom: 8px;
        }
        
        .header .config-info {
            font-size: 12px;
            color: var(--vscode-descriptionForeground);
        }
        
        .chat-container {
            display: flex;
            flex-direction: column;
            gap: 15px;
            margin-bottom: 20px;
            max-height: calc(100vh - 300px);
            overflow-y: auto;
            padding-right: 5px;
        }
        
        .message {
            padding: 12px 15px;
            border-radius: 8px;
            max-width: 85%;
        }
        
        .message.user {
            background-color: var(--vscode-button-background);
            color: var(--vscode-button-foreground);
            align-self: flex-end;
            margin-left: auto;
        }
        
        .message.assistant {
            background-color: var(--vscode-textCodeBlock-background);
            border: 1px solid var(--vscode-widget-border);
            align-self: flex-start;
        }
        
        .message.error {
            background-color: #5a1d1d;
            border: 1px solid #be1100;
            color: #f48771;
        }
        
        .message-content {
            white-space: pre-wrap;
            word-wrap: break-word;
        }
        
        .tool-calls {
            margin-top: 10px;
            padding-top: 10px;
            border-top: 1px solid var(--vscode-widget-border);
        }
        
        .tool-call {
            background-color: rgba(0, 0, 0, 0.2);
            padding: 8px 12px;
            border-radius: 6px;
            margin-top: 8px;
            font-size: 12px;
            font-family: 'Consolas', 'Courier New', monospace;
            border-left: 3px solid var(--vscode-textLink-foreground);
        }
        
        .input-container {
            position: sticky;
            bottom: 0;
            background-color: var(--vscode-background);
            padding-top: 15px;
            border-top: 1px solid var(--vscode-widget-border);
        }
        
        .input-row {
            display: flex;
            gap: 10px;
            align-items: center;
        }
        
        input[type="text"] {
            flex: 1;
            padding: 10px 15px;
            border: 1px solid var(--vscode-widget-border);
            border-radius: 6px;
            background-color: var(--vscode-input-background);
            color: var(--vscode-input-foreground);
            font-family: var(--vscode-font-family);
            font-size: var(--vscode-font-size);
        }
        
        input[type="text"]:focus {
            outline: 2px solid var(--vscode-button-background);
            outline-offset: 1px;
        }
        
        button {
            padding: 10px 20px;
            background-color: var(--vscode-button-background);
            color: var(--vscode-button-foreground);
            border: none;
            border-radius: 6px;
            cursor: pointer;
            font-family: var(--vscode-font-family);
            font-size: var(--vscode-font-size);
            font-weight: 500;
            transition: background-color 0.2s;
        }
        
        button:hover {
            background-color: #1177bb;
        }
        
        button:disabled {
            opacity: 0.5;
            cursor: not-allowed;
        }
        
        .action-buttons {
            display: flex;
            gap: 8px;
            margin-top: 10px;
            flex-wrap: wrap;
        }
        
        .action-buttons button {
            padding: 6px 12px;
            font-size: 11px;
            background-color: transparent;
            border: 1px solid var(--vscode-widget-border);
            color: var(--vscode-descriptionForeground);
        }
        
        .action-buttons button:hover {
            background-color: var(--vscode-textCodeBlock-background);
            color: var(--vscode-foreground);
        }
        
        .processing {
            color: var(--vscode-descriptionForeground);
            font-style: italic;
            font-size: 12px;
        }
        
        .metadata {
            font-size: 11px;
            color: var(--vscode-descriptionForeground);
            margin-top: 8px;
        }
        
        /* Scrollbar styling */
        ::-webkit-scrollbar {
            width: 8px;
            height: 8px;
        }
        
        ::-webkit-scrollbar-track {
            background: var(--vscode-background);
        }
        
        ::-webkit-scrollbar-thumb {
            background: var(--vscode-widget-border);
            border-radius: 4px;
        }
        
        ::-webkit-scrollbar-thumb:hover {
            background: #555;
        }
    </style>
</head>
<body>
    <div class="header">
        <h1>i2-Vision ${this.capitalize(layer)} Agent</h1>
        <div class="config-info">
            Agent: ${modelId} | Max Iterations: ${maxIterations}
        </div>
        <div class="action-buttons">
            <button onclick="clearHistory()">Clear History</button>
            <button onclick="reloadConfig()">Reload Config</button>
            <button onclick="openConfig()">Open Config</button>
        </div>
    </div>
    
    <div class="chat-container" id="chatContainer">
        <div class="message assistant">
            <div class="message-content">Hello! I'm your Code Agent. How can I help you with VSLFC today?</div>
        </div>
    </div>
    
    <div class="input-container">
        <div class="input-row">
            <input type="text" id="userInput" placeholder="Ask me anything about your code..." onkeypress="handleKeyPress(event)">
            <button onclick="sendMessage()" id="sendButton">Send</button>
        </div>
    </div>
    
    <script>
        const vscode = acquireVsCodeApi();
        let isProcessing = false;
        
        function handleKeyPress(event) {
            if (event.key === 'Enter' && !event.shiftKey) {
                event.preventDefault();
                sendMessage();
            }
        }
        
        function sendMessage() {
            const input = document.getElementById('userInput');
            const text = input.value.trim();
            
            if (!text || isProcessing) return;
            
            isProcessing = true;
            document.getElementById('sendButton').disabled = true;
            
            // Add user message to chat
            addMessage('user', text);
            
            // Clear input
            input.value = '';
            
            // Show processing indicator
            const processingDiv = document.createElement('div');
            processingDiv.className = 'processing';
            processingDiv.id = 'processingIndicator';
            processingDiv.textContent = 'Processing...';
            document.getElementById('chatContainer').appendChild(processingDiv);
            scrollToBottom();
            
            // Send to extension
            vscode.postMessage({ command: 'sendMessage', text });
        }
        
        function addMessage(type, content, toolCalls = [], iterations = null, durationMs = null) {
            const container = document.getElementById('chatContainer');
            const messageDiv = document.createElement('div');
            messageDiv.className = 'message ' + type;
            
            const contentDiv = document.createElement('div');
            contentDiv.className = 'message-content';
            contentDiv.textContent = content;
            messageDiv.appendChild(contentDiv);
            
            // Add tool calls if present
            if (toolCalls && toolCalls.length > 0) {
                const toolCallsDiv = document.createElement('div');
                toolCallsDiv.className = 'tool-calls';
                
                toolCalls.forEach(toolCall => {
                    const toolDiv = document.createElement('div');
                    toolDiv.className = 'tool-call';
                    toolDiv.innerHTML = '<strong>Tool:</strong> ' + toolCall.toolName + 
                                        '<br><strong>Args:</strong> ' + JSON.stringify(toolCall.args || toolCall.arguments || {}, null, 2);
                    toolCallsDiv.appendChild(toolDiv);
                });
                
                messageDiv.appendChild(toolCallsDiv);
            }
            
            // Add metadata if present
            if (iterations !== null || durationMs !== null) {
                const metaDiv = document.createElement('div');
                metaDiv.className = 'metadata';
                const parts = [];
                if (iterations !== null) parts.push(iterations + ' iterations');
                if (durationMs !== null) parts.push((durationMs / 1000).toFixed(2) + 's');
                metaDiv.textContent = parts.join(', ');
                messageDiv.appendChild(metaDiv);
            }
            
            container.appendChild(messageDiv);
            scrollToBottom();
        }
        
        function addError(error) {
            const container = document.getElementById('chatContainer');
            const errorDiv = document.createElement('div');
            errorDiv.className = 'message error';
            errorDiv.innerHTML = '<div class="message-content">' + error + '</div>';
            container.appendChild(errorDiv);
            scrollToBottom();
        }
        
        function scrollToBottom() {
            const container = document.getElementById('chatContainer');
            container.scrollTop = container.scrollHeight;
        }
        
        function clearHistory() {
            document.getElementById('chatContainer').innerHTML = '<div class="message assistant"><div class="message-content">Hello! I\\'m your Code Agent. How can I help you with VSLFC today?</div></div>';
            vscode.postMessage({ command: 'clearHistory' });
        }
        
        function reloadConfig() {
            vscode.postMessage({ command: 'reloadConfig' });
        }
        
        function openConfig() {
            vscode.postMessage({ command: 'openConfig' });
        }
        
        // Handle messages from extension
        window.addEventListener('message', event => {
            const message = event.data;
            
            switch (message.command) {
                case 'response':
                    // Remove processing indicator
                    const processingIndicator = document.getElementById('processingIndicator');
                    if (processingIndicator) {
                        processingIndicator.remove();
                    }
                    
                    // Add assistant response
                    addMessage(
                        'assistant',
                        message.response.text || 'No response',
                        message.response.toolCalls || [],
                        message.response.iterations,
                        message.response.durationMs
                    );
                    
                    isProcessing = false;
                    document.getElementById('sendButton').disabled = false;
                    break;
                    
                case 'error':
                    // Remove processing indicator
                    const errorIndicator = document.getElementById('processingIndicator');
                    if (errorIndicator) {
                        errorIndicator.remove();
                    }
                    
                    addError(message.error);
                    isProcessing = false;
                    document.getElementById('sendButton').disabled = false;
                    break;
                    
                case 'processing':
                    // Already handled in sendMessage
                    break;
                    
                case 'configReloaded':
                    // Update config info in header
                    const configInfo = document.querySelector('.config-info');
                    if (configInfo && message.config) {
                        configInfo.textContent = 'Agent: ' + message.config.model + ' | Max Iterations: ' + message.config.maxIterations;
                    }
                    addMessage('assistant', 'Configuration reloaded successfully.', [], null, null);
                    break;
            }
        });
    </script>
</body>
</html>`;
  }

  /**
   * Capitalize first letter of a string
   */
  private capitalize(str: string): string {
    return str.charAt(0).toUpperCase() + str.slice(1);
  }

  /**
   * Log a message to the output channel
   */
  private log(message: string): void {
    const timestamp = new Date().toLocaleTimeString('en-US', { hour12: false });
    this.outputChannel.appendLine(`[${timestamp}] [AgentTabManager] ${message}`);
  }
}
