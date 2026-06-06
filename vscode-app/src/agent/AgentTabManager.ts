/**
 * AgentTabManager - Creates and manages agent tabs
 *
 * UPDATED: Added real-time progress streaming for tool calls
 * UPDATED: Added stop/cancel control with combined send/stop button
 * UPDATED: Display tool results in real-time
 * UPDATED: Configurable tool card display with formatting, truncation, folding
 * FIXED: Added error handling in webview to prevent tool card errors from breaking input
 */

import * as vscode from 'vscode';
import * as path from 'path';
import * as crypto from 'crypto';
import { LocalAgentProvider } from './LocalAgentProvider';
import { LocalI2VisionAgent, VslfcLayer, AgentContext } from './LocalI2VisionAgent';
import { AgentConfig, InteractionRecord, ToolCall, ProgressEvent } from './AgentBridge';
import { ToolCardManager } from './ToolCardManager';
import { FormattedToolCard } from './ToolCardConfig';

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
  private toolCardManager: ToolCardManager;

  constructor(
    private context: vscode.ExtensionContext,
    outputChannel: vscode.OutputChannel
  ) {
    this.outputChannel = outputChannel;

    // Create the local agent provider
    this.provider = new LocalAgentProvider(context, outputChannel);

    // Create the tool card manager for configurable tool display
    this.toolCardManager = new ToolCardManager(outputChannel);

    this.log('AgentTabManager initialized with LocalAgentProvider and ToolCardManager');
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
    this.toolCardManager.reloadConfig();
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
      case 'changeProvider':
        await this.changeProvider(tab, message.provider);
        break;
      case 'changeModel':
        await this.changeModel(tab, message.model);
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
   * Clean up agent response text (strip reasoning headers, EOS markers, etc.)
   */
  private cleanResponseText(text: string): string {
    if (!text) return '';
    
    // Strip reasoning: header (case-insensitive)
    text = text.replace(/^reasoning:\s*/gmi, '');
    
    // Strip EOS marker
    text = text.replace(/\bEOS\b/g, '');
    
    // Strip tool_calls: header if present
    text = text.replace(/^tool_calls:\s*/gmi, '');
    
    // Clean up extra whitespace
    text = text.trim();
    
    return text;
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

      // Use streaming for better UX - shows "Hi!" instantly
      this.log(`=== Using streaming mode ===`);
      
      const AGENT_TIMEOUT_MS = 60000; // 60 second timeout
      const requestId = `request-${Date.now()}`;
      
      let accumulatedText = '';
      let toolCalls: any[] = [];
      let iterations = 1;

      const agentPromise = (async () => {
        // Stream the response
        for await (const chunk of tab.agent.processStreaming({
          id: requestId,
          task: userInput,
          context: context
        })) {
          // Handle different chunk types
          if (chunk.type === 'text') {
            // Clean chunk text before streaming (strip reasoning:, EOS, etc.)
            const cleanChunkText = this.cleanResponseText(chunk.text);
            if (cleanChunkText) {
              accumulatedText += cleanChunkText;
              // Stream to webview immediately for better UX
              tab.panel.webview.postMessage({
                command: 'streamingText',
                text: cleanChunkText,
                accumulated: accumulatedText
              });
            }
          } else if (chunk.type === 'tool_call_started') {
            this.log(`Tool call started: ${chunk.toolName}`);
          } else if (chunk.type === 'tool_call_completed') {
            toolCalls.push({
              toolName: chunk.toolName,
              args: chunk.args,
              result: chunk.result
            });
          } else if (chunk.type === 'done') {
            iterations = 1; // Streaming doesn't track iterations yet
          }
        }
        
        // Return final response
        return {
          finalText: accumulatedText,
          toolCalls: toolCalls,
          iterations: iterations,
          durationMs: Date.now() - startTime,
          success: true
        };
      })();

      const timeoutPromise = new Promise<never>((_, reject) => {
        setTimeout(() => {
          reject(new Error(`agent.processStreaming() timed out after ${AGENT_TIMEOUT_MS}ms`));
        }, AGENT_TIMEOUT_MS);
      });

      const response = await Promise.race([agentPromise, timeoutPromise]);

      this.log(`=== DIAGNOSTIC: agent.processStreaming() returned ===`);
      this.log(`Response finalText length: ${response.finalText?.length || 0}`);
      this.log(`Response toolCalls count: ${response.toolCalls?.length || 0}`);
      this.log(`Response success: ${response.success}`);

      // Clean up response text (strip reasoning:, EOS, etc.)
      const cleanedText = this.cleanResponseText(response.finalText || '');

      // Format the response through ToolCardManager
      const formattedResponse = this.toolCardManager.formatAgentResponse(
        cleanedText,
        response.toolCalls || [],
        response.iterations,
        response.durationMs,
        response.success
      );

      // Record the interaction
      const record: InteractionRecord = {
        timestamp: Date.now(),
        userInput,
        agentResponse: cleanedText,
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

      // Update webview with final formatted response
      this.log(`=== DIAGNOSTIC: About to postMessage to webview ===`);
      tab.panel.webview.postMessage({
        command: 'response',
        response: formattedResponse
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
      vscode.window.showErrorMessage(`Could not open config file: ${configPath}`);
    }
  }

  /**
   * Reload the agent with fresh configuration
   */
  private async reloadAgent(tab: AgentTab) {
    this.log(`=== Reloading agent configuration for ${tab.layer}... ===`);

    try {
      // Recreate the agent with fresh config (provider.createAgent clears cache internally)
      const vslfcLayer = VslfcLayer[tab.layer.toUpperCase() as keyof typeof VslfcLayer];
      this.log(`Creating new agent for layer: ${vslfcLayer}`);
      const newAgent = await this.provider.createAgent(vslfcLayer);

      // Update the tab with new agent
      tab.agent = newAgent;
      this.log(`New agent created: ${newAgent.id}`);

      // Get fresh config to send to webview
      const agentConfig = this.provider.getConfig(tab.layer);
      const modelId = agentConfig.model.id;
      const providerId = agentConfig.model.provider;
      const maxIterations = agentConfig.iterationSettings.maxIterations;

      this.log(`Loaded config: provider=${providerId}, model=${modelId}, maxIterations=${maxIterations}`);

      // Update webview HTML with fresh config
      this.updateWebview(tab);
      this.log(`Webview HTML updated`);

      // Notify webview to update UI with new config (NO reload needed)
      tab.panel.webview.postMessage({
        command: 'configUpdated',
        provider: providerId,
        model: modelId,
        maxIterations: maxIterations
      });
      this.log(`Config update message sent to webview`);

      this.log(`✅ Agent reloaded successfully`);
    } catch (error: any) {
      this.log(`❌ Error reloading agent: ${error.message}`);
      this.log(`Stack: ${error.stack}`);
      vscode.window.showErrorMessage(`Failed to reload agent: ${error.message}`);
      throw error;
    }
  }

  /**
   * Change the LLM provider for this agent
   */
  private async changeProvider(tab: AgentTab, provider: string) {
    this.log(`=== Changing provider to ${provider} for ${tab.layer} agent... ===`);

    try {
      // Clear cache FIRST to ensure fresh load
      this.provider.clearConfigCache();
      this.log(`Config cache cleared`);
      
      // Get current config (will be defaults after cache clear)
      const agentConfig = this.provider.getConfig(tab.layer);
      
      // Update provider in config
      agentConfig.model.provider = provider;
      
      // Set default model for the provider
      const defaultModel = provider === 'deepseek' ? 'deepseek-chat' : 'llama3.2:3b';
      agentConfig.model.id = defaultModel;
      
      this.log(`Config updated: provider=${provider}, model=${defaultModel}`);
      
      // Save config to YAML file
      await this.saveAgentConfig(tab.layer, agentConfig);
      this.log(`Config saved to YAML`);
      
      // Small delay to ensure file is written
      await new Promise(resolve => setTimeout(resolve, 100));
      
      // Reload agent with new config (will load from YAML)
      await this.reloadAgent(tab);
      this.log(`Agent reloaded`);
      
      // Notify webview of successful update
      tab.panel.webview.postMessage({
        command: 'configUpdated',
        provider: provider,
        model: defaultModel
      });
      
      this.log(`✅ Provider changed to ${provider}, model set to ${defaultModel}`);
    } catch (error: any) {
      this.log(`❌ Error changing provider: ${error.message}`);
      this.log(`Stack: ${error.stack}`);
      vscode.window.showErrorMessage(`Failed to change provider: ${error.message}`);
    }
  }

  /**
   * Change the LLM model for this agent
   */
  private async changeModel(tab: AgentTab, model: string) {
    this.log(`Changing model to ${model} for ${tab.layer} agent...`);

    try {
      // Get current config
      const agentConfig = this.provider.getConfig(tab.layer);
      
      // Update model in config
      agentConfig.model.id = model;
      
      // Save config to YAML file
      await this.saveAgentConfig(tab.layer, agentConfig);
      
      // Reload agent with new config
      await this.reloadAgent(tab);
      
      // Notify webview of successful update
      tab.panel.webview.postMessage({
        command: 'configUpdated',
        provider: agentConfig.model.provider,
        model: model
      });
      
      this.log(`Model changed to ${model}`);
    } catch (error: any) {
      this.log(`Error changing model: ${error.message}`);
      vscode.window.showErrorMessage(`Failed to change model: ${error.message}`);
    }
  }

  /**
   * Save agent configuration to YAML file
   */
  private async saveAgentConfig(layer: string, config: AgentConfig): Promise<void> {
    const fs = require('fs');
    const path = require('path');
    const yaml = require('js-yaml');
    
    const layerName = layer.toLowerCase();
    const configPath = path.join(
      vscode.workspace.workspaceFolders?.[0]?.uri.fsPath || '',
      '.vision-ai',
      `${layerName}-agent.yaml`
    );
    
    // Convert AgentConfig to YAML format
    const yamlConfig: any = {
      key: config.key,
      agentType: config.agentType,
      version: config.version,
      isActive: config.isActive,
      systemPromptTemplate: config.systemPromptTemplate,
      templateVariables: config.templateVariables,
      model: {
        id: config.model.id,
        provider: config.model.provider,
        contextLength: config.model.contextLength,
        maxOutputTokens: config.model.maxOutputTokens,
        temperature: config.model.temperature,
        topP: config.model.topP
      },
      llm: config.llm,
      formattingRules: config.formattingRules,
      iterationSettings: config.iterationSettings,
      toolSelection: config.toolSelection,
      safety: config.safety,
      parsing: config.parsing,
      discovery: config.discovery,
      execution: config.execution,
      formatting: config.formatting,
      streaming: config.streaming,
      mcp: config.mcp
    };
    
    // Ensure .vision-ai directory exists
    const visionAiDir = path.dirname(configPath);
    await fs.promises.mkdir(visionAiDir, { recursive: true });
    
    // Write YAML file
    const yamlContent = yaml.dump(yamlConfig, {
      lineWidth: -1, // Don't wrap lines
      noRefs: true,  // Don't use anchors/aliases
      quotingType: '"',
      forceQuotes: false
    });
    
    await fs.promises.writeFile(configPath, yamlContent, 'utf8');
    this.log(`Saved config to ${configPath}`);
  }

  /**
   * Close an agent tab
   */
  private async closeTab(tabId: string): Promise<void> {
    const tab = this.tabs.get(tabId);
    if (tab) {
      this.log(`Closing tab: ${tabId}`);

      // Cancel any running agent
      if (tab.cancelToken) {
        tab.cancelToken.cancel();
        tab.cancelToken.dispose();
      }

      // Dispose the panel
      tab.panel.dispose();

      // Remove from map
      this.tabs.delete(tabId);

      this.log(`Tab closed: ${tabId}`);
    }
  }

  /**
   * Update webview content
   */
  private updateWebview(tab: AgentTab): void {
    // Get agent config to display model info
    const agentConfig = this.provider.getConfig(tab.layer);
    const modelId = agentConfig.model.id;
    const providerId = agentConfig.model.provider;
    const maxIterations = agentConfig.iterationSettings.maxIterations;

    tab.panel.webview.html = this.getWebviewContent(modelId, providerId, maxIterations);
  }

  /**
   * Generate a nonce for Content Security Policy
   */
  private getNonce(): string {
    return crypto.randomBytes(16).toString('base64');
  }

  /**
   * Get webview HTML content with configurable tool card display
   */
  private getWebviewContent(modelId: string, providerId: string, maxIterations: number): string {
    return `<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline';">
    <title>i2-Vision Agent</title>
    <style>
        :root {
            --container-padding: 20px;
        }

        body {
            font-family: var(--vscode-font-family);
            padding: var(--container-padding);
            color: var(--vscode-foreground);
            background-color: var(--vscode-editor-background);
            margin: 0;
        }

        #messages {
            min-height: 300px;
            max-height: 60vh;
            overflow-y: auto;
            margin-bottom: 16px;
            padding: 8px;
            border: 1px solid var(--vscode-panel-border, #ccc);
            border-radius: 4px;
            background: var(--vscode-editor-background);
        }

        .message {
            margin-bottom: 12px;
            padding: 8px 12px;
            border-radius: 4px;
            line-height: 1.5;
        }

        .user-message {
            background: var(--vscode-button-background);
            color: var(--vscode-button-foreground);
            margin-left: 20%;
        }

        .agent-message {
            background: var(--vscode-editor-inactiveSelectionBackground);
            margin-right: 20%;
        }

        .config-info {
            font-size: 12px;
            color: var(--vscode-descriptionForeground);
            margin-bottom: 12px;
            padding: 8px;
            background: var(--vscode-editor-inactiveSelectionBackground);
            border-radius: 4px;
        }

        .tool-call-message {
            font-family: var(--vscode-editor-font-family);
            font-size: 12px;
            padding: 4px 8px;
            margin: 4px 0;
            background: var(--vscode-editor-inactiveSelectionBackground);
            border-radius: 3px;
        }

        .tool-call-complete {
            border-left: 3px solid var(--vscode-terminal-ansiGreen);
        }

        .tool-call-error {
            border-left: 3px solid var(--vscode-errorForeground);
        }

        .tool-result {
            font-family: var(--vscode-editor-font-family);
            font-size: 11px;
            color: var(--vscode-descriptionForeground);
            margin: 4px 0;
            padding: 4px 8px;
            background: var(--vscode-editor-inactiveSelectionBackground);
            border-radius: 3px;
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
            position: sticky;
            bottom: 0;
            background: var(--vscode-editor-background);
            padding-top: 8px;
        }

        #userInput {
            flex: 1;
            padding: 8px 12px;
            border: 1px solid var(--vscode-input-border, var(--vscode-panel-border, #ccc));
            border-radius: 4px;
            background: var(--vscode-input-background);
            color: var(--vscode-input-foreground);
            font-family: var(--vscode-font-family);
        }

        #userInput:focus {
            outline: 2px solid var(--vscode-focusBorder);
        }

        #userInput:disabled {
            opacity: 0.6;
            cursor: not-allowed;
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

        /* ===== TOOL CARD STYLES ===== */
        .tool-card {
            margin: 8px 0;
            border: 1px solid var(--vscode-panel-border, #ccc);
            border-radius: 4px;
            overflow: hidden;
            background: var(--vscode-editor-inactiveSelectionBackground);
        }

        .tool-card-header {
            display: flex;
            align-items: center;
            gap: 8px;
            padding: 8px 12px;
            cursor: pointer;
            user-select: none;
            background: var(--vscode-editor-background);
            border-bottom: 1px solid var(--vscode-panel-border, #ccc);
        }

        .tool-card-header:hover {
            background: var(--vscode-list-hoverBackground);
        }

        .tool-card-header.success {
            border-left: 4px solid var(--vscode-terminal-ansiGreen);
        }

        .tool-card-header.error {
            border-left: 4px solid var(--vscode-errorForeground);
        }

        .tool-card-header.pending {
            border-left: 4px solid var(--vscode-terminal-ansiYellow);
        }

        .tool-card-toggle {
            font-size: 10px;
            transition: transform 0.2s;
            width: 12px;
            text-align: center;
        }

        .tool-card-toggle.collapsed {
            transform: rotate(-90deg);
        }

        .tool-card-icon {
            font-size: 14px;
        }

        .tool-card-name {
            font-weight: 600;
            flex: 1;
        }

        .tool-card-meta {
            display: flex;
            gap: 12px;
            font-size: 11px;
            color: var(--vscode-descriptionForeground);
        }

        .tool-card-duration {
            font-family: var(--vscode-editor-font-family);
        }

        .tool-card-truncated {
            color: var(--vscode-terminal-ansiYellow);
        }

        .tool-card-body {
            padding: 12px;
            transition: max-height 0.3s ease-out;
            max-height: 1000px;
            overflow: hidden;
        }

        .tool-card-body.collapsed {
            max-height: 0;
            padding: 0 12px;
        }

        .tool-card-args {
            font-family: var(--vscode-editor-font-family);
            font-size: 11px;
            color: var(--vscode-descriptionForeground);
            margin-bottom: 8px;
            padding: 4px 8px;
            background: var(--vscode-editor-background);
            border-radius: 3px;
        }

        .tool-card-args-label {
            font-weight: 600;
            color: var(--vscode-foreground);
        }

        .tool-card-result {
            font-family: var(--vscode-editor-font-family);
            font-size: 11px;
            white-space: pre-wrap;
            word-break: break-word;
            padding: 8px;
            background: var(--vscode-editor-background);
            border-radius: 3px;
            max-height: 400px;
            overflow-y: auto;
        }

        .tool-card-result.tree-format {
            color: var(--vscode-terminal-ansiGreen);
        }

        .tool-card-result.table-format {
            color: var(--vscode-terminal-ansiCyan);
        }

        .tool-card-result.markdown-format {
            color: var(--vscode-terminal-ansiBlue);
        }

        .line-number {
            display: inline-block;
            width: 30px;
            color: var(--vscode-descriptionForeground);
            text-align: right;
            margin-right: 8px;
            user-select: none;
        }

        optgroup {
            font-weight: 600;
            color: var(--vscode-foreground);
        }

        optgroup[label*="Local"] {
            color: var(--vscode-terminal-ansiGreen);
        }

        optgroup[label*="Cloud"] {
            color: var(--vscode-terminal-ansiBlue);
        }
    </style>
</head>
<body>
    <div class="config-info">
        <div style="display: flex; gap: 16px; align-items: center; flex-wrap: wrap;">
            <div style="display: flex; align-items: center; gap: 8px;">
                <label for="providerSelect" style="font-weight: 600;">Provider:</label>
                <select id="providerSelect" onchange="onProviderChange()" style="padding: 4px 8px; border: 1px solid var(--vscode-input-border); border-radius: 4px; background: var(--vscode-input-background); color: var(--vscode-input-foreground);">
                    <option value="ollama" ${providerId === 'ollama' ? 'selected' : ''}>Ollama (Local + Cloud)</option>
                    <option value="deepseek" ${providerId === 'deepseek' ? 'selected' : ''}>DeepSeek Direct (Cloud)</option>
                </select>
            </div>
            <div style="display: flex; align-items: center; gap: 8px;">
                <label for="modelSelect" style="font-weight: 600;">Model:</label>
                <select id="modelSelect" onchange="onModelChange()" style="padding: 4px 8px; border: 1px solid var(--vscode-input-border); border-radius: 4px; background: var(--vscode-input-background); color: var(--vscode-input-foreground); min-width: 200px;">
                    <!-- Options populated dynamically based on provider -->
                </select>
            </div>
            <div style="display: flex; align-items: center; gap: 8px; margin-left: auto;">
                <label style="font-weight: 600;">Max Iterations:</label>
                <span style="font-family: var(--vscode-editor-font-family);">${maxIterations}</span>
            </div>
        </div>
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
        const providerSelect = document.getElementById('providerSelect');
        const modelSelect = document.getElementById('modelSelect');

        let currentProgressDiv = null;
        let isProcessing = false;

        // Available models per provider
        // Ollama provides BOTH local and cloud models through the same API
        const MODELS_BY_PROVIDER = {
            'ollama': [
                // Local models (run on your machine)
                { id: 'gemma3:1b', name: '⚠️ Gemma 3 1B (Local) - Too small for chat', type: 'local', quality: 'poor' },
                { id: 'qwen2.5-coder:0.5b-instruct', name: '⚠️ Qwen 2.5 Coder 0.5B (Local) - Too small for chat', type: 'local', quality: 'poor' },
                { id: 'llama3.2:3b', name: '✅ Llama 3.2 3B (Local) - Recommended for chat', type: 'local', quality: 'good' },
                { id: 'llama3.2:7b', name: '✅ Llama 3.2 7B (Local) - Good balance', type: 'local', quality: 'good' },
                { id: 'llama3.1:8b', name: '✅ Llama 3.1 8B (Local) - Good general purpose', type: 'local', quality: 'good' },
                { id: 'qwen3:4b', name: '✅ Qwen 3 4B (Local) - Good for code', type: 'local', quality: 'good' },
                { id: 'codellama:7b', name: '✅ CodeLlama 7B (Local) - Code specialist', type: 'local', quality: 'good' },
                { id: 'codellama:13b', name: '✅ CodeLlama 13B (Local) - Advanced coding', type: 'local', quality: 'excellent' },
                { id: 'mistral:7b', name: '✅ Mistral 7B (Local) - Good general purpose', type: 'local', quality: 'good' },
                { id: 'qwen2.5:7b', name: '✅ Qwen 2.5 7B (Local) - Multilingual', type: 'local', quality: 'good' },
                { id: 'llama2:7b', name: '⚠️ Llama 2 7B (Local) - Legacy model', type: 'local', quality: 'fair' },
                
                // Cloud models (via Ollama Cloud API)
                { id: 'minimax-m2.1:cloud', name: '✅ MiniMax M2.1 (Cloud) - Multilingual code', type: 'cloud', quality: 'excellent' },
                { id: 'mistral-large-3:675b-cloud', name: '✅ Mistral Large 3 675B (Cloud) - Enterprise', type: 'cloud', quality: 'excellent' },
                { id: 'gemma4:31b-cloud', name: '✅ Gemma 4 31B (Cloud) - Multimodal', type: 'cloud', quality: 'excellent' },
                { id: 'deepseek-v3.1:671b-cloud', name: '✅ DeepSeek V3.1 671B (Cloud) - Advanced reasoning', type: 'cloud', quality: 'excellent' },
                { id: 'qwen3-coder:480b-cloud', name: '✅ Qwen 3 Coder 480B (Cloud) - Expert coding', type: 'cloud', quality: 'excellent' },
                { id: 'qwen3.5:cloud', name: '✅ Qwen 3.5 (Cloud) - Best balance', type: 'cloud', quality: 'excellent' },
                { id: 'glm-5.1:cloud', name: '✅ GLM 5.1 (Cloud) - Agentic tasks', type: 'cloud', quality: 'excellent' },
                { id: 'glm-4.7:cloud', name: '✅ GLM 4.7 (Cloud) - Engineering', type: 'cloud', quality: 'excellent' },
                { id: 'glm-4.6:cloud', name: '✅ GLM 4.6 (Cloud) - Engineering', type: 'cloud', quality: 'excellent' },
                { id: 'kimi-k2.6:cloud', name: '✅ Kimi K2.6 (Cloud) - Long context', type: 'cloud', quality: 'excellent' },
                { id: 'nemotron-3-super:cloud', name: '✅ Nemotron 3 Super (Cloud) - Multi-agent', type: 'cloud', quality: 'excellent' },
                { id: 'gpt-oss:20b-cloud', name: '✅ GPT-OSS 20B (Cloud) - Open alternative', type: 'cloud', quality: 'good' }
            ],
            'deepseek': [
                { id: 'deepseek-chat', name: '✅ DeepSeek Chat (V3) - General purpose', type: 'cloud', quality: 'excellent' },
                { id: 'deepseek-coder', name: '✅ DeepSeek Coder - Code specialist', type: 'cloud', quality: 'excellent' },
                { id: 'deepseek-reasoner', name: '✅ DeepSeek Reasoner (R1) - Complex reasoning', type: 'cloud', quality: 'excellent' }
            ]
        };

        // Initialize model dropdown based on current provider
        function initializeModelDropdown() {
            const currentProvider = providerSelect.value;
            const models = MODELS_BY_PROVIDER[currentProvider] || [];
            
            modelSelect.innerHTML = '';
            
            // Group models by type (local/cloud) and quality
            const localModels = models.filter(m => m.type === 'local');
            const cloudModels = models.filter(m => m.type === 'cloud');
            
            // Add local models
            if (localModels.length > 0) {
                const localOptgroup = document.createElement('optgroup');
                localOptgroup.label = 'Local Models (Run on your machine)';
                localModels.forEach(model => {
                    const option = document.createElement('option');
                    option.value = model.id;
                    option.textContent = model.name;
                    // Color-code by quality
                    if (model.quality === 'poor') {
                        option.style.color = '#e74c3c'; // Red warning
                    } else if (model.quality === 'fair') {
                        option.style.color = '#f39c12'; // Orange caution
                    } else if (model.quality === 'excellent') {
                        option.style.color = '#27ae60'; // Green recommended
                    }
                    if (model.id === '${modelId}') {
                        option.selected = true;
                    }
                    localOptgroup.appendChild(option);
                });
                modelSelect.appendChild(localOptgroup);
            }
            
            // Add cloud models
            if (cloudModels.length > 0) {
                const cloudOptgroup = document.createElement('optgroup');
                cloudOptgroup.label = 'Cloud Models (Via Ollama Cloud API)';
                cloudModels.forEach(model => {
                    const option = document.createElement('option');
                    option.value = model.id;
                    option.textContent = model.name;
                    // Color-code by quality
                    if (model.quality === 'excellent') {
                        option.style.color = '#27ae60'; // Green recommended
                    }
                    if (model.id === '${modelId}') {
                        option.selected = true;
                    }
                    cloudOptgroup.appendChild(option);
                });
                modelSelect.appendChild(cloudOptgroup);
            }
        }

        // Handle provider change
        function onProviderChange() {
            const newProvider = providerSelect.value;
            vscode.postMessage({ 
                command: 'changeProvider', 
                provider: newProvider 
            });
            
            // Update model dropdown
            initializeModelDropdown();
        }

        // Handle model change
        function onModelChange() {
            const newModel = modelSelect.value;
            
            // Warn if selecting a poor quality model
            const allModels = MODELS_BY_PROVIDER['ollama'].concat(MODELS_BY_PROVIDER['deepseek']);
            const selectedModel = allModels.find(m => m.id === newModel);
            if (selectedModel && selectedModel.quality === 'poor') {
                if (!confirm('⚠️ Warning: This model is too small for natural conversation. It may output raw JSON instead of friendly responses. Continue anyway?')) {
                    // Revert to a good model
                    const goodModel = allModels.find(m => m.quality === 'good' || m.quality === 'excellent');
                    if (goodModel) {
                        modelSelect.value = goodModel.id;
                    }
                    return;
                }
            }
            
            vscode.postMessage({ 
                command: 'changeModel', 
                model: newModel 
            });
        }

        // Initialize on load
        initializeModelDropdown();

        // ===== ERROR HANDLING =====
        window.addEventListener('error', (event) => {
            console.error('WebView JavaScript error:', event.error);
            // Ensure input is re-enabled even if there's an error
            isProcessing = false;
            userInput.disabled = false;
            updateActionButton();
            userInput.focus();
            addMessage('agent', '<strong style="color: var(--vscode-errorForeground)">⚠️ A JavaScript error occurred. Input has been re-enabled.</strong>', true);
        });

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
            try {
                const text = userInput.value.trim();
                if (!text) return;

                isProcessing = true;
                userInput.disabled = true;
                updateActionButton();

                addMessage('user', text);

                if (currentProgressDiv) {
                    currentProgressDiv.remove();
                    currentProgressDiv = null;
                }

                vscode.postMessage({ command: 'sendMessage', text });
                userInput.value = '';
            } catch (error) {
                console.error('Error in sendMessage:', error);
                isProcessing = false;
                userInput.disabled = false;
                updateActionButton();
            }
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

        // ===== FORMATTED TOOL CARD FUNCTIONS =====

        function createToolCard(toolCard) {
            try {
                const card = document.createElement('div');
                card.className = 'tool-card';
                card.id = 'tool-card-' + toolCard.toolName + '-' + Date.now();

                const headerState = toolCard.foldState === 'collapsed' ? 'collapsed' : '';
                const bodyState = toolCard.foldState === 'collapsed' ? 'collapsed' : '';
                const successState = toolCard.success ? 'success' : 'error';

                let headerHtml = '<div class="tool-card-header ' + successState + '" onclick="toggleToolCard(this)">';
                headerHtml += '<span class="tool-card-toggle ' + headerState + '">&#9662;</span>';
                headerHtml += '<span class="tool-card-icon">&#9881;</span>';
                headerHtml += '<span class="tool-card-name">' + escapeHtml(toolCard.toolName) + '</span>';

                headerHtml += '<span class="tool-card-meta">';
                if (toolCard.durationMs !== undefined) {
                    headerHtml += '<span class="tool-card-duration">' + toolCard.durationMs + 'ms</span>';
                }
                if (toolCard.isTruncated) {
                    headerHtml += '<span class="tool-card-truncated">&#9888; truncated</span>';
                }
                headerHtml += '</span></div>';

                let bodyHtml = '<div class="tool-card-body ' + bodyState + '">';

                // Args
                if (toolCard.args && Object.keys(toolCard.args).length > 0) {
                    bodyHtml += '<div class="tool-card-args">';
                    bodyHtml += '<span class="tool-card-args-label">Args:</span> ';
                    const argPairs = [];
                    for (const [key, value] of Object.entries(toolCard.args)) {
                        argPairs.push(key + '=' + JSON.stringify(value));
                    }
                    bodyHtml += escapeHtml(argPairs.join(', '));
                    bodyHtml += '</div>';
                }

                // Result
                let resultClass = 'tool-card-result';
                if (toolCard.format === 'tree') resultClass += ' tree-format';
                if (toolCard.format === 'table') resultClass += ' table-format';
                if (toolCard.format === 'markdown') resultClass += ' markdown-format';

                bodyHtml += '<div class="' + resultClass + '">';

                if (toolCard.showLineNumbers) {
                    const lines = (toolCard.result || '').split('\\n');
                    const numberedLines = lines.map((line, i) => {
                        return '<span class="line-number">' + (i + 1) + '</span>' + escapeHtml(line);
                    });
                    bodyHtml += numberedLines.join('\\n');
                } else {
                    bodyHtml += escapeHtml(toolCard.result || '');
                }

                bodyHtml += '</div>';
                bodyHtml += '</div>';

                card.innerHTML = headerHtml + bodyHtml;
                return card;
            } catch (error) {
                console.error('Error creating tool card:', error, toolCard);
                // Return a simple error card instead of breaking
                const errorCard = document.createElement('div');
                errorCard.className = 'tool-card';
                errorCard.innerHTML = '<div class="tool-card-header error"><span class="tool-card-name">⚠️ Error rendering tool card</span></div><div class="tool-card-body"><div class="tool-card-result">' + escapeHtml(error.message) + '</div></div>';
                return errorCard;
            }
        }

        function toggleToolCard(header) {
            try {
                const toggle = header.querySelector('.tool-card-toggle');
                const body = header.nextElementSibling;

                if (body.classList.contains('collapsed')) {
                    body.classList.remove('collapsed');
                    toggle.classList.remove('collapsed');
                } else {
                    body.classList.add('collapsed');
                    toggle.classList.add('collapsed');
                }
            } catch (error) {
                console.error('Error toggling tool card:', error);
            }
        }

        function addToolCardToChat(toolCard) {
            try {
                const card = createToolCard(toolCard);
                messagesDiv.appendChild(card);
                card.scrollIntoView({ behavior: 'smooth' });
            } catch (error) {
                console.error('Error adding tool card to chat:', error);
                addMessage('agent', '<span style="color: var(--vscode-errorForeground)">⚠️ Error displaying tool card: ' + escapeHtml(error.message) + '</span>', true);
            }
        }

        function escapeHtml(text) {
            if (!text) return '';
            const div = document.createElement('div');
            div.textContent = text;
            return div.innerHTML;
        }

        // ===== LEGACY TOOL CALL FUNCTIONS (for backward compatibility) =====

        function addToolCallMessage(toolCall, type) {
            try {
                const div = document.createElement('div');
                div.className = 'tool-call-message ' + (type === 'complete' ? 'tool-call-complete' : (toolCall.error ? 'tool-call-error' : ''));

                let content = '<strong>' + toolCall.toolName + '</strong>(';
                for (const [key, value] of Object.entries(toolCall.args || {})) {
                    content += key + ': ' + JSON.stringify(value) + ', ';
                }
                content = content.replace(/, $/, '') + ')';

                if (type === 'complete') {
                    content += ' ✅ <em>Completed</em>';
                }
                if (toolCall.error) {
                    content += ' ❌ <strong style="color: var(--vscode-errorForeground)">Error: ' + toolCall.error + '</strong>';
                }

                div.innerHTML = content;
                messagesDiv.appendChild(div);
                div.scrollIntoView({ behavior: 'smooth' });
            } catch (error) {
                console.error('Error adding tool call message:', error);
            }
        }

        function addToolResult(toolName, result) {
            try {
                if (!result || result.trim() === '') return;

                const div = document.createElement('div');
                div.className = 'tool-result';

                let displayResult = result;
                if (result.length > 500) {
                    displayResult = result.substring(0, 500) + '... (truncated)';
                }

                div.textContent = '📃 ' + toolName + ' result: ' + displayResult;
                messagesDiv.appendChild(div);
                div.scrollIntoView({ behavior: 'smooth' });
            } catch (error) {
                console.error('Error adding tool result:', error);
            }
        }

        function showProgress(message) {
            try {
                if (currentProgressDiv) {
                    currentProgressDiv.remove();
                }

                currentProgressDiv = document.createElement('div');
                currentProgressDiv.className = 'progress-indicator';
                currentProgressDiv.innerHTML = '<div class="spinner"></div><span>' + message + '</span>';
                messagesDiv.appendChild(currentProgressDiv);
                currentProgressDiv.scrollIntoView({ behavior: 'smooth' });
            } catch (error) {
                console.error('Error showing progress:', error);
            }
        }

        function hideProgress() {
            try {
                if (currentProgressDiv) {
                    currentProgressDiv.remove();
                    currentProgressDiv = null;
                }
            } catch (error) {
                console.error('Error hiding progress:', error);
            }
        }

        // Handle messages from extension
        window.addEventListener('message', event => {
            const message = event.data;

            try {
                switch (message.command) {
                    case 'processing':
                        showProgress('Processing: ' + message.userInput.substring(0, 50) + '...');
                        break;

                    case 'streamingText':
                        // Real-time streaming - show text as it arrives
                        hideProgress();
                        
                        // If we have an existing agent message, update it
                        const lastMessage = messagesDiv.lastElementChild;
                        if (lastMessage && lastMessage.classList.contains('agent-message')) {
                            // Update existing message
                            lastMessage.innerHTML = message.accumulated.replace(/\\n/g, '<br>');
                        } else {
                            // Create new message
                            addMessage('agent', message.accumulated.replace(/\\n/g, '<br>'), true);
                        }
                        break;

                    case 'progress':
                        const evt = message.event;
                        if (evt.type === 'thinking') {
                            showProgress(evt.message);
                        } else if (evt.type === 'tool_start') {
                            if (evt.toolCall) {
                                addToolCallMessage(evt.toolCall, 'start');
                            }
                        } else if (evt.type === 'tool_complete') {
                            if (evt.toolCard) {
                                // New formatted tool card
                                addToolCardToChat(evt.toolCard);
                            } else if (evt.toolCall) {
                                // Legacy fallback
                                addToolCallMessage(evt.toolCall, 'complete');
                                if (evt.toolCall.result) {
                                    addToolResult(evt.toolCall.toolName, evt.toolCall.result);
                                }
                            }
                        } else if (evt.type === 'iteration_complete') {
                            // Optional: show iteration complete message
                        }
                        break;

                    case 'configUpdated':
                        // Configuration updated successfully - update dropdowns
                        if (message.provider) {
                            providerSelect.value = message.provider;
                        }
                        if (message.model) {
                            // Re-initialize model dropdown to get correct options
                            initializeModelDropdown();
                            // Set the selected model
                            modelSelect.value = message.model;
                        }
                        showProgress('Configuration updated: ' + (message.provider || '') + ' / ' + (message.model || ''));
                        setTimeout(hideProgress, 2000);
                        break;

                    case 'response':
                        hideProgress();

                        // If streaming already showed text, just update with final stats
                        const lastMsg = messagesDiv.lastElementChild;
                        if (lastMsg && lastMsg.classList.contains('agent-message')) {
                            // Add iteration info to existing message
                            let statsHtml = '<div class="iteration-info">Iterations: ' + message.response.iterations + ' | Duration: ' + message.response.durationMs + 'ms</div>';
                            
                            if (message.response.toolCards && message.response.toolCards.length > 0) {
                                statsHtml += '<div class="iteration-info">Tools Used: ' + message.response.toolCards.map(tc => tc.toolName).join(', ') + '</div>';
                            }
                            
                            if (message.response.success !== undefined) {
                                statsHtml += '<div class="iteration-info">Status: ' + (message.response.success ? '✅ Success' : '❌ Failed') + '</div>';
                            }
                            
                            lastMsg.innerHTML += statsHtml;
                        } else {
                            // No streaming happened, show full response
                            let responseHtml = '';

                            // Show final text if available
                            if (message.response.text && message.response.text.trim() !== '') {
                                responseHtml = '<div>' + message.response.text.replace(/\\n/g, '<br>') + '</div>';
                            } else if (message.response.toolCards && message.response.toolCards.length > 0) {
                                responseHtml = '<div><em>Completed tool operations:</em></div>';
                            } else {
                                responseHtml = '<div><em>No response generated.</em></div>';
                            }

                            // Show tool cards summary
                            if (message.response.toolCards && message.response.toolCards.length > 0) {
                                responseHtml += '<div class="iteration-info">Tools Used: ' + message.response.toolCards.map(tc => tc.toolName).join(', ') + '</div>';
                            }

                            responseHtml += '<div class="iteration-info">Iterations: ' + message.response.iterations + ' | Duration: ' + message.response.durationMs + 'ms</div>';

                            if (message.response.success !== undefined) {
                                responseHtml += '<div class="iteration-info">Status: ' + (message.response.success ? '✅ Success' : '❌ Failed') + '</div>';
                            }

                            addMessage('agent', responseHtml, true);
                        }

                        isProcessing = false;
                        updateActionButton();
                        userInput.disabled = false;
                        userInput.focus();
                        break;

                    case 'stopped':
                        hideProgress();
                        addMessage('agent', '<strong>⏹️ Stopped by user</strong>', true);
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
                        // No longer needed - configUpdated handles UI updates without reload
                        // This is kept for backward compatibility but does nothing
                        break;
                }
            } catch (error) {
                console.error('Error handling webview message:', error, message);
                // Ensure input is re-enabled even if there's an error
                isProcessing = false;
                userInput.disabled = false;
                updateActionButton();
            }
        });

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

    // Dispose provider and tool card manager
    await this.provider.dispose();
    this.toolCardManager.dispose();

    this.log('AgentTabManager disposed');
  }
}
