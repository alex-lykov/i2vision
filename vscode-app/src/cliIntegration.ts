/**
 * CLI Integration - Multi-provider LLM calls
 * 
 * This module provides HTTP integration with multiple LLM providers:
 * - Ollama (local + cloud models)
 * - DeepSeek (cloud API)
 * - 3D LLM (FreeDeepseekAPI proxy)
 *
 * UPDATED: All shell commands now use workspace root as working directory
 * UPDATED: Provider routing based on model ID
 */

import * as fs from 'fs';
import * as path from 'path';
import { exec } from 'child_process';
import { promisify } from 'util';
import * as vscode from 'vscode';
import { ProjectArchitecture, getExtensionsFromArchitecture } from './agent/tools/DomainDetector';

const execAsync = promisify(exec);

/**
 * LLM Message format
 */
export interface LLMMessage {
  role: string;
  content: string;
  tool_call_id?: string;
  tool_calls?: {
    id: string;
    type: string;
    function: {
      name: string;
      arguments: string;
    };
  }[];
  /** Internal flag for auto-generated nudge messages that should be filtered out between iterations */
  _isNudge?: boolean;
}

/**
 * LLM Tool definition
 */
export interface LLMTool {
  type: string;
  function: {
    name: string;
    description: string;
    parameters: {
      type: string;
      properties: Record<string, any>;
      required?: string[];
    };
  };
}

/**
 * LLM Options
 */
export interface LLMOptions {
  temperature?: number;
  top_p?: number;
  max_tokens?: number;
  thinking_enabled?: boolean;
  search_enabled?: boolean;
}

/**
 * Tool Call result from LLM
 */
export interface LLMToolCall {
  id: string;
  name: string;
  arguments: Record<string, any>;
}

/**
 * LLM Response with both content and tool calls
 */
export interface LLMResponse {
  content: string;
  toolCalls: LLMToolCall[];
  tokenUsage?: {
    prompt: number;
    completion: number;
    total: number;
  };
}

/**
 * LLM Chunk for streaming responses
 */
export interface LLMChunk {
  text: string;
  done: boolean;
  toolCalls?: LLMToolCall[];
  tokenUsage?: {
    prompt: number;
    completion: number;
    total: number;
  };
}

/**
 * Violation info structure
 */
export interface Violation {
  type: string;
  message: string;
  severity: string;
  component?: string;
  layer?: string;
  source?: string;
  target?: string;
  rule?: string;
}

/**
 * Discovery result structure
 */
export interface DiscoveryResult {
  projectName: string;
  version: string;
  components: ComponentInfo[];
  relationships: any[];
  layers: any[];
  violations: Violation[];
}

/**
 * Component info structure
 */
export interface ComponentInfo {
  name: string;
  type: string;
  path: string;
  layer?: string;
  dependencies?: string[];
}

/**
 * Template info structure
 */
export interface TemplateInfo {
  name: string;
  path: string;
  type: string;
  description: string;
  category: string;
  files: { path: string; content: string }[];
  variables: { name: string; description: string; required: boolean; defaultValue: string }[];
}

/**
 * File context structure
 */
export interface FileContext {
  path: string;
  filePath: string;
  name: string;
  language: string;
  content: string;
  size: number;
  lines: number;
  imports: string[];
  classes: string[];
  functions: string[];
  component?: string;
  layer?: string;
  dependencies?: string[];
}

/**
 * Search result with line-level match details
 */
export interface SearchMatch {
  line: number;
  text: string;
  snippet: string;
}

export interface SearchResult {
  path: string;
  matchCount: number;
  matches: SearchMatch[];
  matchedByName: boolean;
}

/**
 * CLI class for multi-provider LLM integration
 */
export class CLI {
  private outputChannel?: any;
  private ollamaUrl: string = 'http://localhost:11434';
  private deepSeekApiKey?: string;
  private deepSeekBaseUrl: string = 'https://api.deepseek.com';
  private threeDLlmUrl: string = 'http://localhost:9655';
  private workspaceRoot: string;
  private cliPath?: string;
  private projectArchitecture?: ProjectArchitecture;
  private fileExtensions: string[] = [];

  constructor(workspaceRoot: string, outputChannel?: any) {
    this.workspaceRoot = workspaceRoot;
    this.outputChannel = outputChannel;
    
    // Validate workspace root
    if (!this.workspaceRoot) {
      this.log('⚠️ WARNING: No workspace folder open - using current directory');
      this.workspaceRoot = process.cwd();
    } else {
      this.log(`Workspace root: ${this.workspaceRoot}`);
    }
    
    // Get CLI path from VSCode settings
    try {
      const config = vscode.workspace.getConfiguration('i2vision');
      this.cliPath = config.get<string>('cli.path');
      const configuredOllamaUrl = config.get<string>('ollamaUrl');
      if (configuredOllamaUrl) {
        this.ollamaUrl = configuredOllamaUrl;
        this.log(`Ollama URL from settings: ${this.ollamaUrl}`);
      }
      
      // Load DeepSeek API key from settings or environment
      this.deepSeekApiKey = config.get<string>('deepseek.apiKey') || process.env['DEEPSEEK_API_KEY'];
      if (this.deepSeekApiKey) {
        this.log(`DeepSeek API key configured (from ${config.get<string>('deepseek.apiKey') ? 'settings' : 'env'})`);
      } else {
        this.log(`⚠️ DeepSeek API key not configured - DeepSeek provider will not work`);
      }

      // Load 3D LLM URL from settings
      const configured3DLlmUrl = config.get<string>('3dLlmUrl');
      if (configured3DLlmUrl) {
        this.threeDLlmUrl = configured3DLlmUrl;
        this.log(`3D LLM URL from settings: ${this.threeDLlmUrl}`);
      }

      if (this.cliPath) {
        this.log(`CLI path from settings: ${this.cliPath}`);
      } else {
        // Fallback: try to load directly from project config file
        this.cliPath = this.loadCliPathFromProjectConfig();
        if (this.cliPath) {
          this.log(`CLI path from project config file: ${this.cliPath}`);
        }
      }
    } catch (error: any) {
      this.log(`Could not read CLI path from settings: ${error.message}`);
    }
    
    // Load project architecture for dynamic file extension detection
    this.loadProjectArchitecture();
  }

  /**
   * Get the workspace root directory
   */
  getWorkspaceRoot(): string {
    return this.workspaceRoot;
  }

  /**
   * Check if CLI is available
   */
  isAvailable(): boolean {
    return true;
  }

  /**
   * Load CLI path directly from .vision-ai/config/cli.yaml
   */
  private loadCliPathFromProjectConfig(): string | undefined {
    const cliConfigPath = path.join(this.workspaceRoot, '.vision-ai', 'config', 'cli.yaml');
    
    try {
      if (!fs.existsSync(cliConfigPath)) {
        return undefined;
      }
      
      const data = fs.readFileSync(cliConfigPath, 'utf8');
      const yaml = require('js-yaml');
      const yamlConfig = yaml.load(data) as any;
      
      if (yamlConfig?.cli?.path) {
        const cliPath = yamlConfig.cli.path;
        return path.isAbsolute(cliPath) 
          ? cliPath 
          : path.join(this.workspaceRoot, cliPath);
      }
    } catch (error: any) {
      this.log(`Could not load CLI config from project: ${error.message}`);
    }
    
    return undefined;
  }

  /**
   * Load project architecture from discovery cache to determine file extensions
   */
  private loadProjectArchitecture(): void {
    const cachePath = path.join(this.workspaceRoot, '.vision-ai', 'cache', 'architecture.json');
    
    try {
      if (!fs.existsSync(cachePath)) {
        this.log('Project architecture cache not found, using default file extensions');
        return;
      }
      
      const cacheContent = fs.readFileSync(cachePath, 'utf-8');
      this.projectArchitecture = JSON.parse(cacheContent) as ProjectArchitecture;
      
      // Extract file extensions from detected languages
      this.fileExtensions = getExtensionsFromArchitecture(this.projectArchitecture);
      
      this.log(`Loaded project architecture: ${this.fileExtensions.length} file extensions (primary: ${this.projectArchitecture.technologyStack?.primaryLanguage || 'unknown'})`);
    } catch (error: any) {
      this.log(`Could not load project architecture cache: ${error.message}`);
      this.fileExtensions = this.getDefaultExtensions();
    }
  }

  /**
   * Get default file extensions when architecture cache is not available
   */
  private getDefaultExtensions(): string[] {
    return ['.ts', '.tsx', '.js', '.jsx', '.kt', '.kts', '.java', '.xml', '.json', '.yaml', '.yml', '.css', '.scss', '.less', '.html', '.htm', '.md', '.txt', '.gradle', '.properties', '.svg'];
  }

  /**
   * Log a message to the output channel
   */
  private log(message: string): void {
    const timestamp = new Date().toLocaleTimeString();
    const formatted = `[${timestamp}] [CLI] ${message}`;
    if (this.outputChannel) {
      this.outputChannel.appendLine(formatted);
    }
    console.log(formatted);
  }

  /**
   * Check if model is a DeepSeek model
   */
  private isDeepSeekModel(modelId: string): boolean {
    return modelId.startsWith('deepseek-') ||
           modelId === 'deepseek-chat' ||
           modelId === 'deepseek-coder' ||
           modelId === 'deepseek-reasoner';
  }

  /**
   * Check if model is a 3D LLM model
   */
  private is3DLlmModel(modelId: string): boolean {
    return modelId === 'deepseek-chat' || modelId === 'deepseek-web-v3' || modelId === '3d-llm';
  }

  /**
   * Check if a model is likely an Ollama local model (heuristic)
   */
  private isOllamaModel(modelId: string): boolean {
    return !this.isDeepSeekModel(modelId) && !this.is3DLlmModel(modelId);
  }

  /**
   * Call LLM through appropriate provider (Ollama, DeepSeek, or 3D LLM)
   * Returns both content and tool calls
   *
   * @param stream - If true, returns AsyncGenerator<LLMChunk> for streaming
   */
  async callLLM(
    modelId: string,
    messages: LLMMessage[],
    options?: LLMOptions,
    tools?: LLMTool[],
    stream: boolean = false,
    provider?: string
  ): Promise<LLMResponse | AsyncGenerator<LLMChunk>> {
    const resolvedProvider = provider || (this.isDeepSeekModel(modelId) ? 'deepseek' : (this.isOllamaModel(modelId) ? 'ollama' : '3d-llm'));
    const providerName = resolvedProvider === '3d-llm' ? '3D LLM' : (resolvedProvider === 'deepseek' ? 'DeepSeek' : 'Ollama');
    this.log(`[LLM] ${modelId} | ${providerName} | ${messages.length} msg | ${tools?.length || 0} tools | stream=${stream}`);

    // Route to appropriate provider (explicit provider overrides model ID inference)
    if (resolvedProvider === '3d-llm') {
      return this.call3DLlm(modelId, messages, options, tools, stream);
    } else if (resolvedProvider === 'deepseek') {
      return this.callDeepSeek(modelId, messages, options, tools, stream);
    } else {
      return this.callOllama(modelId, messages, options, tools, stream);
    }
  }

  /**
   * Call Ollama API (local or cloud)
   */
  private async callOllama(
    modelId: string,
    messages: LLMMessage[],
    options?: LLMOptions,
    tools?: LLMTool[],
    stream: boolean = false
  ): Promise<LLMResponse | AsyncGenerator<LLMChunk>> {
    const startTime = Date.now();

    try {
      const body: any = {
        model: modelId,
        messages,
        stream: stream,
        options: {
          temperature: options?.temperature || 0.2,
          top_p: options?.top_p || 0.95,
          num_predict: options?.max_tokens || 4096
        }
      };

      // Tools are embedded in system prompt by AgentBridge for 3D LLM.
      // The proxy handles tool calling through text parsing, not the `tools` array.

      this.log(`POST ${this.ollamaUrl}/api/chat`);

      const res = await fetch(`${this.ollamaUrl}/api/chat`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body)
      });

      const elapsed = Date.now() - startTime;
      this.log(`Response: ${res.status} ${res.statusText} (${elapsed}ms)`);

      if (!res.ok) {
        const errorText = await res.text();
        this.log(`Error body: ${errorText.substring(0, 500)}`);
        throw new Error(`Ollama HTTP error: ${res.status} ${res.statusText}`);
      }

      if (stream) {
        return this.streamOllamaResponse(res, startTime);
      } else {
        const data = await res.json() as any;
        
        const content = data.message?.content || '';
        const toolCallsData = data.message?.tool_calls || [];
        
        const toolCalls: LLMToolCall[] = toolCallsData.map((tc: any) => {
          let args = tc.function?.arguments || {};
          if (typeof args === 'string') {
            try {
              args = JSON.parse(args);
            } catch (e) {
              this.log(`Warning: Could not parse tool arguments: ${args}`);
              args = {};
            }
          }
          return {
            id: tc.id || tc.function?.id || `call_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`,
            name: tc.function?.name || '',
            arguments: args
          };
        });
        
        this.log(`Response: ${content.length} chars, ${toolCalls.length} tool calls`);
        this.log(`=== LLM CALL END ===`);
        
        return { content, toolCalls };
      }
    } catch (error: any) {
      const elapsed = Date.now() - startTime;
      this.log(`Error after ${elapsed}ms: ${error.message}`);
      this.log(`=== LLM CALL FAILED ===`);
      
      return {
        content: `Error: LLM call failed - ${error.message}`,
        toolCalls: []
      };
    }
  }

  /**
   * Call DeepSeek API
   */
  private async callDeepSeek(
    modelId: string,
    messages: LLMMessage[],
    options?: LLMOptions,
    tools?: LLMTool[],
    stream: boolean = false
  ): Promise<LLMResponse | AsyncGenerator<LLMChunk>> {
    const startTime = Date.now();

    if (!this.deepSeekApiKey) {
      throw new Error('DeepSeek API key not configured. Set DEEPSEEK_API_KEY environment variable or VSCode setting.');
    }

    try {
      // Convert messages to DeepSeek format
      const deepSeekMessages = messages.map(msg => ({
        role: msg.role,
        content: msg.content,
        tool_call_id: msg.tool_call_id,
        tool_calls: msg.tool_calls
      }));

      const body: any = {
        model: modelId,
        messages: deepSeekMessages,
        stream: stream,
        temperature: options?.temperature || 0.2,
        top_p: options?.top_p || 0.95,
        max_tokens: options?.max_tokens || 4096
      };

      // Forward thinking/search toggles (supported by DeepSeek's chat completions API)
      if (options?.thinking_enabled !== undefined) {
        body.thinking_enabled = options.thinking_enabled;
      }
      if (options?.search_enabled !== undefined) {
        body.search_enabled = options.search_enabled;
      }

      // Skip tools array for 3D LLM — tool definitions are embedded in system prompt by AgentBridge.
      // The proxy parses TOOL_CALL: patterns from DeepSeek's text response.

      this.log(`POST ${this.deepSeekBaseUrl}/chat/completions`);

      const res = await fetch(`${this.deepSeekBaseUrl}/chat/completions`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Authorization': `Bearer ${this.deepSeekApiKey}`
        },
        body: JSON.stringify(body)
      });

      const elapsed = Date.now() - startTime;
      this.log(`Response: ${res.status} ${res.statusText} (${elapsed}ms)`);

      if (!res.ok) {
        const errorText = await res.text();
        this.log(`Error body: ${errorText.substring(0, 500)}`);
        
        // Provide helpful message for common errors
        let errorMessage = `DeepSeek API error: ${res.status} ${res.statusText}`;
        
        if (res.status === 402) {
          errorMessage = 'DeepSeek API error: 402 Payment Required - Your API key has insufficient credits. Please add credits at https://platform.deepseek.com/';
        } else if (res.status === 401) {
          errorMessage = 'DeepSeek API error: 401 Unauthorized - Invalid API key. Check your DEEPSEEK_API_KEY environment variable or VSCode settings.';
        }
        
        throw new Error(errorMessage);
      }

      if (stream) {
        return this.streamDeepSeekResponse(res, startTime);
      } else {
        const data = await res.json() as any;
        
        const choice = data.choices?.[0];
        const content = choice?.message?.content || '';
        const toolCallsData = choice?.message?.tool_calls || [];
        
        const toolCalls: LLMToolCall[] = toolCallsData.map((tc: any) => {
          let args = tc.function?.arguments || {};
          
          // Special handling for apply_edits - if args is already an object with edits array, don't parse it
          if (typeof args === 'string') {
            try {
              args = JSON.parse(args);
            } catch (e) {
              this.log(`Warning: Could not parse tool arguments: ${args}`);
              args = {};
            }
          }
          // If this is apply_edits with an edits array, preserve the structure
          else if (tc.function?.name === 'apply_edits' && typeof args === 'object' && args.edits) {
            // args is already in the correct format, don't modify it
          }
          // For other tools, ensure args is an object
          else if (typeof args !== 'object' || args === null) {
            args = {};
          }
          
          // Debug logging to verify the structure
          if (tc.function?.name === 'apply_edits') {
            this.log(`[DEBUG] apply_edits args type: ${typeof args}, has edits: ${Array.isArray(args.edits)}`);
            if (Array.isArray(args.edits)) {
              this.log(`[DEBUG] apply_edits has ${args.edits.length} edits`);
            }
          }
          
          return {
            id: tc.id || `call_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`,
            name: tc.function?.name || '',
            arguments: args
          };
        });
        
        this.log(`Response: ${content.length} chars, ${toolCalls.length} tool calls`);
        this.log(`=== LLM CALL END ===`);
        
        return { content, toolCalls };
      }
    } catch (error: any) {
      const elapsed = Date.now() - startTime;
      this.log(`Error after ${elapsed}ms: ${error.message}`);
      this.log(`=== LLM CALL FAILED ===`);
      
      return {
        content: `Error: LLM call failed - ${error.message}`,
        toolCalls: []
      };
    }
  }

  /**
   * Call 3D LLM proxy API (FreeDeepseekAPI)
   */
  /** Sanitize messages before sending to 3D LLM proxy to prevent format contamination */
  private sanitizeMessages(messages: LLMMessage[]): LLMMessage[] {
    return messages.map(m => {
      if (!m.content || typeof m.content !== 'string') return m;
      let content = m.content;
      // Remove XML tags that teach the model wrong format
      content = content.replace(/<\/?file_action\b[^>]*>/gi, '');
      content = content.replace(/<\/?action\b[^>]*>/gi, '');
      content = content.replace(/<\/?invoke\b[^>]*>/gi, '');
      content = content.replace(/<\/?parameter\b[^>]*>/gi, '');
      content = content.replace(/<\/?DSML\b[^>]*>/gi, '');
      // Remove markdown code blocks containing tool calls
      content = content.replace(/```(?:json)?\s*\n?\s*\{\s*"name"[\s\S]*?```/g, '');
      // Remove "Calling:" patterns
      content = content.replace(/Calling:\s*\w+\s*\n?\s*```[\s\S]*?```/gi, '');
      // Clean up multiple consecutive blank lines
      content = content.replace(/\n{3,}/g, '\n\n');
      return { ...m, content };
    });
  }

  private async call3DLlm(
    modelId: string,
    messages: LLMMessage[],
    options?: LLMOptions,
    tools?: LLMTool[],
    stream: boolean = false
  ): Promise<LLMResponse | AsyncGenerator<LLMChunk>> {
    const startTime = Date.now();

    try {
      // Sanitize messages before sending to prevent XML/markdown contamination
      const sanitizedMessages = this.sanitizeMessages(messages);
      const body: any = {
        model: modelId,
        messages: sanitizedMessages,
        stream: stream,
        temperature: options?.temperature || 0.2,
        top_p: options?.top_p || 0.95,
        max_tokens: options?.max_tokens || 4096
      };

      // Forward thinking/search toggle if specified (supported by DeepSeek Web API proxy)
      if (options?.thinking_enabled !== undefined) {
        body.thinking_enabled = options.thinking_enabled;
      }
      if (options?.search_enabled !== undefined) {
        body.search_enabled = options.search_enabled;
      }

      // Skip tools array — proxy handles tool calling via text parsing from system prompt

      this.log(`POST ${this.threeDLlmUrl}/v1/chat/completions`);

      const res = await fetch(`${this.threeDLlmUrl}/v1/chat/completions`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body)
      });

      const elapsed = Date.now() - startTime;
      this.log(`Response: ${res.status} ${res.statusText} (${elapsed}ms)`);

      if (!res.ok) {
        const errorText = await res.text();
        this.log(`Error body: ${errorText.substring(0, 500)}`);
        throw new Error(`3D LLM API error: ${res.status} ${res.statusText}`);
      }

      if (stream) {
        return this.stream3DLlmResponse(res, startTime);
      } else {
        const data = await res.json() as any;

        const choice = data.choices?.[0];
        let content = choice?.message?.content || '';
        let toolCallsData = choice?.message?.tool_calls || [];

        // Fallback: if proxy returned text with embedded tool calls but no tool_calls array
        if (toolCallsData.length === 0 && content) {
          // Try 1: Parse <file_action> XML format (DeepSeek v4-pro specific format)
          if (toolCallsData.length === 0) {
            const fileActionPattern = /<file_action>\s*<action>([^<]+)<\/action>(.*?)<\/file_action>/gs;
            let faMatch;
            while ((faMatch = fileActionPattern.exec(content)) !== null) {
              try {
                const toolName = faMatch[1].trim();
                const innerContent = faMatch[2];
                
                // Extract parameters
                const args: any = {};
                const paramPattern = /<([a-zA-Z_][a-zA-Z0-9_]*)\s*>(.*?)<\/\1\s*>/gs;
                let pmMatch;
                while ((pmMatch = paramPattern.exec(innerContent)) !== null) {
                  const key = pmMatch[1].trim();
                  const value = pmMatch[2].trim();
                  if (key && key !== 'action') {
                    args[key] = value;
                  }
                }
                
                toolCallsData.push({
                  id: `call_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`,
                  type: 'function',
                  function: {
                    name: toolName,
                    arguments: JSON.stringify(args)
                  }
                });
              } catch (e: any) {}
            }
          }

          // Try 2: Parse JSON objects inside markdown code blocks (most common DeepSeek format)
          const codeBlockPattern = /```(?:json)?\s*\n?([\s\S]*?)```/g;
          let cbMatch;
          while ((cbMatch = codeBlockPattern.exec(content)) !== null) {
            try {
              const blockContent = cbMatch[1].trim();
              // Try parsing the whole block as a single tool call
              const toolCallObj = JSON.parse(blockContent);
              if (toolCallObj.name && typeof toolCallObj.name === 'string') {
                // Special handling for apply_edits tool to preserve nested structure
                const toolName = toolCallObj.name;
                let toolArgs = toolCallObj.arguments || {};
                
                // If arguments is a string, try to parse it as JSON
                if (typeof toolArgs === 'string') {
                  try {
                    toolArgs = JSON.parse(toolArgs);
                  } catch (parseError) {
                    // Keep as string if parsing fails
                  }
                }
                
                // Special handling for tools with nested structures
                let finalArgs = toolArgs;
                if (toolName === 'apply_edits' && typeof toolArgs === 'object' && toolArgs.edits) {
                  // Preserve the edits array structure - don't double-stringify
                  finalArgs = toolArgs;
                } else if (typeof toolArgs === 'object') {
                  // For other objects, stringify to maintain compatibility
                  finalArgs = JSON.stringify(toolArgs);
                }
                
                toolCallsData.push({
                  id: `call_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`,
                  type: 'function',
                  function: {
                    name: toolName,
                    arguments: finalArgs
                  }
                });
              }
              // Also try if it's an array of tool calls
              else if (Array.isArray(toolCallObj)) {
                for (const tc of toolCallObj) {
                  if (tc.name && typeof tc.name === 'string') {
                    // Special handling for apply_edits tool
                    const toolName = tc.name;
                    let toolArgs = tc.arguments || {};
                    
                    if (typeof toolArgs === 'string') {
                      try {
                        toolArgs = JSON.parse(toolArgs);
                      } catch (parseError) {
                        // Keep as string if parsing fails
                      }
                    }
                    
                    // Special handling for tools with nested structures
                    let finalArgs = toolArgs;
                    if (toolName === 'apply_edits' && typeof toolArgs === 'object' && toolArgs.edits) {
                      // Preserve the edits array structure - don't double-stringify
                      finalArgs = toolArgs;
                    } else if (typeof toolArgs === 'object') {
                      // For other objects, stringify to maintain compatibility
                      finalArgs = JSON.stringify(toolArgs);
                    }
                    
                    toolCallsData.push({
                      id: `call_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`,
                      type: 'function',
                      function: {
                        name: toolName,
                        arguments: finalArgs
                      }
                    });
                  }
                }
              }
            } catch (e: any) {
              this.log(`Warning: Could not parse tool call from code block: ${e.message}`);
            }
          }

          // Try 2: Extract JSON objects by balancing braces (handles nested objects)
          if (toolCallsData.length === 0) {
            const lines = content.split('\n');
            for (let i = 0; i < lines.length; i++) {
              const line = lines[i].trim();
              if (line === '{' || line.startsWith('{')) {
                // Try to extract a complete JSON object starting at this line
                let braceCount = 0;
                let jsonStr = '';
                for (let j = i; j < lines.length; j++) {
                  for (const char of lines[j]) {
                    if (char === '{') braceCount++;
                    if (char === '}') braceCount--;
                    jsonStr += char;
                    if (braceCount === 0 && jsonStr.includes('"name"')) {
                      try {
                        const toolCallObj = JSON.parse(jsonStr);
                        if (toolCallObj.name && typeof toolCallObj.name === 'string') {
                          // Special handling for tools with nested structures
                          let toolArgs = toolCallObj.arguments || {};
                          let finalArgs = toolArgs;
                          
                          if (toolCallObj.name === 'apply_edits' && typeof toolArgs === 'object' && toolArgs.edits) {
                            // Preserve the edits array structure - don't double-stringify
                            finalArgs = toolArgs;
                          } else if (typeof toolArgs === 'object') {
                            // For other objects, stringify to maintain compatibility
                            finalArgs = JSON.stringify(toolArgs);
                          } else {
                            finalArgs = JSON.stringify(toolArgs);
                          }
                          
                          toolCallsData.push({
                            id: `call_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`,
                            type: 'function',
                            function: {
                              name: toolCallObj.name,
                              arguments: finalArgs
                            }
                          });
                          i = j; // Skip parsed lines
                          break;
                        }
                      } catch (e: any) {}
                    }
                  }
                  if (toolCallsData.length > 0) break;
                }
              }
            }
          }

          // Try 3: Parse "Calling:" format
          if (toolCallsData.length === 0) {
            const callingPattern = /Calling:\s*(\w+)\s*\n?\s*```(?:json)?\s*\n?([\s\S]*?)```|Calling:\s*(\w+)\s*\n?\s*(\{[\s\S]*?\})/gi;
            let cmatch;
            while ((cmatch = callingPattern.exec(content)) !== null) {
              try {
                const toolName = cmatch[1] || cmatch[3];
                const jsonStr = (cmatch[2] || cmatch[4]).trim();
                const args = JSON.parse(jsonStr);
                if (toolName) {
                  // Special handling for tools with nested structures
                  let finalArgs = args || {};
                  
                  if (toolName === 'apply_edits' && typeof args === 'object' && args.edits) {
                    // Preserve the edits array structure - don't double-stringify
                    finalArgs = args;
                  } else if (typeof args === 'object') {
                    // For other objects, stringify to maintain compatibility
                    finalArgs = JSON.stringify(args);
                  } else {
                    finalArgs = JSON.stringify(args);
                  }
                  
                  toolCallsData.push({
                    id: `call_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`,
                    type: 'function',
                    function: {
                      name: toolName,
                      arguments: finalArgs
                    }
                  });
                }
              } catch (e: any) {}
            }
          }
          
          // Try 4: Fallback for LLM outputs that contain tool parameters without name/arguments wrapper
          if (toolCallsData.length === 0) {
            const inlineJsonPattern = /\{["']path["']\s*:\s*["'][^"']+["'][^}]*["']edits["']\s*:\s*\[/g;
            let jsonMatch;
            while ((jsonMatch = inlineJsonPattern.exec(content)) !== null) {
              try {
                // Extract the JSON object
                let braceCount = 0;
                let jsonStr = '';
                let startPos = jsonMatch.index;
                
                // Find the complete JSON object starting from the match
                for (let i = startPos; i < content.length; i++) {
                  const char = content[i];
                  if (char === '{') braceCount++;
                  if (char === '}') braceCount--;
                  jsonStr += char;
                  if (braceCount === 0) break;
                }
                
                if (jsonStr.trim()) {
                  const toolParams = JSON.parse(jsonStr);
                  
                  // Infer tool name based on parameter structure
                  let inferredToolName: string | null = null;
                  
                  if (toolParams.path && Array.isArray(toolParams.edits)) {
                    inferredToolName = 'apply_edits';
                  } else if (toolParams.path && toolParams.recursive !== undefined) {
                    inferredToolName = 'list_directory';
                  } else if (toolParams.command) {
                    inferredToolName = 'run_terminal';
                  } else if (toolParams.pattern) {
                    inferredToolName = 'search_files';
                  }
                  
                  if (inferredToolName) {
                    // Special handling for tools with nested structures
                    let finalArgs = toolParams;
                    
                    if (inferredToolName === 'apply_edits' && typeof toolParams === 'object' && toolParams.edits) {
                      // Preserve the edits array structure - don't double-stringify
                      finalArgs = toolParams;
                    } else if (typeof toolParams === 'object') {
                      // For other objects, stringify to maintain compatibility
                      finalArgs = JSON.stringify(toolParams);
                    } else {
                      finalArgs = JSON.stringify(toolParams);
                    }
                    
                    toolCallsData.push({
                      id: `call_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`,
                      type: 'function',
                      function: {
                        name: inferredToolName,
                        arguments: finalArgs
                      }
                    });
                    
                    this.log(`Fallback tool call parsing: inferred ${inferredToolName} from parameters`);
                  }
                }
              } catch (e: any) {
                this.log(`Warning: Could not parse fallback tool call: ${e.message}`);
              }
            }
          }
        }

        const toolCalls: LLMToolCall[] = toolCallsData.map((tc: any) => {
          let args = tc.function?.arguments || {};
          if (typeof args === 'string') {
            try {
              args = JSON.parse(args);
            } catch (e) {
              this.log(`Warning: Could not parse tool arguments: ${args}`);
              args = {};
            }
          }
          return {
            id: tc.id || `call_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`,
            name: tc.function?.name || '',
            arguments: args
          };
        });

        this.log(`Response: ${content.length} chars, ${toolCalls.length} tool calls`);
        this.log(`=== LLM CALL END ===`);

        const tokenUsage = data.usage?.prompt_tokens !== undefined ? {
          prompt: data.usage.prompt_tokens,
          completion: data.usage.completion_tokens,
          total: data.usage.total_tokens
        } : undefined;

        return { content, toolCalls, tokenUsage };
      }
    } catch (error: any) {
      const elapsed = Date.now() - startTime;
      this.log(`Error after ${elapsed}ms: ${error.message}`);
      this.log(`=== LLM CALL FAILED ===`);

      return {
        content: `Error: LLM call failed - ${error.message}`,
        toolCalls: []
      };
    }
  }

  /**
   * Extract tool calls from raw text using multiple parsing strategies.
   * Used as a fallback when the LLM provider returns inline tool calls
   * that were not parsed into the standard tool_calls array.
   */
  private extractToolCallsFromText(text: string): LLMToolCall[] {
    const toolCalls: LLMToolCall[] = [];

    // Strategy 1: Parse <file_action> XML format (DeepSeek v4-pro specific)
    const fileActionPattern = /<file_action>\s*<action>([^<]+)<\/action>(.*?)<\/file_action>/gs;
    let faMatch;
    while ((faMatch = fileActionPattern.exec(text)) !== null) {
      try {
        const toolName = faMatch[1].trim();
        const innerContent = faMatch[2];
        const args: any = {};
        const paramPattern = /<([a-zA-Z_][a-zA-Z0-9_]*)\s*>(.*?)<\/\1\s*>/gs;
        let pmMatch;
        while ((pmMatch = paramPattern.exec(innerContent)) !== null) {
          const key = pmMatch[1].trim();
          const value = pmMatch[2].trim();
          if (key && key !== 'action') {
            args[key] = value;
          }
        }
        toolCalls.push({
          id: `call_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`,
          name: toolName,
          arguments: args
        });
      } catch (e: any) {}
    }
    if (toolCalls.length > 0) return toolCalls;

    // Strategy 2: JSON objects inside markdown code blocks
    const codeBlockPattern = /```(?:json)?\s*\n?([\s\S]*?)```/g;
    let cbMatch;
    while ((cbMatch = codeBlockPattern.exec(text)) !== null) {
      try {
        const blockContent = cbMatch[1].trim();
        const toolCallObj = JSON.parse(blockContent);
        if (toolCallObj.name && typeof toolCallObj.name === 'string') {
          toolCalls.push({
            id: `call_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`,
            name: toolCallObj.name,
            arguments: toolCallObj.arguments || {}
          });
        } else if (Array.isArray(toolCallObj)) {
          for (const tc of toolCallObj) {
            if (tc.name && typeof tc.name === 'string') {
              toolCalls.push({
                id: `call_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`,
                name: tc.name,
                arguments: tc.arguments || {}
              });
            }
          }
        }
      } catch (e: any) {}
    }

    if (toolCalls.length > 0) return toolCalls;

    // Strategy 3: Balance-brace JSON extraction (handles nested objects)
    const lines = text.split('\n');
    for (let i = 0; i < lines.length; i++) {
      const line = lines[i].trim();
      if (line === '{' || line.startsWith('{')) {
        let braceCount = 0;
        let jsonStr = '';
        for (let j = i; j < lines.length; j++) {
          for (const char of lines[j]) {
            if (char === '{') braceCount++;
            if (char === '}') braceCount--;
            jsonStr += char;
            if (braceCount === 0 && jsonStr.includes('"name"')) {
              try {
                const toolCallObj = JSON.parse(jsonStr);
                if (toolCallObj.name && typeof toolCallObj.name === 'string') {
                  toolCalls.push({
                    id: `call_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`,
                    name: toolCallObj.name,
                    arguments: toolCallObj.arguments || {}
                  });
                  i = j; // Skip parsed lines
                  break;
                }
              } catch (e: any) {}
            }
          }
          if (toolCalls.length > 0) break;
        }
      }
    }

    if (toolCalls.length > 0) return toolCalls;

    // Strategy 4: "Calling:" format
    const callingPattern = /Calling:\s*(\w+)\s*\n?\s*```(?:json)?\s*\n?([\s\S]*?)```|Calling:\s*(\w+)\s*\n?\s*(\{[\s\S]*?\})/gi;
    let cmatch;
    while ((cmatch = callingPattern.exec(text)) !== null) {
      try {
        const toolName = cmatch[1] || cmatch[3];
        const jsonStr = (cmatch[2] || cmatch[4]).trim();
        const args = JSON.parse(jsonStr);
        if (toolName) {
          toolCalls.push({
            id: `call_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`,
            name: toolName,
            arguments: args || {}
          });
        }
      } catch (e: any) {}
    }

    if (toolCalls.length > 0) return toolCalls;

    // Strategy 5: Fallback for LLM outputs that contain tool parameters without name/arguments wrapper
    const inlineJsonPattern = /\{["']path["']\s*:\s*["'][^"']+["'][^}]*["']edits["']\s*:\s*\[/g;
    let jsonMatch;
    while ((jsonMatch = inlineJsonPattern.exec(text)) !== null) {
      try {
        // Extract the complete JSON object
        let braceCount = 0;
        let jsonStr = '';
        let startPos = jsonMatch.index;
        
        // Find the complete JSON object starting from the match
        for (let i = startPos; i < text.length; i++) {
          const char = text[i];
          if (char === '{') braceCount++;
          if (char === '}') braceCount--;
          jsonStr += char;
          if (braceCount === 0) break;
        }
        
        if (jsonStr.trim()) {
          const toolParams = JSON.parse(jsonStr);
          
          // Infer tool name based on parameter structure
          let inferredToolName: string | null = null;
          
          if (toolParams.path && Array.isArray(toolParams.edits)) {
            inferredToolName = 'apply_edits';
          } else if (toolParams.path && toolParams.recursive !== undefined) {
            inferredToolName = 'list_directory';
          } else if (toolParams.command) {
            inferredToolName = 'run_terminal';
          } else if (toolParams.pattern) {
            inferredToolName = 'search_files';
          }
          
          if (inferredToolName) {
            toolCalls.push({
              id: `call_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`,
              name: inferredToolName,
              arguments: toolParams
            });
          }
        }
      } catch (e: any) {
        // Silent catch for parsing errors
      }
    }

    return toolCalls;
  }

  /**
   * Stream 3D LLM response
   */
  private async *stream3DLlmResponse(
    res: Response,
    startTime: number
  ): AsyncGenerator<LLMChunk> {
    try {
      const reader = res.body?.getReader();
      if (!reader) {
        throw new Error('Response body is null');
      }

      const decoder = new TextDecoder('utf-8');
      let buffer = '';
      const toolCalls: LLMToolCall[] = [];
      let promptTokens = 0;
      let completionTokens = 0;
      let accumulatedText = ''; // Track all text for post-stream inline parsing

      while (true) {
        const { done, value } = await reader.read();

        if (done) {
          this.log(`Stream complete (${Date.now() - startTime}ms)`);
          
          // Fallback: if no tool_calls in SSE deltas, try to extract from accumulated text.
          // The proxy may have returned inline JSON that its own parseToolCall couldn't match.
          if (toolCalls.length === 0 && accumulatedText) {
            const extracted = this.extractToolCallsFromText(accumulatedText);
            if (extracted.length > 0) {
              this.log(`Post-stream fallback: extracted ${extracted.length} tool calls from accumulated text`);
              toolCalls.push(...extracted);
            }
          }
          
          const finalChunk: LLMChunk = { text: '', done: true, toolCalls: toolCalls.length > 0 ? toolCalls : undefined };
          if (promptTokens > 0 || completionTokens > 0) {
            (finalChunk as any).tokenUsage = {
              prompt: promptTokens,
              completion: completionTokens,
              total: promptTokens + completionTokens
            };
          }
          yield finalChunk;
          break;
        }

        const decoded = decoder.decode(value, { stream: true });
        buffer += decoded;
        const lines = buffer.split('\n');
        buffer = lines.pop() || '';

        for (const line of lines) {
          if (!line.trim() || line === 'data: [DONE]') continue;

          try {
            const chunkStr = line.startsWith('data: ') ? line.slice(6) : line;
            const chunk = JSON.parse(chunkStr) as any;

            const choice = chunk.choices?.[0];
            const delta = choice?.delta?.content || '';

            if (delta) {
              accumulatedText += delta;
              yield { text: delta, done: false };
            }

            if (chunk.usage?.prompt_tokens) {
              promptTokens = chunk.usage.prompt_tokens;
            }
            if (chunk.usage?.completion_tokens) {
              completionTokens = chunk.usage.completion_tokens;
            }

            if (choice?.delta?.tool_calls) {
              const newToolCalls: LLMToolCall[] = choice.delta.tool_calls.map((tc: any) => {
                let args = tc.function?.arguments || {};
                if (typeof args === 'string') {
                  try { args = JSON.parse(args); } catch (e) { args = {}; }
                }
                return {
                  id: tc.id || `call_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`,
                  name: tc.function?.name || '',
                  arguments: args
                };
              });
              toolCalls.push(...newToolCalls);
            }
          } catch (e: any) {
            this.log(`Warning: Could not parse chunk: ${line.substring(0, 100)}`);
          }
        }
      }
    } catch (error: any) {
      this.log(`Streaming error: ${error.message}`);
      yield { text: `Error: ${error.message}`, done: true };
    }
  }

  /**
   * Stream Ollama response
   */
  private async *streamOllamaResponse(
    res: Response,
    startTime: number
  ): AsyncGenerator<LLMChunk> {
    try {
      const reader = res.body?.getReader();
      if (!reader) {
        throw new Error('Response body is null');
      }

      const decoder = new TextDecoder('utf-8');
      let buffer = '';
      const toolCalls: LLMToolCall[] = [];
      let promptTokens = 0;
      let completionTokens = 0;

      while (true) {
        const { done, value } = await reader.read();
        
        if (done) {
          this.log(`Stream complete (${Date.now() - startTime}ms)`);
          const finalChunk: LLMChunk = { text: '', done: true, toolCalls: toolCalls.length > 0 ? toolCalls : undefined };
          // Include token usage if available
          if (promptTokens > 0 || completionTokens > 0) {
            (finalChunk as any).tokenUsage = {
              prompt: promptTokens,
              completion: completionTokens,
              total: promptTokens + completionTokens
            };
          }
          yield finalChunk;
          break;
        }

        // Decode with proper UTF-8 handling
        const decoded = decoder.decode(value, { stream: true });
        buffer += decoded;
        const lines = buffer.split('\n');
        buffer = lines.pop() || '';

        for (const line of lines) {
          if (!line.trim()) continue;

          try {
            const chunk = JSON.parse(line) as any;
            const delta = chunk.message?.content || '';
            
            if (delta) {
              yield { text: delta, done: false };
            }

            // Track token usage from final chunk (Ollama includes this in the last chunk)
            if (chunk.prompt_eval_count) {
              promptTokens = chunk.prompt_eval_count;
            }
            if (chunk.eval_count) {
              completionTokens = chunk.eval_count;
            }

            if (chunk.message?.tool_calls) {
              const newToolCalls: LLMToolCall[] = chunk.message.tool_calls.map((tc: any) => {
                let args = tc.function?.arguments || {};
                if (typeof args === 'string') {
                  try { args = JSON.parse(args); } catch (e) { args = {}; }
                }
                return {
                  id: tc.id || tc.function?.id || `call_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`,
                  name: tc.function?.name || '',
                  arguments: args
                };
              });
              toolCalls.push(...newToolCalls);
            }
          } catch (e: any) {
            this.log(`Warning: Could not parse chunk: ${line.substring(0, 100)}`);
          }
        }
      }
    } catch (error: any) {
      this.log(`Streaming error: ${error.message}`);
      yield { text: `Error: ${error.message}`, done: true };
    }
  }

  /**
   * Stream DeepSeek response
   */
  private async *streamDeepSeekResponse(
    res: Response,
    startTime: number
  ): AsyncGenerator<LLMChunk> {
    try {
      const reader = res.body?.getReader();
      if (!reader) {
        throw new Error('Response body is null');
      }

      const decoder = new TextDecoder('utf-8');
      let buffer = '';
      const toolCalls: LLMToolCall[] = [];
      let promptTokens = 0;
      let completionTokens = 0;

      while (true) {
        const { done, value } = await reader.read();
        
        if (done) {
          this.log(`Stream complete (${Date.now() - startTime}ms)`);
          const finalChunk: LLMChunk = { text: '', done: true, toolCalls: toolCalls.length > 0 ? toolCalls : undefined };
          // Include token usage from usage data if available
          if (promptTokens > 0 || completionTokens > 0) {
            (finalChunk as any).tokenUsage = {
              prompt: promptTokens,
              completion: completionTokens,
              total: promptTokens + completionTokens
            };
          }
          yield finalChunk;
          break;
        }

        // Decode with proper UTF-8 handling
        const decoded = decoder.decode(value, { stream: true });
        buffer += decoded;
        const lines = buffer.split('\n');
        buffer = lines.pop() || '';

        for (const line of lines) {
          if (!line.trim() || line === 'data: [DONE]') continue;

          try {
            const chunkStr = line.startsWith('data: ') ? line.slice(6) : line;
            const chunk = JSON.parse(chunkStr) as any;
            
            const choice = chunk.choices?.[0];
            const delta = choice?.delta?.content || '';
            
            if (delta) {
              yield { text: delta, done: false };
            }

            // Track token usage from final chunk (DeepSeek includes this in the last chunk)
            if (chunk.usage?.prompt_tokens) {
              promptTokens = chunk.usage.prompt_tokens;
            }
            if (chunk.usage?.completion_tokens) {
              completionTokens = chunk.usage.completion_tokens;
            }

            if (choice?.delta?.tool_calls) {
              const newToolCalls: LLMToolCall[] = choice.delta.tool_calls.map((tc: any) => {
                let args = tc.function?.arguments || {};
                if (typeof args === 'string') {
                  try { args = JSON.parse(args); } catch (e) { args = {}; }
                }
                return {
                  id: tc.id || `call_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`,
                  name: tc.function?.name || '',
                  arguments: args
                };
              });
              toolCalls.push(...newToolCalls);
            }
          } catch (e: any) {
            this.log(`Warning: Could not parse chunk: ${line.substring(0, 100)}`);
          }
        }
      }
    } catch (error: any) {
      this.log(`Streaming error: ${error.message}`);
      yield { text: `Error: ${error.message}`, done: true };
    }
  }

  /**
   * Run a shell command
   */
  async runCommand(command: string, workingDir?: string): Promise<{ stdout: string, stderr: string }> {
    const effectiveWorkingDir = workingDir || this.workspaceRoot;
    
    this.log(`Running command: ${command}`);
    this.log(`Working directory: ${effectiveWorkingDir}`);
    
    try {
      const options = { cwd: effectiveWorkingDir };
      const { stdout, stderr } = await execAsync(command, options);
      this.log(`Command completed`);
      return { stdout, stderr };
    } catch (error: any) {
      this.log(`Command failed: ${error.message}`);
      throw error;
    }
  }

  /**
   * Run a Git command
   */
  async runGitCommand(args: string[]): Promise<{ stdout: string, stderr: string }> {
    const command = `git ${args.join(' ')}`;
    this.log(`Running Git: ${command}`);
    return this.runCommand(command);
  }

  /**
   * Read a file
   */
  async readFile(filePath: string): Promise<string> {
    this.log(`Reading: ${filePath}`);
    return fs.promises.readFile(filePath, 'utf8');
  }

  /**
   * Write a file
   */
  async writeFile(filePath: string, content: string): Promise<void> {
    this.log(`Writing: ${filePath} (${content.length} chars)`);
    const dir = path.dirname(filePath);
    await fs.promises.mkdir(dir, { recursive: true });
    await fs.promises.writeFile(filePath, content, 'utf8');
  }

  /**
   * Search files by name AND content, returning detailed match info
   */
  async searchFiles(pattern: string, dirPath?: string): Promise<SearchResult[]> {
    this.log(`Searching: ${pattern}`);
    let searchDir = dirPath || this.workspaceRoot || process.cwd();

    // If searchDir points to a file, search in its parent directory instead
    try {
      const stat = await fs.promises.stat(searchDir);
      if (stat.isFile()) {
        const parentDir = path.dirname(searchDir);
        this.log(`Search path is a file, searching parent directory: ${parentDir}`);
        searchDir = parentDir;
      }
    } catch (e: any) {
      // If stat fails, continue with the original path (will fail gracefully in searchInDir)
    }
    const results: SearchResult[] = [];
    const resultsMap = new Map<string, SearchResult>();
    const regex = new RegExp(pattern, 'i');

    // Use project-specific extensions from architecture detection, fallback to defaults
    const textExtensions = this.fileExtensions.length > 0
      ? this.fileExtensions
      : this.getDefaultExtensions();

    const extractSnippet = (lines: string[], matchLine: number): string => {
      const start = Math.max(0, matchLine - 2);
      const end = Math.min(lines.length, matchLine + 3);
      return lines.slice(start, end).map((line, i) => {
        const lineNum = start + i + 1;
        const prefix = lineNum === matchLine ? '>>>' : '   ';
        return `${prefix} ${String(lineNum).padStart(4)} | ${line}`;
      }).join('\n');
    };

    const searchInDir = async (dir: string) => {
      try {
        const entries = await fs.promises.readdir(dir, { withFileTypes: true });
        for (const entry of entries) {
          if (entry.isDirectory() && ['build', '.gradle', '.idea', 'node_modules', '.git', 'out', 'bin', 'target', 'dist'].includes(entry.name)) {
            continue;
          }
          const fullPath = path.join(dir, entry.name);
          if (entry.isDirectory() && !entry.name.startsWith('.')) {
            await searchInDir(fullPath);
          } else if (entry.isFile()) {
            // First check file name
            if (regex.test(entry.name)) {
              if (!resultsMap.has(fullPath)) {
                const result: SearchResult = {
                  path: fullPath,
                  matchCount: 1,
                  matches: [{ line: 0, text: `File name matches "${pattern}"`, snippet: '' }],
                  matchedByName: true
                };
                resultsMap.set(fullPath, result);
                results.push(result);
                if (results.length >= 50) return;
              }
            } else {
              // If name doesn't match, check file content for text files
              const ext = path.extname(entry.name).toLowerCase();
              if (textExtensions.includes(ext)) {
                try {
                  const content = await fs.promises.readFile(fullPath, 'utf8');
                  const lines = content.split('\n');
                  const matches: SearchMatch[] = [];

                  for (let i = 0; i < lines.length; i++) {
                    if (regex.test(lines[i])) {
                      matches.push({
                        line: i + 1,
                        text: lines[i].trim().substring(0, 120),
                        snippet: extractSnippet(lines, i + 1)
                      });
                    }
                  }

                  if (matches.length > 0) {
                    if (!resultsMap.has(fullPath)) {
                      const result: SearchResult = {
                        path: fullPath,
                        matchCount: matches.length,
                        matches: matches.slice(0, 5), // Cap at 5 matches per file
                        matchedByName: false
                      };
                      resultsMap.set(fullPath, result);
                      results.push(result);
                      if (results.length >= 50) return;
                    }
                  }
                } catch (readError: any) {
                  // Skip files that can't be read (binary, permissions, etc.)
                  this.log(`Skipping ${fullPath}: ${readError.message}`);
                }
              }
            }
          }
        }
      } catch (error: any) {
        this.log(`Error searching ${dir}: ${error.message}`);
      }
    };
    
    await searchInDir(searchDir);
    this.log(`Found ${results.length} matches`);
    return results.slice(0, 50);
  }

  /**
   * Run discovery
   */
  async runDiscovery(): Promise<DiscoveryResult> {
    this.log('Running discovery...');
    try {
      const rootDir = this.workspaceRoot || process.cwd();
      const entries = await fs.promises.readdir(rootDir, { withFileTypes: true });
      
      const directories = entries.filter(e => e.isDirectory()).map(e => e.name);
      const components: ComponentInfo[] = directories.map(dir => ({
        name: dir,
        type: 'module',
        path: path.join(rootDir, dir),
        layer: this.inferLayer(dir),
        dependencies: []
      }));
      
      const violations = await this.analyzeViolations(components);
      
      return {
        projectName: path.basename(rootDir),
        version: '1.0.0',
        components,
        relationships: [],
        layers: [],
        violations
      };
    } catch (error: any) {
      this.log(`Discovery failed: ${error.message}`);
      return {
        projectName: 'unknown',
        version: '0.0.0',
        components: [],
        relationships: [],
        layers: [],
        violations: []
      };
    }
  }

  private inferLayer(dirName: string): string {
    const lower = dirName.toLowerCase();
    if (lower.includes('vision') || lower.includes('ui') || lower.includes('view')) return 'VISION';
    if (lower.includes('struct') || lower.includes('model') || lower.includes('entity')) return 'STRUCTURE';
    if (lower.includes('logic') || lower.includes('service') || lower.includes('business')) return 'LOGIC';
    if (lower.includes('flow') || lower.includes('control') || lower.includes('router')) return 'FLOW';
    return 'CODE';
  }

  async analyzeViolations(components?: ComponentInfo[]): Promise<Violation[]> {
    const violations: Violation[] = [];
    const comps = components || (await this.runDiscovery()).components;
    
    for (const component of comps) {
      if (component.layer === 'VISION' && component.dependencies?.some(d => d.includes('Logic'))) {
        violations.push({
          type: 'LAYER_VIOLATION',
          message: `Vision component '${component.name}' should not depend on Logic layer`,
          severity: 'warning',
          component: component.name,
          layer: component.layer,
          source: component.name,
          target: 'Logic',
          rule: 'LayerDependency'
        });
      }
    }
    
    return violations;
  }

  async listTemplates(): Promise<TemplateInfo[]> {
    this.log('Listing templates...');
    return [];
  }

  async createProject(templateName: string, targetDir: string, variables: Record<string, string>): Promise<boolean> {
    this.log(`Creating project: ${templateName}`);
    return false;
  }

  async getContext(filePath: string): Promise<FileContext | null> {
    this.log(`Getting context: ${filePath}`);
    try {
      // Resolve relative paths to workspace root
      const resolvedPath = path.isAbsolute(filePath) ? filePath : path.join(this.workspaceRoot, filePath);
      const content = await fs.promises.readFile(resolvedPath, 'utf8');
      const lines = content.split('\n');
      const imports = content.match(/import.*from.*['"].*['"]/g) || [];
      const classes = content.match(/(class|interface|type)\s+\w+/g) || [];
      const functions = content.match(/(function|const|let|var)\s+\w+\s*=\s*\(.*\)/g) || [];

      return {
        path: resolvedPath,
        filePath: resolvedPath,
        name: path.basename(resolvedPath),
        language: path.extname(resolvedPath).slice(1),
        content,
        size: content.length,
        lines: lines.length,
        imports,
        classes,
        functions,
        component: path.basename(path.dirname(resolvedPath)),
        layer: this.inferLayer(path.basename(path.dirname(resolvedPath)))
      };
    } catch (error: any) {
      this.log(`Error getting context: ${error.message}`);
      return null;
    }
  }

  async listFiles(dirPath?: string, recursive?: boolean): Promise<string[]> {
    // Handle "." as workspace root
    let targetDir: string;
    if (!dirPath || dirPath === '.' || dirPath === './') {
      targetDir = this.workspaceRoot;
    } else if (path.isAbsolute(dirPath)) {
      targetDir = dirPath;
    } else {
      targetDir = path.join(this.workspaceRoot, dirPath);
    }
    
    this.log(`list_files: ${path.basename(targetDir)}${recursive ? ' (recursive)' : ''} → ${targetDir}`);
    
    const results: string[] = [];
    
    const listInDir = async (dir: string) => {
      try {
        const entries = await fs.promises.readdir(dir, { withFileTypes: true });
        for (const entry of entries) {
          if (entry.isDirectory() && ['build', '.gradle', '.idea', 'node_modules', '.git', 'out', 'bin', 'target', 'dist'].includes(entry.name)) {
            continue;
          }
          const fullPath = path.join(dir, entry.name);
          if (entry.isDirectory() && recursive && !entry.name.startsWith('.')) {
            await listInDir(fullPath);
          } else if (entry.isFile() && !entry.name.startsWith('.')) {
            results.push(fullPath);
          }
        }
      } catch (e: any) {
        this.log(`Error listing ${dir}: ${e.message}`);
      }
    };
    
    await listInDir(targetDir);
    this.log(`Found ${results.length} files`);
    return results;
  }

  async listDirectories(dirPath?: string): Promise<string[]> {
    const targetDir = dirPath || this.workspaceRoot;
    this.log(`Listing directories: ${targetDir}`);
    try {
      const entries = await fs.promises.readdir(targetDir, { withFileTypes: true });
      const dirs = entries
        .filter(e => e.isDirectory() && !e.name.startsWith('.'))
        .map(e => e.name)
        .sort();
      this.log(`Found ${dirs.length} directories`);
      return dirs;
    } catch (e: any) {
      this.log(`Error: ${e.message}`);
      return [];
    }
  }
}
