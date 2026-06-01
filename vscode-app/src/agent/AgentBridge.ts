/**
 * AgentBridge - Bridge between VSCode extension and conf-agent-core
 * 
 * This class wraps the agent core functionality and provides a clean API
 * for the AgentTabManager to interact with configured agents.
 * 
 * UPDATED: Implements modern agentic loop with reflection - LLM sees tool results
 * and decides if more tools are needed or if task is complete.
 * 
 * FIXES APPLIED:
 * - Tool result truncation to prevent context window overflow
 * - Repeated tool call detection to prevent infinite loops
 * - Better logging for debugging
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
 * Message in conversation history
 */
export interface Message {
  role: 'system' | 'user' | 'assistant' | 'tool';
  content: string;
  tool_calls?: LLMToolCall[];
  tool_call_id?: string;
}

/**
 * Track tool call history for loop detection
 */
interface ToolCallHistory {
  toolName: string;
  argsSignature: string;
  iteration: number;
}

/**
 * AgentBridge - Manages agent lifecycle and communication
 */
export class AgentBridge {
  private config: AgentConfig;
  private cli: CLI;
  private isInitialized: boolean = false;
  private outputChannel?: vscode.OutputChannel;

  // Truncation settings
  private static readonly MAX_TOOL_RESULT_LENGTH = 2000; // characters
  private static readonly MAX_LIST_FILES_RESULTS = 100; // max files to return

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
    this.log(`Max iterations (safety net): ${this.config.iterationSettings.maxIterations}`);
    
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
   * Strip <think> tags from LLM content
   */
  private stripThinkTags(content: string): string {
    // Remove <think>...</think> blocks
    return content.replace(new RegExp('<think>[\\s\\S]*?</think>', 'gi'), '').trim();
  }

  /**
   * Create a signature for tool call deduplication
   */
  private createToolCallSignature(toolName: string, args: Record<string, any>): string {
    // Sort args keys for consistent signature
    const sortedArgs = Object.keys(args).sort().map(k => `${k}=${JSON.stringify(args[k])}`).join('|');
    return `${toolName}:${sortedArgs}`;
  }

  /**
   * Execute the agent loop with reflection (MODERN APPROACH)
   * 
   * This implements the task-complete pattern:
   * - Loop continues while LLM keeps making tool calls
   * - Tool results are fed back to LLM for analysis
   * - LLM decides when task is complete (no more tool calls)
   * - maxIterations is a safety net, not the primary control
   * 
   * FIXES:
   * - Truncates large tool results to prevent context window overflow
   * - Detects repeated tool calls to prevent infinite loops
   * - Better error handling and logging
   */
  private async executeAgentLoop(
    userInput: string,
    systemPrompt: string,
    context?: ProcessContext
  ): Promise<Omit<AgentResponse, 'durationMs' | 'success'>> {
    const allToolCalls: ToolCall[] = [];
    const toolCallHistory: ToolCallHistory[] = [];
    let iterations = 0;
    let finalText = '';
    let lastSanitizedContent = '';

    // Build initial message history
    const messages: Message[] = [
      { role: 'system', content: systemPrompt },
      { role: 'user', content: userInput }
    ];

    // Get available tools once
    const tools = this.getAvailableTools();
    this.log(`Available tools: ${tools.length}`);

    try {
      // MODERN AGENTIC LOOP: Continue while LLM keeps calling tools
      while (iterations < this.config.iterationSettings.maxIterations) {
        this.log(`\n=== ITERATION ${iterations + 1} ===`);
        
        // Call LLM with current message history
        this.log(`Calling model: ${this.config.model.id} with ${messages.length} messages`);
        const llmResponse = await this.callLLM(messages, tools);
        
        // Strip <think> tags from LLM content
        const sanitizedContent = this.stripThinkTags(llmResponse.content);
        lastSanitizedContent = sanitizedContent;
        
        this.log(`LLM response - content: ${sanitizedContent.length} chars, toolCalls: ${llmResponse.toolCalls.length}`);
        
        // Add assistant's response to message history
        messages.push({
          role: 'assistant',
          content: sanitizedContent,
          tool_calls: llmResponse.toolCalls
        });

        // CHECK: Did LLM make any tool calls?
        if (llmResponse.toolCalls.length === 0) {
          // No tool calls = task is complete!
          this.log('LLM has no more tool calls - task complete');
          finalText = sanitizedContent;
          break;
        }

        // Execute all tool calls from this iteration
        this.log(`Executing ${llmResponse.toolCalls.length} tool calls...`);
        
        for (const tc of llmResponse.toolCalls) {
          try {
            const toolCall: ToolCall = {
              toolName: tc.name,
              args: tc.arguments
            };
            
            this.log(`  → Executing: ${toolCall.toolName}(${JSON.stringify(toolCall.args)})`);
            
            // Check for repeated tool calls (loop detection)
            const signature = this.createToolCallSignature(toolCall.toolName, toolCall.args);
            const previousCalls = toolCallHistory.filter(h => h.argsSignature === signature);
            
            if (previousCalls.length >= 2) {
              // Same tool called 3+ times with same args = stuck in loop
              this.log(`  ⚠️ DETECTED: Repeated tool call (3rd time). Forcing completion.`);
              finalText = sanitizedContent + '\n\n[Note: I appear to be stuck in a loop. Based on the information gathered, I cannot make further progress with the current approach.]';
              break;
            }
            
            toolCallHistory.push({
              toolName: toolCall.toolName,
              argsSignature: signature,
              iteration: iterations
            });
            
            const result = await this.executeTool(toolCall);
            
            // TRUNCATE large results to prevent context window overflow
            let resultText = result;
            if (resultText.length > AgentBridge.MAX_TOOL_RESULT_LENGTH) {
              resultText = resultText.substring(0, AgentBridge.MAX_TOOL_RESULT_LENGTH) + 
                `\n\n[... truncated ${resultText.length - AgentBridge.MAX_TOOL_RESULT_LENGTH} more chars ...]`;
              this.log(`  ← Result truncated from ${result.length} to ${AgentBridge.MAX_TOOL_RESULT_LENGTH} chars`);
            } else {
              this.log(`  ← Result: ${resultText.length} chars`);
            }
            
            toolCall.result = result;
            allToolCalls.push(toolCall);
            
            // Feed tool result back to LLM (truncated version)
            messages.push({
              role: 'tool',
              content: resultText,
              tool_call_id: tc.name
            });
          } catch (error: any) {
            const toolCall: ToolCall = {
              toolName: tc.name,
              args: tc.arguments,
              error: error.message
            };
            allToolCalls.push(toolCall);
            this.log(`  ← Error: ${error.message}`);
            
            // Feed error back to LLM
            messages.push({
              role: 'tool',
              content: `Error: ${error.message}`,
              tool_call_id: tc.name
            });
          }
        }

        iterations++;
        this.log(`Iteration ${iterations} complete. Tool results fed back to LLM.`);
      }

      // Check if we hit the iteration limit
      if (iterations >= this.config.iterationSettings.maxIterations) {
        this.log(`⚠️ Hit max iterations limit (${this.config.iterationSettings.maxIterations})`);
        finalText = lastSanitizedContent + '\n\n[Note: Reached maximum iteration limit]';
      }

      this.log(`\n=== AGENTIC LOOP COMPLETE ===`);
      this.log(`Total iterations: ${iterations}`);
      this.log(`Total tool calls: ${allToolCalls.length}`);
      this.log(`Final text length: ${finalText.length} chars`);
      
    } catch (error: any) {
      finalText = `Error during agent loop: ${error.message}`;
      iterations = 0;
      this.log(`Agent loop error: ${error.message}`);
      this.log(`Stack trace: ${error.stack}`);
    }

    return {
      finalText,
      toolCalls: allToolCalls,
      iterations
    };
  }

  /**
   * Call LLM through CLI - returns both content and tool calls
   */
  private async callLLM(
    messages: Message[],
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
      messages.map(m => ({ role: m.role, content: m.content })),
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
          description: "List files and directories (limited to 100 results)",
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
          description: "Search for a regex pattern across files (returns up to 50 matches)",
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
      },
      {
        type: "function",
        function: {
          name: "i2vision_discover",
          description: "Run full VSLFC discovery on the project using the i2vision CLI. Analyzes architecture patterns, flows, business rules, and components across all modules.",
          parameters: {
            type: "object",
            properties: {
              path: {
                type: "string",
                description: "Project root path to discover (default: current workspace)"
              },
              intent: {
                type: "string",
                description: "Discovery intent: full_discovery, quick_overview, architecture_audit, or flow_mapping"
              }
            },
            required: ["path"]
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
        // Limit results to prevent context explosion
        const limitedFiles = files.slice(0, AgentBridge.MAX_LIST_FILES_RESULTS);
        if (files.length > AgentBridge.MAX_LIST_FILES_RESULTS) {
          return limitedFiles.join('\n') + `\n\n[... and ${files.length - AgentBridge.MAX_LIST_FILES_RESULTS} more files]`;
        }
        return limitedFiles.join('\n');
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
        this.log(`regex_search: pattern="${pattern}", path="${fullPath}"`);
        const results = await this.searchFiles(pattern, fullPath);
        this.log(`regex_search result: ${results.length} matches`);
        return results.join('\n');
      }
      
      case 'i2vision_discover': {
        const projectPath = toolCall.args.path || workspaceRoot;
        const intent = toolCall.args.intent || 'full_discovery';
        this.log(`Running i2vision discovery on ${projectPath} with intent: ${intent}`);
        const result = await this.cli.runDiscovery();
        return JSON.stringify(result);
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
        files.push(`[DIR]  ${fullPath}`);
        if (recursive) {
          const subFiles = await this.listFiles(fullPath, recursive);
          files.push(...subFiles);
        }
      } else {
        files.push(`[FILE] ${fullPath}`);
      }
    }
    
    return files;
  }

  /**
   * Search for a pattern in files using ripgrep or PowerShell
   */
  private async searchFiles(pattern: string, dirPath: string): Promise<string[]> {
    this.log(`searchFiles: pattern="${pattern}", dirPath="${dirPath}"`);
    
    try {
      // Try ripgrep first (faster, better regex support)
      const rgPath = await this.findRipgrep();
      if (rgPath) {
        this.log(`Using ripgrep: ${rgPath}`);
        const command = `"${rgPath}" --max-count 50 --line-number --column --with-filename "${pattern}" "${dirPath}"`;
        this.log(`Executing: ${command}`);
        const { stdout } = await this.runCommand(command);
        const results = stdout.split('\n').filter(line => line.trim()).slice(0, 50);
        this.log(`ripgrep found ${results.length} matches`);
        return results;
      }
      
      // Fallback to PowerShell
      this.log('Falling back to PowerShell search');
      const command = `powershell -Command "Get-ChildItem -Path '${dirPath}' -Recurse -File -ErrorAction SilentlyContinue | Select-String -Pattern '${pattern}' -SimpleMatch | Select-Object -First 50 -ExpandProperty Line"`;
      this.log(`Executing: ${command}`);
      const { stdout } = await this.runCommand(command);
      const results = stdout.split('\n').filter(line => line.trim()).slice(0, 50);
      this.log(`PowerShell found ${results.length} matches`);
      return results;
    } catch (error: any) {
      this.log(`searchFiles error: ${error.message}`);
      return [];
    }
  }

  /**
   * Find ripgrep executable
   */
  private async findRipgrep(): Promise<string | null> {
    try {
      // Check common locations
      const candidates = [
        'rg',
        'ripgrep',
        path.join(process.env.LOCALAPPDATA || '', 'Programs', 'ripgrep', 'rg.exe'),
        path.join('C:', 'Program Files', 'ripgrep', 'rg.exe'),
      ];
      
      for (const candidate of candidates) {
        try {
          const { stdout } = await this.runCommand(`"${candidate}" --version`);
          if (stdout.includes('ripgrep')) {
            return candidate;
          }
        } catch {
          continue;
        }
      }
    } catch {
      // Ignore errors
    }
    
    return null;
  }

  /**
   * Run a shell command
   */
  private async runCommand(command: string): Promise<{ stdout: string, stderr: string }> {
    return new Promise((resolve, reject) => {
      const { exec } = require('child_process');
      exec(command, { maxBuffer: 10 * 1024 * 1024 }, (error: any, stdout: string, stderr: string) => {
        if (error && !stdout) {
          reject(error);
        } else {
          resolve({ stdout, stderr });
        }
      });
    });
  }

  /**
   * Dispose resources
   */
  dispose(): void {
    this.log(`Disposing AgentBridge for agent: ${this.config.key}`);
    this.isInitialized = false;
  }

  /**
   * Log a message to the output channel
   */
  private log(message: string): void {
    const timestamp = new Date().toLocaleTimeString();
    const formatted = `[${timestamp}] [AgentBridge:${this.config.key}] ${message}`;
    if (this.outputChannel) {
      this.outputChannel.appendLine(formatted);
    }
    console.log(formatted);
  }
}
