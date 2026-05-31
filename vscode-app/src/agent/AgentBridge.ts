/**
 * AgentBridge - Bridge between VSCode extension and conf-agent-core
 * 
 * This class wraps the agent core functionality and provides a clean API
 * for the AgentTabManager to interact with configured agents.
 * 
 * UPDATED: Fixed tool call parsing, improved logging, better error handling
 * DIAGNOSTIC: Added detailed logging to trace response flow
 */

import * as vscode from 'vscode';
import * as fs from 'fs';
import * as path from 'path';
import { CLI, LLMResponse } from '../cliIntegration';

/**
 * Agent configuration interface (matches YAML structure)
 */
export interface AgentConfig {
  key: string;
  agentType: string;
  version: string;
  isActive: boolean;
  
  // Prompt section
  systemPromptTemplate: string;
  templateVariables: Record<string, string>;
  ruleSetKeys?: string[];
  parserTemplateName?: string;
  
  // Model section
  model: {
    id: string;
    provider: string;
    contextLength: number;
    maxOutputTokens: number;
    temperature: number;
    topP: number;
  };
  
  // LLM behavior section
  llm: {
    timeoutSeconds: number;
    modificationTimeoutSeconds: number;
    finalTurnBonusSeconds: number;
    maxRetries: number;
    retryBackoffMs: number[];
  };
  
  // Formatting rules section
  formattingRules: {
    rules: string;
    brief: string;
    reasoningHeader: string;
    toolCallHeader: string;
    eosMarker: string;
  };
  
  // Iteration section
  iterationSettings: {
    maxIterations: number;
    maxConsecutiveToolCalls: number;
    enableKickstart: boolean;
    kickstartMinInvalidOutputs: number;
  };
  
  // Tool selection section
  toolSelection: {
    requiredToolsForModification: string[];
    defaultRelevanceThreshold: number;
    maxToolsPerTask: number;
    toolTimeoutSeconds: number;
  };
  
  // Safety section
  safety: {
    modificationKeywords: string[];
    listingKeywords: string[];
    listingModificationExclusions: string[];
    blockGeneratedPaths: string[];
    allowNewFileCreationPatterns: string[];
  };
  
  // Parsing section
  parsing: {
    enabledParsers: string[];
    headerPattern: string;
    toolCallPattern: string;
    malformedPattern: string;
    maxResponseSize: number;
    maxProseChars: number;
  };
  
  // Repair strategies section
  repairStrategies: any[];
  
  // Discovery section
  discovery: {
    maxSearchTerms: number;
    maxCandidates: number;
    frameworkProfiles: Record<string, any>;
  };
  
  // Execution section
  execution: {
    enableBuildVerification: boolean;
    buildCommand: string;
    buildTimeoutSeconds: number;
    enableSynthesis: boolean;
    synthesisOnlyForNonModification: boolean;
    fileOperations: {
      mode: string;
      shell: {
        executable: string;
        useNoProfile: boolean;
        readFileEnabled: boolean;
        writeFileEnabled: boolean;
        listDirectoryEnabled: boolean;
        regexSearchEnabled: boolean;
      };
    };
  };
  
  // Formatting section
  formatting: {
    chunkSize: number;
    delayMs: number;
    maxObservationChars: number;
  };
  
  // Streaming section
  streaming: {
    enabled: boolean;
    methodCandidates: string[];
    fallbackToNonStreaming: boolean;
    fallbackChunkSize: number;
    fallbackChunkDelayMs: number;
  };
  
  // MCP section
  mcp: {
    enabled: boolean;
    injectClusterContext: boolean;
    directCliEnabled: boolean;
    allowedToolPrefixes: string[];
    strictToolNamePolicy: boolean;
  };
}

/**
 * Tool call record from agent response
 */
export interface ToolCall {
  toolName: string;
  args: Record<string, any>;
  result?: string;
  error?: string;
}

/**
 * Interaction record for history tracking
 */
export interface InteractionRecord {
  timestamp: number;
  userInput: string;
  agentResponse: string;
  toolCalls: string[];
  iterations: number;
  durationMs: number;
}

/**
 * Agent response after processing
 */
export interface AgentResponse {
  finalText: string;
  toolCalls: ToolCall[];
  iterations: number;
  durationMs: number;
  success: boolean;
  error?: string;
}

/**
 * Context for agent processing
 */
export interface ProcessContext {
  currentFile?: string;
  projectName?: string;
  task?: string;
}

/**
 * LLM Tool Call from API
 */
export interface LLMToolCall {
  name: string;
  arguments: Record<string, any>;
}

/**
 * AgentBridge - Manages agent lifecycle and communication
 */
export class AgentBridge {
  private config: AgentConfig;
  private cli: CLI;
  private isInitialized: boolean = false;
  private outputChannel?: vscode.OutputChannel;

  constructor(config: AgentConfig, outputChannel?: vscode.OutputChannel) {
    this.config = config;
    this.outputChannel = outputChannel;
    
    // Initialize CLI integration
    const workspaceRoot = vscode.workspace.workspaceFolders?.[0]?.uri.fsPath || '';
    this.cli = new CLI(workspaceRoot, outputChannel);
  }

  /**
   * Initialize the agent bridge
   */
  async initialize(): Promise<void> {
    if (this.isInitialized) {
      return;
    }

    this.log(`Initializing agent: ${this.config.key} (type: ${this.config.agentType})`);
    this.log(`Model: ${this.config.model.id} (${this.config.model.provider})`);
    this.log(`Max iterations: ${this.config.iterationSettings.maxIterations}`);
    
    this.isInitialized = true;
    this.log(`Agent ${this.config.key} initialized successfully`);
  }

  /**
   * Process user input through the agent
   */
  async process(userInput: string, context?: ProcessContext): Promise<AgentResponse> {
    if (!this.isInitialized) {
      await this.initialize();
    }

    const startTime = Date.now();
    this.log(`Processing input: "${userInput.substring(0, 50)}..."`);

    try {
      // Prepare template variables
      const templateVars = {
        ...this.config.templateVariables,
        currentFile: context?.currentFile || this.config.templateVariables.currentFile,
        task: userInput
      };

      // Build the system prompt
      const systemPrompt = this.buildSystemPrompt(templateVars);
      
      this.log(`System prompt built (${systemPrompt.length} chars)`);

      // Execute agent loop using CLI
      const response = await this.executeAgentLoop(userInput, systemPrompt, context);
      
      const durationMs = Date.now() - startTime;
      this.log(`Agent completed in ${durationMs}ms with ${response.iterations} iterations`);
      this.log(`Agent response finalText length: ${response.finalText?.length || 0} chars`);
      this.log(`Agent response toolCalls count: ${response.toolCalls?.length || 0}`);
      
      return {
        ...response,
        durationMs,
        success: true
      };
    } catch (error: any) {
      const durationMs = Date.now() - startTime;
      this.log(`Agent error: ${error.message}`);
      
      return {
        finalText: `Error: ${error.message}`,
        toolCalls: [],
        iterations: 0,
        durationMs,
        success: false,
        error: error.message
      };
    }
  }

  /**
   * Build system prompt from template
   */
  private buildSystemPrompt(variables: Record<string, string>): string {
    let prompt = this.config.systemPromptTemplate;
    
    for (const [key, value] of Object.entries(variables)) {
      prompt = prompt.replace(new RegExp(`\\$\\{${key}\\}`, 'g'), value);
    }
    
    // Append strong formatting instructions with examples
    prompt += '\n\n--- OUTPUT FORMAT (STRICT) ---';
    prompt += '\nYou MUST use this exact format:';
    prompt += '\n\nExample 1 (with tool):';
    prompt += '\nreasoning: I need to read the file to understand its contents.';
    prompt += '\ntool_call: {"tool":"read_file","args":{"path":"vscode-app/src/extension.ts"}}';
    prompt += '\nEOS';
    prompt += '\n\nExample 2 (no tool needed):';
    prompt += '\nreasoning: The answer is 42.';
    prompt += '\nEOS';
    prompt += '\n\nIMPORTANT: Always start with "reasoning:" and use "tool_call:" if you need to use a tool.';
    
    return prompt;
  }

  /**
   * Execute the agent loop (simplified version for initial testing)
   * In production, this would integrate with conf-agent-core's BaseConfigurableAgent
   */
  private async executeAgentLoop(
    userInput: string,
    systemPrompt: string,
    context?: ProcessContext
  ): Promise<Omit<AgentResponse, 'durationMs' | 'success'>> {
    const toolCalls: ToolCall[] = [];
    let iterations = 0;
    let finalText = '';

    try {
      this.log('Going directly to LLM');

      // Build messages for LLM
      const messages = [
        { role: 'system', content: systemPrompt },
        { role: 'user', content: userInput }
      ];

      // Call LLM with tools
      this.log(`Calling model: ${this.config.model.id}`);
      const tools = this.getAvailableTools();
      this.log(`Passing ${tools.length} tools to LLM`);
      
      const llmResponse = await this.callLLM(messages, tools);
      
      // DIAGNOSTIC: Log the raw response
      this.log(`=== DIAGNOSTIC: LLM RESPONSE ===`);
      this.log(`LLM content length: ${llmResponse.content.length} chars`);
      this.log(`LLM content preview: ${llmResponse.content.substring(0, 300)}`);
      this.log(`LLM tool calls count: ${llmResponse.toolCalls.length}`);
      if (llmResponse.toolCalls.length > 0) {
        llmResponse.toolCalls.forEach((tc, i) => {
          this.log(`  Tool ${i}: ${tc.name} - args: ${JSON.stringify(tc.arguments)}`);
        });
      }
      this.log(`=== END DIAGNOSTIC ===`);
      
      // Convert LLM tool calls to our format and execute them
      if (llmResponse.toolCalls.length > 0) {
        this.log(`Found ${llmResponse.toolCalls.length} tool calls from LLM`);
        
        for (const tc of llmResponse.toolCalls) {
          try {
            const toolCall: ToolCall = {
              toolName: tc.name,
              args: tc.arguments
            };
            
            this.log(`Executing tool: ${toolCall.toolName} with args: ${JSON.stringify(toolCall.args)}`);
            const result = await this.executeTool(toolCall);
            toolCall.result = result;
            toolCalls.push(toolCall);
            this.log(`Tool ${toolCall.toolName} completed successfully (${result.length} chars)`);
          } catch (error: any) {
            const toolCall: ToolCall = {
              toolName: tc.name,
              args: tc.arguments,
              error: error.message
            };
            toolCalls.push(toolCall);
            this.log(`Tool ${tc.name} failed: ${error.message}`);
          }
        }
        
        finalText = llmResponse.content + '\n\nTool results:\n' + 
          toolCalls.map(tc => `- ${tc.toolName}: ${tc.result || tc.error}`).join('\n');
      } else {
        finalText = llmResponse.content || 'No response from LLM';
      }
      
      // DIAGNOSTIC: Log final text before return
      this.log(`=== DIAGNOSTIC: FINAL TEXT ===`);
      this.log(`finalText length: ${finalText?.length || 0} chars`);
      this.log(`finalText preview: ${finalText?.substring(0, 200)}`);
      this.log(`iterations: ${iterations}`);
      this.log(`=== END DIAGNOSTIC ===`);
      
      iterations = 1;
    } catch (error: any) {
      finalText = `Error during agent loop: ${error.message}`;
      iterations = 0;
      this.log(`Agent loop error: ${error.message}`);
      this.log(`Stack trace: ${error.stack}`);
    }

    return {
      finalText,
      toolCalls,
      iterations
    };
  }

  /**
   * Call LLM through CLI - returns both content and tool calls
   */
  private async callLLM(
    messages: Array<{role: string, content: string}>,
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
  ): Promise<LLMResponse> {
    // Use the CLI to call the LLM
    this.log(`Calling LLM with ${messages.length} messages and ${tools?.length || 0} tools`);
    
    const response = await this.cli.callLLM(
      this.config.model.id,
      messages,
      {
        temperature: this.config.model.temperature,
        top_p: this.config.model.topP,
        max_tokens: this.config.model.maxOutputTokens
      },
      tools
    );
    
    this.log(`LLM response - content: ${response.content.length} chars, toolCalls: ${response.toolCalls.length}`);
    return response;
  }

  /**
   * Get the list of tools the agent can use.
   * These are sent to the LLM so it knows what tools are available.
   */
  private getAvailableTools(): Array<{
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
  }> {
    return [
      {
        type: "function",
        function: {
          name: "read_file",
          description: "Read the contents of a file",
          parameters: {
            type: "object",
            properties: {
              path: { 
                type: "string", 
                description: "File path relative to workspace root" 
              }
            },
            required: ["path"]
          }
        }
      },
      {
        type: "function",
        function: {
          name: "list_directory",
          description: "List contents of a directory",
          parameters: {
            type: "object",
            properties: {
              path: { 
                type: "string", 
                description: "Directory path relative to workspace root" 
              }
            },
            required: ["path"]
          }
        }
      },
      {
        type: "function",
        function: {
          name: "list_files",
          description: "List files and directories",
          parameters: {
            type: "object",
            properties: {
              path: { 
                type: "string", 
                description: "Directory path relative to workspace root" 
              },
              recursive: { 
                type: "boolean", 
                description: "Whether to list recursively" 
              }
            }
          }
        }
      },
      {
        type: "function",
        function: {
          name: "regex_search",
          description: "Search for a regex pattern across files",
          parameters: {
            type: "object",
            properties: {
              pattern: { 
                type: "string", 
                description: "Regex pattern to search for" 
              },
              path: { 
                type: "string", 
                description: "Directory or file to search in" 
              }
            },
            required: ["pattern", "path"]
          }
        }
      },
      {
        type: "function",
        function: {
          name: "write_file",
          description: "Write content to a file (creates or overwrites)",
          parameters: {
            type: "object",
            properties: {
              path: { 
                type: "string", 
                description: "File path relative to workspace root" 
              },
              content: { 
                type: "string", 
                description: "Content to write to the file" 
              }
            },
            required: ["path", "content"]
          }
        }
      },
      {
        type: "function",
        function: {
          name: "edit_file",
          description: "Edit a file by replacing exact string match",
          parameters: {
            type: "object",
            properties: {
              path: { 
                type: "string", 
                description: "File path relative to workspace root" 
              },
              old_string: { 
                type: "string", 
                description: "The exact text to find in the file" 
              },
              new_string: { 
                type: "string", 
                description: "The replacement text" 
              }
            },
            required: ["path", "old_string", "new_string"]
          }
        }
      }
    ];
  }

  /**
   * Execute a tool call
   */
  private async executeTool(toolCall: ToolCall): Promise<string> {
    this.log(`Executing tool: ${toolCall.toolName}`);
    
    const workspaceRoot = vscode.workspace.workspaceFolders?.[0]?.uri.fsPath || '';
    
    switch (toolCall.toolName) {
      case 'read_file': {
        const filePath = toolCall.args.path;
        if (!filePath) {
          throw new Error('Missing path argument for read_file');
        }
        const fullPath = filePath.startsWith(workspaceRoot) ? filePath : path.join(workspaceRoot, filePath);
        return await this.readFile(fullPath);
      }
      
      case 'list_directory':
      case 'list_files': {
        const dirPath = toolCall.args.path || '.';
        const recursive = toolCall.args.recursive || false;
        const fullPath = dirPath.startsWith(workspaceRoot) ? dirPath : path.join(workspaceRoot, dirPath);
        const files = await this.listFiles(fullPath, recursive);
        return files.join('\n');
      }
      
      case 'write_file': {
        const filePath = toolCall.args.path;
        const content = toolCall.args.content;
        if (!filePath || content === undefined) {
          throw new Error('Missing path or content argument for write_file');
        }
        const fullPath = filePath.startsWith(workspaceRoot) ? filePath : path.join(workspaceRoot, filePath);
        await this.writeFile(fullPath, content);
        return `Successfully wrote ${content.length} chars to ${filePath}`;
      }
      
      case 'edit_file': {
        const filePath = toolCall.args.path;
        const oldString = toolCall.args.old_string;
        const newString = toolCall.args.new_string;
        if (!filePath || !oldString || newString === undefined) {
          throw new Error('Missing required arguments for edit_file');
        }
        const fullPath = filePath.startsWith(workspaceRoot) ? filePath : path.join(workspaceRoot, filePath);
        const content = await this.readFile(fullPath);
        if (!content.includes(oldString)) {
          throw new Error(`Could not find old_string in ${filePath}`);
        }
        const newContent = content.replace(oldString, newString);
        await this.writeFile(fullPath, newContent);
        return `Successfully edited ${filePath}`;
      }
      
      case 'regex_search': {
        const pattern = toolCall.args.pattern;
        const searchPath = toolCall.args.path || '.';
        if (!pattern) {
          throw new Error('Missing pattern argument for regex_search');
        }
        const fullPath = searchPath.startsWith(workspaceRoot) ? searchPath : path.join(workspaceRoot, searchPath);
        const results = await this.searchFiles(pattern, fullPath);
        return results.join('\n');
      }
      
      default:
        throw new Error(`Unknown tool: ${toolCall.toolName}`);
    }
  }

  /**
   * Read a file
   */
  private async readFile(filePath: string): Promise<string> {
    return new Promise((resolve, reject) => {
      fs.readFile(filePath, 'utf8', (err, data) => {
        if (err) reject(err);
        else resolve(data);
      });
    });
  }

  /**
   * Write a file
   */
  private async writeFile(filePath: string, content: string): Promise<void> {
    const dir = path.dirname(filePath);
    await fs.promises.mkdir(dir, { recursive: true });
    await fs.promises.writeFile(filePath, content, 'utf8');
  }

  /**
   * List files in a directory
   */
  private async listFiles(dirPath: string, recursive: boolean = false): Promise<string[]> {
    const files: string[] = [];
    const entries = await fs.promises.readdir(dirPath, { withFileTypes: true });
    
    for (const entry of entries) {
      const fullPath = path.join(dirPath, entry.name);
      if (entry.isDirectory()) {
        if (recursive) {
          const subFiles = await this.listFiles(fullPath, recursive);
          files.push(...subFiles);
        } else {
          files.push(entry.name + '/');
        }
      } else {
        files.push(entry.name);
      }
    }
    
    return files;
  }

  /**
   * Search files with regex
   */
  private async searchFiles(pattern: string, searchPath: string): Promise<string[]> {
    const results: string[] = [];
    const regex = new RegExp(pattern);
    
    const searchDir = async (dir: string) => {
      const entries = await fs.promises.readdir(dir, { withFileTypes: true });
      for (const entry of entries) {
        if (entry.isDirectory()) {
          if (!entry.name.startsWith('.') && entry.name !== 'node_modules') {
            await searchDir(path.join(dir, entry.name));
          }
        } else if (entry.isFile()) {
          const filePath = path.join(dir, entry.name);
          try {
            const content = await this.readFile(filePath);
            const lines = content.split('\n');
            for (let i = 0; i < lines.length; i++) {
              if (regex.test(lines[i])) {
                results.push(`${filePath}:${i + 1}: ${lines[i]}`);
              }
            }
          } catch (error) {
            // Skip binary files
          }
        }
      }
    };
    
    await searchDir(searchPath);
    return results;
  }

  /**
   * Log a message to the output channel
   */
  private log(message: string): void {
    const timestamp = new Date().toLocaleTimeString();
    const formatted = `[${timestamp}] [AgentBridge] ${message}`;
    if (this.outputChannel) {
      this.outputChannel.appendLine(formatted);
    }
    console.log(formatted);
  }

  /**
   * Dispose the agent bridge
   */
  dispose(): void {
    this.log('AgentBridge disposed');
  }
}
