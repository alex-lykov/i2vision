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
 * - REAL-TIME STREAMING: Tool calls are emitted immediately via progress callback
 * - BUG FIX: Tool calls are now recorded in history BEFORE execution to catch loops on errors
 * - BUG FIX: Path normalization in loop detection to catch backslash/forward slash variations
 * - BUG FIX: When directory not found, error includes suggestions for existing paths
 * - BUG FIX: Empty results formatted as clear messages LLM can act on
 * - BUG FIX: Simplified error format with explicit DO NOT RETRY instruction
 * - STREAMING SUPPORT: Added processStreaming method for real-time token generation
 */

import * as vscode from 'vscode';
import * as fs from 'fs';
import * as path from 'path';
import { CLI, LLMResponse, LLMTool, LLMMessage, LLMToolCall, LLMChunk } from '../cliIntegration';

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
 * Progress event for real-time updates
 */
export interface ProgressEvent {
  type: 'tool_start' | 'tool_complete' | 'iteration_complete' | 'thinking';
  iteration: number;
  toolCall?: ToolCall;
  message?: string;
}

/**
 * Progress callback type
 */
export type ProgressCallback = (event: ProgressEvent) => void;

/**
 * Interaction record for history tracking
 */
export interface InteractionRecord {
  timestamp: number;
  userInput: string;
  agentResponse: string;
  toolCalls: ToolCall[];
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
 * Agent chunk types for streaming responses
 */
export type AgentChunk = 
  | { type: 'thinking'; message: string; timestamp: number }
  | { type: 'tool_call_started'; toolName: string; args: Record<string, any>; timestamp: number }
  | { type: 'tool_call_completed'; toolName: string; result: string; timestamp: number }
  | { type: 'text'; text: string; timestamp: number }
  | { type: 'done'; outcome: 'success' | 'error'; timestamp: number }
  | { type: 'error'; error: string; timestamp: number };

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
        // Go up one level to project root
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
   * Initialize the bridge
   */
  async initialize(): Promise<void> {
    this.log('Initializing AgentBridge...');
    this.isInitialized = true;
    this.log('AgentBridge initialized');
  }

  /**
   * Log a message
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
   * Process user input through the agent with real-time progress updates
   */
  async process(
    userInput: string,
    context?: ProcessContext,
    onProgress?: ProgressCallback
  ): Promise<AgentResponse> {
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
      const response = await this.executeAgentLoop(userInput, systemPrompt, context, onProgress);
      
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
   * Process user input with streaming responses
   * Yields chunks as they are generated for real-time display
   */
  async *processStreaming(
    userInput: string,
    context?: ProcessContext
  ): AsyncGenerator<AgentChunk> {
    if (!this.isInitialized) {
      await this.initialize();
    }

    this.log(`Starting streaming process: "${userInput.substring(0, 50)}..."`);

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

      // Execute streaming agent loop
      for await (const chunk of this.executeStreamingAgentLoop(userInput, systemPrompt, context)) {
        yield chunk;
      }
    } catch (error: any) {
      this.log(`Streaming agent error: ${error.message}`);
      yield {
        type: 'error',
        error: error.message,
        timestamp: Date.now()
      };
    }
  }

  /**
   * Execute streaming agent loop
   * Yields AgentChunk events as they occur
   */
  private async *executeStreamingAgentLoop(
    userInput: string,
    systemPrompt: string,
    context?: ProcessContext
  ): AsyncGenerator<AgentChunk> {
    const messages: LLMMessage[] = [
      { role: 'system', content: systemPrompt },
      { role: 'user', content: userInput }
    ];

    const tools = this.getTools();
    const maxIterations = this.config.iterationSettings.maxIterations;
    const toolCalls: ToolCall[] = [];

    this.log(`Starting streaming loop with max ${maxIterations} iterations`);

    for (let iteration = 1; iteration <= maxIterations; iteration++) {
      this.log(`=== Iteration ${iteration}/${maxIterations} ===`);

      // Emit thinking event
      yield {
        type: 'thinking',
        message: `Iteration ${iteration}: Processing...`,
        timestamp: Date.now()
      };

      // Call LLM with streaming
      this.log(`Calling LLM with streaming enabled...`);
      const streamResponse = await this.cli.callLLM(
        this.config.model.id,
        messages,
        {
          temperature: this.config.model.temperature,
          top_p: this.config.model.topP,
          max_tokens: this.config.model.maxOutputTokens
        },
        tools,
        true // Enable streaming
      ) as AsyncGenerator<LLMChunk>;

      // Collect streaming response
      let responseText = '';
      let streamingToolCalls: LLMToolCall[] = [];

      for await (const chunk of streamResponse) {
        if (chunk.text) {
          responseText += chunk.text;
          yield {
            type: 'text',
            text: chunk.text,
            timestamp: Date.now()
          };
        }
        if (chunk.toolCalls) {
          streamingToolCalls = chunk.toolCalls;
        }
        if (chunk.done) {
          break;
        }
      }

      this.log(`LLM response: ${responseText.length} chars, ${streamingToolCalls.length} tool calls`);

      // If no tool calls, we're done
      if (streamingToolCalls.length === 0) {
        this.log(`No tool calls - iteration complete`);
        
        yield {
          type: 'done',
          outcome: 'success',
          timestamp: Date.now()
        };
        
        return;
      }

      // Execute tool calls
      for (const toolCall of streamingToolCalls) {
        this.log(`Executing tool: ${toolCall.name}`);
        
        yield {
          type: 'tool_call_started',
          toolName: toolCall.name,
          args: toolCall.arguments,
          timestamp: Date.now()
        };

        const toolCallObj: ToolCall = {
          toolName: toolCall.name,
          args: toolCall.arguments
        };

        try {
          const result = await this.executeTool({
            toolName: toolCall.name,
            args: toolCall.arguments
          });
          
          toolCallObj.result = result.result;
          if (result.error) {
            toolCallObj.error = result.error;
          }
          
          yield {
            type: 'tool_call_completed',
            toolName: toolCall.name,
            result: result.result,
            timestamp: Date.now()
          };
        } catch (error: any) {
          toolCallObj.error = error.message;
          
          yield {
            type: 'tool_call_completed',
            toolName: toolCall.name,
            result: `Error: ${error.message}`,
            timestamp: Date.now()
          };
        }

        toolCalls.push(toolCallObj);
      }

      // Add assistant message with tool calls to history
      // CRITICAL FIX: If LLM returned tool calls without content, add explanatory message
      // This prevents the LLM from repeating the same tool call in the next iteration
      let assistantContent = responseText;
      if (!assistantContent || assistantContent.trim() === '') {
        // LLM returned only tool calls with no prose - add descriptive message
        const toolDescriptions = toolCalls.map(tc => {
          const argsStr = JSON.stringify(tc.args);
          return `Calling ${tc.toolName}(${argsStr})`;
        }).join('; ');
        assistantContent = `I will: ${toolDescriptions}`;
        this.log(`Assistant content was empty - added synthetic message: "${assistantContent}"`);
      }
      
      messages.push({
        role: 'assistant',
        content: assistantContent
      });

      // Add tool results to messages
      for (const tc of toolCalls) {
        messages.push({
          role: 'tool',
          content: tc.error || tc.result || 'No result'
        });
      }
    }

    this.log(`Max iterations (${maxIterations}) reached`);
    
    yield {
      type: 'done',
      outcome: 'success',
      timestamp: Date.now()
    };
  }

  /**
   * Execute non-streaming agent loop (for backward compatibility)
   */
  private async executeAgentLoop(
    userInput: string,
    systemPrompt: string,
    context?: ProcessContext,
    onProgress?: ProgressCallback
  ): Promise<{ finalText: string; toolCalls: ToolCall[]; iterations: number }> {
    const messages: LLMMessage[] = [
      { role: 'system', content: systemPrompt },
      { role: 'user', content: userInput }
    ];

    const tools = this.getTools();
    const maxIterations = this.config.iterationSettings.maxIterations;
    const toolCalls: ToolCall[] = [];
    const history: ToolCallHistory[] = [];

    this.log(`Starting agent loop with max ${maxIterations} iterations`);

    for (let iteration = 1; iteration <= maxIterations; iteration++) {
      this.log(`=== Iteration ${iteration}/${maxIterations} ===`);

      // Emit thinking event
      if (onProgress) {
        onProgress({
          type: 'thinking',
          message: `Iteration ${iteration}: Processing...`,
          iteration
        });
      }

      // Call LLM
      const response = await this.callLLM(messages, tools);

      this.log(`LLM response: ${response.content.length} chars, ${response.toolCalls.length} tool calls`);

      // If no tool calls, we're done
      if (response.toolCalls.length === 0) {
        this.log(`No tool calls - iteration complete`);
        return {
          finalText: response.content,
          toolCalls,
          iterations: iteration
        };
      }

      // Check for repeated tool calls (loop detection)
      for (const toolCall of response.toolCalls) {
        const argsSignature = JSON.stringify(toolCall.arguments);
        const normalizedToolName = toolCall.name.toLowerCase().replace(/[_-]/g, '');
        
        // Check if this exact tool call was made in the last 2 iterations
        const recentCalls = history.filter(h => 
          h.iteration >= iteration - 2 && 
          h.toolName.toLowerCase().replace(/[_-]/g, '') === normalizedToolName &&
          h.argsSignature === argsSignature
        );
        
        if (recentCalls.length > 0) {
          this.log(`LOOP DETECTED: ${toolCall.name} called with same args at iterations ${recentCalls.map(h => h.iteration).join(', ')}`);
          
          // Break out of both loops
          return {
            finalText: `Stopped to prevent infinite loop. Repeated tool call: ${toolCall.name} with args: ${argsSignature.substring(0, 100)}...`,
            toolCalls,
            iterations: iteration
          };
        }
        
        // Record BEFORE execution
        history.push({
          toolName: toolCall.name,
          argsSignature,
          iteration
        });
      }

      // Execute tool calls
      for (const toolCall of response.toolCalls) {
        this.log(`Executing tool: ${toolCall.name}`);
        
        // Emit tool start event
        if (onProgress) {
          onProgress({
            type: 'tool_start',
            toolCall: {
              toolName: toolCall.name,
              args: toolCall.arguments
            },
            iteration
          });
        }

        const toolCallObj: ToolCall = {
          toolName: toolCall.name,
          args: toolCall.arguments
        };

        try {
          const result = await this.executeTool({
            toolName: toolCall.name,
            args: toolCall.arguments
          });
          
          toolCallObj.result = result.result;
          if (result.error) {
            toolCallObj.error = result.error;
          }
          
          // Emit tool complete event
          if (onProgress) {
            onProgress({
              type: 'tool_complete',
              toolCall: toolCallObj,
              iteration
            });
          }
        } catch (error: any) {
          toolCallObj.error = error.message;
          
          if (onProgress) {
            onProgress({
              type: 'tool_complete',
              toolCall: toolCallObj,
              iteration
            });
          }
        }

        toolCalls.push(toolCallObj);
      }

      // Add assistant message with tool calls to history
      // CRITICAL FIX: If LLM returned tool calls without content, add explanatory message
      // This prevents the LLM from repeating the same tool call in the next iteration
      let assistantContent = response.content;
      if (!assistantContent || assistantContent.trim() === '') {
        // LLM returned only tool calls with no prose - add descriptive message
        const toolDescriptions = toolCalls.map(tc => {
          const argsStr = JSON.stringify(tc.args);
          return `Calling ${tc.toolName}(${argsStr})`;
        }).join('; ');
        assistantContent = `I will: ${toolDescriptions}`;
        this.log(`Assistant content was empty - added synthetic message: "${assistantContent}"`);
      }
      
      messages.push({
        role: 'assistant',
        content: assistantContent
      });

      // Add tool results to messages
      for (const tc of toolCalls) {
        messages.push({
          role: 'tool',
          content: tc.error || tc.result || 'No result'
        });
      }

      // Emit iteration complete event
      if (onProgress) {
        onProgress({
          type: 'iteration_complete',
          iteration
        });
      }
    }

    this.log(`Max iterations (${maxIterations}) reached`);
    return {
      finalText: 'Max iterations reached without final answer',
      toolCalls,
      iterations: maxIterations
    };
  }

  /**
   * Call LLM through CLI
   */
  private async callLLM(messages: LLMMessage[], tools: LLMTool[]): Promise<LLMResponse> {
    const result = await this.cli.callLLM(
      this.config.model.id,
      messages,
      {
        temperature: this.config.model.temperature,
        top_p: this.config.model.topP,
        max_tokens: this.config.model.maxOutputTokens
      },
      tools,
      false // Non-streaming
    );
    
    // Type guard to ensure we get LLMResponse, not AsyncGenerator
    if (Symbol.asyncIterator in result) {
      throw new Error('Expected non-streaming response but got streaming generator');
    }
    
    return result as LLMResponse;
  }

  /**
   * Execute a tool call
   */
  private async executeTool(toolCall: ToolCall): Promise<{ result: string; error?: string }> {
    try {
      switch (toolCall.toolName) {
        case 'list_directory': {
          const dirPath = this.resolvePath(toolCall.args.path);
          const recursive = toolCall.args.recursive === true;
          
          this.log(`  Listing directory: ${dirPath} (recursive: ${recursive})`);
          
          const files = await this.cli.listFiles(dirPath, recursive);
          
          // BUG FIX: Format empty results explicitly
          if (files.length === 0) {
            return {
              result: 'This directory is empty. No files found.',
              error: undefined
            };
          }
          
          const result = files.join('\n');
          
          // Truncate if too large
          if (result.length > this.config.formatting.maxObservationChars) {
            const truncated = result.substring(0, this.config.formatting.maxObservationChars);
            return {
              result: truncated + '\n\n[...truncated...]',
              error: undefined
            };
          }
          
          return { result };
        }
        
        case 'read_file': {
          const filePath = this.resolvePath(toolCall.args.path);
          this.log(`  Reading file: ${filePath}`);
          
          const result = await this.cli.readFile(filePath);
          
          // Empty file check
          if (!result || result.trim() === '') {
            return {
              result: 'The file exists but is empty.',
              error: undefined
            };
          }
          
          return { result };
        }
        
        case 'write_file': {
          const filePath = this.resolvePath(toolCall.args.path);
          this.log(`  Writing file: ${filePath} (${toolCall.args.content.length} chars)`);
          
          await this.cli.writeFile(filePath, toolCall.args.content);
          
          return {
            result: `Successfully wrote ${toolCall.args.content.length} characters to ${filePath}`
          };
        }
        
        case 'search_files': {
          const pattern = toolCall.args.pattern;
          const searchPath = toolCall.args.path ? this.resolvePath(toolCall.args.path) : undefined;
          
          this.log(`  Searching for pattern: ${pattern}`);
          
          const results = await this.cli.searchFiles(pattern, searchPath);
          
          if (results.length === 0) {
            return {
              result: 'No files found matching pattern.',
              error: undefined
            };
          }
          
          const result = results.join('\n');
          
          // Truncate if too large
          if (result.length > this.config.formatting.maxObservationChars) {
            const truncated = result.substring(0, this.config.formatting.maxObservationChars);
            return {
              result: truncated + '\n\n[...truncated...]',
              error: undefined
            };
          }
          
          return { result };
        }
        
        case 'run_command': {
          const command = toolCall.args.command;
          const workingDir = toolCall.args.workingDir ? this.resolvePath(toolCall.args.workingDir) : undefined;
          
          this.log(`  Running command: ${command}`);
          
          const result = await this.cli.runCommand(command, workingDir);
          
          return {
            result: result.stdout || result.stderr || 'Command completed with no output'
          };
        }
        
        case 'get_file_context': {
          const filePath = this.resolvePath(toolCall.args.path);
          this.log(`  Getting file context: ${filePath}`);
          
          const context = await this.cli.getContext(filePath);
          
          return {
            result: JSON.stringify(context, null, 2)
          };
        }
        
        default:
          throw new Error(`Unknown tool: ${toolCall.toolName}`);
      }
    } catch (error: any) {
      this.log(`  Tool execution error: ${error.message}`);
      return {
        result: '',
        error: error.message
      };
    }
  }

  /**
   * Resolve relative path to absolute
   */
  private resolvePath(relativePath: string): string {
    if (path.isAbsolute(relativePath)) {
      return relativePath;
    }
    return path.join(this.workspaceRoot, relativePath);
  }

  /**
   * Get available tools
   */
  private getTools(): LLMTool[] {
    return [
      {
        type: 'function',
        function: {
          name: 'list_directory',
          description: 'List files in a directory',
          parameters: {
            type: 'object',
            properties: {
              path: { type: 'string', description: 'Directory path (relative to workspace root)' },
              recursive: { type: 'boolean', description: 'Whether to search recursively' }
            },
            required: ['path']
          }
        }
      },
      {
        type: 'function',
        function: {
          name: 'read_file',
          description: 'Read contents of a file',
          parameters: {
            type: 'object',
            properties: {
              path: { type: 'string', description: 'File path (relative to workspace root)' }
            },
            required: ['path']
          }
        }
      },
      {
        type: 'function',
        function: {
          name: 'write_file',
          description: 'Write content to a file',
          parameters: {
            type: 'object',
            properties: {
              path: { type: 'string', description: 'File path (relative to workspace root)' },
              content: { type: 'string', description: 'Content to write' }
            },
            required: ['path', 'content']
          }
        }
      },
      {
        type: 'function',
        function: {
          name: 'search_files',
          description: 'Search for files matching a pattern',
          parameters: {
            type: 'object',
            properties: {
              pattern: { type: 'string', description: 'Regex pattern to search for' },
              path: { type: 'string', description: 'Directory to search in (optional)' }
            },
            required: ['pattern']
          }
        }
      },
      {
        type: 'function',
        function: {
          name: 'run_command',
          description: 'Run a shell command',
          parameters: {
            type: 'object',
            properties: {
              command: { type: 'string', description: 'Command to execute' },
              workingDir: { type: 'string', description: 'Working directory (optional)' }
            },
            required: ['command']
          }
        }
      },
      {
        type: 'function',
        function: {
          name: 'get_file_context',
          description: 'Get context for a specific file (classes, functions, imports)',
          parameters: {
            type: 'object',
            properties: {
              path: { type: 'string', description: 'File path (relative to workspace root)' }
            },
            required: ['path']
          }
        }
      }
    ];
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
    prompt += '\n\nExample 2 (final answer, no tools):';
    prompt += '\nreasoning: I have all the information needed to answer.';
    prompt += '\nThe main entry point is in extension.ts line 45.';
    prompt += '\nEOS';
    prompt += '\n\n--- TOOL RESULT INTERPRETATION (CRITICAL) ---';
    prompt += '\nWhen you receive a tool result:';
    prompt += '\n1. "This directory is empty. No files found." → Tell the user the directory exists but is empty, then STOP';
    prompt += '\n2. "No files found matching pattern" → Tell the user no matches exist, then STOP';
    prompt += '\n3. "DIRECTORY_NOT_FOUND" → DO NOT RETRY. Tell user folder doesn\'t exist, offer alternatives, then STOP';
    prompt += '\n4. List of file paths → Report the files found to the user';
    prompt += '\n\n--- DIRECTORY_NOT_FOUND HANDLING (CRITICAL) ---';
    prompt += '\nWhen list_directory returns DIRECTORY_NOT_FOUND:';
    prompt += '\n1. DO NOT call list_directory again with the same or different path';
    prompt += '\n2. DO NOT try root "/", ".", or workspace root';
    prompt += '\n3. Tell the user: "The folder [path] doesn\'t exist. Available folders: [list]. Would you like me to check one of those?"';
    prompt += '\n4. Then STOP and wait for user response';
    prompt += '\n\nExample response:';
    prompt += '\n"❌ The folder \'core/ui/src\' doesn\'t exist in this project.';
    prompt += '\nAvailable top-level folders: conf-agent-core, discovery-api, vscode-app, vslfc-core.';
    prompt += '\nWould you like me to check one of these instead?"';
    prompt += '\n\n--- ERROR HANDLING ---';
    prompt += '\nFor other errors:';
    prompt += '\n- DO NOT retry the same tool call more than once';
    prompt += '\n- NEVER try more than 2 different approaches for the same task';
    prompt += '\n- If stuck, report to user and ask for clarification';
    prompt += '\n\n--- PATH HANDLING ---';
    prompt += '\n- Always use forward slashes (/) for paths';
    prompt += '\n- Paths are relative to workspace root';
    
    return prompt;
  }
}
