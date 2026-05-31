/**
 * i2-Vision CLI Integration
 * 
 * Bridges the VSCode extension with the i2vision CLI backend
 * for real-time discovery, context extraction, and analysis.
 */

import { exec, spawn } from 'child_process';
import { promisify } from 'util';
import * as vscode from 'vscode';
import * as path from 'path';
import * as fs from 'fs';

const execAsync = promisify(exec);

/**
 * Execute a command with stdin input
 */
function execWithInput(command: string, input: string, options: { cwd?: string, timeout?: number } = {}): Promise<string> {
  return new Promise((resolve, reject) => {
    // On Windows, use cmd.exe to handle complex commands
    const isWindows = process.platform === 'win32';
    const shell = isWindows ? 'cmd.exe' : '/bin/sh';
    const shellArgs = isWindows ? ['/c', command] : ['-c', command];
    
    const child = spawn(shell, shellArgs, {
      cwd: options.cwd,
      stdio: ['pipe', 'pipe', 'pipe']
    });

    let stdout = '';
    let stderr = '';

    child.stdout.on('data', (data) => {
      stdout += data.toString();
    });

    child.stderr.on('data', (data) => {
      stderr += data.toString();
    });

    child.on('error', (err) => {
      reject(err);
    });

    child.on('close', (code) => {
      if (code === 0) {
        resolve(stdout);
      } else {
        reject(new Error(`Command failed with code ${code}: ${stderr}`));
      }
    });

    // Write input to stdin
    child.stdin.write(input);
    child.stdin.end();

    // Timeout handling
    if (options.timeout) {
      setTimeout(() => {
        child.kill('SIGTERM');
        reject(new Error('Command timed out'));
      }, options.timeout);
    }
  });
}

/**
 * Discovery result from i2vision CLI
 */
export interface DiscoveryResult {
  projectName: string;
  version: string;
  components: ComponentInfo[];
  relationships: Relationship[];
  layers: LayerInfo[];
  violations?: Violation[];
}

/**
 * Component information from discovery
 */
export interface ComponentInfo {
  name: string;
  type: 'module' | 'package' | 'class' | 'interface' | 'service';
  path: string;
  layer?: string;
  dependencies?: string[];
  metadata?: Record<string, any>;
}

/**
 * Relationship between components
 */
export interface Relationship {
  from: string;
  to: string;
  type: 'depends_on' | 'implements' | 'extends' | 'calls' | 'uses';
  strength?: number;
}

/**
 * Architecture layer information
 */
export interface LayerInfo {
  name: string;
  level: number;
  components: string[];
  allowedDependencies?: string[];
}

/**
 * Architecture violation
 */
export interface Violation {
  severity: 'error' | 'warning' | 'info';
  message: string;
  source: string;
  target: string;
  rule: string;
}

/**
 * VSLF Context for a file
 */
export interface VSLFContext {
  filePath: string;
  component: string;
  layer: string;
  responsibilities: string[];
  dependencies: string[];
  dependents: string[];
  metrics?: {
    complexity: number;
    coupling: number;
    cohesion: number;
  };
}

/**
 * Project template information
 */
export interface TemplateInfo {
  name: string;
  description: string;
  category: 'basic' | 'advanced' | 'enterprise' | 'microservice';
  files: TemplateFile[];
  variables: TemplateVariable[];
}

export interface TemplateFile {
  path: string;
  content: string;
  template: boolean;
}

export interface TemplateVariable {
  name: string;
  description: string;
  defaultValue?: string;
  required: boolean;
}

/**
 * LLM message structure
 */
export interface LLMMessage {
  role: string;
  content: string;
}

/**
 * LLM tool definition
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
 * LLM options
 */
export interface LLMOptions {
  temperature?: number;
  top_p?: number;
  max_tokens?: number;
}

/**
 * i2-Vision CLI wrapper
 */
export class I2VisionCLI {
  private workspaceRoot: string;
  private cliPath: string;
  private outputChannel: vscode.OutputChannel;
  private i2VisionProjectRoot: string | null;

  constructor(workspaceRoot: string, outputChannel?: vscode.OutputChannel) {
    this.workspaceRoot = workspaceRoot;
    // Initialize outputChannel FIRST - detectCLIPath() calls this.log() which needs it
    this.outputChannel = outputChannel || vscode.window.createOutputChannel('i2-Vision CLI');
    
    // Find i2-vision project root first
    this.i2VisionProjectRoot = this.findI2VisionProjectRoot();
    this.cliPath = this.detectCLIPath();
  }

  /**
   * Detect the i2vision CLI path
   */
  private detectCLIPath(): string {
    // First, check VSCode settings for custom path
    const config = vscode.workspace.getConfiguration('i2vision');
    const customPath = config.get<string>('cli.path');
    if (customPath && fs.existsSync(customPath)) {
      this.log(`Using custom CLI path from settings: ${customPath}`);
      return `java -jar "${customPath}"`;
    }

    // Second, try to find the shadow JAR in i2-vision project
    if (this.i2VisionProjectRoot) {
      const shadowJar = path.join(
        this.i2VisionProjectRoot,
        'i2vision-cli',
        'build',
        'libs',
        'i2vision-cli-1.0.0-all.jar'
      );
      
      if (fs.existsSync(shadowJar)) {
        this.log(`Using shadow JAR: ${shadowJar}`);
        return `java -jar "${shadowJar}"`;
      }

      // Fallback to installed distribution
      const installedCli = path.join(
        this.i2VisionProjectRoot,
        'i2vision-cli',
        'build',
        'install',
        'i2vision-cli',
        'bin',
        'i2vision-cli.bat'
      );
      
      if (fs.existsSync(installedCli)) {
        this.log(`Using installed CLI: ${installedCli}`);
        return `"${installedCli}"`;
      }
    }

    // Third, check known development locations
    const knownLocations = [
      'D:/proj/AI/i2-vision',
      'C:/proj/AI/i2-vision',
      path.join(process.env.USERPROFILE || '', 'projects', 'i2-vision'),
      path.join(process.env.HOME || '', 'projects', 'i2-vision')
    ];

    for (const location of knownLocations) {
      if (fs.existsSync(location)) {
        const shadowJar = path.join(location, 'i2vision-cli', 'build', 'libs', 'i2vision-cli-1.0.0-all.jar');
        if (fs.existsSync(shadowJar)) {
          this.log(`Found CLI in known location: ${shadowJar}`);
          return `java -jar "${shadowJar}"`;
        }
      }
    }

    // Last resort: try PATH
    this.log('CLI not found, will try PATH');
    return 'i2vision-cli';
  }

  /**
   * Find the i2-vision project root (looks for the specific multi-module structure)
   */
  private findI2VisionProjectRoot(): string | null {
    // Strategy 1: Search upward from workspace root
    let currentDir = this.workspaceRoot;
    const maxDepth = 8;
    let depth = 0;

    while (depth < maxDepth) {
      // Look for the specific i2-vision structure
      const settingsFile = path.join(currentDir, 'settings.gradle.kts');
      const cliModuleDir = path.join(currentDir, 'i2vision-cli');
      
      if (fs.existsSync(settingsFile) && fs.existsSync(cliModuleDir)) {
        // Verify it's actually i2-vision by checking settings.gradle.kts content
        try {
          const settingsContent = fs.readFileSync(settingsFile, 'utf-8');
          if (settingsContent.includes('i2vision-cli') || settingsContent.includes('i2-vision')) {
            this.log(`Found i2-vision project root (upward search): ${currentDir}`);
            return currentDir;
          }
        } catch (err) {
          // Continue searching
        }
      }
      
      const parentDir = path.dirname(currentDir);
      if (parentDir === currentDir) {
        break;
      }
      currentDir = parentDir;
      depth++;
    }

    // Strategy 2: Check known development locations
    const knownLocations = [
      'D:/proj/AI/i2-vision',
      'C:/proj/AI/i2-vision',
      path.join(process.env.USERPROFILE || '', 'projects', 'i2-vision'),
      path.join(process.env.HOME || '', 'projects', 'i2-vision')
    ];

    for (const location of knownLocations) {
      if (fs.existsSync(location)) {
        const settingsFile = path.join(location, 'settings.gradle.kts');
        const cliModuleDir = path.join(location, 'i2vision-cli');
        
        if (fs.existsSync(settingsFile) && fs.existsSync(cliModuleDir)) {
          try {
            const settingsContent = fs.readFileSync(settingsFile, 'utf-8');
            if (settingsContent.includes('i2vision-cli') || settingsContent.includes('i2-vision')) {
              this.log(`Found i2-vision project root (known location): ${location}`);
              return location;
            }
          } catch (err) {
            // Continue searching
          }
        }
      }
    }

    this.log(`Could not find i2-vision project root from: ${this.workspaceRoot}`);
    return null;
  }

  /**
   * Run discovery on the workspace
   */
  async runDiscovery(): Promise<DiscoveryResult> {
    this.log('Running discovery...');
    
    try {
      const command = `${this.cliPath} discover --json "${this.workspaceRoot}"`;
      const { stdout, stderr } = await execAsync(command, {
        cwd: this.workspaceRoot,
        maxBuffer: 10 * 1024 * 1024 // 10MB buffer
      });

      if (stderr) {
        this.log(`Warning: ${stderr}`);
      }

      const result = JSON.parse(stdout) as DiscoveryResult;
      this.log(`Discovery complete: ${result.components.length} components found`);
      return result;
    } catch (error: any) {
      this.log(`Discovery error: ${error.message}`);
      // Return mock data if CLI is not available (development mode)
      return this.getMockDiscovery();
    }
  }

  /**
   * Get VSLF context for a specific file
   */
  async getContext(filePath: string): Promise<VSLFContext> {
    this.log(`Getting context for: ${filePath}`);

    try {
      const command = `${this.cliPath} context file --path "${filePath}" --json`;
      const { stdout, stderr } = await execAsync(command, {
        cwd: this.workspaceRoot
      });

      if (stderr) {
        this.log(`Warning: ${stderr}`);
      }

      return JSON.parse(stdout) as VSLFContext;
    } catch (error: any) {
      this.log(`Context error: ${error.message}`);
      // Return mock context
      return this.getMockContext(filePath);
    }
  }

  /**
   * List available templates
   */
  async listTemplates(): Promise<TemplateInfo[]> {
    this.log('Listing templates...');

    try {
      const command = `${this.cliPath} preset --list --json`;
      const { stdout, stderr } = await execAsync(command, {
        cwd: this.workspaceRoot
      });

      if (stderr) {
        this.log(`Warning: ${stderr}`);
      }

      return JSON.parse(stdout) as TemplateInfo[];
    } catch (error: any) {
      this.log(`Template list error: ${error.message}`);
      // Return mock templates
      return this.getMockTemplates();
    }
  }

  /**
   * Create a new project from template
   */
  async createProject(templateName: string, projectName: string, variables: Record<string, string>): Promise<boolean> {
    this.log(`Creating project: ${projectName} from template: ${templateName}`);

    try {
      const varsString = Object.entries(variables)
        .map(([k, v]) => `--var ${k}="${v}"`)
        .join(' ');

      const command = `${this.cliPath} preset --create --template "${templateName}" --name "${projectName}" ${varsString}`;
      const { stdout, stderr } = await execAsync(command, {
        cwd: this.workspaceRoot
      });

      if (stderr) {
        this.log(`Warning: ${stderr}`);
      }

      this.log('Project created successfully');
      return true;
    } catch (error: any) {
      this.log(`Project creation error: ${error.message}`);
      vscode.window.showErrorMessage(`Failed to create project: ${error.message}`);
      return false;
    }
  }

  /**
   * Analyze architecture violations
   */
  async analyzeViolations(): Promise<Violation[]> {
    this.log('Analyzing architecture violations...');

    try {
      const command = `${this.cliPath} discover --violations --json`;
      const { stdout, stderr } = await execAsync(command, {
        cwd: this.workspaceRoot
      });

      if (stderr) {
        this.log(`Warning: ${stderr}`);
      }

      const result = JSON.parse(stdout) as { violations: Violation[] };
      return result.violations || [];
    } catch (error: any) {
      this.log(`Analysis error: ${error.message}`);
      return [];
    }
  }

  /**
   * Check if CLI is available
   */
  async isAvailable(): Promise<boolean> {
    try {
      await execAsync(`${this.cliPath} --help`, {
        cwd: this.workspaceRoot,
        timeout: 5000
      });
      return true;
    } catch {
      return false;
    }
  }

  /**
   * Get available models from Ollama
   */
  async getLoadedModels(): Promise<Array<{name: string, provider: string}>> {
    try {
      const res = await fetch('http://localhost:11434/api/tags', {
        method: 'GET',
        headers: { 'Content-Type': 'application/json' }
      });

      if (!res.ok) {
        this.log(`Failed to fetch models: ${res.status}`);
        return [];
      }

      const data = await res.json() as any;
      const models = data.models || [];
      
      return models.map((m: any) => ({
        name: m.name || m.model || 'unknown',
        provider: 'ollama'
      }));
    } catch (error: any) {
      this.log(`Error fetching models: ${error.message}`);
      return [];
    }
  }

  /**
   * Call LLM through Ollama HTTP API
   */
  async callLLM(
    modelId: string,
    messages: LLMMessage[],
    options?: LLMOptions,
    tools?: LLMTool[]
  ): Promise<string> {
    this.log(`Calling LLM: ${modelId} with ${messages.length} messages`);

    try {
      const body: any = {
        model: modelId,
        messages,
        stream: false,
        options: {
          temperature: options?.temperature || 0.2,
          top_p: options?.top_p || 0.95,
          num_predict: options?.max_tokens || 4096
        }
      };

      if (tools?.length) {
        body.tools = tools;
      }

      // Direct HTTP to Ollama - no JAR, no spawn, no shell
      const res = await fetch('http://localhost:11434/api/chat', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body)
      });

      if (!res.ok) {
        throw new Error(`Ollama HTTP error: ${res.status} ${res.statusText}`);
      }

      const data = await res.json() as any;
      return JSON.stringify(data.message);
    } catch (error: any) {
      this.log(`LLM call error: ${error.message}`);
      return `Error: LLM call failed - ${error.message}`;
    }
  }


  /**
   * Read file using Node.js fs module
   */
  async readFile(filePath: string): Promise<string> {
    try {
      return fs.promises.readFile(filePath, 'utf-8');
    } catch (error: any) {
      this.log(`Read file error: ${error.message}`);
      throw error;
    }
  }

  /**
   * Write file using Node.js fs module
   */
  async writeFile(filePath: string, content: string): Promise<string> {
    try {
      // Ensure directory exists
      const dir = path.dirname(filePath);
      await fs.promises.mkdir(dir, { recursive: true });
      await fs.promises.writeFile(filePath, content, 'utf-8');
      this.log(`File written: ${filePath}`);
      return `Successfully wrote ${filePath}`;
    } catch (error: any) {
      this.log(`Write file error: ${error.message}`);
      throw error;
    }
  }

  /**
   * Edit file using Node.js fs module
   */
  async editFile(filePath: string, oldString: string, newString: string): Promise<string> {
    try {
      const content = await fs.promises.readFile(filePath, 'utf-8');
      const updatedContent = content.replace(oldString, newString);
      await fs.promises.writeFile(filePath, updatedContent, 'utf-8');
      this.log(`File edited: ${filePath}`);
      return `Successfully edited ${filePath}`;
    } catch (error: any) {
      this.log(`Edit file error: ${error.message}`);
      throw error;
    }
  }

  /**
   * List directory using Node.js fs module
   */
  async listDirectory(dirPath: string): Promise<string> {
    try {
      const entries = await fs.promises.readdir(dirPath, { withFileTypes: true });
      const fileList = entries.map(entry => {
        const type = entry.isDirectory() ? '[DIR]' : '[FILE]';
        return `${type} ${entry.name}`;
      }).join('\n');
      return fileList;
    } catch (error: any) {
      this.log(`List directory error: ${error.message}`);
      throw error;
    }
  }

  /**
   * Regex search in files using Node.js fs module
   */
  async regexSearch(pattern: string, searchPath: string): Promise<string> {
    const results: Array<{file: string, line: number, match: string}> = [];
    const regex = new RegExp(pattern, 'g');

    try {
      const searchDir = async (dir: string) => {
        const entries = await fs.promises.readdir(dir, { withFileTypes: true });
        
        for (const entry of entries) {
          const fullPath = path.join(dir, entry.name);
          
          if (entry.isDirectory()) {
            // Skip common non-source directories
            if (['node_modules', '.git', 'build', 'dist', 'out', '.idea', '.vscode'].includes(entry.name)) {
              continue;
            }
            await searchDir(fullPath);
          } else if (entry.isFile()) {
            // Only search in text-based source files
            const ext = path.extname(entry.name).toLowerCase();
            if (['.kt', '.java', '.ts', '.js', '.py', '.go', '.rs', '.cs', '.cpp', '.c', '.h', '.hpp', '.xml', '.json', '.yaml', '.yml', '.md', '.txt'].includes(ext)) {
              try {
                const content = await fs.promises.readFile(fullPath, 'utf-8');
                const lines = content.split('\n');
                
                for (let i = 0; i < lines.length; i++) {
                  const line = lines[i];
                  const matches = line.match(regex);
                  if (matches) {
                    results.push({
                      file: fullPath,
                      line: i + 1,
                      match: line.trim()
                    });
                  }
                }
              } catch (err: any) {
                // Skip binary files or files that can't be read
                if (!err.message.includes('utf-8')) {
                  this.log(`Search error in ${fullPath}: ${err.message}`);
                }
              }
            }
          }
        }
      };

      const stat = await fs.promises.stat(searchPath);
      if (stat.isDirectory()) {
        await searchDir(searchPath);
      } else if (stat.isFile()) {
        const content = await fs.promises.readFile(searchPath, 'utf-8');
        const lines = content.split('\n');
        
        for (let i = 0; i < lines.length; i++) {
          const line = lines[i];
          const matches = line.match(regex);
          if (matches) {
            results.push({
              file: searchPath,
              line: i + 1,
              match: line.trim()
            });
          }
        }
      }

      // Format results as a readable string
      if (results.length === 0) {
        return 'No matches found';
      }
      
      return results.map(r => `${r.file}:${r.line}: ${r.match}`).join('\n');
    } catch (error: any) {
      this.log(`Regex search error: ${error.message}`);
      throw error;
    }
  }

  /**
   * Log message to output channel
   */
  private log(message: string): void {
    const timestamp = new Date().toISOString().split('T')[1].split('.')[0];
    this.outputChannel.appendLine(`[${timestamp}] ${message}`);
  }

  // ============= Mock Data for Development =============

  private getMockDiscovery(): DiscoveryResult {
    return {
      projectName: 'i2-vision',
      version: '1.0.0',
      components: [
        {
          name: 'app',
          type: 'module',
          path: 'app/src/main/kotlin',
          layer: 'application',
          dependencies: ['storage-core', 'index-provider']
        },
        {
          name: 'storage-core',
          type: 'module',
          path: 'storage-core/src/main/kotlin',
          layer: 'infrastructure',
          dependencies: []
        },
        {
          name: 'index-provider',
          type: 'module',
          path: 'index-provider/src/main/kotlin',
          layer: 'infrastructure',
          dependencies: ['storage-core']
        },
        {
          name: 'vscode-app',
          type: 'module',
          path: 'vscode-app',
          layer: 'presentation',
          dependencies: ['i2vision-cli']
        },
        {
          name: 'DiscoveryService',
          type: 'class',
          path: 'app/src/main/kotlin/core/DiscoveryService.kt',
          layer: 'application',
          dependencies: ['IndexProvider']
        },
        {
          name: 'IndexProvider',
          type: 'interface',
          path: 'index-provider/src/main/kotlin/IndexProvider.kt',
          layer: 'infrastructure',
          dependencies: []
        }
      ],
      relationships: [
        { from: 'app', to: 'storage-core', type: 'depends_on', strength: 0.9 },
        { from: 'app', to: 'index-provider', type: 'depends_on', strength: 0.8 },
        { from: 'index-provider', to: 'storage-core', type: 'depends_on', strength: 0.7 },
        { from: 'vscode-app', to: 'i2vision-cli', type: 'calls', strength: 1.0 },
        { from: 'DiscoveryService', to: 'IndexProvider', type: 'uses', strength: 0.95 }
      ],
      layers: [
        {
          name: 'presentation',
          level: 3,
          components: ['vscode-app'],
          allowedDependencies: ['application', 'infrastructure']
        },
        {
          name: 'application',
          level: 2,
          components: ['app', 'DiscoveryService'],
          allowedDependencies: ['infrastructure']
        },
        {
          name: 'infrastructure',
          level: 1,
          components: ['storage-core', 'index-provider', 'IndexProvider'],
          allowedDependencies: []
        }
      ],
      violations: []
    };
  }

  private getMockContext(filePath: string): VSLFContext {
    return {
      filePath,
      component: path.basename(filePath),
      layer: 'unknown',
      responsibilities: ['Mock responsibility'],
      dependencies: [],
      dependents: [],
      metrics: {
        complexity: 5,
        coupling: 3,
        cohesion: 7
      }
    };
  }

  private getMockTemplates(): TemplateInfo[] {
    return [
      {
        name: 'basic-kotlin',
        description: 'Basic Kotlin project structure',
        category: 'basic',
        files: [
          { path: 'build.gradle.kts', content: '// Build file', template: false },
          { path: 'settings.gradle.kts', content: '// Settings', template: false },
          { path: 'src/main/kotlin/Main.kt', content: 'fun main() {}', template: false }
        ],
        variables: [
          { name: 'projectName', description: 'Project name', required: true },
          { name: 'groupId', description: 'Maven group ID', defaultValue: 'com.example', required: false }
        ]
      }
    ];
  }
}
