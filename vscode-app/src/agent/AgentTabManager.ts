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
  private panel?: vscode.WebviewPanel;

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
    // Remove reasoning: markers ANYWHERE in text
    text = text.replace(/\s*reasoning:\s*/gi, ' ');
    // Remove EOS markers
    text = text.replace(/\bEOS\b/g, '');
    // Remove tool_calls: prefix
    text = text.replace(/^tool_calls:\s*/gmi, '');
    // Remove tool_call: lines with JSON
    text = text.replace(/^\s*tool_call:\s*\{[\s\S]*?\}\s*$/gmi, '');
    text = text.replace(/\s*tool_call:\s*\{[\s\S]*?\}/gi, '');
    // Fix run-together words: lowercase→uppercase (Ihave → I have)
    text = text.replace(/([a-z])([A-Z])/g, '$1 $2');
    // Fix common concatenated words
    text = text.replace(/\b(Ihave|Iwill|Ineed|Letme|Let's|Thisis|Thatis|Whatis|Whatare)\b/gi, (match) => {
      return match.replace(/([a-z])([A-Z])/g, '$1 $2');
    });
    // Fix missing space after periods
    text = text.replace(/([.!?])([A-Za-z])/g, '$1 $2');
    // Fix missing space after commas
    text = text.replace(/(,)([A-Za-z])/g, '$1 $2');
    // Fix "Electri City" → "ElectriCity" (over-correction)
    text = text.replace(/Electri\s+City/g, 'ElectriCity');
    // Collapse multiple spaces
    text = text.replace(/\s+/g, ' ');
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
      
      // Accumulate data for final response
      let accumulatedText = '';
      let toolCalls: any[] = [];
      let iterationCount = 0;
      let chunkCount = 0;

      // FIXED: Process chunks in real-time instead of waiting for promise
      this.log('Starting real-time chunk processing...');
      
      resetIdleTimer();
      
      try {
        // Iterate through chunks as they arrive (TRUE STREAMING)
        const streamIterator = tab.agent.processStreaming({
          id: requestId,
          task: userInput,
          context: context
        });
        
        this.log('Got stream iterator, starting iteration...');
        
        for await (const chunk of streamIterator) {
          resetIdleTimer();
          chunkCount++;
          
          this.log(`[Chunk ${chunkCount}] Type: ${chunk.type}, Timestamp: ${chunk.timestamp}`);
          
          // Process each chunk immediately as it arrives
          if (chunk.type === 'text') {
            accumulatedText += chunk.text;
            this.log(`[Chunk ${chunkCount}] Text: ${chunk.text.length} chars (total: ${accumulatedText.length})`);
            
            // Clean the accumulated text for streaming display (remove markers)
            const cleanedForDisplay = this.cleanResponseText(accumulatedText);
            
            // Send streaming text to webview for real-time display
            const sent = tab.panel.webview.postMessage({
              command: 'streamingText',
              text: chunk.text,
              accumulated: cleanedForDisplay
            });
            this.log(`[Chunk ${chunkCount}] Posted streamingText to webview: ${sent}`);
            
          } else if (chunk.type === 'tool_call_started') {
            this.log(`[Chunk ${chunkCount}] Tool started: ${chunk.toolName}`);
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
            this.log(`[Chunk ${chunkCount}] Tool completed: ${completedChunk.toolName}`);
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
            this.log(`[Chunk ${chunkCount}] Iteration complete: ${iterationCount}`);
            
          } else if (chunk.type === 'done') {
            const doneChunk = chunk as { type: 'done'; iterations?: number };
            iterationCount = doneChunk.iterations || (iterationCount > 0 ? iterationCount : 1);
            this.log(`[Chunk ${chunkCount}] Done: ${iterationCount} iterations`);
          }
        }
      } finally {
        if (idleTimer) {
          clearTimeout(idleTimer);
          idleTimer = null;
        }
      }

      this.log(`Streaming complete: ${chunkCount} chunks, ${accumulatedText.length} chars, ${toolCalls.length} tools`);

      // Log concise summary
      const toolSummary = toolCalls?.map(tc => 
        `${tc.toolName}${tc.error ? '❌' : '✅'}`
      ).join(', ') || 'none';
      this.log(`✅ Complete: ${iterationCount} iter, ${toolCalls?.length || 0} tools (${toolSummary}), ${Date.now() - startTime}ms`);

      // Clean the final accumulated text
      const cleanedText = this.cleanResponseText(accumulatedText);
      const formattedResponse = this.toolCardManager.formatAgentResponse(
        cleanedText,
        toolCalls || [],
        iterationCount,
        Date.now() - startTime,
        true
      );

      const record: InteractionRecord = {
        timestamp: Date.now(),
        userInput,
        agentResponse: cleanedText,
        toolCalls: toolCalls?.map(tc => ({ toolName: tc.toolName, args: tc.args || {} })) || [],
        iterations: iterationCount,
        durationMs: Date.now() - startTime
      };

      tab.history.push(record);
      this.log(`Interaction recorded: ${record.iterations} iterations, ${record.durationMs}ms`);
      this.log(`Sending final response to webview: text=${cleanedText.length} chars, toolCards=${formattedResponse.toolCards?.length || 0}`);

      try {
        const sent = await tab.panel.webview.postMessage({
          command: 'response',
          response: formattedResponse
        });
        this.log(`Webview response message sent: ${sent ? '✅' : '❌'}`);
      } catch (error: any) {
        this.log(`ERROR sending response to webview: ${error.message}`);
        throw error;
      }
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
    tab.panel.webview.html = this.getWebviewContent(modelId, providerId, maxIterations, tab.panel);
  }

  private getNonce(): string {
    return crypto.randomBytes(16).toString('base64');
  }

  private getWebviewContent(modelId: string, providerId: string, maxIterations: number, panel: vscode.WebviewPanel): string {
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
    
    // Load external JavaScript file
    const jsPath = vscode.Uri.file(path.join(this.context.extensionPath, 'resources', 'webview.js'));
    const scriptUri = panel.webview.asWebviewUri(jsPath);
    
    return `<!DOCTYPE html>
<html lang="en" data-output-settings="${settingsJson}" data-provider-id="${providerId}" data-model-id="${modelId}">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline' ${scriptUri.scheme}://*;">
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
        // Pass configuration to external script via window object
        window.PROVIDER_ID = '${providerId}';
        window.MODEL_ID = '${modelId}';
    </script>
    <script src="${scriptUri}"></script>
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
