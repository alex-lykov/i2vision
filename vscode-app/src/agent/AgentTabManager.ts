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
import { ConversationHistoryManager, ChatMessage } from './ConversationHistoryManager';
import { AgentSettingsManager } from './AgentSettings';

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
 * Manages agent tabs and their UI state
 */
export class AgentTabManager {
  private context: vscode.ExtensionContext;
  private outputChannel: vscode.OutputChannel;
  private agentProvider: LocalAgentProvider;
  private settingsManager: AgentSettingsManager;
  private historyManager: ConversationHistoryManager | null = null;
  private tabs: Map<string, AgentTabState> = new Map();
  private activeTabId: string | null = null;
  private webviewPanel: vscode.WebviewPanel | null = null;
  private currentAgentBridge: AgentBridge | null = null;
  private isProcessing: boolean = false;
  private cancelTokenSource: vscode.CancellationTokenSource | null = null;

  constructor(
    context: vscode.ExtensionContext,
    outputChannel: vscode.OutputChannel,
    agentProvider: LocalAgentProvider
  ) {
    this.context = context;
    this.outputChannel = outputChannel;
    this.agentProvider = agentProvider;
    this.settingsManager = AgentSettingsManager.getInstance(context);
    
    const workspaceRoot = vscode.workspace.workspaceFolders?.[0]?.uri.fsPath || '';
    if (workspaceRoot) {
      this.historyManager = new ConversationHistoryManager(workspaceRoot);
    }
    
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
  async createTab(layer: string, conversationId?: string): Promise<string> {
    this.log(`Creating ${layer} agent tab...`);
    
    const layerEnum = layer.toUpperCase() as VslfcLayer;
    const agent = await this.agentProvider.createAgent(layerEnum);
    const tabId = conversationId || `tab-${Date.now()}-${Math.random().toString(36).substr(2, 4)}`;
    
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
      workspaceRoot,
      this.settingsManager
    );
    await this.currentAgentBridge.initialize();
    
    this.log(`Created ${layer} agent tab: ${tabId}`);
    this.log(`   Agent ID: ${agent.id}`);
    this.log(`   Provider: ${config.model.provider}, Model: ${config.model.id}`);
    
    this.showWebview();
    
    // Load existing conversation if resuming
    if (conversationId && this.historyManager) {
      await this.loadConversation(tabId);
    }
    
    return tabId;
  }

  /**
   * Load a saved conversation into the current tab
   */
  private async loadConversation(tabId: string): Promise<void> {
    if (!this.historyManager) {
      this.log('History manager not available');
      return;
    }
    
    const saved = await this.historyManager.load(tabId);
    if (!saved) {
      this.log(`No saved conversation found for ${tabId}`);
      return;
    }
    
    const tabState = this.tabs.get(tabId);
    if (!tabState) {
      this.log(`Tab ${tabId} not found for loading conversation`);
      return;
    }
    
    // Restore history
    tabState.history = saved.messages;
    tabState.lastActivityAt = saved.updatedAt;
    
    this.log(`Loaded conversation with ${saved.messages.length} messages`);
    
    // Send messages to webview for display
    for (const msg of saved.messages) {
      this.sendToWebview({
        type: msg.role === 'user' ? 'user_message' : 'restored_message',
        content: msg.content,
        toolCalls: msg.toolCalls,
        timestamp: msg.timestamp
      });
    }
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
   * Stop the current agent processing
   */
  async stopAgent(): Promise<void> {
    if (!this.isProcessing) {
      this.log('No active processing to stop');
      return;
    }
    
    this.log('Stopping agent processing...');
    
    if (this.cancelTokenSource) {
      this.cancelTokenSource.cancel();
    }
    
    // Send stop signal to webview
    this.sendToWebview({
      type: 'stopped',
      timestamp: Date.now()
    });
    
    this.isProcessing = false;
    this.log('Agent stopped');
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
    
    // Get settings
    const settings = this.settingsManager.getSettings();
    const streamingEnabled = settings.streaming.enabled;
    const showThinking = settings.streaming.showThinkingIndicator;
    
    // Add user message to history
    const userMessage: ChatMessage = {
      role: 'user',
      content: userInput,
      timestamp: Date.now()
    };
    tabState.history.push(userMessage);
    
    // Show user message in timeline
    this.sendToWebview({
      type: 'user_message',
      content: userInput,
      timestamp: Date.now()
    });
    
    try {
      // Clear accumulated tool calls for new request
      tabState.accumulatedToolCalls = [];
      
      // Create cancel token for this request
      this.cancelTokenSource = new vscode.CancellationTokenSource();
      
      // Process with streaming - events will appear inline in timeline
      let responseText = '';
      let startTime = Date.now();
      
      // Show thinking indicator at start (if enabled)
      if (showThinking) {
        this.sendToWebview({
          type: 'thinking',
          message: 'Agent is thinking...',
          timestamp: Date.now()
        });
      }
      
      // Use streaming or non-streaming based on settings
      const streamGenerator = this.currentAgentBridge.processStreaming(userInput, currentFile);
      
      for await (const chunk of streamGenerator) {
        // Check if cancelled
        if (this.cancelTokenSource.token.isCancellationRequested) {
          this.log('Processing cancelled by user');
          this.sendToWebview({
            type: 'stopped',
            timestamp: Date.now()
          });
          break;
        }
        
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
            // Stream text incrementally to timeline (if streaming enabled)
            if (streamingEnabled) {
              this.sendToWebview({
                type: 'streaming_text',
                text: chunk.text,
                timestamp: chunk.timestamp
              });
            }
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
            
          case 'thinking':
            // Move thinking indicator to the end (latest position)
            this.sendToWebview({
              type: 'thinking_update',
              message: chunk.message,
              timestamp: chunk.timestamp
            });
            break;
        }
      }
      
      // Clean response text (only if not cancelled)
      if (!this.cancelTokenSource?.token.isCancellationRequested) {
        const cleanedResponse = this.cleanResponseText(responseText);
        
        // Add assistant response to history
        const assistantMessage: ChatMessage = {
          role: 'assistant',
          content: cleanedResponse,
          toolCalls: tabState.accumulatedToolCalls.map(tc => ({
            toolName: tc.toolName,
            args: tc.args,
            result: tc.result
          })),
          timestamp: Date.now()
        };
        tabState.history.push(assistantMessage);
        
        // Finalize streaming text in timeline
        const durationMs = Date.now() - startTime;
        this.sendToWebview({
          type: 'assistant_response',
          content: cleanedResponse,
          durationMs,
          timestamp: Date.now()
        });
        
        this.log(`Complete: ${tabState.accumulatedToolCalls.length} tools, ${Date.now() - tabState.lastActivityAt}ms`);
      }
      
    } catch (error: any) {
      // Only show error if not cancelled
      if (error.name !== 'CancellationError' && !this.cancelTokenSource?.token.isCancellationRequested) {
        this.log(`Error processing input: ${error.message}`);
        vscode.window.showErrorMessage(`Agent error: ${error.message}`);
        
        this.sendToWebview({
          type: 'error',
          error: error.message,
          timestamp: Date.now()
        });
      }
    } finally {
      this.isProcessing = false;
      if (this.cancelTokenSource) {
        this.cancelTokenSource.dispose();
        this.cancelTokenSource = null;
      }
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
      this.log(`Webview message received: ${message.type}`);
      
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
          
        case 'stop_agent':
          await this.stopAgent();
          break;
          
        case 'change_provider':
          await this.changeProvider(message.provider);
          break;
          
        case 'change_model':
          await this.changeModel(message.model);
          break;
          
        case 'fetch_models':
          await this.fetchAndSendModels();
          break;
          
        case 'open_settings':
          try {
            this.log('Opening settings panel from webview...');
            // Try both methods
            const success = await vscode.commands.executeCommand('i2vision.settings');
            this.log(`Settings command executed, result: ${success}`);
          } catch (error: any) {
            this.log(`Error opening settings: ${error.message}`);
            this.log(`Error stack: ${error.stack}`);
            vscode.window.showErrorMessage(`Failed to open settings: ${error.message}`);
          }
          break;
          
        default:
          this.log(`Unknown message type: ${message.type}`);
      }
    }, null, this.context.subscriptions);
    
    this.webviewPanel.onDidDispose(() => {
      this.webviewPanel = null;
      this.log('Webview panel disposed');
    }, null, this.context.subscriptions);
  }

  /**
   * Change the provider for the active agent
   */
  private async changeProvider(provider: string): Promise<void> {
    if (!this.activeTabId) {
      vscode.window.showErrorMessage('No active agent tab');
      return;
    }
    
    const tabState = this.tabs.get(this.activeTabId);
    if (!tabState) {
      vscode.window.showErrorMessage('Active tab not found');
      return;
    }
    
    this.log(`Changing provider to ${provider} for ${tabState.layer} agent...`);
    
    try {
      this.agentProvider.clearConfigCache();
      const agentConfig = this.agentProvider.getConfig(tabState.layer);
      agentConfig.model.provider = provider;
      
      // Set default model for provider
      const defaultModel = provider === 'deepseek' ? 'deepseek-chat' : 'llama3.2:3b';
      agentConfig.model.id = defaultModel;
      
      // Update agent config
      await this.agentProvider.updateConfig(tabState.layer, {
        provider: provider,
        model: defaultModel
      });
      
      this.log(`Provider changed to ${provider}`);
      vscode.window.showInformationMessage(`Provider changed to ${provider}`);
      
      // Update webview with new provider
      this.sendToWebview({
        type: 'provider_changed',
        provider: provider,
        model: defaultModel
      });
    } catch (error: any) {
      this.log(`Error changing provider: ${error.message}`);
      vscode.window.showErrorMessage(`Failed to change provider: ${error.message}`);
    }
  }

  /**
   * Change the model for the active agent
   */
  private async changeModel(model: string): Promise<void> {
    if (!this.activeTabId) {
      vscode.window.showErrorMessage('No active agent tab');
      return;
    }
    
    const tabState = this.tabs.get(this.activeTabId);
    if (!tabState) {
      vscode.window.showErrorMessage('Active tab not found');
      return;
    }
    
    this.log(`Changing model to ${model} for ${tabState.layer} agent...`);
    
    try {
      const agentConfig = this.agentProvider.getConfig(tabState.layer);
      agentConfig.model.id = model;
      
      // Update agent config
      await this.agentProvider.updateConfig(tabState.layer, {
        provider: agentConfig.model.provider,
        model: model
      });
      
      this.log(`Model changed to ${model}`);
      vscode.window.showInformationMessage(`Model changed to ${model}`);
      
      // Update webview with new model
      this.sendToWebview({
        type: 'model_changed',
        model: model
      });
    } catch (error: any) {
      this.log(`Error changing model: ${error.message}`);
      vscode.window.showErrorMessage(`Failed to change model: ${error.message}`);
    }
  }

  /**
   * Fetch available models and send to webview
   */
  private async fetchAndSendModels(): Promise<void> {
    if (!this.activeTabId) {
      return;
    }
    
    const tabState = this.tabs.get(this.activeTabId);
    if (!tabState) {
      return;
    }
    
    try {
      const agentConfig = this.agentProvider.getConfig(tabState.layer);
      const providerId = agentConfig.model.provider;
      
      // Fetch models from Ollama if using Ollama provider
      let models: string[] = [];
      
      if (providerId === 'ollama') {
        models = await this.fetchOllamaModels();
      } else if (providerId === 'deepseek') {
        models = ['deepseek-chat', 'deepseek-coder'];
      }
      
      this.sendToWebview({
        type: 'models_list',
        models: models,
        currentModel: agentConfig.model.id,
        currentProvider: providerId
      });
    } catch (error: any) {
      this.log(`Error fetching models: ${error.message}`);
      this.sendToWebview({
        type: 'models_list',
        models: [],
        currentModel: '',
        currentProvider: '',
        error: error.message
      });
    }
  }

  /**
   * Fetch available models from Ollama
   */
  private async fetchOllamaModels(): Promise<string[]> {
    return new Promise<string[]>((resolve, reject) => {
      const https = require('https');
      const http = require('http');
      
      const url = 'http://localhost:11434/api/tags';
      const lib = http;
      
      const req = lib.get(url, (res: any) => {
        let data = '';
        res.on('data', (chunk: string) => data += chunk);
        res.on('end', () => {
          try {
            const parsed = JSON.parse(data);
            const modelNames = (parsed.models || []).map((m: any) => m.name);
            resolve(modelNames);
          } catch (e) {
            reject(new Error('Failed to parse Ollama response'));
          }
        });
      });
      
      req.on('error', (e: any) => reject(e));
      req.setTimeout(5000, () => {
        req.destroy();
        reject(new Error('Ollama timeout'));
      });
    });
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
   * Get webview HTML content - Unified Timeline UX with context meter and provider/model selectors
   */
  private getWebviewContent(): string {
    const workspaceRoot = vscode.workspace.workspaceFolders?.[0]?.uri.fsPath || 'No workspace';
    const workspaceName = vscode.workspace.workspaceFolders?.[0]?.name || 'Unknown';
    
    // Get current agent config for initial provider/model
    let currentProvider = 'ollama';
    let currentModel = 'llama3.2:3b';
    
    if (this.activeTabId) {
      const tabState = this.tabs.get(this.activeTabId);
      if (tabState) {
        const config = this.agentProvider.getConfig(tabState.layer);
        currentProvider = config.model.provider;
        currentModel = config.model.id;
      }
    }
    
    // Get settings for terminal behavior and streaming
    const settings = this.settingsManager.getSettings();
    const streamingEnabled = settings.streaming.enabled;
    const showThinking = settings.streaming.showThinkingIndicator;
    
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
      flex-wrap: wrap;
      gap: 10px;
    }
    
    .context-left {
      display: flex;
      gap: 20px;
      align-items: center;
      flex-wrap: wrap;
    }
    
    .context-item {
      display: flex;
      align-items: center;
      gap: 6px;
      color: var(--vscode-descriptionForeground);
    }
    
    /* Provider and Model selectors */
    .provider-model-group {
      display: flex;
      gap: 10px;
      align-items: center;
      flex-wrap: wrap;
    }
    
    .selector-group {
      display: flex;
      align-items: center;
      gap: 6px;
    }
    
    .selector-group label {
      font-weight: 600;
      color: var(--vscode-foreground);
      font-size: 0.9em;
    }
    
    .selector-group select {
      padding: 4px 8px;
      border: 1px solid var(--vscode-input-border);
      border-radius: 4px;
      background: var(--vscode-input-background);
      color: var(--vscode-input-foreground);
      font-family: var(--vscode-font-family);
      font-size: 0.85em;
      cursor: pointer;
      min-width: 120px;
    }
    
    .selector-group select:hover {
      border-color: var(--vscode-focusBorder);
    }
    
    .selector-group select:focus {
      outline: 2px solid var(--vscode-focusBorder);
      outline-offset: -2px;
    }
    
    /* Settings button */
    .btn-settings {
      padding: 4px 8px;
      font-size: 1.2em;
      background: transparent;
      border: 1px solid var(--vscode-editorWidget-border);
      border-radius: 4px;
      cursor: pointer;
      color: var(--vscode-foreground);
      transition: all 0.2s ease;
    }
    
    .btn-settings:hover {
      background-color: var(--vscode-editor-selectionBackground);
      border-color: var(--vscode-focusBorder);
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
    
    /* Restored message (from history) */
    .message.restored {
      background-color: var(--vscode-editorWidget-background);
      border-left: 3px solid var(--vscode-descriptionForeground);
      padding: 12px 15px;
      border-radius: 6px;
      white-space: pre-wrap;
      opacity: 0.8;
    }
    
    /* Stopped message */
    .message.stopped {
      background-color: var(--vscode-editorWidget-background);
      border-left: 3px solid var(--vscode-descriptionForeground);
      padding: 10px 15px;
      border-radius: 6px;
      font-style: italic;
      color: var(--vscode-descriptionForeground);
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
      width: 14px;
      height: 14px;
      border: 2px solid var(--vscode-progressBarBackground);
      border-top-color: transparent;
      border-radius: 50%;
      animation: spin 1s linear infinite;
    }
    
    @keyframes spin {
      to { transform: rotate(360deg); }
    }
    
    /* Unified footer bar */
    .message-footer {
      display: flex;
      justify-content: space-between;
      align-items: center;
      padding: 8px 12px;
      background-color: var(--vscode-editorWidget-background);
      border-radius: 4px;
      margin-top: 10px;
      font-size: 0.85em;
    }
    
    .footer-left {
      display: flex;
      gap: 15px;
      color: var(--vscode-descriptionForeground);
    }
    
    .footer-left span {
      display: flex;
      align-items: center;
      gap: 5px;
    }
    
    .footer-right {
      display: flex;
      gap: 8px;
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
      gap: 8px;
      align-items: center;
    }
    
    /* Combined Send/Stop button */
    .btn-action {
      padding: 6px 16px;
      border: none;
      border-radius: 4px;
      cursor: pointer;
      font-family: var(--vscode-font-family);
      font-size: 0.9em;
      display: inline-flex;
      align-items: center;
      gap: 8px;
      font-weight: 500;
      min-width: 100px;
      justify-content: center;
      transition: all 0.2s ease;
    }
    
    /* Send state (default) */
    .btn-action.send {
      background-color: var(--vscode-button-background);
      color: var(--vscode-button-foreground);
    }
    
    .btn-action.send:hover {
      background-color: var(--vscode-button-hoverBackground);
    }
    
    /* Stop state (during processing) */
    .btn-action.stop {
      background-color: var(--vscode-errorForeground);
      color: white;
      animation: pulse-stop 2s ease-in-out infinite;
    }
    
    .btn-action.stop:hover {
      opacity: 0.9;
    }
    
    @keyframes pulse-stop {
      0%, 100% { box-shadow: 0 0 0 0 rgba(255, 0, 0, 0.4); }
      50% { box-shadow: 0 0 0 8px rgba(255, 0, 0, 0); }
    }
    
    /* Base button class */
    .btn {
      padding: 6px 12px;
      border: none;
      border-radius: 4px;
      cursor: pointer;
      font-family: var(--vscode-font-family);
      font-size: 0.85em;
      display: inline-flex;
      align-items: center;
      gap: 6px;
    }
    
    /* Secondary button (Clear) */
    .btn-secondary {
      background-color: var(--vscode-button-secondaryBackground);
      color: var(--vscode-button-secondaryForeground);
    }
    
    .btn-secondary:hover {
      opacity: 0.9;
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
    
    /* Collapsed tool card */
    .tool-card.collapsed .tool-card-content {
      display: none;
    }
    
    .tool-card.collapsed .toggle-icon {
      transform: rotate(0deg);
    }
  </style>
</head>
<body>
  <!-- Context bar with workspace, provider/model selectors, and token usage -->
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
    
    <!-- Provider and Model Selectors -->
    <div class="provider-model-group">
      <div class="selector-group">
        <label for="providerSelect">Provider:</label>
        <select id="providerSelect" onchange="onProviderChange()">
          <option value="ollama" ${currentProvider === 'ollama' ? 'selected' : ''}>Ollama (Local + Cloud)</option>
          <option value="deepseek" ${currentProvider === 'deepseek' ? 'selected' : ''}>DeepSeek Direct (Cloud)</option>
        </select>
      </div>
      <div class="selector-group">
        <label for="modelSelect">Model:</label>
        <select id="modelSelect" onchange="onModelChange()" style="min-width: 200px;">
          <option value="${currentModel}" selected>${currentModel}</option>
        </select>
      </div>
    </div>
    
    <div class="token-meter">
      <span style="color: var(--vscode-descriptionForeground); font-size: 0.8em;">Context:</span>
      <div class="token-bar">
        <div class="token-fill" id="tokenFill" style="width: 0%"></div>
      </div>
      <span class="token-text" id="tokenText">0 / 0 tokens</span>
    </div>
    
    <!-- Settings Button -->
    <div style="margin-left: 15px;">
      <button class="btn-settings" onclick="openSettings()" title="Agent Settings" style="padding: 4px 8px; font-size: 1.2em; background: transparent; border: 1px solid var(--vscode-editorWidget-border); border-radius: 4px; cursor: pointer; color: var(--vscode-foreground);">
        ⚙️
      </button>
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
      <button class="btn-action send" id="actionBtn">
        <span id="actionIcon">⏵</span>
        <span id="actionText">Send</span>
      </button>
      <button class="btn btn-secondary" id="clearBtn">Clear</button>
    </div>
  </div>
  
  <script>
    const vscode = acquireVsCodeApi();
    const timeline = document.getElementById('timeline');
    const userInput = document.getElementById('userInput');
    const actionBtn = document.getElementById('actionBtn');
    const actionIcon = document.getElementById('actionIcon');
    const actionText = document.getElementById('actionText');
    const clearBtn = document.getElementById('clearBtn');
    const tokenFill = document.getElementById('tokenFill');
    const tokenText = document.getElementById('tokenText');
    const currentFileEl = document.getElementById('currentFile');
    const providerSelect = document.getElementById('providerSelect');
    const modelSelect = document.getElementById('modelSelect');
    
    // Track current streaming element
    let streamingElement = null;
    let thinkingEl = null;
    let isProcessing = false;
    
    // Settings from extension
    const streamingEnabled = ${streamingEnabled};
    const showThinkingIndicator = ${showThinking};
    
    // Fetch models on load
    window.addEventListener('load', () => {
      vscode.postMessage({ type: 'fetch_models' });
    });
    
    // Send message on button click
    actionBtn.addEventListener('click', () => {
      if (isProcessing) {
        // Stop agent
        vscode.postMessage({ type: 'stop_agent' });
      } else {
        // Send message
        const content = userInput.value.trim();
        if (content) {
          vscode.postMessage({ type: 'user_input', content });
          userInput.value = '';
        }
      }
    });
    
    // Send on Enter (Shift+Enter for new line)
    userInput.addEventListener('keydown', (e) => {
      if (e.key === 'Enter' && !e.shiftKey) {
        e.preventDefault();
        actionBtn.click();
      }
    });
    
    // Clear timeline
    clearBtn.addEventListener('click', () => {
      timeline.innerHTML = '';
      streamingElement = null;
      thinkingEl = null;
      setProcessingState(false);
    });
    
    // Provider change handler
    function onProviderChange() {
      const provider = providerSelect.value;
      vscode.postMessage({ type: 'change_provider', provider: provider });
    }
    
    // Model change handler
    function onModelChange() {
      const model = modelSelect.value;
      vscode.postMessage({ type: 'change_model', model: model });
    }
    
    // Open settings
    function openSettings() {
      vscode.postMessage({ type: 'open_settings' });
    }
    
    // Handle messages from extension - all events append to timeline in order
    window.addEventListener('message', (event) => {
      const message = event.data;
      
      switch (message.type) {
        case 'user_message':
          appendUserMessage(message.content);
          break;
          
        case 'restored_message':
          appendRestoredMessage(message.content, message.toolCalls, message.timestamp);
          break;
          
        case 'thinking':
          showThinkingIndicator(message.message);
          break;
          
        case 'thinking_update':
          updateThinkingIndicator(message.message);
          break;
          
        case 'tool_start':
          appendToolCard(message.toolName, message.args);
          collapseAllToolCardsExceptLast();
          break;
          
        case 'tool_complete':
          updateToolCard(message.toolName, message.result);
          collapseAllToolCardsExceptLast();
          break;
          
        case 'streaming_text':
          appendStreamingText(message.text);
          break;
          
        case 'assistant_response':
          finalizeStreamingText(message.durationMs);
          setProcessingState(false);
          break;
          
        case 'token_usage':
          updateTokenMeter(message.tokenUsage, message.contextLength);
          break;
          
        case 'error':
          appendErrorMessage(message.error);
          setProcessingState(false);
          break;
          
        case 'stopped':
          hideThinkingIndicator();
          setProcessingState(false);
          appendStoppedMessage();
          break;
          
        case 'provider_changed':
          // Update provider dropdown
          providerSelect.value = message.provider;
          // Fetch models for new provider
          vscode.postMessage({ type: 'fetch_models' });
          break;
          
        case 'model_changed':
          // Update model dropdown
          modelSelect.value = message.model;
          break;
          
        case 'models_list':
          // Populate model dropdown
          populateModelDropdown(message.models, message.currentModel, message.currentProvider);
          break;
      }
      
      scrollToBottom();
    });
    
    function populateModelDropdown(models, currentModel, currentProvider) {
      modelSelect.innerHTML = '';
      
      if (models && models.length > 0) {
        models.forEach(model => {
          const option = document.createElement('option');
          option.value = model;
          option.textContent = model;
          if (model === currentModel) {
            option.selected = true;
          }
          modelSelect.appendChild(option);
        });
      } else {
        // Show current model even if fetch failed
        const option = document.createElement('option');
        option.value = currentModel;
        option.textContent = currentModel;
        option.selected = true;
        modelSelect.appendChild(option);
      }
    }
    
    function appendUserMessage(content) {
      const div = document.createElement('div');
      div.className = 'message user';
      div.textContent = content;
      timeline.appendChild(div);
    }
    
    function appendRestoredMessage(content, toolCalls, timestamp) {
      const div = document.createElement('div');
      div.className = 'message restored';
      div.textContent = content;
      
      // Add timestamp
      const date = new Date(timestamp);
      const timeStr = date.toLocaleTimeString();
      div.title = 'Restored from history • ' + timeStr;
      
      timeline.appendChild(div);
      
      // Restore tool cards if present
      if (toolCalls && toolCalls.length > 0) {
        toolCalls.forEach(tc => {
          const card = document.createElement('div');
          card.className = 'tool-card done collapsed';
          card.innerHTML = \`
            <div class="tool-card-header" onclick="toggleToolCard('\${card.id}')">
              <span class="tool-card-status">🔧</span>
              <span>\${escapeHtml(tc.toolName)}</span>
              <span class="toggle-icon">▶</span>
            </div>
            <div class="tool-card-content">
              \${tc.result ? \`<div class="tool-card-label">Result</div><div class="tool-card-result">\${escapeHtml(tc.result.substring(0, 500))}\${tc.result.length > 500 ? '...' : ''}</div>\` : ''}
            </div>
          \`;
          timeline.appendChild(card);
        });
      }
    }
    
    function showThinkingIndicator(message) {
      // Check if thinking indicator is enabled
      if (!showThinkingIndicator) {
        return;
      }
      
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
      setProcessingState(true);
    }
    
    function updateThinkingIndicator(message) {
      // Move thinking indicator to the end (latest position)
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
        // Add unified footer bar with timing + actions
        const footer = document.createElement('div');
        footer.className = 'message-footer';
        footer.innerHTML = \`
          <div class="footer-left">
            <span>⏱️ \${durationMs ? (durationMs / 1000).toFixed(1) : '?'}s</span>
          </div>
          <div class="footer-right">
            <button class="btn btn-secondary" onclick="copyResponse()" style="padding: 4px 8px; font-size: 0.85em;">📋 Copy</button>
            <button class="btn btn-secondary" onclick="applyChanges()" style="padding: 4px 8px; font-size: 0.85em;">📝 Apply</button>
          </div>
        \`;
        streamingElement.appendChild(footer);
        
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
    
    function appendStoppedMessage() {
      const div = document.createElement('div');
      div.className = 'message stopped';
      div.textContent = '⏹️ Processing stopped by user.';
      timeline.appendChild(div);
    }
    
    function toggleToolCard(cardId) {
      const card = document.getElementById(cardId);
      if (card) {
        card.classList.toggle('expanded');
        card.classList.toggle('collapsed');
      }
    }
    
    function collapseAllToolCardsExceptLast() {
      // Collapse all completed tool cards except the most recent one
      const cards = timeline.querySelectorAll('.tool-card.done');
      cards.forEach((card, index) => {
        if (index < cards.length - 1) {
          card.classList.add('collapsed');
          card.classList.remove('expanded');
        } else {
          card.classList.remove('collapsed');
          card.classList.add('expanded');
        }
      });
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
    
    function setProcessingState(processing) {
      isProcessing = processing;
      
      if (processing) {
        // Change to Stop state
        actionBtn.classList.remove('send');
        actionBtn.classList.add('stop');
        actionIcon.textContent = '⏹';
        actionText.textContent = 'Stop';
        userInput.disabled = true;
        userInput.style.opacity = '0.5';
      } else {
        // Change to Send state
        actionBtn.classList.remove('stop');
        actionBtn.classList.add('send');
        actionIcon.textContent = '⏵';
        actionText.textContent = 'Send';
        userInput.disabled = false;
        userInput.style.opacity = '1';
      }
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
   * Close a tab and save its conversation history
   */
  async closeTab(tabId: string): Promise<void> {
    const tab = this.tabs.get(tabId);
    if (tab) {
      // Save conversation history before closing
      if (this.historyManager && tab.history.length > 0) {
        await this.historyManager.save(tabId, tab.history, tab.layer);
        this.log(`Saved conversation history for ${tabId} (${tab.history.length} messages)`);
      }
      
      // Clean up agent
      tab.agent.dispose();
      this.tabs.delete(tabId);
      
      if (this.activeTabId === tabId) {
        this.activeTabId = null;
      }
      
      this.log(`Closed tab ${tabId}`);
    }
  }

  /**
   * Resume a saved conversation
   */
  async resumeConversation(conversationId: string): Promise<string> {
    this.log(`Resuming conversation: ${conversationId}`);
    
    // Create new tab with the conversation ID
    const tabId = await this.createTab('vision', conversationId);
    
    return tabId;
  }

  /**
   * List all saved conversations
   */
  async listConversations(): Promise<string[]> {
    if (!this.historyManager) {
      return [];
    }
    return this.historyManager.list();
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
  async dispose(): Promise<void> {
    if (this.webviewPanel) {
      this.webviewPanel.dispose();
    }
    
    // Dispose the current agent bridge (cleans up terminal manager)
    if (this.currentAgentBridge) {
      this.currentAgentBridge.dispose();
      this.currentAgentBridge = null;
    }
    
    // Save and dispose all tabs
    const savePromises = Array.from(this.tabs.entries()).map(async ([tabId, tabState]) => {
      if (this.historyManager && tabState.history.length > 0) {
        await this.historyManager.save(tabId, tabState.history, tabState.layer);
      }
      tabState.agent.dispose();
    });
    
    this.tabs.clear();
    this.log('AgentTabManager disposed');
    
    await Promise.all(savePromises);
  }
}
