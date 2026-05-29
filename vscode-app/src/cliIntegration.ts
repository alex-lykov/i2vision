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
    const [cmd, ...args] = command.split(' ');
    const child = spawn(cmd, args, {
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
 * i2-Vision CLI wrapper
 */
export class I2VisionCLI {
  private workspaceRoot: string;
  private cliPath: string;
  private outputChannel: vscode.OutputChannel;

  constructor(workspaceRoot: string, outputChannel?: vscode.OutputChannel) {
    this.workspaceRoot = workspaceRoot;
    // Initialize outputChannel FIRST - detectCLIPath() calls this.log() which needs it
    this.outputChannel = outputChannel || vscode.window.createOutputChannel('i2-Vision CLI');
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
      this.log(`Using custom CLI path: ${customPath}`);
      return `java -jar "${customPath}"`;
    }

    // Second, try to find the shadow JAR
    const projectRoot = this.findProjectRoot();
    if (projectRoot) {
      const shadowJar = path.join(
        projectRoot,
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
        projectRoot,
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

      // Last fallback: gradle wrapper
      this.log('Using gradle wrapper for CLI');
      return `cd "${projectRoot}" && .\\gradlew.bat :i2vision-cli:run --args=`;
    }

    // Last resort: try PATH
    return 'i2vision-cli';
  }

  /**
   * Find the i2-vision project root
   */
  private findProjectRoot(): string | null {
    let currentDir = this.workspaceRoot;
    const maxDepth = 5;
    let depth = 0;

    while (depth < maxDepth) {
      if (fs.existsSync(path.join(currentDir, 'settings.gradle.kts'))) {
        return currentDir;
      }
      const parentDir = path.dirname(currentDir);
      if (parentDir === currentDir) {
        break;
      }
      currentDir = parentDir;
      depth++;
    }

    return null;
  }

  /**
   * Run discovery on the workspace
   */
  async runDiscovery(): Promise<DiscoveryResult> {
    this.log('Running discovery...');
    
    try {
      const command = `${this.cliPath} discover --json --dir "${this.workspaceRoot}"`;
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
      const command = `${this.cliPath} presets --list --json`;
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

      const command = `${this.cliPath} create --template "${templateName}" --name "${projectName}" ${varsString}`;
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
      const command = `${this.cliPath} analyze --violations --json`;
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
   * Get loaded models from CLI
   */
  async getLoadedModels(): Promise<Array<{name: string, provider: string}>> {
    try {
      const command = `${this.cliPath} models --list --json`;
      const { stdout } = await execAsync(command, {
        cwd: this.workspaceRoot,
        timeout: 5000
      });
      return JSON.parse(stdout) as Array<{name: string, provider: string}>;
    } catch (error: any) {
      this.log(`Failed to get models: ${error.message}`);
      return [];
    }
  }

  /**
   * Call LLM through CLI
   */
  async callLLM(
    modelId: string,
    messages: Array<{role: string, content: string}>,
    options: {temperature?: number, top_p?: number, max_tokens?: number},
    tools?: Array<{
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
    }>
  ): Promise<string> {
    try {
      this.log(`Calling Ollama API directly: ${modelId}`);
      
      // Build Ollama API request
      const requestBody: any = {
        model: modelId,
        messages: messages,
        stream: false,
        options: {
          temperature: options.temperature ?? 0.7,
          top_p: options.top_p ?? 0.9,
          num_predict: options.max_tokens ?? 2048
        }
      };

      // Include tools if provided
      if (tools && tools.length > 0) {
        this.log(`Including ${tools.length} tools in request`);
        requestBody.tools = tools;
      }

      // Direct HTTP call to Ollama REST API - no shell, no spawn, no cd
      const response = await fetch('http://localhost:11434/api/chat', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(requestBody)
      });

      if (!response.ok) {
        const errorText = await response.text();
        throw new Error(`Ollama API error: ${response.status} ${response.statusText} - ${errorText}`);
      }

      const data: any = await response.json();
      this.log(`Ollama response received: ${JSON.stringify(data).length} chars`);
      
      // Return the full message object as JSON so AgentBridge can extract tool_calls
      // Include both content and tool_calls if present
      return JSON.stringify(data.message);
    } catch (error: any) {
      this.log(`LLM call error: ${error.message}`);
      this.log(`Stack: ${error.stack}`);
      throw error;
    }
  }

  /**
   * Read file through CLI
   */
  async readFile(filePath: string): Promise<string> {
    try {
      const command = `${this.cliPath} read --file "${filePath}"`;
      const { stdout } = await execAsync(command, {
        cwd: this.workspaceRoot
      });
      return stdout;
    } catch (error: any) {
      this.log(`Read file error: ${error.message}`);
      throw error;
    }
  }

  /**
   * Write file through CLI
   */
  async writeFile(filePath: string, content: string): Promise<string> {
    try {
      const command = `${this.cliPath} write --file "${filePath}"`;
      const stdout = await execWithInput(command, content, {
        cwd: this.workspaceRoot
      });
      return stdout;
    } catch (error: any) {
      this.log(`Write file error: ${error.message}`);
      throw error;
    }
  }

  /**
   * Edit file through CLI
   */
  async editFile(filePath: string, oldString: string, newString: string): Promise<string> {
    try {
      const payload = {
        path: filePath,
        old_string: oldString,
        new_string: newString
      };

      const command = `${this.cliPath} edit --json`;
      const stdout = await execWithInput(command, JSON.stringify(payload), {
        cwd: this.workspaceRoot
      });
      return stdout;
    } catch (error: any) {
      this.log(`Edit file error: ${error.message}`);
      throw error;
    }
  }

  /**
   * List directory through CLI
   */
  async listDirectory(dirPath: string): Promise<string> {
    try {
      const command = `${this.cliPath} list --dir "${dirPath}"`;
      const { stdout } = await execAsync(command, {
        cwd: this.workspaceRoot
      });
      return stdout;
    } catch (error: any) {
      this.log(`List directory error: ${error.message}`);
      throw error;
    }
  }

  /**
   * Regex search through CLI
   */
  async regexSearch(pattern: string, searchPath: string): Promise<string> {
    try {
      const command = `${this.cliPath} search --pattern "${pattern}" --path "${searchPath}"`;
      const { stdout } = await execAsync(command, {
        cwd: this.workspaceRoot
      });
      return stdout;
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
