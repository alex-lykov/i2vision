/**
 * CLI Integration - Refactored with new provider system and error handling
 * 
 * This module provides HTTP integration with multiple LLM providers using the
 * new provider-agnostic error handling system.
 * 
 * Key Improvements:
 * - Uses ProviderFactory for provider creation
 * - Integrated with unified error handling system
 * - Consistent error handling across all providers
 * - Automatic retry logic
 * - User-friendly error messages
 */

import * as fs from 'fs';
import * as path from 'path';
import {exec} from 'child_process';
import {promisify} from 'util';
import * as vscode from 'vscode';
import {getExtensionsFromArchitecture, ProjectArchitecture} from './agent/tools/DomainDetector';
import { ProviderFactory } from './providers/ProviderFactory';
import { LLMProvider } from './types/provider-types';

const execAsync = promisify(exec);

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
  _isNudge?: boolean;
}

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

export interface LLMOptions {
  temperature?: number;
  top_p?: number;
  max_tokens?: number;
  thinking_enabled?: boolean;
  search_enabled?: boolean;
}

export interface LLMToolCall {
  id: string;
  name: string;
  arguments: Record<string, any>;
}

export interface LLMResponse {
  content: string;
  toolCalls: LLMToolCall[];
  tokenUsage?: {
    prompt: number;
    completion: number;
    total: number;
  };
}

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

export interface DiscoveryResult {
  projectName: string;
  version: string;
  components: ComponentInfo[];
  relationships: any[];
  layers: any[];
  violations: Violation[];
}

export interface ComponentInfo {
  name: string;
  type: string;
  path: string;
  layer?: string;
  dependencies?: string[];
}

export interface TemplateInfo {
  name: string;
  path: string;
  type: string;
  description: string;
  category: string;
  files: { path: string; content: string }[];
  variables: { name: string; description: string; required: boolean; defaultValue: string }[];
}

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

export class CLI {
  private outputChannel?: any;
  private workspaceRoot: string;
  private providerFactory: ProviderFactory;
  private currentProvider: LLMProvider | null = null;
  private fileExtensions: string[] = [];
  private projectArchitecture?: ProjectArchitecture;
  private cliPath?: string;

  constructor(workspaceRoot: string, outputChannel?: any) {
    this.workspaceRoot = workspaceRoot;
    this.outputChannel = outputChannel;
    this.providerFactory = new ProviderFactory();

    // Validate workspace root
    if (!this.workspaceRoot) {
      this.log('⚠️ WARNING: No workspace folder open - using current directory');
      this.workspaceRoot = process.cwd();
    } else {
      this.log(`Workspace root: ${this.workspaceRoot}`);
    }

    // Load configuration
    this.loadConfiguration();
    
    // Load project architecture for dynamic file extension detection
    this.loadProjectArchitecture();
  }

  private loadConfiguration(): void {
    try {
      const config = vscode.workspace.getConfiguration('i2vision');
      
      // Get CLI path from VSCode settings
      this.cliPath = config.get<string>('cli.path');
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
      this.log(`Could not read configuration: ${error.message}`);
    }
  }

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

  private loadProjectArchitecture(): void {
    const cachePath = path.join(this.workspaceRoot, '.vision-ai', 'cache', 'architecture.json');
    
    try {
      if (!fs.existsSync(cachePath)) {
        this.log('Project architecture cache not found, using default file extensions');
        this.fileExtensions = this.getDefaultExtensions();
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

  private getDefaultExtensions(): string[] {
    return ['.ts', '.tsx', '.js', '.jsx', '.kt', '.kts', '.java', '.xml', '.json', '.yaml', '.yml', '.css', '.scss', '.less', '.html', '.htm', '.md', '.txt', '.gradle', '.properties', '.svg'];
  }

  private log(message: string): void {
    const timestamp = new Date().toLocaleTimeString();
    const formatted = `[${timestamp}] [CLI] ${message}`;
    if (this.outputChannel) {
      this.outputChannel.appendLine(formatted);
    }
    console.log(formatted);
  }

  getWorkspaceRoot(): string {
    return this.workspaceRoot;
  }

  isAvailable(): boolean {
    return true;
  }

  /**
   * Get the current provider or create one based on model ID
   */
  private async getProvider(modelId: string, explicitProvider?: string): Promise<LLMProvider> {
    // Use explicit provider if specified
    if (explicitProvider) {
      return this.createProviderForType(explicitProvider);
    }

    // Determine provider based on model ID
    return this.createProviderForModel(modelId);
  }

  private createProviderForModel(modelId: string): LLMProvider {
    try {
      // Get configuration for provider creation
      const config = this.getProviderConfig();
      return this.providerFactory.createProvider(modelId, config);
    } catch (error: any) {
      this.log(`Failed to create provider for model ${modelId}: ${error.message}`);
      // Fallback to 3D LLM provider
      const config = this.getProviderConfig();
      return this.providerFactory.createProvider('3dllm:fallback', config);
    }
  }

  private createProviderForType(providerType: string): LLMProvider {
    try {
      const config = this.getProviderConfig();
      
      // Map provider type to model ID for factory
      let modelId: string;
      switch (providerType.toLowerCase()) {
        case 'ollama':
          modelId = 'ollama:default';
          break;
        case 'deepseek':
          modelId = 'deepseek-chat';
          break;
        case 'mistral':
          modelId = 'mistral-tiny';
          break;
        case '3dllm':
        default:
          modelId = '3dllm:default';
          break;
      }
      
      return this.providerFactory.createProvider(modelId, config);
    } catch (error: any) {
      this.log(`Failed to create ${providerType} provider: ${error.message}`);
      // Fallback to 3D LLM provider
      const config = this.getProviderConfig();
      return this.providerFactory.createProvider('3dllm:fallback', config);
    }
  }

  private getProviderConfig(): any {
    try {
      const config = vscode.workspace.getConfiguration('i2vision');
      
      return {
        ollamaUrl: config.get<string>('ollamaUrl') || 'http://localhost:11434',
        ollamaModel: config.get<string>('ollamaModel') || 'llama3.2:3b',
        
        deepSeekApiKey: config.get<string>('deepseek.apiKey') || process.env['DEEPSEEK_API_KEY'],
        deepSeekUrl: config.get<string>('deepseek.url') || 'https://api.deepseek.com',
        
        mistralApiKey: config.get<string>('mistral.apiKey') || process.env['MISTRAL_API_KEY'],
        mistralUrl: config.get<string>('mistral.url') || 'https://api.mistral.ai',
        
        threeDLlmUrl: config.get<string>('3dLlmUrl') || 'http://localhost:9655',
        threeDLlmModel: config.get<string>('3dLlmModel') || 'deepseek-web-v3'
      };
    } catch (error: any) {
      this.log(`Could not read provider configuration: ${error.message}`);
      return {};
    }
  }

  /**
   * Call LLM through appropriate provider using new provider system
   */
  async callLLM(
    modelId: string,
    messages: LLMMessage[],
    options?: LLMOptions,
    tools?: LLMTool[],
    stream: boolean = false,
    provider?: string
  ): Promise<LLMResponse | AsyncGenerator<LLMChunk>> {
    const startTime = Date.now();
    
    try {
      // Get or create the appropriate provider
      const llmProvider = await this.getProvider(modelId, provider);
      const providerName = llmProvider.getProviderName();
      
      this.log(`[LLM] ${modelId} | ${providerName} | ${messages.length} msg | ${tools?.length || 0} tools | stream=${stream}`);

      // Build prompt string for logging/fallback, but pass structured messages to provider
      const prompt = this.convertMessagesToPrompt(messages);
      
      // Call the provider with structured messages (preferred) and flat prompt as fallback
      const result = await llmProvider.callAPI({
        prompt: prompt,
        messages: messages,
        model: modelId,
        temperature: options?.temperature,
        topP: options?.top_p,
        maxTokens: options?.max_tokens,
        stream: stream,
        thinking_enabled: options?.thinking_enabled,
        search_enabled: options?.search_enabled
      });

      const elapsed = Date.now() - startTime;
      const rawTextLen = result.text?.length || 0;
      const toolCallsLen = (result as any).tool_calls?.length || 0;
      this.log(`[LLM SUCCESS] Completed in ${elapsed}ms | text: ${rawTextLen} chars | tool_calls: ${toolCallsLen}`);
      if (rawTextLen === 0 && toolCallsLen === 0) {
        this.log(`[LLM WARN] Empty response from provider — raw result: ${JSON.stringify(result).substring(0, 200)}`);
      }

      // Convert provider response to expected format
      return this.convertProviderResponseToLLMResponse(result, stream);
      
    } catch (error: any) {
      const elapsed = Date.now() - startTime;
      this.log(`[LLM ERROR] Failed after ${elapsed}ms: ${error.message}`);
      
      // Return error response in expected format
      return {
        content: `Error: LLM call failed - ${error.message}`,
        toolCalls: []
      };
    }
  }

  private convertMessagesToPrompt(messages: LLMMessage[]): string {
    // Convert message array to single prompt string
    // This is a simplified conversion - may need enhancement based on specific requirements
    return messages.map(msg => {
      if (msg.role === 'system') {
        return `System: ${msg.content}`;
      } else if (msg.role === 'user') {
        return `User: ${msg.content}`;
      } else if (msg.role === 'assistant') {
        return `Assistant: ${msg.content}`;
      } else {
        return `${msg.role}: ${msg.content}`;
      }
    }).join('\n\n');
  }

  private convertProviderResponseToLLMResponse(
    providerResponse: any,
    stream: boolean
  ): LLMResponse | AsyncGenerator<LLMChunk> {
    if (stream) {
      return this.createStreamingResponse(
        providerResponse.text || '',
        providerResponse.tool_calls || [],
        providerResponse.usage
      );
    } else {
      return {
        content: providerResponse.text || '',
        toolCalls: providerResponse.tool_calls || [],
        tokenUsage: providerResponse.usage ? {
          prompt: providerResponse.usage.promptTokens || 0,
          completion: providerResponse.usage.completionTokens || 0,
          total: providerResponse.usage.totalTokens || 0
        } : undefined
      };
    }
  }

  /**
   * Simulate streaming by yielding chunks of the complete response.
   * Uses sentence boundaries for natural flow. Real SSE streaming from providers
   * should replace this once the LLMProvider interface is extended with streamAPI().
   */
  private async *createStreamingResponse(
    text: string,
    toolCalls: any[],
    usage?: any
  ): AsyncGenerator<LLMChunk> {
    // Split on sentence/clause boundaries for natural chunking
    const chunks = text.split(/(?<=[.!?\n])\s*/);
    for (const chunk of chunks) {
      if (chunk.length > 0) {
        yield {text: chunk, done: false};
        await new Promise(resolve => setTimeout(resolve, 5));
      }
    }
    // Emit tool calls in the final chunk
    const finalChunk: LLMChunk = {text: '', done: true};
    if (toolCalls && toolCalls.length > 0) {
      finalChunk.toolCalls = toolCalls.map((tc: any) => ({
        id: tc.id || `call_${Date.now()}`,
        name: tc.name || '',
        arguments: typeof tc.arguments === 'string'
          ? (() => { try { return JSON.parse(tc.arguments); } catch { return tc.arguments; } })()
          : (tc.arguments || {})
      }));
    }
    if (usage) {
      finalChunk.tokenUsage = {
        prompt: usage.promptTokens || 0,
        completion: usage.completionTokens || 0,
        total: (usage.promptTokens || 0) + (usage.completionTokens || 0)
      };
    }
    yield finalChunk;
  }


  // === File system methods ===

  /** Read a file from disk, relative to workspace root */
  async readFile(filePath: string): Promise<string> {
    const resolvedPath = path.isAbsolute(filePath) ? filePath : path.join(this.workspaceRoot, filePath);
    return fs.promises.readFile(resolvedPath, 'utf-8');
  }

  /** Write content to a file, creating parent directories as needed */
  async writeFile(filePath: string, content: string): Promise<void> {
    const resolvedPath = path.isAbsolute(filePath) ? filePath : path.join(this.workspaceRoot, filePath);
    await fs.promises.mkdir(path.dirname(resolvedPath), {recursive: true});
    await fs.promises.writeFile(resolvedPath, content, 'utf-8');
  }

  /** Run a shell command and return stdout/stderr */
  async runCommand(command: string): Promise<{stdout: string; stderr: string}> {
    try {
      return await execAsync(command, {cwd: this.workspaceRoot});
    } catch (error: any) {
      return {
        stdout: error.stdout || '',
        stderr: error.stderr || error.message || ''
      };
    }
  }

  // === Other CLI methods ===

  async listFiles(dirPath?: string, recursive?: boolean): Promise<string[]> {
    const searchPath = dirPath ? path.resolve(this.workspaceRoot, dirPath) : this.workspaceRoot;
    const results: string[] = [];
    try {
      const entries = await fs.promises.readdir(searchPath, {withFileTypes: true});
      for (const entry of entries) {
        const fullPath = path.join(searchPath, entry.name);
        const relativePath = path.relative(this.workspaceRoot, fullPath);
        if (entry.isFile()) {
          results.push(relativePath);
        } else if (entry.isDirectory() && recursive) {
          const subResults = await this.listFiles(fullPath, recursive);
          results.push(...subResults);
        }
      }
    } catch {
      // Directory doesn't exist or can't be read
    }
    return results;
  }

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
    } catch {
      // If stat fails, continue with the original path
    }

    const results: SearchResult[] = [];
    const resultsMap = new Map<string, SearchResult>();
    const regex = new RegExp(pattern, 'i');

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

    const searchInDir = async (dir: string): Promise<void> => {
      try {
        const entries = await fs.promises.readdir(dir, {withFileTypes: true});
        for (const entry of entries) {
          if (entry.isDirectory() && ['build', '.gradle', '.idea', 'node_modules', '.git', 'out', 'bin', 'target', 'dist'].includes(entry.name)) {
            continue;
          }
          const fullPath = path.join(dir, entry.name);
          if (entry.isDirectory() && !entry.name.startsWith('.')) {
            await searchInDir(fullPath);
          } else if (entry.isFile()) {
            if (regex.test(entry.name)) {
              if (!resultsMap.has(fullPath)) {
                const result: SearchResult = {
                  path: fullPath,
                  matchCount: 1,
                  matches: [{line: 0, text: `File name matches "${pattern}"`, snippet: ''}],
                  matchedByName: true
                };
                resultsMap.set(fullPath, result);
                results.push(result);
                if (results.length >= 50) return;
              }
            } else {
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
                      results.push({
                        path: fullPath,
                        matchCount: matches.length,
                        matches: matches.slice(0, 5),
                        matchedByName: false
                      });
                      resultsMap.set(fullPath, results[results.length - 1]);
                      if (results.length >= 50) return;
                    }
                  }
                } catch {
                  // Skip files that can't be read (binary, permissions, etc.)
                }
              }
            }
          }
        }
      } catch {
        // Directory doesn't exist or can't be read
      }
    };

    await searchInDir(searchDir);
    return results;
  }

  async runDiscovery(): Promise<DiscoveryResult> {
    this.log('Running discovery...');
    try {
      const rootDir = this.workspaceRoot || process.cwd();
      const entries = await fs.promises.readdir(rootDir, {withFileTypes: true});

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

  private inferLayer(dirName: string): string {
    const lower = dirName.toLowerCase();
    if (lower.includes('vision') || lower.includes('ui') || lower.includes('view')) return 'VISION';
    if (lower.includes('struct') || lower.includes('model') || lower.includes('entity')) return 'STRUCTURE';
    if (lower.includes('logic') || lower.includes('service') || lower.includes('business')) return 'LOGIC';
    if (lower.includes('flow') || lower.includes('control') || lower.includes('router')) return 'FLOW';
    return 'CODE';
  }

  async readSourceFile(filePath: string): Promise<any> {
    const resolvedPath = path.isAbsolute(filePath) ? filePath : path.join(this.workspaceRoot, filePath);
    try {
      const stat = await fs.promises.stat(resolvedPath);
      const content = await fs.promises.readFile(resolvedPath, 'utf-8');
      const ext = path.extname(resolvedPath);
      return {
        path: path.relative(this.workspaceRoot, resolvedPath),
        filePath: resolvedPath,
        name: path.basename(resolvedPath),
        language: ext.replace('.', ''),
        content,
        size: stat.size,
        lines: content.split('\n').length,
        imports: [],
        classes: [],
        functions: []
      };
    } catch {
      return null;
    }
  }

  async listTemplates(): Promise<TemplateInfo[]> {
    return [];
  }

  async createProject(templateName: string, targetDir: string, variables: Record<string, string>): Promise<boolean> {
    return false;
  }

  async listDirectories(dirPath?: string): Promise<string[]> {
    const searchPath = dirPath ? path.resolve(this.workspaceRoot, dirPath) : this.workspaceRoot;
    const results: string[] = [];
    try {
      const entries = await fs.promises.readdir(searchPath, {withFileTypes: true});
      for (const entry of entries) {
        if (entry.isDirectory()) {
          results.push(path.relative(this.workspaceRoot, path.join(searchPath, entry.name)));
        }
      }
    } catch {
      // Directory doesn't exist or can't be read
    }
    return results;
  }

  async getContext(filePath: string): Promise<FileContext | null> {
    this.log(`Getting context: ${filePath}`);
    try {
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
}