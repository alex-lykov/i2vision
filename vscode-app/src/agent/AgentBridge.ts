/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

/**
 * AgentBridge - Bridge between VSCode extension and agent core
 * 
 * Integrated with AgentStateMachine for comprehensive flow control:
 *   Intent → Plan → Constraints → Sequence → Execute → Verify → Output
 */

import * as vscode from 'vscode';
import * as fs from 'fs';
import * as path from 'path';
import {CLI, LLMChunk, LLMMessage, LLMResponse, LLMTool, LLMToolCall} from '../cliIntegration';
import {TerminalManager} from './TerminalManager';
import {AgentSettingsManager} from './AgentSettings';
import {applyEditsToContent, EditOperation, formatEditFailure} from './ApplyEditsTool';
import {
  AgentStateMachine,
  AgentState,
  AgentEvent,
  StateContext,
} from './AgentStateMachine';

export interface ContextProfile {
  eager: { currentFile?: boolean; projectMetadata?: boolean; gitStatus?: boolean; gitDiff?: boolean; relatedFiles?: boolean; directoryStructure?: boolean };
  lazy: { discovery?: boolean; fullContext?: boolean; contractValidation?: boolean };
}

export interface TaskContextProfile {
  eager?: Partial<ContextProfile['eager']>;
  lazy?: Partial<ContextProfile['lazy']>;
}

export interface VslfcContext {
  layer: string;
  currentFile?: { path: string; symbols: string; relatedFiles?: string[] };
  project?: { moduleCount: number; architecturePattern?: string; directoryStructure?: string };
  git?: { status?: string; diff?: string };
}

export interface AgentConfig {
  key: string;
  agentType: string;
  version: string;
  isActive: boolean;
  systemPromptTemplate: string;
  templateVariables: Record<string, string>;
  ruleSetKeys?: string[];
  parserTemplateName?: string;
  model: { id: string; provider: string; contextLength: number; maxOutputTokens: number; temperature: number; topP: number };
  llm: { timeoutSeconds: number; modificationTimeoutSeconds: number; finalTurnBonusSeconds: number; maxRetries: number; retryBackoffMs: number[] };
  formattingRules: { rules: string; brief: string; reasoningHeader: string; toolCallHeader: string; eosMarker: string };
  iterationSettings: { maxIterations: number; maxConsecutiveToolCalls: number; enableKickstart: boolean; kickstartMinInvalidOutputs: number };
  toolSelection: { requiredToolsForModification: string[]; defaultRelevanceThreshold: number; maxToolsPerTask: number; toolTimeoutSeconds: number };
  safety: { modificationKeywords: string[]; listingKeywords: string[]; listingModificationExclusions: string[]; blockGeneratedPaths: string[]; allowNewFileCreationPatterns: string[] };
  parsing: { enabledParsers: string[]; headerPattern: string; toolCallPattern: string; malformedPattern: string; maxResponseSize: number; maxProseChars: number };
  repairStrategies: any[];
  discovery: { maxSearchTerms: number; maxCandidates: number; frameworkProfiles: Record<string, any> };
  execution: { enableBuildVerification: boolean; buildCommand: string; buildTimeoutSeconds: number; enableSynthesis: boolean; synthesisOnlyForNonModification: boolean; fileOperations: { mode: string; shell: { executable: string; useNoProfile: boolean; readFileEnabled: boolean; writeFileEnabled: boolean; listDirectoryEnabled: boolean; regexSearchEnabled: boolean } }; longRunningPatterns?: string[] };
  formatting: { chunkSize: number; delayMs: number; maxObservationChars: number };
  streaming: { enabled: boolean; methodCandidates: string[]; fallbackToNonStreaming: boolean; fallbackChunkSize: number; fallbackChunkDelayMs: number };
  mcp: { enabled: boolean; injectClusterContext: boolean; directCliEnabled: boolean; allowedToolPrefixes: string[]; strictToolNamePolicy: boolean };
  context?: { default: ContextProfile; tasks?: Record<string, TaskContextProfile> };
}

export interface ToolCall {
  toolName: string;
  args: Record<string, any>;
  result?: string;
  error?: string;
  durationMs?: number;
  toolCallId?: string;
}

export interface InteractionRecord {
  timestamp: number;
  userInput: string;
  agentResponse: string;
  toolCalls: ToolCall[];
  iterations: number;
  durationMs: number;
}

export interface ProgressEvent {
  type: 'tool_start' | 'tool_complete' | 'iteration_complete' | 'thinking' | 'tool_output' | 'state_change';
  iteration: number;
  toolCall?: ToolCall;
  message?: string;
  partialOutput?: string;
  state?: { from: string; to: string; event: string };
}

export type ProgressCallback = (event: ProgressEvent) => void;
export interface AgentResponse { finalText: string; toolCalls: ToolCall[]; iterations: number; durationMs: number; success: boolean; error?: string; }

export type AgentChunk = 
  | { type: 'thinking'; message: string; timestamp: number }
  | { type: 'tool_call_started'; toolName: string; args: Record<string, any>; timestamp: number }
  | { type: 'tool_call_completed'; toolName: string; result: string; timestamp: number }
  | { type: 'text'; text: string; timestamp: number }
  | { type: 'done'; outcome: 'success' | 'error'; timestamp: number; iterations?: number; durationMs?: number; tokenUsage?: { prompt: number; completion: number; total: number } }
  | { type: 'iteration_complete'; iteration: number; timestamp: number }
  | { type: 'error'; error: string; timestamp: number }
  | { type: 'state_change'; from: string; to: string; event: string; timestamp: number };

interface ToolCallHistory { toolName: string; argsSignature: string; iteration: number; }
interface AgentLoopOptions { streaming: boolean; onProgress?: ProgressCallback; toolCallArgs?: Map<string, Record<string, any>>; }

export class AgentBridge {
  private config: AgentConfig;
  private cli: CLI;
  private terminalManager: TerminalManager;
  private settingsManager: AgentSettingsManager;
  private isInitialized: boolean = false;
  private outputChannel?: vscode.OutputChannel;
  private workspaceRoot: string;
  private extensionRoot: string;
  private currentIteration: number = 1;
  private progressCallback?: ProgressCallback;
  
  // STATE MACHINE: Single source of truth for all agent state
  private stateMachine: AgentStateMachine = new AgentStateMachine();
  
  // Auto-read tracking (not part of state machine - it's per-session cache)
  private _autoReadFiles: Set<string> = new Set();
  
  // Auto-nudge message (generated during tool execution, consumed in next iteration)
  private _autoNudge: string | null = null;
  
  // File snapshots for revert capability
  private _fileSnapshots: Map<string, string> = new Map();
  
  // Auto-inject build context tracking (per-workspace)
  private _buildFileReadAttempts = new Map<string, number>();
  private _hasReadBuildFiles = new Map<string, boolean>();

  // Legacy state tracking (migrated to state machine context)
  // These are kept for backward compatibility during transition
  private _pendingFixes: string[] = [];
  private _buildFailureCount: number = 0;
  private _consecutivePlans: number = 0;
  private _consecutiveSuccessfulEdits: number = 0;
  private _fixMode: boolean = false;
  private _failedEditAttempts: number = 0;
  private _lastBuildErrors: string = '';
  private _serverJustStarted: string | null = null;
  private _lastSearchPattern: string | null = null;
  private _lastSearchFiles: string[] = [];
  private _lastSearchIteration: number = 0;

  private static readonly MAX_TOOL_RESULT_LENGTH = 2000;
  private static readonly MAX_LIST_FILES_RESULTS = 100;
  private static readonly MAX_APPLY_EDITS = 50;
  private static readonly MAX_SEARCH_ITERATIONS = 3; // Max iterations searching for same pattern

  private longRunningPatterns: string[] = [
    'run', 'serve', 'dev', 'start', 'watch', 'nodemon', 'vite', 'next dev',
    'spring-boot:run', 'jetty:run', 'webpack --watch', 'tsc --watch',
    'gulp watch', 'grunt watch', 'cargo run', 'go run', 'python -m uvicorn', 'poetry run'
  ];

  private static readonly BLOCKED_COMMAND_PATTERNS = [
    'rm -rf /', 'del /F /S /Q C:\\*', 'format', 'mkfs', 'dd if=/dev/zero'
  ];

  constructor(
    config: AgentConfig, 
    outputChannel: vscode.OutputChannel, 
    extensionRoot: string, 
    workspaceRoot: string,
    settingsManager?: AgentSettingsManager
  ) {
    this.config = config;
    this.outputChannel = outputChannel;
    
    if (!workspaceRoot) throw new Error('workspaceRoot must be explicitly provided');
    if (extensionRoot && extensionRoot === workspaceRoot) throw new Error('workspaceRoot and extensionRoot cannot be the same path');
    
    this.workspaceRoot = workspaceRoot;
    this.extensionRoot = extensionRoot;
    this.settingsManager = settingsManager!;
    
    this.log(`Workspace root: ${this.workspaceRoot}`);
    this.cli = new CLI(this.workspaceRoot, outputChannel);
    
    const settings = this.settingsManager.getSettings();
    this.terminalManager = new TerminalManager(outputChannel, settings.terminal.autoCloseDelayMs);
    
    const customPatterns = (config as any).execution?.longRunningPatterns;
    if (customPatterns && Array.isArray(customPatterns) && customPatterns.length > 0) {
      this.longRunningPatterns = customPatterns;
    }
  }

  async initialize(): Promise<void> { 
    this.isInitialized = true;
    this.stateMachine.reset();
    this.log('AgentBridge initialized with state machine');
  }

  setWorkspaceRoot(newWorkspaceRoot: string): void {
    if (newWorkspaceRoot && newWorkspaceRoot !== this.workspaceRoot) {
      this.workspaceRoot = newWorkspaceRoot;
      this.cli = new CLI(this.workspaceRoot, this.outputChannel);
      this.stateMachine.reset();
    }
  }

  getConfig(): AgentConfig { return { ...this.config }; }

  /** Get current state machine state for debugging/monitoring */
  getState(): { state: AgentState; context: StateContext; history: Array<{ from: AgentState; to: AgentState; event: AgentEvent }> } {
    return {
      state: this.stateMachine.state,
      context: this.stateMachine.context,
      history: this.stateMachine.getLastTransitions(10),
    };
  }

  private log(message: string): void {
    const timestamp = new Date().toLocaleTimeString();
    const formatted = `[${timestamp}] [AgentBridge] ${message}`;
    if (this.outputChannel) this.outputChannel.appendLine(formatted);
    console.log(formatted);
  }

  /** Detect task type from user input */
  private detectTaskType(userInput: string): string {
    const intent = this.stateMachine.classifyIntent(userInput);
    this.log(`Intent classified: ${intent}`);
    return intent;
  }

  private async loadEagerContext(profile: ContextProfile, currentFile?: string): Promise<VslfcContext> {
    return { layer: this.config.agentType };
  }

  private mergeContextProfiles(defaultProfile: ContextProfile, taskProfile?: TaskContextProfile): ContextProfile {
    const merged: ContextProfile = { eager: { ...defaultProfile.eager }, lazy: { ...defaultProfile.lazy } };
    if (taskProfile) {
      if (taskProfile.eager) Object.assign(merged.eager, taskProfile.eager);
      if (taskProfile.lazy) Object.assign(merged.lazy, taskProfile.lazy);
    }
    return merged;
  }

  private getTools(lazyProfile?: ContextProfile['lazy'], toolFilter?: 'all' | 'fix_only' | 'read_only'): LLMTool[] {
    // Use state machine to determine tool filter if not explicitly provided
    if (!toolFilter) {
      toolFilter = this.stateMachine.getToolFilter();
    }

    const allTools: LLMTool[] = [
      {
        type: 'function',
        function: {
          name: 'list_directory',
          description: 'List files in a directory',
          parameters: {
            type: 'object',
            properties: {
              path: { type: 'string', description: 'Directory path relative to workspace root' },
              recursive: { type: 'boolean', description: 'Search recursively (default: false)' }
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
            properties: { path: { type: 'string', description: 'File path relative to workspace root' } },
            required: ['path']
          }
        }
      },
      {
        type: 'function',
        function: {
          name: 'write_file',
          description: 'Write content to a file. Use for large changes, new files, or when apply_edits would need more than 50 edits.',
          parameters: {
            type: 'object',
            properties: {
              path: { type: 'string', description: 'File path relative to workspace root' },
              content: { type: 'string', description: 'Full file content to write' }
            },
            required: ['path', 'content']
          }
        }
      },
      {
        type: 'function',
        function: {
          name: 'apply_edits',
          description: 'Apply targeted edits to an existing file. MAX 50 edits per call. For small changes (1-5 lines each). For large rewrites (>50 edits), use write_file instead.',
          parameters: {
            type: 'object',
            properties: {
              path: { type: 'string', description: 'File path relative to workspace root' },
              edits: {
                type: 'array',
                description: 'List of edit operations. MAX 50 edits per call. For larger changes, use write_file.',
                items: {
                  type: 'object',
                  properties: {
                    search: { type: 'string', description: 'Exact text to find (must be unique in file)' },
                    replace: { type: 'string', description: 'Replacement text' },
                    lineHint: { type: 'number', description: 'Optional: approximate line number' }
                  },
                  required: ['search', 'replace']
                }
              }
            },
            required: ['path', 'edits']
          }
        }
      },
      {
        type: 'function',
        function: {
          name: 'search_files',
          description: 'Search for files matching a regex pattern. Returns file paths and matching lines. TIP: If you find a file, read it immediately instead of searching more.',
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
            properties: { path: { type: 'string', description: 'File path relative to workspace root' } },
            required: ['path']
          }
        }
      },
      {
        type: 'function',
        function: {
          name: 'git_status',
          description: 'Show working tree status (modified, staged, untracked files)',
          parameters: { type: 'object', properties: {}, required: [] }
        }
      },
      {
        type: 'function',
        function: {
          name: 'git_diff',
          description: 'Show changes between commits, staged, or working tree',
          parameters: {
            type: 'object',
            properties: {
              target: { type: 'string', enum: ['staged', 'unstaged', 'all'], description: 'What to diff' },
              path: { type: 'string', description: 'Specific file or directory (optional)' }
            },
            required: ['target']
          }
        }
      },
      {
        type: 'function',
        function: {
          name: 'run_build',
          description: 'Run a build command. FOR COMPILATION: use compileKotlin (source only, NO tests). FOR TESTS: use test. NEVER use "build" - it runs ALL tests and is slow.',
          parameters: {
            type: 'object',
            properties: {
              command: {
                type: 'string',
                description: 'Build command. FOR COMPILATION (source only): ./gradlew compileKotlin. FOR TESTS: ./gradlew test. NEVER use ./gradlew build (runs all tests, slow).',
                enum: [
                  './gradlew compileKotlin',
                  './gradlew :app:server:compileKotlin',
                  './gradlew :app:shared:compileKotlin',
                  './gradlew :app:client:compileKotlin',
                  './gradlew test',
                  './gradlew :app:server:test',
                  'gradlew.bat compileKotlin',
                  'gradlew.bat :app:server:compileKotlin',
                  'gradlew.bat test',
                  'npm run build',
                  'npm test',
                  'tsc',
                  'mvn clean install',
                  'mvn test'
                ]
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
          description: 'Run a terminal command. FOR SERVERS: use gradlew :app:server:run. IMPORTANT: Servers take 10-30 seconds to start. Do NOT check terminal_status immediately - wait 15+ seconds first.',
          parameters: {
            type: 'object',
            properties: {
              command: { type: 'string', description: 'Shell command. For servers: gradlew :app:server:run' },
              workingDir: { type: 'string', description: 'Working directory relative to project root (optional)' }
            },
            required: ['command']
          }
        }
      },
      {
        type: 'function',
        function: {
          name: 'kill_terminal',
          description: 'Stop a running managed terminal by name',
          parameters: {
            type: 'object',
            properties: { name: { type: 'string', description: 'Terminal name (e.g., "backend", "frontend")' } },
            required: ['name']
          }
        }
      },
      {
        type: 'function',
        function: {
          name: 'list_terminals',
          description: 'List all managed terminals and their status',
          parameters: { type: 'object', properties: {}, required: [] }
        }
      },
      {
        type: 'function',
        function: {
          name: 'terminal_status',
          description: 'Check if a specific terminal is running. IMPORTANT: Only use this 15+ seconds after starting a server - servers take time to start up.',
          parameters: {
            type: 'object',
            properties: { name: { type: 'string', description: 'Terminal name to check' } },
            required: ['name']
          }
        }
      },
      {
        type: 'function',
        function: {
          name: 'revert_file',
          description: 'Revert a file to its original state before edits were made. Use this to undo changes that caused build failures.',
          parameters: {
            type: 'object',
            properties: {
              path: { type: 'string', description: 'File path relative to workspace root to revert' }
            },
            required: ['path']
          }
        }
      },
      {
        type: 'function',
        function: {
          name: 'revert_all',
          description: 'Revert ALL modified files to their original state. Use this to undo all changes in the current session.',
          parameters: { type: 'object', properties: {}, required: [] }
        }
      },
      {
        type: 'function',
        function: {
          name: 'list_snapshots',
          description: 'List all files that have been modified and can be reverted.',
          parameters: { type: 'object', properties: {}, required: [] }
        }
      }
    ];

    if (toolFilter === 'fix_only') {
      const fixTools = allTools.filter(t => 
        ['apply_edits', 'read_file', 'write_file', 'get_file_context'].includes(t.function.name)
      );
      this.log(`Tool filter: fix_only (${fixTools.length}/${allTools.length} tools)`);
      return fixTools;
    }
    
    if (toolFilter === 'read_only') {
      const readTools = allTools.filter(t => 
        ['read_file', 'list_directory', 'search_files', 'get_file_context', 'git_status', 'git_diff', 'git_log', 'git_branch', 'list_terminals', 'terminal_status', 'list_snapshots'].includes(t.function.name)
      );
      this.log(`Tool filter: read_only (${readTools.length}/${allTools.length} tools)`);
      return readTools;
    }

    return allTools;
  }

  private extractFinalResponse(text: string): string {
    if (!text) return '';
    text = text.replace(/reasoning:\s*/gi, '');
    text = text.replace(/\bEOS\b/gi, '');
    text = text.replace(/tool_call:\s*\{[\s\S]*?\}(?=\n|$|tool_call:)/g, '');
    text = text.replace(/^tool_calls:\s*/gmi, '');
    const blocks = text.split(/\n\n+/).filter(b => b.trim().length > 20);
    if (blocks.length > 1) return blocks.reduce((a, b) => a.length > b.length ? a : b).trim();
    return text.trim();
  }

  private emitProgress(event: ProgressEvent): void {
    if (this.progressCallback) this.progressCallback(event);
  }

  async process(userInput: string, currentFile?: string, onProgress?: ProgressCallback): Promise<AgentResponse> {
    if (!this.isInitialized) await this.initialize();

    const startTime = Date.now();
    try {
      const templateVars = { ...this.config.templateVariables, currentFile: currentFile || this.config.templateVariables.currentFile || '', task: userInput };
      const systemPrompt = this.buildSystemPrompt(templateVars);
      
      const chunks: AgentChunk[] = [];
      const toolCallArgs = new Map<string, Record<string, any>>();
      
      for await (const chunk of this.executeAgentLoop(userInput, systemPrompt, { streaming: true, onProgress, toolCallArgs })) {
        chunks.push(chunk);
      }
      
      const response = this.chunksToResponse(chunks, toolCallArgs, startTime);
      return { ...response, durationMs: Date.now() - startTime, success: response.success ?? true };
    } catch (error: any) {
      return { finalText: `Error: ${error.message}`, toolCalls: [], iterations: 0, durationMs: Date.now() - startTime, success: false, error: error.message };
    }
  }

  private chunksToResponse(chunks: AgentChunk[], toolCallArgs: Map<string, Record<string, any>>, startTime: number): AgentResponse {
    const toolCalls: ToolCall[] = [];
    let finalText = '';
    let iterations = 0;
    let error: string | undefined;

    for (const chunk of chunks) {
      if (chunk.type === 'text') finalText += chunk.text;
      else if (chunk.type === 'tool_call_started') toolCallArgs.set(chunk.toolName, chunk.args);
      else if (chunk.type === 'tool_call_completed') {
        toolCalls.push({ toolName: chunk.toolName, args: toolCallArgs.get(chunk.toolName) || {}, result: chunk.result });
      } else if (chunk.type === 'iteration_complete') iterations = chunk.iteration;
      else if (chunk.type === 'error') error = chunk.error;
      else if (chunk.type === 'done' && chunk.iterations) iterations = chunk.iterations;
      else if (chunk.type === 'state_change') {
        this.log(`State: ${chunk.from} → ${chunk.to} (${chunk.event})`);
      }
    }

    return { finalText, toolCalls, iterations, durationMs: Date.now() - startTime, success: !error, error };
  }

  async *processStreaming(userInput: string, currentFile?: string): AsyncGenerator<AgentChunk> {
    if (!this.isInitialized) await this.initialize();
    try {
      const templateVars = { ...this.config.templateVariables, currentFile: currentFile || this.config.templateVariables.currentFile || '', task: userInput };
      const systemPrompt = this.buildSystemPrompt(templateVars);
      for await (const chunk of this.executeAgentLoop(userInput, systemPrompt, { streaming: true })) yield chunk;
    } catch (error: any) {
      yield { type: 'error', error: error.message, timestamp: Date.now() };
    }
  }

  async *executeAgentLoop(userInput: string, systemPrompt: string, options: AgentLoopOptions = { streaming: false }): AsyncGenerator<AgentChunk> {
    // STATE MACHINE FLOW: Intent → Plan → Constraints → Sequence → Execute → Verify → Output
    
    const taskType = this.detectTaskType(userInput);
    let contextProfile: ContextProfile | undefined;
    
    if (this.config.context) {
      const defaultProfile = this.config.context.default;
      const taskProfile = this.config.context.tasks?.[taskType];
      contextProfile = this.mergeContextProfiles(defaultProfile, taskProfile);
    }

    const loadedContext = contextProfile ? await this.loadEagerContext(contextProfile, this.config.templateVariables.currentFile) : undefined;
    const contextEnhancedPrompt = this.injectContextIntoPrompt(systemPrompt, loadedContext);

    const messages: LLMMessage[] = [
      { role: 'system', content: contextEnhancedPrompt },
      { role: 'user', content: userInput }
    ];

    let maxIterations = this.config.iterationSettings.maxIterations;
    if (typeof maxIterations !== 'number' || maxIterations < 0 || maxIterations > 100) maxIterations = 50;
    
    // Reset all state for a fresh conversation
    this.stateMachine.reset();
    this._pendingFixes = [];
    this._buildFailureCount = 0;
    this._autoReadFiles.clear();
    this._consecutivePlans = 0;
    this._consecutiveSuccessfulEdits = 0;
    this._fixMode = false;
    this._failedEditAttempts = 0;
    this._lastBuildErrors = '';
    this._serverJustStarted = null;
    this._lastSearchPattern = null;
    this._lastSearchFiles = [];
    this._lastSearchIteration = 0;
    this._autoNudge = null;

    const toolCalls: ToolCall[] = [];
    const history: ToolCallHistory[] = [];

    let iteration = 0;
    
    while (true) {
      iteration++;
      this._autoNudge = null;
      this.stateMachine.incrementIteration();
      
      // STATE: INTENT - Classify user intent
      if (iteration === 1) {
        this.stateMachine.dispatch(AgentEvent.USER_INPUT);
        this.stateMachine.dispatch(AgentEvent.INTENT_CLASSIFIED, { 
          intentType: taskType,
          taskDescription: userInput,
        });
        
        if (options.streaming) {
          yield { type: 'thinking', message: `Understanding task: ${taskType}`, timestamp: Date.now() };
        }
      }
      
      // Check iteration limits
      if (iteration > maxIterations) {
        this.stateMachine.dispatch(AgentEvent.MAX_ITERATIONS);
        if (options.streaming) {
          yield { type: 'text', text: `⚠️ Stopped after ${iteration} iterations.`, timestamp: Date.now() };
          yield { type: 'done', outcome: 'error', timestamp: Date.now(), iterations: iteration };
        }
        return;
      }
      
      // Check for stuck states
      if (this.stateMachine.isPlanLoopStuck()) {
        this.log('Plan loop detected - forcing tool usage');
      }
      
      if (this.stateMachine.isBuildFixCycleStuck()) {
        this.log('Build-fix cycle stuck after 3 failures - stopping');
        this.stateMachine.dispatch(AgentEvent.MAX_FAILURES);
        if (options.streaming) {
          yield { type: 'text', text: `⚠️ Build failed ${this._buildFailureCount} times. Manual intervention required.`, timestamp: Date.now() };
          yield { type: 'done', outcome: 'error', timestamp: Date.now(), iterations: iteration };
        }
        return;
      }
      
      this.currentIteration = iteration;

      // STATE: PLAN - Get tools based on current state
      const tools = this.getTools(contextProfile?.lazy, this.stateMachine.getToolFilter());
      
      // AUTO-FIX WORKFLOW (when in build-fix cycle)
      if (this._pendingFixes && this._pendingFixes.length > 0) {
        const filesToRead = [...this._pendingFixes];
        this._pendingFixes = [];
        
        for (const file of filesToRead) {
          const cleanPath = file.replace(/^(e:\/\/\/|file:\/\/\/)/, '');
          const normalizedPath = cleanPath.toLowerCase();
          if (this._autoReadFiles.has(normalizedPath)) continue;
          
          try {
            const result = await this.executeTool({ toolName: 'read_file', args: { path: cleanPath } });
            this._autoReadFiles.add(normalizedPath);
            messages.push({ role: 'assistant', content: `Reading ${cleanPath} to understand the compilation error.` });
            messages.push({ role: 'tool', content: `[AUTO-READ] ${cleanPath}:\n${result.result?.substring(0, 3000) || result.error}`, tool_call_id: `auto_fix_${Date.now()}_${cleanPath}` });
          } catch (e: any) {
            messages.push({ role: 'tool', content: `[AUTO-READ ERROR] ${cleanPath}: ${e.message}`, tool_call_id: `auto_fix_${Date.now()}_${cleanPath}` });
          }
        }
        
        const compilerErrors = this._lastBuildErrors.split('\n').filter(l => l.includes('.kt:') || l.includes('.java:') || l.includes('Unresolved reference')).slice(0, 10);
        const fileList = filesToRead.slice(0, 5).map(f => `${path.basename(f)} → ${f}`).join('\n  ');
        const examplePath = filesToRead[0] || 'path/to/File.kt';

        messages.push({
          role: 'user',
          content: `I've automatically read the failing files. 

**COMPILATION ERRORS:**
${compilerErrors.map(e => `- ${e}`).join('\n') || '(see build output)'}

**FILES TO FIX:**
  ${fileList}

**USE apply_edits NOW:**
\`\`\`json
{
  "path": "${examplePath}",
  "edits": [
    { "search": "broken code", "replace": "fixed code" }
  ]
}
\`\`\`

DO NOT re-run build. DO NOT read more files. Call apply_edits NOW.`
        });
      }

      // FORCED BUILD VERIFICATION (after consecutive successful edits)
      if (this.stateMachine.shouldForceBuild()) {
        this.log(`Consecutive edits (${this._consecutiveSuccessfulEdits}) - forcing build verification`);
        this._consecutiveSuccessfulEdits = 0;
        this._pendingFixes = [];
        this._fixMode = false;

        const buildCmd = process.platform === 'win32' ? '.\\gradlew :app:server:compileKotlin --console=plain' : './gradlew :app:server:compileKotlin --console=plain';

        try {
          const buildResult = await this.executeTool({ toolName: 'run_terminal', args: { command: buildCmd, workingDir: this.workspaceRoot } });
          const buildOutput = buildResult.result || buildResult.error || 'No output';
          messages.push({ role: 'tool', content: `[AUTO BUILD VERIFICATION]\n${buildOutput}`, tool_call_id: `auto_build_${Date.now()}` } as any);
          messages.push({ role: 'user', content: 'Build verification complete. Review results. If build passed, task is complete. If errors, fix them.' });
          continue;
        } catch (e: any) {
          messages.push({ role: 'tool', content: `Error: ${e.message}` } as any);
        }
      }

      if (options.streaming) yield { type: 'thinking', message: `Processing...`, timestamp: Date.now() };

      // INJECT PENDING MESSAGES (auto-context, auto-fix, etc.)
      const pendingMessages = (this as any)._pendingMessages as LLMMessage[] | undefined;
      if (pendingMessages && pendingMessages.length > 0) {
        messages.push(...pendingMessages);
        this.log(`Injected ${pendingMessages.length} pending message(s) into conversation`);
        (this as any)._pendingMessages = []; // Clear after injection
      }

      let responseText = '';
      let streamingToolCalls: LLMToolCall[] = [];
      let textBuffer: string[] = [];
      let textAlreadyStreamed = false;
      let toolCallDetected = false;
      let streamBuffer = '';

      if (options.streaming) {
        const rawResponse = await this.cli.callLLM(this.config.model.id, messages, { temperature: this.config.model.temperature, top_p: this.config.model.topP, max_tokens: this.config.model.maxOutputTokens }, tools, true);
        
        // Check if response is actually an AsyncGenerator (streaming supported)
        const isAsyncGenerator = rawResponse && typeof (rawResponse as any)[Symbol.asyncIterator] === 'function';
        
        if (!isAsyncGenerator) {
          // Streaming not supported, fall back to non-streaming
          this.log(`Streaming not available, falling back to non-streaming mode`);
          const nonStreamResponse = await this.cli.callLLM(this.config.model.id, messages, { temperature: this.config.model.temperature, top_p: this.config.model.topP, max_tokens: this.config.model.maxOutputTokens }, tools, false) as LLMResponse;
          responseText = nonStreamResponse.content;
          streamingToolCalls = nonStreamResponse.toolCalls.map(tc => ({ id: tc.id, name: tc.name, arguments: tc.arguments }));
        } else {
          const streamResponse = rawResponse as AsyncGenerator<LLMChunk>;
          for await (const chunk of streamResponse) {
            if (chunk.text) {
              responseText += chunk.text;
              textBuffer.push(chunk.text);
              
              if (!toolCallDetected) {
                streamBuffer += chunk.text;
                const toolCallIdx = streamBuffer.indexOf('tool_call:');
                if (toolCallIdx !== -1) {
                  toolCallDetected = true;
                  const beforeToolCall = streamBuffer.substring(0, toolCallIdx).trim();
                  if (beforeToolCall) {
                    textAlreadyStreamed = true;
                    yield { type: 'text', text: beforeToolCall, timestamp: Date.now() };
                  }
                  streamBuffer = '';
                } else if (streamBuffer.length > 20) {
                  const safeLength = streamBuffer.length - 10;
                  const textToStream = streamBuffer.substring(0, safeLength);
                  streamBuffer = streamBuffer.substring(safeLength);
                  if (textToStream) {
                    textAlreadyStreamed = true;
                    yield { type: 'text', text: textToStream, timestamp: Date.now() };
                  }
                }
              }
            }
            if (chunk.toolCalls) streamingToolCalls = chunk.toolCalls;
            if (chunk.tokenUsage) (this as any)._lastTokenUsage = chunk.tokenUsage;
            if (chunk.done) break;
          }
          
          if (!toolCallDetected && streamBuffer.trim()) {
            textAlreadyStreamed = true;
            yield { type: 'text', text: streamBuffer.trim(), timestamp: Date.now() };
          }

          if (streamingToolCalls.length === 0 && responseText.includes('tool_call:')) {
            const toolCallPattern = /tool_call:\s*({[\s\S]*?})(?=\n|$|tool_call:)/g;
            let match;
            let parseIndex = 0;
            while ((match = toolCallPattern.exec(responseText)) !== null) {
              try {
                let jsonStr = match[1].replace(/""/g, ',"').replace(/\\}/g, '}').replace(/\\{/g, '{').replace(/'/g, '"');
                const toolCallObj = JSON.parse(jsonStr);
                if (toolCallObj.tool) {
                  streamingToolCalls.push({ id: `call_${iteration}_${parseIndex}`, name: toolCallObj.tool, arguments: toolCallObj.args || {} });
                  parseIndex++;
                }
              } catch (e: any) {}
            }
          }

          responseText = this.extractFinalResponse(responseText);
          textBuffer = [responseText];
        }
      } else {
        const response = await this.callLLM(messages, tools);
        responseText = response.content;
        streamingToolCalls = response.toolCalls.map(tc => ({ id: tc.id, name: tc.name, arguments: tc.arguments }));
      }

      // STATE: PLAN → CONSTRAINTS/COMPLETE
      // Classify LLM response and transition state machine
      const hasToolCalls = streamingToolCalls.length > 0;
      const responseEvent = this.stateMachine.classifyLLMResponse(responseText, hasToolCalls, this.stateMachine.context);
      
      // NATURAL EXIT (no tool calls)
      if (!hasToolCalls) {
        const trimmedResponse = responseText.trim();
        const isPlanOnly = responseEvent === AgentEvent.PLAN_ONLY;

        if (isPlanOnly) {
          this._consecutivePlans++;
          const lastBuildError = messages.filter(m => m.role === 'tool' && typeof m.content === 'string' && m.content.includes('BUILD FAILED')).pop();
          if (lastBuildError) this._fixMode = true;
          
          let nudgeMessage = `STOP describing plans. Use tool calling API NOW.`;
          if (lastBuildError) {
            const content = lastBuildError.content as string;
            const fileMatch = content.match(/FILES TO READ AND FIX:[\s\S]*?(?:COMPILER ERRORS|$)/);
            if (fileMatch) {
              const files = fileMatch[0].split('\n').filter(line => line.includes('.kt:') || line.includes('.java')).map(line => line.replace(/^\s*-\s*/, '').trim());
              nudgeMessage += `\n\nBuild failed. Use apply_edits to fix:\n${files.map(f => `- ${f}`).join('\n')}`;
            }
          }
          
          messages.push({ role: 'user', content: nudgeMessage });
          this.stateMachine.dispatch(responseEvent);
          continue;
        } else {
          this._consecutivePlans = 0;
        }

        // Complete naturally
        this.stateMachine.dispatch(responseEvent);
        if (options.streaming) {
          if (!textAlreadyStreamed) {
            for (const textChunk of textBuffer) yield { type: 'text', text: textChunk, timestamp: Date.now() };
          }
          yield { type: 'done', outcome: 'success', timestamp: Date.now(), iterations: iteration };
        }
        return;
      }

      // STATE: CONSTRAINTS - Validate tool calls
      this.log(`Validating ${streamingToolCalls.length} tool calls against constraints...`);
      this.stateMachine.dispatch(AgentEvent.TOOL_CALLS_RECEIVED);
      
      const toolCallObjs = streamingToolCalls.map(tc => ({ toolName: tc.name, args: tc.arguments }));
      const validation = this.stateMachine.validateConstraints(toolCallObjs);
      
      if (!validation.passed) {
        this.log(`Constraints validation failed: ${validation.violations.join(', ')}`);
        this.stateMachine.dispatch(AgentEvent.PLAN_INVALID, {
          constraintsValidation: validation,
        });
        
        // Inject constraint violations as user message
        messages.push({ 
          role: 'user', 
          content: `⚠️ Plan validation failed:\n${validation.violations.join('\n')}\n\nPlease revise your tool calls to comply with constraints.` 
        });
        continue;
      }
      
      if (validation.warnings.length > 0) {
        this.log(`Constraints warnings: ${validation.warnings.join(', ')}`);
      }
      
      this.stateMachine.dispatch(AgentEvent.PLAN_VALIDATED, {
        constraintsValidation: validation,
        safetyFlags: validation.safetyFlags,
        validatedToolCalls: streamingToolCalls.map(tc => ({ toolName: tc.name, args: tc.arguments, toolCallId: tc.id })),
      });
      
      // STATE: SEQUENCE - Optimize execution order
      this.log('Optimizing tool execution sequence...');
      const optimizedSequence = this.stateMachine.optimizeSequence(
        streamingToolCalls.map(tc => ({ toolName: tc.name, args: tc.arguments, toolCallId: tc.id }))
      );
      
      this.stateMachine.dispatch(AgentEvent.SEQUENCE_READY, {
        executionSequence: optimizedSequence,
      });
      
      // LOOP DETECTION
      const repeatCountMap = new Map<string, number>();
      const shouldNudge: string[] = [];
      const thisIterationCalls = new Map<string, string>();

      for (const toolCall of streamingToolCalls) {
        const normalizedArgs = { ...toolCall.arguments };
        if (toolCall.name === 'list_directory' && !('recursive' in normalizedArgs)) normalizedArgs.recursive = false;
        
        const argsSignature = JSON.stringify(normalizedArgs);
        const normalizedToolName = toolCall.name.toLowerCase().replace(/[_-]/g, '');
        const callKey = `${normalizedToolName}:${argsSignature}`;
        
        if (thisIterationCalls.has(callKey)) {
          this.log(`DUPLICATE: Skipping ${toolCall.name} (same args in same iteration)`);
          continue;
        }
        thisIterationCalls.set(callKey, argsSignature);
        
        // Check state machine history for loop detection
        if (this.stateMachine.detectLoop(toolCall.name, normalizedArgs, iteration)) {
          this.log(`LOOP DETECTED: ${toolCall.name} with same arguments`);
          this.stateMachine.dispatch(AgentEvent.LOOP_DETECTED);
          if (options.streaming) {
            yield { type: 'text', text: `⚠️ Loop detected. Stopping.`, timestamp: Date.now() };
            yield { type: 'done', outcome: 'error', timestamp: Date.now(), iterations: iteration };
          }
          return;
        }
        
        const recentCalls = history.filter(h => h.iteration >= iteration - 2 && h.toolName.toLowerCase().replace(/[_-]/g, '') === normalizedToolName && h.argsSignature === argsSignature);
        const totalRepeatCount = (repeatCountMap.get(callKey) || 0) + recentCalls.length;
        repeatCountMap.set(callKey, totalRepeatCount);

        if (recentCalls.length > 0) {
          if (totalRepeatCount === 2) shouldNudge.push(toolCall.name);
          else if (totalRepeatCount >= 4) {
            if (options.streaming) {
              yield { type: 'text', text: `⚠️ Stopped after ${totalRepeatCount} repeated ${toolCall.name} calls.`, timestamp: Date.now() };
              yield { type: 'done', outcome: 'error', timestamp: Date.now(), iterations: iteration };
            }
            return;
          }
        }
        
        history.push({ toolName: toolCall.name, argsSignature, iteration });
      }

      if (shouldNudge.length > 0) {
        messages.push({ role: 'user', content: `NOTICE: You called ${[...new Set(shouldNudge)].join(', ')} with same arguments. Try a DIFFERENT approach.` });
      }

      // STATE: EXECUTE - Execute tools
      this.stateMachine.dispatch(AgentEvent.SEQUENCE_READY);
      const currentIterationToolCalls: ToolCall[] = [];

      for (const toolCall of streamingToolCalls) {
        const startTime = Date.now();
        
        if (options.streaming) {
          yield { type: 'tool_call_started', toolName: toolCall.name, args: toolCall.arguments, timestamp: Date.now() };
          
          // Emit state change for monitoring
          yield { 
            type: 'state_change', 
            from: this.stateMachine.state, 
            to: AgentState.EXECUTE, 
            event: AgentEvent.TOOL_CALLS_RECEIVED,
            timestamp: Date.now() 
          };
        }

        const toolCallObj: ToolCall = { toolName: toolCall.name, args: toolCall.arguments, toolCallId: toolCall.id };

        try {
          const result = await this.executeTool({ toolName: toolCall.name, args: toolCall.arguments });
          toolCallObj.result = result.result;
          if (result.error) toolCallObj.error = result.error;
          
          const durationMs = Date.now() - startTime;
          
          // Record in state machine for loop detection and metrics
          this.stateMachine.recordToolCall(toolCall.name, toolCall.arguments, result.result, result.error, durationMs);
          
          if (options.streaming) {
            yield { type: 'tool_call_completed', toolName: toolCall.name, result: result.result, timestamp: Date.now() };
          }
        } catch (error: any) {
          toolCallObj.error = error.message;
          this.stateMachine.recordToolCall(toolCall.name, toolCall.arguments, undefined, error.message);
          if (options.streaming) {
            yield { type: 'tool_call_completed', toolName: toolCall.name, result: `Error: ${error.message}`, timestamp: Date.now() };
          }
        }

        currentIterationToolCalls.push(toolCallObj);
        toolCalls.push(toolCallObj);
      }

      let assistantContent = responseText;
      if (!assistantContent || assistantContent.trim() === '') {
        assistantContent = `I will: ${currentIterationToolCalls.map(tc => `Calling ${tc.toolName}`).join('; ')}`;
      }
      
      messages.push({ role: 'assistant', content: assistantContent || '' });

      for (let i = 0; i < currentIterationToolCalls.length; i++) {
        const tc = currentIterationToolCalls[i];
        const toolCallId = streamingToolCalls[i]?.id || tc.toolCallId || `call_${iteration}_${i}`;
        messages.push({ role: 'tool', content: tc.error || tc.result || 'No result', tool_call_id: toolCallId });
      }

      if (this._autoNudge) {
        messages.push({ role: 'user', content: this._autoNudge });
        this._autoNudge = null;
      }

      messages.push({ role: 'user', content: 'Tool results received. If you have enough information, answer now. Only call another tool if missing critical info.' });

      // STATE: VERIFY - Transition and check results
      this.stateMachine.dispatch(AgentEvent.TOOLS_EXECUTED);
      
      if (options.streaming) {
        yield { type: 'iteration_complete', iteration, timestamp: Date.now() };
      }
    }
  }

  private injectContextIntoPrompt(prompt: string, context?: VslfcContext): string {
    if (!context) return prompt;
    return prompt;
  }

  private async callLLM(messages: LLMMessage[], tools: LLMTool[]): Promise<LLMResponse> {
    const result = await this.cli.callLLM(this.config.model.id, messages, { temperature: this.config.model.temperature, top_p: this.config.model.topP, max_tokens: this.config.model.maxOutputTokens }, tools, false);
    if (Symbol.asyncIterator in result) throw new Error('Expected non-streaming response but got streaming generator');
    return result as LLMResponse;
  }

  private async executeTool(toolCall: ToolCall): Promise<{ result: string; error?: string }> {
    try {
      switch (toolCall.toolName) {
        case 'list_directory': {
          const dirPath = this.resolvePath(toolCall.args.path);
          const recursive = toolCall.args.recursive === true;
          try {
            const files = await this.cli.listFiles(dirPath, recursive);
            if (files.length === 0) {
              try { fs.accessSync(dirPath); return { result: 'This directory is empty.' }; }
              catch { return { result: `DIRECTORY_NOT_FOUND: '${toolCall.args.path}' does not exist.` }; }
            }
            return { result: files.join('\n') };
          } catch (error: any) {
            if (error.code === 'ENOENT') return { result: `DIRECTORY_NOT_FOUND: '${toolCall.args.path}' does not exist.` };
            throw error;
          }
        }
        
        case 'read_file': {
          const filePath = this.resolvePath(toolCall.args.path);
          const normalizedPath = filePath.toLowerCase();
          if (this._autoReadFiles.has(normalizedPath)) return { result: '[Already auto-read. Focus on proposing fixes with apply_edits or write_file.]' };
          try {
            const result = await this.cli.readFile(filePath);
            if (!result || result.trim() === '') return { result: 'The file exists but is empty.' };
            return { result };
          } catch (error: any) {
            if (error.code === 'ENOENT') return { result: 'FILE_NOT_FOUND' };
            throw error;
          }
        }
        
        case 'write_file': {
          const filePath = this.resolvePath(toolCall.args.path);
          const content = toolCall.args.content;
          
          // MODERN EDIT PIPELINE: Snapshot before edit
          if (!this._fileSnapshots.has(filePath)) {
            try {
              const original = await this.cli.readFile(filePath);
              this._fileSnapshots.set(filePath, original);
              this.log(`$(save) Snapshot saved: ${filePath} (${original.length} chars)`);
            } catch {
              // New file - no snapshot needed
              this.log(`$(file) New file: ${filePath}`);
            }
          }
          
          await this.cli.writeFile(filePath, content);
          this._failedEditAttempts = 0;
          this._consecutiveSuccessfulEdits++;
          
          if (this._pendingFixes && this._pendingFixes.length > 0) {
            this._autoNudge = `Wrote ${filePath}. ${this._pendingFixes.length} fix(es) remaining. Next: ${this._pendingFixes[0]}`;
          } else {
            this._fixMode = false;
            this._autoNudge = `Fix applied to ${filePath}. Re-run build: .\\gradlew :app:server:compileKotlin`;
          }
          return { result: `Successfully wrote ${content.length} characters to ${filePath}` };
        }
        
        case 'apply_edits': {
          const filePath = this.resolvePath(toolCall.args.path);
          const edits: EditOperation[] = toolCall.args.edits;
          
          if (edits.length > AgentBridge.MAX_APPLY_EDITS) {
            return { result: '', error: `Too many edits (${edits.length}). Maximum ${AgentBridge.MAX_APPLY_EDITS} edits per call. For large changes, use write_file to replace the entire file instead.` };
          }
          
          // MODERN EDIT PIPELINE: Snapshot before edit
          if (!this._fileSnapshots.has(filePath)) {
            try {
              const original = await this.cli.readFile(filePath);
              this._fileSnapshots.set(filePath, original);
              this.log(`📸 Snapshot saved: ${filePath} (${original.length} chars)`);
            } catch {
              this.log(`📄 Reading file for edits: ${filePath}`);
            }
          }
          
          const currentContent = await this.cli.readFile(filePath);
          const editResult = applyEditsToContent(currentContent, edits);
          
          if (editResult.appliedCount === 0) {
            this._failedEditAttempts++;
            this._consecutiveSuccessfulEdits = 0;
            const failureMessages = editResult.failures.map(f => formatEditFailure(f, filePath));
            
            if (this._failedEditAttempts >= 3) {
              this.log(`Failed 3 times on ${filePath} - moving to next file`);
              if (this._pendingFixes && this._pendingFixes.length > 0) this._pendingFixes.shift();
              this._failedEditAttempts = 0;
              if (this._pendingFixes && this._pendingFixes.length > 0) {
                this._autoNudge = `Failed 3 attempts on ${filePath}. Next: ${this._pendingFixes[0]}`;
              } else {
                this._fixMode = false;
                this._autoNudge = `Failed 3 attempts on ${filePath}. Re-run build to check other errors.`;
              }
            } else {
              this._autoNudge = `Edit failed (${this._failedEditAttempts}/3). Read file for EXACT text, then retry apply_edits.`;
            }
            return { result: `❌ No edits applied\n\n${failureMessages.join('\n\n')}`, error: 'All edits failed' };
          }
          
          this._failedEditAttempts = 0;
          this._consecutiveSuccessfulEdits++;
          await this.cli.writeFile(filePath, editResult.finalContent);
          
          let resultMessage = `✅ Applied ${editResult.appliedCount}/${editResult.totalCount} edits to ${filePath}`;
          if (editResult.failures.length > 0) resultMessage += `\n⚠️ ${editResult.failures.length} edit(s) failed`;
          
          if (this._pendingFixes && this._pendingFixes.length > 0) {
            this._autoNudge = `Edited ${filePath}. ${this._pendingFixes.length} fix(es) remaining. Next: ${this._pendingFixes[0]}`;
          } else {
            this._fixMode = false;
            this._autoNudge = `Fix applied to ${filePath}. Re-run build: .\\gradlew :app:server:compileKotlin`;
          }
          return { result: resultMessage };
        }
        
        case 'search_files': {
          const pattern = toolCall.args.pattern;
          const searchPath = toolCall.args.path ? this.resolvePath(toolCall.args.path) : undefined;
          
          // Check for search loop - same pattern searched multiple times
          if (this._lastSearchPattern === pattern && this._lastSearchFiles.length > 0) {
            this.log(`SEARCH LOOP: Pattern "${pattern}" already searched. Found ${this._lastSearchFiles.length} files: ${this._lastSearchFiles.slice(0, 3).join(', ')}...`);
            this.stateMachine.dispatch(AgentEvent.SEARCH_LOOP);
            return {
              result: `⚠️ You already searched for "${pattern}" and found ${this._lastSearchFiles.length} files. Instead of searching again, READ one of these files: ${this._lastSearchFiles.slice(0, 3).join(', ')}`,
              error: 'SEARCH_LOOP_DETECTED'
            };
          }
          
          const results = await this.cli.searchFiles(pattern, searchPath);
          
          // Track this search for loop detection
          this._lastSearchPattern = pattern;
          this._lastSearchFiles = results;
          
          if (results.length === 0) {
            return { result: `No files found matching pattern "${pattern}". Try a different search term or use list_directory to explore.`, error: 'NO_RESULTS' };
          }
          
          // If many results found, suggest reading instead of more searching
          if (results.length > 10) {
            return { result: `Found ${results.length} files matching "${pattern}". Here are the first 10:\n${results.slice(0, 10).join('\n')}\n\nTIP: You found many results. Instead of searching more, READ one of these files to understand the code.`, error: 'MANY_RESULTS' };
          }
          
          return { result: `Found ${results.length} file(s):\n${results.join('\n')}\n\nTIP: You found the files! Now READ one of them instead of searching more.` };
        }
        
        case 'get_file_context': {
          const filePath = this.resolvePath(toolCall.args.path);
          try {
            const stat = fs.statSync(filePath);
            if (stat.isDirectory()) return { result: '', error: 'PATH_IS_DIRECTORY: Use list_directory for folders.' };
          } catch (e: any) {}
          const context = await this.cli.getContext(filePath);
          return { result: JSON.stringify(context, null, 2) };
        }
        
        case 'git_status': {
          const result = await this.cli.runCommand('git status --porcelain');
          return { result: result.stdout.trim() || 'Working tree clean.' };
        }
        
        case 'git_diff': {
          const target = toolCall.args.target || 'unstaged';
          const filePath = toolCall.args.path || '';
          const flag = target === 'staged' ? '--staged' : '';
          const result = await this.cli.runCommand(`git diff ${flag} ${filePath}`);
          const diffOutput = result.stdout || 'No differences.';
          
          // If there are actual changes, format as diff card HTML
          if (diffOutput !== 'No differences.' && diffOutput.trim()) {
            // Return diff output with marker for webview to detect
            return { 
              result: `DIFF_CARD_START\n${diffOutput}\nDIFF_CARD_END`,
              error: undefined
            };
          }
          return { result: diffOutput };
        }
        
        case 'git_log': {
          const count = Math.min(toolCall.args.count || 10, 50);
          const result = await this.cli.runCommand(`git log --oneline -${count}`);
          return { result: result.stdout || 'No commits.' };
        }
        
        case 'git_branch': {
          const action = toolCall.args.action || 'current';
          if (action === 'current') {
            const result = await this.cli.runCommand('git branch --show-current');
            return { result: result.stdout.trim() || 'Not in a git repository' };
          } else {
            const result = await this.cli.runCommand('git branch');
            return { result: result.stdout || 'No branches found' };
          }
        }
        
        case 'git_commit': {
          const message = toolCall.args.message;
          const files = toolCall.args.files || ['.'];
          for (const f of files) await this.cli.runCommand(`git add "${f}"`);
          const safeMessage = message.replace(/"/g, '\\"');
          const result = await this.cli.runCommand(`git commit -m "${safeMessage}"`);
          return { result: result.stdout || result.stderr || 'Committed successfully.' };
        }
        
        case 'run_build': {
          let command = toolCall.args.command;
          const timeout = 120000;
          
          // AUTO-INJECT BUILD CONTEXT: First build/run attempt - read build file
          const workspaceKey = this.getWorkspaceKey();
          if (!this._hasReadBuildFiles.get(workspaceKey) && (this._buildFileReadAttempts.get(workspaceKey) || 0) < 2) {
            const attempts = (this._buildFileReadAttempts.get(workspaceKey) || 0) + 1;
            this._buildFileReadAttempts.set(workspaceKey, attempts);
            
            const buildFile = await this.findBuildFile();
            if (buildFile) {
              try {
                const content = await this.cli.readFile(buildFile);
                const extractedInfo = this.extractBuildTasks(buildFile, content);
                const contextMessage = `[AUTO-CONTEXT] Build configuration from ${buildFile}:\n${extractedInfo}`;
                
                // Inject context into messages for next LLM call
                (this as any)._pendingMessages = (this as any)._pendingMessages || [];
                (this as any)._pendingMessages.push({ 
                  role: 'tool', 
                  content: contextMessage,
                  tool_call_id: `auto_context_${Date.now()}`
                });
                
                this._hasReadBuildFiles.set(workspaceKey, true);
                this.log(`Auto-injected build context from ${buildFile}`);
              } catch (e: any) {
                this.log(`Failed to read build file: ${e.message}`);
              }
            }
          }
          
          if (process.platform === 'win32' && /^\.\//i.test(command)) {
            command = command.replace(/^\.\//, '.\\');
            this.log(`  Windows PowerShell fix: ./ -> .\\`);
          }
          
          const settings = this.settingsManager.getSettings();
          const showInWebview = settings.terminal.showOutputInWebview;
          
          const result = await this.runCommandWithTimeout(command, timeout, undefined, (output: string) => {
            if (showInWebview) {
              this.emitProgress({ type: 'tool_output', toolCall: { toolName: 'run_build', args: toolCall.args }, partialOutput: output.slice(-200), iteration: this.currentIteration });
            }
          });
          
          const output = (result.stdout || '') + '\n' + (result.stderr || '');
          const exitCodeInfo = result.exitCode !== null ? ` (exit: ${result.exitCode})` : '';
          const hasFailure = result.exitCode !== 0 || output.includes('BUILD FAILED') || output.includes('FAILED') || output.includes('error:');
          
          if (!hasFailure) {
            this._buildFailureCount = 0;
            this._autoReadFiles.clear();
            this._fixMode = false;
            this._consecutiveSuccessfulEdits = 0;
            (this as any)._pendingMessages = (this as any)._pendingMessages || [];
            (this as any)._pendingMessages.push({ role: 'user', content: '✅ BUILD SUCCESSFUL. The compilation completed without errors. Your task is complete. Provide a final summary and do NOT call any more tools.' });
            this.log('Build success - injected completion signal');
            return { result: `✅ BUILD SUCCESSFUL${exitCodeInfo}\n\nCompilation passed.\n\n${output.slice(-500)}` };
          }
          
          const errors = this.extractCompilationErrors(output);
          this._lastBuildErrors = errors;
          this._buildFailureCount++;
          this._consecutiveSuccessfulEdits = 0;
          this._failedEditAttempts = 0;
          
          // IMPORTANT: Do NOT set _fixMode = true here. The LLM needs all tools
          // (including run_build and run_terminal) to diagnose and fix the issue.
          // _fixMode should only be set by the plan-only detection logic.
          
          const fileMatch = errors.match(/FILES TO READ AND FIX:\s*\n([\s\S]*?)(?:\n\n|$)/);
          if (fileMatch) {
            const files = fileMatch[1].split('\n').map(f => f.replace(/^\s*-\s*/, '').trim()).filter(f => f.length > 0);
            const cleanPaths = new Set<string>();
            for (const file of files) cleanPaths.add(file.replace(/^(e:\/\/\/|file:\/\/\/)/, ''));
            this._pendingFixes = Array.from(cleanPaths).slice(0, 5);
            this.log(`Auto-fix: Queued ${this._pendingFixes.length} files`);
          }
          
          return { result: `❌ BUILD FAILED (failure #${this._buildFailureCount})\n\nExit code: ${result.exitCode}\n\n${errors}\n\n⚠️ DO NOT re-run build. READ files above, FIX errors, THEN re-run.`, error: 'Build failed' };
        }
        
        case 'run_terminal': {
          let command = toolCall.args.command;
          const workingDir = toolCall.args.workingDir ? this.resolvePath(toolCall.args.workingDir) : this.workspaceRoot;
          
          // AUTO-INJECT BUILD CONTEXT: First build/run attempt - read build file
          const workspaceKey = this.getWorkspaceKey();
          if (!this._hasReadBuildFiles.get(workspaceKey) && (this._buildFileReadAttempts.get(workspaceKey) || 0) < 2) {
            const attempts = (this._buildFileReadAttempts.get(workspaceKey) || 0) + 1;
            this._buildFileReadAttempts.set(workspaceKey, attempts);
            
            const buildFile = await this.findBuildFile();
            if (buildFile) {
              try {
                const content = await this.cli.readFile(buildFile);
                const extractedInfo = this.extractBuildTasks(buildFile, content);
                const contextMessage = `[AUTO-CONTEXT] Build configuration from ${buildFile}:\n${extractedInfo}`;
                
                // Inject context into messages for next LLM call
                (this as any)._pendingMessages = (this as any)._pendingMessages || [];
                (this as any)._pendingMessages.push({ 
                  role: 'tool', 
                  content: contextMessage,
                  tool_call_id: `auto_context_${Date.now()}`
                });
                
                this._hasReadBuildFiles.set(workspaceKey, true);
                this.log(`Auto-injected build context from ${buildFile}`);
              } catch (e: any) {
                this.log(`Failed to read build file: ${e.message}`);
              }
            }
          }
          
          if (/^:/.test(command)) {
            const gradleWrapper = process.platform === 'win32' ? '.\\gradlew' : './gradlew';
            command = `${gradleWrapper} ${command}`;
          }
          
          if (process.platform === 'win32') {
            if (/^gradlew(\s|$)/i.test(command)) command = command.replace(/^gradlew/i, '.\\gradlew');
            if (/^\.\//i.test(command)) command = command.replace(/^\.\//, '.\\');
            
            // FIRST: Handle cd "path" && command pattern - extract command, ignore cd (we use workingDir)
            const cdMatch = command.match(/^cd\s+["']?([^"']+)["']?\s*&&\s*(.+)$/i);
            if (cdMatch) {
              command = cdMatch[2]; // Just use the actual command part
            }
            
            // THEN: Fix any remaining bash && to PowerShell ;
            if (command.includes(' && ')) command = command.replace(/ && /g, '; ');
          }
          
          if (this.isDestructiveCommand(command)) return { result: '', error: 'BLOCKED: Dangerous command.' };
          
          if (this.isBuildCommand(command)) {
            const timeout = 120000;
            const result = await this.runCommandWithTimeout(command, timeout, workingDir);
            const output = (result.stdout || '') + '\n' + (result.stderr || '');
            
            if (result.exitCode !== 0 || output.includes('BUILD FAILED') || output.includes('FAILED')) {
              const errors = this.extractCompilationErrors(output);
              this._lastBuildErrors = errors;
              this._buildFailureCount++;
              this._consecutiveSuccessfulEdits = 0;
              this._failedEditAttempts = 0;
              const fileMatch = errors.match(/FILES TO READ AND FIX:\s*\n([\s\S]*?)(?:\n\n|$)/);
              if (fileMatch) {
                const files = fileMatch[1].split('\n').map(f => f.replace(/^\s*-\s*/, '').trim()).filter(f => f.length > 0);
                const cleanPaths = new Set<string>();
                for (const file of files) cleanPaths.add(file.replace(/^(e:\/\/\/|file:\/\/\/)/, ''));
                this._pendingFixes = Array.from(cleanPaths).slice(0, 5);
              }
              return { result: `❌ BUILD FAILED (failure #${this._buildFailureCount})\n\n${errors}\n\n⚠️ DO NOT re-run build. FIX errors first.`, error: 'Build failed' };
            }
            
            this._buildFailureCount = 0;
            this._autoReadFiles.clear();
            this._fixMode = false;
            this._consecutiveSuccessfulEdits = 0;
            return { result: `✅ Build successful\n\n${output.slice(-1000)}` };
          }
          
          const classification = this.classifyCommand(command);
          if (classification === 'long') {
            const terminalName = this.generateTerminalName(command);
            const result = await this.terminalManager.runInTerminal(terminalName, command, workingDir, true);
            
            // Server commands capture output to detect build failures.
            // If the build failed, return the errors to the LLM so it can fix them.
            // IMPORTANT: Do NOT set _fixMode here - the LLM needs all tools (including
            // run_build and run_terminal) to diagnose and fix the issue.
            this._serverJustStarted = terminalName;
            
            if (result.includes('❌') || result.includes('BUILD FAILED') || result.includes('FAILED')) {
              this.log(`Server build failed - returning errors to LLM without activating fixMode`);
              return { result };
            }
            
            (this as any)._pendingMessages = (this as any)._pendingMessages || [];
            (this as any)._pendingMessages.push({ 
              role: 'user', 
              content: `Server started in terminal "${terminalName}". It's running in background with auto-restart. You can check status later with terminal_status.` 
            });
            return { result };
          } else {
            const terminalName = `i2-Vision: ${this.generateTerminalName(command).substring(0, 20)}`;
            const terminal = vscode.window.createTerminal({ name: terminalName, cwd: workingDir, shellPath: process.platform === 'win32' ? 'powershell.exe' : undefined });
            terminal.show(true);
            terminal.sendText(command);
            await new Promise(resolve => setTimeout(resolve, 3000));
            setTimeout(() => terminal.dispose(), 5000);
            return { result: `Command executed in terminal: ${command}` };
          }
        }
        
        case 'kill_terminal': return { result: this.terminalManager.killTerminal(toolCall.args.name) };
        
        case 'list_terminals': return { result: this.terminalManager.listTerminals() };
        
        case 'terminal_status': {
          const name = toolCall.args.name;
          const status = this.terminalManager.getTerminalStatus(name);
          
          if (!status) {
            return { result: `Terminal "${name}" is not running.` };
          }
          
          // Terminal is running - provide age info to help agent understand startup progress
          const ageInfo = status.ageSeconds ? ` (started ${status.ageSeconds}s ago)` : '';
          const startupNote = status.ageSeconds && status.ageSeconds < 30 
            ? ` Server is still starting up - this is normal for Gradle servers.` 
            : '';
          
          return { 
            result: `Terminal "${name}" is running${ageInfo}. Auto-restart: ${status.autoRestart ? 'enabled' : 'disabled'}.${startupNote}` 
          };
        }
        
        // MODERN EDIT PIPELINE: Revert tools
        case 'revert_file': {
          const filePath = this.resolvePath(toolCall.args.path);
          const original = this._fileSnapshots.get(filePath);
          
          if (!original) {
            return { result: '', error: `No snapshot available for ${toolCall.args.path}. It may not have been edited in this session.` };
          }
          
          await this.cli.writeFile(filePath, original);
          this._fileSnapshots.delete(filePath);
          this.log(`🔄 Reverted: ${filePath}`);
          
          return { result: `Reverted ${toolCall.args.path} to original state (${original.length} chars).` };
        }
        
        case 'revert_all': {
          let count = 0;
          for (const [filePath, original] of this._fileSnapshots) {
            await this.cli.writeFile(filePath, original);
            count++;
          }
          const paths = Array.from(this._fileSnapshots.keys());
          this._fileSnapshots.clear();
          this.log(`🔄 Reverted all: ${count} files`);
          
          return { result: count > 0 
            ? `Reverted ${count} file(s) to original state:\n${paths.map(p => `  - ${p}`).join('\n')}`
            : 'No files were modified in this session.' };
        }
        
        case 'list_snapshots': {
          if (this._fileSnapshots.size === 0) {
            return { result: 'No files have been modified in this session.' };
          }
          const files = Array.from(this._fileSnapshots.keys());
          return { result: `Modified files (${files.length}):\n${files.map(f => `  - ${f}`).join('\n')}` };
        }
        
        default: throw new Error(`Unknown tool: ${toolCall.toolName}`);
      }
    } catch (error: any) {
      this.log(`  Tool error: ${error.message}`);
      return { result: '', error: error.message };
    }
  }

  private async runCommandWithTimeout(command: string, timeoutMs: number, workingDir?: string, onOutput?: (output: string) => void): Promise<{ stdout: string; stderr: string; exitCode: number | null }> {
    const { spawn } = require('child_process');
    return new Promise((resolve) => {
      const proc = spawn(command, { shell: true, cwd: workingDir || this.workspaceRoot, timeout: timeoutMs });
      let stdout = '', stderr = '', exitCode: number | null = null;
      const timeout = setTimeout(() => { proc.kill(); stderr += '\n\n[TIMEOUT]'; resolve({ stdout, stderr, exitCode: null }); }, timeoutMs);
      proc.stdout?.on('data', (data: Buffer) => { stdout += data.toString(); if (onOutput) onOutput(stdout); if (stdout.length > 10000) { stdout = stdout.slice(0, 10000) + '\n... (truncated)'; proc.kill(); } });
      proc.stderr?.on('data', (data: Buffer) => { stderr += data.toString(); if (onOutput) onOutput(stderr); });
      proc.on('close', (code: number | null) => { clearTimeout(timeout); exitCode = code; resolve({ stdout, stderr, exitCode }); });
      proc.on('error', (err: Error) => { clearTimeout(timeout); resolve({ stdout, stderr: err.message, exitCode: -1 }); });
    });
  }

  private formatBuildResult(result: { stdout: string; stderr: string; exitCode: number | null }): string {
    const hasError = result.exitCode !== 0 || result.stderr?.includes('FAILED') || result.stderr?.includes('BUILD FAILED') || result.stdout?.includes('FAILED');
    let resultMessage = result.exitCode !== null ? `Exit code: ${result.exitCode}\n` : '';
    if (hasError) {
      resultMessage += `\n❌ Build FAILED\n\n${this.extractCompilationErrors(result.stderr || result.stdout)}\n\n⚠️ DO NOT re-run build. FIX errors first.`;
    } else {
      resultMessage += `\n✅ Build successful\n`;
      if (result.stdout?.trim() || result.stderr?.trim()) resultMessage += `\n--- Output ---\n${(result.stdout || result.stderr || '').slice(-2000)}`;
      else resultMessage += `\n⚠️ No output (likely cached)\n`;
    }
    return resultMessage;
  }

  private formatGitStatus(output: string): string {
    if (!output.trim()) return 'No changes.';
    const lines = output.trim().split('\n');
    const staged = lines.filter(l => /^[MADRC]/.test(l.charAt(0)));
    const unstaged = lines.filter(l => /^.[MADRC]/.test(l));
    const untracked = lines.filter(l => l.startsWith('??'));
    let result = '';
    if (staged.length) result += `**Staged:**\n${staged.map(l => '  ' + l).join('\n')}\n\n`;
    if (unstaged.length) result += `**Unstaged:**\n${unstaged.map(l => '  ' + l).join('\n')}\n\n`;
    if (untracked.length) result += `**Untracked:**\n${untracked.map(l => '  ' + l).join('\n')}`;
    return result || output;
  }

  private extractCompilationErrors(output: string): string {
    if (!output) return 'No error output';
    const errors: string[] = [];
    const mentionedFiles = new Set<string>();
    const kotlinErrorPattern = /e:\s*file:\/\/\/?([a-zA-Z]:[\\/].+?):(\d+):(\d+)\s+(.+)/g;
    let match;
    while ((match = kotlinErrorPattern.exec(output)) !== null) {
      const [, filePath, lineNum, col, message] = match;
      const normalizedPath = filePath.replace(/\\/g, '/');
      mentionedFiles.add(normalizedPath);
      errors.push(`${normalizedPath}:${lineNum}:${col} ${message}`);
    }
    const unresolvedErrors = output.match(/Unresolved reference[^\n]+/g);
    if (unresolvedErrors) unresolvedErrors.forEach(err => errors.push(err.trim()));
    const keywordPatterns = [/Type mismatch[^\n]+/g, /is not abstract[^\n]+/g, /must implement[^\n]+/g, /cannot find symbol[^\n]+/g, /Overload resolution[^\n]+/g, /Conflicting overloads[^\n]+/g];
    for (const pattern of keywordPatterns) {
      const matches = output.match(pattern);
      if (matches) matches.forEach(err => errors.push(err.trim()));
    }
    const lines = output.split('\n');
    for (let i = 0; i < lines.length; i++) {
      const line = lines[i];
      if (line.includes('FAILED') && !line.includes('BUILD FAILED')) {
        const contextLines = [];
        for (let j = i; j < Math.min(i + 4, lines.length); j++) {
          const contextLine = lines[j].trim();
          if (contextLine && !contextLine.startsWith('> Task') && contextLine.length > 10) contextLines.push(contextLine);
        }
        if (contextLines.length > 0) errors.push(contextLines.slice(0, 3).join(' '));
      }
    }
    let result = '';
    if (mentionedFiles.size > 0) result += `FILES TO READ AND FIX:\n  - ${Array.from(mentionedFiles).slice(0, 5).join('\n  - ')}\n\n`;
    if (errors.length > 0) result += `COMPILER ERRORS:\n${[...new Set(errors)].slice(0, 15).join('\n')}`;
    else {
      const eLines = lines.filter(l => l.trim().startsWith('e: '));
      if (eLines.length > 0) result += `COMPILER ERRORS:\n${eLines.slice(0, 15).join('\n')}`;
      else result = `Build output (last 1500 chars):\n${output.slice(-1500)}`;
    }
    return result;
  }

  private isDestructiveCommand(command: string): boolean {
    return AgentBridge.BLOCKED_COMMAND_PATTERNS.some(b => command.includes(b));
  }

  private isBuildCommand(command: string): boolean {
    if (/\b(run|serve|server|start|watch)\b/i.test(command)) return false;
    return /gradlew|gradle|mvn|mvnw|npm run build|make|tsc|yarn build/i.test(command);
  }

  private classifyCommand(command: string): 'short' | 'long' {
    const cmdLower = command.toLowerCase();
    for (const pattern of this.longRunningPatterns) if (cmdLower.includes(pattern.toLowerCase())) return 'long';
    if (/\b(watch|dev|server|serve)\b/i.test(command)) return 'long';
    return 'short';
  }

  private generateTerminalName(command: string): string { return this.terminalManager.generateTerminalName(command); }
  private resolvePath(relativePath: string): string { return path.isAbsolute(relativePath) ? relativePath : path.join(this.workspaceRoot, relativePath); }
  
  /** Get workspace key for per-workspace tracking */
  private getWorkspaceKey(): string {
    return this.workspaceRoot || 'default';
  }
  
  /** Find build file in workspace (root first, then common subdirs) */
  private async findBuildFile(): Promise<string | null> {
    const candidates = [
      'build.gradle.kts',
      'build.gradle',
      'pom.xml',
      'package.json',
      'Makefile',
      'CMakeLists.txt',
      // Common subdirectories
      'backend/build.gradle.kts',
      'server/build.gradle.kts',
      'app/build.gradle.kts',
    ];
    
    for (const candidate of candidates) {
      const candidatePath = this.resolvePath(candidate);
      try {
        await fs.promises.access(candidatePath);
        return candidate;
      } catch {
        // File doesn't exist, try next
      }
    }
    return null;
  }
  
  /** Extract relevant task information from build file */
  private extractBuildTasks(buildFile: string, content: string): string {
    const fileExt = path.extname(buildFile).toLowerCase();
    const extractedInfo: string[] = [];
    
    // For Gradle Kotlin DSL
    if (fileExt === '.kts' || fileExt === '.gradle') {
      // EXTRACT MAIN CLASS (highest priority - tells agent what to search for)
      const mainClassMatch = content.match(/mainClass\.set\(["']([^"']+)["']\)/);
      if (mainClassMatch) {
        extractedInfo.push(`🎯 MAIN CLASS: ${mainClassMatch[1]}`);
        extractedInfo.push(`   → This is the server entry point. Read this file to understand the startup.`);
      }
      
      // Extract application block info
      const applicationMatch = content.match(/application\s*\{[\s\S]*?mainClass[\s\S]*?\}/);
      if (applicationMatch && !mainClassMatch) {
        const mainInBlock = applicationMatch[0].match(/mainClass\s*=\s*["']([^"']+)["']/);
        if (mainInBlock) {
          extractedInfo.push(`🎯 MAIN CLASS: ${mainInBlock[1]}`);
          extractedInfo.push(`   → This is the server entry point.`);
        }
      }
      
      // Extract available tasks
      const taskMatches = content.match(/task\s*\(['"`]?(\w+)['"`]?\)/g);
      if (taskMatches && taskMatches.length > 0) {
        const tasks = taskMatches.map(m => {
          const match = m.match(/task\s*\(['"`]?(\w+)['"`]?\)/);
          return match ? match[1] : '';
        }).filter(t => t);
        extractedInfo.push(`Available Gradle tasks: ${tasks.join(', ')}`);
      }
      
      // Look for run tasks in subprojects
      const runTaskMatch = content.match(/:([\w:-]+):run/);
      if (runTaskMatch) {
        extractedInfo.push(`Server run task: gradlew :${runTaskMatch[1]}:run`);
      }
    }
    
    // For Maven
    if (fileExt === '.xml') {
      const pluginMatch = content.match(/<artifactId>(maven-[\w-]+|spring-boot-maven-plugin)<\/artifactId>/);
      if (pluginMatch) {
        extractedInfo.push(`Build system: Maven with ${pluginMatch[1]}`);
      }
      
      // Extract main class from Maven
      const mainClassMatch = content.match(/<mainClass>([^<]+)<\/mainClass>/);
      if (mainClassMatch) {
        extractedInfo.push(`🎯 MAIN CLASS: ${mainClassMatch[1]}`);
        extractedInfo.push(`   → This is the server entry point.`);
      }
    }
    
    // For npm
    if (fileExt === '.json') {
      try {
        const pkg = JSON.parse(content);
        if (pkg.scripts) {
          const scripts = Object.keys(pkg.scripts);
          extractedInfo.push(`Available npm scripts: ${scripts.join(', ')}`);
        }
        if (pkg.main) {
          extractedInfo.push(`🎯 MAIN ENTRY: ${pkg.main}`);
        }
      } catch {
        // Invalid JSON, will return raw content
      }
    }
    
    // Return extracted info, or fallback to raw content
    if (extractedInfo.length > 0) {
      return extractedInfo.join('\n');
    }
    
    // Default: return first 2000 chars
    return content.substring(0, 2000);
  }

  private resolveImportPath(importPath: string, currentFile: string): string | null {
    const match = importPath.match(/['"](.+?)['"]/);
    if (!match) return null;
    const importSpecifier = match[1];
    if (!importSpecifier.startsWith('.') && !importSpecifier.startsWith('/')) return null;
    const currentDir = path.dirname(currentFile);
    const resolvedPath = path.resolve(currentDir, importSpecifier);
    const extensions = ['', '.ts', '.tsx', '.js', '.jsx', '.kt', '.java'];
    for (const ext of extensions) {
      const candidate = resolvedPath + ext;
      try { fs.accessSync(candidate); return candidate; } catch {  }
    }
    return null;
  }

  dispose(): void { this.terminalManager.dispose(); }

  private buildSystemPrompt(variables: Record<string, string>): string {
    let prompt = this.config.systemPromptTemplate;
    for (const [key, value] of Object.entries(variables)) prompt = prompt.replace(new RegExp(`\\$\\{${key}\\}`, 'g'), value);
    prompt += '\n\n--- RULES ---';
    prompt += '\n• ALWAYS use tool calls. Never describe plans.';
    prompt += '\n• FOR "run backend" or "run server": use run_terminal with gradlew :app:server:run (NOT run_build)';
    prompt += '\n• FOR compilation: use run_build with compileKotlin (source code ONLY, NO tests). NEVER use "build" - it runs ALL tests.';
    prompt += '\n• apply_edits: MAX 50 edits per call. For large changes, use write_file instead.';
    prompt += '\n• When build fails: READ failing files, FIX code, THEN re-run compileKotlin.';
    prompt += '\n• NEVER re-run build without fixing first.';
    prompt += '\n• SERVER STARTUP WORKFLOW:';
    prompt += '\n  1. Start server with run_terminal';
    prompt += '\n  2. WAIT 20-30 seconds (Gradle servers take time!)';
    prompt += '\n  3. Check terminal_status';
    prompt += '\n  4. If terminal shows "not running" or BUILD FAILED: run .\\gradlew :app:server:compileKotlin to see errors';
    prompt += '\n  5. Fix errors with apply_edits, then retry';
    prompt += '\n• SEARCH TIP: If search_files finds files, READ them immediately. Do NOT search again with different patterns.';
    prompt += '\n• FOCUS: Fix source files (src/main), NOT test files (src/test), unless user specifically asks about tests.';
    prompt += '\n• Paths: relative to workspace root, use forward slashes (/).';
    return prompt;
  }
}
