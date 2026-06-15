/**
 * AgentTabManager - Manages agent tabs in the VSCode webview
 * 
 * Implements unified timeline UX: thinking, tool execution, and streaming text
 * all appear inline in one chronological stream - not separate sections.
 */

import * as vscode from 'vscode';
import * as path from 'path';
import * as fs from 'fs';
import { AgentBridge, AgentChunk, ToolCall, AgentConfig } from './AgentBridge';
import { LocalAgentProvider } from './LocalAgentProvider';
import { LocalI2VisionAgent, VslfcLayer, getLayerName } from './LocalI2VisionAgent';

/**
 * Tab state for tracking agent session
 */
interface AgentTabState {
  tabId: string;
  agent: LocalI2VisionAgent;
  layer: string;
  history: ChatMessage[];
  accumulatedToolCalls: ToolCall[];
  isActive: boolean;
  createdAt: number;
  lastActivityAt: number;
}

/**
 * Chat message in tab history
 */
interface ChatMessage {
  role: 'user' | 'assistant';
  content: string;
  toolCalls?: ToolCall[];
  timestamp: number;
}

/**
 * Manages agent tabs and their UI state
 */
export class AgentTabManager {
  private context: vscode.ExtensionContext;
  private outputChannel: vscode.OutputChannel;
  private agentProvider: LocalAgentProvider;
  private tabs: Map<string, AgentTabState> = new Map();
  private activeTabId: string | null = null;
  private webviewPanel: vscode.WebviewPanel | null = null;
  private currentAgentBridge: AgentBridge | null = null;
  private isProcessing: boolean = false;

  constructor(
    context: vscode.ExtensionContext,
    outputChannel: vscode.OutputChannel,
    agentProvider: LocalAgentProvider
  ) {
    this.context = context;
    this.outputChannel = outputChannel;
    this.agentProvider = agentProvider;
    
    this.log('AgentTabManager initialized');
  }

  /**
   * Initialize the manager
   */
  async initialize(): Promise<void> {
    await this.agentProvider.initialize();
    this.log('AgentTabManager initialization complete');
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

  /**
   * Create a new agent tab for a specific layer
   */
  async createTab(layer: string): Promise<string> {
    this.log(`Creating ${layer} agent tab...`);
    
    const layerEnum = layer.toUpperCase() as VslfcLayer;
    const agent = await this.agentProvider.createAgent(layerEnum);
    const tabId = `tab-${Date.now()}-${Math.random().toString(36).substr(2, 4)}`;
    
    const tabState: AgentTabState = {
      tabId,
      agent,
      layer,
      history: [],
      accumulatedToolCalls: [],
      isActive: true,
      createdAt: Date.now(),
      lastActivityAt: Date.now()
    };
    
    this.tabs.set(tabId, tabState);
    this.activeTabId = tabId;
    
    const config = agent.getConfig();
    const workspaceRoot = vscode.workspace.workspaceFolders?.[0]?.uri.fsPath || '';
    
    this.currentAgentBridge = new AgentBridge(
      config,
      this.outputChannel,
      this.context.extensionPath,
      workspaceRoot
    );
    await this.currentAgentBridge.initialize();
    
    this.log(`Created ${layer} agent tab: ${tabId}`);
    this.log(`   Agent ID: ${agent.id}`);
    this.log(`   Provider: ${config.model.provider}, Model: ${config.model.id}`);
    
    this.showWebview();
    return tabId;
  }

  /**
   * Get the active tab ID
   */
  getActiveTabId(): string | null {
    return this.activeTabId;
  }

  /**
   * Get tab state by ID
   */
  getTabState(tabId: string): AgentTabState | undefined {
    return this.tabs.get(tabId);
  }

  /**
   * Clear config cache (delegates to provider)
   */
  clearConfigCache(): void {
    this.agentProvider.clearConfigCache();
    this.log('Config cache cleared');
  }

  /**
   * Process user input through the active agent with streaming
   */
  async processUserInput(userInput: string, currentFile?: string): Promise<void> {
    if (!this.activeTabId) {
      vscode.window.showErrorMessage('No active agent tab. Create one first.');
      return;
    }
    
    if (this.isProcessing) {
      vscode.window.showErrorMessage('Agent is already processing a request.');
      return;
    }
    
    const tabState = this.tabs.get(this.activeTabId);
    if (!tabState) {
      vscode.window.showErrorMessage('Active tab not found.');
      return;
    }
    
    if (!this.currentAgentBridge) {
      vscode.window.showErrorMessage('Agent bridge not initialized.');
      return;
    }
    
    this.isProcessing = true;
    tabState.lastActivityAt = Date.now();
    
    // Add user message to history
    tabState.history.push({
      role: 'user',
      content: userInput,
      timestamp: Date.now()
    });
    
    // Show user message in timeline
    this.sendToWebview({
      type: 'user_message',
      content: userInput,
      timestamp: Date.now()
    });
    
    try {
      // Clear accumulated tool calls for new request
      tabState.accumulatedToolCalls = [];
      
      // Process with streaming - events will appear inline in timeline
      let responseText = '';
      let startTime = Date.now();
      
      // Show thinking indicator at start
      this.sendToWebview({
        type: 'thinking',
        message: 'Agent is thinking...',
        timestamp: Date.now()
      });
      
      for await (const chunk of this.currentAgentBridge.processStreaming(userInput, currentFile)) {
        switch (chunk.type) {
          case 'tool_call_started':
            // Show tool card inline as it starts
            this.sendToWebview({
              type: 'tool_start',
              toolName: chunk.toolName,
              args: chunk.args,
              timestamp: chunk.timestamp
            });
            break;
            
          case 'tool_call_completed':
            // Add to accumulated tool calls
            const toolCall: ToolCall = {
              toolName: chunk.toolName,
              args: {},
              result: chunk.result
            };
            tabState.accumulatedToolCalls.push(toolCall);
            
            // Update the same tool card in place
            this.sendToWebview({
              type: 'tool_complete',
              toolName: chunk.toolName,
              result: chunk.result,
              timestamp: chunk.timestamp
            });
            break;
            
          case 'text':
            responseText += chunk.text;
            // Stream text incrementally to timeline
            this.sendToWebview({
              type: 'streaming_text',
              text: chunk.text,
              timestamp: chunk.timestamp
            });
            break;
            
          case 'done':
            // Send token usage if available
            if (chunk.tokenUsage) {
              this.sendToWebview({
                type: 'token_usage',
                tokenUsage: chunk.tokenUsage,
                contextLength: this.currentAgentBridge.getConfig().model.contextLength,
                timestamp: chunk.timestamp
              });
            }
            break;
            
          case 'error':
            vscode.window.showErrorMessage(`Agent error: ${chunk.error}`);
            break;
        }
      }
      
      // Clean response text
      const cleanedResponse = this.cleanResponseText(responseText);
      
      // Add assistant response to history
      tabState.history.push({
        role: 'assistant',
        content: cleanedResponse,
        toolCalls: tabState.accumulatedToolCalls,
        timestamp: Date.now()
      });
      
      // Finalize streaming text in timeline
      const durationMs = Date.now() - startTime;
      this.sendToWebview({
        type: 'assistant_response',
        content: cleanedResponse,
        durationMs,
        timestamp: Date.now()
      });
      
      this.log(`Complete: ${tabState.accumulatedToolCalls.length} tools, ${Date.now() - tabState.lastActivityAt}ms`);
      
    } catch (error: any) {
      this.log(`Error processing input: ${error.message}`);
      vscode.window.showErrorMessage(`Agent error: ${error.message}`);
      
      this.sendToWebview({
        type: 'error',
        error: error.message,
        timestamp: Date.now()
      });
    } finally {
      this.isProcessing = false;
    }
  }

  /**
   * Clean response text by removing reasoning headers, EOS markers, and tool calls
   */
  private cleanResponseText(text: string): string {
    if (!text) return '';
    
    text = text.replace(/reasoning:\s*[\s\S]*?(?=\n\n|EOS|[A-Z][a-z])/gi, '');
    text = text.replace(/^reasoning:.*$/gim, '');
    text = text.replace(/\bEOS\b\s*/g, '');
    text = text.replace(/tool_call:\s*\{[\s\S]*?\}(?=\n|$|tool_call:)/g, '');
    
    const blocks = text.split(/\n\n+/).filter(b => b.trim().length > 20);
    
    if (blocks.length > 1) {
      text = blocks.reduce((a, b) => a.length > b.length ? a : b).trim();
    }
    
    return text.trim();
  }

  /**
   * Show the agent webview panel
   */
  private showWebview(): void {
    if (this.webviewPanel) {
      this.webviewPanel.reveal(vscode.ViewColumn.One);
      return;
    }
    
    this.webviewPanel = vscode.window.createWebviewPanel(
      'i2visionAgent',
      'i2-Vision Agent',
      vscode.ViewColumn.One,
      {
        enableScripts: true,
        retainContextWhenHidden: true,
        localResourceRoots: [
          vscode.Uri.file(path.join(this.context.extensionPath, 'media'))
        ]
      }
    );
    
    this.webviewPanel.webview.html = this.getWebviewContent();
    
    this.webviewPanel.webview.onDidReceiveMessage(async (message) => {
      switch (message.type) {
        case 'user_input':
          // Get current file from VSCode
          const currentFile = vscode.window.activeTextEditor?.document.uri.fsPath;
          const workspaceRoot = vscode.workspace.workspaceFolders?.[0]?.uri.fsPath;
          const relativePath = currentFile && workspaceRoot 
            ? path.relative(workspaceRoot, currentFile)
            : undefined;
          
          await this.processUserInput(message.content, relativePath);
          break;
          
        case 'apply_changes':
          await this.handleApplyChanges(message.content);
          break;
          
        case 'copy_response':
          vscode.env.clipboard.writeText(message.content);
          vscode.window.showInformationMessage('Response copied to clipboard');
          break;
      }
    }, null, this.context.subscriptions);
    
    this.webviewPanel.onDidDispose(() => {
      this.webviewPanel = null;
      this.log('Webview panel disposed');
    }, null, this.context.subscriptions);
  }

  /**
   * Send message to webview
   */
  private sendToWebview(message: any): void {
    if (this.webviewPanel) {
      this.webviewPanel.webview.postMessage(message);
    }
  }

  /**
   * Get webview HTML content - Unified Timeline UX with context meter
   */
  private getWebviewContent(): string {
    const workspaceRoot = vscode.workspace.workspaceFolders?.[0]?.uri.fsPath || 'No workspace';
    const workspaceName = vscode.workspace.workspaceFolders?.[0]?.name || 'Unknown';
    
    // Escape workspace name for HTML (Node.js safe - no document)
    const escapeHtmlStr = (text: string) => {
      return text
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#039;');
    };
    
    return `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>i2-Vision Agent</title>
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
    
    /* Context bar at top */
    .context-bar {
      display: flex;
      justify-content: space-between;
      align-items: center;
      padding: 10px 15px;
      background-color: var(--vscode-editorWidget-background);
      border: 1px solid var(--vscode-editorWidget-border);
      border-radius: 6px;
      margin-bottom: 15px;
      font-size: 0.85em;
    }
    
    .context-left {
      display: flex;
      gap: 20px;
      align-items: center;
    }
    
    .context-item {
      display: flex;
      align-items: center;
      gap: 6px;
      color: var(--vscode-descriptionForeground);
    }
    
    /* Token usage meter */
    .token-meter {
      display: flex;
      align-items: center;
      gap: 10px;
    }
    
    .token-bar {
      width: 150px;
      height: 8px;
      background-color: var(--vscode-editorWidget-border);
      border-radius: 4px;
      overflow: hidden;
      position: relative;
    }
    
    .token-fill {
      height: 100%;
      background: linear-gradient(90deg, 
        var(--vscode-terminal-successBackground) 0%, 
        var(--vscode-terminal-successBackground) 50%,
        var(--vscode-terminal-ansiYellow) 75%,
        var(--vscode-errorForeground) 100%);
      transition: width 0.3s ease;
    }
    
    .token-text {
      min-width: 80px;
      text-align: right;
      color: var(--vscode-descriptionForeground);
      font-size: 0.8em;
    }
    
    /* Single timeline container */
    .timeline {
      max-width: 900px;
      margin: 0 auto;
      display: flex;
      flex-direction: column;
      gap: 12px;
    }
    
    /* User message */
    .message.user {
      background-color: var(--vscode-editor-inactiveSelectionBackground);
      border-left: 4px solid var(--vscode-button-background);
      padding: 12px 15px;
      border-radius: 6px;
      white-space: pre-wrap;
    }
    
    /* Agent streaming text */
    .message.agent {
      background-color: var(--vscode-editor-selectionBackground);
      border-left: 4px solid var(--vscode-editorLineNumber-foreground);
      padding: 12px 15px;
      border-radius: 6px;
      white-space: pre-wrap;
    }
    
    /* Tool card - inline in timeline */
    .tool-card {
      background-color: var(--vscode-editorWidget-background);
      border: 1px solid var(--vscode-editorWidget-border);
      border-radius: 6px;
      padding: 10px 12px;
      display: flex;
      flex-direction: column;
      gap: 8px;
      transition: all 0.2s ease;
    }
    
    /* Thinking indicator */
    .thinking-indicator {
      display: flex;
      align-items: center;
      gap: 10px;
      padding: 10px 12px;
      background-color: var(--vscode-editor-selectionBackground);
      border-left: 3px solid var(--vscode-progressBarBackground);
      border-radius: 6px;
      font-style: italic;
      color: var(--vscode-descriptionForeground);
    }
    
    .thinking-indicator .spinner {
      width: 16px;
      height: 16px;
      border: 2px solid var(--vscode-progressBarBackground);
      border-top-color: transparent;
      border-radius: 50%;
      animation: spin 1s linear infinite;
    }
    
    @keyframes spin {
      to { transform: rotate(360deg); }
    }
    
    /* Meta info bar */
    .meta-bar {
      display: flex;
      gap: 15px;
      font-size: 0.85em;
      color: var(--vscode-descriptionForeground);
      padding: 8px 12px;
      background-color: var(--vscode-editorWidget-background);
      border-radius: 4px;
      margin-top: 10px;
    }
    
    .meta-bar span {
      display: flex;
      align-items: center;
      gap: 5px;
    }
    
    .tool-card.running {
      border-left: 3px solid var(--vscode-progressBarBackground);
    }
    
    .tool-card.done {
      border-left: 3px solid var(--vscode-terminal-successBackground);
    }
    
    .tool-card.error {
      border-left: 3px solid var(--vscode-errorForeground);
    }
    
    .tool-card-header {
      display: flex;
      align-items: center;
      gap: 8px;
      font-weight: 600;
      font-size: 0.9em;
    }
    
    .tool-card-status {
      font-size: 1.1em;
    }
    
    .tool-card-status.running {
      animation: pulse 1s ease-in-out infinite;
    }
    
    @keyframes pulse {
      0%, 100% { opacity: 0.5; }
      50% { opacity: 1; }
    }
    
    .tool-card-content {
      display: none;
      flex-direction: column;
      gap: 6px;
      margin-top: 4px;
    }
    
    .tool-card.expanded .tool-card-content {
      display: flex;
    }
    
    .tool-card-label {
      font-weight: 600;
      color: var(--vscode-descriptionForeground);
      font-size: 0.8em;
      text-transform: uppercase;
      letter-spacing: 0.5px;
    }
    
    .tool-card-result {
      background-color: var(--vscode-textCodeBlock-background);
      padding: 8px;
      border-radius: 4px;
      font-family: var(--vscode-editor-font-family);
      font-size: 0.85em;
      max-height: 300px;
      overflow-y: auto;
      white-space: pre-wrap;
      word-break: break-word;
    }
    
    /* Input area */
    .input-container {
      margin-top: 30px;
      padding-top: 20px;
      border-top: 1px solid var(--vscode-editorWidget-border);
      max-width: 900px;
      margin-left: auto;
      margin-right: auto;
    }
    
    .input-box {
      width: 100%;
      padding: 12px;
      border: 1px solid var(--vscode-editorWidget-border);
      border-radius: 6px;
      background-color: var(--vscode-input-background);
      color: var(--vscode-input-foreground);
      font-family: var(--vscode-font-family);
      font-size: var(--vscode-font-size);
      resize: vertical;
      min-height: 80px;
    }
    
    .input-box:focus {
      outline: 2px solid var(--vscode-focusBorder);
      outline-offset: -2px;
    }
    
    .button-row {
      margin-top: 10px;
      display: flex;
      gap: 10px;
    }
    
    .btn {
      padding: 10px 20px;
      border: none;
      border-radius: 6px;
      cursor: pointer;
      font-family: var(--vscode-font-family);
      font-size: var(--vscode-font-size);
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
    
    /* Error message */
    .message.error {
      background-color: var(--vscode-inputValidation-errorBackground);
      border-left: 4px solid var(--vscode-errorForeground);
      padding: 12px 15px;
      border-radius: 6px;
      color: var(--vscode-errorForeground);
    }
    
    /* Collapsible tool card */
    .tool-card-header {
      cursor: pointer;
      user-select: none;
    }
    
    .tool-card-header:hover {
      opacity: 0.9;
    }
    
    .toggle-icon {
      margin-left: auto;
      transition: transform 0.2s;
    }
    
    .tool-card.expanded .toggle-icon {
      transform: rotate(90deg);
    }
  </style>
</head>
<body>
  <!-- Context bar with workspace and token usage -->
  <div class="context-bar">
    <div class="context-left">
      <div class="context-item">
        <span>📁</span>
        <span id="workspaceName">${escapeHtmlStr(workspaceName)}</span>
      </div>
      <div class="context-item">
        <span>📄</span>
        <span id="currentFile">None</span>
      </div>
    </div>
    <div class="token-meter">
      <span style="color: var(--vscode-descriptionForeground); font-size: 0.8em;">Context:</span>
      <div class="token-bar">
        <div class="token-fill" id="tokenFill" style="width: 0%"></div>
      </div>
      <span class="token-text" id="tokenText">0 / 0 tokens</span>
    </div>
  </div>
  
  <!-- Single timeline container - everything appends here in order -->
  <div class="timeline" id="timeline">
    <div class="message agent">
      👋 Hello! I'm your i2-Vision coding agent. I can help you with:
      
      • Reading and analyzing code files
      • Searching for patterns in your codebase
      • Running builds and tests
      • Git operations (status, diff, log, commit)
      • Writing new files
      
      What would you like to work on?
    </div>
  </div>
  
  <div class="input-container">
    <textarea 
      class="input-box" 
      id="userInput" 
      placeholder="Ask me anything about your code..."
      rows="3"
    ></textarea>
    <div class="button-row">
      <button class="btn btn-primary" id="sendBtn">Send</button>
      <button class="btn btn-secondary" id="clearBtn">Clear</button>
    </div>
  </div>
  
  <script>
    const vscode = acquireVsCodeApi();
    const timeline = document.getElementById('timeline');
    const userInput = document.getElementById('userInput');
    const sendBtn = document.getElementById('sendBtn');
    const clearBtn = document.getElementById('clearBtn');
    const tokenFill = document.getElementById('tokenFill');
    const tokenText = document.getElementById('tokenText');
    const currentFileEl = document.getElementById('currentFile');
    
    // Track current streaming element
    let streamingElement = null;
    let thinkingEl = null;
    
    // Send message on button click
    sendBtn.addEventListener('click', () => {
      const content = userInput.value.trim();
      if (content) {
        vscode.postMessage({ type: 'user_input', content });
        userInput.value = '';
      }
    });
    
    // Send on Enter (Shift+Enter for new line)
    userInput.addEventListener('keydown', (e) => {
      if (e.key === 'Enter' && !e.shiftKey) {
        e.preventDefault();
        sendBtn.click();
      }
    });
    
    // Clear timeline
    clearBtn.addEventListener('click', () => {
      timeline.innerHTML = '';
      streamingElement = null;
      thinkingEl = null;
    });
    
    // Handle messages from extension - all events append to timeline in order
    window.addEventListener('message', (event) => {
      const message = event.data;
      
      switch (message.type) {
        case 'user_message':
          appendUserMessage(message.content);
          break;
          
        case 'thinking':
          showThinkingIndicator(message.message);
          break;
          
        case 'tool_start':
          appendToolCard(message.toolName, message.args);
          break;
          
        case 'tool_complete':
          updateToolCard(message.toolName, message.result);
          break;
          
        case 'streaming_text':
          appendStreamingText(message.text);
          break;
          
        case 'assistant_response':
          finalizeStreamingText(message.durationMs);
          break;
          
        case 'token_usage':
          updateTokenMeter(message.tokenUsage, message.contextLength);
          break;
          
        case 'error':
          appendErrorMessage(message.error);
          break;
      }
      
      scrollToBottom();
    });
    
    function appendUserMessage(content) {
      const div = document.createElement('div');
      div.className = 'message user';
      div.textContent = content;
      timeline.appendChild(div);
    }
    
    function showThinkingIndicator(message) {
      // Remove any existing thinking indicator
      if (thinkingEl) {
        thinkingEl.remove();
      }
      
      thinkingEl = document.createElement('div');
      thinkingEl.className = 'thinking-indicator';
      thinkingEl.innerHTML = \`
        <div class="spinner"></div>
        <span>\${message}</span>
      \`;
      timeline.appendChild(thinkingEl);
    }
    
    function hideThinkingIndicator() {
      if (thinkingEl) {
        thinkingEl.remove();
        thinkingEl = null;
      }
    }
    
    function appendToolCard(toolName, args) {
      const cardId = 'tool-' + Date.now();
      const card = document.createElement('div');
      card.className = 'tool-card running expanded';
      card.id = cardId;
      card.dataset.toolName = toolName;
      
      let argsHtml = '';
      if (args && Object.keys(args).length > 0) {
        argsHtml = \`
          <div class="tool-card-label">Arguments</div>
          <div class="tool-card-result">\${escapeHtml(JSON.stringify(args, null, 2))}</div>
        \`;
      }
      
      card.innerHTML = \`
        <div class="tool-card-header" onclick="toggleToolCard('\${cardId}')">
          <span class="tool-card-status running">⏳</span>
          <span>🔧 \${escapeHtml(toolName)}</span>
          <span class="toggle-icon">▶</span>
        </div>
        <div class="tool-card-content">
          \${argsHtml}
        </div>
      \`;
      
      timeline.appendChild(card);
    }
    
    function updateToolCard(toolName, result) {
      // Find the most recent tool card with this name
      const cards = timeline.querySelectorAll('.tool-card.running');
      let targetCard = null;
      
      for (let i = cards.length - 1; i >= 0; i--) {
        if (cards[i].dataset.toolName === toolName) {
          targetCard = cards[i];
          break;
        }
      }
      
      if (targetCard) {
        // Update status - remove running state, no checkmark
        targetCard.classList.remove('running');
        targetCard.classList.add('done');
        
        // Remove the spinner, keep tool icon only
        const statusEl = targetCard.querySelector('.tool-card-status');
        if (statusEl) {
          statusEl.textContent = '';
          statusEl.classList.remove('running');
        }
        
        // Add result section
        const contentDiv = targetCard.querySelector('.tool-card-content');
        if (contentDiv && result) {
          const resultHtml = \`
            <div class="tool-card-label" style="margin-top: 8px;">Result</div>
            <div class="tool-card-result">\${escapeHtml(result.substring(0, 3000))}\${result.length > 3000 ? '...' : ''}</div>
          \`;
          contentDiv.insertAdjacentHTML('beforeend', resultHtml);
        }
      }
    }
    
    function appendStreamingText(text) {
      if (!streamingElement) {
        streamingElement = document.createElement('div');
        streamingElement.className = 'message agent';
        timeline.appendChild(streamingElement);
      }
      streamingElement.textContent += text;
    }
    
    function finalizeStreamingText(durationMs) {
      // Hide thinking indicator
      hideThinkingIndicator();
      
      // Streaming element already has the text from chunks - just finalize it
      if (streamingElement) {
        // Add meta info bar with timing only (no iterations)
        const metaBar = document.createElement('div');
        metaBar.className = 'meta-bar';
        metaBar.innerHTML = \`
          <span>⏱️ \${durationMs ? (durationMs / 1000).toFixed(1) : '?'}s</span>
        \`;
        streamingElement.appendChild(metaBar);
        
        // Add action buttons
        const buttonRow = document.createElement('div');
        buttonRow.className = 'button-row';
        buttonRow.style.marginTop = '15px';
        buttonRow.innerHTML = \`
          <button class="btn btn-secondary" onclick="copyResponse()">📋 Copy</button>
          <button class="btn btn-secondary" onclick="applyChanges()">📝 Apply</button>
        \`;
        streamingElement.appendChild(buttonRow);
        
        streamingElement = null;
      }
    }
    
    function updateTokenMeter(tokenUsage, contextLength) {
      const total = tokenUsage.prompt + tokenUsage.completion;
      const percentage = Math.min((total / contextLength) * 100, 100);
      
      tokenFill.style.width = percentage + '%';
      tokenText.textContent = \`\${total.toLocaleString()} / \${contextLength.toLocaleString()} tokens (\${percentage.toFixed(1)}%)\`;
      
      // Change color based on usage
      if (percentage > 80) {
        tokenFill.style.background = 'var(--vscode-errorForeground)';
      } else if (percentage > 60) {
        tokenFill.style.background = 'var(--vscode-terminal-ansiYellow)';
      } else {
        tokenFill.style.background = 'var(--vscode-terminal-successBackground)';
      }
    }
    
    function appendErrorMessage(error) {
      const div = document.createElement('div');
      div.className = 'message error';
      div.textContent = '❌ Error: ' + error;
      timeline.appendChild(div);
    }
    
    function toggleToolCard(cardId) {
      const card = document.getElementById(cardId);
      if (card) {
        card.classList.toggle('expanded');
      }
    }
    
    function scrollToBottom() {
      timeline.scrollTop = timeline.scrollHeight;
    }
    
    function escapeHtml(text) {
      if (!text) return '';
      const div = document.createElement('div');
      div.textContent = text;
      return div.innerHTML;
    }
    
    function copyResponse() {
      const lastAgentMessage = timeline.querySelector('.message.agent:last-child');
      if (lastAgentMessage) {
        const content = lastAgentMessage.childNodes[0]?.textContent || '';
        vscode.postMessage({ type: 'copy_response', content });
      }
    }
    
    function applyChanges() {
      vscode.postMessage({ type: 'apply_changes' });
    }
  </script>
</body>
</html>`;
  }

  /**
   * Handle apply changes action
   */
  private async handleApplyChanges(content: string): Promise<void> {
    vscode.window.showInformationMessage('Apply changes not yet implemented');
  }

  /**
   * Get all tabs
   */
  getAllTabs(): AgentTabState[] {
    return Array.from(this.tabs.values());
  }

  /**
   * Dispose of the manager
   */
  dispose(): void {
    if (this.webviewPanel) {
      this.webviewPanel.dispose();
    }
    this.tabs.clear();
    this.log('AgentTabManager disposed');
  }
}
