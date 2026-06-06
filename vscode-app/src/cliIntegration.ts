/**
 * CLI Integration - Multi-provider LLM calls
 * 
 * This module provides HTTP integration with multiple LLM providers:
 * - Ollama (local + cloud models)
 * - DeepSeek (cloud API)
 * 
 * UPDATED: All shell commands now use workspace root as working directory
 * UPDATED: Provider routing based on model ID
 */

import * as fs from 'fs';
import * as path from 'path';
import { exec } from 'child_process';
import { promisify } from 'util';
import * as vscode from 'vscode';

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
}

/**
 * LLM Chunk for streaming responses
 */
export interface LLMChunk {
  text: string;
  done: boolean;
  toolCalls?: LLMToolCall[];
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
 * CLI class for multi-provider LLM integration
 */
export class CLI {
  private outputChannel?: any;
  private ollamaUrl: string = 'http://localhost:11434';
  private deepSeekApiKey?: string;
  private deepSeekBaseUrl: string = 'https://api.deepseek.com';
  private workspaceRoot: string;
  private cliPath?: string;

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
   * Call LLM through appropriate provider (Ollama or DeepSeek)
   * Returns both content and tool calls
   * 
   * @param stream - If true, returns AsyncGenerator<LLMChunk> for streaming
   */
  async callLLM(
    modelId: string,
    messages: LLMMessage[],
    options?: LLMOptions,
    tools?: LLMTool[],
    stream: boolean = false
  ): Promise<LLMResponse | AsyncGenerator<LLMChunk>> {
    this.log(`=== LLM CALL START ===`);
    this.log(`Model: ${modelId}`);
    this.log(`Provider: ${this.isDeepSeekModel(modelId) ? 'DeepSeek' : 'Ollama'}`);
    this.log(`Messages: ${messages.length}`);
    this.log(`Tools: ${tools?.length || 0}`);
    this.log(`Stream: ${stream}`);

    // Route to appropriate provider
    if (this.isDeepSeekModel(modelId)) {
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

      if (tools?.length) {
        body.tools = tools;
        this.log(`Including ${tools.length} tools in request`);
      }

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

      if (tools?.length) {
        body.tools = tools;
        this.log(`Including ${tools.length} tools in request`);
      }

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
        throw new Error(`DeepSeek API error: ${res.status} ${res.statusText}`);
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

      const decoder = new TextDecoder();
      let buffer = '';
      const toolCalls: LLMToolCall[] = [];

      while (true) {
        const { done, value } = await reader.read();
        
        if (done) {
          this.log(`Stream complete (${Date.now() - startTime}ms)`);
          yield { text: '', done: true, toolCalls: toolCalls.length > 0 ? toolCalls : undefined };
          break;
        }

        buffer += decoder.decode(value, { stream: true });
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
          } catch (e) {
            this.log(`Warning: Could not parse chunk: ${line}`);
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

      const decoder = new TextDecoder();
      let buffer = '';
      const toolCalls: LLMToolCall[] = [];

      while (true) {
        const { done, value } = await reader.read();
        
        if (done) {
          this.log(`Stream complete (${Date.now() - startTime}ms)`);
          yield { text: '', done: true, toolCalls: toolCalls.length > 0 ? toolCalls : undefined };
          break;
        }

        buffer += decoder.decode(value, { stream: true });
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
          } catch (e) {
            this.log(`Warning: Could not parse chunk: ${line}`);
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
   * Search files
   */
  async searchFiles(pattern: string, dirPath?: string): Promise<string[]> {
    this.log(`Searching: ${pattern}`);
    const searchDir = dirPath || this.workspaceRoot || process.cwd();
    const results: string[] = [];
    const regex = new RegExp(pattern, 'i');
    
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
            if (regex.test(entry.name)) {
              results.push(fullPath);
              if (results.length >= 50) return;
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
      const content = await fs.promises.readFile(filePath, 'utf8');
      const lines = content.split('\n');
      const imports = content.match(/import.*from.*['"].*['"]/g) || [];
      const classes = content.match(/(class|interface|type)\s+\w+/g) || [];
      const functions = content.match(/(function|const|let|var)\s+\w+\s*=\s*\(.*\)/g) || [];
      
      return {
        path: filePath,
        filePath: filePath,
        name: path.basename(filePath),
        language: path.extname(filePath).slice(1),
        content,
        size: content.length,
        lines: lines.length,
        imports,
        classes,
        functions,
        component: path.basename(path.dirname(filePath)),
        layer: this.inferLayer(path.basename(path.dirname(filePath)))
      };
    } catch (error: any) {
      this.log(`Error getting context: ${error.message}`);
      return null;
    }
  }

  async listFiles(dirPath?: string, recursive?: boolean): Promise<string[]> {
    const targetDir = dirPath || this.workspaceRoot;
    this.log(`Listing: ${targetDir} (recursive: ${recursive})`);
    
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
