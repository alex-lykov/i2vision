/**
 * AgentTabManager - Manages agent tabs in the VSCode webview
 */

import * as vscode from 'vscode';
import * as path from 'path';
import { AgentBridge, ToolCall } from './AgentBridge';
import { LocalAgentProvider } from './LocalAgentProvider';
import { LocalI2VisionAgent, VslfcLayer } from './LocalI2VisionAgent';
import { ConversationHistoryManager, ChatMessage } from './ConversationHistoryManager';
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
    this.log('AgentTabManager initialization complete');
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
      await this.historyManager.save(tabId, tabState.history, tabState.layer);
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
    this.showWebview();
    if (conversationId && this.historyManager) await this.loadConversation(tabId);
    return tabId;
  }

  private async loadConversation(tabId: string): Promise<void> {
    if (!this.historyManager) { this.log('History manager not available'); return; }
    const saved = await this.historyManager.load(tabId);
    if (!saved) { this.log('No saved conversation found for ' + tabId); return; }
    const tabState = this.tabs.get(tabId);
    if (!tabState) { this.log('Tab ' + tabId + ' not found for loading conversation'); return; }
    tabState.history = saved.messages;
    tabState.lastActivityAt = saved.updatedAt;
    tabState.lastAutoSaveAt = saved.updatedAt;
    this.log('Loaded conversation with ' + saved.messages.length + ' messages');
    for (const msg of saved.messages) {
      this.sendToWebview({ type: msg.role === 'user' ? 'user_message' : 'restored_message', content: msg.content, toolCalls: msg.toolCalls, timestamp: msg.timestamp });
    }
  }

  getActiveTabId(): string | null { return this.activeTabId; }
  getTabState(tabId: string): AgentTabState | undefined { return this.tabs.get(tabId); }
  clearConfigCache(): void { this.agentProvider.clearConfigCache(); this.log('Config cache cleared'); }

  async stopAgent(): Promise<void> {
    if (!this.isProcessing) { this.log('No active processing to stop'); return; }
    this.log('Stopping agent processing...');
    if (this.cancelTokenSource) this.cancelTokenSource.cancel();
    this.sendToWebview({ type: 'stopped', timestamp: Date.now() });
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
    this.sendToWebview({ type: 'user_message', content: userInput, timestamp: Date.now() });
    try {
      tabState.accumulatedToolCalls = [];
      this.cancelTokenSource = new vscode.CancellationTokenSource();
      let responseText = '';
      let startTime = Date.now();
      if (showThinking) this.sendToWebview({ type: 'thinking', message: 'Agent is thinking...', timestamp: Date.now() });
      const streamGenerator = this.currentAgentBridge.processStreaming(userInput, currentFile);
      for await (const chunk of streamGenerator) {
        if (this.cancelTokenSource.token.isCancellationRequested) {
          this.log('Processing cancelled by user');
          this.sendToWebview({ type: 'stopped', timestamp: Date.now() });
          break;
        }
        switch (chunk.type) {
          case 'tool_call_started':
            this.sendToWebview({ type: 'tool_start', toolName: chunk.toolName, args: chunk.args, timestamp: chunk.timestamp });
            break;
          case 'tool_call_completed':
            const toolCall: ToolCall = { toolName: chunk.toolName, args: {}, result: chunk.result };
            tabState.accumulatedToolCalls.push(toolCall);
            this.sendToWebview({ type: 'tool_complete', toolName: chunk.toolName, result: chunk.result, timestamp: chunk.timestamp });
            break;
          case 'text':
            responseText += chunk.text;
            if (streamingEnabled) this.sendToWebview({ type: 'streaming_text', text: chunk.text, timestamp: chunk.timestamp });
            break;
          case 'done':
            if (chunk.tokenUsage) this.sendToWebview({ type: 'token_usage', tokenUsage: chunk.tokenUsage, contextLength: this.currentAgentBridge.getConfig().model.contextLength, timestamp: chunk.timestamp });
            break;
          case 'error':
            vscode.window.showErrorMessage('Agent error: ' + chunk.error);
            break;
          case 'thinking':
            this.sendToWebview({ type: 'thinking', message: chunk.message, timestamp: chunk.timestamp });
            break;
        }
      }
      if (!this.cancelTokenSource?.token.isCancellationRequested) {
        const cleanedResponse = this.cleanResponseText(responseText);
        const assistantMessage: ChatMessage = { role: 'assistant', content: cleanedResponse, toolCalls: tabState.accumulatedToolCalls.map(tc => ({ toolName: tc.toolName, args: tc.args, result: tc.result })), timestamp: Date.now() };
        tabState.history.push(assistantMessage);
        if (settings.agent.autoSaveConversation && this.historyManager) await this.saveTabQuietly(this.activeTabId!, tabState);
        const durationMs = Date.now() - startTime;
        this.sendToWebview({ type: 'assistant_response', content: cleanedResponse, durationMs, timestamp: Date.now() });
        this.log('Complete: ' + tabState.accumulatedToolCalls.length + ' tools, ' + (Date.now() - tabState.lastActivityAt) + 'ms');
      }
    } catch (error: any) {
      if (error.name !== 'CancellationError' && !this.cancelTokenSource?.token.isCancellationRequested) {
        this.log('Error processing input: ' + error.message);
        vscode.window.showErrorMessage('Agent error: ' + error.message);
        this.sendToWebview({ type: 'error', error: error.message, timestamp: Date.now() });
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

  private showWebview(): void {
    if (this.webviewPanel) {
      this.webviewPanel.webview.html = this.getWebviewContent();
      this.webviewPanel.reveal(vscode.ViewColumn.One);
      return;
    }
    this.webviewPanel = vscode.window.createWebviewPanel('i2visionAgent', 'i2-Vision Agent', vscode.ViewColumn.One, {
      enableScripts: true,
      retainContextWhenHidden: true,
      localResourceRoots: [vscode.Uri.file(path.join(this.context.extensionPath, 'media'))]
    });
    this.webviewPanel.webview.html = this.getWebviewContent();
    this.webviewPanel.webview.onDidReceiveMessage(async (message) => {
      this.log('Webview message received: ' + message.type);
      switch (message.type) {
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
        case 'fetch_history': await this.sendHistoryToWebview(); break;
        case 'resume_conversation': await this.resumeConversationFromWebview(message.conversationId); break;
        case 'delete_conversation': await this.deleteConversationFromWebview(message.conversationId); break;
        default: this.log('Unknown message type: ' + message.type);
      }
    }, null, this.context.subscriptions);
    this.webviewPanel.onDidDispose(() => { this.webviewPanel = null; this.log('Webview panel disposed'); }, null, this.context.subscriptions);
  }

  private async sendHistoryToWebview(): Promise<void> {
    if (!this.historyManager) { this.sendToWebview({ type: 'history_list', conversations: [] }); return; }
    try {
      const conversationIds = await this.historyManager.list();
      const conversations = await Promise.all(conversationIds.map(async (id) => {
        const saved = await this.historyManager!.load(id);
        return { id, layer: saved?.layer || 'unknown', messageCount: saved?.messages.length || 0, createdAt: saved?.createdAt || 0, updatedAt: saved?.updatedAt || 0, workspace: saved?.workspace || 'unknown' };
      }));
      conversations.sort((a, b) => b.updatedAt - a.updatedAt);
      this.sendToWebview({ type: 'history_list', conversations });
    } catch (error: any) {
      this.log('Error fetching history: ' + error.message);
      this.sendToWebview({ type: 'history_list', conversations: [], error: error.message });
    }
  }

  private async resumeConversationFromWebview(conversationId: string): Promise<void> {
    try {
      this.log('Resuming conversation: ' + conversationId);
      await this.resumeConversation(conversationId);
      this.sendToWebview({ type: 'conversation_resumed', conversationId });
    } catch (error: any) {
      this.log('Error resuming conversation: ' + error.message);
      this.sendToWebview({ type: 'error', error: 'Failed to resume conversation: ' + error.message });
    }
  }

  private async deleteConversationFromWebview(conversationId: string): Promise<void> {
    try {
      if (!this.historyManager) throw new Error('History manager not available');
      await this.historyManager.delete(conversationId);
      this.log('Deleted conversation: ' + conversationId);
      await this.sendHistoryToWebview();
      this.sendToWebview({ type: 'conversation_deleted', conversationId });
    } catch (error: any) {
      this.log('Error deleting conversation: ' + error.message);
      this.sendToWebview({ type: 'error', error: 'Failed to delete conversation: ' + error.message });
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
      this.sendToWebview({ type: 'provider_changed', provider, model: defaultModel });
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
      this.sendToWebview({ type: 'model_changed', model });
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
      this.sendToWebview({ type: 'models_list', models, currentModel: agentConfig.model.id, currentProvider: providerId });
    } catch (error: any) {
      this.log('Error fetching models: ' + error.message);
      this.sendToWebview({ type: 'models_list', models: [], currentModel: '', currentProvider: '', error: error.message });
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
    const escapeHtmlStr = (text: string) => text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#039;');
    const selectedProvider = currentProvider === 'ollama' ? 'selected' : '';
    const selectedProviderDeepSeek = currentProvider === 'deepseek' ? 'selected' : '';

    return '<!DOCTYPE html><html lang="en"><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width, initial-scale=1.0"><meta http-equiv="Content-Security-Policy" content="default-src \'none\'; style-src \'self\' \'unsafe-inline\' https://*; font-src \'self\' https://* data: blob:; script-src \'unsafe-inline\'; img-src \'self\' https: data:;"><title>i2-Vision Agent</title><link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/codicons/0.0.36/codicon.min.css"><style>*{box-sizing:border-box;margin:0;padding:0}body{font-family:var(--vscode-font-family);font-size:var(--vscode-font-size);color:var(--vscode-foreground);background-color:var(--vscode-editor-background);padding:0;line-height:1.6;height:100vh;display:flex;flex-direction:column;overflow:hidden}.header{flex:0 0 auto;padding:8px 16px;background-color:var(--vscode-editorWidget-background);border-bottom:1px solid var(--vscode-editorWidget-border);display:flex;flex-wrap:nowrap;gap:16px;align-items:center;justify-content:space-between}.header-left{display:flex;gap:12px;align-items:center;flex-wrap:nowrap}.header-item{display:flex;align-items:center;gap:6px;font-size:0.85em;color:var(--vscode-foreground);white-space:nowrap}.header-item .codicon{font-size:1.1em;color:var(--vscode-descriptionForeground)}.provider-model-group{display:flex;gap:10px;align-items:center;flex-wrap:nowrap}.selector-group{display:flex;align-items:center;gap:6px}.selector-group label{font-weight:500;color:var(--vscode-descriptionForeground);font-size:0.8em}.selector-group select{padding:3px 8px;border:1px solid var(--vscode-input-border);border-radius:4px;background:var(--vscode-input-background);color:var(--vscode-input-foreground);font-family:var(--vscode-font-family);font-size:0.8em;cursor:pointer;min-width:120px}.selector-group select:hover{border-color:var(--vscode-focusBorder)}.selector-group select:focus{outline:2px solid var(--vscode-focusBorder);outline-offset:-2px}.token-meter{display:flex;align-items:center;gap:8px;font-size:0.75em;color:var(--vscode-descriptionForeground)}.token-bar{width:100px;height:4px;background-color:var(--vscode-input-border);border-radius:2px;overflow:hidden}.token-fill{height:100%;background:linear-gradient(90deg,var(--vscode-terminal-successBackground) 0%,var(--vscode-terminal-successBackground) 50%,var(--vscode-terminal-ansiYellow) 75%,var(--vscode-errorForeground) 100%);transition:width 0.3s ease}.header-right{display:flex;gap:8px;align-items:center}.btn-icon{width:28px;height:28px;display:flex;align-items:center;justify-content:center;background:transparent;border:1px solid transparent;border-radius:4px;cursor:pointer;color:var(--vscode-foreground);transition:all 0.2s ease;font-size:1.2em}.btn-icon:hover{background:var(--vscode-toolbar-hoverBackground);border-color:var(--vscode-input-border)}.btn-icon .codicon{font-size:16px;display:inline-block;line-height:1}.btn-icon .codicon::before{display:inline-block}.center-panel{flex:1 1 auto;overflow-y:auto;padding:16px;display:flex;flex-direction:column;gap:12px}.timeline{max-width:900px;margin:0 auto;width:100%;display:flex;flex-direction:column;gap:12px}.message{padding:12px 15px;border-radius:6px;white-space:pre-wrap;border-left:4px solid}.message.user{background-color:var(--vscode-editor-inactiveSelectionBackground);border-left-color:var(--vscode-button-background)}.message.agent{background-color:var(--vscode-editor-selectionBackground);border-left-color:var(--vscode-editorLineNumber-foreground)}.message.restored{background-color:var(--vscode-editorWidget-background);border-left-color:var(--vscode-descriptionForeground);opacity:0.8}.message.stopped{background-color:var(--vscode-editorWidget-background);border-left-color:var(--vscode-descriptionForeground);font-style:italic;color:var(--vscode-descriptionForeground)}.message.error{background-color:var(--vscode-inputValidation-errorBackground);border-left-color:var(--vscode-errorForeground);color:var(--vscode-errorForeground)}.thinking-indicator{display:flex;align-items:center;gap:10px;padding:10px 12px;background-color:var(--vscode-editor-selectionBackground);border-left:3px solid var(--vscode-progressBarBackground);border-radius:6px;font-style:italic;color:var(--vscode-descriptionForeground)}.thinking-indicator .codicon-loading{animation:spin 1s linear infinite}@keyframes spin{to{transform:rotate(360deg)}}.tool-card{background-color:var(--vscode-editorWidget-background);border:1px solid var(--vscode-editorWidget-border);border-radius:6px;padding:10px 12px;display:flex;flex-direction:column;gap:8px;transition:all 0.2s ease}.tool-card.running{border-left:3px solid var(--vscode-progressBarBackground)}.tool-card.done{border-left:3px solid var(--vscode-terminal-successBackground)}.tool-card.error{border-left:3px solid var(--vscode-errorForeground)}.tool-card-header{display:flex;align-items:center;gap:8px;font-weight:600;font-size:0.9em;cursor:pointer;user-select:none}.tool-card-header:hover{opacity:0.9}.tool-card-status .codicon{font-size:1.1em}.tool-card-status.running .codicon{animation:pulse 1s ease-in-out infinite}@keyframes pulse{0%,100%{opacity:0.5}50%{opacity:1}}.tool-card-content{display:none;flex-direction:column;gap:6px;margin-top:4px}.tool-card.expanded .tool-card-content{display:flex}.toggle-icon{margin-left:auto;transition:transform 0.2s}.tool-card.expanded .toggle-icon{transform:rotate(90deg)}.tool-card-label{font-weight:600;color:var(--vscode-descriptionForeground);font-size:0.8em;text-transform:uppercase;letter-spacing:0.5px}.tool-card-result{background-color:var(--vscode-textCodeBlock-background);padding:8px;border-radius:4px;font-family:var(--vscode-editor-font-family);font-size:0.85em;max-height:300px;overflow-y:auto;white-space:pre-wrap;word-break:break-word}.response-card{background-color:var(--vscode-editorWidget-background);border:1px solid var(--vscode-editorWidget-border);border-radius:6px;padding:10px 12px;display:flex;flex-direction:column;gap:8px}.response-card.streaming{border-left:3px solid var(--vscode-progressBarBackground)}.response-card.done{border-left:3px solid #73c991}.response-card-header{display:flex;align-items:center;gap:8px;font-size:0.9em;font-weight:600;color:var(--vscode-foreground)}.response-card-status{font-size:0.8em;margin-left:auto}.response-card-status.streaming{color:var(--vscode-progressBarBackground)}.response-card-status.done{color:#73c991}.response-card-body{background-color:var(--vscode-textCodeBlock-background);padding:10px 12px;border-radius:4px;font-family:var(--vscode-editor-font-family);font-size:0.9em;white-space:pre-wrap;word-break:break-word;line-height:1.5;max-height:500px;overflow-y:auto}.message-footer{display:flex;justify-content:space-between;align-items:center;padding:8px 12px;background-color:var(--vscode-editorWidget-background);border-radius:4px;margin-top:10px;font-size:0.85em}.footer-left{display:flex;gap:15px;color:var(--vscode-descriptionForeground)}.footer-right{display:flex;gap:8px}.footer-panel{flex:0 0 auto;padding:16px;background-color:var(--vscode-editorWidget-background);border-top:1px solid var(--vscode-editorWidget-border)}.input-container{max-width:900px;margin:0 auto}.input-box{width:100%;padding:12px;border:1px solid var(--vscode-editorWidget-border);border-radius:6px;background-color:var(--vscode-input-background);color:var(--vscode-input-foreground);font-family:var(--vscode-font-family);font-size:var(--vscode-font-size);resize:vertical;min-height:80px}.input-box:focus{outline:2px solid var(--vscode-focusBorder);outline-offset:-2px}.button-row{margin-top:10px;display:flex;gap:8px;align-items:center}.btn-action{padding:8px 20px;border:none;border-radius:4px;cursor:pointer;font-family:var(--vscode-font-family);font-size:0.9em;display:inline-flex;align-items:center;gap:8px;font-weight:500;transition:all 0.2s ease}.btn-action.send{background-color:var(--vscode-button-background);color:var(--vscode-button-foreground)}.btn-action.send:hover{background-color:var(--vscode-button-hoverBackground)}.btn-action.stop{background-color:var(--vscode-errorForeground);color:white}.btn-action.stop:hover{opacity:0.9}.btn-secondary{padding:8px 16px;border:1px solid var(--vscode-button-secondaryBackground);border-radius:4px;background-color:var(--vscode-button-secondaryBackground);color:var(--vscode-button-secondaryForeground);cursor:pointer;font-family:var(--vscode-font-family);font-size:0.9em;display:inline-flex;align-items:center;gap:6px;transition:all 0.2s ease}.btn-secondary:hover{opacity:0.9}.history-sidebar{position:fixed;right:0;top:0;bottom:0;width:320px;background:var(--vscode-sideBar-background,#252526);border-left:1px solid var(--vscode-sideBar-border,#333);transform:translateX(100%);transition:transform 0.2s ease;z-index:100;display:flex;flex-direction:column;box-shadow:-2px 0 8px rgba(0,0,0,0.3)}.history-sidebar.visible{transform:translateX(0)}.history-header{padding:12px;border-bottom:1px solid var(--vscode-sideBar-border,#333);display:flex;justify-content:space-between;align-items:center;background:var(--vscode-sideBarSectionHeader-background,#252526)}.history-title{font-weight:600;font-size:0.9em;color:var(--vscode-sideBarSectionHeader-foreground,#d4d4d4);display:flex;align-items:center;gap:8px}.history-list{flex:1;overflow-y:auto;padding:8px}.history-item{padding:10px;margin-bottom:8px;background:var(--vscode-list-item-background,#2d2d2d);border-radius:4px;cursor:pointer;border:1px solid transparent;transition:all 0.2s ease}.history-item:hover{border-color:var(--vscode-focusBorder,#007fd4);background:var(--vscode-list-hoverBackground,#2a2d2e)}.history-item-header{display:flex;justify-content:space-between;align-items:center;margin-bottom:6px}.history-item-layer{font-size:0.7em;text-transform:uppercase;padding:2px 6px;border-radius:3px;background:var(--vscode-badge-background,#007fd4);color:var(--vscode-badge-foreground,#ffffff);font-weight:600}.history-item-time{font-size:0.7em;color:var(--vscode-descriptionForeground,#888)}.history-item-meta{font-size:0.75em;color:var(--vscode-descriptionForeground,#888);margin-bottom:6px;display:flex;gap:8px;align-items:center}.history-item-actions{display:flex;gap:6px}.history-item-actions .btn-secondary{flex:1;justify-content:center;font-size:0.75em;padding:4px 8px}.history-empty{text-align:center;padding:30px 20px;color:var(--vscode-descriptionForeground,#888);font-style:italic;font-size:0.85em}.history-refresh-btn{background:transparent;border:none;color:var(--vscode-foreground,#d4d4d4);cursor:pointer;font-size:1.1em;padding:4px;border-radius:4px}.history-refresh-btn:hover{background:var(--vscode-toolbar-hoverBackground,rgba(255,255,255,0.1))}</style></head><body><div class="header"><div class="header-left"><div class="header-item"><span class="codicon codicon-folder-active"></span><span id="workspaceName">' + escapeHtmlStr(workspaceName) + '</span></div><div class="header-item"><span class="codicon codicon-file"></span><span id="currentFile">None</span></div><div class="provider-model-group"><div class="selector-group"><label for="providerSelect">Provider:</label><select id="providerSelect" onchange="onProviderChange()"><option value="ollama" ' + selectedProvider + '>Ollama</option><option value="deepseek" ' + selectedProviderDeepSeek + '>DeepSeek</option></select></div><div class="selector-group"><label for="modelSelect">Model:</label><select id="modelSelect" onchange="onModelChange()" style="min-width:180px;"><option value="' + escapeHtmlStr(currentModel) + '" selected>' + escapeHtmlStr(currentModel) + '</option></select></div></div></div><div class="header-right"><div class="token-meter"><span class="codicon codicon-database"></span><div class="token-bar"><div class="token-fill" id="tokenFill" style="width:0%"></div></div><span id="tokenText">0 / 0</span></div><button class="btn-icon" onclick="openSettings()" title="Settings">S</button><button class="btn-icon" onclick="toggleHistorySidebar()" title="Conversation History">H</button></div></div><div class="center-panel"><div class="timeline" id="timeline"><div class="message agent"><span class="codicon codicon-robot" style="margin-right:8px;"></span>Hello! I am your i2-Vision coding agent. What would you like to work on?</div></div></div><div class="footer-panel"><div class="input-container"><textarea class="input-box" id="userInput" placeholder="Ask me anything about your code..." rows="3"></textarea><div class="button-row"><button class="btn-action send" id="actionBtn"><span class="codicon codicon-play" id="actionIcon"></span><span id="actionText">Send</span></button><button class="btn-secondary" id="clearBtn"><span class="codicon codicon-clear-all"></span>Clear</button></div></div></div><div class="history-sidebar" id="historySidebar"><div class="history-header"><span class="history-title"><span class="codicon codicon-comment-discussion"></span>Conversation History</span><div style="display:flex;gap:6px;align-items:center"><button class="history-refresh-btn" onclick="refreshHistory()" title="Refresh"><span class="codicon codicon-refresh"></span></button><button class="btn-icon" onclick="toggleHistorySidebar()" title="Close"><span class="codicon codicon-close"></span></button></div></div><div class="history-list" id="historyList"><div class="history-empty">Loading...</div></div></div><script>const vscode=acquireVsCodeApi();const timeline=document.getElementById("timeline");const userInput=document.getElementById("userInput");const actionBtn=document.getElementById("actionBtn");const actionIcon=document.getElementById("actionIcon");const actionText=document.getElementById("actionText");const clearBtn=document.getElementById("clearBtn");const tokenFill=document.getElementById("tokenFill");const tokenText=document.getElementById("tokenText");const providerSelect=document.getElementById("providerSelect");const modelSelect=document.getElementById("modelSelect");let streamingElement=null;let thinkingEl=null;let isProcessing=false;const streamingEnabled=' + streamingEnabled + ';const showThinkingSetting=' + showThinking + ';let historySidebarVisible=false;window.addEventListener("load",function(){vscode.postMessage({type:"fetch_models"})});function toggleHistorySidebar(){historySidebarVisible=!historySidebarVisible;const sidebar=document.getElementById("historySidebar");if(historySidebarVisible){sidebar.classList.add("visible");refreshHistory()}else{sidebar.classList.remove("visible")}}function refreshHistory(){vscode.postMessage({type:"fetch_history"})}function renderHistoryList(conversations){const list=document.getElementById("historyList");if(!conversations||conversations.length===0){list.innerHTML="<div class=\\"history-empty\\">No saved conversations yet.</div>";return}list.innerHTML=conversations.map(function(conv){const date=new Date(conv.updatedAt);const timeStr=date.toLocaleDateString()+" "+date.toLocaleTimeString([],{hour:"2-digit",minute:"2-digit"});return"<div class=\\"history-item\\" data-id=\\""+conv.id+"\\">"+"<div class=\\"history-item-header\\"><span class=\\"history-item-layer\\">"+escapeHtml(conv.layer)+"</span><span class=\\"history-item-time\\">"+timeStr+"</span></div>"+"<div class=\\"history-item-meta\\"><span><span class=\\"codicon codicon-comment\\"></span> "+conv.messageCount+" msgs</span><span><span class=\\"codicon codicon-folder\\"></span> "+escapeHtml(conv.workspace)+"</span></div>"+"<div class=\\"history-item-actions\\"><button class=\\"btn-secondary\\" onclick=\\"resumeConversation("+JSON.stringify(conv.id)+")\\" style=\\"flex:1;\\"><span class=\\"codicon codicon-reply\\"></span> Resume</button><button class=\\"btn-secondary\\" onclick=\\"deleteConversation("+JSON.stringify(conv.id)+")\\" title=\\"Delete\\"><span class=\\"codicon codicon-trash\\"></span></button></div>"+"</div>"}).join("")}function resumeConversation(conversationId){if(confirm("Resume this conversation?")){vscode.postMessage({type:"resume_conversation",conversationId:conversationId})}}function deleteConversation(conversationId){if(confirm("Delete this conversation permanently?")){vscode.postMessage({type:"delete_conversation",conversationId:conversationId})}}actionBtn.addEventListener("click",function(){if(isProcessing){vscode.postMessage({type:"stop_agent"})}else{const content=userInput.value.trim();if(content){vscode.postMessage({type:"user_input",content:content});userInput.value=""}}});userInput.addEventListener("keydown",function(e){if(e.key==="Enter"&&!e.shiftKey){e.preventDefault();actionBtn.click()}});clearBtn.addEventListener("click",function(){timeline.innerHTML="";streamingElement=null;thinkingEl=null;setProcessingState(false)});function onProviderChange(){const provider=providerSelect.value;vscode.postMessage({type:"change_provider",provider:provider})}function onModelChange(){const model=modelSelect.value;vscode.postMessage({type:"change_model",model:model})}function openSettings(){vscode.postMessage({type:"open_settings"})}window.addEventListener("message",function(event){const message=event.data;switch(message.type){case"user_message":appendUserMessage(message.content);break;case"restored_message":appendRestoredMessage(message.content,message.toolCalls,message.timestamp);break;case"thinking":showThinkingIndicator(message.message);break;case"thinking_update":updateThinkingIndicator(message.message);break;case"tool_start":appendToolCard(message.toolName,message.args);collapseAllToolCardsExceptLast();break;case"tool_complete":updateToolCard(message.toolName,message.result);collapseAllToolCardsExceptLast();break;case"streaming_text":appendStreamingText(message.text);break;case"assistant_response":if(!streamingElement&&message.content){appendStreamingText(message.content)}finalizeStreamingText(message.durationMs);setProcessingState(false);break;case"token_usage":updateTokenMeter(message.tokenUsage,message.contextLength);break;case"error":appendErrorMessage(message.error);setProcessingState(false);break;case"stopped":hideThinkingIndicator();setProcessingState(false);appendStoppedMessage();break;case"provider_changed":providerSelect.value=message.provider;vscode.postMessage({type:"fetch_models"});break;case"model_changed":modelSelect.value=message.model;break;case"models_list":populateModelDropdown(message.models,message.currentModel,message.currentProvider);break;case"history_list":renderHistoryList(message.conversations);break;case"conversation_resumed":showToast("Conversation resumed");toggleHistorySidebar();break;case"conversation_deleted":showToast("Conversation deleted");break}scrollToBottom()});function populateModelDropdown(models,currentModel,currentProvider){modelSelect.innerHTML="";if(models&&models.length>0){models.forEach(function(model){const option=document.createElement("option");option.value=model;option.textContent=model;if(model===currentModel)option.selected=true;modelSelect.appendChild(option)})}else{const option=document.createElement("option");option.value=currentModel;option.textContent=currentModel;option.selected=true;modelSelect.appendChild(option)}}function appendUserMessage(content){const div=document.createElement("div");div.className="message user";div.textContent=content;timeline.appendChild(div)}function appendRestoredMessage(content,toolCalls,timestamp){const div=document.createElement("div");div.className="message restored";div.textContent=content;timeline.appendChild(div);if(toolCalls&&toolCalls.length>0){toolCalls.forEach(function(tc){const card=document.createElement("div");card.className="tool-card done collapsed";const cardId="tool-"+Date.now();card.id=cardId;let resultHtml=tc.result?"<div class=\\"tool-card-label\\">Result</div><div class=\\"tool-card-result\\">"+escapeHtml(tc.result.substring(0,500))+"</div>":"";card.innerHTML="<div class=\\"tool-card-header\\" onclick=\\"toggleToolCard("+JSON.stringify(cardId)+")\\"><span class=\\"tool-card-status\\"><span class=\\"codicon codicon-check\\"></span></span><span>"+escapeHtml(tc.toolName)+"</span><span class=\\"toggle-icon\\"><span class=\\"codicon codicon-chevron-right\\"></span></span></div><div class=\\"tool-card-content\\">"+resultHtml+"</div>";timeline.appendChild(card)})}}function showThinkingIndicator(message){if(!showThinkingSetting)return;if(thinkingEl)thinkingEl.remove();thinkingEl=document.createElement("div");thinkingEl.className="thinking-indicator";thinkingEl.innerHTML="<span class=\\"codicon codicon-loading\\"></span><span>"+message+"</span>";timeline.appendChild(thinkingEl);setProcessingState(true)}function updateThinkingIndicator(message){if(thinkingEl)thinkingEl.remove();thinkingEl=document.createElement("div");thinkingEl.className="thinking-indicator";thinkingEl.innerHTML="<span class=\\"codicon codicon-loading\\"></span><span>"+message+"</span>";timeline.appendChild(thinkingEl)}function hideThinkingIndicator(){if(thinkingEl){thinkingEl.remove();thinkingEl=null}}function appendToolCard(toolName,args){if(streamingElement){const statusEl=streamingElement.querySelector(".response-card-status");if(statusEl){statusEl.className="response-card-status done";statusEl.innerHTML="<span class=\\"codicon codicon-check\\"></span> Done"}streamingElement.className="response-card done";streamingElement=null}const cardId="tool-"+Date.now();const card=document.createElement("div");card.className="tool-card running expanded";card.id=cardId;card.dataset.toolName=toolName;let argsHtml=(args&&Object.keys(args).length>0)?"<div class=\\"tool-card-label\\">Arguments</div><div class=\\"tool-card-result\\">"+escapeHtml(JSON.stringify(args,null,2))+"</div>":"";card.innerHTML="<div class=\\"tool-card-header\\" onclick=\\"toggleToolCard("+JSON.stringify(cardId)+")\\"><span class=\\"tool-card-status running\\"><span class=\\"codicon codicon-loading\\"></span></span><span><span class=\\"codicon codicon-wrench\\"></span> "+escapeHtml(toolName)+"</span><span class=\\"toggle-icon\\"><span class=\\"codicon codicon-chevron-right\\"></span></span></div><div class=\\"tool-card-content\\">"+argsHtml+"</div>";timeline.appendChild(card)}function updateToolCard(toolName,result){const cards=timeline.querySelectorAll(".tool-card.running");let targetCard=null;for(let i=cards.length-1;i>=0;i--){if(cards[i].dataset.toolName===toolName){targetCard=cards[i];break}}if(targetCard){targetCard.classList.remove("running");targetCard.classList.add("done");const statusEl=targetCard.querySelector(".tool-card-status");if(statusEl){statusEl.className="tool-card-status";statusEl.innerHTML="<span class=\\"codicon codicon-check\\"></span>"}const contentDiv=targetCard.querySelector(".tool-card-content");if(contentDiv&&result){const resultHtml="<div class=\\"tool-card-label\\" style=\\"margin-top:8px;\\">Result</div><div class=\\"tool-card-result\\">"+escapeHtml(result.substring(0,3000))+"</div>";contentDiv.insertAdjacentHTML("beforeend",resultHtml)}}}function appendStreamingText(text){if(!streamingElement){streamingElement=document.createElement("div");streamingElement.className="response-card streaming";streamingElement.innerHTML="<div class=\\"response-card-header\\"><span class=\\"codicon codicon-comment\\"></span><span class=\\"response-card-title\\">Response</span><span class=\\"response-card-status streaming\\"><span class=\\"codicon codicon-loading\\"></span> Streaming...</span></div><div class=\\"response-card-body\\"></div>";timeline.appendChild(streamingElement);scrollToBottom()}const body=streamingElement.querySelector(".response-card-body");if(body){body.textContent+=text;scrollToBottom()}}function finalizeStreamingText(durationMs){hideThinkingIndicator();let targetElement=streamingElement;if(!targetElement){const responseCards=timeline.querySelectorAll(".response-card");if(responseCards.length>0)targetElement=responseCards[responseCards.length-1]}if(targetElement){const statusEl=targetElement.querySelector(".response-card-status");if(statusEl){statusEl.className="response-card-status done";statusEl.innerHTML="<span class=\\"codicon codicon-check\\"></span> Done"}targetElement.className="response-card done";const footer=document.createElement("div");footer.className="message-footer";const durationStr=durationMs?(durationMs/1000).toFixed(1):"?";footer.innerHTML="<div class=\\"footer-left\\"><span><span class=\\"codicon codicon-clock\\"></span> "+durationStr+"s</span></div><div class=\\"footer-right\\"><button class=\\"btn-secondary\\" onclick=\\"copyResponse()\\" style=\\"padding:4px 8px;font-size:0.85em;\\"><span class=\\"codicon codicon-copy\\"></span> Copy</button><button class=\\"btn-secondary\\" onclick=\\"applyChanges()\\" style=\\"padding:4px 8px;font-size:0.85em;\\"><span class=\\"codicon codicon-check\\"></span> Apply</button></div>";targetElement.appendChild(footer);streamingElement=null}}function updateTokenMeter(tokenUsage,contextLength){const total=tokenUsage.prompt+tokenUsage.completion;const percentage=Math.min((total/contextLength)*100,100);tokenFill.style.width=percentage+"%";tokenText.textContent=total.toLocaleString()+" / "+contextLength.toLocaleString();if(percentage>80)tokenFill.style.background="var(--vscode-errorForeground)";else if(percentage>60)tokenFill.style.background="var(--vscode-terminal-ansiYellow)";else tokenFill.style.background="var(--vscode-terminal-successBackground)"}function appendErrorMessage(error){const div=document.createElement("div");div.className="message error";div.innerHTML="<span class=\\"codicon codicon-error\\" style=\\"margin-right:8px;\\"></span>Error: "+error;timeline.appendChild(div)}function appendStoppedMessage(){const div=document.createElement("div");div.className="message stopped";div.innerHTML="<span class=\\"codicon codicon-debug-stop\\" style=\\"margin-right:8px;\\"></span>Processing stopped by user.";timeline.appendChild(div)}function toggleToolCard(cardId){const card=document.getElementById(cardId);if(card)card.classList.toggle("expanded")}function collapseAllToolCardsExceptLast(){const cards=timeline.querySelectorAll(".tool-card.done");cards.forEach(function(card,index){if(index<cards.length-1)card.classList.add("collapsed");else card.classList.remove("collapsed")})}function scrollToBottom(){timeline.scrollTop=timeline.scrollHeight}function escapeHtml(text){if(!text)return"";const div=document.createElement("div");div.textContent=text;return div.innerHTML}function copyResponse(){const lastAgentMessage=timeline.querySelector(".message.agent:last-child");if(lastAgentMessage){const content=lastAgentMessage.childNodes[0]?.textContent||"";vscode.postMessage({type:"copy_response",content:content})}}function applyChanges(){vscode.postMessage({type:"apply_changes"})}function setProcessingState(processing){isProcessing=processing;if(processing){actionBtn.classList.remove("send");actionBtn.classList.add("stop");actionIcon.className="codicon codicon-debug-stop";actionText.textContent="Stop";userInput.disabled=true;userInput.style.opacity="0.5"}else{actionBtn.classList.remove("stop");actionBtn.classList.add("send");actionIcon.className="codicon codicon-play";actionText.textContent="Send";userInput.disabled=false;userInput.style.opacity="1"}}function showToast(message){const toast=document.createElement("div");toast.style.cssText="position:fixed;bottom:20px;left:50%;transform:translateX(-50%);background:var(--vscode-notifications-background);color:var(--vscode-notifications-foreground);padding:10px 20px;border-radius:6px;box-shadow:0 2px 8px rgba(0,0,0,0.3);z-index:1000;font-size:0.9em;display:flex;align-items:center;gap:8px;";toast.innerHTML="<span class=\\"codicon codicon-check\\"></span>"+message;document.body.appendChild(toast);setTimeout(function(){toast.remove()},2000)}</script></body></html>';
  }

  private async handleApplyChanges(content: string): Promise<void> {
    vscode.window.showInformationMessage('Apply changes not yet implemented');
  }

  async closeTab(tabId: string): Promise<void> {
    const tab = this.tabs.get(tabId);
    if (tab) {
      if (this.historyManager && tab.history.length > 0) {
        await this.historyManager.save(tabId, tab.history, tab.layer);
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
      if (this.historyManager && tabState.history.length > 0) await this.historyManager.save(tabId, tabState.history, tabState.layer);
      tabState.agent.dispose();
    });
    this.tabs.clear();
    this.log('AgentTabManager disposed');
    await Promise.all(savePromises);
    await this.enforceHistoryLimit();
  }
}
