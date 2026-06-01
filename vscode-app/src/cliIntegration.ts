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
import * as vscode from 'vscode';

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
  private cliPath?: string;

  constructor(workspaceRoot: string, outputChannel?: any) {
    this.workspaceRoot = workspaceRoot;
    this.outputChannel = outputChannel;
    
    // Get CLI path from VSCode settings
    try {
      const config = vscode.workspace.getConfiguration('i2vision');
      this.cliPath = config.get<string>('cli.path');
      if (this.cliPath) {
        this.log(`CLI path from settings: ${this.cliPath}`);
      }
    } catch (error: any) {
      this.log(`Could not read CLI path from settings: ${error.message}`);
    }
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
    
    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>`;
        await fs.promises.writeFile(path.join(targetDir, 'pom.xml'), pomContent);
        
      } else if (templateName === 'express') {
        const projectName = variables.projectName || 'my-api';
        
        // Create src directory
        const srcDir = path.join(targetDir, 'src');
        await fs.promises.mkdir(srcDir, { recursive: true });
        
        // Create index.ts
        const indexContent = `import express, { Application, Request, Response } from 'express';

const app: Application = express();
const port = process.env.PORT || 3000;

app.use(express.json());

app.get('/', (req: Request, res: Response) => {
  res.json({ message: 'Hello World!' });
});

app.listen(port, () => {
  console.log(\`Server running at http://localhost:\${port}\`);
});`;
        await fs.promises.writeFile(path.join(srcDir, 'index.ts'), indexContent);
        
        // Create package.json
        const packageContent = `{
  "name": "${projectName}",
  "version": "1.0.0",
  "description": "Express.js REST API",
  "main": "dist/index.js",
  "scripts": {
    "build": "tsc",
    "start": "node dist/index.js",
    "dev": "ts-node src/index.ts"
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
}`;
        await fs.promises.writeFile(path.join(targetDir, 'package.json'), packageContent);
      }
      
      this.log(`Project created successfully in ${targetDir}`);
      return true;
    } catch (error: any) {
      this.log(`Error creating project: ${error.message}`);
      return false;
    }
  }

  /**
   * Get context for a specific file
   */
  async getContext(filePath: string): Promise<FileContext | null> {
    this.log(`Getting context for: ${filePath}`);
    try {
      const content = await fs.promises.readFile(filePath, 'utf8');
      const ext = path.extname(filePath).toLowerCase();
      
      let language = 'unknown';
      const languageMap: Record<string, string> = {
        '.java': 'java',
        '.ts': 'typescript',
        '.js': 'javascript',
        '.py': 'python',
        '.go': 'go',
        '.rs': 'rust',
        '.kt': 'kotlin',
        '.scala': 'scala',
        '.cs': 'csharp',
        '.cpp': 'cpp',
        '.c': 'c',
        '.rb': 'ruby',
        '.php': 'php',
        '.swift': 'swift',
        '.sql': 'sql',
        '.xml': 'xml',
        '.json': 'json',
        '.yaml': 'yaml',
        '.yml': 'yaml',
        '.md': 'markdown',
        '.html': 'html',
        '.css': 'css',
        '.scss': 'scss',
        '.sh': 'shell',
        '.bash': 'shell'
      };
      
      language = languageMap[ext] || 'unknown';
      
      const context: FileContext = {
        path: filePath,
        name: path.basename(filePath),
        language,
        content,
        size: content.length,
        lines: content.split('\n').length,
        imports: this.extractImports(content, language),
        classes: this.extractClasses(content, language),
        functions: this.extractFunctions(content, language)
      };
      
      this.log(`Context extracted: ${context.classes.length} classes, ${context.functions.length} functions`);
      return context;
    } catch (error: any) {
      this.log(`Error getting context: ${error.message}`);
      return null;
    }
  }

  /**
   * Extract imports from file content
   */
  private extractImports(content: string, language: string): string[] {
    const imports: string[] = [];
    
    if (language === 'java') {
      const importRegex = /^import\s+(static\s+)?([\w.*]+);/gm;
      let match;
      while ((match = importRegex.exec(content)) !== null) {
        imports.push(match[2]);
      }
    } else if (language === 'typescript' || language === 'javascript') {
      const importRegex = /^import\s+.*?\s+from\s+['"](.+?)['"];?/gm;
      let match;
      while ((match = importRegex.exec(content)) !== null) {
        imports.push(match[1]);
      }
    } else if (language === 'python') {
      const importRegex = /^(?:import\s+(\w+)|from\s+(\w+)\s+import)/gm;
      let match;
      while ((match = importRegex.exec(content)) !== null) {
        imports.push(match[1] || match[2]);
      }
    }
    
    return imports;
  }

  /**
   * Extract class names from file content
   */
  private extractClasses(content: string, language: string): string[] {
    const classes: string[] = [];
    
    if (language === 'java' || language === 'typescript' || language === 'csharp') {
      const classRegex = /^(?:public\s+|private\s+|protected\s+)?(?:abstract\s+|final\s+)?class\s+(\w+)/gm;
      let match;
      while ((match = classRegex.exec(content)) !== null) {
        classes.push(match[1]);
      }
    } else if (language === 'python') {
      const classRegex = /^class\s+(\w+)/gm;
      let match;
      while ((match = classRegex.exec(content)) !== null) {
        classes.push(match[1]);
      }
    }
    
    return classes;
  }

  /**
   * Extract function/method names from file content
   */
  private extractFunctions(content: string, language: string): string[] {
    const functions: string[] = [];
    
    if (language === 'java' || language === 'typescript' || language === 'csharp') {
      const funcRegex = /^(?:public\s+|private\s+|protected\s+)?(?:static\s+)?(?:\w+(?:<[^>]+>)?\s+)?(\w+)\s*\([^)]*\)\s*(?:\{|throws)/gm;
      let match;
      while ((match = funcRegex.exec(content)) !== null) {
        if (!['if', 'for', 'while', 'switch', 'catch', 'constructor'].includes(match[1])) {
          functions.push(match[1]);
        }
      }
    } else if (language === 'python') {
      const funcRegex = /^def\s+(\w+)\s*\(/gm;
      let match;
      while ((match = funcRegex.exec(content)) !== null) {
        functions.push(match[1]);
      }
    }
    
    return functions;
  }

  /**
   * Check if CLI is available
   */
  async isAvailable(): Promise<boolean> {
    try {
      // Check if CLI path is configured
      if (!this.cliPath) {
        this.log('CLI path not configured in settings');
        return false;
      }
      
      // Check if JAR file exists
      const jarExists = await fs.promises.access(this.cliPath, fs.constants.F_OK)
        .then(() => true)
        .catch(() => false);
      
      if (!jarExists) {
        this.log(`CLI JAR not found at: ${this.cliPath}`);
        return false;
      }
      
      this.log(`CLI JAR found at: ${this.cliPath}`);
      
      // Try to run CLI with --version or --help to verify it works
      try {
        const command = `java -jar "${this.cliPath}" --version`;
        const { stdout } = await this.runCommand(command);
        this.log(`CLI version check successful: ${stdout.trim()}`);
        return true;
      } catch (error: any) {
        this.log(`CLI version check failed: ${error.message}`);
        // JAR exists but might not be executable, still return true
        return true;
      }
    } catch (error: any) {
      this.log(`CLI availability check error: ${error.message}`);
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
  metadata?: Record<string, any>;
}

export interface RelationshipInfo {
  from: string;
  to: string;
  type: string;
  description?: string;
}

export interface LayerInfo {
  name: string;
  description?: string;
  components: string[];
  allowedDependencies?: string[];
  level?: number;
}

export interface DiscoveryResult {
  projectName: string;
  version: string;
  components: ComponentInfo[];
  relationships: RelationshipInfo[];
  layers: LayerInfo[];
  violations?: ViolationInfo[];
}

/**
 * Template types
 */
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
  description: string;
  category: string;
  files: TemplateFile[];
  variables: TemplateVariable[];
}

/**
 * File context for LLM
 */
export interface FileContext {
  path: string;
  name: string;
  language: string;
  content: string;
  size: number;
  lines: number;
  imports: string[];
  classes: string[];
  functions: string[];
  filePath?: string;
  component?: string;
  layer?: string;
  dependencies?: string[];
}

/**
 * Architecture violation info
 */
export interface ViolationInfo {
  severity: 'error' | 'warning' | 'info';
  rule: string;
  message: string;
  file?: string;
  line?: number;
  suggestion?: string;
  source?: string;
  target?: string;
}
