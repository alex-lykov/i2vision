/**
 * AgentBridge - Bridge between VSCode extension and agent core
 * 
 * Wraps agent core functionality and provides a clean API for AgentTabManager
 * to interact with configured agents. Implements modern agentic loop with
 * reflection - LLM sees tool results and decides if more tools are needed.
 * 
 * CONTEXT MANAGEMENT: Supports configurable context profiles (eager/lazy loading)
 * defined in YAML config. Task-aware context loading optimizes performance.
 */

import * as vscode from 'vscode';
import * as fs from 'fs';
import * as path from 'path';
import { CLI, LLMResponse, LLMTool, LLMMessage, LLMToolCall, LLMChunk } from '../cliIntegration';
import { TerminalManager } from './TerminalManager';

/**
 * Context profile - defines what context to load eagerly vs lazily
 */
export interface ContextProfile {
  // What to load eagerly (before agent starts)
  eager: {
    currentFile?: boolean;
    projectMetadata?: boolean;
    gitStatus?: boolean;
    gitDiff?: boolean;
    relatedFiles?: boolean;
    directoryStructure?: boolean;
  };
  // What the agent can request via tools (lazy)
  lazy: {
    discovery?: boolean;
    fullContext?: boolean;
    contractValidation?: boolean;
  };
}

/**
 * Task-specific context overrides
 */
export interface TaskContextProfile {
  eager?: Partial<ContextProfile['eager']>;
  lazy?: Partial<ContextProfile['lazy']>;
}

/**
 * Loaded context data passed to agent
 */
export interface VslfcContext {
  layer: string;
  currentFile?: {
    path: string;
    symbols: string;
    relatedFiles?: string[];
  };
  project?: {
    moduleCount: number;
    architecturePattern?: string;
    directoryStructure?: string;
  };
  git?: {
    status?: string;
    diff?: string;
  };
}

/**
 * Agent configuration interface (matches YAML structure)
 * Extended with context management settings
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
    // Long-running command patterns for terminal classification
    longRunningPatterns?: string[];
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

  // ===== CONTEXT MANAGEMENT (NEW) =====
  context?: {
    default: ContextProfile;
    tasks?: Record<string, TaskContextProfile>;
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
 * Agent chunk types for streaming responses
 */
export type AgentChunk = 
  | { type: 'thinking'; message: string; timestamp: number }
  | { type: 'tool_call_started'; toolName: string; args: Record<string, any>; timestamp: number }
  | { type: 'tool_call_completed'; toolName: string; result: string; timestamp: number }
  | { type: 'text'; text: string; timestamp: number }
  | { type: 'done'; outcome: 'success' | 'error'; timestamp: number; iterations?: number; durationMs?: number; tokenUsage?: { prompt: number; completion: number; total: number } }
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
  private terminalManager: TerminalManager;
  private isInitialized: boolean = false;
  private outputChannel?: vscode.OutputChannel;
  private workspaceRoot: string;
  private extensionRoot: string;
  private currentIteration: number = 1;
  private progressCallback?: ProgressCallback;

  // Truncation settings
  private static readonly MAX_TOOL_RESULT_LENGTH = 2000; // characters
  private static readonly MAX_LIST_FILES_RESULTS = 100; // max files to return

  // Long-running command patterns - these trigger persistent terminal mode
  // Configurable via YAML: execution.longRunningPatterns
  private longRunningPatterns: string[] = [
    'run',
    'serve',
    'dev',
    'start',
    'watch',
    'nodemon',
    'vite',
    'next dev',
    'spring-boot:run',
    'jetty:run',
    'webpack --watch',
    'tsc --watch',
    'gulp watch',
    'grunt watch',
    'cargo run',
    'go run',
    'python -m uvicorn',
    'poetry run'
  ];

  // Blocked commands - never execute (security)
  private static readonly BLOCKED_COMMAND_PATTERNS = [
    'rm -rf /',
    'del /F /S /Q C:\\*',
    'format',
    'mkfs',
    'dd if=/dev/zero'
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
    
    // Initialize TerminalManager for persistent terminals
    // Use configurable debounce from YAML (default 1000ms for Gradle projects)
    const debounceMs = 1000; // Default 1 second for Gradle projects
    this.terminalManager = new TerminalManager(outputChannel, debounceMs);
    
    // Load custom long-running patterns from YAML config if provided
    const customPatterns = (config as any).execution?.longRunningPatterns;
    if (customPatterns && Array.isArray(customPatterns) && customPatterns.length > 0) {
      // Replace default patterns with configured ones (not append)
      this.longRunningPatterns = customPatterns;
      this.log(`Loaded ${customPatterns.length} long-running patterns from YAML config`);
    } else {
      this.log(`Using ${this.longRunningPatterns.length} default long-running patterns`);
    }
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
   * Get the agent configuration
   */
  getConfig(): AgentConfig {
    return { ...this.config };
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
   * Detect task type from user input for context profile selection
   */
  private detectTaskType(userInput: string): string {
    const input = userInput.toLowerCase();
    
    // Refactor tasks
    if (/\b(refactor|rename|extract|move|restructure|reorganize)\b/i.test(input)) {
      return 'refactor';
    }
    
    // Debug tasks
    if (/\b(debug|fix|bug|error|crash|fail|exception|issue|problem|broken)\b/i.test(input)) {
      return 'debug';
    }
    
    // Explore/explain tasks
    if (/\b(explain|what|how|explore|find|show|describe|understand|overview)\b/i.test(input)) {
      return 'explore';
    }
    
    // Write/create tasks
    if (/\b(write|create|add|implement|build|generate|new)\b/i.test(input)) {
      return 'create';
    }
    
    // Test tasks
    if (/\b(test|spec|unit|integration|coverage)\b/i.test(input)) {
      return 'test';
    }
    
    return 'default';
  }

  /**
   * Load eager context based on profile
   */
  private async loadEagerContext(
    profile: ContextProfile,
    currentFile?: string
  ): Promise<VslfcContext> {
    const context: VslfcContext = {
      layer: this.config.agentType
    };

    // Load current file context
    if (profile.eager.currentFile && currentFile) {
      try {
        const fileContext = await this.cli.getContext(currentFile);
        if (fileContext) {
          context.currentFile = {
            path: currentFile,
            symbols: JSON.stringify({
              name: fileContext.name,
              language: fileContext.language,
              classes: fileContext.classes,
              functions: fileContext.functions,
              imports: fileContext.imports,
              component: fileContext.component,
              layer: fileContext.layer
            }, null, 2),
            relatedFiles: []
          };

          // Load related files if requested (using imports as related files)
          if (profile.eager.relatedFiles && fileContext.imports && fileContext.imports.length > 0) {
            const relatedContext: string[] = [];
            // Limit to first 5 imports to avoid excessive loading
            for (const importPath of fileContext.imports.slice(0, 5)) {
              try {
                // Try to resolve import path to actual file
                const resolvedPath = this.resolveImportPath(importPath, currentFile);
                if (resolvedPath) {
                  const relatedSymbols = await this.cli.getContext(resolvedPath);
                  if (relatedSymbols) {
                    relatedContext.push(`${resolvedPath}:\n${JSON.stringify({
                      name: relatedSymbols.name,
                      classes: relatedSymbols.classes,
                      functions: relatedSymbols.functions
                    }, null, 2)}`);
                  }
                }
              } catch (e: any) {
                this.log(`Warning: Could not load related file from import ${importPath}: ${e.message}`);
              }
            }
            context.currentFile.relatedFiles = relatedContext;
          }
        }
      } catch (e: any) {
        this.log(`Warning: Could not load file context for ${currentFile}: ${e.message}`);
      }
    }

    // Load project metadata
    if (profile.eager.projectMetadata) {
      try {
        const modules = await this.cli.listDirectories(this.workspaceRoot);
        context.project = {
          moduleCount: modules.length,
          architecturePattern: modules.length > 1 ? 'multi-module' : 'single-module',
          directoryStructure: modules.slice(0, 20).join('\n') // Limit to 20 modules
        };
      } catch (e: any) {
        this.log(`Warning: Could not load project metadata: ${e.message}`);
      }
    }

    // Load directory structure
    if (profile.eager.directoryStructure) {
      try {
        const files = await this.cli.listFiles(this.workspaceRoot, false);
        if (context.project) {
          context.project.directoryStructure = files.slice(0, 50).join('\n'); // Limit to 50 items
        } else {
          context.project = {
            moduleCount: 0,
            directoryStructure: files.slice(0, 50).join('\n')
          };
        }
      } catch (e: any) {
        this.log(`Warning: Could not load directory structure: ${e.message}`);
      }
    }

    // Load git status
    if (profile.eager.gitStatus) {
      try {
        const gitResult = await this.cli.runCommand('git status --porcelain');
        if (gitResult.stdout.trim()) {
          context.git = {
            status: this.formatGitStatus(gitResult.stdout)
          };
        }
      } catch (e: any) {
        this.log(`Warning: Could not load git status: ${e.message}`);
      }
    }

    // Load git diff
    if (profile.eager.gitDiff) {
      try {
        const diffResult = await this.cli.runCommand('git diff HEAD');
        if (diffResult.stdout.trim()) {
          if (!context.git) context.git = {};
          context.git.diff = diffResult.stdout.slice(0, 5000); // Limit diff size
        }
      } catch (e: any) {
        this.log(`Warning: Could not load git diff: ${e.message}`);
      }
    }

    return context;
  }

  /**
   * Merge context profiles (default + task-specific)
   */
  private mergeContextProfiles(
    defaultProfile: ContextProfile,
    taskProfile?: TaskContextProfile
  ): ContextProfile {
    const merged: ContextProfile = {
      eager: { ...defaultProfile.eager },
      lazy: { ...defaultProfile.lazy }
    };

    if (taskProfile) {
      if (taskProfile.eager) {
        Object.assign(merged.eager, taskProfile.eager);
      }
      if (taskProfile.lazy) {
        Object.assign(merged.lazy, taskProfile.lazy);
      }
    }

    return merged;
  }

  /**
   * Get available tools (filtered by lazy context profile)
   */
  private getTools(lazyProfile?: ContextProfile['lazy']): LLMTool[] {
    const allTools: LLMTool[] = [
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
          description: 'Run a short-lived terminal command and return stdout/stderr. Max 30 seconds. For long-running servers, the command automatically runs in a persistent terminal with auto-restart on file changes.',
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
      },
      
      // ===== TERMINAL MANAGEMENT (Manual control) =====
      {
        type: 'function',
        function: {
          name: 'kill_terminal',
          description: 'Stop a running managed terminal by name. Use this to stop servers or watchers started by run_terminal.',
          parameters: {
            type: 'object',
            properties: {
              name: { 
                type: 'string', 
                description: 'Terminal name (e.g., "backend", "frontend")' 
              }
            },
            required: ['name']
          }
        }
      },
      {
        type: 'function',
        function: {
          name: 'list_terminals',
          description: 'List all managed terminals and their status.',
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
          name: 'terminal_status',
          description: 'Check if a specific terminal is running and get its status. Use this when the user asks "is X running?" or "what state is X in?".',
          parameters: {
            type: 'object',
            properties: {
              name: { 
                type: 'string', 
                description: 'Terminal name to check (e.g., "backend", "frontend")' 
              }
            },
            required: ['name']
          }
        }
      }
    ];

    // Filter tools based on lazy profile (if provided)
    // Note: Currently all core tools are always enabled
    // i2vision_* tools would be filtered here if they existed in the tool list
    if (lazyProfile) {
      const disabledTools: string[] = [];
      
      // Track which optional tools are disabled for logging
      if (!lazyProfile.discovery) {
        disabledTools.push('discovery');
      }
      if (!lazyProfile.fullContext) {
        disabledTools.push('fullContext');
      }
      if (!lazyProfile.contractValidation) {
        disabledTools.push('contractValidation');
      }
      
      if (disabledTools.length > 0) {
        this.log(`Lazy profile: ${disabledTools.length} optional tool categories disabled`);
      }
    }

    return allTools;
  }

  /**
   * Extract the actual response by removing reasoning section and tool calls
   * This is the PRIMARY place where reasoning is stripped - NOT in AgentTabManager
   */
  private extractFinalResponse(text: string): string {
    if (!text) return '';
    
    // STEP 1: Remove reasoning: headers
    text = text.replace(/reasoning:\s*/gi, '');
    
    // STEP 2: Remove EOS markers
    text = text.replace(/\bEOS\b/gi, '');
    
    // STEP 3: Remove tool_call: JSON blocks
    text = text.replace(/tool_call:\s*\{[\s\S]*?\}(?=\n|$|tool_call:)/g, '');
    
    // STEP 4: Remove tool_calls: prefix
    text = text.replace(/^tool_calls:\s*/gmi, '');
    
    // STEP 5: Split on double newlines — take the LONGEST block as the real answer
    // The last block might be a short footnote; the longest block has the substantial content
    // Lower threshold to 20 chars to avoid filtering out short valid responses
    const blocks = text.split(/\n\n+/).filter(b => b.trim().length > 20);
    
    if (blocks.length > 1) {
      // Return the longest block — the real answer, not a trailing footnote
      return blocks.reduce((a, b) => a.length > b.length ? a : b).trim();
    }
    
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
    currentFile?: string,
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
        currentFile: currentFile || this.config.templateVariables.currentFile || '',
        task: userInput
      };

      // Build the system prompt
      const systemPrompt = this.buildSystemPrompt(templateVars);
      
      this.log(`System prompt built (${systemPrompt.length} chars)`);

      // Execute agent loop and collect chunks
      const chunks: AgentChunk[] = [];
      const toolCallArgs = new Map<string, Record<string, any>>();
      
      for await (const chunk of this.executeAgentLoop(userInput, systemPrompt, { streaming: true, onProgress, toolCallArgs })) {
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
    currentFile?: string
  ): AsyncGenerator<AgentChunk> {
    if (!this.isInitialized) {
      await this.initialize();
    }

    this.log(`Starting streaming process: "${userInput.substring(0, 50)}..."`);

    try {
      // Prepare template variables
      const templateVars = {
        ...this.config.templateVariables,
        currentFile: currentFile || this.config.templateVariables.currentFile || '',
        task: userInput
      };

      // Build the system prompt
      const systemPrompt = this.buildSystemPrompt(templateVars);
      
      this.log(`System prompt built (${systemPrompt.length} chars)`);

      // Execute unified agent loop with streaming enabled
      for await (const chunk of this.executeAgentLoop(userInput, systemPrompt, { streaming: true })) {
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
   * 
   * CONTEXT MANAGEMENT: Loads eager context and filters tools based on YAML profile
   */
  async *executeAgentLoop(
    userInput: string,
    systemPrompt: string,
    options: AgentLoopOptions = { streaming: false }
  ): AsyncGenerator<AgentChunk> {
    // ===== CONTEXT MANAGEMENT =====
    const taskType = this.detectTaskType(userInput);
    this.log(`Task type detected: "${taskType}"`);

    // Get context profile (default + task-specific merge)
    let contextProfile: ContextProfile | undefined;
    if (this.config.context) {
      const defaultProfile = this.config.context.default;
      const taskProfile = this.config.context.tasks?.[taskType];
      contextProfile = this.mergeContextProfiles(defaultProfile, taskProfile);
      this.log(`Context profile loaded: eager=${Object.keys(contextProfile.eager).length}, lazy=${Object.keys(contextProfile.lazy).length}`);
    }

    // Load eager context
    let loadedContext: VslfcContext | undefined;
    if (contextProfile) {
      loadedContext = await this.loadEagerContext(contextProfile, this.config.templateVariables.currentFile);
      this.log(`Eager context loaded: currentFile=${!!loadedContext.currentFile}, project=${!!loadedContext.project}, git=${!!loadedContext.git}`);
    }
    // ===== END CONTEXT MANAGEMENT =====

    // Inject context into system prompt
    const contextEnhancedPrompt = this.injectContextIntoPrompt(systemPrompt, loadedContext);

    const messages: LLMMessage[] = [
      { role: 'system', content: contextEnhancedPrompt },
      { role: 'user', content: userInput }
    ];

    // Get tools (filtered by lazy profile)
    const tools = this.getTools(contextProfile?.lazy);
    const maxIterations = this.config.iterationSettings.maxIterations;
    const toolCalls: ToolCall[] = [];
    const history: ToolCallHistory[] = [];

    this.log(`Starting agent loop (streaming=${options.streaming}) with max ${maxIterations} iterations, ${tools.length} tools`);

    for (let iteration = 1; iteration <= maxIterations; iteration++) {
      this.currentIteration = iteration;
      this.log(`[Iter ${iteration}/${maxIterations}] Calling LLM...`);

      // Emit thinking event (streaming only)
      if (options.streaming) {
        yield {
          type: 'thinking',
          message: `Iteration ${iteration}: Calling LLM...`,
          timestamp: Date.now()
        };
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
          if (chunk.tokenUsage) {
            // Store token usage for final 'done' chunk
            (this as any)._lastTokenUsage = chunk.tokenUsage;
          }
          if (chunk.done) {
            break;
          }
        }

        // FALLBACK: Parse tool calls from text if structured tool calls not provided
        // CRITICAL: This must happen BEFORE extractFinalResponse strips the tool_call: headers
        if (streamingToolCalls.length === 0 && responseText.includes('tool_call:')) {
          this.log(`No structured tool calls - parsing from text`);
          // More robust pattern that handles malformed JSON
          const toolCallPattern = /tool_call:\s*({[\s\S]*?})(?=\n|$|tool_call:)/g;
          let match;
          let parseIndex = 0;
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
                // Generate consistent tool_call_id for linking results
                const toolCallId = `call_${iteration}_${parseIndex}`;
                streamingToolCalls.push({
                  id: toolCallId,
                  name: toolCallObj.tool,
                  arguments: toolCallObj.args || {}
                });
                this.log(`Parsed tool call: ${toolCallObj.tool} (id: ${toolCallId})`);
                parseIndex++;
              }
            } catch (e: any) {
              this.log(`Warning: Could not parse tool call JSON: ${match[1].substring(0, 100)}... Error: ${e.message}`);
            }
          }
        }

        // Extract final response (remove reasoning, tool calls, EOS)
        // Only clean the accumulated responseText, NOT individual chunks
        // Individual chunks are too small for meaningful cleaning
        // IMPORTANT: This happens AFTER tool call parsing to avoid stripping headers prematurely
        responseText = this.extractFinalResponse(responseText);
        textBuffer = [responseText];  // Use cleaned text for display
        // Don't clean textBuffer chunks - yield them raw for streaming
        // The webview accumulates them, and final cleaning happens on responseText

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
        
        // Simple plan detection - catches most common patterns
        // System prompt instructs LLM to avoid these, this is just a safety net
        const isPlanOnly = trimmedResponse.length < 200 && (
          /^(I will|I'll|Let me|First,? I)/i.test(trimmedResponse) ||
          trimmedResponse.toLowerCase().includes('calling ')
        );

        if (isPlanOnly) {
          this.log(`[Iter ${iteration}] Plan detected - discarding: "${trimmedResponse.substring(0, 80)}..."`);
          messages.push({
            role: 'user',
            content: 'That is just a plan. You MUST call tools to complete the task. Do NOT respond with another plan - actually call the tools now.'
          });
          continue;
        }

        // Final answer - yield/send text
        this.log(`[Iter ${iteration}] Final answer received (${trimmedResponse.length} chars)`);
        
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
            iterations: iteration,
            durationMs: Date.now() - (messages[0] as any)._startTime || 0,
            tokenUsage: (this as any)._lastTokenUsage
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

      // Has tool calls - DO NOT yield text yet (it's just reasoning, not the final answer)
      // The final answer will come in a later iteration after tools complete
      // Just log that we're executing tools
      this.log(`[Iter ${iteration}] Executing ${streamingToolCalls.length} tool(s)...`);

      // ===== LOOP DETECTION (SHARED LOGIC) =====
      const repeatCountMap = new Map<string, number>();
      const shouldNudge: string[] = [];
      const thisIterationCalls = new Map<string, string>(); // Track tool calls within this iteration

      for (const toolCall of streamingToolCalls) {
        // Normalize arguments to ensure consistent comparison
        // This prevents false negatives when optional params are missing vs explicitly set to default
        const normalizedArgs = { ...toolCall.arguments };
        
        // Set defaults for missing optional params
        if (toolCall.name === 'list_directory' && !('recursive' in normalizedArgs)) {
          normalizedArgs.recursive = false;
        }
        
        const argsSignature = JSON.stringify(normalizedArgs);
        const normalizedToolName = toolCall.name.toLowerCase().replace(/[_-]/g, '');
        const callKey = `${normalizedToolName}:${argsSignature}`;
        
        // ===== DUPLICATE DETECTION: Same tool called multiple times in one response =====
        if (thisIterationCalls.has(callKey)) {
          this.log(`DUPLICATE: Skipping ${toolCall.name} with same args in same iteration (already called)`);
          continue; // Skip this duplicate tool call
        }
        thisIterationCalls.set(callKey, argsSignature);
        // ===== END DUPLICATE DETECTION =====
        
        // Check if this exact tool call was made in the last 2 iterations
        const recentCalls = history.filter(h => 
          h.iteration >= iteration - 2 && 
          h.toolName.toLowerCase().replace(/[_-]/g, '') === normalizedToolName &&
          h.argsSignature === argsSignature
        );
        
        // Calculate repeat count
        const previousRepeatCount = repeatCountMap.get(callKey) || 0;
        const totalRepeatCount = previousRepeatCount + recentCalls.length;
        repeatCountMap.set(callKey, totalRepeatCount);

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
        
        // Record BEFORE execution with normalized args
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
      // This helps the LLM associate results with their original calls
      for (let i = 0; i < currentIterationToolCalls.length; i++) {
        const tc = currentIterationToolCalls[i];
        const matchingToolCall = streamingToolCalls[i];
        
        // Ensure tool_call_id is set for proper linking
        const toolCallId = matchingToolCall?.id || tc.toolCallId || `call_${iteration}_${i}`;
        
        messages.push({
          role: 'tool',
          content: tc.error || tc.result || 'No result',
          tool_call_id: toolCallId
        });
        
        this.log(`[Iter ${iteration}] Linked tool result to ${toolCallId}`);
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
   * Inject loaded context into system prompt
   */
  private injectContextIntoPrompt(prompt: string, context?: VslfcContext): string {
    if (!context) return prompt;

    let contextSection = '\n\n--- LOADED CONTEXT (EAGER) ---\n';
    
    if (context.currentFile) {
      contextSection += `\n[CURRENT FILE: ${context.currentFile.path}]\n`;
      contextSection += `Symbols:\n${context.currentFile.symbols}\n`;
      if (context.currentFile.relatedFiles?.length) {
        contextSection += `\nRelated files:\n${context.currentFile.relatedFiles.join('\n\n')}\n`;
      }
    }
    
    if (context.project) {
      contextSection += `\n[PROJECT METADATA]\n`;
      contextSection += `Architecture: ${context.project.architecturePattern || 'unknown'}\n`;
      contextSection += `Modules: ${context.project.moduleCount}\n`;
      if (context.project.directoryStructure) {
        contextSection += `\nDirectory structure:\n${context.project.directoryStructure}\n`;
      }
    }
    
    if (context.git?.status) {
      contextSection += `\n[GIT STATUS]\n${context.git.status}\n`;
    }
    
    if (context.git?.diff) {
      contextSection += `\n[GIT DIFF]\n${context.git.diff}\n`;
    }
    
    contextSection += '\n--- END LOADED CONTEXT ---\n';
    
    return prompt + contextSection;
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
            : this.workspaceRoot;
          
          this.log(`  Running terminal: ${command}`);
          
          // SAFETY CHECK: Block only truly destructive commands
          if (this.isDestructiveCommand(command)) {
            return { 
              result: '', 
              error: `BLOCKED: This command is dangerous and cannot be executed.`
            };
          }
          
          // UNIFIED APPROACH: Classify command and route appropriately
          const classification = this.classifyCommand(command);
          
          if (classification === 'long') {
            // Long-running server: Use persistent terminal with auto-restart
            const terminalName = this.generateTerminalName(command);
            const result = this.terminalManager.runInTerminal(
              terminalName,
              command,
              workingDir,
              true // Enable auto-restart on file changes
            );
            this.log(`  Started long-running server in terminal "${terminalName}"`);
            return { result };
          } else {
            // Short-lived command: Run with timeout and return output
            const timeout = 30000; // 30 seconds
            const result = await this.runCommandWithTimeout(command, timeout, workingDir);
            
            const output = result.stdout || result.stderr || 'Command completed with no output.';
            const exitCodeInfo = result.exitCode !== null ? ` (exit: ${result.exitCode})` : '';
            
            return { result: `${output}${exitCodeInfo}` };
          }
        }
        
        // ===== TERMINAL MANAGEMENT TOOLS =====
        case 'kill_terminal': {
          const name = toolCall.args.name;
          this.log(`  Killing terminal: ${name}`);
          const result = this.terminalManager.killTerminal(name);
          return { result };
        }
        
        case 'list_terminals': {
          this.log(`  Listing terminals`);
          const result = this.terminalManager.listTerminals();
          return { result };
        }
        
        case 'terminal_status': {
          const name = toolCall.args.name;
          this.log(`  Checking terminal status: ${name}`);
          
          const status = this.terminalManager.getTerminalStatus(name);
          
          if (!status) {
            return { result: `Terminal "${name}" is not running.` };
          }
          
          return { 
            result: `Terminal "${name}" is running. Command: ${status.command}. Auto-restart: ${status.autoRestart ? 'enabled' : 'disabled'}. Restart count: ${status.restartCount}.` 
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
   * Check if command is destructive/dangerous (should be blocked)
   * Only blocks truly dangerous commands, not server/start commands
   */
  private isDestructiveCommand(command: string): boolean {
    const blocked = AgentBridge.BLOCKED_COMMAND_PATTERNS;
    return blocked.some(b => command.includes(b));
  }

  /**
   * Classify command as short-lived or long-running
   * Long-running commands use persistent terminals with auto-restart
   */
  private classifyCommand(command: string): 'short' | 'long' {
    const cmdLower = command.toLowerCase();
    
    // Check against configured patterns
    for (const pattern of this.longRunningPatterns) {
      if (cmdLower.includes(pattern.toLowerCase())) {
        return 'long';
      }
    }
    
    // Additional heuristic: commands with watch/dev/run typically long-running
    if (/\b(watch|dev|server|serve)\b/i.test(command)) {
      return 'long';
    }
    
    return 'short';
  }

  /**
   * Generate unique terminal name from command
   */
  private generateTerminalName(command: string): string {
    return this.terminalManager.generateTerminalName(command);
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
   * Resolve import path to actual file path
   * Handles relative imports and module resolution
   */
  private resolveImportPath(importPath: string, currentFile: string): string | null {
    // Remove quotes and extract path from import statement
    const match = importPath.match(/['"](.+?)['"]/);
    if (!match) return null;
    
    const importSpecifier = match[1];
    
    // Skip external modules (no relative path)
    if (!importSpecifier.startsWith('.') && !importSpecifier.startsWith('/')) {
      return null; // External module, not a local file
    }
    
    // Resolve relative to current file's directory
    const currentDir = path.dirname(currentFile);
    const resolvedPath = path.resolve(currentDir, importSpecifier);
    
    // Try common extensions
    const extensions = ['', '.ts', '.tsx', '.js', '.jsx', '.kt', '.java'];
    for (const ext of extensions) {
      const candidate = resolvedPath + ext;
      try {
        fs.accessSync(candidate);
        return candidate;
      } catch {
        continue;
      }
    }
    
    // Try index files
    for (const ext of extensions) {
      const candidate = path.join(resolvedPath, 'index' + ext);
      try {
        fs.accessSync(candidate);
        return candidate;
      } catch {
        continue;
      }
    }
    
    return null; // Could not resolve
  }

  /**
   * Dispose resources - clean up terminal manager
   */
  dispose(): void {
    this.log('Disposing AgentBridge...');
    this.terminalManager.dispose();
    this.log('AgentBridge disposed');
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
    prompt += '\n\n--- CRITICAL RULE ---';
    prompt += '\nNever describe what you will do. Either call a tool immediately or provide the final answer.';
    prompt += '\nNever output "I will call..." or "Let me..." — just act.';
    prompt += '\n\n--- AVAILABLE TOOLS (KNOW YOUR CAPABILITIES) ---';
    prompt += '\nYou have access to these tools. When asked "what tools do you have?" or "what can you do?", list them:';
    prompt += '\n• list_directory — List files in a directory';
    prompt += '\n• read_file — Read contents of a file';
    prompt += '\n• write_file — Write content to a file';
    prompt += '\n• search_files — Search for files matching a pattern';
    prompt += '\n• get_file_context — Get context for a file (classes, functions, imports)';
    prompt += '\n• git_status — Show working tree status (modified, staged, untracked)';
    prompt += '\n• git_diff — Show changes between commits or working tree';
    prompt += '\n• git_log — Show commit history';
    prompt += '\n• git_branch — List branches or show current branch';
    prompt += '\n• git_commit — Stage and commit changes';
    prompt += '\n• run_build — Run build commands (Gradle, npm, Maven)';
    prompt += '\n• run_terminal — Run shell commands (short commands return output, servers run in persistent terminals)';
    prompt += '\n• kill_terminal — Stop a running terminal by name';
    prompt += '\n• list_terminals — List all managed terminals';
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
    prompt += '\n\n--- TERMINAL MANAGEMENT ---';
    prompt += '\nThe run_terminal tool handles both short commands and long-running servers:';
    prompt += '\n• Short commands (git, ls, npm test): Run immediately, return output in 30s';
    prompt += '\n• Long-running servers (npm run dev, gradlew run): Start in persistent terminal with auto-restart on file changes';
    prompt += '\n• Use kill_terminal to stop a running server';
    prompt += '\n• Use list_terminals to see all running terminals';
    prompt += '\n\n--- BLOCKED COMMANDS ---';
    prompt += '\nThese dangerous commands are BLOCKED and will be rejected:';
    prompt += '\n- rm -rf /, del /F /S /Q C:\\*, format, mkfs, dd if=/dev/zero';
    prompt += '\nAll other commands are allowed, including server start commands.';
    prompt += '\n\n--- SERVER COMMANDS ---';
    prompt += '\nTo start development servers, use run_terminal with the full command:';
    prompt += '\n- ./gradlew :app:server:run';
    prompt += '\n- ./gradlew run';
    prompt += '\n- npm run dev, npm start';
    prompt += '\n- yarn dev, yarn start';
    prompt += '\nThe system automatically detects long-running servers and runs them in persistent terminals with auto-restart on file changes.';
    prompt += '\nYou do NOT need to report the command to the user - just call run_terminal and the system handles it.';
    prompt += '\n\n--- PATH HANDLING ---';
    prompt += '\n- Always use forward slashes (/) for paths';
    prompt += '\n- Paths are relative to workspace root';
    
    return prompt;
  }
}
