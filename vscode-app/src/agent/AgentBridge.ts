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
 * - CRITICAL FIX: Added tool_call_id to link tool results with tool calls (LLM now learns from results)
 * - CRITICAL FIX: Blocked long-running commands (npm run dev, gradlew run, etc.) in run_command tool
 * - OPTIMIZATION: Tightened decision nudge to complete in 3-5 iterations instead of 10+
 * - BUG FIX: Removed tool_calls from assistant messages (Ollama incompatible - causes 400 error)
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
  durationMs?: number;
  toolCallId?: string; // Added for OpenAI API compatibility
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
  workspaceRoot?: string; // Target project root (the project user is working on)
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

  // Blocked command patterns to prevent long-running processes
  private static readonly BLOCKED_COMMAND_PATTERNS = [
    'npm run dev',
    'npm start',
    'yarn dev',
    'yarn start',
    'pnpm dev',
    'pnpm start',
    'gradlew run',
    './gradlew run',
    'gradle run',
    'mvn spring-boot:run',
    'mvn jetty:run',
    'node server',
    'nodemon',
    'webpack-dev-server',
    'vite',
    'next dev',
    'gatsby develop'
  ];

  constructor(config: AgentConfig, outputChannel?: vscode.OutputChannel, extensionRoot?: string, workspaceRoot?: string) {
    this.config = config;
    this.outputChannel = outputChannel;
    
    // ROBUST: workspaceRoot MUST be explicitly provided - caller is responsible for passing correct value
    if (!workspaceRoot) {
      throw new Error('CRITICAL: workspaceRoot (target project) must be explicitly provided.');
    }

    // Validate: workspaceRoot and extensionRoot must be different directories
    // This ensures agent operates on user's project, not extension project
    if (extensionRoot && extensionRoot === workspaceRoot) {
      throw new Error(`CRITICAL: workspaceRoot and extensionRoot cannot be the same path. ` +
                    `workspaceRoot=${workspaceRoot}, extensionRoot=${extensionRoot}`);
    }
    
    this.workspaceRoot = workspaceRoot;
    this.extensionRoot = extensionRoot || '';
    
    this.log(`Workspace root (target project): ${this.workspaceRoot}`);
    if (this.extensionRoot) {
      this.log(`Extension root: ${this.extensionRoot}`);
    }

    // Initialize CLI with workspace root for user's project operations
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
   * Set workspace root dynamically (for when target project changes)
   */
  setWorkspaceRoot(newWorkspaceRoot: string): void {
    if (newWorkspaceRoot && newWorkspaceRoot !== this.workspaceRoot) {
      this.workspaceRoot = newWorkspaceRoot;
      this.log(`Workspace root updated to: ${this.workspaceRoot}`);
      // Re-initialize CLI with new workspace root
      this.cli = new CLI(this.workspaceRoot, this.outputChannel);
    }
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
        success: response.success ?? true
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

      // If no tool calls, check if this is just a plan (not actual execution)
      if (streamingToolCalls.length === 0) {
        // PLAN DETECTION: Check if response is just a plan without execution
        const trimmedResponse = responseText.trim();

        // Expanded plan patterns
        const isPlanOnly =
          trimmedResponse.startsWith('I will:') ||
          trimmedResponse.startsWith('I\'ll') ||
          trimmedResponse.toLowerCase().startsWith('calling ') ||
          /^[Ii] will (call|use|read|search|run|execute)/.test(trimmedResponse) ||
          /^[Ii]\'ll (call|use|read|search|run|execute)/.test(trimmedResponse) ||
          /^(First|I\'ll first|Let me first|I will first)/i.test(trimmedResponse) ||
          // Short responses that are likely just plans
          (trimmedResponse.length < 100 && /^(Sure|Okay|Let me|I will|I\'ll)/i.test(trimmedResponse));

        if (isPlanOnly && trimmedResponse.length < 300) {
          // This is just a plan, not actual work - don't accept it as final answer
          this.log(`PLAN DETECTED: "${trimmedResponse.substring(0, 50)}..." - forcing tool execution`);

          yield {
            type: 'text',
            text: 'I understand that plan, but I need you to EXECUTE the tools to complete this task. Please call the tools now.',
            timestamp: Date.now()
          };

          // Add nudge to continue iterating
          messages.push({
            role: 'user',
            content: 'That is just a plan. You MUST call tools to complete the task. Do NOT respond with another plan - actually call the tools now.'
          });

          continue; // Continue the loop to force tool execution
        }

        // Otherwise, we have actual content - we're done
        this.log(`No tool calls - iteration complete`);
        
        yield {
          type: 'done',
          outcome: 'success',
          timestamp: Date.now()
        };
        
        return;
      }

      // Execute tool calls - collect results for THIS iteration only
      const currentIterationToolCalls: ToolCall[] = [];

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
          args: toolCall.arguments,
          toolCallId: toolCall.id // Store the tool call ID for linking results
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

        currentIterationToolCalls.push(toolCallObj);
        toolCalls.push(toolCallObj);
      }

      // Add assistant message with tool calls to history
      // CRITICAL FIX: If LLM returned tool calls without content, add explanatory message
      // This prevents the LLM from repeating the same tool call in the next iteration
      let assistantContent = responseText;
      if (!assistantContent || assistantContent.trim() === '') {
        // LLM returned only tool calls with no prose - add descriptive message
        const toolDescriptions = currentIterationToolCalls.map(tc => {
          const argsStr = JSON.stringify(tc.args);
          return `Calling ${tc.toolName}(${argsStr})`;
        }).join('; ');
        assistantContent = `I will: ${toolDescriptions}`;
        this.log(`Assistant content was empty - added synthetic message: "${assistantContent}"`);
      }
      
      // Push assistant message with content only
      // Note: Ollama does NOT accept tool_calls in assistant messages (causes 400 error)
      // The tool_call_id in tool results is sufficient for linking
      messages.push({
        role: 'assistant',
        content: assistantContent || ''
      });

      // CRITICAL FIX: Add ONLY current iteration's tool results WITH tool_call_id
      // This links each result to its corresponding tool call
      for (let i = 0; i < currentIterationToolCalls.length; i++) {
        const tc = currentIterationToolCalls[i];
        const matchingToolCall = streamingToolCalls[i];
        messages.push({
          role: 'tool',
          content: tc.error || tc.result || 'No result',
          tool_call_id: matchingToolCall?.id // ← Critical for LLM to learn from results
        });
      }

      // TIGHTENED DECISION NUDGE: Push toward completion
      // Encourages LLM to answer now instead of continuing to explore
      messages.push({
        role: 'user',
        content: `Tool results received. You have ${maxIterations - iteration} of ${maxIterations} iterations remaining. If you have enough information to answer the user's question, provide your answer now. Only call another tool if you're missing critical information.`
      });
    }

    this.log(`Max iterations (${maxIterations}) reached without final answer - giving LLM one more chance`);
    // Give LLM one final chance to provide a final answer
    const finalResponse = await this.callLLM(messages, tools);
    if (finalResponse.toolCalls.length === 0) {
      this.log(`Final answer from LLM: ${finalResponse.content.length} chars`);
      return {
        finalText: finalResponse.content,
        toolCalls,
        iterations: maxIterations + 1,
        success: true
      };
    }
    
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
  ): Promise<{ finalText: string; toolCalls: ToolCall[]; iterations: number; success?: boolean; error?: string }> {
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

      // If no tool calls, check if this is just a plan (not actual execution)
      if (response.toolCalls.length === 0) {
        // PLAN DETECTION: Check if response is just a plan without execution
        const trimmedResponse = response.content.trim();

        // Expanded plan patterns
        const isPlanOnly =
          trimmedResponse.startsWith('I will:') ||
          trimmedResponse.startsWith('I\'ll') ||
          trimmedResponse.toLowerCase().startsWith('calling ') ||
          /^[Ii] will (call|use|read|search|run|execute)/.test(trimmedResponse) ||
          /^[Ii]\'ll (call|use|read|search|run|execute)/.test(trimmedResponse) ||
          /^(First|I\'ll first|Let me first|I will first)/i.test(trimmedResponse) ||
          // Short responses that are likely just plans
          (trimmedResponse.length < 100 && /^(Sure|Okay|Let me|I will|I\'ll)/i.test(trimmedResponse));

        if (isPlanOnly && trimmedResponse.length < 300) {
          // This is just a plan, not actual work - don't accept it as final answer
          this.log(`PLAN DETECTED: "${trimmedResponse.substring(0, 50)}..." - forcing tool execution`);

          // Add nudge to continue iterating
          messages.push({
            role: 'user',
            content: 'That is just a plan. You MUST call tools to complete the task. Do NOT respond with another plan - actually call the tools now.'
          });

          continue;
        }

        // Otherwise, we have actual content - we're done
        this.log(`No tool calls - iteration complete`);
        return {
          finalText: response.content,
          toolCalls,
          iterations: iteration
        };
      }

      // Check for repeated tool calls (loop detection with nudge strategy)
      // Track how many times each tool call has been repeated
      const repeatCountMap = new Map<string, number>();

      for (const toolCall of response.toolCalls) {
        const argsSignature = JSON.stringify(toolCall.arguments);
        const normalizedToolName = toolCall.name.toLowerCase().replace(/[_-]/g, '');
        
        // Check if this exact tool call was made in the last 2 iterations
        const recentCalls = history.filter(h => 
          h.iteration >= iteration - 2 && 
          h.toolName.toLowerCase().replace(/[_-]/g, '') === normalizedToolName &&
          h.argsSignature === argsSignature
        );
        
        // Calculate repeat count for this tool call signature
        const repeatKey = `${normalizedToolName}:${argsSignature}`;
        const previousRepeatCount = repeatCountMap.get(repeatKey) || 0;
        const totalRepeatCount = previousRepeatCount + recentCalls.length;
        repeatCountMap.set(repeatKey, totalRepeatCount);

        if (recentCalls.length > 0) {
          this.log(`LOOP DETECTED: ${toolCall.name} called with same args at iterations ${recentCalls.map(h => h.iteration).join(', ')} (repeat count: ${totalRepeatCount})`);
          
          // STRATEGY: Nudge instead of hard stop - let LLM try a different approach
          if (totalRepeatCount === 2) {
            // 2nd repeat: Inject a nudge message and continue execution
            this.log(`Injecting nudge message for ${toolCall.name} - attempt ${totalRepeatCount + 1}`);

            // Add a nudge message to the conversation
            const nudgeMessage = `NOTICE: You just called ${toolCall.name} with the same arguments as before. This repeated call hasn't made progress. 
            
Please try a DIFFERENT approach:
1. Use a different tool that might give you new information
2. If you already have enough information, answer the user's question directly
3. Don't repeat the same tool call again - it won't give you different results`;

            messages.push({
              role: 'user',
              content: nudgeMessage
            });
          } else if (totalRepeatCount >= 4) {
            // 4th repeat: Force completion - LLM has had multiple chances
            this.log(`FORCE STOP: ${toolCall.name} repeated ${totalRepeatCount} times - forcing completion`);
            return {
              finalText: `Agent stopped after ${totalRepeatCount} repeated attempts with ${toolCall.name}. The tool returned the same result each time. You may need to try a different approach or tool.`,
              toolCalls,
              iterations: iteration,
              success: false,
              error: 'Infinite loop detected and prevented'
            };
          }
        }
        
        // Record BEFORE execution
        history.push({
          toolName: toolCall.name,
          argsSignature,
          iteration
        });
      }

      // Execute tool calls - collect results for THIS iteration only
      const currentIterationToolCalls: ToolCall[] = [];

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
          args: toolCall.arguments,
          toolCallId: toolCall.id // Store the tool call ID for linking results
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

        currentIterationToolCalls.push(toolCallObj);
        toolCalls.push(toolCallObj);
      }

      // Add assistant message with tool calls to history
      // CRITICAL FIX: If LLM returned tool calls without content, add explanatory message
      // This prevents the LLM from repeating the same tool call in the next iteration
      let assistantContent = response.content;
      if (!assistantContent || assistantContent.trim() === '') {
        // LLM returned only tool calls with no prose - add descriptive message
        const toolDescriptions = currentIterationToolCalls.map(tc => {
          const argsStr = JSON.stringify(tc.args);
          return `Calling ${tc.toolName}(${argsStr})`;
        }).join('; ');
        assistantContent = `I will: ${toolDescriptions}`;
        this.log(`Assistant content was empty - added synthetic message: "${assistantContent}"`);
      }
      
      // Push assistant message with content only
      // Note: Ollama does NOT accept tool_calls in assistant messages (causes 400 error)
      // The tool_call_id in tool results is sufficient for linking
      messages.push({
        role: 'assistant',
        content: assistantContent || ''
      });

      // CRITICAL FIX: Add ONLY current iteration's tool results WITH tool_call_id
      // This links each result to its corresponding tool call
      for (let i = 0; i < currentIterationToolCalls.length; i++) {
        const tc = currentIterationToolCalls[i];
        const matchingToolCall = response.toolCalls[i];
        messages.push({
          role: 'tool',
          content: tc.error || tc.result || 'No result',
          tool_call_id: matchingToolCall?.id // ← Critical for LLM to learn from results
        });
      }

      // TIGHTENED DECISION NUDGE: Push toward completion
      // Encourages LLM to answer now instead of continuing to explore
      messages.push({
        role: 'user',
        content: `Tool results received. You have ${maxIterations - iteration} of ${maxIterations} iterations remaining. If you have enough information to answer the user's question, provide your answer now. Only call another tool if you're missing critical information.`
      });

      // Emit iteration complete event
      if (onProgress) {
        onProgress({
          type: 'iteration_complete',
          iteration
        });
      }
    }

    this.log(`Max iterations (${maxIterations}) reached without final answer - giving LLM one more chance`);
    // Give LLM one final chance to provide a final answer
    const finalResponse = await this.callLLM(messages, tools);
    if (finalResponse.toolCalls.length === 0) {
      this.log(`Final answer from LLM: ${finalResponse.content.length} chars`);
      return {
        finalText: finalResponse.content,
        toolCalls,
        iterations: maxIterations + 1,
        success: true
      };
    }
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
          
          try {
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
          } catch (error: any) {
            // Check for DIRECTORY_NOT_FOUND error
            if (error.code === 'DIRECTORY_NOT_FOUND' || error.message?.includes('Directory not found')) {
              return {
                result: 'DIRECTORY_NOT_FOUND',
                error: undefined
              };
            }
            // Re-throw other errors
            throw error;
          }
        }
        
        case 'read_file': {
          const filePath = this.resolvePath(toolCall.args.path);
          this.log(`  Reading file: ${filePath}`);
          
          try {
            const result = await this.cli.readFile(filePath);

            // Empty file check
            if (!result || result.trim() === '') {
              return {
                result: 'The file exists but is empty.',
                error: undefined
              };
            }

            return { result };
          } catch (error: any) {
            // Check for file not found error
            if (error.code === 'ENOENT' || error.code === 'FILE_NOT_FOUND' || error.message?.includes('not found') || error.message?.includes('ENOENT')) {
              return {
                result: 'FILE_NOT_FOUND',
                error: undefined
              };
            }
            // Re-throw other errors
            throw error;
          }
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
          
          // CRITICAL FIX: Block long-running commands that would timeout
          const blockedPattern = AgentBridge.BLOCKED_COMMAND_PATTERNS.find(p => command.includes(p));
          if (blockedPattern) {
            this.log(`  BLOCKED: Command contains '${blockedPattern}'`);
            return {
              result: '',
              error: `BLOCKED: This command starts a long-running server ('${blockedPattern}'). Tell the user to run it manually in a terminal instead.`
            };
          }
          
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
          description: 'Run a shell command (BLOCKED: long-running servers like npm run dev, gradlew run, etc.)',
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
    prompt += '\ntool_call: {"tool":"read_file","args":{"path":"vscode-app/src/extension.ts"\}\}';
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
    prompt += '\n\n--- BLOCKED COMMANDS ---';
    prompt += '\nThe run_command tool BLOCKS these long-running commands:';
    prompt += '\n- npm run dev, npm start, yarn dev, yarn start';
    prompt += '\n- gradlew run, ./gradlew run, gradle run';
    prompt += '\n- mvn spring-boot:run, mvn jetty:run';
    prompt += '\n- node server, nodemon, webpack-dev-server, vite, next dev';
    prompt += '\nIf blocked, tell the user to run the command manually in their terminal.';
    prompt += '\n\n--- PATH HANDLING ---';
    prompt += '\n- Always use forward slashes (/) for paths';
    prompt += '\n- Paths are relative to workspace root';
    
    return prompt;
  }
}
