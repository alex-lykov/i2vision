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
      case 'applyToFile':
        await this.applyToFile(tab);
        break;
      case 'openSettings':
        await this.openSettings();
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
    // Fix common concatenated words (more comprehensive list)
    text = text.replace(/\b(Ihave|Iwill|Ineed|Letme|Let's|Thisis|Thatis|Whatis|Whatare|Iam|Youare|Weare|Theyare|Itis|Thereis|Thereare|Whatis|Whatare|Howto|Howdoes|Canyou|Cani|Letus|Dont|Cant|Wont|Isnt|Arent|Wasnt|Werent)\b/gi, (match) => {
      return match.replace(/([a-z])([A-Z])/g, '$1 $2');
    });
    // Fix lowercase word boundaries: "tothe" → "to the", "inthe" → "in the"
    text = text.replace(/\b(to|in|on|at|for|with|about|from|into|through|during|before|after|above|below|between|under|again|further|then|once|here|there|when|where|why|how|what|which|who|whom|whose|this|that|these|those|am|is|are|was|were|be|been|being|have|has|had|do|does|did|will|would|could|should|may|might|must)([a-z])/gi, '$1 $2');
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

  private async applyToFile(tab: AgentTab): Promise<void> {
    this.log('Apply to file requested');
    
    // Get the last response from history
    if (tab.history.length === 0) {
      vscode.window.showWarningMessage('No response to apply');
      return;
    }
    
    const lastInteraction = tab.history[tab.history.length - 1];
    const responseText = lastInteraction.agentResponse;
    
    // Check if there's code in the response
    const codeBlockMatch = responseText.match(/```[\s\S]*?```/);
    if (!codeBlockMatch) {
      vscode.window.showInformationMessage('No code blocks found in response to apply');
      return;
    }
    
    // Extract code (remove markdown fences)
    const code = codeBlockMatch[0].replace(/^```\w*\n?|\n?```$/g, '');
    
    // Show quick pick for action
    const options = ['Insert at Cursor', 'Replace Selection', 'Create New File'];
    const selected = await vscode.window.showQuickPick(options, {
      placeHolder: 'How would you like to apply this code?'
    });
    
    if (!selected) return;
    
    const editor = vscode.window.activeTextEditor;
    
    try {
      if (selected === 'Insert at Cursor') {
        if (!editor) {
          vscode.window.showWarningMessage('No active editor');
          return;
        }
        await editor.edit(editBuilder => {
          editBuilder.insert(editor.selection.active, code);
        });
        this.log('Code inserted at cursor');
        
      } else if (selected === 'Replace Selection') {
        if (!editor) {
          vscode.window.showWarningMessage('No active editor');
          return;
        }
        if (editor.selection.isEmpty) {
          vscode.window.showWarningMessage('No selection to replace');
          return;
        }
        await editor.edit(editBuilder => {
          editBuilder.replace(editor.selection, code);
        });
        this.log('Code replaced selection');
        
      } else if (selected === 'Create New File') {
        const fileName = await vscode.window.showInputBox({
          prompt: 'Enter file name',
          value: 'generated-code.ts'
        });
        
        if (!fileName) return;
        
        const workspaceRoot = vscode.workspace.workspaceFolders?.[0]?.uri.fsPath;
        if (!workspaceRoot) {
          vscode.window.showWarningMessage('No workspace folder open');
          return;
        }
        
        const filePath = path.join(workspaceRoot, fileName);
        const uri = vscode.Uri.file(filePath);
        await vscode.workspace.fs.writeFile(uri, Buffer.from(code, 'utf8'));
        
        const doc = await vscode.workspace.openTextDocument(uri);
        await vscode.window.showTextDocument(doc);
        this.log(`Code saved to ${filePath}`);
      }
      
      vscode.window.showInformationMessage('Code applied successfully ✓');
    } catch (error: any) {
      this.log(`Error applying code: ${error.message}`);
      vscode.window.showErrorMessage(`Failed to apply code: ${error.message}`);
    }
  }

  private async openSettings(): Promise<void> {
    this.log('Opening settings');
    
    const options = [
      { label: 'Extension Settings', description: 'Open i2-Vision settings', command: 'workbench.action.openSettings', args: ['@ext:i2vision.i2-vision-vscode'] },
      { label: 'Agent Config File', description: 'Edit code-agent.yaml', command: 'vscode.open', args: [] },
      { label: 'Default Config', description: 'View default configuration', command: 'vscode.open', args: [] }
    ];
    
    const selected = await vscode.window.showQuickPick(options, {
      placeHolder: 'Select settings to open'
    });
    
    if (!selected) return;
    
    try {
      if (selected.command === 'workbench.action.openSettings') {
        await vscode.commands.executeCommand(selected.command, ...(selected.args || []));
      } else if (selected.command === 'vscode.open') {
        // Open agent config file
        const workspaceRoot = vscode.workspace.workspaceFolders?.[0]?.uri.fsPath;
        if (!workspaceRoot) {
          vscode.window.showWarningMessage('No workspace folder open');
          return;
        }
        
        const configPath = path.join(workspaceRoot, '.vision-ai', 'code-agent.yaml');
        const uri = vscode.Uri.file(configPath);
        
        // Check if file exists, if not create it
        try {
          await vscode.workspace.fs.stat(uri);
        } catch {
          // File doesn't exist, create it from current config
          const agentConfig = this.provider.getConfig('code');
          await this.saveAgentConfig('code', agentConfig);
        }
        
        const doc = await vscode.workspace.openTextDocument(uri);
        await vscode.window.showTextDocument(doc);
        this.log(`Opened config file: ${configPath}`);
      }
    } catch (error: any) {
      this.log(`Error opening settings: ${error.message}`);
      vscode.window.showErrorMessage(`Failed to open settings: ${error.message}`);
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
        :root {
            --container-padding: 16px;
            --card-radius: 8px;
            --border-radius: 6px;
            --spacing-xs: 4px;
            --spacing-sm: 8px;
            --spacing-md: 12px;
            --spacing-lg: 16px;
            --font-size-xs: 11px;
            --font-size-sm: 12px;
            --font-size-md: 13px;
            --font-size-lg: 14px;
        }
        
        body {
            font-family: var(--vscode-font-family);
            padding: var(--container-padding);
            color: var(--vscode-foreground);
            background-color: var(--vscode-editor-background);
            margin: 0;
            font-size: var(--font-size-sm);
        }
        
        body.theme-light { --card-bg: #ffffff; --card-border: #e0e0e0; }
        body.theme-dark { --card-bg: #1e1e1e; --card-border: #404040; }
        body.theme-compact { --container-padding: 10px; }
        body.font-small { font-size: 11px; }
        body.font-medium { font-size: 13px; }
        body.font-large { font-size: 15px; }
        
        /* ===================================================================
           AGENT OUTPUT CARD - Modern Clean Design
           =================================================================== */
        
        .agent-output-card {
            border: 1px solid var(--card-border, var(--vscode-panel-border, #ccc));
            border-radius: var(--card-radius);
            background: var(--card-bg, var(--vscode-editor-background));
            margin-bottom: var(--spacing-lg);
            overflow: hidden;
            box-shadow: 0 1px 3px rgba(0,0,0,0.1);
        }
        
        .output-card-header {
            display: flex;
            justify-content: space-between;
            align-items: center;
            padding: var(--spacing-sm) var(--spacing-md);
            background: var(--vscode-editor-inactiveSelectionBackground);
            border-bottom: 1px solid var(--card-border, var(--vscode-panel-border, #ccc));
        }
        
        .header-left { display: flex; align-items: center; gap: var(--spacing-sm); }
        .header-right { display: flex; align-items: center; gap: var(--spacing-sm); }
        
        .provider-badge {
            padding: 2px 8px;
            border-radius: 4px;
            color: white;
            font-size: var(--font-size-xs);
            font-weight: 600;
            text-transform: uppercase;
            letter-spacing: 0.3px;
        }
        
        .model-name {
            font-size: var(--font-size-xs);
            color: var(--vscode-descriptionForeground);
            font-family: var(--vscode-editor-font-family);
        }
        
        .status-badge {
            display: inline-flex;
            align-items: center;
            justify-content: center;
            width: 18px;
            height: 18px;
            border-radius: 50%;
            font-size: var(--font-size-xs);
            font-weight: bold;
        }
        .status-success { background: var(--vscode-terminal-ansiGreen); color: white; }
        .status-error { background: var(--vscode-errorForeground); color: white; }
        
        .metric-badge {
            font-size: var(--font-size-xs);
            color: var(--vscode-descriptionForeground);
            font-family: var(--vscode-editor-font-family);
            padding: 1px 6px;
            background: var(--vscode-editor-background);
            border-radius: 3px;
        }
        
        /* ===================================================================
           CARD CONTENT
           =================================================================== */
        
        .output-card-content { padding: var(--spacing-md); }
        
        /* Error Section */
        .error-section {
            display: flex;
            align-items: center;
            gap: var(--spacing-sm);
            padding: var(--spacing-sm) var(--spacing-md);
            background: var(--vscode-inputValidation-errorBackground);
            border: 1px solid var(--vscode-inputValidation-errorBorder);
            border-radius: var(--border-radius);
            margin-bottom: var(--spacing-md);
        }
        .error-icon { font-size: var(--font-size-lg); color: var(--vscode-errorForeground); }
        .error-text { color: var(--vscode-errorForeground); font-size: var(--font-size-sm); }
        
        /* Reasoning Section */
        .reasoning-section {
            margin-bottom: var(--spacing-md);
            border: 1px solid var(--card-border, var(--vscode-panel-border, #ccc));
            border-radius: var(--border-radius);
            overflow: hidden;
        }
        .reasoning-section .section-header {
            display: flex;
            align-items: center;
            gap: var(--spacing-sm);
            padding: var(--spacing-sm) var(--spacing-md);
            background: var(--vscode-editor-inactiveSelectionBackground);
            cursor: pointer;
            user-select: none;
        }
        .reasoning-section .section-header:hover {
            background: var(--vscode-list-hoverBackground);
        }
        .reasoning-section .section-icon { font-size: var(--font-size-md); }
        .reasoning-section .section-label { font-weight: 600; font-size: var(--font-size-xs); color: var(--vscode-foreground); }
        .reasoning-section .section-toggle { font-size: var(--font-size-xs); margin-left: auto; }
        .reasoning-content { padding: var(--spacing-md); background: var(--vscode-editor-background); }
        .reasoning-text {
            font-size: var(--font-size-sm);
            line-height: 1.6;
            color: var(--vscode-descriptionForeground);
            font-style: italic;
            border-left: 3px solid var(--vscode-descriptionForeground);
            padding-left: var(--spacing-md);
        }
        
        /* Response Text Section */
        .response-text-section { margin-bottom: var(--spacing-md); }
        .response-text-section.collapsed .response-text {
            max-height: 120px;
            overflow: hidden;
            position: relative;
        }
        .response-text-section.collapsed .response-text::after {
            content: '';
            position: absolute;
            bottom: 0;
            left: 0;
            right: 0;
            height: 50px;
            background: linear-gradient(transparent, var(--card-bg, var(--vscode-editor-background)));
        }
        .response-text {
            line-height: 1.6;
            font-size: var(--font-size-sm);
        }
        .response-text p { margin: var(--spacing-sm) 0; }
        .response-text h1, .response-text h2, .response-text h3 {
            margin: var(--spacing-md) 0 var(--spacing-sm) 0;
            font-weight: 600;
            color: var(--vscode-foreground);
        }
        .response-text h1 { font-size: var(--font-size-lg); border-bottom: 1px solid var(--card-border); padding-bottom: var(--spacing-xs); }
        .response-text h2 { font-size: var(--font-size-md); }
        .response-text h3 { font-size: var(--font-size-sm); }
        .response-text ul { margin: var(--spacing-sm) 0; padding-left: var(--spacing-md); }
        .response-text li { margin: var(--spacing-xs) 0; }
        .response-link { color: var(--vscode-textLink-foreground); text-decoration: none; }
        .response-link:hover { text-decoration: underline; }
        
        .expand-button {
            display: inline-flex;
            align-items: center;
            gap: var(--spacing-xs);
            margin-top: var(--spacing-sm);
            padding: 4px 10px;
            background: var(--vscode-button-background);
            color: var(--vscode-button-foreground);
            border: none;
            border-radius: var(--border-radius);
            cursor: pointer;
            font-size: var(--font-size-xs);
            font-weight: 500;
            transition: background 0.2s;
        }
        .expand-button:hover { background: var(--vscode-button-hoverBackground); }
        
        /* ===================================================================
           TOOL SECTION
           =================================================================== */
        
        .tool-section {
            margin-top: var(--spacing-lg);
            border-top: 1px solid var(--card-border, var(--vscode-panel-border, #ccc));
            padding-top: var(--spacing-md);
        }
        
        .section-header {
            display: flex;
            align-items: center;
            gap: var(--spacing-sm);
            margin-bottom: var(--spacing-md);
            padding-bottom: var(--spacing-xs);
            border-bottom: 1px solid var(--card-border, var(--vscode-panel-border, #ccc));
        }
        .section-icon { font-size: var(--font-size-md); }
        .section-label { font-weight: 600; font-size: var(--font-size-xs); color: var(--vscode-foreground); text-transform: uppercase; letter-spacing: 0.5px; }
        
        .tool-calls-list { display: flex; flex-direction: column; gap: var(--spacing-sm); }
        
        .tool-call-card {
            border: 1px solid var(--card-border, var(--vscode-panel-border, #ccc));
            border-radius: var(--border-radius);
            overflow: hidden;
            background: var(--vscode-editor-background);
        }
        .tool-call-card.success { border-left: 3px solid var(--vscode-terminal-ansiGreen); }
        .tool-call-card.error { border-left: 3px solid var(--vscode-errorForeground); }
        
        .tool-call-header {
            display: flex;
            align-items: center;
            gap: var(--spacing-sm);
            padding: var(--spacing-sm) var(--spacing-md);
            cursor: pointer;
            user-select: none;
            background: var(--vscode-editor-inactiveSelectionBackground);
            transition: background 0.2s;
        }
        .tool-call-header:hover { background: var(--vscode-list-hoverBackground); }
        
        .tool-call-toggle {
            font-size: var(--font-size-xs);
            width: 14px;
            text-align: center;
            color: var(--vscode-descriptionForeground);
        }
        .tool-call-status {
            display: inline-flex;
            align-items: center;
            justify-content: center;
            width: 16px;
            height: 16px;
            border-radius: 50%;
            font-size: 10px;
            font-weight: bold;
            color: white;
        }
        .tool-call-card.success .tool-call-status { background: var(--vscode-terminal-ansiGreen); }
        .tool-call-card.error .tool-call-status { background: var(--vscode-errorForeground); }
        
        .tool-call-name { font-weight: 600; flex: 1; font-size: var(--font-size-sm); font-family: var(--vscode-editor-font-family); }
        .tool-call-meta { display: flex; gap: var(--spacing-md); font-size: var(--font-size-xs); color: var(--vscode-descriptionForeground); }
        .tool-call-duration { font-family: var(--vscode-editor-font-family); }
        
        .tool-call-body { padding: var(--spacing-md); display: block; }
        .tool-call-body[style*="display: none"] { display: none !important; }
        
        .tool-call-args, .tool-call-result, .tool-call-error { margin-top: var(--spacing-sm); font-size: var(--font-size-xs); }
        .tool-call-args code, .tool-call-result pre {
            background: var(--vscode-editor-background);
            padding: var(--spacing-sm);
            border-radius: 3px;
            font-family: var(--vscode-editor-font-family);
            font-size: var(--font-size-xs);
            display: block;
            margin-top: var(--spacing-xs);
            overflow-x: auto;
            border: 1px solid var(--card-border, var(--vscode-panel-border, #ccc));
        }
        .tool-call-result pre { white-space: pre-wrap; word-break: break-word; max-height: 300px; overflow-y: auto; }
        
        .args-label, .result-label, .error-label {
            font-weight: 600;
            color: var(--vscode-foreground);
            display: block;
            margin-bottom: var(--spacing-xs);
            font-size: var(--font-size-xs);
            text-transform: uppercase;
            letter-spacing: 0.3px;
        }
        
        /* ===================================================================
           CARD FOOTER
           =================================================================== */
        
        .output-card-footer {
            display: flex;
            justify-content: space-between;
            align-items: center;
            padding: var(--spacing-sm) var(--spacing-md);
            background: var(--vscode-editor-inactiveSelectionBackground);
            border-top: 1px solid var(--card-border, var(--vscode-panel-border, #ccc));
        }
        
        .footer-meta { display: flex; gap: var(--spacing-md); font-size: var(--font-size-xs); color: var(--vscode-descriptionForeground); }
        .meta-item { font-family: var(--vscode-editor-font-family); }
        
        .footer-actions { display: flex; gap: var(--spacing-xs); }
        
        .footer-action-btn {
            display: inline-flex;
            align-items: center;
            gap: var(--spacing-xs);
            padding: 4px 10px;
            background: var(--vscode-button-background);
            color: var(--vscode-button-foreground);
            border: none;
            border-radius: var(--border-radius);
            cursor: pointer;
            font-size: var(--font-size-xs);
            font-weight: 500;
            transition: background 0.2s;
        }
        .footer-action-btn:hover { background: var(--vscode-button-hoverBackground); }
        .footer-action-btn.settings-btn { padding: 4px 8px; }
        .footer-action-btn .action-icon { font-size: var(--font-size-md); }
        
        /* ===================================================================
           CODE FORMATTING
           =================================================================== */
        
        .code-block {
            background: var(--vscode-editor-background);
            padding: var(--spacing-md);
            border-radius: var(--border-radius);
            overflow-x: auto;
            margin: var(--spacing-sm) 0;
            border: 1px solid var(--card-border, var(--vscode-panel-border, #ccc));
            font-family: var(--vscode-editor-font-family);
            font-size: var(--font-size-xs);
            line-height: 1.5;
        }
        .inline-code {
            background: var(--vscode-editor-inactiveSelectionBackground);
            padding: 2px 6px;
            border-radius: 3px;
            font-family: var(--vscode-editor-font-family);
            font-size: 0.9em;
        }
        
        /* Syntax highlighting */
        .code-keyword { color: var(--vscode-terminal-ansiBlue); font-weight: 600; }
        .code-string { color: var(--vscode-terminal-ansiGreen); }
        .code-number { color: var(--vscode-terminal-ansiYellow); }
        .code-boolean { color: var(--vscode-terminal-ansiMagenta); font-weight: 600; }
        
        /* ===================================================================
           CONFIG AREA & MESSAGES
           =================================================================== */
        
        .config-info {
            font-size: var(--font-size-sm);
            color: var(--vscode-descriptionForeground);
            margin-bottom: var(--spacing-md);
            padding: var(--spacing-sm) var(--spacing-md);
            background: var(--vscode-editor-inactiveSelectionBackground);
            border-radius: var(--border-radius);
            border: 1px solid var(--card-border, var(--vscode-panel-border, #ccc));
        }
        
        #messages {
            min-height: 300px;
            max-height: 60vh;
            overflow-y: auto;
            margin-bottom: var(--spacing-lg);
            padding: var(--spacing-sm);
            border: 1px solid var(--vscode-panel-border, #ccc);
            border-radius: var(--border-radius);
            background: var(--vscode-editor-background);
        }
        
        .message {
            margin-bottom: var(--spacing-md);
            padding: var(--spacing-sm) var(--spacing-md);
            border-radius: var(--border-radius);
            line-height: 1.5;
            font-size: var(--font-size-sm);
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
        
        /* ===================================================================
           INPUT AREA
           =================================================================== */
        
        .input-row {
            display: flex;
            gap: var(--spacing-sm);
            align-items: center;
            position: sticky;
            bottom: 0;
            background: var(--vscode-editor-background);
            padding-top: var(--spacing-md);
        }
        
        #userInput {
            flex: 1;
            padding: 8px 12px;
            border: 1px solid var(--vscode-input-border, var(--vscode-panel-border, #ccc));
            border-radius: var(--border-radius);
            background: var(--vscode-input-background);
            color: var(--vscode-input-foreground);
            font-family: var(--vscode-font-family);
            font-size: var(--font-size-sm);
        }
        #userInput:focus { outline: 2px solid var(--vscode-focusBorder); }
        #userInput:disabled { opacity: 0.6; cursor: not-allowed; }
        
        #actionButton {
            padding: 8px 16px;
            background: var(--vscode-button-background);
            color: var(--vscode-button-foreground);
            border: none;
            border-radius: var(--border-radius);
            cursor: pointer;
            min-width: 80px;
            font-weight: 600;
            font-size: var(--font-size-sm);
            transition: background 0.2s;
        }
        #actionButton:hover { background: var(--vscode-button-hoverBackground); }
        #actionButton:disabled { opacity: 0.5; cursor: not-allowed; }
        #actionButton.stop-button { background: #dc3545; }
        #actionButton.stop-button:hover { background: #c82333; }
        
        /* ===================================================================
           UTILITIES
           =================================================================== */
        
        .text-muted { color: var(--vscode-descriptionForeground); font-style: italic; }
        
        .temporary-feedback {
            position: fixed;
            bottom: 20px;
            left: 50%;
            transform: translateX(-50%);
            background: var(--vscode-notifications-background);
            color: var(--vscode-notifications-foreground);
            padding: 8px 16px;
            border-radius: var(--border-radius);
            font-size: var(--font-size-sm);
            box-shadow: 0 2px 8px rgba(0,0,0,0.2);
            z-index: 1000;
            animation: fadeIn 0.3s ease;
        }
        .temporary-feedback.fade-out {
            animation: fadeOut 0.3s ease forwards;
        }
        @keyframes fadeIn { from { opacity: 0; transform: translateX(-50%) translateY(10px); } to { opacity: 1; transform: translateX(-50%) translateY(0); } }
        @keyframes fadeOut { from { opacity: 1; } to { opacity: 0; } }
        
        /* Progress indicator */
        .progress-indicator {
            display: flex;
            align-items: center;
            gap: var(--spacing-sm);
            padding: var(--spacing-sm) var(--spacing-md);
            background: var(--vscode-editor-inactiveSelectionBackground);
            border-radius: var(--border-radius);
            margin-bottom: var(--spacing-md);
            font-size: var(--font-size-sm);
            color: var(--vscode-descriptionForeground);
        }
        .spinner {
            width: 14px;
            height: 14px;
            border: 2px solid var(--vscode-progressBar-background);
            border-top-color: var(--vscode-foreground);
            border-radius: 50%;
            animation: spin 1s linear infinite;
        }
        @keyframes spin { to { transform: rotate(360deg); } }
        
        /* Dropdown styling */
        optgroup { font-weight: 600; color: var(--vscode-foreground); }
        optgroup[label*="Local"] { color: var(--vscode-terminal-ansiGreen); }
        optgroup[label*="Cloud"] { color: var(--vscode-terminal-ansiBlue); }
        select {
            font-size: var(--font-size-sm);
            padding: 4px 8px;
            border: 1px solid var(--vscode-input-border);
            border-radius: var(--border-radius);
            background: var(--vscode-input-background);
            color: var(--vscode-input-foreground);
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
