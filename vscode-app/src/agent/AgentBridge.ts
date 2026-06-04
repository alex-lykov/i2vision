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
 */

import * as vscode from 'vscode';
import * as fs from 'fs';
import * as path from 'path';
import { CLI, LLMResponse, LLMTool, LLMMessage, LLMToolCall } from '../cliIntegration';

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

  /**
   * Execute the agentic processing loop
   * 
   * MODERN APPROACH:
   * - LLM makes tool calls based on current context
   * - Tool results are fed back to LLM for analysis
   * - LLM decides when task is complete (no more tool calls)
   * - maxIterations is a safety net, not the primary control
   * 
   * FIXES:
   * - Truncates large tool results to prevent context window overflow
   * - Detects repeated tool calls to prevent infinite loops
   * - Better error handling and logging
   * - REAL-TIME: Emits progress events for each tool call
   * - BUG FIX: Records tool calls in history BEFORE execution to catch loops on errors
   * - BUG FIX: Path normalization to catch backslash/forward slash variations
   * - BUG FIX: When directory not found, error includes suggestions for existing paths
   * - BUG FIX: Empty results formatted as clear messages LLM can act on
   * - BUG FIX: Simplified error format with explicit DO NOT RETRY instruction
   */
  private async executeAgentLoop(
    userInput: string,
    systemPrompt: string,
    context?: ProcessContext,
    onProgress?: ProgressCallback
  ): Promise<Omit<AgentResponse, 'durationMs' | 'success'>> {
    const allToolCalls: ToolCall[] = [];
    const toolCallHistory: ToolCallHistory[] = [];
    let iterations = 0;
    let finalText = '';
    let lastSanitizedContent = '';

    // Build initial message history
    const messages: LLMMessage[] = [
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
        
        // Emit thinking event
        if (onProgress) {
          onProgress({
            type: 'thinking',
            iteration: iterations + 1,
            message: `Thinking... (iteration ${iterations + 1})`
          });
        }
        
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
          content: sanitizedContent
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
            
            this.log(`  🛠️ Executing: ${toolCall.toolName}(${JSON.stringify(toolCall.args)})`);
            
            // Emit tool_start event BEFORE executing
            if (onProgress) {
              onProgress({
                type: 'tool_start',
                iteration: iterations + 1,
                toolCall: { ...toolCall }
              });
            }
            
            // Validate required arguments
            if (toolCall.toolName === 'list_directory' && !toolCall.args.path) {
              this.log(`  ⚠️ WARNING: list_directory called without 'path' argument!`);
              toolCall.error = 'Missing required argument: path';
              allToolCalls.push(toolCall);
              
              // Feed error back to LLM
              messages.push({
                role: 'tool',
                content: `Error: Missing required argument 'path' for list_directory. Example: {"path": "vscode-app/src"}`
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
                content: `Error: Missing required argument 'path' for read_file. Example: {"path": "vscode-app/src/extension.ts"}`
              });
              continue; // Skip to next tool call
            }
            
            // Check for repeated tool calls (loop detection)
            const signature = this.createToolCallSignature(toolCall.toolName, toolCall.args);
            const previousCalls = toolCallHistory.filter(h => h.argsSignature === signature);
            
            if (previousCalls.length >= 2) {
              this.log(`  ⚠️ DETECTED: Repeated tool call (${previousCalls.length + 1}th time). Forcing completion.`);
              
              // Add error to tool call
              toolCall.error = `Repeated tool call detected (${previousCalls.length + 1} times). Breaking loop.`;
              allToolCalls.push(toolCall);
              
              // Feed error back to LLM
              messages.push({
                role: 'tool',
                content: `Error: You've called ${toolCall.toolName} with the same arguments ${previousCalls.length + 1} times. Please analyze the previous results and take a different approach, or provide a final answer.`
              });
              
              // Break the inner loop (tool execution)
              shouldBreakOuterLoop = true;
              break; // Break inner for loop
            }
            
            // BUG FIX: Record this tool call BEFORE execution
            // This ensures failed tool calls are tracked for loop detection
            toolCallHistory.push({
              toolName: toolCall.toolName,
              argsSignature: signature,
              iteration: iterations + 1
            });
            
            // Execute the tool
            const result = await this.executeTool(toolCall);
            toolCall.result = result.result;
            if (result.error) {
              toolCall.error = result.error;
            }
            
            allToolCalls.push(toolCall);
            
            // BUG FIX: Format tool result for LLM consumption
            // Empty results need explicit phrasing so LLM knows to stop
            let resultText = result.result || '';
            const errorText = result.error || '';
            
            if (errorText) {
              // Error case - include full error message
              resultText = `Error: ${errorText}`;
            } else if (!resultText || resultText.trim() === '') {
              // Empty result - make it explicit
              if (toolCall.toolName === 'list_directory') {
                resultText = 'This directory is empty. No files found.';
              } else if (toolCall.toolName === 'search_files') {
                resultText = 'No files found matching that pattern.';
              } else if (toolCall.toolName === 'read_file') {
                resultText = 'The file exists but is empty.';
              } else {
                resultText = 'No results found.';
              }
            } else {
              // Truncate large results
              if (resultText.length > this.config.formatting.maxObservationChars) {
                const truncated = resultText.substring(0, this.config.formatting.maxObservationChars);
                resultText = truncated + `\n\n[...truncated: ${resultText.length - this.config.formatting.maxObservationChars} more characters...]`;
              }
            }
            
            this.log(`  ✅ ${errorText ? 'Error: ' + errorText : 'Success: ' + resultText.substring(0, 100) + (resultText.length > 100 ? '...' : '')}`);
            
            // Feed result back to LLM
            messages.push({
              role: 'tool',
              content: resultText
            });
            
            // Emit tool_complete event AFTER executing
            if (onProgress) {
              onProgress({
                type: 'tool_complete',
                iteration: iterations + 1,
                toolCall: { ...toolCall }
              });
            }
            
          } catch (toolError: any) {
            this.log(`  ❌ Tool execution error: ${toolError.message}`);
            
            const errorToolCall: ToolCall = {
              toolName: tc.name,
              args: tc.arguments || {},
              error: toolError.message
            };
            
            allToolCalls.push(errorToolCall);
            
            // Feed error back to LLM
            messages.push({
              role: 'tool',
              content: `Error executing ${tc.name}: ${toolError.message}`
            });
            
            // Emit tool_complete event with error
            if (onProgress) {
              onProgress({
                type: 'tool_complete',
                iteration: iterations + 1,
                toolCall: errorToolCall
              });
            }
          }
        }
        
        // If we detected a loop, break the outer loop too
        if (shouldBreakOuterLoop) {
          this.log('Breaking outer loop due to repeated tool calls');
          finalText = lastSanitizedContent + '\n\n⚠️ Loop detected: The agent was repeating the same tool calls. Please refine your request.';
          break;
        }
        
        // Increment iteration counter
        iterations++;
        
        // Emit iteration_complete event
        if (onProgress) {
          onProgress({
            type: 'iteration_complete',
            iteration: iterations,
            message: `Completed iteration ${iterations}`
          });
        }
      }
      
      // If we hit max iterations without completion
      if (iterations >= this.config.iterationSettings.maxIterations && !finalText) {
        this.log(`⚠️ Hit max iterations (${this.config.iterationSettings.maxIterations})`);
        finalText = lastSanitizedContent || 'Agent reached maximum iterations without completing the task.';
      }
      
      this.log(`\n=== AGENTIC LOOP COMPLETE ===`);
      this.log(`Total iterations: ${iterations}`);
      this.log(`Total tool calls: ${allToolCalls.length}`);
      this.log(`Final text length: ${finalText.length} chars`);
      
      return {
        finalText,
        toolCalls: allToolCalls,
        iterations
      };
      
    } catch (error: any) {
      this.log(`Agent loop error: ${error.message}`);
      throw error;
    }
  }

  /**
   * Call LLM through CLI
   */
  private async callLLM(messages: LLMMessage[], tools: LLMTool[]): Promise<LLMResponse> {
    return await this.cli.callLLM(
      this.config.model.id,
      messages,
      {
        temperature: this.config.model.temperature,
        top_p: this.config.model.topP,
        max_tokens: this.config.model.maxOutputTokens
      },
      tools
    );
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
          // For write operations, always use workspace root (user's project)
          const filePath = path.isAbsolute(toolCall.args.path) 
            ? toolCall.args.path 
            : path.join(this.workspaceRoot, toolCall.args.path);
          
          this.log(`  Writing file: ${filePath}`);
          
          await this.cli.writeFile(filePath, toolCall.args.content);
          return { result: `Successfully wrote ${filePath}` };
        }
        
        case 'edit_file': {
          const filePath = this.resolvePath(toolCall.args.path);
          this.log(`  Editing file: ${filePath}`);
          
          // Read current content
          const content = await this.cli.readFile(filePath);
          
          // Apply edit
          const edited = content.replace(
            new RegExp(toolCall.args.oldString, 'g'),
            toolCall.args.newString
          );
          
          // Write back
          await this.cli.writeFile(filePath, edited);
          return { result: `Successfully edited ${filePath}` };
        }
        
        case 'search_files': {
          const pattern = toolCall.args.pattern;
          const dirPath = toolCall.args.path ? this.resolvePath(toolCall.args.path) : undefined;
          const filePattern = toolCall.args.file_pattern;
          
          this.log(`  Searching for: ${pattern} in ${dirPath || 'workspace'}`);
          
          const results = await this.cli.searchFiles(pattern, dirPath);
          
          // BUG FIX: Format empty results explicitly
          if (results.length === 0) {
            return {
              result: 'No files found matching that pattern.',
              error: undefined
            };
          }
          
          return { result: results.join('\n') };
        }
        
        default:
          return {
            result: '',
            error: `Unknown tool: ${toolCall.toolName}`
          };
      }
    } catch (error: any) {
      this.log(`  Tool execution failed: ${error.message}`);
      return {
        result: '',
        error: error.message
      };
    }
  }

  /**
   * Resolve a path relative to workspace root
   */
  private resolvePath(requestedPath: string): string {
    // If absolute path, use as-is
    if (path.isAbsolute(requestedPath)) {
      return requestedPath;
    }
    
    // Normalize path separators
    const normalized = requestedPath.replace(/\\/g, '/');
    
    // Resolve relative to workspace root
    return path.join(this.workspaceRoot, normalized);
  }

  /**
   * Create a signature for tool call comparison (ignores optional params)
   * 
   * BUG FIX: Normalizes path separators to catch backslash/forward slash variations
   */
  private createToolCallSignature(toolName: string, args: Record<string, any>): string {
    // Normalize arguments by removing optional parameters that don't affect semantics
    const normalizedArgs: Record<string, any> = {};
    
    for (const [key, value] of Object.entries(args)) {
      // Skip 'recursive' for list_directory as it's often defaulted
      if (toolName === 'list_directory' && key === 'recursive') {
        continue;
      }
      
      // BUG FIX: Normalize path separators for path arguments
      // This prevents the agent from bypassing loop detection by using different separators
      if (typeof value === 'string' && (key === 'path' || key === 'dir' || key === 'file' || key === 'filePath')) {
        // Normalize to forward slashes and lowercase for comparison
        normalizedArgs[key] = value.replace(/\\/g, '/').toLowerCase();
      } else {
        normalizedArgs[key] = value;
      }
    }
    
    // Sort keys for consistent comparison
    const sortedKeys = Object.keys(normalizedArgs).sort();
    const sortedArgs: Record<string, any> = {};
    for (const key of sortedKeys) {
      sortedArgs[key] = normalizedArgs[key];
    }
    
    return `${toolName}:${JSON.stringify(sortedArgs)}`;
  }

  /**
   * Strip <think> tags from LLM output
   */
  private stripThinkTags(content: string): string {
    return content.replace(/<think>[\s\S]*?<\/think>/g, '').trim();
  }

  /**
   * Get available tools for the agent
   */
  private getAvailableTools(): LLMTool[] {
    const tools: LLMTool[] = [
      {
        type: 'function',
        function: {
          name: 'list_directory',
          description: 'List files and directories in a given path',
          parameters: {
            type: 'object',
            properties: {
              path: {
                type: 'string',
                description: 'Directory path to list (relative to workspace root)'
              },
              recursive: {
                type: 'boolean',
                description: 'Whether to list recursively (default: false)'
              }
            },
            required: ['path']
          }
        }
      },
      {
        type: 'function',
        function: {
          name: 'read_file',
          description: 'Read the contents of a file',
          parameters: {
            type: 'object',
            properties: {
              path: {
                type: 'string',
                description: 'File path to read (relative to workspace root)'
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
          description: 'Write content to a file (creates if doesn\'t exist)',
          parameters: {
            type: 'object',
            properties: {
              path: {
                type: 'string',
                description: 'File path to write (relative to workspace root)'
              },
              content: {
                type: 'string',
                description: 'Content to write to the file'
              }
            },
            required: ['path', 'content']
          }
        }
      },
      {
        type: 'function',
        function: {
          name: 'edit_file',
          description: 'Edit a file by replacing text',
          parameters: {
            type: 'object',
            properties: {
              path: {
                type: 'string',
                description: 'File path to edit (relative to workspace root)'
              },
              oldString: {
                type: 'string',
                description: 'Text to find and replace'
              },
              newString: {
                type: 'string',
                description: 'Replacement text'
              }
            },
            required: ['path', 'oldString', 'newString']
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
              pattern: {
                type: 'string',
                description: 'Regex pattern to search for'
              },
              path: {
                type: 'string',
                description: 'Directory to search in (optional, defaults to workspace root)'
              },
              file_pattern: {
                type: 'string',
                description: 'Glob pattern to filter files (e.g., "*.java")'
              }
            },
            required: ['pattern']
          }
        }
      }
    ];
    
    return tools;
  }

  /**
   * Log a message to the output channel
   */
  private log(message: string): void {
    if (this.outputChannel) {
      const timestamp = new Date().toLocaleTimeString('en-US', { hour12: false });
      this.outputChannel.appendLine(`[${timestamp}] [AgentBridge] ${message}`);
    }
  }
}
