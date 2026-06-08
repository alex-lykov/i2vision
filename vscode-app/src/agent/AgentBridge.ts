/**
 * AgentBridge - Bridge between VSCode extension and agent core
 * 
 * Wraps agent core functionality and provides a clean API for AgentTabManager
 * to interact with configured agents. Implements modern agentic loop with
 * reflection - LLM sees tool results and decides if more tools are needed.
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
  toolCallId?: string; // OpenAI-compatible ID for linking results
}

/**
 * Progress event for real-time updates
 */
export interface ProgressEvent {
  type: 'tool_start' | 'tool_complete' | 'iteration_complete' | 'thinking' | 'tool_output';
  iteration: number;
  toolCall?: ToolCall;
  message?: string;
  partialOutput?: string; // For streaming build output
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
  | { type: 'done'; outcome: 'success' | 'error'; timestamp: number; iterations?: number }
  | { type: 'iteration_complete'; iteration: number; timestamp: number }
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
 * Options for agent loop execution
 */
interface AgentLoopOptions {
  streaming: boolean;
  onProgress?: ProgressCallback;
  toolCallArgs?: Map<string, Record<string, any>>; // Track tool args for non-streaming compatibility
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
  private currentIteration: number = 1;
  private progressCallback?: ProgressCallback;

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
    
    if (!workspaceRoot) {
      throw new Error('workspaceRoot must be explicitly provided');
    }

    if (extensionRoot && extensionRoot === workspaceRoot) {
      throw new Error('workspaceRoot and extensionRoot cannot be the same path');
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
   * Clean response markers from text (reasoning:, EOS, tool_call:, tool_calls:)
   * Only removes markers at line boundaries to preserve normal text
   */
  private cleanResponseMarkers(text: string): string {
    if (!text) return '';
    // Only remove markers at start of lines or as standalone words
    text = text.replace(/^\s*reasoning:\s*/gmi, '');
    text = text.replace(/\n\s*reasoning:\s*/gmi, '\n');
    text = text.replace(/\bEOS\b/g, '');
    text = text.replace(/^\s*tool_call:\s*/gmi, '');
    text = text.replace(/^\s*tool_calls:\s*/gmi, '');
    return text.trim();
  }

  /**
   * Emit progress event to callback
   */
  private emitProgress(event: ProgressEvent): void {
    if (this.progressCallback) {
      this.progressCallback(event);
    }
  }

  /**
   * Process user input through the agent with real-time progress updates (NON-STREAMING)
   * @deprecated Use processStreaming() and collect chunks instead
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

      // Execute agent loop and collect chunks
      const chunks: AgentChunk[] = [];
      const toolCallArgs = new Map<string, Record<string, any>>();
      
      for await (const chunk of this.executeAgentLoop(userInput, systemPrompt, context, { streaming: true, onProgress, toolCallArgs })) {
        chunks.push(chunk);
      }
      
      // Convert chunks to AgentResponse
      const response = this.chunksToResponse(chunks, toolCallArgs, startTime);
      
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
   * Convert streaming chunks to AgentResponse (for backward compatibility)
   */
  private chunksToResponse(
    chunks: AgentChunk[], 
    toolCallArgs: Map<string, Record<string, any>>,
    startTime: number
  ): AgentResponse {
    const toolCalls: ToolCall[] = [];
    let finalText = '';
    let iterations = 0;
    let error: string | undefined;

    for (const chunk of chunks) {
      if (chunk.type === 'text') {
        finalText += chunk.text;
      } else if (chunk.type === 'tool_call_started') {
        // Capture args when tool starts
        toolCallArgs.set(chunk.toolName, chunk.args);
      } else if (chunk.type === 'tool_call_completed') {
        toolCalls.push({
          toolName: chunk.toolName,
          args: toolCallArgs.get(chunk.toolName) || {},
          result: chunk.result
        });
      } else if (chunk.type === 'iteration_complete') {
        iterations = chunk.iteration;
      } else if (chunk.type === 'error') {
        error = chunk.error;
      } else if (chunk.type === 'done' && chunk.iterations) {
        iterations = chunk.iterations;
      }
    }

    return {
      finalText,
      toolCalls,
      iterations,
      durationMs: Date.now() - startTime,
      success: !error,
      error
    };
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

      // Execute unified agent loop with streaming enabled
      for await (const chunk of this.executeAgentLoop(userInput, systemPrompt, context, { streaming: true })) {
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
   * UNIFIED: Execute agent loop with optional streaming
   * Single source of truth for all agent logic (loop detection, tool execution, etc.)
   */
  async *executeAgentLoop(
    userInput: string,
    systemPrompt: string,
    context?: ProcessContext,
    options: AgentLoopOptions = { streaming: false }
  ): AsyncGenerator<AgentChunk> {
    const messages: LLMMessage[] = [
      { role: 'system', content: systemPrompt },
      { role: 'user', content: userInput }
    ];

    const tools = this.getTools();
    const maxIterations = this.config.iterationSettings.maxIterations;
    const toolCalls: ToolCall[] = [];
    const history: ToolCallHistory[] = [];

    this.log(`Starting agent loop (streaming=${options.streaming}) with max ${maxIterations} iterations`);

    for (let iteration = 1; iteration <= maxIterations; iteration++) {
      this.currentIteration = iteration;
      this.log(`[Iter ${iteration}/${maxIterations}] Calling LLM...`);

      // Emit thinking event (streaming only)
      if (options.streaming) {
        yield {
          type: 'thinking',
          message: `Iteration ${iteration}: Processing...`,
          timestamp: Date.now()
        };
      } else if (options.onProgress) {
        options.onProgress({
          type: 'thinking',
          message: `Iteration ${iteration}: Processing...`,
          iteration
        });
      }

      // Call LLM (streaming or non-streaming)
      let responseText = '';
      let streamingToolCalls: LLMToolCall[] = [];
      let textBuffer: string[] = [];

      if (options.streaming) {
        // Streaming: collect chunks
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

        for await (const chunk of streamResponse) {
          if (chunk.text) {
            responseText += chunk.text;
            textBuffer.push(chunk.text);
          }
          if (chunk.toolCalls) {
            streamingToolCalls = chunk.toolCalls;
          }
          if (chunk.done) {
            break;
          }
        }

        // FALLBACK: Parse tool calls from text if structured tool calls not provided
        if (streamingToolCalls.length === 0 && responseText.includes('tool_call:')) {
          this.log(`No structured tool calls - parsing from text`);
          // More robust pattern that handles malformed JSON
          const toolCallPattern = /tool_call:\s*({[\s\S]*?})(?=\n|$|tool_call:)/g;
          let match;
          while ((match = toolCallPattern.exec(responseText)) !== null) {
            try {
              // Clean up common JSON issues from LLM output
              let jsonStr = match[1]
                .replace(/""/g, ',"')  // Fix double quotes
                .replace(/\\}/g, '}')   // Fix escaped braces
                .replace(/\\{/g, '{')
                .replace(/'/g, '"');    // Fix single quotes
              
              const toolCallObj = JSON.parse(jsonStr);
              if (toolCallObj.tool) {
                streamingToolCalls.push({
                  id: `call_${Date.now()}_${streamingToolCalls.length}`,
                  name: toolCallObj.tool,
                  arguments: toolCallObj.args || {}
                });
                this.log(`Parsed tool call: ${toolCallObj.tool}`);
              }
            } catch (e: any) {
              this.log(`Warning: Could not parse tool call JSON: ${match[1].substring(0, 100)}... Error: ${e.message}`);
            }
          }
          // Remove tool_call: lines from text
          responseText = responseText.replace(/tool_call:\s*{[\s\S]*?}(?=\n|$|tool_call:)/g, '').trim();
        }

        // Clean text: remove reasoning:, EOS, tool_calls: markers
        responseText = this.cleanResponseMarkers(responseText);
        textBuffer = textBuffer.map(chunk => this.cleanResponseMarkers(chunk));

      } else {
        // Non-streaming: direct response
        const response = await this.callLLM(messages, tools);
        responseText = response.content;
        streamingToolCalls = response.toolCalls.map(tc => ({
          id: tc.id,
          name: tc.name,
          arguments: tc.arguments
        }));
      }

      this.log(`[Iter ${iteration}] LLM: ${responseText.length} chars, ${streamingToolCalls.length} tool(s)`);

      // No tool calls - check if plan or final answer
      if (streamingToolCalls.length === 0) {
        const trimmedResponse = responseText.trim();
        const isPlanOnly =
          trimmedResponse.startsWith('I will:') ||
          trimmedResponse.startsWith('I\'ll') ||
          trimmedResponse.toLowerCase().startsWith('calling ') ||
          /^[Ii] will (call|use|read|search|run|execute)/.test(trimmedResponse) ||
          /^[Ii]\'ll (call|use|read|search|run|execute)/.test(trimmedResponse) ||
          /^(First|I\'ll first|Let me first|I will first)/i.test(trimmedResponse) ||
          (trimmedResponse.length < 100 && /^(Sure|Okay|Let me|I will|I\'ll)/i.test(trimmedResponse));

        if (isPlanOnly && trimmedResponse.length < 300) {
          this.log(`[Iter ${iteration}] Plan detected - discarding text`);
          messages.push({
            role: 'user',
            content: 'That is just a plan. You MUST call tools to complete the task. Do NOT respond with another plan - actually call the tools now.'
          });
          continue;
        }

        // Final answer - yield/send text
        this.log(`[Iter ${iteration}] Final answer received`);
        
        if (options.streaming) {
          for (const textChunk of textBuffer) {
            yield {
              type: 'text',
              text: textChunk,
              timestamp: Date.now()
            };
          }
          yield {
            type: 'done',
            outcome: 'success',
            timestamp: Date.now(),
            iterations: iteration
          };
        } else if (options.onProgress) {
          options.onProgress({
            type: 'tool_complete',
            toolCall: { toolName: 'final_answer', args: {}, result: responseText },
            iteration
          });
        }
        
        return;
      }

      // Has tool calls - yield/send text first (streaming only)
      if (options.streaming) {
        for (const textChunk of textBuffer) {
          yield {
            type: 'text',
            text: textChunk,
            timestamp: Date.now()
          };
        }
      }

      // ===== LOOP DETECTION (SHARED LOGIC) =====
      const repeatCountMap = new Map<string, number>();
      const shouldNudge: string[] = [];

      for (const toolCall of streamingToolCalls) {
        const argsSignature = JSON.stringify(toolCall.arguments);
        const normalizedToolName = toolCall.name.toLowerCase().replace(/[_-]/g, '');
        
        // Check if this exact tool call was made in the last 2 iterations
        const recentCalls = history.filter(h => 
          h.iteration >= iteration - 2 && 
          h.toolName.toLowerCase().replace(/[_-]/g, '') === normalizedToolName &&
          h.argsSignature === argsSignature
        );
        
        // Calculate repeat count
        const repeatKey = `${normalizedToolName}:${argsSignature}`;
        const previousRepeatCount = repeatCountMap.get(repeatKey) || 0;
        const totalRepeatCount = previousRepeatCount + recentCalls.length;
        repeatCountMap.set(repeatKey, totalRepeatCount);

        if (recentCalls.length > 0) {
          this.log(`LOOP DETECTED: ${toolCall.name} called with same args at iterations ${recentCalls.map(h => h.iteration).join(', ')} (repeat count: ${totalRepeatCount})`);
          
          if (totalRepeatCount === 2) {
            shouldNudge.push(toolCall.name);
          } else if (totalRepeatCount >= 4) {
            this.log(`FORCE STOP: ${toolCall.name} repeated ${totalRepeatCount} times`);
            
            // Force stop - yield/send final message
            const errorMsg = `⚠️ Agent stopped after ${totalRepeatCount} repeated attempts with ${toolCall.name}. The tool returned the same result each time. Try a different approach.`;
            
            if (options.streaming) {
              yield {
                type: 'text',
                text: errorMsg,
                timestamp: Date.now()
              };
              yield {
                type: 'done',
                outcome: 'error',
                timestamp: Date.now(),
                iterations: iteration
              };
            } else if (options.onProgress) {
              options.onProgress({
                type: 'tool_complete',
                toolCall: { toolName: 'force_stop', args: {}, error: errorMsg },
                iteration
              });
            }
            return;
          }
        }
        
        // Record BEFORE execution
        history.push({
          toolName: toolCall.name,
          argsSignature,
          iteration
        });
      }

      // Inject nudge messages if needed
      if (shouldNudge.length > 0) {
        const uniqueTools = [...new Set(shouldNudge)];
        const nudgeMessage = `NOTICE: You just called ${uniqueTools.join(', ')} with the same arguments as before. This repeated call hasn't made progress. 
        
Please try a DIFFERENT approach:
1. Use a different tool that might give you new information
2. If you already have enough information, answer the user's question directly
3. Don't repeat the same tool call again - it won't give you different results`;

        messages.push({
          role: 'user',
          content: nudgeMessage
        });
      }
      // ===== END LOOP DETECTION =====

      // Execute tool calls
      const currentIterationToolCalls: ToolCall[] = [];

      for (const toolCall of streamingToolCalls) {
        // Emit tool start event
        if (options.streaming) {
          yield {
            type: 'tool_call_started',
            toolName: toolCall.name,
            args: toolCall.arguments,
            timestamp: Date.now()
          };
        } else if (options.onProgress) {
          options.onProgress({
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
          toolCallId: toolCall.id
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
          
          // Log tool result summary (first 100 chars)
          const resultSummary = result.result.substring(0, 100).replace(/\n/g, ' ');
          this.log(`[Iter ${iteration}] ✅ ${toolCall.name}: ${resultSummary}${result.result.length > 100 ? '...' : ''}`);
          
          // Emit tool complete event
          if (options.streaming) {
            yield {
              type: 'tool_call_completed',
              toolName: toolCall.name,
              result: result.result,
              timestamp: Date.now()
            };
          } else if (options.onProgress) {
            options.onProgress({
              type: 'tool_complete',
              toolCall: toolCallObj,
              iteration
            });
          }
        } catch (error: any) {
          toolCallObj.error = error.message;
          this.log(`[Iter ${iteration}] ❌ ${toolCall.name}: ${error.message}`);
          
          if (options.streaming) {
            yield {
              type: 'tool_call_completed',
              toolName: toolCall.name,
              result: `Error: ${error.message}`,
              timestamp: Date.now()
            };
          } else if (options.onProgress) {
            options.onProgress({
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
      let assistantContent = responseText;
      if (!assistantContent || assistantContent.trim() === '') {
        const toolDescriptions = currentIterationToolCalls.map(tc => {
          const argsStr = JSON.stringify(tc.args);
          return `Calling ${tc.toolName}(${argsStr})`;
        }).join('; ');
        assistantContent = `I will: ${toolDescriptions}`;
        this.log(`[Internal] Added synthetic assistant message for LLM history`);
      }
      
      messages.push({
        role: 'assistant',
        content: assistantContent || ''
      });

      // Add tool results with tool_call_id for linking
      for (let i = 0; i < currentIterationToolCalls.length; i++) {
        const tc = currentIterationToolCalls[i];
        const matchingToolCall = streamingToolCalls[i];
        messages.push({
          role: 'tool',
          content: tc.error || tc.result || 'No result',
          tool_call_id: matchingToolCall?.id
        });
      }

      messages.push({
        role: 'user',
        content: `Tool results received. You have ${maxIterations - iteration} of ${maxIterations} iterations remaining. If you have enough information to answer the user's question, provide your answer now. Only call another tool if you're missing critical information.`
      });

      // Emit iteration complete event
      if (options.streaming) {
        yield {
          type: 'iteration_complete',
          iteration,
          timestamp: Date.now()
        };
      } else if (options.onProgress) {
        options.onProgress({
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
      
      if (options.streaming) {
        yield {
          type: 'text',
          text: finalResponse.content,
          timestamp: Date.now()
        };
        yield {
          type: 'done',
          outcome: 'success',
          timestamp: Date.now(),
          iterations: maxIterations + 1
        };
      } else if (options.onProgress) {
        options.onProgress({
          type: 'tool_complete',
          toolCall: { toolName: 'final_answer', args: {}, result: finalResponse.content },
          iteration: maxIterations + 1
        });
      }
      return;
    }
    
    // Max iterations reached
    if (options.streaming) {
      yield {
        type: 'done',
        outcome: 'success',
        timestamp: Date.now(),
        iterations: maxIterations
      };
    }
  }

  /**
   * Call LLM through CLI (non-streaming)
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
   * Modern approach: idempotent reads, guarded writes, structured output, timeouts
   */
  private async executeTool(toolCall: ToolCall): Promise<{ result: string; error?: string }> {
    try {
      switch (toolCall.toolName) {
        // ===== FILE OPERATIONS =====
        case 'list_directory': {
          const dirPath = this.resolvePath(toolCall.args.path);
          const recursive = toolCall.args.recursive === true;
          
          this.log(`  Listing directory: ${dirPath} (recursive: ${recursive})`);
          
          try {
            const files = await this.cli.listFiles(dirPath, recursive);

            if (files.length === 0) {
              // Check if directory exists or if it's a real "not found" error
              try {
                fs.accessSync(dirPath);
                // Directory exists but is empty
                return {
                  result: 'This directory is empty. No files found.',
                  error: undefined
                };
              } catch (accessError: any) {
                // Directory doesn't exist
                return {
                  result: `DIRECTORY_NOT_FOUND: The folder '${toolCall.args.path}' does not exist.`,
                  error: undefined
                };
              }
            }

            const result = files.join('\n');

            if (result.length > this.config.formatting.maxObservationChars) {
              const truncated = result.substring(0, this.config.formatting.maxObservationChars);
              return {
                result: truncated + '\n\n[...truncated...]',
                error: undefined
              };
            }

            return { result };
          } catch (error: any) {
            // ENOENT = directory doesn't exist
            if (error.code === 'ENOENT' || error.message?.includes('no such file') || error.message?.includes('Directory not found')) {
              return {
                result: `DIRECTORY_NOT_FOUND: The folder '${toolCall.args.path}' does not exist.`,
                error: undefined
              };
            }
            throw error;
          }
        }
        
        case 'read_file': {
          const filePath = this.resolvePath(toolCall.args.path);
          this.log(`  Reading file: ${filePath}`);
          
          try {
            const result = await this.cli.readFile(filePath);

            if (!result || result.trim() === '') {
              return {
                result: 'The file exists but is empty.',
                error: undefined
              };
            }

            return { result };
          } catch (error: any) {
            if (error.code === 'ENOENT' || error.code === 'FILE_NOT_FOUND' || error.message?.includes('not found') || error.message?.includes('ENOENT')) {
              return {
                result: 'FILE_NOT_FOUND',
                error: undefined
              };
            }
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
        
        case 'get_file_context': {
          const filePath = this.resolvePath(toolCall.args.path);
          this.log(`  Getting file context: ${filePath}`);
          
          const context = await this.cli.getContext(filePath);
          
          return {
            result: JSON.stringify(context, null, 2)
          };
        }
        
        // ===== GIT OPERATIONS =====
        case 'git_status': {
          this.log(`  Running: git status --porcelain`);
          const result = await this.cli.runCommand('git status --porcelain');
          
          if (!result.stdout.trim()) {
            return { result: 'Working tree clean. No changes.' };
          }
          
          return { result: this.formatGitStatus(result.stdout) };
        }
        
        case 'git_diff': {
          const target = toolCall.args.target || 'unstaged';
          const filePath = toolCall.args.path || '';
          const flag = target === 'staged' ? '--staged' : '';
          
          this.log(`  Running: git diff ${flag} ${filePath}`);
          const result = await this.cli.runCommand(`git diff ${flag} ${filePath}`);
          
          return { result: result.stdout || 'No differences.' };
        }
        
        case 'git_log': {
          const count = Math.min(toolCall.args.count || 10, 50);
          
          this.log(`  Running: git log --oneline -${count}`);
          const result = await this.cli.runCommand(`git log --oneline -${count}`);
          
          return { result: result.stdout || 'No commits.' };
        }
        
        case 'git_branch': {
          const action = toolCall.args.action || 'current';
          
          if (action === 'current') {
            this.log(`  Running: git branch --show-current`);
            const result = await this.cli.runCommand('git branch --show-current');
            return { result: result.stdout.trim() || 'Not in a git repository' };
          } else {
            this.log(`  Running: git branch`);
            const result = await this.cli.runCommand('git branch');
            return { result: result.stdout || 'No branches found' };
          }
        }
        
        case 'git_commit': {
          const message = toolCall.args.message;
          const files = toolCall.args.files || ['.'];
          
          this.log(`  Git commit: "${message}" for ${files.length} files`);
          
          // Stage files
          for (const f of files) {
            this.log(`    Staging: ${f}`);
            await this.cli.runCommand(`git add "${f}"`);
          }
          
          // Commit
          const safeMessage = message.replace(/"/g, '\\"');
          const result = await this.cli.runCommand(`git commit -m "${safeMessage}"`);
          
          const output = result.stdout || result.stderr || 'Committed successfully.';
          return { result: output };
        }
        
        // ===== BUILD & TERMINAL OPERATIONS =====
        case 'run_build': {
          const command = toolCall.args.command;
          const timeout = 120000; // 2 minutes
          
          this.log(`  Running build: ${command} (timeout: ${timeout}ms)`);
          
          // Run build with progress streaming
          const result = await this.runCommandWithTimeout(command, timeout, undefined, (output: string) => {
            // Send partial output as heartbeat during long builds
            // This resets the idle timeout and shows user progress
            const partialOutput = output.slice(-200); // Last 200 chars
            this.emitProgress({
              type: 'tool_output',
              toolCall: { toolName: 'run_build', args: toolCall.args },
              partialOutput: partialOutput,
              iteration: this.currentIteration
            });
          });
          
          this.log(`  Build completed: exitCode=${result.exitCode}, stdout=${result.stdout.length} chars, stderr=${result.stderr.length} chars`);
          
          // Parse build results - check for errors in both stdout and stderr
          const hasError = result.exitCode !== 0 ||
                          result.stderr?.includes('FAILED') || 
                          result.stderr?.includes('BUILD FAILED') ||
                          result.stderr?.includes('error') ||
                          result.stdout?.includes('FAILED') ||
                          result.stdout?.includes('error:');
          
          // Build comprehensive result message
          let resultMessage = '';
          
          // Add exit code info
          if (result.exitCode !== null) {
            resultMessage += `Exit code: ${result.exitCode}\n`;
          }
          
          // Add summary
          if (hasError) {
            resultMessage += `\n❌ Build FAILED\n`;
            resultMessage += `\n--- Errors ---\n${this.extractBuildErrors(result.stderr || result.stdout)}`;
          } else {
            resultMessage += `\n✅ Build successful\n`;
          }
          
          // Add output (even if empty, to show agent something was run)
          const stdoutContent = result.stdout?.trim();
          const stderrContent = result.stderr?.trim();
          
          if (stdoutContent || stderrContent) {
            resultMessage += `\n\n--- Build Output ---\n`;
            if (stdoutContent) {
              resultMessage += `STDOUT:\n${stdoutContent.slice(-2000)}\n`;
            }
            if (stderrContent && !hasError) {
              resultMessage += `STDERR:\n${stderrContent.slice(-1000)}\n`;
            }
          } else {
            resultMessage += `\n⚠️ Build produced no output (likely cached/UP-TO-DATE)\n`;
            resultMessage += `This usually means Gradle found cached results and skipped compilation.\n`;
            resultMessage += `To force a rebuild, run: ./gradlew clean build\n`;
          }
          
          return {
            result: resultMessage
          };
        }
        
        case 'run_terminal': {
          const command = toolCall.args.command;
          const workingDir = toolCall.args.workingDir 
            ? this.resolvePath(toolCall.args.workingDir) 
            : undefined;
          
          this.log(`  Running terminal: ${command}`);
          
          // Block dangerous/long-running commands
          const blocked = AgentBridge.BLOCKED_COMMAND_PATTERNS;
          const isBlocked = blocked.some(b => command.includes(b));
          
          if (isBlocked) {
            return { 
              result: '', 
              error: `BLOCKED: This command starts a server or long-running process ('${blocked.find(b => command.includes(b))}'). Tell the user to run it manually in their terminal instead.`
            };
          }
          
          const timeout = 30000; // 30 seconds
          const result = await this.runCommandWithTimeout(command, timeout, workingDir);
          
          // Include exit code and both stdout/stderr for better diagnostics
          const output = result.stdout || result.stderr || 'Command completed with no output.';
          const exitCodeInfo = result.exitCode !== null ? ` (exit: ${result.exitCode})` : '';
          
          return { 
            result: `${output}${exitCodeInfo}`
          };
        }
        
        // Legacy - kept for backward compatibility
        case 'run_command': {
          const command = toolCall.args.command;
          const workingDir = toolCall.args.workingDir ? this.resolvePath(toolCall.args.workingDir) : undefined;
          
          this.log(`  Running command: ${command}`);
          
          // Block long-running commands that would timeout
          const blockedPattern = AgentBridge.BLOCKED_COMMAND_PATTERNS.find(p => command.includes(p));
          if (blockedPattern) {
            this.log(`  BLOCKED: Command contains '${blockedPattern}'`);
            return {
              result: '',
              error: `BLOCKED: This command starts a long-running server ('${blockedPattern}'). Tell the user to run it manually in a terminal instead.`
            };
          }
          
          const result = await this.cli.runCommand(command, workingDir);
          
          // Include exit code info if available
          const output = result.stdout || result.stderr || 'Command completed with no output';
          const exitCodeInfo = (result as any).exitCode !== undefined ? ` (exit: ${(result as any).exitCode})` : '';
          
          return {
            result: `${output}${exitCodeInfo}`
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
   * Run command with timeout and structured output
   * Modern approach: spawn with timeout, truncate large output, capture exit code
   */
  private async runCommandWithTimeout(
    command: string,
    timeoutMs: number,
    workingDir?: string,
    onOutput?: (output: string) => void
  ): Promise<{ stdout: string; stderr: string; exitCode: number | null }> {
    const { spawn } = require('child_process');
    const path = require('path');
    
    return new Promise((resolve) => {
      const proc = spawn(command, {
        shell: true,
        cwd: workingDir || this.workspaceRoot,
        timeout: timeoutMs
      });
      
      let stdout = '';
      let stderr = '';
      let exitCode: number | null = null;
      let timedOut = false;
      
      const timeout = setTimeout(() => {
        timedOut = true;
        proc.kill();
        stderr += `\n\n[TIMEOUT] Command exceeded ${timeoutMs}ms limit`;
        exitCode = null; // Mark as timed out
        resolve({ stdout, stderr, exitCode });
      }, timeoutMs);
      
      proc.stdout?.on('data', (data: Buffer) => {
        stdout += data.toString();
        // Stream output if callback provided
        if (onOutput) {
          onOutput(stdout);
        }
        // Truncate if too large (prevent memory issues)
        if (stdout.length > 10000) {
          stdout = stdout.slice(0, 10000) + '\n... (output truncated - exceeded 10KB)';
          proc.kill();
        }
      });
      
      proc.stderr?.on('data', (data: Buffer) => {
        stderr += data.toString();
        // Stream stderr too
        if (onOutput) {
          onOutput(stderr);
        }
        if (stderr.length > 5000) {
          stderr = stderr.slice(0, 5000) + '\n... (stderr truncated - exceeded 5KB)';
        }
      });
      
      proc.on('close', (code: number | null) => {
        clearTimeout(timeout);
        exitCode = code;
        resolve({ stdout, stderr, exitCode });
      });
      
      proc.on('error', (err: Error) => {
        clearTimeout(timeout);
        exitCode = -1;
        resolve({ stdout, stderr: err.message, exitCode });
      });
      
      proc.on('timeout', () => {
        timedOut = true;
        proc.kill();
        exitCode = null;
      });
    });
  }

  /**
   * Format git status output into structured sections
   */
  private formatGitStatus(output: string): string {
    if (!output.trim()) return 'No changes.';
    
    const lines = output.trim().split('\n');
    const staged = lines.filter(l => /^[MADRC]/.test(l.charAt(0)));
    const unstaged = lines.filter(l => /^.[MADRC]/.test(l));
    const untracked = lines.filter(l => l.startsWith('??'));
    
    let result = '';
    if (staged.length) result += `**Staged changes:**\n${staged.map(l => '  ' + l).join('\n')}\n\n`;
    if (unstaged.length) result += `**Unstaged changes:**\n${unstaged.map(l => '  ' + l).join('\n')}\n\n`;
    if (untracked.length) result += `**Untracked files:**\n${untracked.map(l => '  ' + l).join('\n')}`;
    
    return result || output;
  }

  /**
   * Extract build errors from output
   */
  private extractBuildErrors(output: string): string {
    if (!output) return 'Unknown error';
    
    // Look for common error patterns
    const errorLines = output.split('\n')
      .filter(line => 
        line.toLowerCase().includes('error') ||
        line.toLowerCase().includes('failed') ||
        line.includes('❌') ||
        line.includes('^') // Compiler error marker
      )
      .slice(0, 10); // Limit to first 10 error lines
    
    if (errorLines.length > 0) {
      return errorLines.join('\n');
    }
    
    // Fallback: return last 500 chars of output
    return output.slice(-500);
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
   * Modern tool design: idempotent reads, guarded writes, structured output, timeouts
   */
  private getTools(): LLMTool[] {
    return [
      // ===== FILE OPERATIONS =====
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
          description: 'Write content to a file. ALWAYS show the user what will be written first.',
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
      },
      
      // ===== GIT OPERATIONS (Read-only by default) =====
      {
        type: 'function',
        function: {
          name: 'git_status',
          description: 'Show working tree status — modified, staged, untracked files. Read-only.',
          parameters: {
            type: 'object',
            properties: {},
            required: []
          }
        }
      },
      {
        type: 'function',
        function: {
          name: 'git_diff',
          description: 'Show changes between commits, staged, or working tree. Read-only.',
          parameters: {
            type: 'object',
            properties: {
              target: { 
                type: 'string', 
                enum: ['staged', 'unstaged', 'all'], 
                description: 'What to diff' 
              },
              path: { 
                type: 'string', 
                description: 'Specific file or directory (optional)' 
              }
            },
            required: ['target']
          }
        }
      },
      {
        type: 'function',
        function: {
          name: 'git_log',
          description: 'Show commit history. Read-only.',
          parameters: {
            type: 'object',
            properties: {
              count: { 
                type: 'number', 
                description: 'Number of commits (default 10, max 50)' 
              }
            },
            required: []
          }
        }
      },
      {
        type: 'function',
        function: {
          name: 'git_branch',
          description: 'List branches or show current branch. Read-only.',
          parameters: {
            type: 'object',
            properties: {
              action: { 
                type: 'string', 
                enum: ['list', 'current'], 
                description: 'What to show' 
              }
            },
            required: []
          }
        }
      },
      {
        type: 'function',
        function: {
          name: 'git_commit',
          description: 'Stage and commit changes. ALWAYS show the user what will be committed first and get confirmation.',
          parameters: {
            type: 'object',
            properties: {
              message: { 
                type: 'string', 
                description: 'Commit message' 
              },
              files: { 
                type: 'array', 
                items: { type: 'string' }, 
                description: 'Files to stage (empty = all modified)' 
              }
            },
            required: ['message']
          }
        }
      },
      
      // ===== BUILD & TERMINAL OPERATIONS (With timeouts and safety) =====
      {
        type: 'function',
        function: {
          name: 'run_build',
          description: 'Run a build command and return results. Commands time out after 120 seconds. Use predefined commands only.',
          parameters: {
            type: 'object',
            properties: {
              command: { 
                type: 'string', 
                enum: [
                  './gradlew build',
                  './gradlew compileKotlin',
                  './gradlew test',
                  './gradlew :app:test',
                  'gradlew.bat build',
                  'gradlew.bat compileKotlin',
                  'gradlew.bat test',
                  'npm run build',
                  'npm test',
                  'npm run lint',
                  'npm run compile',
                  'tsc',
                  'mvn clean install',
                  'mvn test'
                ],
                description: 'Build command to run'
              }
            },
            required: ['command']
          }
        }
      },
      {
        type: 'function',
        function: {
          name: 'run_terminal',
          description: 'Run a short-lived terminal command and return stdout/stderr. Max 30 seconds. BLOCKED: servers, watchers, interactive commands.',
          parameters: {
            type: 'object',
            properties: {
              command: { 
                type: 'string', 
                description: 'Shell command to execute' 
              },
              workingDir: { 
                type: 'string', 
                description: 'Working directory relative to project root (optional)' 
              }
            },
            required: ['command']
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
