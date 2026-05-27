/**
 * i2-Vision CLI Integration
 * 
 * Bridges the VSCode extension with the i2vision CLI backend
 * for real-time discovery, context extraction, and analysis.
 */

import { exec } from 'child_process';
import { promisify } from 'util';
import * as vscode from 'vscode';

const execAsync = promisify(exec);

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
    // Try to find i2vision-cli in PATH or use gradle wrapper
    this.cliPath = this.detectCLIPath();
    this.outputChannel = outputChannel || vscode.window.createOutputChannel('i2-Vision CLI');
  }

  /**
   * Detect the i2vision CLI path
   */
  private detectCLIPath(): string {
    // First, try to find i2vision-cli in PATH
    // If not found, use gradle wrapper from project root
    const projectRoot = this.findProjectRoot();
    if (projectRoot) {
      return `cd "${projectRoot}" && ./gradlew :i2vision-cli:run --args=`;
    }
    return 'i2vision-cli';
  }

  /**
   * Find the i2-vision project root
   */
  private findProjectRoot(): string | null {
    // Look for settings.gradle.kts or build.gradle.kts
    const fs = require('fs');
    const path = require('path');
    
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
      const command = `${this.cliPath} 'discover --json --dir "${this.workspaceRoot}"'`;
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
      const command = `${this.cliPath} 'context --file "${filePath}" --json'`;
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
      const command = `${this.cliPath} 'templates --list --json'`;
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

      const command = `${this.cliPath} 'create --template "${templateName}" --name "${projectName}" ${varsString}'`;
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
      const command = `${this.cliPath} 'analyze --violations --json'`;
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
      await execAsync(`${this.cliPath} 'version'`, {
        cwd: this.workspaceRoot,
        timeout: 5000
      });
      return true;
    } catch {
      return false;
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
      component: filePath.includes('app') ? 'app' : 'unknown',
      layer: filePath.includes('core') ? 'infrastructure' : 'application',
      responsibilities: ['Process requests', 'Manage data'],
      dependencies: ['storage-core'],
      dependents: ['vscode-app'],
      metrics: {
        complexity: 15,
        coupling: 3,
        cohesion: 8
      }
    };
  }

  private getMockTemplates(): TemplateInfo[] {
    return [
      {
        name: 'Basic Template',
        description: 'Simple project structure for small applications',
        category: 'basic',
        files: [
          { path: 'src/main.kt', content: 'fun main() { println("Hello") }', template: false },
          { path: 'build.gradle.kts', content: '// Build config', template: false }
        ],
        variables: [
          { name: 'projectName', description: 'Project name', required: true },
          { name: 'version', description: 'Initial version', defaultValue: '1.0.0', required: false }
        ]
      },
      {
        name: 'Advanced Template',
        description: 'Multi-module project with architecture layers',
        category: 'advanced',
        files: [
          { path: 'app/src/main.kt', content: '// Application layer', template: false },
          { path: 'core/src/main.kt', content: '// Core layer', template: false },
          { path: 'settings.gradle.kts', content: '// Settings', template: false }
        ],
        variables: [
          { name: 'projectName', description: 'Project name', required: true },
          { name: 'packageName', description: 'Base package name', required: true },
          { name: 'version', description: 'Initial version', defaultValue: '1.0.0', required: false }
        ]
      },
      {
        name: 'Enterprise Template',
        description: 'Full enterprise architecture with all layers',
        category: 'enterprise',
        files: [
          { path: 'presentation/src/main.kt', content: '// Presentation layer', template: false },
          { path: 'application/src/main.kt', content: '// Application layer', template: false },
          { path: 'domain/src/main.kt', content: '// Domain layer', template: false },
          { path: 'infrastructure/src/main.kt', content: '// Infrastructure layer', template: false }
        ],
        variables: [
          { name: 'projectName', description: 'Project name', required: true },
          { name: 'organization', description: 'Organization name', required: true },
          { name: 'version', description: 'Initial version', defaultValue: '1.0.0', required: false }
        ]
      },
      {
        name: 'Microservice Template',
        description: 'Containerized microservice with API and database',
        category: 'microservice',
        files: [
          { path: 'src/main.kt', content: '// Service entry point', template: false },
          { path: 'Dockerfile', content: 'FROM openjdk:21', template: false },
          { path: 'docker-compose.yml', content: 'version: "3.8"', template: false }
        ],
        variables: [
          { name: 'serviceName', description: 'Service name', required: true },
          { name: 'port', description: 'Service port', defaultValue: '8080', required: false },
          { name: 'database', description: 'Database type', defaultValue: 'postgresql', required: false }
        ]
      }
    ];
  }

  /**
   * Call LLM through CLI
   */
  async callLLM(
    modelId: string,
    messages: Array<{role: string, content: string}>,
    options?: { temperature?: number; top_p?: number; max_tokens?: number },
    tools?: Array<any>
  ): Promise<string> {
    this.log(`Calling LLM: ${modelId}`);
    
    // For now, return a mock response - this would integrate with the actual CLI
    const systemMsg = messages.find(m => m.role === 'system');
    const userMsg = messages.find(m => m.role === 'user');
    
    this.log(`System prompt: ${systemMsg?.content?.substring(0, 100)}...`);
    this.log(`User input: ${userMsg?.content?.substring(0, 100)}...`);
    
    // Mock response for testing
    return `reasoning: I understand your request. Let me analyze the codebase.
tool_call: {"tool": "i2vision_get_context", "args": {}}
EOS`;
  }

  /**
   * Read a file
   */
  async readFile(filePath: string): Promise<string> {
    this.log(`Reading file: ${filePath}`);
    
    try {
      const fs = require('fs');
      const content = fs.readFileSync(filePath, 'utf-8');
      return content;
    } catch (error: any) {
      throw new Error(`Failed to read file: ${error.message}`);
    }
  }

  /**
   * Write a file
   */
  async writeFile(filePath: string, content: string): Promise<string> {
    this.log(`Writing file: ${filePath}`);
    
    try {
      const fs = require('fs');
      const path = require('path');
      
      // Ensure directory exists
      const dir = path.dirname(filePath);
      if (!fs.existsSync(dir)) {
        fs.mkdirSync(dir, { recursive: true });
      }
      
      fs.writeFileSync(filePath, content, 'utf-8');
      return `File written successfully: ${filePath}`;
    } catch (error: any) {
      throw new Error(`Failed to write file: ${error.message}`);
    }
  }

  /**
   * Edit a file (replace text)
   */
  async editFile(filePath: string, oldString: string, newString: string): Promise<string> {
    this.log(`Editing file: ${filePath}`);
    
    try {
      const fs = require('fs');
      const content = fs.readFileSync(filePath, 'utf-8');
      
      if (!content.includes(oldString)) {
        throw new Error('Old string not found in file');
      }
      
      const newContent = content.replace(oldString, newString);
      fs.writeFileSync(filePath, newContent, 'utf-8');
      
      return `File edited successfully: ${filePath}`;
    } catch (error: any) {
      throw new Error(`Failed to edit file: ${error.message}`);
    }
  }

  /**
   * List directory contents
   */
  async listDirectory(dirPath: string): Promise<string> {
    this.log(`Listing directory: ${dirPath}`);
    
    try {
      const fs = require('fs');
      const items = fs.readdirSync(dirPath, { withFileTypes: true });
      
      const result = items.map((item: any) => {
        const type = item.isDirectory() ? '[DIR]' : '[FILE]';
        return `${type} ${item.name}`;
      }).join('\n');
      
      return result;
    } catch (error: any) {
      throw new Error(`Failed to list directory: ${error.message}`);
    }
  }

  /**
   * Regex search in files
   */
  async regexSearch(pattern: string, path?: string): Promise<string> {
    this.log(`Regex search: ${pattern} in ${path || 'workspace'}`);
    
    try {
      const fs = require('fs');
      const pathModule = require('path');
      
      const searchDir = path || this.workspaceRoot;
      const results: string[] = [];
      const regex = new RegExp(pattern, 'g');
      
      const searchRecursive = (dir: string) => {
        const items = fs.readdirSync(dir, { withFileTypes: true });
        
        for (const item of items as any[]) {
          const fullPath = pathModule.join(dir, item.name);
          
          // Skip common directories
          if (item.name === 'node_modules' || item.name === 'build' || item.name === '.git') {
            continue;
          }
          
          if (item.isDirectory()) {
            searchRecursive(fullPath);
          } else if (item.isFile() && /\.(kt|java|ts|js|py|yaml|yml|json|xml|gradle)$/.test(item.name)) {
            try {
              const content = fs.readFileSync(fullPath, 'utf-8');
              const matches = content.match(regex);
              
              if (matches) {
                results.push(`${fullPath}: ${matches.length} match(es)`);
              }
            } catch (e) {
              // Skip binary files
            }
          }
        }
      };
      
      searchRecursive(searchDir);
      
      return results.join('\n') || 'No matches found';
    } catch (error: any) {
      throw new Error(`Failed to search: ${error.message}`);
    }
  }

  /**
   * Get loaded models from Ollama
   */
  async getLoadedModels(): Promise<Array<{name: string, provider: string}>> {
    this.log('Getting loaded models');
    
    try {
      // Try to get models from Ollama
      const { exec } = require('child_process');
      
      return new Promise((resolve) => {
        exec('ollama list', (error: any, stdout: string) => {
          if (error) {
            this.log(`Ollama command failed: ${error.message}`);
            resolve([]);
            return;
          }
          
          const models = stdout
            .split('\n')
            .filter(line => line.trim().length > 0 && !line.startsWith('NAME'))
            .map(line => {
              const parts = line.split(/\s+/);
              return {
                name: parts[0],
                provider: 'ollama'
              };
            });
          
          this.log(`Found ${models.length} models`);
          resolve(models);
        });
      });
    } catch (error: any) {
      this.log(`Error getting models: ${error.message}`);
      return [];
    }
  }

  /**
   * Get discovery results
   */
  async getDiscovery(): Promise<DiscoveryResult> {
    this.log('Getting discovery results');
    return await this.runDiscovery();
  }
}


