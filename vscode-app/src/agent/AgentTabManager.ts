/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

/**
 * AgentTabManager - Manages agent tabs in the VSCode webview
 */

import * as vscode from 'vscode';
import * as path from 'path';
import * as fs from 'fs';
import {AgentBridge, ToolCall} from './AgentBridge';
import {LocalAgentProvider} from './LocalAgentProvider';
import {LocalI2VisionAgent, VslfcLayer} from './LocalI2VisionAgent';
import {AgentSessionState, ChatMessage, ConversationHistoryManager} from './ConversationHistoryManager';
import {AgentSettingsManager} from './AgentSettings';
import {ContextMeter} from './ContextMeter';

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
  contextMeter: ContextMeter; // Comprehensive context usage tracking
  isFirstUserInput?: boolean; // Track if this is the first user input after tab creation
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
  private proxyHealthTimer: NodeJS.Timeout | null = null;
  private loadedConversationTimeout: NodeJS.Timeout | null = null;
  private readonly AUTO_SAVE_INTERVAL_MS = 5 * 60 * 1000;
  private readonly PROXY_HEALTH_INTERVAL_MS = 10 * 1000;

  constructor(context: vscode.ExtensionContext, outputChannel: vscode.OutputChannel, agentProvider: LocalAgentProvider) {
    this.context = context;
    this.outputChannel = outputChannel;
    this.agentProvider = agentProvider;
    this.settingsManager = AgentSettingsManager.getInstance(context);
    const workspaceRoot = vscode.workspace.workspaceFolders?.[0]?.uri.fsPath || '';
    this.log('Creating tab with workspaceRoot=' + workspaceRoot);
    if (workspaceRoot) {
      this.historyManager = new ConversationHistoryManager(workspaceRoot);
    }
    this.log('AgentTabManager initialized');
  }

  async initialize(): Promise<void> {
    await this.agentProvider.initialize();
    this.startProxyHealthTimer();
    // await this.loadLastConversation(); // DISABLED: new chat should start fresh
    this.log('AgentTabManager initialization complete');
  }

  private async loadLastConversation(): Promise<void> {
    // DISABLED: new chat should start fresh. Conversations must be explicitly resumed.
    // This method intentionally does nothing to prevent auto-loading old sessions.
    this.log('loadLastConversation: disabled - new chats start fresh');
  }

  private startProxyHealthTimer(): void {
    if (this.proxyHealthTimer) clearInterval(this.proxyHealthTimer);
    this.proxyHealthTimer = setInterval(() => this.updateProxyDashboard(), this.PROXY_HEALTH_INTERVAL_MS);
    this.log('Proxy health timer started (interval: ' + (this.PROXY_HEALTH_INTERVAL_MS / 1000) + 's)');
  }

  private stopProxyHealthTimer(): void {
    if (this.proxyHealthTimer) {
      clearInterval(this.proxyHealthTimer);
      this.proxyHealthTimer = null;
      this.log('Proxy health timer stopped');
    }
  }

  /**
   * Poll proxy health and session status, send dashboard update to webview.
   */
  private async updateProxyDashboard(): Promise<void> {
    if (!this.activeTabId) return;
    const tabState = this.tabs.get(this.activeTabId);
    if (!tabState) return;

    const config = this.agentProvider.getConfig(tabState.layer);
    if (config.model.provider !== '3d-llm') return;

    const bridge = this.currentAgentBridge;
    if (!bridge) return;

    const sessionMgr = bridge.getSessionManager?.();
    if (!sessionMgr || sessionMgr.name === 'Null') return;

    try {
      const health = await sessionMgr.checkHealth();
      const sessionState = sessionMgr.getSessionState();
      const contextStatus = tabState.contextMeter.getUsageSummary();

      this.sendToWebview({
        command: 'proxy_dashboard',
        health: { healthy: health.healthy, status: health.diagnostics.status || 'unknown', agents: health.diagnostics.agents || 0 },
        session: sessionState,
        contextStatus,
        warnings: health.warnings,
        timestamp: Date.now(),
      });
    } catch (error: any) {
      this.log(`Proxy dashboard update failed: ${error.message}`);
      this.sendToWebview({
        command: 'proxy_dashboard',
        error: 'Proxy unreachable',
        timestamp: Date.now(),
      });
    }
  }

  /**
   * Save the current active tab's conversation immediately.
   * Called after every assistant response and on deactivate.
   */
  private async saveActiveTab(): Promise<void> {
    if (!this.historyManager || !this.activeTabId) return;
    const tabState = this.tabs.get(this.activeTabId);
    if (!tabState || tabState.history.length === 0) return;
    try {
      await this.historyManager.save(this.activeTabId, tabState.history, tabState.layer, tabState.sessionState);
    } catch (error: any) {
      this.log('Save failed for ' + this.activeTabId + ': ' + error.message);
    }
  }

  /**
   * Dispose the tab manager â€” save all tabs and stop timers.
   * Called from extension deactivate().
   */
  async dispose(): Promise<void> {
    this.log('Disposing AgentTabManager â€” saving all tabs...');
    this.stopProxyHealthTimer();
    if (this.historyManager) {
      for (const [tabId, tabState] of this.tabs.entries()) {
        if (tabState.history.length > 0) {
          try {
            await this.historyManager.save(tabId, tabState.history, tabState.layer, tabState.sessionState);
            this.log('Saved tab ' + tabId + ' (' + tabState.history.length + ' messages)');
          } catch (error: any) {
            this.log('Dispose save failed for ' + tabId + ': ' + error.message);
          }
        }
      }
    }
    this.log('AgentTabManager disposed');
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
    // Always start from a fresh webview. This is the single choke point for all
    // tab creation (new chat, resume, command palette) and prevents the previous
    // conversation DOM from persisting when the new HTML is byte-identical.
    this.disposeWebview();

    // Reset processing state so a new tab never inherits a stuck flag or a
    // stale cancellation token from a prior (possibly interruped) session.
    this.isProcessing = false;
    if (this.cancelTokenSource) {
      this.cancelTokenSource.cancel();
      this.cancelTokenSource.dispose();
      this.cancelTokenSource = null;
    }

    // HARDCODED: Until VSLFC layer support is fully implemented, all agent sessions
    // use only the CODE layer with .vision-ai/code-agent.yaml.
    const effectiveLayer = 'code';
    this.log('Creating ' + effectiveLayer + ' agent tab (hardcoded â€” CODE layer only at current implementation stage)...');
    const layerEnum = effectiveLayer.toUpperCase() as VslfcLayer;
    const agent = await this.agentProvider.createAgent(layerEnum);
    const tabId = conversationId || 'tab-' + Date.now() + '-' + Math.random().toString(36).substr(2, 4);
    const workspaceRoot = vscode.workspace.workspaceFolders?.[0]?.uri.fsPath || '';
    const config = agent.getConfig();

    // Initialize context meter for this tab
    const contextMeter = new ContextMeter();
    contextMeter.configure(
      config.model.provider,
      config.model.id,
      config.model.contextLength
    );

    const tabState: AgentTabState = { 
      tabId, agent, layer: effectiveLayer, 
      history: [], 
      accumulatedToolCalls: [], 
      isActive: true, 
      createdAt: Date.now(), 
      lastActivityAt: Date.now(), 
      lastAutoSaveAt: undefined, 
      workspaceRoot, 
      contextMeter,
      isFirstUserInput: true // Track if this is the first user input after creation
    };
    this.tabs.set(tabId, tabState);
    this.activeTabId = tabId;
    // Use the stable tab/conversation id as the AgentBridge session key, NOT the
    // randomly-generated agent.id. agent.id changes on every resume, which would
    // orphan the proxy's sticky session and prevent restarting an existing session.
    this.currentAgentBridge = new AgentBridge(config, this.outputChannel, this.context.extensionPath, workspaceRoot, this.settingsManager, tabId);
    await this.currentAgentBridge.initialize();
    this.log('Created ' + effectiveLayer + ' agent tab: ' + tabId);
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
    // A resumed conversation is NOT a new chat — the first user input must not
    // trigger forceFreshSession, otherwise the proxy session gets reset and the
    // conversation context the user is resuming is lost.
    if (saved.messages.length > 0) {
      tabState.isFirstUserInput = false;
    }
    this.log('Loaded conversation data with ' + saved.messages.length + ' messages from workspaceRoot=' + tabState.workspaceRoot);
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
   * Estimate token count from messages (rough approximation: 1 token â‰ˆ 4 characters for code)
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
    // Conservative estimate: 1 token â‰ˆ 6 characters for code (includes whitespace, brackets, etc.)
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
    let accumulatedReasoning = '';
    const userMessage: ChatMessage = { role: 'user', content: userInput, timestamp: Date.now() };
    // Capture prior turns before appending the current input. processStreaming
    // injects the live userInput itself, so passing the full history would
    // duplicate the current message (roles=[...,user,user]).
    const priorHistory = [...tabState.history];
    tabState.history.push(userMessage);
    this.sendToWebview({ command: 'user_message', content: userInput, timestamp: Date.now() });
    try {
      tabState.accumulatedToolCalls = [];
      this.cancelTokenSource = new vscode.CancellationTokenSource();
      let responseText = '';
      let hasAgentError = false;
      let startTime = Date.now();
      if (showThinking) this.sendToWebview({ command: 'thinking', message: 'Agent is thinking...', timestamp: Date.now() });
      // Force fresh session for new chats to prevent session reuse
      // Use the first input flag to distinguish truly new chats from continuations
      // Check the flag FIRST before considering history, as webview may add current message to history
      const isFirstUserInput = tabState.isFirstUserInput === true;
      const isNewChat = isFirstUserInput || tabState.history.length === 0;
      const forceFreshSession = isNewChat;
      
      // Clear the flag after first use
      if (tabState.isFirstUserInput === true) {
        tabState.isFirstUserInput = false;
      }
      
      this.log(`Processing user input - ${forceFreshSession ? 'NEW CHAT' : 'continuing'} (history: ${tabState.history.length} messages) from workspaceRoot=${tabState.workspaceRoot}`);
      
      const streamGenerator = this.currentAgentBridge.processStreaming(
        userInput, 
        currentFile,
        priorHistory,  // Pass prior turns only — current input injected by processStreaming
        tabState.sessionState,  // Pass session state
        forceFreshSession  // Force fresh session for new chats
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
            accumulatedReasoning += chunk.reasoning;
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
          case 'token_usage':
            {
              // Update context meter after each LLM response (not just at end)
              const tu = chunk.tokenUsage;
              tabState.contextMeter.recordTokenUsage({ prompt: tu.prompt, completion: tu.completion, total: tu.total });
              tabState.contextMeter.recordLatency(Date.now() - startTime);

              const summary = tabState.contextMeter.getUsageSummary();
              const totalTokens = tu.prompt + tu.completion;
              const contextLength = this.currentAgentBridge.getConfig().model.contextLength;
              const percentage = ((totalTokens / contextLength) * 100).toFixed(1);
              this.log(`Token usage (step): ${totalTokens.toLocaleString()} / ${contextLength.toLocaleString()} (${percentage}%)`);
              if (summary.warnings.length > 0) {
                this.log(`Context warnings: ${summary.warnings.join('; ')}`);
              }

              this.sendToWebview({ command: 'token_usage', tokenUsage: tu, contextLength, timestamp: chunk.timestamp });
              this.sendToWebview({ command: 'context_meter_update', summary, timestamp: chunk.timestamp });
            }
            break;
          case 'done':
            if (chunk.outcome === 'error') {
              // Agent completed with an error — the preceding 'error' chunk handles webview notification
              this.log(`Agent completed with error outcome`);
              hasAgentError = true;
              break;
            }
            if (chunk.tokenUsage) {
              // Fallback: if token_usage wasn't emitted, update on done
              tabState.contextMeter.recordTokenUsage({
                prompt: chunk.tokenUsage.prompt,
                completion: chunk.tokenUsage.completion,
                total: chunk.tokenUsage.total,
              });
              tabState.contextMeter.recordLatency(Date.now() - startTime);

              const summary = tabState.contextMeter.getUsageSummary();
              const totalTokens = chunk.tokenUsage.prompt + chunk.tokenUsage.completion;
              const contextLength = this.currentAgentBridge.getConfig().model.contextLength;
              const percentage = ((totalTokens / contextLength) * 100).toFixed(1);
              this.log(`Token usage (done): ${totalTokens.toLocaleString()} / ${contextLength.toLocaleString()} (${percentage}%) - prompt: ${chunk.tokenUsage.prompt.toLocaleString()}, completion: ${chunk.tokenUsage.completion.toLocaleString()}`);
              if (summary.warnings.length > 0) {
                this.log(`Context warnings: ${summary.warnings.join('; ')}`);
              }

              this.sendToWebview({ command: 'token_usage', tokenUsage: chunk.tokenUsage, contextLength, timestamp: chunk.timestamp });
              this.sendToWebview({ command: 'context_meter_update', summary, timestamp: chunk.timestamp });
            } else {
              this.log('Response complete (no token usage data)');
            }
            break;
          case 'error':
            this.log(`Agent error: ${chunk.error}`);
            vscode.window.showErrorMessage('Agent error: ' + chunk.error);
            this.sendToWebview({ command: 'error', error: chunk.error, timestamp: chunk.timestamp });
            hasAgentError = true;
            break;
          case 'thinking':
            this.sendToWebview({ command: 'thinking', message: chunk.message, timestamp: chunk.timestamp });
            accumulatedReasoning += chunk.message;
            break;
        }
      }
      if (!this.cancelTokenSource?.token.isCancellationRequested && !hasAgentError) {
        const cleanedResponse = this.cleanResponseText(responseText);
        const assistantMessage: ChatMessage = { role: 'assistant', content: cleanedResponse, reasoning: accumulatedReasoning.trim() || undefined, toolCalls: tabState.accumulatedToolCalls.map(tc => ({ toolName: tc.toolName, args: tc.args, result: tc.result })), timestamp: Date.now() };
        tabState.history.push(assistantMessage);
      this.saveActiveTab(); // Persist immediately after each assistant response
        
        // Update session state from agent bridge
        tabState.sessionState = this.currentAgentBridge.getSessionState();
        
        if (settings.agent.autoSaveConversation && this.historyManager) {
        await this.historyManager.save(this.activeTabId!, tabState.history, tabState.layer, tabState.sessionState);
      }
        const durationMs = Date.now() - startTime;
        this.sendToWebview({ command: 'assistant_response', content: cleanedResponse, reasoning: accumulatedReasoning.trim() || undefined, durationMs, timestamp: Date.now() });
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

  /**
   * Dispose the current webview panel (if any) so the next showWebview() creates a
   * brand-new panel. This is required on new chat / resume because reassigning
   * webview.html with identical markup (same provider/model/settings) is treated as
   * a no-op by VS Code, leaving the previous conversation DOM intact.
   */
  private disposeWebview(): void {
    if (this.webviewPanel) {
      this.webviewPanel.dispose();
      this.webviewPanel = null;
    }
  }

  private showWebview(loadConversation: boolean = false): void {
    if (this.webviewPanel) {
      this.webviewPanel.webview.html = this.getWebviewContent();
      this.webviewPanel.reveal(vscode.ViewColumn.One);
      if (loadConversation && this.activeTabId) {
        this.loadedConversationTimeout = setTimeout(() => { if (this.activeTabId) this.sendLoadedConversation(this.activeTabId); }, 500);
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
      this.loadedConversationTimeout = setTimeout(() => { if (this.activeTabId) this.sendLoadedConversation(this.activeTabId); }, 500);
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
        case 'change_thinking': await this.changeThinking(message.enabled); break;
        case 'change_search': await this.changeSearch(message.enabled); break;
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
            // Cancel any pending loaded conversation timeout from old session
            if (this.loadedConversationTimeout) {
              clearTimeout(this.loadedConversationTimeout);
              this.loadedConversationTimeout = null;
            }
            if (this.isProcessing) {
              await this.stopAgent();
            }
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
      if (this.isProcessing) {
        await this.stopAgent();
      }
      if (this.activeTabId) {
        await this.closeTab(this.activeTabId);
      }
      // Create new tab with saved conversation
      await this.resumeConversation(conversationId);
      // Get context title from saved conversation
      const saved = await this.historyManager?.load(conversationId);
      const contextTitle = saved?.contextTitle || 'Untitled';
      // Send loaded conversation after a delay to ensure webview is ready
      this.loadedConversationTimeout = setTimeout(() => {
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
      let defaultModel: string;
      if (provider === 'deepseek') {
        defaultModel = 'deepseek-chat';
      } else if (provider === '3d-llm') {
        defaultModel = 'deepseek-chat';
      } else if (provider === 'mistral') {
        defaultModel = 'mistral-tiny';
      } else {
        defaultModel = 'llama3.2:3b';
      }
      agentConfig.model.id = defaultModel;
      await this.agentProvider.updateConfig(tabState.layer, { provider, model: defaultModel });

      // Reconfigure context meter for new provider
      tabState.contextMeter.configure(provider, defaultModel, agentConfig.model.contextLength);
      this.log('Context meter reconfigured for ' + provider + ' / ' + defaultModel);

      // Reconfigure bridge config and session manager for new provider
      if (this.currentAgentBridge) {
        this.currentAgentBridge.updateConfig({ provider, id: defaultModel });
        this.log('AgentBridge config updated: provider=' + provider + ', model=' + defaultModel);

        const { createSessionManager } = require('./SessionManager');
        const extension = vscode.extensions.getExtension('i2vision') as any;
        const settings = AgentSettingsManager.getInstance(extension?.extensionContext).getSettings();
        
        // Get provider URL based on provider type
        let providerUrl: string | undefined;
        switch (provider) {
          case '3d-llm':
            providerUrl = settings.proxy.baseUrl;
            break;
          case 'mistral':
            providerUrl = settings.mistral.baseUrl;
            break;
          case 'deepseek':
            // DeepSeek URL would come from settings if available
            break;
          case 'ollama':
          default:
            // Ollama uses default local URL
            break;
        }
        
        const newSessionMgr = createSessionManager(provider, providerUrl);
        this.currentAgentBridge.setSessionManager(newSessionMgr);
        this.log('Session manager reconfigured: ' + newSessionMgr.name);
      }

      this.log('Provider changed to ' + provider);
      vscode.window.showInformationMessage('Provider changed to ' + provider);
      this.sendToWebview({ 
        command: 'provider_changed', 
        provider, 
        model: defaultModel,
        thinkingEnabled: agentConfig.model.thinkingEnabled ?? false,
        searchEnabled: agentConfig.model.searchEnabled ?? false
      });
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

  private async changeThinking(enabled: boolean): Promise<void> {
    if (!this.activeTabId) { vscode.window.showErrorMessage('No active agent tab'); return; }
    const tabState = this.tabs.get(this.activeTabId);
    if (!tabState) { vscode.window.showErrorMessage('Active tab not found'); return; }
    this.log('Changing thinking_enabled to ' + enabled + ' for ' + tabState.layer + ' agent...');
    try {
      const agentConfig = this.agentProvider.getConfig(tabState.layer);
      agentConfig.model.thinkingEnabled = enabled;
      await this.agentProvider.updateConfig(tabState.layer, { thinkingEnabled: enabled });
      if (this.currentAgentBridge) {
        this.currentAgentBridge.updateConfig({ thinkingEnabled: enabled });
      }
      this.sendToWebview({ command: 'thinking_changed', enabled });
    } catch (error: any) {
      this.log('Error changing thinking: ' + error.message);
    }
  }

  private async changeSearch(enabled: boolean): Promise<void> {
    if (!this.activeTabId) { vscode.window.showErrorMessage('No active agent tab'); return; }
    const tabState = this.tabs.get(this.activeTabId);
    if (!tabState) { vscode.window.showErrorMessage('Active tab not found'); return; }
    this.log('Changing search_enabled to ' + enabled + ' for ' + tabState.layer + ' agent...');
    try {
      const agentConfig = this.agentProvider.getConfig(tabState.layer);
      agentConfig.model.searchEnabled = enabled;
      await this.agentProvider.updateConfig(tabState.layer, { searchEnabled: enabled });
      if (this.currentAgentBridge) {
        this.currentAgentBridge.updateConfig({ searchEnabled: enabled });
      }
      this.sendToWebview({ command: 'search_changed', enabled });
    } catch (error: any) {
      this.log('Error changing search: ' + error.message);
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
      else if (providerId === '3d-llm') models = await this.fetchThreeDLlmModels();
      else if (providerId === 'mistral') models = ['mistral-tiny', 'mistral-small', 'mistral-medium', 'mistral-large', 'mistral-embed'];
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

  private async fetchThreeDLlmModels(): Promise<string[]> {
    try {
      const extension = vscode.extensions.getExtension('i2vision') as any;
      const settings = AgentSettingsManager.getInstance(extension?.extensionContext).getSettings();
      const url = settings.proxy.baseUrl || 'http://localhost:9655';
      const response = await fetch(`${url}/v1/models`);
      if (!response.ok) {
        this.log(`3D LLM models fetch failed: ${response.status}`);
        return ['deepseek-chat'];
      }
      const data = await response.json() as any;
      if (data.data && Array.isArray(data.data)) {
        const models = data.data.map((m: any) => m.id).filter((id: string) => typeof id === 'string');
        this.log(`Fetched ${models.length} 3D LLM models: ${models.join(', ')}`);
        return models.length > 0 ? models : ['deepseek-chat'];
      }
      return ['deepseek-chat'];
    } catch (error: any) {
      this.log(`3D LLM models fetch error: ${error.message}`);
      return ['deepseek-chat'];
    }
  }

  private sendToWebview(message: any): void {
    if (this.webviewPanel) this.webviewPanel.webview.postMessage(message);
  }

  private getWebviewContent(): string {
    const workspaceRoot = vscode.workspace.workspaceFolders?.[0]?.uri.fsPath || 'Unknown';
    const workspaceName = path.basename(workspaceRoot);

    let currentProvider = 'ollama';
    let currentModel = 'llama3.2:3b';
    let thinkingEnabled = false;
    let searchEnabled = false;
    if (this.activeTabId) {
      const tabState = this.tabs.get(this.activeTabId);
      if (tabState) {
        const config = this.agentProvider.getConfig(tabState.layer);
        currentProvider = config.model.provider;
        currentModel = config.model.id;
        thinkingEnabled = config.model.thinkingEnabled ?? false;
        searchEnabled = config.model.searchEnabled ?? false;
      }
    }
    const settings = this.settingsManager.getSettings();
    const streamingEnabled = settings.streaming.enabled;
    const showThinking = settings.streaming.showThinkingIndicator;
    const selectedProviderOllama = currentProvider === 'ollama' ? 'selected' : '';
    const selectedProviderDeepSeek = currentProvider === 'deepseek' ? 'selected' : '';
    const selectedProvider3DLlm = currentProvider === '3d-llm' ? 'selected' : '';
    const selectedProviderMistral = currentProvider === 'mistral' ? 'selected' : '';

    // Read HTML template from file
    const templatePath = path.join(this.context.extensionPath, 'resources', 'agent-tab.html');
    let html = fs.readFileSync(templatePath, 'utf8');

    // Replace placeholders
    html = html.replace(/{workspaceName}/g, workspaceName);
    html = html.replace(/{workspacePath}/g, workspaceRoot);
    html = html.replace(/{currentProvider}/g, currentProvider);
    html = html.replace(/{currentModel}/g, currentModel);
    html = html.replace(/{selectedProviderOllama}/g, selectedProviderOllama);
    html = html.replace(/{selectedProviderDeepSeek}/g, selectedProviderDeepSeek);
    html = html.replace(/{selectedProvider3DLlm}/g, selectedProvider3DLlm);
    html = html.replace(/{selectedProviderMistral}/g, selectedProviderMistral);
    html = html.replace(/{streamingEnabled}/g, String(streamingEnabled));
    html = html.replace(/{showThinking}/g, String(showThinking));
    html = html.replace(/{thinkingChecked}/g, thinkingEnabled ? 'checked' : '');
    html = html.replace(/{searchChecked}/g, searchEnabled ? 'checked' : '');

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
    await this.enforceHistoryLimit();
  }
}
