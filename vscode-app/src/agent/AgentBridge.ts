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
 * - Repeated tool call detection to prevent infinite loops (FIXED: now breaks outer loop)
 * - Better logging for debugging
 * - Correct workspace root handling
 * - Extension root path support for accessing extension source files
 */

import * as vscode from 'vscode';
import * as fs from 'fs';
import * as path from 'path';
import { CLI, LLMResponse, LLMTool, LLMMessage } from '../cliIntegration';

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
  private workspaceRoot: string;
  private extensionRoot: string;

  // Truncation settings
  private static readonly MAX_TOOL_RESULT_LENGTH = 2000; // characters
  private static readonly MAX_LIST_FILES_RESULTS = 100; // max files to return

  constructor(config: AgentConfig, outputChannel?: vscode.OutputChannel, extensionRoot?: string) {
    this.config = config;
    this.outputChannel = outputChannel;
    
    // CRITICAL: Get workspace root ONCE and store it
    // This is the USER'S project root (e.g., d:\proj\alyk\android-arch-sketch)
    this.workspaceRoot = vscode.workspace.workspaceFolders?.[0]?.uri.fsPath || '';
    this.log(`Workspace root (user project): ${this.workspaceRoot}`);
    
    // Extension root: where the i2-vision PROJECT root is
    // If extension is installed from vscode-app directory, extensionRoot points to vscode-app
    // We need to go up to the actual project root (parent of vscode-app)
    let detectedExtensionRoot = extensionRoot || vscode.extensions.getExtension('i2vision.i2-vision-vscode')?.extensionPath || '';
    
    if (!detectedExtensionRoot) {
      // Fallback: use workspace root
      detectedExtensionRoot = this.workspaceRoot;
    } else {
      // Check if we're in development mode (extension running from vscode-app directory)
      // If extension path ends with 'vscode-app', go up to project root
      const pathSegments = detectedExtensionRoot.split(/[\\/]/);
      if (pathSegments[pathSegments.length - 1] === 'vscode-app') {
        // Go up one level to get project root
        detectedExtensionRoot = path.dirname(detectedExtensionRoot);
        this.log(`Development mode detected: extension root adjusted to project root`);
      }
    }
    
    this.extensionRoot = detectedExtensionRoot;
    this.log(`Extension root (project root): ${this.extensionRoot}`);
    
    // Initialize CLI integration with the workspace root (for CLI operations on user's project)
    this.cli = new CLI(this.workspaceRoot, outputChannel);
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
    prompt += '\n\n--- CRITICAL RULES ---';
    prompt += '\n1. NEVER call the same tool with the same arguments more than once.';
    prompt += '\n2. If a tool returns an error, try a different approach or explain the issue to the user.';
    prompt += '\n3. If a tool returns the expected result, summarize the findings in text - do NOT call more tools.';
    prompt += '\n4. When you have enough information to answer the user\'s question, stop calling tools and provide a text response.';
    prompt += '\n5. If you get the same error twice, explain the problem to the user instead of retrying.';
    
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
        
        // Convert Message[] to LLMMessage[] for CLI
        const cliMessages: LLMMessage[] = messages.map(m => ({ role: m.role, content: m.content }));
        
        const llmResponse = await this.callLLM(cliMessages, tools);
        
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
        
        // Track if we need to break the outer loop
        let shouldBreakOuterLoop = false;
        
        for (const tc of llmResponse.toolCalls) {
          try {
            const toolCall: ToolCall = {
              toolName: tc.name,
              args: tc.arguments || {}
            };
            
            this.log(`  → Executing: ${toolCall.toolName}(${JSON.stringify(toolCall.args)})`);
            
            // Validate required arguments
            if (toolCall.toolName === 'list_directory' && !toolCall.args.path) {
              this.log(`  ⚠️ WARNING: list_directory called without 'path' argument!`);
              toolCall.error = 'Missing required argument: path';
              allToolCalls.push(toolCall);
              
              // Feed error back to LLM
              messages.push({
                role: 'tool',
                content: `Error: Missing required argument 'path' for list_directory. Example: {"path": "vscode-app/src"}`,
                tool_call_id: tc.name
              });
              continue; // Skip to next tool call
            }
            
            if (toolCall.toolName === 'read_file' && !toolCall.args.path) {
              this.log(`  ⚠️ WARNING: read_file called without 'path' argument!`);
              toolCall.error = 'Missing required argument: path';
              allToolCalls.push(toolCall);
              
              // Feed error back to LLM
              messages.push({
                role: 'tool',
                content: `Error: Missing required argument 'path' for read_file. Example: {"path": "vscode-app/src/extension.ts"}`,
                tool_call_id: tc.name
              });
              continue; // Skip to next tool call
            }
            
            // Check for repeated tool calls (loop detection)
            const signature = this.createToolCallSignature(toolCall.toolName, toolCall.args);
            const previousCalls = toolCallHistory.filter(h => h.argsSignature === signature);
            
            if (previousCalls.length >= 2) {
              // Same tool called 3+ times with same args = stuck in loop
              this.log(`  ⚠️ DETECTED: Repeated tool call (3rd time). Forcing completion.`);
              finalText = sanitizedContent + '\n\n[Note: I appear to be stuck in a loop. Based on the information gathered, I cannot make further progress with the current approach.]';
              shouldBreakOuterLoop = true;
              break; // Break inner for loop
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
        
        // Check if we need to break the outer loop (from repeated tool detection)
        if (shouldBreakOuterLoop) {
          break;
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
      finalText = `Error during agent execution: ${error.message}`;
      this.log(`Agent loop error: ${error.message}`);
    }

    return {
      finalText,
      toolCalls: allToolCalls,
      iterations
    };
  }

  /**
   * Call LLM via CLI
   */
  private async callLLM(messages: LLMMessage[], tools: any[]): Promise<LLMResponse> {
    this.log(`Calling LLM with ${messages.length} messages and ${tools.length} tools`);
    return await this.cli.callLLM(
      this.config.model.id,
      messages,
      {
        temperature: this.config.model.temperature,
        max_tokens: this.config.model.maxOutputTokens
      },
      tools
    );
  }

  /**
   * Resolve a path - handles both workspace and extension paths
   * 
   * Smart path resolution:
   * - If path starts with common extension dirs (vscode-app, storage-core, etc.), use extensionRoot
   * - Otherwise, use workspaceRoot (user's project)
   * - Absolute paths are used as-is
   */
  private resolvePath(requestedPath: string): string {
    // If already absolute, use as-is
    if (path.isAbsolute(requestedPath)) {
      return requestedPath;
    }
    
    // Check if path refers to extension source directories
    const extensionDirs = ['vscode-app', 'storage-core', 'conf-agent-core', 'i2vision-cli', 'backlog'];
    const firstSegment = requestedPath.split(/[\\/]/)[0];
    
    if (extensionDirs.includes(firstSegment)) {
      // This is a reference to extension source - use extension root
      const fullPath = path.join(this.extensionRoot, requestedPath);
      this.log(`  Path resolution: "${requestedPath}" → extension root → ${fullPath}`);
      return fullPath;
    }
    
    // Default: use workspace root (user's project)
    const fullPath = path.join(this.workspaceRoot, requestedPath);
    this.log(`  Path resolution: "${requestedPath}" → workspace root → ${fullPath}`);
    return fullPath;
  }

  /**
   * Execute a tool call
   */
  private async executeTool(toolCall: ToolCall): Promise<string> {
    this.log(`Executing tool: ${toolCall.toolName}`);
    
    const args = toolCall.args;
    
    switch (toolCall.toolName) {
      case 'read_file': {
        // Smart path resolution: detects extension source paths vs workspace paths
        const filePath = this.resolvePath(args.path);
        this.log(`  Reading file: ${filePath}`);
        return await this.cli.readFile(filePath);
      }
      
      case 'list_directory': {
        // Smart path resolution: detects extension source paths vs workspace paths
        const dirPath = this.resolvePath(args.path);
        this.log(`  Listing directory: ${dirPath}`);
        
        const files = await this.cli.listFiles(dirPath, args.recursive || false);
        
        // Limit results to prevent context overflow
        if (files.length > AgentBridge.MAX_LIST_FILES_RESULTS) {
          const truncated = files.slice(0, AgentBridge.MAX_LIST_FILES_RESULTS);
          return truncated.join('\n') + `\n\n[... truncated ${files.length - AgentBridge.MAX_LIST_FILES_RESULTS} more files ...]`;
        }
        return files.join('\n');
      }
      
      case 'write_file': {
        // For write operations, always use workspace root (user's project)
        const filePath = path.isAbsolute(args.path) ? args.path : path.join(this.workspaceRoot, args.path);
        this.log(`  Writing file: ${filePath}`);
        await this.cli.writeFile(filePath, args.content);
        return `Successfully wrote ${args.path} (${args.content.length} chars)`;
      }
      
      case 'regex_search': {
        const pattern = args.pattern;
        // Smart path resolution: detects extension source paths vs workspace paths
        const searchDir = this.resolvePath(args.path);
        this.log(`  Searching for "${pattern}" in ${searchDir}`);
        const results = await this.cli.searchFiles(pattern, searchDir);
        return results.join('\n');
      }
      
      case 'i2vision_discover': {
        this.log(`  Running VSLFC discovery...`);
        const result = await this.cli.runDiscovery();
        return JSON.stringify(result, null, 2);
      }
      
      default:
        throw new Error(`Unknown tool: ${toolCall.toolName}`);
    }
  }

  /**
   * Get available tools for the agent
   */
  private getAvailableTools(): LLMTool[] {
    return [
      {
        type: 'function',
        function: {
          name: 'read_file',
          description: 'Read the contents of a file. Path can be relative to workspace or extension source (e.g., "vscode-app/src/extension.ts" or "src/main.kt"). Use list_directory to find files first.',
          parameters: {
            type: 'object',
            properties: {
              path: {
                type: 'string',
                description: 'Path to the file (relative to workspace root, extension root, or absolute)'
              }
            },
            required: ['path']
          }
        }
      },
      {
        type: 'function',
        function: {
          name: 'list_directory',
          description: 'List contents of a directory. Path can be relative to workspace or extension source (e.g., "vscode-app/src" or "src/main")',
          parameters: {
            type: 'object',
            properties: {
              path: {
                type: 'string',
                description: 'Directory path (relative to workspace root, extension root, or absolute)'
              },
              recursive: {
                type: 'boolean',
                description: 'Whether to list files recursively',
                default: false
              }
            },
            required: ['path']
          }
        }
      },
      {
        type: 'function',
        function: {
          name: 'write_file',
          description: 'Write content to a file in the workspace (user\'s project)',
          parameters: {
            type: 'object',
            properties: {
              path: {
                type: 'string',
                description: 'Path to the file (relative to workspace root or absolute)'
              },
              content: {
                type: 'string',
                description: 'Content to write'
              }
            },
            required: ['path', 'content']
          }
        }
      },
      {
        type: 'function',
        function: {
          name: 'regex_search',
          description: 'Search for a regex pattern in files. Path can be relative to workspace or extension source',
          parameters: {
            type: 'object',
            properties: {
              pattern: {
                type: 'string',
                description: 'Regex pattern to search for'
              },
              path: {
                type: 'string',
                description: 'Directory to search in (relative to workspace root, extension root, or absolute)'
              }
            },
            required: ['pattern', 'path']
          }
        }
      },
      {
        type: 'function',
        function: {
          name: 'i2vision_discover',
          description: 'Run VSLFC discovery on the current workspace (user\'s project)',
          parameters: {
            type: 'object',
            properties: {},
            required: []
          }
        }
      }
    ];
  }

  /**
   * Log a message to the output channel
   */
  private log(message: string): void {
    const timestamp = new Date().toLocaleTimeString('en-US', { hour12: true });
    const formatted = `[${timestamp}] [AgentBridge:${this.config.key}] ${message}`;
    
    if (this.outputChannel) {
      this.outputChannel.appendLine(formatted);
    }
    console.log(formatted);
  }

  /**
   * Dispose resources
   */
  dispose(): void {
    this.isInitialized = false;
    this.log('AgentBridge disposed');
  }
}
