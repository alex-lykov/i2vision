/**
 * CLI Integration - Direct Ollama HTTP calls
 * 
 * This module provides direct HTTP integration with Ollama,
 * bypassing the need for external CLI tools.
 */

import * as fs from 'fs';
import * as path from 'path';
import { exec } from 'child_process';
import { promisify } from 'util';

const execAsync = promisify(exec);

/**
 * LLM Message format
 */
export interface LLMMessage {
  role: string;
  content: string;
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
 * CLI class for Ollama integration
 */
export class CLI {
  private outputChannel?: any;
  private ollamaUrl: string = 'http://localhost:11434';
  private workspaceRoot: string;

  constructor(workspaceRoot: string, outputChannel?: any) {
    this.workspaceRoot = workspaceRoot;
    this.outputChannel = outputChannel;
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
   */
  async callLLM(
    modelId: string,
    messages: LLMMessage[],
    options?: LLMOptions,
    tools?: LLMTool[]
  ): Promise<LLMResponse> {
    this.log(`Calling LLM: ${modelId} with ${messages.length} messages`);
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
        stream: false,
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

      // Direct HTTP to Ollama
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
      const toolCalls: LLMToolCall[] = toolCallsData.map((tc: any) => ({
        name: tc.function?.name || '',
        arguments: tc.function?.arguments || {}
      }));
      
      if (toolCalls.length > 0) {
        this.log(`Tool calls: ${JSON.stringify(toolCalls).substring(0, 300)}`);
      }
      
      this.log(`=== DIAGNOSTIC: LLM CALL END ===`);
      
      return {
        content,
        toolCalls
      };
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
   * Read file using Node.js fs module
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
   * Write file using Node.js fs module
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
   * List files in a directory
   */
  async listFiles(dirPath: string, recursive: boolean = false): Promise<string[]> {
    this.log(`Listing files in: ${dirPath} (recursive: ${recursive})`);
    try {
      const files: string[] = [];
      
      const walk = async (dir: string) => {
        const entries = await fs.promises.readdir(dir, { withFileTypes: true });
        for (const entry of entries) {
          const fullPath = path.join(dir, entry.name);
          if (entry.isDirectory()) {
            if (recursive && !entry.name.startsWith('.')) {
              await walk(fullPath);
            }
          } else {
            files.push(fullPath);
          }
        }
      };
      
      await walk(dirPath);
      this.log(`Found ${files.length} files`);
      return files;
    } catch (error: any) {
      this.log(`Error listing files: ${error.message}`);
      throw error;
    }
  }

  /**
   * Run a shell command
   */
  async runCommand(command: string, workingDir?: string): Promise<{ stdout: string, stderr: string }> {
    this.log(`Running command: ${command}`);
    try {
      const options = workingDir ? { cwd: workingDir } : {};
      const { stdout, stderr } = await execAsync(command, options);
      this.log(`Command completed`);
      return { stdout, stderr };
    } catch (error: any) {
      this.log(`Command failed: ${error.message}`);
      throw error;
    }
  }

  /**
   * Search for a pattern in files
   */
  async searchFiles(pattern: string, dirPath?: string): Promise<string[]> {
    this.log(`Searching for pattern: ${pattern}`);
    try {
      const searchDir = dirPath || process.cwd();
      const command = `powershell -Command "Get-ChildItem -Path '${searchDir}' -Recurse -File | Select-String -Pattern '${pattern}' | Select-Object -First 50"`;
      const { stdout } = await this.runCommand(command);
      
      const results = stdout.split('\n')
        .filter(line => line.trim())
        .slice(0, 50);
      
      this.log(`Found ${results.length} matches`);
      return results;
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
        path: path.join(rootDir, dir)
      }));
      
      const result: DiscoveryResult = {
        projectName: path.basename(rootDir),
        version: '1.0.0',
        components,
        relationships: [],
        layers: []
      };
      
      this.log(`Discovery complete: ${components.length} components found`);
      return result;
    } catch (error: any) {
      this.log(`Discovery failed: ${error.message}`);
      return {
        projectName: 'unknown',
        version: '0.0.0',
        components: [],
        relationships: [],
        layers: []
      };
    }
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
            { name: 'artifactId', description: 'Maven artifact ID', required: true, defaultValue: 'my-service' },
            { name: 'packageName', description: 'Base package name', required: true, defaultValue: 'com.example.service' }
          ]
        },
        {
          name: 'express',
          path: 'templates/express',
          type: 'project',
          description: 'Express.js REST API template',
          category: 'Node.js',
          files: [
            { path: 'src/app.ts', content: '' },
            { path: 'package.json', content: '' }
          ],
          variables: [
            { name: 'appName', description: 'Application name', required: true, defaultValue: 'my-api' },
            { name: 'port', description: 'Server port', required: false, defaultValue: '3000' }
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
   * Create a new project from template
   */
  async createProject(templateName: string, projectName: string, variables: Record<string, string>): Promise<boolean> {
    this.log(`Creating project '${projectName}' from template '${templateName}'...`);
    try {
      const rootDir = this.workspaceRoot || process.cwd();
      const projectPath = path.join(rootDir, projectName);
      
      // Create project directory
      await fs.promises.mkdir(projectPath, { recursive: true });
      
      // Create basic structure based on template
      if (templateName === 'spring-boot') {
        const packageName = variables.packageName?.replace(/\./g, '/') || 'com/example/service';
        const srcPath = path.join(projectPath, 'src', 'main', 'java', packageName);
        await fs.promises.mkdir(srcPath, { recursive: true });
        
        // Create main application class
        const mainClass = `package ${variables.packageName || 'com.example.service'};

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
`;
        await fs.promises.writeFile(path.join(srcPath, 'Application.java'), mainClass);
        
        // Create pom.xml
        const pom = `<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    
    <groupId>${variables.groupId || 'com.example'}</groupId>
    <artifactId>${variables.artifactId || projectName}</artifactId>
    <version>1.0.0</version>
    <packaging>jar</packaging>
    
    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.2.0</version>
    </parent>
    
    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
    </dependencies>
</project>
`;
        await fs.promises.writeFile(path.join(projectPath, 'pom.xml'), pom);
        
      } else if (templateName === 'express') {
        const srcPath = path.join(projectPath, 'src');
        await fs.promises.mkdir(srcPath, { recursive: true });
        
        // Create main app file
        const app = `import express from 'express';

const app = express();
const PORT = ${variables.port || '3000'};

app.get('/', (req, res) => {
  res.json({ message: 'Hello World!' });
});

app.listen(PORT, () => {
  console.log(\`Server running on port \${PORT}\`);
});
`;
        await fs.promises.writeFile(path.join(srcPath, 'app.ts'), app);
        
        // Create package.json
        const packageJson = `{
  "name": "${projectName}",
  "version": "1.0.0",
  "main": "dist/app.js",
  "scripts": {
    "build": "tsc",
    "start": "node dist/app.js",
    "dev": "ts-node src/app.ts"
  },
  "dependencies": {
    "express": "^4.18.2"
  },
  "devDependencies": {
    "@types/express": "^4.17.21",
    "@types/node": "^20.10.0",
    "typescript": "^5.3.0",
    "ts-node": "^10.9.2"
  }
}
`;
        await fs.promises.writeFile(path.join(projectPath, 'package.json'), packageJson);
      }
      
      this.log(`Project '${projectName}' created successfully at ${projectPath}`);
      return true;
    } catch (error: any) {
      this.log(`Error creating project: ${error.message}`);
      return false;
    }
  }

  /**
   * Get context for a specific file
   */
  async getContext(filePath: string): Promise<any> {
    this.log(`Getting context for: ${filePath}`);
    try {
      // Return mock context for testing
      return {
        filePath,
        component: {
          name: path.basename(filePath),
          type: 'file',
          path: filePath
        },
        layer: 'default',
        dependencies: []
      };
    } catch (error: any) {
      this.log(`Error getting context: ${error.message}`);
      return null;
    }
  }

  /**
   * Check if CLI is available
   */
  async isAvailable(): Promise<boolean> {
    try {
      // For now, always return false as we're using direct HTTP
      return false;
    } catch (error: any) {
      return false;
    }
  }

  /**
   * Analyze architecture violations
   */
  async analyzeViolations(): Promise<ViolationInfo[]> {
    this.log('Analyzing architecture violations...');
    try {
      // Return mock violations for now
      return [];
    } catch (error: any) {
      this.log(`Error analyzing violations: ${error.message}`);
      return [];
    }
  }
}

/**
 * Discovery result types
 */
export interface ComponentInfo {
  name: string;
  layer?: string;
  file?: string;
  path?: string;
  type?: string;
  responsibilities?: string[];
  dependencies?: string[];
}

export interface RelationshipInfo {
  source: string;
  target: string;
  type: string;
}

export interface LayerInfo {
  name: string;
  level: number;
  components: string[];
}

export interface ViolationInfo {
  message: string;
  severity: 'error' | 'warning';
  source?: string;
  target?: string;
  rule?: string;
}

export interface DiscoveryResult {
  projectName: string;
  version: string;
  components: ComponentInfo[];
  relationships: RelationshipInfo[];
  layers: LayerInfo[];
  violations?: ViolationInfo[];
}

export interface TemplateVariable {
  name: string;
  description: string;
  required: boolean;
  defaultValue?: string;
}

export interface TemplateFile {
  path: string;
  content: string;
}

export interface TemplateInfo {
  name: string;
  path: string;
  type: string;
  description?: string;
  category?: string;
  files: TemplateFile[];
  variables: TemplateVariable[];
}
