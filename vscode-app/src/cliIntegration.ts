/**
 * CLI Integration - Direct Ollama HTTP calls
 * 
 * This module provides direct HTTP integration with Ollama,
 * bypassing the need for external CLI tools.
 * 
 * UPDATED: All shell commands now use workspace root as working directory
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
  tool_call_id?: string; // Links tool results to the assistant's tool_call
  tool_calls?: {
    id: string;
    type: string;
    function: {
      name: string;
      arguments: string;
    };
  }[]; // Tool calls made by assistant - enables linking results to calls
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
 * CLI class for Ollama integration
 */
export class CLI {
  private outputChannel?: any;
  private ollamaUrl: string = 'http://localhost:11434';
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
    
    // Get CLI path from VSCode settings (may have been set by LocalAgentProvider from project config)
    try {
      const config = vscode.workspace.getConfiguration('i2vision');
      this.cliPath = config.get<string>('cli.path');
      const configuredOllamaUrl = config.get<string>('ollamaUrl');
      if (configuredOllamaUrl) {
        this.ollamaUrl = configuredOllamaUrl;
        this.log(`Ollama URL from settings: ${this.ollamaUrl}`);
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
   * This is the authoritative source for all shell command working directories
   */
  getWorkspaceRoot(): string {
    return this.workspaceRoot;
  }

  /**
   * Check if CLI is available
   */
  isAvailable(): boolean {
    return true; // HTTP API is always available if Ollama is running
  }

  /**
   * Load CLI path directly from .vision-ai/config/cli.yaml
   * This is a fallback if VSCode settings don't have the path
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
        // Resolve relative paths
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
   * Call LLM through Ollama HTTP API
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
    this.log(`Calling LLM: ${modelId} with ${messages.length} messages (stream: ${stream})`);
    this.log(`=== DIAGNOSTIC: LLM CALL START ===`);
    this.log(`Model: ${modelId}`);
    this.log(`Tools: ${tools?.length || 0}`);
    this.log(`Temperature: ${options?.temperature || 0.2}`);
    this.log(`Max tokens: ${options?.max_tokens || 4096}`);

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
        this.log(`Body includes ${tools.length} tools`);
      }

      this.log(`Sending HTTP POST to ${this.ollamaUrl}/api/chat`);
      this.log(`Request body size: ${JSON.stringify(body).length} bytes`);

      const res = await fetch(`${this.ollamaUrl}/api/chat`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body)
      });

      const elapsed = Date.now() - startTime;
      this.log(`HTTP response received after ${elapsed}ms - Status: ${res.status} ${res.statusText}`);

      if (!res.ok) {
        const errorText = await res.text();
        this.log(`HTTP error body: ${errorText.substring(0, 500)}`);
        throw new Error(`Ollama HTTP error: ${res.status} ${res.statusText}`);
      }

      if (stream) {
        // Return streaming generator
        this.log(`Starting streaming response...`);
        return this.streamResponse(res, startTime);
      } else {
        // Non-streaming: wait for full response
        this.log(`Parsing JSON response...`);
        const data = await res.json() as any;
        this.log(`Response parsed successfully`);
        this.log(`Response structure: ${Object.keys(data).join(', ')}`);
        
        // Extract content and tool calls separately
        const content = data.message?.content || '';
        const toolCallsData = data.message?.tool_calls || [];
        
        this.log(`Message content length: ${content.length} chars`);
        this.log(`Message tool_calls count: ${toolCallsData.length}`);
        
        // Convert Ollama tool calls to our format
        const toolCalls: LLMToolCall[] = toolCallsData.map((tc: any) => {
          // Ollama may return arguments as a string or object - parse if needed
          let args = tc.function?.arguments || {};
          if (typeof args === 'string') {
            try {
              args = JSON.parse(args);
            } catch (e) {
              this.log(`Warning: Could not parse tool arguments as JSON: ${args}`);
              args = {};
            }
          }
          return {
            id: tc.id || tc.function?.id || `call_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`,
            name: tc.function?.name || '',
            arguments: args
          };
        });
        
        if (toolCalls.length > 0) {
          this.log(`Tool calls: ${JSON.stringify(toolCalls, null, 2)}`);
        }
        
        this.log(`=== DIAGNOSTIC: LLM CALL END ===`);
        
        return {
          content,
          toolCalls
        };
      }
    } catch (error: any) {
      const elapsed = Date.now() - startTime;
      this.log(`LLM call error after ${elapsed}ms: ${error.message}`);
      this.log(`Error stack: ${error.stack}`);
      this.log(`=== DIAGNOSTIC: LLM CALL FAILED ===`);
      
      // Return empty response on error
      return {
        content: `Error: LLM call failed - ${error.message}`,
        toolCalls: []
      };
    }
  }

  /**
   * Stream LLM response as chunks
   */
  private async *streamResponse(
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
      let accumulatedContent = '';
      const toolCalls: LLMToolCall[] = [];

      while (true) {
        const { done, value } = await reader.read();
        
        if (done) {
          this.log(`Stream complete after ${Date.now() - startTime}ms`);
          yield {
            text: '',
            done: true,
            toolCalls: toolCalls.length > 0 ? toolCalls : undefined
          };
          break;
        }

        // Decode chunk and parse NDJSON
        buffer += decoder.decode(value, { stream: true });
        const lines = buffer.split('\n');
        buffer = lines.pop() || '';

        for (const line of lines) {
          if (!line.trim()) continue;

          try {
            const chunk = JSON.parse(line) as any;
            
            // Accumulate content
            const delta = chunk.message?.content || '';
            accumulatedContent += delta;
            
            if (delta) {
              yield {
                text: delta,
                done: false
              };
            }

            // Accumulate tool calls
            if (chunk.message?.tool_calls) {
              const newToolCalls: LLMToolCall[] = chunk.message.tool_calls.map((tc: any) => {
                let args = tc.function?.arguments || {};
                if (typeof args === 'string') {
                  try {
                    args = JSON.parse(args);
                  } catch (e) {
                    args = {};
                  }
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
      yield {
        text: `Error: ${error.message}`,
        done: true
      };
    }
  }

  /**
   * Run a shell command
   * 
   * CRITICAL: Always uses workspace root as working directory unless explicitly overridden
   * This ensures Git and other tools work correctly with the project
   * 
   * @param command - The command to execute
   * @param workingDir - Optional override (defaults to workspace root)
   */
  async runCommand(command: string, workingDir?: string): Promise<{ stdout: string, stderr: string }> {
    // ALWAYS use workspace root if not explicitly provided
    // This is critical for Git, build tools, and file operations
    const effectiveWorkingDir = workingDir || this.workspaceRoot;
    
    this.log(`Running command: ${command}`);
    this.log(`Working directory: ${effectiveWorkingDir}`);
    
    try {
      const options = { cwd: effectiveWorkingDir };
      const { stdout, stderr } = await execAsync(command, options);
      this.log(`Command completed successfully`);
      return { stdout, stderr };
    } catch (error: any) {
      this.log(`Command failed: ${error.message}`);
      this.log(`Stderr: ${error.stderr}`);
      throw error;
    }
  }

  /**
   * Run a Git command with workspace root as working directory
   * This is a convenience wrapper that ensures Git always works correctly
   */
  async runGitCommand(args: string[]): Promise<{ stdout: string, stderr: string }> {
    const command = `git ${args.join(' ')}`;
    this.log(`Running Git command: ${command}`);
    return this.runCommand(command);
  }

  /**
   * Read a file's contents
   */
  async readFile(filePath: string): Promise<string> {
    this.log(`Reading file: ${filePath}`);
    try {
      const content = await fs.promises.readFile(filePath, 'utf8');
      this.log(`File read successfully (${content.length} chars)`);
      return content;
    } catch (error: any) {
      this.log(`Error reading file: ${error.message}`);
      throw error;
    }
  }

  /**
   * Write content to a file
   */
  async writeFile(filePath: string, content: string): Promise<void> {
    this.log(`Writing file: ${filePath} (${content.length} chars)`);
    try {
      // Ensure directory exists
      const dir = path.dirname(filePath);
      await fs.promises.mkdir(dir, { recursive: true });
      
      await fs.promises.writeFile(filePath, content, 'utf8');
      this.log(`File written successfully`);
    } catch (error: any) {
      this.log(`Error writing file: ${error.message}`);
      throw error;
    }
  }

  /**
   * Search for a pattern in files using Node.js fs (excludes build directories)
   */
  async searchFiles(pattern: string, dirPath?: string): Promise<string[]> {
    this.log(`Searching for pattern: ${pattern}`);
    try {
      const searchDir = dirPath || this.workspaceRoot || process.cwd();
      this.log(`Search directory: ${searchDir}`);
      
      const results: string[] = [];
      const regex = new RegExp(pattern, 'i');
      
      const searchInDir = async (dir: string) => {
        try {
          const entries = await fs.promises.readdir(dir, { withFileTypes: true });
          
          for (const entry of entries) {
            // Skip excluded directories
            if (entry.isDirectory() && ['build', '.gradle', '.idea', 'node_modules', '.git', 'out', 'bin', 'target', 'dist'].includes(entry.name)) {
              this.log(`  Skipping excluded directory: ${entry.name}`);
              continue;
            }
            
            const fullPath = path.join(dir, entry.name);
            
            if (entry.isDirectory() && !entry.name.startsWith('.')) {
              await searchInDir(fullPath);
            } else if (entry.isFile()) {
              try {
                // Check if filename matches
                if (regex.test(entry.name)) {
                  results.push(fullPath);
                  if (results.length >= 50) return; // Limit results
                }
              } catch (e) {
                // Skip files that can't be read
              }
            }
          }
        } catch (error: any) {
          this.log(`Error searching directory ${dir}: ${error.message}`);
        }
      };
      
      await searchInDir(searchDir);
      
      this.log(`Found ${results.length} matches`);
      return results.slice(0, 50);
    } catch (error: any) {
      this.log(`Error searching files: ${error.message}`);
      return [];
    }
  }

  /**
   * Run discovery to get project context
   */
  async runDiscovery(): Promise<DiscoveryResult> {
    this.log('Running project discovery...');
    try {
      // Simple discovery: list top-level directories
      const rootDir = this.workspaceRoot || process.cwd();
      const entries = await fs.promises.readdir(rootDir, { withFileTypes: true });
      
      const directories = entries.filter(e => e.isDirectory()).map(e => e.name);
      const files = entries.filter(e => e.isFile()).map(e => e.name);
      
      // Create a simple component for each directory
      const components: ComponentInfo[] = directories.map(dir => ({
        name: dir,
        type: 'module',
        path: path.join(rootDir, dir),
        layer: this.inferLayer(dir),
        dependencies: []
      }));
      
      // Analyze violations
      const violations = await this.analyzeViolations(components);
      
      const result: DiscoveryResult = {
        projectName: path.basename(rootDir),
        version: '1.0.0',
        components,
        relationships: [],
        layers: [],
        violations
      };
      
      this.log(`Discovery complete: ${components.length} components found, ${violations.length} violations`);
      return result;
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

  /**
   * Infer VSLFC layer from directory name
   */
  private inferLayer(dirName: string): string {
    const lower = dirName.toLowerCase();
    if (lower.includes('vision') || lower.includes('ui') || lower.includes('view')) return 'VISION';
    if (lower.includes('struct') || lower.includes('model') || lower.includes('entity')) return 'STRUCTURE';
    if (lower.includes('logic') || lower.includes('service') || lower.includes('business')) return 'LOGIC';
    if (lower.includes('flow') || lower.includes('control') || lower.includes('router')) return 'FLOW';
    if (lower.includes('code') || lower.includes('impl') || lower.includes('util')) return 'CODE';
    return 'CODE';
  }

  /**
   * Analyze violations in the project
   */
  async analyzeViolations(components?: ComponentInfo[]): Promise<Violation[]> {
    this.log(`Analyzing violations...`);
    const violations: Violation[] = [];
    
    // If no components provided, run discovery first
    const comps = components || (await this.runDiscovery()).components;
    
    this.log(`Analyzing violations for ${comps.length} components...`);
    
    // Simple heuristic analysis
    for (const component of comps) {
      // Check for layer violations (simplified)
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
    
    this.log(`Found ${violations.length} violations`);
    return violations;
  }

  /**
   * List available project templates
   */
  async listTemplates(): Promise<TemplateInfo[]> {
    this.log('Listing available templates...');
    try {
      // Return built-in templates
      const templates: TemplateInfo[] = [
        {
          name: 'spring-boot',
          path: 'templates/spring-boot',
          type: 'project',
          description: 'Spring Boot microservice template',
          category: 'Java',
          files: [
            { path: 'src/main/java/Application.java', content: '' },
            { path: 'pom.xml', content: '' }
          ],
          variables: [
            { name: 'groupId', description: 'Maven group ID', required: true, defaultValue: 'com.example' },
            { name: 'artifactId', description: 'Maven artifact ID', required: true, defaultValue: 'demo' },
            { name: 'packageName', description: 'Base package name', required: true, defaultValue: 'com.example.demo' }
          ]
        },
        {
          name: 'express',
          path: 'templates/express',
          type: 'project',
          description: 'Express.js REST API template',
          category: 'Node.js',
          files: [
            { path: 'src/index.ts', content: '' },
            { path: 'package.json', content: '' }
          ],
          variables: [
            { name: 'projectName', description: 'Project name', required: true, defaultValue: 'my-api' }
          ]
        }
      ];
      
      this.log(`Found ${templates.length} templates`);
      return templates;
    } catch (error: any) {
      this.log(`Error listing templates: ${error.message}`);
      return [];
    }
  }

  /**
   * Create a project from a template
   */
  async createProject(templateName: string, targetDir: string, variables: Record<string, string>): Promise<boolean> {
    this.log(`Creating project from template: ${templateName} in ${targetDir}`);
    try {
      // Create target directory
      await fs.promises.mkdir(targetDir, { recursive: true });
      
      // Create basic structure based on template
      if (templateName === 'spring-boot') {
        const groupId = variables.groupId || 'com.example';
        const artifactId = variables.artifactId || 'demo';
        const packageName = variables.packageName || 'com.example.demo';
        
        // Create directory structure
        const srcDir = path.join(targetDir, 'src', 'main', 'java', ...packageName.split('.'));
        await fs.promises.mkdir(srcDir, { recursive: true });
        
        // Create Application.java
        const appClass = artifactId.charAt(0).toUpperCase() + artifactId.slice(1);
        const appContent = `package ${packageName};

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ${appClass}Application {
    public static void main(String[] args) {
        SpringApplication.run(${appClass}Application.class, args);
    }
}`;
        await fs.promises.writeFile(path.join(srcDir, `${appClass}Application.java`), appContent);
        
        // Create pom.xml
        const pomContent = `<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 
         https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    
    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.2.0</version>
        <relativePath/>
    </parent>
    
    <groupId>${groupId}</groupId>
    <artifactId>${artifactId}</artifactId>
    <version>0.0.1-SNAPSHOT</version>
    <name>${artifactId}</name>
    <description>Demo project for Spring Boot</description>
    
    <properties>
        <java.version>17</java.version>
    </properties>
    
    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
    </dependencies>
</project>`;
        await fs.promises.writeFile(path.join(targetDir, 'pom.xml'), pomContent);
        
        this.log(`Spring Boot project created successfully`);
        return true;
      } else if (templateName === 'express') {
        const projectName = variables.projectName || 'my-api';
        
        // Create directory structure
        const srcDir = path.join(targetDir, 'src');
        await fs.promises.mkdir(srcDir, { recursive: true });
        
        // Create index.ts
        const indexContent = `import express from 'express';

const app = express();
const port = 3000;

app.get('/', (req, res) => {
  res.json({ message: 'Hello World!' });
});

app.listen(port, () => {
  console.log(\`Server running at http://localhost:\${port}\`);
});`;
        await fs.promises.writeFile(path.join(srcDir, 'index.ts'), indexContent);
        
        // Create package.json
        const packageJson = {
          name: projectName,
          version: '1.0.0',
          description: 'Express.js REST API',
          main: 'dist/index.js',
          scripts: {
            build: 'tsc',
            start: 'node dist/index.js',
            dev: 'ts-node src/index.ts'
          },
          dependencies: {
            express: '^4.18.2'
          },
          devDependencies: {
            '@types/express': '^4.17.21',
            '@types/node': '^20.10.0',
            'ts-node': '^10.9.2',
            typescript: '^5.3.0'
          }
        };
        await fs.promises.writeFile(path.join(targetDir, 'package.json'), JSON.stringify(packageJson, null, 2));
        
        this.log(`Express.js project created successfully`);
        return true;
      }
      
      this.log(`Unknown template: ${templateName}`);
      return false;
    } catch (error: any) {
      this.log(`Error creating project: ${error.message}`);
      return false;
    }
  }

  /**
   * Get context for a file
   */
  async getContext(filePath: string): Promise<FileContext | null> {
    this.log(`Getting context for file: ${filePath}`);
    try {
      const content = await fs.promises.readFile(filePath, 'utf8');
      const lines = content.split('\n');
      
      // Extract imports
      const imports = content.match(/import.*from.*['"].*['"]/g) || [];
      
      // Extract classes
      const classes = content.match(/(class|interface|type)\s+\w+/g) || [];
      
      // Extract functions
      const functions = content.match(/(function|const|let|var)\s+\w+\s*=\s*\(.*\)/g) || [];
      
      const context: FileContext = {
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
      
      this.log(`Context extracted: ${context.lines} lines, ${imports.length} imports`);
      return context;
    } catch (error: any) {
      this.log(`Error getting context: ${error.message}`);
      return null;
    }
  }

  /**
   * List files in a directory
   * @param dirPath - Directory path (optional, defaults to workspace root)
   * @param recursive - Whether to search recursively (optional, defaults to false)
   */
  async listFiles(dirPath?: string, recursive?: boolean): Promise<string[]> {
    const targetDir = dirPath || this.workspaceRoot;
    this.log(`Listing files in: ${targetDir} (recursive: ${recursive})`);
    
    const results: string[] = [];
    
    const listInDir = async (dir: string) => {
      try {
        const entries = await fs.promises.readdir(dir, { withFileTypes: true });
        
        for (const entry of entries) {
          // Skip excluded directories
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
        this.log(`Error listing directory ${dir}: ${e.message}`);
      }
    };
    
    await listInDir(targetDir);
    
    this.log(`Found ${results.length} files`);
    return results;
  }

  /**
   * List directories in a path
   */
  async listDirectories(dirPath?: string): Promise<string[]> {
    const targetDir = dirPath || this.workspaceRoot;
    this.log(`Listing directories in: ${targetDir}`);
    try {
      const entries = await fs.promises.readdir(targetDir, { withFileTypes: true });
      const dirs = entries
        .filter(e => e.isDirectory() && !e.name.startsWith('.'))
        .map(e => e.name)
        .sort();
      this.log(`Found ${dirs.length} directories`);
      return dirs;
    } catch (e: any) {
      this.log(`Error listing directories: ${e.message}`);
      return [];
    }
  }
}
