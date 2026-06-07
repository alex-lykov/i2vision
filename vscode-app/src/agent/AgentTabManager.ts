/**
 * AgentTabManager - Creates and manages agent tabs
 */

import * as vscode from 'vscode';
import * as path from 'path';
import * as crypto from 'crypto';
import { LocalAgentProvider } from './LocalAgentProvider';
import { LocalI2VisionAgent, VslfcLayer, AgentContext } from './LocalI2VisionAgent';
import { AgentConfig, InteractionRecord } from './AgentBridge';
import { ToolCardManager } from './ToolCardManager';

interface AgentTab {
  id: string;
  layer: 'vision' | 'structure' | 'logic' | 'flow' | 'code';
  agent: LocalI2VisionAgent;
  panel: vscode.WebviewPanel;
  history: InteractionRecord[];
  isProcessing: boolean;
  cancelToken?: vscode.CancellationTokenSource;
}

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
    this.provider = new LocalAgentProvider(context, outputChannel);
    this.toolCardManager = new ToolCardManager(outputChannel);
    this.log('AgentTabManager initialized');
  }

  async initialize(): Promise<void> {
    await this.provider.initialize();
    this.log('AgentTabManager initialization complete');
  }

  clearConfigCache(): void {
    this.provider.clearConfigCache();
    this.toolCardManager.reloadConfig();
    this.log('Config cache cleared');
  }

  async createTab(layer: 'vision' | 'structure' | 'logic' | 'flow' | 'code'): Promise<string> {
    this.log(`Creating ${layer} agent tab...`);

    try {
      const vslfcLayer = VslfcLayer[layer.toUpperCase() as keyof typeof VslfcLayer];
      const agent = await this.provider.createAgent(vslfcLayer);

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

      panel.webview.onDidReceiveMessage(async (message) => {
        await this.handleWebviewMessage(tab, message);
      });

      panel.onDidDispose(() => {
        this.closeTab(tab.id);
      });

      this.tabs.set(tab.id, tab);
      this.updateWebview(tab);

      this.log(`Created ${layer} agent tab: ${tab.id}`);
      return tab.id;
    } catch (error: any) {
      this.log(`Error creating tab: ${error.message}`);
      vscode.window.showErrorMessage(`Failed to create agent tab: ${error.message}`);
      throw error;
    }
  }

  private async handleWebviewMessage(tab: AgentTab, message: any) {
    switch (message.command) {
      case 'sendMessage':
        await this.processUserInput(tab, message.text);
        break;
      case 'stopAgent':
        await this.stopAgent(tab);
        break;
      case 'changeProvider':
        await this.changeProvider(tab, message.provider);
        break;
      case 'changeModel':
        await this.changeModel(tab, message.model);
        break;
    }
  }

  private async stopAgent(tab: AgentTab): Promise<void> {
    if (!tab.isProcessing) return;

    this.log('Stopping agent...');
    if (tab.cancelToken) {
      tab.cancelToken.cancel();
      tab.cancelToken.dispose();
      tab.cancelToken = undefined;
    }

    tab.panel.webview.postMessage({ command: 'stopped' });
    tab.isProcessing = false;
    this.log('Agent stopped');
  }

  private cleanResponseText(text: string): string {
    if (!text) return '';
    text = text.replace(/^reasoning:\s*/gmi, '');
    text = text.replace(/\bEOS\b/g, '');
    text = text.replace(/^tool_calls:\s*/gmi, '');
    return text.trim();
  }

  private async processUserInput(tab: AgentTab, userInput: string) {
    if (tab.isProcessing) {
      this.log('Agent is already processing, ignoring input');
      return;
    }

    tab.isProcessing = true;
    const startTime = Date.now();
    tab.cancelToken = new vscode.CancellationTokenSource();

    this.log(`Processing user input (${userInput.length} chars)`);

    tab.panel.webview.postMessage({
      command: 'processing',
      userInput
    });

    try {
      const currentFile = vscode.window.activeTextEditor?.document.uri.fsPath;
      const context: AgentContext = {
        workspaceRoot: vscode.workspace.workspaceFolders?.[0]?.uri.fsPath || '',
        currentFile,
        sessionId: tab.id
      };

      const requestId = `request-${Date.now()}`;
      const IDLE_TIMEOUT_MS = 30000;
      let idleTimer: NodeJS.Timeout | null = null;
      
      const resetIdleTimer = () => {
        if (idleTimer) clearTimeout(idleTimer);
        idleTimer = setTimeout(() => {
          this.log(`⚠️ Agent idle for ${IDLE_TIMEOUT_MS}ms - timing out`);
          throw new Error(`Agent idle for ${IDLE_TIMEOUT_MS / 1000} seconds`);
        }, IDLE_TIMEOUT_MS);
      };
      
      let accumulatedText = '';
      let toolCalls: any[] = [];
      let iterationCount = 0;

      const agentPromise = (async () => {
        resetIdleTimer();
        
        try {
          for await (const chunk of tab.agent.processStreaming({
            id: requestId,
            task: userInput,
            context: context
          })) {
            resetIdleTimer();
            
            if (chunk.type === 'text') {
              accumulatedText += chunk.text;
              tab.panel.webview.postMessage({
                command: 'streamingText',
                text: chunk.text,
                accumulated: accumulatedText
              });
            } else if (chunk.type === 'tool_call_started') {
              tab.panel.webview.postMessage({
                command: 'progress',
                event: { type: 'tool_start', toolCall: { toolName: chunk.toolName, args: chunk.args } }
              });
            } else if (chunk.type === 'tool_call_completed') {
              const completedChunk = chunk as any;
              toolCalls.push({
                toolName: completedChunk.toolName,
                args: completedChunk.args || {},
                result: completedChunk.result,
                toolCallId: completedChunk.toolCallId
              });
              tab.panel.webview.postMessage({
                command: 'progress',
                event: { 
                  type: 'tool_complete', 
                  toolCall: { 
                    toolName: completedChunk.toolName, 
                    args: completedChunk.args, 
                    result: completedChunk.result,
                    toolCallId: completedChunk.toolCallId
                  } 
                }
              });
            } else if (chunk.type === 'iteration_complete') {
              iterationCount++;
            } else if (chunk.type === 'done') {
              const doneChunk = chunk as { type: 'done'; iterations?: number };
              // Use explicit iterations if provided, otherwise use counted iterations
              iterationCount = doneChunk.iterations || (iterationCount > 0 ? iterationCount : 1);
            }
          }
        } finally {
          if (idleTimer) {
            clearTimeout(idleTimer);
            idleTimer = null;
          }
        }
        
        return {
          finalText: accumulatedText,
          toolCalls: toolCalls,
          iterations: iterationCount,
          durationMs: Date.now() - startTime,
          success: true
        };
      })();

      const response = await agentPromise;

      // Log concise summary
      const toolSummary = response.toolCalls?.map(tc => 
        `${tc.toolName}${tc.error ? '❌' : '✅'}`
      ).join(', ') || 'none';
      this.log(`✅ Complete: ${response.iterations} iter, ${response.toolCalls?.length || 0} tools (${toolSummary}), ${response.durationMs}ms`);

      // Clean the final accumulated text (remove reasoning:, EOS, tool_calls: markers)
      const cleanedText = this.cleanResponseText(response.finalText || '');
      const formattedResponse = this.toolCardManager.formatAgentResponse(
        cleanedText,
        response.toolCalls || [],
        response.iterations,
        response.durationMs,
        response.success
      );

      const record: InteractionRecord = {
        timestamp: Date.now(),
        userInput,
        agentResponse: cleanedText,
        toolCalls: response.toolCalls?.map(tc => ({ toolName: tc.toolName, args: tc.args || {} })) || [],
        iterations: response.iterations,
        durationMs: Date.now() - startTime
      };

      tab.history.push(record);
      this.log(`Interaction recorded: ${record.iterations} iterations, ${record.durationMs}ms`);

      tab.panel.webview.postMessage({
        command: 'response',
        response: formattedResponse
      });
    } catch (error: any) {
      this.log(`Error processing input: ${error.message}`);

      if (error.message === 'cancelled' || (tab.cancelToken && tab.cancelToken.token.isCancellationRequested)) {
        tab.panel.webview.postMessage({ command: 'stopped' });
      } else {
        tab.panel.webview.postMessage({ command: 'error', error: String(error) });
      }
    } finally {
      if (tab.cancelToken) {
        tab.cancelToken.dispose();
        tab.cancelToken = undefined;
      }
      tab.isProcessing = false;
    }
  }

  private async changeProvider(tab: AgentTab, provider: string) {
    this.log(`Changing provider to ${provider} for ${tab.layer} agent...`);

    try {
      this.provider.clearConfigCache();
      const agentConfig = this.provider.getConfig(tab.layer);
      agentConfig.model.provider = provider;
      const defaultModel = provider === 'deepseek' ? 'deepseek-chat' : 'llama3.2:3b';
      agentConfig.model.id = defaultModel;
      
      await this.saveAgentConfig(tab.layer, agentConfig);
      await new Promise(resolve => setTimeout(resolve, 100));
      await this.reloadAgent(tab);
      
      tab.panel.webview.postMessage({
        command: 'configUpdated',
        provider: provider,
        model: defaultModel
      });
      
      this.log(`Provider changed to ${provider}`);
    } catch (error: any) {
      this.log(`Error changing provider: ${error.message}`);
      vscode.window.showErrorMessage(`Failed to change provider: ${error.message}`);
    }
  }

  private async changeModel(tab: AgentTab, model: string) {
    this.log(`Changing model to ${model} for ${tab.layer} agent...`);

    try {
      const agentConfig = this.provider.getConfig(tab.layer);
      agentConfig.model.id = model;
      await this.saveAgentConfig(tab.layer, agentConfig);
      await this.reloadAgent(tab);
      
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

  private async reloadAgent(tab: AgentTab) {
    this.log(`Reloading agent configuration for ${tab.layer}...`);

    try {
      const vslfcLayer = VslfcLayer[tab.layer.toUpperCase() as keyof typeof VslfcLayer];
      const newAgent = await this.provider.createAgent(vslfcLayer);
      tab.agent = newAgent;
      this.log(`New agent created: ${newAgent.id}`);

      const agentConfig = this.provider.getConfig(tab.layer);
      const modelId = agentConfig.model.id;
      const providerId = agentConfig.model.provider;
      const maxIterations = agentConfig.iterationSettings.maxIterations;

      this.updateWebview(tab);

      tab.panel.webview.postMessage({
        command: 'configUpdated',
        provider: providerId,
        model: modelId,
        maxIterations: maxIterations
      });

      this.log(`Agent reloaded successfully`);
    } catch (error: any) {
      this.log(`Error reloading agent: ${error.message}`);
      vscode.window.showErrorMessage(`Failed to reload agent: ${error.message}`);
      throw error;
    }
  }

  private async saveAgentConfig(layer: string, config: AgentConfig): Promise<void> {
    const fs = require('fs');
    const yaml = require('js-yaml');
    
    const layerName = layer.toLowerCase();
    const configPath = path.join(
      vscode.workspace.workspaceFolders?.[0]?.uri.fsPath || '',
      '.vision-ai',
      `${layerName}-agent.yaml`
    );
    
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
    
    const visionAiDir = path.dirname(configPath);
    await fs.promises.mkdir(visionAiDir, { recursive: true });
    
    const yamlContent = yaml.dump(yamlConfig, {
      lineWidth: -1,
      noRefs: true,
      quotingType: '"',
      forceQuotes: false
    });
    
    await fs.promises.writeFile(configPath, yamlContent, 'utf8');
    this.log(`Saved config to ${configPath}`);
  }

  private async closeTab(tabId: string): Promise<void> {
    const tab = this.tabs.get(tabId);
    if (tab) {
      this.log(`Closing tab: ${tabId}`);
      if (tab.cancelToken) {
        tab.cancelToken.cancel();
        tab.cancelToken.dispose();
      }
      tab.panel.dispose();
      this.tabs.delete(tabId);
      this.log(`Tab closed: ${tabId}`);
    }
  }

  private updateWebview(tab: AgentTab): void {
    const agentConfig = this.provider.getConfig(tab.layer);
    const modelId = agentConfig.model.id;
    const providerId = agentConfig.model.provider;
    const maxIterations = agentConfig.iterationSettings.maxIterations;
    tab.panel.webview.html = this.getWebviewContent(modelId, providerId, maxIterations);
  }

  private getNonce(): string {
    return crypto.randomBytes(16).toString('base64');
  }

  private getWebviewContent(modelId: string, providerId: string, maxIterations: number): string {
    const config = vscode.workspace.getConfiguration('i2vision.output');
    const outputSettings = {
      showReasoning: config.get<boolean>('showReasoning', false),
      autoCollapse: config.get<number>('autoCollapse', 500),
      maxPreviewLines: config.get<number>('maxPreviewLines', 10),
      theme: config.get<string>('theme', 'system'),
      fontSize: config.get<string>('fontSize', 'medium'),
      showTokenCount: config.get<boolean>('showTokenCount', false),
      showConfidence: config.get<boolean>('showConfidence', false),
      showToolDetails: config.get<boolean>('showToolDetails', true),
      codeHighlight: config.get<boolean>('codeHighlight', true),
    };

    const settingsJson = JSON.stringify(outputSettings).replace(/"/g, '&quot;');
    
    // Note: AgentOutputCard is plain TypeScript (not React) - generates HTML via template strings
    // No bundler required - works directly in VSCode webviews
    
    return `<!DOCTYPE html>
<html lang="en" data-output-settings="${settingsJson}">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline';">
    <title>i2-Vision Agent</title>
    <style>
        :root { --container-padding: 20px; }
        body {
            font-family: var(--vscode-font-family);
            padding: var(--container-padding);
            color: var(--vscode-foreground);
            background-color: var(--vscode-editor-background);
            margin: 0;
        }
        body.theme-light { --card-bg: #ffffff; --card-border: #e0e0e0; }
        body.theme-dark { --card-bg: #1e1e1e; --card-border: #404040; }
        body.theme-compact { --container-padding: 10px; }
        body.font-small { font-size: 12px; }
        body.font-medium { font-size: 14px; }
        body.font-large { font-size: 16px; }
        
        .agent-output-card {
            border: 1px solid var(--card-border, var(--vscode-panel-border, #ccc));
            border-radius: 6px;
            background: var(--card-bg, var(--vscode-editor-background));
            margin-bottom: 16px;
            overflow: hidden;
        }
        .output-card-header {
            display: flex;
            justify-content: space-between;
            align-items: center;
            padding: 10px 14px;
            background: var(--vscode-editor-inactiveSelectionBackground);
            border-bottom: 1px solid var(--card-border, var(--vscode-panel-border, #ccc));
        }
        .header-left { display: flex; align-items: center; gap: 10px; }
        .provider-badge {
            padding: 3px 8px;
            border-radius: 4px;
            color: white;
            font-size: 11px;
            font-weight: 600;
        }
        .model-name {
            font-size: 12px;
            color: var(--vscode-descriptionForeground);
            font-family: var(--vscode-editor-font-family);
        }
        .header-right { display: flex; align-items: center; gap: 10px; }
        .status-icon { font-size: 14px; }
        .duration-badge, .iterations-badge {
            font-size: 11px;
            color: var(--vscode-descriptionForeground);
            font-family: var(--vscode-editor-font-family);
        }
        .output-card-content { padding: 14px; }
        .response-text-section { margin-bottom: 12px; }
        .response-text-section.collapsed .response-text {
            max-height: 200px;
            overflow: hidden;
            position: relative;
        }
        .response-text-section.collapsed .response-text::after {
            content: '';
            position: absolute;
            bottom: 0;
            left: 0;
            right: 0;
            height: 40px;
            background: linear-gradient(transparent, var(--card-bg, var(--vscode-editor-background)));
        }
        .response-text { line-height: 1.6; }
        .expand-button {
            display: flex;
            align-items: center;
            gap: 6px;
            margin-top: 8px;
            padding: 6px 10px;
            background: var(--vscode-button-background);
            color: var(--vscode-button-foreground);
            border: none;
            border-radius: 4px;
            cursor: pointer;
            font-size: 12px;
            width: fit-content;
        }
        .expand-button:hover { background: var(--vscode-button-hoverBackground); }
        .error-section {
            display: flex;
            align-items: center;
            gap: 8px;
            padding: 10px 14px;
            background: var(--vscode-inputValidation-errorBackground);
            border: 1px solid var(--vscode-inputValidation-errorBorder);
            border-radius: 4px;
            margin-bottom: 12px;
        }
        .error-icon { font-size: 16px; }
        .error-text { color: var(--vscode-errorForeground); font-size: 13px; }
        .section-title {
            display: flex;
            align-items: center;
            gap: 8px;
            margin: 16px 0 10px 0;
            padding-bottom: 6px;
            border-bottom: 1px solid var(--card-border, var(--vscode-panel-border, #ccc));
        }
        .section-icon { font-size: 14px; }
        .section-label { font-weight: 600; font-size: 12px; color: var(--vscode-foreground); }
        .tool-calls-list { display: flex; flex-direction: column; gap: 8px; }
        .tool-call-card {
            border: 1px solid var(--card-border, var(--vscode-panel-border, #ccc));
            border-radius: 4px;
            overflow: hidden;
            background: var(--vscode-editor-inactiveSelectionBackground);
        }
        .tool-call-card.success { border-left: 3px solid var(--vscode-terminal-ansiGreen); }
        .tool-call-card.error { border-left: 3px solid var(--vscode-errorForeground); }
        .tool-call-header {
            display: flex;
            align-items: center;
            gap: 8px;
            padding: 8px 12px;
            cursor: pointer;
            user-select: none;
            background: var(--vscode-editor-background);
        }
        .tool-call-header:hover { background: var(--vscode-list-hoverBackground); }
        .tool-call-toggle { font-size: 10px; width: 12px; text-align: center; }
        .tool-call-icon { font-size: 14px; }
        .tool-call-name { font-weight: 600; flex: 1; font-size: 12px; }
        .tool-call-meta { display: flex; gap: 10px; font-size: 11px; color: var(--vscode-descriptionForeground); }
        .tool-call-duration { font-family: var(--vscode-editor-font-family); }
        .tool-call-id { font-family: var(--vscode-editor-font-family); font-size: 9px; opacity: 0.6; cursor: help; }
        .tool-call-body { padding: 10px 12px; display: block; }
        .tool-call-body[style*="display: none"] { display: none !important; }
        .tool-call-args, .tool-call-result, .tool-call-error { margin-top: 8px; font-size: 11px; }
        .tool-call-args code, .tool-call-result pre {
            background: var(--vscode-editor-background);
            padding: 8px;
            border-radius: 3px;
            font-family: var(--vscode-editor-font-family);
            font-size: 11px;
            display: block;
            margin-top: 4px;
            overflow-x: auto;
        }
        .tool-call-result pre { white-space: pre-wrap; word-break: break-word; max-height: 300px; overflow-y: auto; }
        .args-label, .result-label, .error-label { font-weight: 600; color: var(--vscode-foreground); display: block; margin-bottom: 4px; }
        .output-card-footer {
            display: flex;
            justify-content: space-between;
            align-items: center;
            padding: 10px 14px;
            background: var(--vscode-editor-inactiveSelectionBackground);
            border-top: 1px solid var(--card-border, var(--vscode-panel-border, #ccc));
        }
        .footer-meta { display: flex; gap: 12px; font-size: 11px; color: var(--vscode-descriptionForeground); }
        .meta-item { font-family: var(--vscode-editor-font-family); }
        .footer-actions { display: flex; gap: 8px; }
        .footer-action-btn {
            display: flex;
            align-items: center;
            gap: 6px;
            padding: 6px 12px;
            background: var(--vscode-button-background);
            color: var(--vscode-button-foreground);
            border: none;
            border-radius: 4px;
            cursor: pointer;
            font-size: 12px;
        }
        .footer-action-btn:hover { background: var(--vscode-button-hoverBackground); }
        .action-icon { font-size: 14px; }
        .text-muted { color: var(--vscode-descriptionForeground); font-style: italic; }
        .code-block { background: var(--vscode-editor-background); padding: 8px; border-radius: 3px; overflow-x: auto; margin: 8px 0; }
        .inline-code { background: var(--vscode-editor-inactiveSelectionBackground); padding: 2px 6px; border-radius: 3px; font-family: var(--vscode-editor-font-family); font-size: 0.9em; }
        
        .config-info { font-size: 12px; color: var(--vscode-descriptionForeground); margin-bottom: 12px; padding: 8px; background: var(--vscode-editor-inactiveSelectionBackground); border-radius: 4px; }
        #messages { min-height: 300px; max-height: 60vh; overflow-y: auto; margin-bottom: 16px; padding: 8px; border: 1px solid var(--vscode-panel-border, #ccc); border-radius: 4px; background: var(--vscode-editor-background); }
        .message { margin-bottom: 12px; padding: 8px 12px; border-radius: 4px; line-height: 1.5; }
        .user-message { background: var(--vscode-button-background); color: var(--vscode-button-foreground); margin-left: 20%; }
        .agent-message { background: var(--vscode-editor-inactiveSelectionBackground); margin-right: 20%; }
        .input-row { display: flex; gap: 8px; align-items: center; position: sticky; bottom: 0; background: var(--vscode-editor-background); padding-top: 8px; }
        #userInput { flex: 1; padding: 8px 12px; border: 1px solid var(--vscode-input-border, var(--vscode-panel-border, #ccc)); border-radius: 4px; background: var(--vscode-input-background); color: var(--vscode-input-foreground); font-family: var(--vscode-font-family); }
        #userInput:focus { outline: 2px solid var(--vscode-focusBorder); }
        #userInput:disabled { opacity: 0.6; cursor: not-allowed; }
        #actionButton { padding: 8px 16px; background: var(--vscode-button-background); color: var(--vscode-button-foreground); border: none; border-radius: 4px; cursor: pointer; min-width: 80px; font-weight: 600; }
        #actionButton:hover { background: var(--vscode-button-hoverBackground); }
        #actionButton:disabled { opacity: 0.5; cursor: not-allowed; }
        #actionButton.stop-button { background: #dc3545; }
        #actionButton.stop-button:hover { background: #c82333; }
        
        optgroup { font-weight: 600; color: var(--vscode-foreground); }
        optgroup[label*="Local"] { color: var(--vscode-terminal-ansiGreen); }
        optgroup[label*="Cloud"] { color: var(--vscode-terminal-ansiBlue); }
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

        const htmlEl = document.documentElement;
        const outputSettings = htmlEl.dataset.outputSettings ? JSON.parse(htmlEl.dataset.outputSettings) : {};
        
        if (outputSettings.theme && outputSettings.theme !== 'system') {
            document.body.classList.add('theme-' + outputSettings.theme);
        }
        if (outputSettings.fontSize) {
            document.body.classList.add('font-' + outputSettings.fontSize);
        }
        window.outputSettings = outputSettings;

        const MODELS_BY_PROVIDER = {
            'ollama': [
                { id: 'llama3.2:3b', name: 'Llama 3.2 3B (Local)', type: 'local', quality: 'good' },
                { id: 'llama3.2:7b', name: 'Llama 3.2 7B (Local)', type: 'local', quality: 'good' },
                { id: 'codellama:7b', name: 'CodeLlama 7B (Local)', type: 'local', quality: 'good' },
                { id: 'qwen3.5:cloud', name: 'Qwen 3.5 (Cloud)', type: 'cloud', quality: 'excellent' },
                { id: 'deepseek-v3.1:671b-cloud', name: 'DeepSeek V3.1 (Cloud)', type: 'cloud', quality: 'excellent' }
            ],
            'deepseek': [
                { id: 'deepseek-chat', name: 'DeepSeek Chat (V3)', type: 'cloud', quality: 'excellent' },
                { id: 'deepseek-coder', name: 'DeepSeek Coder', type: 'cloud', quality: 'excellent' },
                { id: 'deepseek-reasoner', name: 'DeepSeek Reasoner (R1)', type: 'cloud', quality: 'excellent' }
            ]
        };

        function initializeModelDropdown() {
            const currentProvider = providerSelect.value;
            const models = MODELS_BY_PROVIDER[currentProvider] || [];
            modelSelect.innerHTML = '';
            
            const localModels = models.filter(m => m.type === 'local');
            const cloudModels = models.filter(m => m.type === 'cloud');
            
            if (localModels.length > 0) {
                const localOptgroup = document.createElement('optgroup');
                localOptgroup.label = 'Local Models';
                localModels.forEach(model => {
                    const option = document.createElement('option');
                    option.value = model.id;
                    option.textContent = model.name;
                    if (model.id === '${modelId}') option.selected = true;
                    localOptgroup.appendChild(option);
                });
                modelSelect.appendChild(localOptgroup);
            }
            
            if (cloudModels.length > 0) {
                const cloudOptgroup = document.createElement('optgroup');
                cloudOptgroup.label = 'Cloud Models';
                cloudModels.forEach(model => {
                    const option = document.createElement('option');
                    option.value = model.id;
                    option.textContent = model.name;
                    if (model.id === '${modelId}') option.selected = true;
                    cloudOptgroup.appendChild(option);
                });
                modelSelect.appendChild(cloudOptgroup);
            }
        }

        function onProviderChange() {
            vscode.postMessage({ command: 'changeProvider', provider: providerSelect.value });
            initializeModelDropdown();
        }

        function onModelChange() {
            vscode.postMessage({ command: 'changeModel', model: modelSelect.value });
        }

        initializeModelDropdown();

        window.addEventListener('error', (event) => {
            console.error('WebView error:', event.error);
            isProcessing = false;
            userInput.disabled = false;
            updateActionButton();
        });

        function handleKeyPress(event) {
            if (event.key === 'Enter' && !event.shiftKey) {
                event.preventDefault();
                if (!isProcessing) sendMessage();
            }
        }

        function toggleAction() {
            if (isProcessing) stopAgent();
            else sendMessage();
        }

        function sendMessage() {
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
            if (isHtml) div.innerHTML = content;
            else div.textContent = content;
            messagesDiv.appendChild(div);
            div.scrollIntoView({ behavior: 'smooth' });
        }

        function escapeHtml(text) {
            if (!text) return '';
            const div = document.createElement('div');
            div.textContent = text;
            return div.innerHTML;
        }

        function showProgress(message) {
            if (currentProgressDiv) currentProgressDiv.remove();
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

        function formatDuration(ms) {
            if (ms < 1000) return ms + 'ms';
            return (ms / 1000).toFixed(1) + 's';
        }

        function getPreviewText(text, maxLines) {
            const lines = text.split('\\n');
            if (lines.length <= maxLines) return text;
            return lines.slice(0, maxLines).join('\\n') + '\\n\\n... (expand to show more)';
        }

        function formatResponseText(text) {
            if (!text) return '<em class="text-muted">No response generated.</em>';
            let formatted = escapeHtml(text);
            formatted = formatted.replace(/\\n/g, '<br>');
            formatted = formatted.replace(/\\*\\*([^*]+)\\*\\*/g, '<strong>$1</strong>');
            return formatted;
        }

        window.createOutputCard = function(card) {
            const cardDiv = document.createElement('div');
            cardDiv.className = 'agent-output-card';
            
            const providerColor = card.header.provider === 'ollama' ? '#27ae60' : '#3498db';
            const statusIcon = card.header.status === 'success' ? '✅' : '❌';
            
            let html = '<div class="output-card-header">';
            html += '<div class="header-left">';
            html += '<span class="provider-badge" style="background-color: ' + providerColor + '">' + card.header.providerName + '</span>';
            html += '<span class="model-name">' + escapeHtml(card.header.model) + '</span>';
            html += '</div>';
            html += '<div class="header-right">';
            html += '<span class="status-icon">' + statusIcon + '</span>';
            html += '<span class="duration-badge">' + formatDuration(card.header.durationMs) + '</span>';
            html += '<span class="iterations-badge">' + card.header.iterations + ' iter</span>';
            html += '</div></div>';
            
            html += '<div class="output-card-content">';
            
            if (card.content.error) {
                html += '<div class="error-section"><span class="error-icon">❌</span><span class="error-text">' + escapeHtml(card.content.error) + '</span></div>';
            }
            
            const shouldCollapse = card.content.text.length > card.display.autoCollapseAfter;
            const previewText = shouldCollapse && card.display.collapsed 
                ? getPreviewText(card.content.text, card.display.maxPreviewLines)
                : card.content.text;
            
            html += '<div class="response-text-section' + (shouldCollapse && card.display.collapsed ? ' collapsed' : '') + '">';
            html += '<div class="response-text">' + formatResponseText(previewText) + '</div>';
            if (shouldCollapse) {
                const expandText = card.display.collapsed ? 'Show more' : 'Show less';
                const expandIcon = card.display.collapsed ? '▼' : '▲';
                html += '<button class="expand-button" onclick="toggleOutputCard(this)">';
                html += '<span class="expand-icon">' + expandIcon + '</span>';
                html += '<span class="expand-text">' + expandText + '</span></button>';
            }
            html += '</div>';
            
            if (card.display.showToolDetails && card.content.toolCalls.length > 0) {
                html += '<div class="section-title"><span class="section-icon">🛠️</span><span class="section-label">Tool Calls (' + card.content.toolCalls.length + ')</span></div>';
                html += '<div class="tool-calls-list">';
                card.content.toolCalls.forEach(function(tc) {
                    const successClass = tc.success !== false ? 'success' : 'error';
                    const icon = tc.success !== false ? '✅' : '❌';
                    html += '<div class="tool-call-card ' + successClass + '">';
                    html += '<div class="tool-call-header" onclick="toggleToolCallCard(this)">';
                    html += '<span class="tool-call-toggle">▼</span>';
                    html += '<span class="tool-call-icon">' + icon + '</span>';
                    html += '<span class="tool-call-name">' + escapeHtml(tc.toolName) + '</span>';
                    if (tc.durationMs) {
                        html += '<span class="tool-call-meta"><span class="tool-call-duration">' + formatDuration(tc.durationMs) + '</span></span>';
                    }
                    html += '</div>';
                    html += '<div class="tool-call-body">';
                    if (Object.keys(tc.args).length > 0) {
                        html += '<div class="tool-call-args"><span class="args-label">Args:</span><code>' + escapeHtml(JSON.stringify(tc.args, null, 2)) + '</code></div>';
                    }
                    if (tc.result) {
                        html += '<div class="tool-call-result"><span class="result-label">Result:</span><pre>' + escapeHtml(tc.result) + '</pre></div>';
                    }
                    if (tc.error) {
                        html += '<div class="tool-call-error"><span class="error-label">Error:</span><span>' + escapeHtml(tc.error) + '</span></div>';
                    }
                    html += '</div></div>';
                });
                html += '</div>';
            }
            
            html += '</div>';
            
            html += '<div class="output-card-footer">';
            const metaItems = [];
            if (card.footer.tokensUsed) metaItems.push('📊 ' + card.footer.tokensUsed + ' tokens');
            if (card.footer.confidence) metaItems.push('🎯 ' + Math.round(card.footer.confidence * 100) + '% confidence');
            if (metaItems.length > 0) {
                html += '<div class="footer-meta">' + metaItems.join('') + '</div>';
            }
            html += '<div class="footer-actions">';
            card.footer.actions.filter(function(a) { return a.enabled; }).forEach(function(action) {
                html += '<button class="footer-action-btn" onclick="handleFooterAction(\\'' + action.id + '\\')">';
                html += '<span class="action-icon">' + action.icon + '</span>';
                html += '<span class="action-label">' + action.label + '</span></button>';
            });
            html += '</div></div>';
            
            cardDiv.innerHTML = html;
            return cardDiv;
        };
        
        window.toggleOutputCard = function(button) {
            const section = button.parentElement;
            const icon = button.querySelector('.expand-icon');
            const text = button.querySelector('.expand-text');
            
            if (section.classList.contains('collapsed')) {
                section.classList.remove('collapsed');
                icon.textContent = '▲';
                text.textContent = 'Show less';
            } else {
                section.classList.add('collapsed');
                icon.textContent = '▼';
                text.textContent = 'Show more';
            }
        };
        
        window.toggleToolCallCard = function(header) {
            const body = header.nextElementSibling;
            const toggle = header.querySelector('.tool-call-toggle');
            if (body.style.display === 'none') {
                body.style.display = 'block';
                toggle.textContent = '▼';
            } else {
                body.style.display = 'none';
                toggle.textContent = '▶';
            }
        };
        
        window.handleFooterAction = function(actionId) {
            console.log('Footer action:', actionId);
            if (actionId === 'copy') {
                const lastCard = messagesDiv.querySelector('.agent-output-card:last-child .response-text');
                if (lastCard) {
                    navigator.clipboard.writeText(lastCard.textContent);
                }
            } else if (actionId === 'retry') {
                const lastUserMsg = messagesDiv.querySelector('.user-message:last-child');
                if (lastUserMsg) {
                    userInput.value = lastUserMsg.textContent;
                    sendMessage();
                }
            }
        };

        window.addEventListener('message', event => {
            const message = event.data;

            try {
                switch (message.command) {
                    case 'processing':
                        showProgress('Processing: ' + message.userInput.substring(0, 50) + '...');
                        break;

                    case 'streamingText':
                        hideProgress();
                        // Clean the accumulated text before display (remove reasoning:, EOS, etc.)
                        let cleanAccumulated = message.accumulated;
                        cleanAccumulated = cleanAccumulated.replace(/^reasoning:\s*/gmi, '');
                        cleanAccumulated = cleanAccumulated.replace(/\bEOS\b/g, '');
                        cleanAccumulated = cleanAccumulated.replace(/^tool_calls:\s*/gmi, '');
                        const lastMessage = messagesDiv.lastElementChild;
                        if (lastMessage && lastMessage.classList.contains('agent-message')) {
                            lastMessage.innerHTML = cleanAccumulated.replace(/\\n/g, '<br>');
                        } else {
                            addMessage('agent', cleanAccumulated.replace(/\\n/g, '<br>'), true);
                        }
                        break;

                    case 'progress':
                        const evt = message.event;
                        if (evt.type === 'thinking') showProgress(evt.message);
                        break;

                    case 'configUpdated':
                        if (message.provider) providerSelect.value = message.provider;
                        if (message.model) {
                            initializeModelDropdown();
                            modelSelect.value = message.model;
                        }
                        showProgress('Configuration updated');
                        setTimeout(hideProgress, 2000);
                        break;

                    case 'response':
                        hideProgress();

                        if (window.createOutputCard && message.response.text) {
                            const settings = window.outputSettings || {};
                            const cardData = {
                                header: {
                                    provider: '${providerId}',
                                    providerName: '${providerId}' === 'ollama' ? 'Ollama' : 'DeepSeek',
                                    model: '${modelId}',
                                    timestamp: Date.now(),
                                    durationMs: message.response.durationMs,
                                    iterations: message.response.iterations,
                                    status: message.response.success !== false ? 'success' : 'error'
                                },
                                content: {
                                    text: message.response.text,
                                    toolCalls: (message.response.toolCards || []).map(tc => ({
                                        toolName: tc.toolName,
                                        args: tc.args || {},
                                        result: tc.result,
                                        durationMs: tc.durationMs,
                                        success: !tc.error,
                                        error: tc.error
                                    })),
                                    buildOutput: message.response.buildOutput,
                                    error: message.response.success === false ? 'Request failed' : undefined
                                },
                                footer: {
                                    tokensUsed: settings.showTokenCount ? message.response.tokensUsed : undefined,
                                    confidence: settings.showConfidence ? message.response.confidence : undefined,
                                    actions: [
                                        { id: 'copy', label: 'Copy', icon: '📋', enabled: true },
                                        { id: 'retry', label: 'Retry', icon: '🔄', enabled: true }
                                    ]
                                },
                                display: {
                                    collapsed: message.response.text.length > (settings.autoCollapse || 500),
                                    showReasoning: settings.showReasoning || false,
                                    showToolDetails: settings.showToolDetails !== false,
                                    showTokenCount: settings.showTokenCount || false,
                                    showConfidence: settings.showConfidence || false,
                                    theme: settings.theme || 'system',
                                    fontSize: settings.fontSize || 'medium',
                                    autoCollapseAfter: settings.autoCollapse || 500,
                                    maxPreviewLines: settings.maxPreviewLines || 10,
                                    codeHighlight: settings.codeHighlight !== false
                                }
                            };
                            
                            const cardDiv = window.createOutputCard(cardData);
                            messagesDiv.appendChild(cardDiv);
                            cardDiv.scrollIntoView({ behavior: 'smooth' });
                        } else {
                            const lastMsg = messagesDiv.lastElementChild;
                            if (lastMsg && lastMsg.classList.contains('agent-message')) {
                                let statsHtml = '<div class="iteration-info">Iterations: ' + message.response.iterations + ' | Duration: ' + message.response.durationMs + 'ms</div>';
                                lastMsg.innerHTML += statsHtml;
                            } else {
                                addMessage('agent', 'Response received', false);
                            }
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
                }
            } catch (error) {
                console.error('Error handling message:', error, message);
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

  private capitalize(str: string): string {
    return str.charAt(0).toUpperCase() + str.slice(1);
  }

  private log(message: string): void {
    this.outputChannel.appendLine(`[AgentTabManager] ${message}`);
  }

  async dispose(): Promise<void> {
    this.log('Disposing AgentTabManager...');
    for (const tab of this.tabs.values()) {
      await this.closeTab(tab.id);
    }
    await this.provider.dispose();
    this.toolCardManager.dispose();
    this.log('AgentTabManager disposed');
  }
}
