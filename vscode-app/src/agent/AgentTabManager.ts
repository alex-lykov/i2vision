/**
 * AgentTabManager - Manages agent tabs in the VSCode webview
 */

import * as vscode from 'vscode';
import * as path from 'path';
import * as fs from 'fs';
import { AgentBridge, ToolCall } from './AgentBridge';
import { LocalAgentProvider } from './LocalAgentProvider';
import { LocalI2VisionAgent, VslfcLayer } from './LocalI2VisionAgent';
import { ConversationHistoryManager, ChatMessage, AgentSessionState } from './ConversationHistoryManager';
import { AgentSettingsManager } from './AgentSettings';

interface AgentTabState {
  tabId: string;
  agent: LocalI2VisionAgent;
  layer: string;
  history: ChatMessage[];
  accumulatedToolCalls: ToolCall[];
  isActive: boolean;
  createdAt: number;
  lastActivityAt: number;
  lastAutoSaveAt?: number;
  workspaceRoot: string; // Persist workspace path to prevent context loss on resume
  sessionState?: AgentSessionState; // Persist session state across agent recreation
}

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
  private autoSaveTimer: NodeJS.Timeout | null = null;
  private readonly AUTO_SAVE_INTERVAL_MS = 5 * 60 * 1000;

  constructor(context: vscode.ExtensionContext, outputChannel: vscode.OutputChannel, agentProvider: LocalAgentProvider) {
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

  async initialize(): Promise<void> {
    await this.agentProvider.initialize();
    this.startAutoSaveTimer();
    await this.loadLastConversation();
    this.log('AgentTabManager initialization complete');
  }

  private async loadLastConversation(): Promise<void> {
    if (!this.historyManager) return;
    try {
      const conversationIds = await this.historyManager.list();
      if (conversationIds.length === 0) return;
      
      // Get the most recently updated conversation
      const conversations = await Promise.all(conversationIds.map(async (id) => {
        const saved = await this.historyManager!.load(id);
        return { id, updatedAt: saved?.updatedAt || 0 };
      }));
      conversations.sort((a, b) => b.updatedAt - a.updatedAt);
      
      const lastConvId = conversations[0].id;
      this.log('Auto-loading last conversation: ' + lastConvId);
      
      // Resume the last conversation
      await this.resumeConversation(lastConvId);
      
      // Send loaded conversation to webview after delay
      setTimeout(() => {
        if (this.activeTabId) {
          this.sendLoadedConversation(this.activeTabId);
        }
      }, 500);
    } catch (error: any) {
      this.log('Error loading last conversation: ' + error.message);
    }
  }

  private startAutoSaveTimer(): void {
    if (this.autoSaveTimer) clearInterval(this.autoSaveTimer);
    this.autoSaveTimer = setInterval(() => this.autoSaveAllTabs(), this.AUTO_SAVE_INTERVAL_MS);
    this.log('Auto-save timer started (interval: ' + (this.AUTO_SAVE_INTERVAL_MS / 1000) + 's)');
  }

  private stopAutoSaveTimer(): void {
    if (this.autoSaveTimer) {
      clearInterval(this.autoSaveTimer);
      this.autoSaveTimer = null;
      this.log('Auto-save timer stopped');
    }
  }

  private async autoSaveAllTabs(): Promise<void> {
    const settings = this.settingsManager.getSettings();
    if (!settings.agent.autoSaveConversation) return;
    const now = Date.now();
    const savePromises: Promise<void>[] = [];
    for (const [tabId, tabState] of this.tabs.entries()) {
      const shouldSave = !tabState.lastAutoSaveAt || (tabState.lastActivityAt > tabState.lastAutoSaveAt) || (now - tabState.createdAt > 10 * 60 * 1000);
      if (shouldSave && tabState.history.length > 0) {
        savePromises.push(this.saveTabQuietly(tabId, tabState));
      }
    }
    if (savePromises.length > 0) {
      await Promise.all(savePromises);
      this.log('Auto-saved ' + savePromises.length + ' tab(s)');
    }
  }

  private async saveTabQuietly(tabId: string, tabState: AgentTabState): Promise<void> {
    if (!this.historyManager) return;
    try {
      await this.historyManager.save(tabId, tabState.history, tabState.layer, tabState.sessionState);
      tabState.lastAutoSaveAt = Date.now();
      this.log('Auto-saved tab ' + tabId + ' (' + tabState.history.length + ' messages)');
    } catch (error: any) {
      this.log('Auto-save failed for ' + tabId + ': ' + error.message);
    }
  }

  private async enforceHistoryLimit(): Promise<void> {
    if (!this.historyManager) return;
    const settings = this.settingsManager.getSettings();
    const limit = settings.agent.conversationHistoryLimit;
    try {
      const conversations = await this.historyManager.list();
      if (conversations.length <= limit) return;
      const convWithMeta = await Promise.all(conversations.map(async (id) => {
        const saved = await this.historyManager!.load(id);
        return { id, updatedAt: saved?.updatedAt || 0 };
      }));
      convWithMeta.sort((a, b) => a.updatedAt - b.updatedAt);
      const toDelete = convWithMeta.slice(0, convWithMeta.length - limit);
      await Promise.all(toDelete.map(c => this.historyManager!.delete(c.id)));
      this.log('Pruned ' + toDelete.length + ' old conversation(s) (limit: ' + limit + ')');
    } catch (error: any) {
      this.log('Failed to enforce history limit: ' + error.message);
    }
  }

  private log(message: string): void {
    const timestamp = new Date().toLocaleTimeString();
    const formatted = '[' + timestamp + '] [AgentTabManager] ' + message;
    this.outputChannel.appendLine(formatted);
    console.log(formatted);
  }

  async createTab(layer: string, conversationId?: string): Promise<string> {
    this.log('Creating ' + layer + ' agent tab...');
    const layerEnum = layer.toUpperCase() as VslfcLayer;
    const agent = await this.agentProvider.createAgent(layerEnum);
    const tabId = conversationId || 'tab-' + Date.now() + '-' + Math.random().toString(36).substr(2, 4);
    const tabState: AgentTabState = { tabId, agent, layer, history: [], accumulatedToolCalls: [], isActive: true, createdAt: Date.now(), lastActivityAt: Date.now(), lastAutoSaveAt: undefined };
    this.tabs.set(tabId, tabState);
    this.activeTabId = tabId;
    const config = agent.getConfig();
    const workspaceRoot = vscode.workspace.workspaceFolders?.[0]?.uri.fsPath || '';
    this.currentAgentBridge = new AgentBridge(config, this.outputChannel, this.context.extensionPath, workspaceRoot, this.settingsManager);
    await this.currentAgentBridge.initialize();
    this.log('Created ' + layer + ' agent tab: ' + tabId);
    this.log('   Agent ID: ' + agent.id);
    this.log('   Provider: ' + config.model.provider + ', Model: ' + config.model.id);
    if (conversationId && this.historyManager) {
      await this.loadConversationData(tabId);
      this.showWebview(true);
    } else {
      this.showWebview(false);
    }
    return tabId;
  }

  private async loadConversationData(tabId: string): Promise<void> {
    if (!this.historyManager) { this.log('History manager not available'); return; }
    const saved = await this.historyManager.load(tabId);
    if (!saved) { this.log('No saved conversation found for ' + tabId); return; }
    const tabState = this.tabs.get(tabId);
    if (!tabState) { this.log('Tab ' + tabId + ' not found for loading conversation'); return; }
    tabState.history = saved.messages;
    tabState.sessionState = saved.sessionState; // Restore session state
    tabState.lastActivityAt = saved.updatedAt;
    tabState.lastAutoSaveAt = saved.updatedAt;
    this.log('Loaded conversation data with ' + saved.messages.length + ' messages');
    if (saved.sessionState) {
      this.log('Restored session state: ' + JSON.stringify({
        visitedPaths: saved.sessionState.visitedPaths?.length || 0,
        searchCache: saved.sessionState.searchCache?.length || 0,
        domain: saved.sessionState.resolvedDomain?.primaryDomain || 'none'
      }));
    }
  }

  private async sendLoadedConversation(tabId: string): Promise<void> {
    const tabState = this.tabs.get(tabId);
    if (!tabState || tabState.history.length === 0) return;
    this.log('Sending loaded conversation with ' + tabState.history.length + ' messages to webview');
    // Extract context title from first user message
    const firstUserMessage = tabState.history.find(m => m.role === 'user');
    const contextTitle = firstUserMessage?.content ? firstUserMessage.content.trim().split('\n')[0].substring(0, 50) : 'Untitled';
    this.sendToWebview({ command: 'context_title', contextTitle });
    for (const msg of tabState.history) {
      this.sendToWebview({ command: msg.role === 'user' ? 'user_message' : 'restored_message', content: msg.content, toolCalls: msg.toolCalls, timestamp: msg.timestamp });
    }
    
    // Initialize context meter with estimated token count from loaded messages
    if (this.currentAgentBridge) {
      const contextLength = this.currentAgentBridge.getConfig().model.contextLength;
      const estimatedTokens = this.estimateTokensFromMessages(tabState.history);
      this.sendToWebview({ 
        command: 'token_usage', 
        tokenUsage: { prompt: estimatedTokens, completion: 0, total: estimatedTokens }, 
        contextLength,
        timestamp: Date.now() 
      });
    }
  }
  
  /**
   * Estimate token count from messages (rough approximation: 1 token ≈ 4 characters for code)
   * Uses more conservative estimate to avoid inflated numbers
   */
  private estimateTokensFromMessages(messages: ChatMessage[]): number {
    const totalChars = messages.reduce((sum, msg) => {
      let msgChars = msg.content.length;
      if (msg.toolCalls) {
        msgChars += msg.toolCalls.reduce((s, tc) => s + JSON.stringify(tc).length, 0);
      }
      return sum + msgChars;
    }, 0);
    // Conservative estimate: 1 token ≈ 6 characters for code (includes whitespace, brackets, etc.)
    return Math.round(totalChars / 6);
  }

  getActiveTabId(): string | null { return this.activeTabId; }
  getTabState(tabId: string): AgentTabState | undefined { return this.tabs.get(tabId); }
  clearConfigCache(): void { this.agentProvider.clearConfigCache(); this.log('Config cache cleared'); }

  async stopAgent(): Promise<void> {
    if (!this.isProcessing) { this.log('No active processing to stop'); return; }
    this.log('Stopping agent processing...');
    if (this.cancelTokenSource) this.cancelTokenSource.cancel();
    this.sendToWebview({ command: 'stopped', timestamp: Date.now() });
    this.isProcessing = false;
    this.log('Agent stopped');
  }

  async processUserInput(userInput: string, currentFile?: string): Promise<void> {
    if (!this.activeTabId) { vscode.window.showErrorMessage('No active agent tab. Create one first.'); return; }
    if (this.isProcessing) { vscode.window.showErrorMessage('Agent is already processing a request.'); return; }
    const tabState = this.tabs.get(this.activeTabId);
    if (!tabState) { vscode.window.showErrorMessage('Active tab not found.'); return; }
    if (!this.currentAgentBridge) { vscode.window.showErrorMessage('Agent bridge not initialized.'); return; }
    this.isProcessing = true;
    tabState.lastActivityAt = Date.now();
    const settings = this.settingsManager.getSettings();
    const streamingEnabled = settings.streaming.enabled;
    const showThinking = settings.streaming.showThinkingIndicator;
    const userMessage: ChatMessage = { role: 'user', content: userInput, timestamp: Date.now() };
    tabState.history.push(userMessage);
    this.sendToWebview({ command: 'user_message', content: userInput, timestamp: Date.now() });
    try {
      tabState.accumulatedToolCalls = [];
      this.cancelTokenSource = new vscode.CancellationTokenSource();
      let responseText = '';
      let startTime = Date.now();
      if (showThinking) this.sendToWebview({ command: 'thinking', message: 'Agent is thinking...', timestamp: Date.now() });
      const streamGenerator = this.currentAgentBridge.processStreaming(
        userInput, 
        currentFile,
        tabState.history,  // Pass conversation history
        tabState.sessionState  // Pass session state
      );
      for await (const chunk of streamGenerator) {
        if (this.cancelTokenSource.token.isCancellationRequested) {
          this.log('Processing cancelled by user');
          this.sendToWebview({ command: 'stopped', timestamp: Date.now() });
          break;
        }
        switch (chunk.type) {
          case 'reasoning':
            this.sendToWebview({ command: 'reasoning', reasoning: chunk.reasoning, timestamp: chunk.timestamp });
            break;
          case 'tool_call_started':
            this.sendToWebview({ command: 'tool_start', toolName: chunk.toolName, args: chunk.args, timestamp: chunk.timestamp });
            break;
          case 'tool_call_completed':
            const toolCall: ToolCall = { toolName: chunk.toolName, args: {}, result: chunk.result };
            tabState.accumulatedToolCalls.push(toolCall);
            this.sendToWebview({ command: 'tool_complete', toolName: chunk.toolName, result: chunk.result, timestamp: chunk.timestamp });
            break;
          case 'text':
            responseText += chunk.text;
            if (streamingEnabled) this.sendToWebview({ command: 'streaming_text', text: chunk.text, timestamp: chunk.timestamp });
            break;
          case 'done':
            if (chunk.tokenUsage) {
              const contextLength = this.currentAgentBridge.getConfig().model.contextLength;
              const totalTokens = chunk.tokenUsage.prompt + chunk.tokenUsage.completion;
              const percentage = ((totalTokens / contextLength) * 100).toFixed(1);
              this.log(`Token usage: ${totalTokens.toLocaleString()} / ${contextLength.toLocaleString()} (${percentage}%) - prompt: ${chunk.tokenUsage.prompt.toLocaleString()}, completion: ${chunk.tokenUsage.completion.toLocaleString()}`);
              this.sendToWebview({ command: 'token_usage', tokenUsage: chunk.tokenUsage, contextLength, timestamp: chunk.timestamp });
            } else {
              this.log('Response complete (no token usage data)');
            }
            break;
          case 'error':
            vscode.window.showErrorMessage('Agent error: ' + chunk.error);
            break;
          case 'thinking':
            this.sendToWebview({ command: 'thinking', message: chunk.message, timestamp: chunk.timestamp });
            break;
        }
      }
      if (!this.cancelTokenSource?.token.isCancellationRequested) {
        const cleanedResponse = this.cleanResponseText(responseText);
        const assistantMessage: ChatMessage = { role: 'assistant', content: cleanedResponse, toolCalls: tabState.accumulatedToolCalls.map(tc => ({ toolName: tc.toolName, args: tc.args, result: tc.result })), timestamp: Date.now() };
        tabState.history.push(assistantMessage);
        
        // Update session state from agent bridge
        tabState.sessionState = this.currentAgentBridge.getSessionState();
        
        if (settings.agent.autoSaveConversation && this.historyManager) await this.saveTabQuietly(this.activeTabId!, tabState);
        const durationMs = Date.now() - startTime;
        this.sendToWebview({ command: 'assistant_response', content: cleanedResponse, durationMs, timestamp: Date.now() });
        this.log('Complete: ' + tabState.accumulatedToolCalls.length + ' tools, ' + (Date.now() - tabState.lastActivityAt) + 'ms');
      }
    } catch (error: any) {
      if (error.name !== 'CancellationError' && !this.cancelTokenSource?.token.isCancellationRequested) {
        this.log('Error processing input: ' + error.message);
        vscode.window.showErrorMessage('Agent error: ' + error.message);
        this.sendToWebview({ command: 'error', error: error.message, timestamp: Date.now() });
      }
    } finally {
      this.isProcessing = false;
      if (this.cancelTokenSource) { this.cancelTokenSource.dispose(); this.cancelTokenSource = null; }
    }
  }

  private cleanResponseText(text: string): string {
    if (!text) return '';
    text = text.replace(/reasoning:\s*[\s\S]*?(?=\n\n|EOS|[A-Z][a-z])/gi, '');
    text = text.replace(/^reasoning:.*$/gim, '');
    text = text.replace(/\bEOS\b\s*/g, '');
    text = text.replace(/tool_call:\s*\{[\s\S]*?\}(?=\n|$|tool_call:)/g, '');
    const blocks = text.split(/\n\n+/).filter(b => b.trim().length > 20);
    if (blocks.length > 1) text = blocks.reduce((a, b) => a.length > b.length ? a : b).trim();
    return text.trim();
  }

  private showWebview(loadConversation: boolean = false): void {
    if (this.webviewPanel) {
      this.webviewPanel.webview.html = this.getWebviewContent();
      this.webviewPanel.reveal(vscode.ViewColumn.One);
      if (loadConversation && this.activeTabId) {
        setTimeout(() => { if (this.activeTabId) this.sendLoadedConversation(this.activeTabId); }, 500);
      }
      return;
    }
    this.webviewPanel = vscode.window.createWebviewPanel('i2visionAgent', 'i2-Vision Agent', vscode.ViewColumn.One, {
      enableScripts: true,
      retainContextWhenHidden: true,
      localResourceRoots: [vscode.Uri.file(path.join(this.context.extensionPath, 'media'))]
    });
    this.webviewPanel.webview.html = this.getWebviewContent();
    if (loadConversation && this.activeTabId) {
      setTimeout(() => { if (this.activeTabId) this.sendLoadedConversation(this.activeTabId); }, 500);
    }
    this.webviewPanel.webview.onDidReceiveMessage(async (message) => {
      this.log('Webview message received: ' + message.command);
      switch (message.command) {
        case 'user_input':
          const currentFile = vscode.window.activeTextEditor?.document.uri.fsPath;
          const workspaceRoot = vscode.workspace.workspaceFolders?.[0]?.uri.fsPath;
          const relativePath = currentFile && workspaceRoot ? path.relative(workspaceRoot, currentFile) : undefined;
          await this.processUserInput(message.content, relativePath);
          break;
        case 'apply_changes': await this.handleApplyChanges(message.content); break;
        case 'copy_response': vscode.env.clipboard.writeText(message.content); vscode.window.showInformationMessage('Response copied to clipboard'); break;
        case 'stop_agent': await this.stopAgent(); break;
        case 'change_provider': await this.changeProvider(message.provider); break;
        case 'change_model': await this.changeModel(message.model); break;
        case 'fetch_models': await this.fetchAndSendModels(); break;
        case 'open_settings':
          try {
            this.log('Opening settings panel from webview...');
            const success = await vscode.commands.executeCommand('i2vision.settings');
            this.log('Settings command executed, result: ' + success);
          } catch (error: any) {
            this.log('Error opening settings: ' + error.message);
            vscode.window.showErrorMessage('Failed to open settings: ' + error.message);
          }
          break;
        case 'open_file':
          const viewColumn = message.viewColumn === 'beside' 
            ? vscode.ViewColumn.Beside 
            : vscode.ViewColumn.Active;
          try {
            let filePath = message.filePath;
            // Convert relative path to absolute if needed
            if (!path.isAbsolute(filePath)) {
              const workspaceRoot = vscode.workspace.workspaceFolders?.[0]?.uri.fsPath;
              if (workspaceRoot) {
                filePath = path.join(workspaceRoot, filePath);
              }
            }
            const doc = await vscode.workspace.openTextDocument(filePath);
            await vscode.window.showTextDocument(doc, viewColumn);
          } catch (error: any) {
            this.log('Error opening file: ' + error.message);
            vscode.window.showErrorMessage('Failed to open file: ' + error.message);
          }
          break;
        case 'fetch_history': await this.sendHistoryToWebview(); break;
        case 'resume_conversation': await this.resumeConversationFromWebview(message.conversationId); break;
        case 'new_chat':
          const confirmNew = await vscode.window.showWarningMessage(
            'Start New Chat',
            { modal: true, detail: 'Starting a new chat will close the current conversation. Make sure it is saved.' },
            'New Chat',
            'Cancel'
          );
          if (confirmNew === 'New Chat') {
            // Clear webview first to remove old conversation from UI
            this.sendToWebview({ command: 'clear_conversation' });
            if (this.activeTabId) {
              await this.closeTab(this.activeTabId);
            }
            await this.createTab('code');
          }
          break;
        case 'confirm_delete':
          const result = await vscode.window.showWarningMessage(
            'Delete Conversation',
            { modal: true, detail: 'Are you sure you want to delete this conversation? This action cannot be undone.' },
            'Delete',
            'Cancel'
          );
          if (result === 'Delete') {
            await this.deleteConversationFromWebview(message.conversationId);
          }
          break;
        case 'delete_conversation': await this.deleteConversationFromWebview(message.conversationId); break;
        default: this.log('Unknown message command: ' + message.command);
      }
    }, null, this.context.subscriptions);
    this.webviewPanel.onDidDispose(() => { this.webviewPanel = null; this.log('Webview panel disposed'); }, null, this.context.subscriptions);
  }

  private async sendHistoryToWebview(): Promise<void> {
    if (!this.historyManager) { this.sendToWebview({ command: 'history_list', conversations: [] }); return; }
    try {
      const conversationIds = await this.historyManager.list();
      const conversations = await Promise.all(conversationIds.map(async (id) => {
        const saved = await this.historyManager!.load(id);
        return { 
          id, 
          layer: saved?.layer || 'unknown', 
          contextTitle: saved?.contextTitle || 'Untitled',
          messageCount: saved?.messages.length || 0, 
          createdAt: saved?.createdAt || 0, 
          updatedAt: saved?.updatedAt || 0, 
          workspace: saved?.workspace || 'unknown' 
        };
      }));
      conversations.sort((a, b) => b.updatedAt - a.updatedAt);
      this.sendToWebview({ command: 'history_list', conversations });
    } catch (error: any) {
      this.log('Error fetching history: ' + error.message);
      this.sendToWebview({ command: 'history_list', conversations: [], error: error.message });
    }
  }

  private async resumeConversationFromWebview(conversationId: string): Promise<void> {
    try {
      this.log('Resuming conversation: ' + conversationId);
      // Clear current webview first
      this.sendToWebview({ command: 'clear_conversation' });
      // Close current tab if exists
      if (this.activeTabId) {
        await this.closeTab(this.activeTabId);
      }
      // Create new tab with saved conversation
      await this.resumeConversation(conversationId);
      // Get context title from saved conversation
      const saved = await this.historyManager?.load(conversationId);
      const contextTitle = saved?.contextTitle || 'Untitled';
      // Send loaded conversation after a delay to ensure webview is ready
      setTimeout(() => {
        if (this.activeTabId) {
          this.sendLoadedConversation(this.activeTabId);
          this.sendToWebview({ command: 'conversation_resumed', conversationId, contextTitle });
        }
      }, 500);
    } catch (error: any) {
      this.log('Error resuming conversation: ' + error.message);
      this.sendToWebview({ command: 'error', error: 'Failed to resume conversation: ' + error.message });
    }
  }

  private async deleteConversationFromWebview(conversationId: string): Promise<void> {
    try {
      if (!this.historyManager) throw new Error('History manager not available');
      await this.historyManager.delete(conversationId);
      this.log('Deleted conversation: ' + conversationId);
      await this.sendHistoryToWebview();
      this.sendToWebview({ command: 'conversation_deleted', conversationId });
    } catch (error: any) {
      this.log('Error deleting conversation: ' + error.message);
      this.sendToWebview({ command: 'error', error: 'Failed to delete conversation: ' + error.message });
    }
  }

  private async changeProvider(provider: string): Promise<void> {
    if (!this.activeTabId) { vscode.window.showErrorMessage('No active agent tab'); return; }
    const tabState = this.tabs.get(this.activeTabId);
    if (!tabState) { vscode.window.showErrorMessage('Active tab not found'); return; }
    this.log('Changing provider to ' + provider + ' for ' + tabState.layer + ' agent...');
    try {
      this.agentProvider.clearConfigCache();
      const agentConfig = this.agentProvider.getConfig(tabState.layer);
      agentConfig.model.provider = provider;
      const defaultModel = provider === 'deepseek' ? 'deepseek-chat' : 'llama3.2:3b';
      agentConfig.model.id = defaultModel;
      await this.agentProvider.updateConfig(tabState.layer, { provider, model: defaultModel });
      this.log('Provider changed to ' + provider);
      vscode.window.showInformationMessage('Provider changed to ' + provider);
      this.sendToWebview({ command: 'provider_changed', provider, model: defaultModel });
    } catch (error: any) {
      this.log('Error changing provider: ' + error.message);
      vscode.window.showErrorMessage('Failed to change provider: ' + error.message);
    }
  }

  private async changeModel(model: string): Promise<void> {
    if (!this.activeTabId) { vscode.window.showErrorMessage('No active agent tab'); return; }
    const tabState = this.tabs.get(this.activeTabId);
    if (!tabState) { vscode.window.showErrorMessage('Active tab not found'); return; }
    this.log('Changing model to ' + model + ' for ' + tabState.layer + ' agent...');
    try {
      const agentConfig = this.agentProvider.getConfig(tabState.layer);
      agentConfig.model.id = model;
      await this.agentProvider.updateConfig(tabState.layer, { provider: agentConfig.model.provider, model });
      this.log('Model changed to ' + model);
      vscode.window.showInformationMessage('Model changed to ' + model);
      this.sendToWebview({ command: 'model_changed', model });
    } catch (error: any) {
      this.log('Error changing model: ' + error.message);
      vscode.window.showErrorMessage('Failed to change model: ' + error.message);
    }
  }

  private async fetchAndSendModels(): Promise<void> {
    if (!this.activeTabId) return;
    const tabState = this.tabs.get(this.activeTabId);
    if (!tabState) return;
    try {
      const agentConfig = this.agentProvider.getConfig(tabState.layer);
      const providerId = agentConfig.model.provider;
      let models: string[] = [];
      if (providerId === 'ollama') models = await this.fetchOllamaModels();
      else if (providerId === 'deepseek') models = ['deepseek-chat', 'deepseek-coder'];
      this.sendToWebview({ command: 'models_list', models, currentModel: agentConfig.model.id, currentProvider: providerId });
    } catch (error: any) {
      this.log('Error fetching models: ' + error.message);
      this.sendToWebview({ command: 'models_list', models: [], currentModel: '', currentProvider: '', error: error.message });
    }
  }

  private async fetchOllamaModels(): Promise<string[]> {
    return new Promise<string[]>((resolve, reject) => {
      const http = require('http');
      const req = http.get('http://localhost:11434/api/tags', (res: any) => {
        let data = '';
        res.on('data', (chunk: string) => data += chunk);
        res.on('end', () => {
          try {
            const parsed = JSON.parse(data);
            resolve((parsed.models || []).map((m: any) => m.name));
          } catch (e) { reject(new Error('Failed to parse Ollama response')); }
        });
      });
      req.on('error', (e: any) => reject(e));
      req.setTimeout(5000, () => { req.destroy(); reject(new Error('Ollama timeout')); });
    });
  }

  private sendToWebview(message: any): void {
    if (this.webviewPanel) this.webviewPanel.webview.postMessage(message);
  }

  private getWebviewContent(): string {
    const workspaceName = vscode.workspace.workspaceFolders?.[0]?.name || 'Unknown';
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
    const settings = this.settingsManager.getSettings();
    const streamingEnabled = settings.streaming.enabled;
    const showThinking = settings.streaming.showThinkingIndicator;
    const selectedProvider = currentProvider === 'ollama' ? 'selected' : '';
    const selectedProviderDeepSeek = currentProvider === 'deepseek' ? 'selected' : '';

    // Read HTML template from file
    const templatePath = path.join(this.context.extensionPath, 'resources', 'agent-tab.html');
    let html = fs.readFileSync(templatePath, 'utf8');

    // Replace placeholders
    html = html.replace(/{workspaceName}/g, workspaceName);
    html = html.replace(/{currentProvider}/g, currentProvider);
    html = html.replace(/{currentModel}/g, currentModel);
    html = html.replace(/{selectedProvider}/g, selectedProvider);
    html = html.replace(/{selectedProviderDeepSeek}/g, selectedProviderDeepSeek);
    html = html.replace(/{streamingEnabled}/g, String(streamingEnabled));
    html = html.replace(/{showThinking}/g, String(showThinking));

    return html;
  }

  private async handleApplyChanges(content: string): Promise<void> {
    vscode.window.showInformationMessage('Apply changes not yet implemented');
  }

  async closeTab(tabId: string): Promise<void> {
    const tab = this.tabs.get(tabId);
    if (tab) {
      if (this.historyManager && tab.history.length > 0) {
        await this.historyManager.save(tabId, tab.history, tab.layer, tab.sessionState);
        this.log('Saved conversation history for ' + tabId + ' (' + tab.history.length + ' messages)');
      }
      tab.agent.dispose();
      this.tabs.delete(tabId);
      if (this.activeTabId === tabId) this.activeTabId = null;
      this.log('Closed tab ' + tabId);
    }
  }

  async resumeConversation(conversationId: string): Promise<string> {
    this.log('Resuming conversation: ' + conversationId);
    return await this.createTab('vision', conversationId);
  }

  async listConversations(): Promise<string[]> {
    if (!this.historyManager) return [];
    return this.historyManager.list();
  }

  getAllTabs(): AgentTabState[] { return Array.from(this.tabs.values()); }

  async dispose(): Promise<void> {
    this.stopAutoSaveTimer();
    if (this.webviewPanel) this.webviewPanel.dispose();
    if (this.currentAgentBridge) { this.currentAgentBridge.dispose(); this.currentAgentBridge = null; }
    const savePromises = Array.from(this.tabs.entries()).map(async ([tabId, tabState]) => {
      if (this.historyManager && tabState.history.length > 0) await this.historyManager.save(tabId, tabState.history, tabState.layer, tabState.sessionState);
      tabState.agent.dispose();
    });
    this.tabs.clear();
    this.log('AgentTabManager disposed');
    await Promise.all(savePromises);
    await this.enforceHistoryLimit();
  }
}
