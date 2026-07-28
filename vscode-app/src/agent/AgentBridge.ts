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
import {AgentEvent, AgentState, AgentStateMachine, StateContext,} from './AgentStateMachine';
import {
  buildTools,
  CustomToolPluginLoader,
  DomainDetector,
  DomainResolution,
  editTools,
  fileTools,
  gitTools,
  ModuleDomain,
  terminalTools,
  ToolConfigLoader,
  ToolContext,
  ToolRegistry,
  VslfcLayer
} from './tools';
import {AgentSessionState, ChatMessage} from './ConversationHistoryManager';
import {createSessionManager, SessionManager} from './SessionManager';
import {ToolResultCompressor} from './ToolResultCompressor';

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
  systemPromptRules?: { rules: string[] };
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
  | { type: 'reasoning'; reasoning: string; timestamp: number }
  | { type: 'tool_call_started'; toolName: string; args: Record<string, any>; timestamp: number }
  | { type: 'tool_call_completed'; toolName: string; result: string; timestamp: number }
  | { type: 'text'; text: string; timestamp: number }
  | { type: 'done'; outcome: 'success' | 'error'; timestamp: number; iterations?: number; durationMs?: number; tokenUsage?: { prompt: number; completion: number; total: number } }
  | { type: 'iteration_complete'; iteration: number; timestamp: number }
  | { type: 'token_usage'; tokenUsage: { prompt: number; completion: number; total: number }; timestamp: number }
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
  
  // Tool Registry - declarative tool management
  private toolRegistry: ToolRegistry = new ToolRegistry();
  
  // Tool Configuration Loader - YAML-based config
  private toolConfigLoader?: ToolConfigLoader;
  
  // Custom Tool Plugin Loader - JavaScript plugin system
  private pluginLoader?: CustomToolPluginLoader;
  
  // Current VSLFC layer for tool filtering
  private currentLayer?: VslfcLayer;
  
  // STATE MACHINE: Single source of truth for all agent state
  private stateMachine: AgentStateMachine = new AgentStateMachine();
  
  // Auto-read tracking (not part of state machine - it's per-session cache)
  private _autoReadFiles: Set<string> = new Set();
  private _readFileCount: Map<string, number> = new Map();
  private static readonly MAX_READS_PER_FILE = 3; // Force action after N reads of same file
  
  // Auto-nudge message (generated during tool execution, consumed in next iteration)
  private _autoNudge: string | null = null;
  
  // Token usage tracking for context meter
  private _lastTokenUsage?: { prompt: number; completion: number; total: number };
  
  // Persisted actual LLM prompt token count and message metrics for accurate
  // pre-call estimation. We track the character/message count at the time
  // the baseline was captured so we can estimate ONLY the delta (new content).
  private _lastKnownPromptTokens: number = 0;
  private _lastKnownMessageCount: number = 0;
  private _lastKnownMessageChars: number = 0;
  // Per-message overhead for role/formatting tokens
  private static readonly PER_MESSAGE_OVERHEAD = 4;
  
  // File snapshots for revert capability
  private _fileSnapshots: Map<string, string> = new Map();
  
  // File read cache to prevent redundant reads within a session
  private _fileReadCache: Map<string, { mtime: number; content: string }> = new Map();
  private static readonly FILE_CACHE_TTL_MS = 60 * 1000; // 1 minute TTL for mtime check

  // Auto-inject build context tracking (per-workspace)
  private _buildFileReadAttempts = new Map<string, number>();
  private _hasReadBuildFiles = new Map<string, boolean>();

  // Domain detector - architecture-aware domain resolution
  private domainDetector: DomainDetector = new DomainDetector();
  private _domainResolution?: DomainResolution;
  private _suggestedDirectories: string[] = [];

  // Session state - persists across agent recreation
  private _sessionState?: AgentSessionState;
  private _searchCache: Map<string, { results: any[], timestamp: number }> = new Map();
  private static readonly SEARCH_CACHE_TTL_MS = 5 * 60 * 1000; // 5 minutes

  // Provider-specific session manager (3D LLM proxy, etc.)
  private sessionManager?: SessionManager;

  // Tool result compressor for large outputs
  private toolCompressor: ToolResultCompressor = new ToolResultCompressor();

  // Auto-explore on failed search tracking
  private _failedSearchCount: number = 0;
  private _lastSearchPattern: string | null = null;
  
  // Similar search detection - track previous search patterns to detect variants
  private _previousSearches: string[] = [];
  private static readonly SIMILAR_SEARCH_THRESHOLD = 0.5; // Minimum overlap ratio to consider similar

  // Legacy state tracking (migrated to state machine context)
  // These are kept for backward compatibility during transition
  private _pendingFixes: string[] = [];
  private _buildFailureCount: number = 0;
  private _consecutivePlans: number = 0;
  private _consecutiveSuccessfulEdits: number = 0;
  private _fixMode: boolean = false;
  private _forceActionMode: boolean = false;
  private _failedEditAttempts: number = 0;
  private _lastBuildErrors: string = '';
  private _serverJustStarted: string | null = null;
  private _lastSearchFiles: string[] = [];
  private _lastSearchIteration: number = 0;
  
  // Strategy rotation for repeated failures
  private _triedStrategies: Set<string> = new Set();
  private _strategyRotationCount: number = 0;

  // DeepSeek misbehavior detection (text responses that look like tool calls)
  private _consecutiveTextResponsesWithoutToolCalls: number = 0;
  
  // Store original user input for misbehavior rebuild (don't use auto-generated tool-result messages)
  private _lastUserInput: string = '';
  
  // Pre-flight check for server start commands
  private _hasCheckedRunningServers: boolean = false;
  
  // Tool call counter to prevent over-exploration
  private _consecutiveToolCallsWithoutResponse: number = 0;
  private static readonly MAX_CONSECUTIVE_TOOL_CALLS = 8; // Force synthesis after N tool calls
  
  // Track tool call history for pattern detection
  private _toolCallHistory: Array<{ toolName: string; iteration: number }> = [];

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

  id: string;

  constructor(
    config: AgentConfig,
    outputChannel: vscode.OutputChannel,
    extensionRoot: string,
    workspaceRoot: string,
    settingsManager?: AgentSettingsManager,
    agentId?: string
  ) {
    this.id = agentId || `bridge-${Date.now()}-${Math.random().toString(16).substring(2, 6)}`;
    this.config = config;
    this.outputChannel = outputChannel;

    if (!workspaceRoot) throw new Error('workspaceRoot must be explicitly provided');
    if (extensionRoot && extensionRoot === workspaceRoot) throw new Error('workspaceRoot and extensionRoot cannot be the same path');

    this.workspaceRoot = workspaceRoot;
    this.extensionRoot = extensionRoot;
    this.settingsManager = settingsManager!;
    
    this.log(`Workspace root: ${this.workspaceRoot}`);
    this.cli = new CLI(this.workspaceRoot, outputChannel);

    // Initialize provider-specific session manager
    const provider = config.model.provider;
    const threeDLlmUrl = vscode.workspace.getConfiguration('i2vision').get<string>('3dLlmUrl') || 'http://localhost:9655';
    this.sessionManager = createSessionManager(
      provider,
      provider === '3d-llm' ? threeDLlmUrl : undefined
    );
    if (this.sessionManager && this.sessionManager.name !== 'Null') {
      this.log(`Session manager initialized: ${this.sessionManager.name}`);
    }

    const settings = this.settingsManager.getSettings();
    this.terminalManager = new TerminalManager(outputChannel, settings.terminal.autoCloseDelayMs, settings.terminal);
    
    // Initialize tool registry with all built-in tools
    this.toolRegistry.registerAll(fileTools);
    this.toolRegistry.registerAll(gitTools);
    this.toolRegistry.registerAll(terminalTools);
    this.toolRegistry.registerAll(editTools);
    this.toolRegistry.registerAll(buildTools);
    
    this.log(`Tool registry initialized with ${this.toolRegistry.getToolNames().length} tools: ${this.toolRegistry.getToolNames().join(', ')}`);
    
    // Initialize tool configuration loader (YAML-based)
    this.toolConfigLoader = new ToolConfigLoader(this.toolRegistry, this.outputChannel);
    
    // Initialize custom tool plugin loader
    this.pluginLoader = new CustomToolPluginLoader(this.workspaceRoot, this.outputChannel);
    
    // Initialize domain detector (architecture-aware)
    this.domainDetector.initialize(this.workspaceRoot).catch((e: any) => 
      this.log(`Domain detector initialization error: ${e.message}`)
    );
    
    const customPatterns = (config as any).execution?.longRunningPatterns;
    if (customPatterns && Array.isArray(customPatterns) && customPatterns.length > 0) {
      this.longRunningPatterns = customPatterns;
    }
  }

  async initialize(): Promise<void> { 
    this.isInitialized = true;
    this.stateMachine.reset();
    
    // Load YAML tool configuration
    if (this.toolConfigLoader) {
      await this.toolConfigLoader.loadConfig(this.workspaceRoot);
      this.log('Tool configuration loader initialized');
    }
    
    // Load custom tool plugins
    if (this.pluginLoader) {
      const plugins = await this.pluginLoader.loadPlugins();
      for (const plugin of plugins) {
        const toolDef = this.pluginLoader.convertToToolDefinition(plugin);
        this.toolRegistry.register(toolDef);
        this.log(`Registered custom plugin tool: ${plugin.name}`);
      }
      this.log(`Loaded ${plugins.length} custom tool plugin(s)`);
    }
    
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

  /** Update model config after provider change */
  updateConfig(updates: Partial<AgentConfig['model']>): void {
    if (updates.id !== undefined) this.config.model.id = updates.id;
    if (updates.provider !== undefined) this.config.model.provider = updates.provider;
    if (updates.contextLength !== undefined) this.config.model.contextLength = updates.contextLength;
    if (updates.maxOutputTokens !== undefined) this.config.model.maxOutputTokens = updates.maxOutputTokens;
    if (updates.temperature !== undefined) this.config.model.temperature = updates.temperature;
    if (updates.topP !== undefined) this.config.model.topP = updates.topP;

    // Hardcode context length for 3D LLM provider since the proxy doesn't expose
    // the actual model's context window over its API.
    if (this.config.model.provider === '3d-llm' && this.config.model.contextLength < 64000) {
      const oldCtx = this.config.model.contextLength;
      this.config.model.contextLength = 64000;
      this.log(`3D LLM provider detected — contextLength overridden: ${oldCtx} -> 64000`);
    }

    this.log(`Config updated: model=${this.config.model.id}, provider=${this.config.model.provider}`);
  }

  /** Replace the session manager (e.g. after provider change) */
  setSessionManager(manager: SessionManager): void {
    this.sessionManager = manager;
    this.log(`Session manager replaced: ${manager.name}`);
  }

  /** Expose the current session manager for inspection */
  getSessionManager(): SessionManager | undefined {
    return this.sessionManager;
  }

  /** Get current state machine state for debugging/monitoring */
  getState(): { state: AgentState; context: StateContext; history: Array<{ from: AgentState; to: AgentState; event: AgentEvent }> } {
    return {
      state: this.stateMachine.state,
      context: this.stateMachine.context,
      history: this.stateMachine.getLastTransitions(10),
    };
  }
  
  /**
   * Set the current VSLFC layer for tool filtering
   */
  setLayer(layer: VslfcLayer): void {
    this.currentLayer = layer;
    this.log(`Layer set to: ${layer}`);
  }
  
  /**
   * Get the current VSLFC layer
   */
  getLayer(): VslfcLayer | undefined {
    return this.currentLayer;
  }

  private log(message: string, level: 'info' | 'warn' | 'error' = 'info'): void {
    const timestamp = new Date().toISOString();
    const levelPrefix = level === 'error' ? '❌' : level === 'warn' ? '⚠️' : 'ℹ️';
    const formatted = `[${timestamp}] [AgentBridge] ${levelPrefix} ${message}`;
    if (this.outputChannel) this.outputChannel.appendLine(formatted);
    console[level](formatted);
  }

  private logStateTransition(from: AgentState, to: AgentState, event: AgentEvent, details?: string): void {
    const timestamp = new Date().toISOString();
    const stateInfo = `[${timestamp}] [StateMachine] ${from}───${event}───> ${to}`;
    const fullMessage = details ? `${stateInfo} | ${details}` : stateInfo;
    if (this.outputChannel) this.outputChannel.appendLine(fullMessage);
    console.log(fullMessage);
  }

  private logToolExecution(toolName: string, args: Record<string, any>, startTime: number): void {
    const duration = Date.now() - startTime;
    const argsSummary = Object.entries(args)
      .map(([k, v]) => `${k}=${typeof v === 'string' && v.length > 50 ? `${v.substring(0, 47)}...` : v}`)
      .join(', ');
    this.log(`🔧 Tool executed: ${toolName}(${argsSummary}) [${duration}ms]`, 'info');
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

  /**
   * Restore session state from previous conversation
   */
  restoreSessionState(sessionState: AgentSessionState | undefined): void {
    if (!sessionState) {
      this.log('No session state to restore');
      return;
    }
    
    this._sessionState = sessionState;
    this.log('Restoring session state...');
    
    // Restore visited paths
    if (sessionState.visitedPaths && sessionState.visitedPaths.length > 0) {
      this._autoReadFiles = new Set(sessionState.visitedPaths);
      this.log(`Restored ${sessionState.visitedPaths.length} visited paths`);
    }
    
    // Restore search cache
    if (sessionState.searchCache) {
      const now = Date.now();
      for (const cached of sessionState.searchCache) {
        if (now - cached.timestamp < AgentBridge.SEARCH_CACHE_TTL_MS) {
          this._searchCache.set(cached.query, { results: cached.results, timestamp: cached.timestamp });
        }
      }
      this.log(`Restored ${this._searchCache.size} search cache entries`);
    }
    
    // Restore domain resolution
    if (sessionState.resolvedDomain) {
      this._domainResolution = {
        primaryDomain: sessionState.resolvedDomain.primaryDomain as ModuleDomain,
        confidence: sessionState.resolvedDomain.confidence,
        suggestedDirectories: sessionState.resolvedDomain.suggestedDirectories,
        rationale: sessionState.resolvedDomain.rationale || '',
        relevantModules: [],
        matchingTechnologies: []
      };
      this._suggestedDirectories = sessionState.resolvedDomain.suggestedDirectories;
      this.log(`Restored domain resolution: ${sessionState.resolvedDomain.primaryDomain} (${(sessionState.resolvedDomain.confidence * 100).toFixed(0)}%)`);
    }
    
    // Restore other state
    if (sessionState.toolFilter) {
      this._forceActionMode = sessionState.toolFilter === 'action_only';
    }
    if (sessionState.forceActionMode !== undefined) {
      this._forceActionMode = sessionState.forceActionMode;
    }
    if (sessionState.failedSearchCount !== undefined) {
      this._failedSearchCount = sessionState.failedSearchCount;
    }
    if (sessionState.lastSearchPattern) {
      this._lastSearchPattern = sessionState.lastSearchPattern;
    }

    // Restore proxy session state
    if (sessionState.proxySession && this.sessionManager) {
      this.sessionManager.deserialize({ proxySession: sessionState.proxySession });
      this.log(`Restored proxy session: ${sessionState.proxySession.id || 'none'} (${sessionState.proxySession.messageCount} msgs)`);
    }

    // Restore session manager state
    if (sessionState.sessionManagerState && this.sessionManager) {
      this.sessionManager.deserialize(sessionState.sessionManagerState);
    }

    this.log('Session state restored');
  }

  /**
   * Get current session state for persistence
   */
  getSessionState(): AgentSessionState | undefined {
    if (!this._sessionState) return undefined;
    
    return {
      visitedPaths: Array.from(this._autoReadFiles),
      searchCache: Array.from(this._searchCache.entries()).map(([query, data]) => ({
        query,
        results: data.results,
        timestamp: data.timestamp,
        workspaceRoot: this.workspaceRoot
      })),
      resolvedDomain: this._domainResolution ? {
        primaryDomain: this._domainResolution.primaryDomain,
        confidence: this._domainResolution.confidence,
        suggestedDirectories: this._domainResolution.suggestedDirectories,
        rationale: this._domainResolution.rationale
      } : undefined,
      workingDirectory: this.workspaceRoot,
      toolFilter: this._forceActionMode ? 'action_only' : 'all',
      forceActionMode: this._forceActionMode,
      failedSearchCount: this._failedSearchCount,
      lastSearchPattern: this._lastSearchPattern || undefined,
      // Include token usage for context meter restoration
      lastTokenUsage: this._lastTokenUsage,
      proxySession: this.sessionManager?.getSessionState() ?? undefined,
      sessionManagerState: this.sessionManager?.serialize() ?? undefined,
    };
  }

  private getTools(lazyProfile?: ContextProfile['lazy'], toolFilter?: 'all' | 'fix_only' | 'read_only' | 'action_only'): LLMTool[] {
    // Use state machine to determine tool filter if not explicitly provided
    if (!toolFilter) {
      toolFilter = this.stateMachine.getToolFilter();
    }

    // Force action mode overrides state machine filter
    if (this._forceActionMode) {
      toolFilter = 'action_only';
    }

    // Get all tools from registry with layer filtering
    this.log(`getTools() called: layer=${this.currentLayer || 'none'}, toolFilter=${toolFilter || 'all'}`);
    const allTools = this.toolRegistry.getLLMTools(this.currentLayer);
    this.log(`Tool registry returned ${allTools.length} tools`);

    if (toolFilter === 'fix_only') {
      const fixTools = this.toolRegistry.getLLMToolsByName(['apply_edits', 'read_file', 'write_file', 'get_file_context']);
      this.log(`Tool filter: fix_only (${fixTools.length}/${allTools.length} tools)`);
      return fixTools;
    }
    
    if (toolFilter === 'read_only') {
      const readTools = this.toolRegistry.getReadOnlyTools();
      this.log(`Tool filter: read_only (${readTools.length}/${allTools.length} tools)`);
      return readTools;
    }

    if (toolFilter === 'action_only') {
      // ACTION-ONLY: prioritize edit/write tools but keep reads available.
      // Read tools are included so the model can reference files when applying edits.
      const actionTools = this.toolRegistry.getLLMToolsByName(['apply_edits', 'write_file', 'read_file', 'get_file_context', 'run_terminal', 'run_build', 'git_commit']);
      this.log(`Tool filter: action_only (${actionTools.length}/${allTools.length} tools) - forcing action mode (NO reads)`);
      return actionTools;
    }

    return allTools;
  }

  private extractReasoning(text: string): string {
    if (!text) return '';
    // Try multiple patterns to extract reasoning/thinking content
    const patterns = [
      /reasoning:\s*([\s\S]*?)(?=tool_call:|EOS|$)/i,
      /thinking:\s*([\s\S]*?)(?=tool_call:|EOS|$)/i,
      /plan:\s*([\s\S]*?)(?=tool_call:|EOS|$)/i,
      /<thinking>([\s\S]*?)<\/thinking>/i,
      /<reasoning>([\s\S]*?)<\/reasoning>/i
    ];
    for (const pattern of patterns) {
      const match = text.match(pattern);
      if (match && match[1].trim()) {
        return match[1].trim();
      }
    }
    return '';
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

  /**
   * Detect when DeepSeek is outputting text/plan format instead of tool calls.
   * Happens when proxy conversation history teaches the model the wrong format.
   */
  private detectProxyMisbehavior(text: string): boolean {
    if (!text || text.length < 10) return false;
    const lower = text.toLowerCase();
    // Score based on misbehavior indicators
    let score = 0;
    // Model writes about calling tools (but doesn't actually call them)
    if (/\b(calling|call)\s*[:\s]+\w+/.test(text)) score += 2;
    if (/\b(will|i'll|i will|let me)\s+\w+/.test(text) && /\b(read|list|search|check)\b/.test(lower)) score += 1;
    // Model outputs markdown code blocks for tools (wrong format)
    if (/```\s*(json)?\s*\n?\s*\{\s*"path"/.test(text)) score += 2;
    // Model describes what it would do
    if (/\bfirst\s*,?\s*(i'll|i will|let me)/.test(text)) score += 1;
    if (/\bnext\s*,?\s*(i'll|i will)/.test(text)) score += 1;
    // Contains tool parameter names but no actual JSON call
    const hasToolParams = /"path"|"recursive"|"command"|"pattern"/.test(text);
    const hasJsonToolCall = /"name"\s*:\s*"\w+"/.test(text) && /"arguments"\s*:/.test(text);
    if (hasToolParams && !hasJsonToolCall) score += 1;
    return score >= 2;
  }

  /** Format tool definitions for embedding in the system prompt (3D LLM text-based tool calling) */
  private formatToolsForSystemPrompt(tools: LLMTool[]): string {
    let text = '--- AVAILABLE TOOLS ---\n';
    text += 'Call tools using raw JSON on a single line: {"name":"tool_name","arguments":{"param":"value"}}\n';
    text += 'Do not use markdown code blocks or XML tags.\n\n';
    text += 'Available tools:\n';
    for (const tool of tools) {
      const fn = tool.function;
      text += `### ${fn.name}\n`;
      text += `${fn.description || 'No description'}\n`;
      if (fn.parameters) {
        const props = fn.parameters.properties || {};
        const req = fn.parameters.required || [];
        text += `Parameters:\n`;
        for (const [key, val] of Object.entries(props)) {
          const desc = (val as any).description || '';
          const required = req.includes(key) ? ' (required)' : '';
          text += `  - ${key}: ${desc}${required}\n`;
        }
      }
      text += '\n';
    }
    text += '--- END TOOLS ---';
    return text;
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

  async *processStreaming(userInput: string, currentFile?: string, history?: ChatMessage[], sessionState?: AgentSessionState): AsyncGenerator<AgentChunk> {
    if (!this.isInitialized) await this.initialize();
    
    // Restore session state if provided
    this.restoreSessionState(sessionState);
    
    try {
      const templateVars = { ...this.config.templateVariables, currentFile: currentFile || this.config.templateVariables.currentFile || '', task: userInput };
      const systemPrompt = this.buildSystemPrompt(templateVars);
      for await (const chunk of this.executeAgentLoop(userInput, systemPrompt, { streaming: true }, history)) yield chunk;
    } catch (error: any) {
      yield { type: 'error', error: error.message, timestamp: Date.now() };
    }
  }

  async *executeAgentLoop(userInput: string, systemPrompt: string, options: AgentLoopOptions = { streaming: false }, history?: ChatMessage[]): AsyncGenerator<AgentChunk> {
    // STATE MACHINE FLOW: Intent → Plan → Constraints → Sequence → Execute → Verify → Output
    
    // DOMAIN DETECTION: Only run if not already resolved (restored from session state)
    if (!this._domainResolution) {
      this._domainResolution = this.domainDetector.resolveDomain(userInput);
      this._suggestedDirectories = this._domainResolution.suggestedDirectories;
    }
    
    if (this._domainResolution.primaryDomain !== 'unknown' && this._domainResolution.confidence > 0.3) {
      this.log(`Domain resolved: ${this._domainResolution.primaryDomain} (confidence: ${(this._domainResolution.confidence * 100).toFixed(0)}%, ${this._domainResolution.rationale})`);
      this.log(`Suggested directories: ${this._suggestedDirectories.join(', ')}`);
      
      // Inject domain hint into system prompt when confidence is moderate or higher
      if (this._domainResolution.confidence >= 0.5 && this._suggestedDirectories.length > 0) {
        systemPrompt += `\n\n[DOMAIN GUIDANCE] This task is about **${this._domainResolution.primaryDomain.toUpperCase()}** code (${(this._domainResolution.confidence * 100).toFixed(0)}% confidence). You should explore these directories FIRST: ${this._suggestedDirectories.join(', ')}. DO NOT waste time exploring unrelated directories like root, other modules, or unrelated code.`;
      }
    }
    
    const taskType = this.detectTaskType(userInput);
    let contextProfile: ContextProfile | undefined;
    
    if (this.config.context) {
      const defaultProfile = this.config.context.default;
      const taskProfile = this.config.context.tasks?.[taskType];
      contextProfile = this.mergeContextProfiles(defaultProfile, taskProfile);
    }

    const loadedContext = contextProfile ? await this.loadEagerContext(contextProfile, this.config.templateVariables.currentFile) : undefined;
    let contextEnhancedPrompt = this.injectContextIntoPrompt(systemPrompt, loadedContext);

    // When using 3D LLM proxy, embed tool definitions directly in system prompt
    // because the proxy's tool injection conflicts with i2-Vision's instructions
    const tools = this.getTools(contextProfile?.lazy, this.stateMachine.getToolFilter());
    if (this.config.model.provider === '3d-llm' && tools.length > 0) {
      contextEnhancedPrompt += '\n\n' + this.formatToolsForSystemPrompt(tools);
    }

    // Build messages array with conversation history
    let messages: LLMMessage[] = [
      { role: 'system', content: contextEnhancedPrompt }
    ];
    
    // Inject conversation history if provided
    if (history && history.length > 0) {
      this.log(`Injecting ${history.length} historical messages into LLM context`);
      for (const msg of history) {
        // Skip text-only assistant messages that had no tool calls.
        // These teach the model to output prose instead of JSON tool calls.
        if (msg.role === 'assistant' && (!msg.toolCalls || msg.toolCalls.length === 0)) {
          this.log(`  Skipped text-only assistant message (${msg.content.length} chars) - no tool calls`);
          continue;
        }
        messages.push({
          role: msg.role,
          content: msg.content
        });
      }
    }
    
    // Add current user input
    messages.push({ role: 'user', content: userInput });

    // Store original user input for potential misbehavior rebuild
    this._lastUserInput = userInput;

    let maxIterations = this.config.iterationSettings.maxIterations;
    // Override with VSCode settings if available (highest priority)
    if (this.settingsManager) {
      const settings = this.settingsManager.getSettings();
      if (settings.agent?.maxIterations && settings.agent.maxIterations > 0) {
        maxIterations = settings.agent.maxIterations;
        this.log(`Max iterations overridden by VSCode settings: ${maxIterations}`);
      }
    }
    if (typeof maxIterations !== 'number' || maxIterations < 0 || maxIterations > 100) maxIterations = 20;
    
    // Reset state only if no session state was restored (first conversation)
    const isFreshConversation = !this._sessionState;
    
    if (isFreshConversation) {
      this.log('Fresh conversation - resetting all state');
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
      this._lastSearchFiles = [];
      this._lastSearchIteration = 0;
      this._failedSearchCount = 0;
      this._lastSearchPattern = null;
      this._previousSearches = [];
      this._autoNudge = null;
      this._lastUserInput = '';
      this._triedStrategies = new Set<string>();
      this._strategyRotationCount = 0;
      this._hasCheckedRunningServers = false;
      this._consecutiveToolCallsWithoutResponse = 0;
      this._consecutiveTextResponsesWithoutToolCalls = 0;
      this._toolCallHistory = [];
      this._forceActionMode = false;
      this._lastKnownPromptTokens = 0; // Reset on fresh conversation
      this._lastKnownMessageCount = 0;
      this._lastKnownMessageChars = 0;

      // CRITICAL: Reset proxy session on fresh conversation so stale history
      // doesn't teach the model wrong formats (DSML XML, "Calling:", etc.)
      if (this.sessionManager && this.sessionManager.name !== 'Null') {
        this.log('Fresh conversation - resetting proxy session');
        await this.sessionManager.resetSession(this.config.model.id);
      }
    } else {
      this.log('Resumed conversation - preserving session state');
      // Only reset state machine, keep session-specific state
      this.stateMachine.reset();
      this._autoNudge = null;
      this._consecutiveToolCallsWithoutResponse = 0;
      this._toolCallHistory = [];
    }

    const toolCalls: ToolCall[] = [];
    const toolCallHistory: ToolCallHistory[] = [];

    let iteration = 0;
    
    // Initialize token usage tracking
    // For resumed conversations, try to restore from session state
    if (!isFreshConversation && this._sessionState?.lastTokenUsage) {
      this._lastTokenUsage = this._sessionState.lastTokenUsage;
      this.log(`Restored token usage from session: prompt=${this._lastTokenUsage.prompt}, completion=${this._lastTokenUsage.completion}`);
    } else {
      this._lastTokenUsage = undefined; // Fresh conversation starts with no token usage
    }
    
    while (true) {
      iteration++;
      this._autoNudge = null;
      // Don't reset token usage - it should persist across iterations
      // Only reset at the start of fresh conversations
      messages = messages.filter(m => !m._isNudge);
      this.stateMachine.incrementIteration();
      
      // STATE: INTENT - Classify user intent
      if (iteration === 1) {
        const userInputResult = this.stateMachine.dispatch(AgentEvent.USER_INPUT);
        this.logStateTransition(AgentState.IDLE, this.stateMachine.state, AgentEvent.USER_INPUT, `Starting new task`);
        
        const intentResult = this.stateMachine.dispatch(AgentEvent.INTENT_CLASSIFIED, { 
          intentType: taskType,
          taskDescription: userInput,
        });
        this.logStateTransition(AgentState.INTENT, this.stateMachine.state, AgentEvent.INTENT_CLASSIFIED, `Intent: ${taskType}`);
        
        if (options.streaming) {
          yield { type: 'thinking', message: `Understanding task: ${taskType}`, timestamp: Date.now() };
        }
      }
      
      // Check iteration limits
      if (iteration >= maxIterations) {
        const failureResult = this.stateMachine.dispatch(AgentEvent.MAX_ITERATIONS);
        this.logStateTransition(this.stateMachine.state, failureResult.state, AgentEvent.MAX_ITERATIONS, `Max iterations reached: ${iteration}/${maxIterations}`);
        this.log(`Agent stopped: Reached maximum iterations (${iteration})`, 'error');
        
        if (options.streaming) {
          yield { type: 'text', text: `❌ Agent stopped: Reached maximum iterations (${maxIterations})`, timestamp: Date.now() };
          yield { 
            type: 'error', 
            error: `MAX_ITERATIONS_REACHED: Agent executed ${maxIterations} iterations without completing the task`, 
            timestamp: Date.now() 
          };
          yield { type: 'done', outcome: 'error', timestamp: Date.now(), iterations: maxIterations };
        }
        return;
      }
      
      // Check for stuck states
      if (this.stateMachine.isPlanLoopStuck()) {
        this.log('Plan loop detected - forcing tool usage');
        this._strategyRotationCount++;
        
        // Strategy rotation on repeated failures
        if (this._strategyRotationCount >= 2) {
          const strategies = [
            'Read the file and look for structural issues (mismatched brackets, missing imports, syntax errors)',
            'List the directory and check if related files have changed',
            'Run the build to see the full error output',
            'Check git diff to see what changed recently',
            'Search for similar patterns in other files that work correctly',
            'Read the file from the beginning, not just the error area',
            'Look at the imports and dependencies - something might be missing',
            'Check if there are TypeScript/Kotlin type errors in the file'
          ];
          
          const unusedStrategy = Array.from(strategies).find(s => !this._triedStrategies.has(s));
          
          if (unusedStrategy) {
            this._triedStrategies.add(unusedStrategy);
            this.log(`Strategy rotation: trying "${unusedStrategy}"`);
            messages.push({
              role: 'user',
              content: `Your current approach isn't working. Try a different strategy: ${unusedStrategy}`,
              _isNudge: true
            });
            continue; // Skip to next iteration with new strategy
          } else {
            this.log('All strategies exhausted - asking user for guidance');
            messages.push({
              role: 'user',
              content: 'Multiple approaches have failed. Please explain what you tried so far and ask the user for guidance on how to proceed.',
              _isNudge: true
            });
            continue;
          }
        }
      }
      
      if (this.stateMachine.isBuildFixCycleStuck()) {
        this.log('Build-fix cycle stuck after 3 failures - stopping', 'error');
        const failureResult = this.stateMachine.dispatch(AgentEvent.MAX_FAILURES);
        this.logStateTransition(this.stateMachine.state, failureResult.state, AgentEvent.MAX_FAILURES, `Build failures: ${this._buildFailureCount}`);
        
        if (options.streaming) {
          yield { 
            type: 'text', 
            text: `❌ Build-fix cycle failed: ${this._buildFailureCount} consecutive build failures. The agent cannot automatically fix these errors.`, 
            timestamp: Date.now() 
          };
          yield { 
            type: 'error', 
            error: `BUILD_FIX_CYCLE_FAILED: ${this._buildFailureCount} consecutive build failures. Last errors: ${this._lastBuildErrors.substring(0, 200)}...`, 
            timestamp: Date.now() 
          };
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

DO NOT re-run build. DO NOT read more files. Call apply_edits NOW.`,
          _isNudge: true
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
          messages.push({ role: 'tool', content: `[AUTO BUILD VERIFICATION]\n${buildOutput}`, tool_call_id: `auto_build_${Date.now()}` });
          messages.push({ role: 'user', content: 'Build verification complete. Review results. If build passed, task is complete. If errors, fix them.', _isNudge: true });
          continue;
        } catch (e: any) {
          messages.push({ role: 'tool', content: `Error: ${e.message}` });
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

      // TOKEN BUDGET CHECK: Trim conversation if approaching token limit
      const estimatedTokens = this.estimateTokens(messages);
      const tokenUsagePercent = (estimatedTokens / this.config.model.contextLength) * 100;
      if (tokenUsagePercent > 50) {
        this.log(`Token usage: ${estimatedTokens.toLocaleString()} / ${this.config.model.contextLength.toLocaleString()} (${tokenUsagePercent.toFixed(1)}%)`);
      }
      if (estimatedTokens > this.config.model.contextLength * 0.8) {
        this.log(`Token warning: ${estimatedTokens} exceeds 80% of context window - summarizing old messages`);
        const trimmedMessages = await this.trimMessagesToBudget(messages, 0.75);
        if (trimmedMessages.length < messages.length) {
          this.log(`Messages trimmed: ${messages.length} → ${trimmedMessages.length} (saved ~${this.estimateTokens(messages) - this.estimateTokens(trimmedMessages)} tokens)`);
          messages = trimmedMessages;
          // Invalidate baseline so next estimate uses a fresh full count
          this._lastKnownPromptTokens = 0;
          this._lastKnownMessageCount = 0;
          this._lastKnownMessageChars = 0;
          this.log('Token baseline reset after trimming');
        }
      }

      // SESSION MANAGEMENT: Sync and manage provider-specific session
      if (this.sessionManager && this.sessionManager.name !== 'Null') {
        if (iteration === 1) {
          await this.sessionManager.syncSession(this.config.model.id);
          const sessionState = this.sessionManager.getSessionState();
          if (sessionState) {
            this.log(`Session ${sessionState.id || 'new'} — ${sessionState.messageCount} msgs, ${Math.round((Date.now() - sessionState.createdAt) / 60000)} min old`);
          }
        }

        // Proactive health check and reset
        const health = await this.sessionManager.checkHealth();
        if (!health.healthy) {
          this.log(`Session unhealthy: ${health.warnings.join('; ')}`);
          const resetOk = await this.sessionManager.resetSession(this.config.model.id);
          if (resetOk) {
            this.log('Session reset due to health check failure');
            yield { type: 'thinking', message: 'Session reset for reliability', timestamp: Date.now() };
          }
        }

        // Manage messages (compact if approaching limits)
        messages = await this.sessionManager.manageMessages(messages);
      }

      let responseText = '';
      let streamingToolCalls: LLMToolCall[] = [];
      let textBuffer: string[] = [];
      let textAlreadyStreamed = false;
      let toolCallDetected = false;
      let streamBuffer = '';

      if (options.streaming) {
        const rawResponse = await this.cli.callLLM(this.config.model.id, messages, { temperature: this.config.model.temperature, top_p: this.config.model.topP, max_tokens: this.config.model.maxOutputTokens }, tools, true, this.config.model.provider);
        
        // Check if response is actually an AsyncGenerator (streaming supported)
        const isAsyncGenerator = rawResponse && typeof (rawResponse as any)[Symbol.asyncIterator] === 'function';
        
        if (!isAsyncGenerator) {
          // Streaming not supported, fall back to non-streaming
          this.log(`Streaming not available, falling back to non-streaming mode`);
          const nonStreamResponse = await this.cli.callLLM(this.config.model.id, messages, { temperature: this.config.model.temperature, top_p: this.config.model.topP, max_tokens: this.config.model.maxOutputTokens }, tools, false, this.config.model.provider) as LLMResponse;
          responseText = nonStreamResponse.content;
          streamingToolCalls = nonStreamResponse.toolCalls.map(tc => ({ id: tc.id, name: tc.name, arguments: tc.arguments }));
          // Carry forward real or estimated token usage from non-streaming response
          if (nonStreamResponse.tokenUsage) {
            this._lastTokenUsage = nonStreamResponse.tokenUsage;
            this._lastKnownPromptTokens = nonStreamResponse.tokenUsage.prompt;
          } else {
            const promptTokens = this.estimateTokens(messages);
            const completionTokens = Math.ceil(responseText.length / 4);
            this._lastTokenUsage = { prompt: promptTokens, completion: completionTokens, total: promptTokens + completionTokens };
            this._lastKnownPromptTokens = promptTokens;
            this.log(`Estimated token usage (non-streaming): prompt=${promptTokens}, completion=${completionTokens}`);
          }
          this._lastKnownMessageCount = messages.length;
          this._lastKnownMessageChars = messages.reduce((s, m) => s + m.content.length, 0);
        } else {
          const streamResponse = rawResponse as AsyncGenerator<LLMChunk>;
          let reasoningCaptured = false;
          for await (const chunk of streamResponse) {
            if (chunk.text) {
              responseText += chunk.text;
              textBuffer.push(chunk.text);
              
              // Capture reasoning before tool calls
              if (!reasoningCaptured && !toolCallDetected) {
                streamBuffer += chunk.text;
                const toolCallIdx = streamBuffer.indexOf('tool_call:');
                if (toolCallIdx !== -1) {
                  toolCallDetected = true;
                  const beforeToolCall = streamBuffer.substring(0, toolCallIdx).trim();
                  // Extract and emit reasoning
                  const reasoningText = this.extractReasoning('reasoning: ' + beforeToolCall);
                  if (reasoningText) {
                    yield { type: 'reasoning', reasoning: reasoningText, timestamp: Date.now() };
                  }
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
            if (chunk.tokenUsage) {
              this._lastTokenUsage = chunk.tokenUsage;
              this._lastKnownPromptTokens = chunk.tokenUsage.prompt;
            }
            if (chunk.done) break;
          }

          // If the LLM provider did not include usage data (e.g. 3D LLM proxy SSE stream),
          // estimate token usage so the context meter can still update.
          if (!this._lastTokenUsage) {
            const promptTokens = this.estimateTokens(messages);
            const completionTokens = Math.ceil(responseText.length / 4);
            this._lastTokenUsage = { prompt: promptTokens, completion: completionTokens, total: promptTokens + completionTokens };
            this._lastKnownPromptTokens = promptTokens;
            this.log(`Estimated token usage (provider omitted usage): prompt=${promptTokens}, completion=${completionTokens}`);
          }
          
          // Persist metrics after streaming response completes for delta estimation
          this._lastKnownMessageCount = messages.length;
          this._lastKnownMessageChars = messages.reduce((s, m) => s + m.content.length, 0);

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

          // Fallback: parse our embedded JSON format {"name":"...","arguments":{...}}
          if (streamingToolCalls.length === 0) {
            const jsonPattern = /\{[^}]*"name"[^}]*"arguments"[^}]*\}/g;
            let match;
            while ((match = jsonPattern.exec(responseText)) !== null) {
              try {
                const toolCallObj = JSON.parse(match[0]);
                if (toolCallObj.name && typeof toolCallObj.name === 'string') {
                  streamingToolCalls.push({ id: `call_${iteration}_${streamingToolCalls.length}`, name: toolCallObj.name, arguments: toolCallObj.arguments || {} });
                }
              } catch (e: any) {}
            }
          }

          // Fallback: parse "Calling:" format that DeepSeek sometimes outputs
          if (streamingToolCalls.length === 0) {
            const callingPattern = /Calling:\s*(\w+)\s*\n?\s*```(?:json)?\s*\n?([\s\S]*?)```|Calling:\s*(\w+)\s*\n?\s*(\{[\s\S]*?\})/gi;
            let match;
            while ((match = callingPattern.exec(responseText)) !== null) {
              try {
                const toolName = match[1] || match[3];
                const jsonStr = (match[2] || match[4]).trim();
                const args = JSON.parse(jsonStr);
                if (toolName) {
                  streamingToolCalls.push({ id: `call_${iteration}_${streamingToolCalls.length}`, name: toolName, arguments: args || {} });
                }
              } catch (e: any) {}
            }
          }

          // Fallback: parse DeepSeek native XML function-calling format
          // (e.g. <｜｜DSML｜｜invoke name="read_file">...)
          if (streamingToolCalls.length === 0) {
            const dsmlPattern = /<\｜\｜DSML\｜\｜invoke\s+name="([^"]+)">([\s\S]*?)<\/\｜\｜DSML\｜\｜invoke>/g;
            let match;
            while ((match = dsmlPattern.exec(responseText)) !== null) {
              try {
                const toolName = match[1];
                const paramBlock = match[2];
                const args: Record<string, any> = {};
                // Parse <｜｜DSML｜｜parameter name="..." ...>value</｜｜DSML｜｜parameter>
                const paramPattern = /<\｜\｜DSML\｜\｜parameter\s+name="([^"]+)"[^>]*>([\s\S]*?)<\/\｜\｜DSML\｜\｜parameter>/g;
                let pMatch;
                while ((pMatch = paramPattern.exec(paramBlock)) !== null) {
                  const pName = pMatch[1];
                  const pValue = pMatch[2].trim();
                  // Try to parse as number/boolean, fallback to string
                  if (pValue === 'true') args[pName] = true;
                  else if (pValue === 'false') args[pName] = false;
                  else if (/^-?\d+$/.test(pValue)) args[pName] = parseInt(pValue, 10);
                  else args[pName] = pValue;
                }
                streamingToolCalls.push({ id: `call_${iteration}_${streamingToolCalls.length}`, name: toolName, arguments: args });
                this.log(`Parsed DSML tool call: ${toolName}(${JSON.stringify(args)})`);
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

      // Detect DeepSeek misbehavior: text-only responses that look like plans/tool calls
      // but don't contain actual tool_calls array. Happens when proxy session history
      // teaches DeepSeek to output its own "Calling:" text format.
      if (!hasToolCalls) {
        const isMisbehavior = this.detectProxyMisbehavior(responseText);
        if (isMisbehavior) {
          this._consecutiveTextResponsesWithoutToolCalls++;
          this.log(`DeepSeek misbehavior detected (${this._consecutiveTextResponsesWithoutToolCalls}/2): text response contains plan/tool-like patterns`);

          // FIRST misbehavior: reset proxy session and rebuild clean context.
          // Don't add a nudge — the corrupted proxy history is the problem.
          if (this._consecutiveTextResponsesWithoutToolCalls >= 1) {
            this.log('Misbehavior detected — resetting proxy session and rebuilding clean context');
            if (this.sessionManager && this.sessionManager.provider === '3d-llm') {
              const resetOk = await this.sessionManager.resetSession(this.config.model.id);
              if (resetOk) {
                this.log('Proxy session reset successfully, re-attempting with clean history');
                await this.sessionManager.syncSession(this.config.model.id);
              } else {
                this.log('Proxy session reset failed');
              }
            }

            // Rebuild messages: keep system prompt + original user task, drop everything else.
            // Using this._lastUserInput instead of messages.filter() because the messages array
            // may contain auto-generated "Tool results received" entries, not the actual task.
            const systemPrompt = messages.find(m => m.role === 'system');
            messages = systemPrompt ? [systemPrompt] : [];
            if (this._lastUserInput) {
              messages.push({ role: 'user', content: this._lastUserInput });
            }
            this.log(`Rebuilt messages: ${messages.length} items (system + original user task)`);
          }

          this._consecutiveTextResponsesWithoutToolCalls = 0;
          this.stateMachine.dispatch(responseEvent);
          continue;
        }
      } else {
        // Successful tool call — reset misbehavior counter
        this._consecutiveTextResponsesWithoutToolCalls = 0;
      }

      // NATURAL EXIT (no tool calls)
      if (!hasToolCalls) {
        const trimmedResponse = responseText.trim();
        const isPlanOnly = responseEvent === AgentEvent.PLAN_ONLY;

        if (isPlanOnly) {
          this._consecutivePlans++;
          const lastBuildError = messages.filter(m => m.role === 'tool' && typeof m.content === 'string' && m.content.includes('BUILD FAILED')).pop();
          if (lastBuildError) this._fixMode = true;
          
          let nudgeMessage = `STOP describing plans. Use tool calling API NOW.`;
          
          // TOOL CALL ENFORCEMENT: Detect when LLM mentions specific tools but doesn't call them
          const toolMentionPatterns = [
            { pattern: /apply_edits\s*(?:on|to|for)/i, tool: 'apply_edits', message: 'You mentioned apply_edits but did not call it. CALL apply_edits NOW with the exact file path and edits.' },
            { pattern: /read_file\s*(?:to|for|from)/i, tool: 'read_file', message: 'You mentioned read_file but did not call it. CALL read_file NOW with the exact file path.' },
            { pattern: /write_file\s*(?:to|for|with)/i, tool: 'write_file', message: 'You mentioned write_file but did not call it. CALL write_file NOW with the exact file path and content.' },
            { pattern: /search_files\s*(?:for|with|using)/i, tool: 'search_files', message: 'You mentioned search_files but did not call it. CALL search_files NOW with the exact pattern.' },
            { pattern: /run_terminal\s*(?:with|using|command)/i, tool: 'run_terminal', message: 'You mentioned run_terminal but did not call it. CALL run_terminal NOW with the exact command.' }
          ];
          
          // Check if LLM mentioned a specific tool without calling it
          for (const { pattern, tool, message } of toolMentionPatterns) {
            if (pattern.test(trimmedResponse)) {
              nudgeMessage = message;
              this.log(`TOOL CALL ENFORCEMENT: LLM mentioned ${tool} but didn't call it`, 'warn');
              break;
            }
          }
          
          // Add specific file context if available
          if (lastBuildError) {
            const content = lastBuildError.content as string;
            const fileMatch = content.match(/FILES TO READ AND FIX:[\s\S]*?(?:COMPILER ERRORS|$)/);
            if (fileMatch) {
              const files = fileMatch[0].split('\n').filter(line => line.includes('.kt:') || line.includes('.java')).map(line => line.replace(/^\s*-\s*/, '').trim());
              nudgeMessage += `\n\nBuild failed. Use apply_edits to fix:\n${files.map(f => `- ${f}`).join('\n')}`;
            }
          }
          if (lastBuildError) {
            const content = lastBuildError.content as string;
            const fileMatch = content.match(/FILES TO READ AND FIX:[\s\S]*?(?:COMPILER ERRORS|$)/);
            if (fileMatch) {
              const files = fileMatch[0].split('\n').filter(line => line.includes('.kt:') || line.includes('.java')).map(line => line.replace(/^\s*-\s*/, '').trim());
              nudgeMessage += `\n\nBuild failed. Use apply_edits to fix:\n${files.map(f => `- ${f}`).join('\n')}`;
            }
          }
          
          messages.push({ role: 'user', content: nudgeMessage, _isNudge: true });
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
          const doneChunk: any = { type: 'done', outcome: 'success', timestamp: Date.now(), iterations: iteration };
          if (this._lastTokenUsage) doneChunk.tokenUsage = this._lastTokenUsage;
          yield doneChunk;
        }
        
        // Reset tool call counter when agent provides text response
        this._consecutiveToolCallsWithoutResponse = 0;
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
          content: `⚠️ Plan validation failed:\n${validation.violations.join('\n')}\n\nPlease revise your tool calls to comply with constraints.`,
          _isNudge: true
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
      
      // LOOP DETECTION - Skip for search_files on first failure (auto-explore will guide)
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
        
        // Skip loop detection for search_files if we're in auto-explore mode (first failed search)
        const isAutoExploreMode = toolCall.name === 'search_files' && this._failedSearchCount === 1;
        
        if (!isAutoExploreMode && this.stateMachine.detectLoop(toolCall.name, normalizedArgs, iteration)) {
          this.log(`LOOP DETECTED: ${toolCall.name} with same arguments`, 'error');
          const failureResult = this.stateMachine.dispatch(AgentEvent.LOOP_DETECTED);
          this.logStateTransition(this.stateMachine.state, failureResult.state, AgentEvent.LOOP_DETECTED, `Tool loop: ${toolCall.name}`);
          
          if (options.streaming) {
            yield { 
              type: 'text', 
              text: `❌ Agent stopped: Detected loop calling ${toolCall.name} with same arguments repeatedly.`, 
              timestamp: Date.now() 
            };
            yield { 
              type: 'error', 
              error: `TOOL_LOOP_DETECTED: Agent is stuck in a loop calling ${toolCall.name} with identical arguments.`, 
              timestamp: Date.now() 
            };
            yield { type: 'done', outcome: 'error', timestamp: Date.now(), iterations: iteration };
          }
          return;
        }
        
        // Only track history for non-search tools during auto-explore
        if (!isAutoExploreMode) {
          toolCallHistory.push({ toolName: toolCall.name, argsSignature, iteration });
        }
      }

      if (shouldNudge.length > 0) {
        messages.push({ role: 'user', content: `NOTICE: You called ${[...new Set(shouldNudge)].join(', ')} with same arguments. Try a DIFFERENT approach.`, _isNudge: true });
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
        let toolResult: { result: string; error?: string } | null = null;

        try {
          this.log(`Executing tool: ${toolCall.name}`, 'info');
          const result = await this.executeToolWithRetry(toolCall, 3);
          toolCallObj.result = result.result;
          if (result.error) toolCallObj.error = result.error;
          toolResult = result;
          
          if (result.error) {
            this.log(`Tool ${toolCall.name} failed: ${result.error}`, 'error');
          } else {
            this.log(`Tool ${toolCall.name} completed successfully`, 'info');
          }

          const durationMs = Date.now() - startTime;

          // Record in state machine for loop detection and metrics
          this.stateMachine.recordToolCall(toolCall.name, toolCall.arguments, result.result, result.error, durationMs);

          if (options.streaming) {
            yield { type: 'tool_call_completed', toolName: toolCall.name, result: result.result, timestamp: Date.now() };
          }
        } catch (error: any) {
          toolCallObj.error = error.message;
          this.log(`Tool ${toolCall.name} execution failed with exception: ${error.message}`, 'error');
          this.log(`Stack trace: ${error.stack}`, 'error');
          this.stateMachine.recordToolCall(toolCall.name, toolCall.arguments, undefined, error.message);
          if (options.streaming) {
            yield { type: 'tool_call_completed', toolName: toolCall.name, result: `Error: ${error.message}`, timestamp: Date.now() };
            yield { type: 'error', error: `TOOL_EXECUTION_FAILED: ${toolCall.name}: ${error.message}`, timestamp: Date.now() };
          }
        }

        currentIterationToolCalls.push(toolCallObj);
        toolCalls.push(toolCallObj);
        
        // AUTO-EXPLORE: Track failed searches and guide agent to explore directories
        // SIMILAR SEARCH DETECTION: Detect variant patterns of previous searches
        if (toolCall.name === 'search_files') {
          const searchPattern = toolCall.arguments.pattern;
          
          // Check for similar search patterns (variants of previous searches)
          const similarPattern = this.findSimilarSearch(searchPattern);
          if (similarPattern) {
            const similarSearchMessage = `[AUTO] You've already searched for a similar pattern ("${similarPattern}"). The results won't change. Read the files you found instead of searching again with a slightly different pattern.`;
            messages.push({
              role: 'tool',
              content: similarSearchMessage,
              tool_call_id: `auto_similar_${Date.now()}`
            });
            this.log(`Similar search detected: "${searchPattern}" is similar to previous "${similarPattern}"`);
          }
          
          // Record this search pattern for future detection
          this.recordSearchPattern(searchPattern);
          
          // Check if search returned no results
          if (toolResult?.result && toolResult.result.includes('No files found')) {
            this._failedSearchCount++;
            this._lastSearchPattern = searchPattern;
            
            // First failed search - use domain knowledge to guide exploration
            if (this._failedSearchCount === 1) {
              // Use domain-aware directories if available
              const directoriesToList = this._suggestedDirectories.length > 0 
                ? this._suggestedDirectories 
                : [this.workspaceRoot];
              
              const domainContext = this._domainResolution?.primaryDomain !== 'unknown'
                ? `This appears to be a **${this._domainResolution.primaryDomain}** task.\n\n`
                : '';
              
              const exploreMessage = `[AUTO-EXPLORE] Search for "${searchPattern}" found nothing.

${domainContext}Instead of searching again, explore these directories first:
${directoriesToList.map(d => `  - ${d}`).join('\n')}

TIP: Use list_directory on these folders to understand the structure, THEN search or read specific files.

Example:
  list_directory(path: "${directoriesToList[0] || 'frontend/src'}")
`;
              messages.push({
                role: 'tool',
                content: exploreMessage,
                tool_call_id: `auto_explore_${Date.now()}`
              });
              this.log(`Auto-explore: Listed ${directoriesToList.length} directories after failed search for "${searchPattern}" (domain: ${this._domainResolution?.primaryDomain || 'unknown'})`);
            } else if (this._failedSearchCount >= 2) {
              // Second failed search - stronger nudge
              const domainTip = this._domainResolution?.primaryDomain !== 'unknown'
                ? ` Focus on ${this._domainResolution.primaryDomain} directories.`
                : '';
              this._autoNudge = `⚠️ You've searched ${this._failedSearchCount} times without finding results. STOP searching. Use list_directory to explore the project structure first.${domainTip}`;
            }
          } else {
            // Successful search - reset counter
            this._failedSearchCount = 0;
            this._lastSearchPattern = null;
          }
        }
      }

      let assistantContent = responseText;
      // When model outputs text prose alongside actual JSON tool calls, don't save
      // the prose to history — it teaches the model that "I will: Calling X" is valid.
      if (currentIterationToolCalls.length > 0) {
        assistantContent = `Executed: ${currentIterationToolCalls.map(tc => tc.toolName).join(', ')}`;
      } else if (!assistantContent || assistantContent.trim() === '') {
        assistantContent = 'No tool calls.';
      }
      
      messages.push({ role: 'assistant', content: assistantContent || '' });

      // Record tool calls in history for pattern detection
      if (currentIterationToolCalls.length > 0) {
        this._consecutiveToolCallsWithoutResponse += currentIterationToolCalls.length;
        currentIterationToolCalls.forEach(tc => {
          this._toolCallHistory.push({ toolName: tc.toolName, iteration });
        });
        
        // Keep history bounded (last 20 calls)
        if (this._toolCallHistory.length > 20) {
          this._toolCallHistory.shift();
        }
        
        // PATTERN DETECTION: Check for over-exploration (4+ exploration tools with no action)
        // Exploration includes list_directory, search_files, read_file, get_file_context.
        // If the model keeps reading/looking without ever acting, force action mode.
        const explorationTools = ['list_directory', 'search_files', 'read_file', 'get_file_context'];
        const actionTools = ['write_file', 'apply_edits', 'run_terminal', 'run_build', 'git_commit'];
        
        const explorationCount = this._toolCallHistory.filter(tc => explorationTools.includes(tc.toolName)).length;
        const actionCount = this._toolCallHistory.filter(tc => actionTools.includes(tc.toolName)).length;
        
        if (explorationCount >= 4 && actionCount === 0) {
          this.log(`Pattern detected: ${explorationCount} exploration calls (including reads) with no action - forcing action mode`);
          this._autoNudge = `⚠️ STOP exploring. You've called ${explorationCount} exploration tools (list_directory, search_files, read_file, get_file_context) without taking any action. You have enough information. Either:
1. Run a command (run_terminal, run_build)
2. Apply edits (apply_edits, write_file)
3. Provide a final answer

Do NOT search, list, or read any more files. RESPOND NOW.`;
          this._consecutiveToolCallsWithoutResponse = 0; // Reset after nudge
          this._forceActionMode = true; // Force action mode - removes exploration tools
        } else if (actionCount > 0 && this._forceActionMode) {
          // Reset force action mode after an action is taken
          this.log(`Force action mode: action tool detected - resetting`);
          this._forceActionMode = false;
        }
      }

      for (let i = 0; i < currentIterationToolCalls.length; i++) {
        const tc = currentIterationToolCalls[i];
        const toolCallId = streamingToolCalls[i]?.id || tc.toolCallId || `call_${iteration}_${i}`;
        messages.push({ role: 'tool', content: tc.error || tc.result || 'No result', tool_call_id: toolCallId });
      }

      if (this._autoNudge) {
        messages.push({ role: 'user', content: this._autoNudge, _isNudge: true });
        this._autoNudge = null;
      }

      // When in action-only mode (forcing synthesis), don't ask the model to "answer now".
      // The system explicitly stripped exploration tools, so the model has no choice but to act.
      if (this._forceActionMode) {
        messages.push({ role: 'user', content: 'You must take action now. Use apply_edits, write_file, run_terminal, run_build, or git_commit. No more exploration.', _isNudge: true });
      } else {
        // PRESSURE TO CONTINUE: Don't let the model stop early. Force it to keep acting
        // until the task is demonstrably complete (files edited, build run, tests pass).
        messages.push({ role: 'user', content: 'Tool results received. Continue with the next step. Do NOT stop or summarize until the task is fully complete — files are edited, builds pass, and changes are verified.', _isNudge: true });
      }

      // STATE: VERIFY - Transition and check results
      this.stateMachine.dispatch(AgentEvent.TOOLS_EXECUTED);

      if (options.streaming) {
        // Emit token usage after each LLM response, even when tool calls follow
        if (this._lastTokenUsage) {
          yield { type: 'token_usage', tokenUsage: this._lastTokenUsage, timestamp: Date.now() };
        }
        yield { type: 'iteration_complete', iteration, timestamp: Date.now() };
      }
    }
  }

  private injectContextIntoPrompt(prompt: string, context?: VslfcContext): string {
    if (!context) return prompt;
    return prompt;
  }

  /**
   * Normalize search pattern by removing regex special characters and converting to lowercase
   * Used for detecting similar search patterns (e.g., "ZoomToolsUI" vs "ZoomToolsUI|UnifiedEditor")
   */
  private normalizeSearchPattern(pattern: string): string {
    return pattern.replace(/[|.*+?^${}()|[\]\\]/g, '').toLowerCase();
  }

  /**
   * Check if current search pattern is similar to any previous search
   * Returns the similar pattern if found, null otherwise
   */
  private findSimilarSearch(currentPattern: string): string | null {
    const normalizedCurrent = this.normalizeSearchPattern(currentPattern);
    
    for (const prevPattern of this._previousSearches) {
      const normalizedPrev = this.normalizeSearchPattern(prevPattern);
      
      // Check for substring overlap in either direction
      const currentInPrev = normalizedPrev.includes(normalizedCurrent);
      const prevInCurrent = normalizedCurrent.includes(normalizedPrev);
      
      // Also check for significant overlap using Jaccard-like similarity
      const currentTerms = normalizedCurrent.split(/[\s_]+/).filter(t => t.length > 2);
      const prevTerms = normalizedPrev.split(/[\s_]+/).filter(t => t.length > 2);
      
      const intersection = currentTerms.filter(t => prevTerms.includes(t));
      const union = [...new Set([...currentTerms, ...prevTerms])];
      const overlapRatio = union.length > 0 ? intersection.length / union.length : 0;
      
      if (currentInPrev || prevInCurrent || overlapRatio >= AgentBridge.SIMILAR_SEARCH_THRESHOLD) {
        return prevPattern;
      }
    }
    
    return null;
  }

  /**
   * Record a search pattern for future similar-search detection
   */
  private recordSearchPattern(pattern: string): void {
    // Keep only last 10 searches to avoid unbounded growth
    if (this._previousSearches.length >= 10) {
      this._previousSearches.shift();
    }
    this._previousSearches.push(pattern);
  }

  /**
   * Estimate token count for messages using actual LLM-reported prompt tokens
   * as a baseline, plus a delta estimate for new content since the last call.
   * Falls back to conservative character counting when no baseline exists.
   */
  private estimateTokens(messages: LLMMessage[]): number {
    const baseline = this._lastKnownPromptTokens || 0;
    if (baseline === 0) {
      // No prior data — fallback to conservative estimate
      const msgOverhead = messages.length * AgentBridge.PER_MESSAGE_OVERHEAD;
      return messages.reduce((sum, msg) => sum + Math.ceil(msg.content.length / 3.5), msgOverhead);
    }

    // Calculate delta: how many new messages and new characters were added
    const currentChars = messages.reduce((sum, msg) => sum + msg.content.length, 0);
    const currentCount = messages.length;
    const newChars = Math.max(0, currentChars - this._lastKnownMessageChars);
    const newMessages = Math.max(0, currentCount - this._lastKnownMessageCount);

    // Estimate only the new content added since the baseline was captured.
    // Use a tighter ratio for deltas (3.5 chars/token) plus per-message overhead.
    const deltaTokens = Math.ceil(newChars / 3.5) + (newMessages * AgentBridge.PER_MESSAGE_OVERHEAD);
    return baseline + deltaTokens;
  }

  /**
   * Summarize old conversation messages to reduce token usage
   */
  private async summarizeConversation(messages: LLMMessage[]): Promise<string> {
    if (messages.length === 0) return '';
    
    // Extract key information from old messages
    const toolCalls = messages
      .filter(m => m.role === 'tool' && typeof m.content === 'string')
      .map(m => {
        const content = m.content as string;
        // Extract file paths and key results
        const fileMatch = content.match(/(?:read|wrote|edited|found)\s+[^\n]+/gi);
        return fileMatch ? fileMatch.slice(0, 3).join('; ') : content.substring(0, 100);
      })
      .filter(Boolean);

    const summary = `Previous conversation covered: ${toolCalls.slice(0, 10).join(' | ')}`;
    this.log(`Conversation summarized: ${toolCalls.length} tool results condensed`);
    return summary;
  }

  /**
   * Trim messages to stay within token budget, summarizing old messages
   */
  private async trimMessagesToBudget(messages: LLMMessage[], maxTokenPercentage: number = 0.8): Promise<LLMMessage[]> {
    const maxTokens = this.config.model.contextLength * maxTokenPercentage;
    let estimatedTokens = this.estimateTokens(messages);
    
    if (estimatedTokens <= maxTokens) {
      return messages;
    }

    this.log(`Token warning: ${estimatedTokens} / ${this.config.model.contextLength} (${(estimatedTokens / this.config.model.contextLength * 100).toFixed(1)}%) - trimming conversation`);

    // Keep system prompt + last 8 messages, summarize the rest
    const systemMsg = messages[0];
    const recentMsgs = messages.slice(-8);
    const oldMessages = messages.slice(1, -8);
    
    if (oldMessages.length === 0) {
      return messages; // Nothing to trim
    }

    const summary = await this.summarizeConversation(oldMessages);
    const summaryMsg: LLMMessage = { role: 'user', content: `[Previous conversation summary: ${summary}]. Continue from recent messages above.` };
    
    return [systemMsg, summaryMsg, ...recentMsgs];
  }

  private async callLLM(messages: LLMMessage[], tools: LLMTool[]): Promise<LLMResponse> {
    const result = await this.cli.callLLM(this.config.model.id, messages, { temperature: this.config.model.temperature, top_p: this.config.model.topP, max_tokens: this.config.model.maxOutputTokens }, tools, false, this.config.model.provider);
    if (Symbol.asyncIterator in result) throw new Error('Expected non-streaming response but got streaming generator');
    return result as LLMResponse;
  }

  private async executeTool(toolCall: ToolCall): Promise<{ result: string; error?: string }> {
    try {
      // TOOL FILTER ENFORCEMENT: When action_only mode is active, reject disallowed tools
      if (this._forceActionMode) {
        const allowedTools = ['apply_edits', 'write_file', 'read_file', 'get_file_context', 'run_terminal', 'run_build', 'git_commit'];
        if (!allowedTools.includes(toolCall.toolName)) {
          this.log(`TOOL FILTER BLOCKED: ${toolCall.toolName} not in action_only set. Allowed: ${allowedTools.join(', ')}`);
          return {
            result: '',
            error: `⚠️ TOOL NOT AVAILABLE: "${toolCall.toolName}" is blocked in action-only mode. You must use one of the following allowed tools: ${allowedTools.join(', ')}. Stop exploring and take action now.`
          };
        }
      }

      // PRE-FLIGHT CHECK: Re-read limit - block repeated reads of same file
      if (toolCall.toolName === 'read_file') {
        const filePath = this.resolvePath(toolCall.args.path);
        const count = this._readFileCount.get(filePath) || 0;
        if (count >= AgentBridge.MAX_READS_PER_FILE) {
          this.log(`READ LIMIT: "${filePath}" already read ${count} times. Forcing action.`);
          return {
            result: '',
            error: `⚠️ READ LIMIT: "${toolCall.args.path}" has been read ${count} times already. You have enough information. Use apply_edits or write_file to make changes. Stop reading and take action now.`
          };
        }
      }

      // PRE-FLIGHT CHECK: Before starting servers, check what's already running
      if (toolCall.toolName === 'run_terminal') {
        const command = toolCall.args.command as string;
        const isServerStartCommand = /gradlew.*:run|npm\s+(run\s+)?(dev|start)|yarn\s+(dev|start)|vite|next\s+dev|react-scripts\s+start/i.test(command);

        if (isServerStartCommand && !this._hasCheckedRunningServers) {
          this._hasCheckedRunningServers = true;

          // Check VS Code terminals for existing servers
          const vscode = require('vscode');
          const allTerminals = vscode.window.terminals;
          const runningTerminals = allTerminals.filter((t: any) =>
            !t.exitStatus && // undefined = running, defined = closed
            (t.name.toLowerCase().includes('gradlew') ||
             t.name.toLowerCase().includes('npm') ||
             t.name.toLowerCase().includes('node') ||
             t.name.toLowerCase().includes('vite') ||
             t.name.toLowerCase().includes('java'))
          );

          if (runningTerminals.length > 0) {
            this.log(`Pre-flight check: Found ${runningTerminals.length} existing terminal(s) that may be servers`);

            // Inject a tool message to inform the agent
            const terminalList = runningTerminals.map((t: any) => `- ${t.name}`).join('\n');
            const preflightMessage = `[AUTO] Found ${runningTerminals.length} existing terminal(s) that may be running servers:\n${terminalList}\n\n**Check if the server is already running before starting a new one.**\n\nUse list_all_terminals to inspect them, or check the browser/application to see if it's responding.`;

            // Store for injection into messages
            if (!(this as any)._pendingMessages) (this as any)._pendingMessages = [];
            (this as any)._pendingMessages.push({
              role: 'tool',
              content: preflightMessage,
              tool_call_id: `auto_preflight_${Date.now()}`
            });
            
            // Also add to auto-nudge for immediate effect
            this._autoNudge = `⚠️ Found ${runningTerminals.length} existing terminal(s). Check list_all_terminals before starting a new server!`;

            return {
              result: `Pre-flight check: Found ${runningTerminals.length} existing terminal(s). Use list_all_terminals to inspect them before starting a new server.`
            };
          }
        }
      }

      // Create tool context with all necessary dependencies
      // Wrap readFile with session-level caching to prevent redundant reads
      const context: ToolContext = {
        workspaceRoot: this.workspaceRoot,
        resolvePath: (p: string) => this.resolvePath(p),
        runCommand: (cmd: string, timeout: number, cwd?: string) => this.runCommandWithTimeout(cmd, timeout, cwd),
        readFile: (p: string) => this.readFileCached(p),
        writeFile: (p: string, c: string) => {
          this.invalidateFileCache(p);
          return this.cli.writeFile(p, c);
        },
        listFiles: (p: string, r: boolean) => this.cli.listFiles(p, r),
        searchFiles: (p: string, d?: string) => this.cli.searchFiles(p, d),
        getFileContext: (p: string) => this.cli.getContext(p),
        fileExists: async (p: string) => fs.existsSync(p),
        terminalManager: this.terminalManager,
        vscode: require('vscode'),
        log: (msg: string) => this.log(msg),
        emitProgress: (e: any) => this.emitProgress(e),
        fileSnapshots: this._fileSnapshots,
      };

      // Delegate to tool registry
      const rawResult = await this.toolRegistry.execute(toolCall.toolName, toolCall.args, context);

      // Track successful read_file calls for per-file limit
      if (toolCall.toolName === 'read_file' && !rawResult.error) {
        const filePath = this.resolvePath(toolCall.args.path);
        const currentCount = this._readFileCount.get(filePath) || 0;
        this._readFileCount.set(filePath, currentCount + 1);
      }

      // Compress large tool results before sending to LLM
      if (rawResult.result && this.config.model.provider === '3d-llm') {
        const compressed = this.toolCompressor.compress(rawResult.result);
        if (compressed.wasCompressed) {
          this.log(`Tool result compressed: ${compressed.originalLength} → ${compressed.compressedLength} chars (${compressed.technique})`);
        }
        return {
          result: compressed.compressed,
          error: rawResult.error,
        };
      }

      return rawResult;
    } catch (error: any) {
      this.log(`  Tool error: ${error.message}`);
      return { result: '', error: error.message };
    }
  }

  /**
   * Execute a tool with retry logic.
   * For 3D LLM: detects session errors and auto-resets, handles malformed JSON.
   */
  private async executeToolWithRetry(
    toolCall: LLMToolCall,
    maxRetries: number = 3
  ): Promise<{ result: string; error?: string }> {
    let attempt = 0;
    let lastError = '';

    while (attempt < maxRetries) {
      attempt++;
      try {
        const result = await this.executeTool({ toolName: toolCall.name, args: toolCall.arguments });

        // If tool executed but returned an error indicating session issues
        if (result.error && this.isSessionError(result.error)) {
          this.log(`Session error in tool result (attempt ${attempt}/${maxRetries})`);
          if (this.sessionManager && attempt < maxRetries) {
            const resetOk = await this.sessionManager.resetSession(this.config.model.id);
            if (resetOk) {
              this.log('Session reset after tool error, retrying...');
              this.sessionManager?.recordRetry();
              continue;
            }
          }
        }

        return result;
      } catch (error: any) {
        lastError = error.message;

        // Session-level errors: reset and retry
        if (this.isSessionError(lastError)) {
          this.log(`Session-level error (attempt ${attempt}/${maxRetries}): ${lastError}`);
          if (this.sessionManager && attempt < maxRetries) {
            const resetOk = await this.sessionManager.resetSession(this.config.model.id);
            if (resetOk) {
              this.log('Session reset, retrying tool...');
              this.sessionManager?.recordRetry();
              continue;
            }
          }
        }

        // JSON parsing errors from LLM: inject simplified prompt for next iteration
        if (lastError.includes('JSON') || lastError.includes('parse')) {
          this.log(`JSON error (attempt ${attempt}/${maxRetries})`);
          if (attempt < maxRetries) {
            this.injectSimplifiedToolPrompt(toolCall);
            continue;
          }
        }

        throw error;
      }
    }

    return { result: '', error: `Tool failed after ${maxRetries} attempts: ${lastError}` };
  }

  private isSessionError(errorText: string): boolean {
    const sessionIndicators = [
      'session',
      'empty_response',
      '502',
      'session_reset',
      'expired',
      'invalid session',
    ];
    return sessionIndicators.some((ind) => errorText.toLowerCase().includes(ind));
  }

  private injectSimplifiedToolPrompt(toolCall: LLMToolCall): void {
    const simplifiedPrompt = `Your previous tool call had malformed JSON. Retry with SIMPLIFIED arguments:
TOOL_CALL: ${toolCall.name}
arguments: {"path": "/path/to/file"}  // Keep it minimal

DO NOT include large content in arguments. Just reference files by path.`;

    const pending = (this as any)._pendingMessages as LLMMessage[] | undefined;
    if (!pending) {
      (this as any)._pendingMessages = [];
    }
    (this as any)._pendingMessages.push({ role: 'user', content: simplifiedPrompt });
    this.log(`Injected simplified tool prompt for ${toolCall.name}`);
  }

  // Legacy executeTool implementation - replaced by ToolRegistry
  // The switch statement below has been migrated to individual tool handlers in src/agent/tools/builtin/
  private async executeToolLegacy(toolCall: ToolCall): Promise<{ result: string; error?: string }> {
    try {
      switch (toolCall.toolName) {
        case 'list_directory': {
          const dirPath = this.resolvePath(toolCall.args.path);
          const recursive = toolCall.args.recursive === true;
          this.log(`list_directory (legacy): args.path="${toolCall.args.path}" → resolved="${dirPath}", recursive=${recursive}`);
          
          // Track visited paths to prevent re-exploration
          const visitedKey = `${dirPath}:${recursive}`;
          if (this._autoReadFiles.has(visitedKey)) {
            this.log(`list_directory: Already explored ${dirPath} (recursive=${recursive})`);
          }
          this._autoReadFiles.add(visitedKey);
          
          try {
            const files = await this.cli.listFiles(dirPath, recursive);
            this.log(`list_directory (legacy) result: ${files.length} files found`);
            if (files.length === 0) {
              try { fs.accessSync(dirPath); return { result: 'This directory is empty.' }; }
              catch { return { result: `DIRECTORY_NOT_FOUND: '${toolCall.args.path}' does not exist.` }; }
            }
            return { result: files.join('\n') };
          } catch (error: any) {
            this.log(`list_directory (legacy) error: ${error.message}`);
            if (error.code === 'ENOENT') return { result: `DIRECTORY_NOT_FOUND: '${toolCall.args.path}' does not exist.` };
            throw error;
          }
        }
        
        case 'read_file': {
          const filePath = this.resolvePath(toolCall.args.path);
          const normalizedPath = filePath.toLowerCase();
          
          // Track visited file paths
          if (this._autoReadFiles.has(normalizedPath)) {
            return { result: '[Already read. Focus on proposing fixes with apply_edits or write_file.]' };
          }
          this._autoReadFiles.add(normalizedPath);
          
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
          
          // Check search cache first
          const cacheKey = `${pattern}:${searchPath || '*'}`;
          const cachedResult = this._searchCache.get(cacheKey);
          const now = Date.now();
          
          if (cachedResult && now - cachedResult.timestamp < AgentBridge.SEARCH_CACHE_TTL_MS) {
            this.log(`Search cache HIT for "${pattern}" (${cachedResult.results.length} files)`);
            const results = cachedResult.results;
            if (results.length === 0) {
              return { result: `No files found matching pattern "${pattern}" (cached). Try a different search term.`, error: 'NO_RESULTS' };
            }
            // Format cached SearchResult objects
            const formatted = results.slice(0, 10).map((r: any) => {
              const rel = r.path.replace(this.workspaceRoot, '').replace(/^[/\\]/, '');
              let out = `📄 ${rel}`;
              if (!r.matchedByName && r.matches?.length) {
                out += ` — ${r.matchCount} match(es)`;
                for (const m of r.matches.slice(0, 3)) {
                  out += `\n  Line ${m.line}: ${m.text}`;
                }
              }
              return out;
            });
            return { result: `Found ${results.length} file(s) (cached):\n\n${formatted.join('\n\n')}\n\nTIP: You found the files! Now READ one of them instead of searching more.` };
          }
          
          // Check for search loop - same pattern searched multiple times
          if (this._lastSearchPattern === pattern && this._lastSearchFiles.length > 0) {
            this.log(`SEARCH LOOP: Pattern "${pattern}" already searched. Found ${this._lastSearchFiles.length} files: ${this._lastSearchFiles.slice(0, 3).join(', ')}...`);
            this.stateMachine.dispatch(AgentEvent.SEARCH_LOOP);
            return {
              result: `⚠️ You already searched for "${pattern}" and found ${this._lastSearchFiles.length} files. Instead of searching again, READ one of these files: ${this._lastSearchFiles.slice(0, 3).join(', ')}`,
              error: 'SEARCH_LOOP_DETECTED'
            };
          }
          
          // TIP: If pattern looks like "class X" or "function Y", suggest simpler search
          const classMatch = pattern.match(/class\s+(\w+)/);
          const functionMatch = pattern.match(/function\s+(\w+)/);
          const simplePattern = classMatch ? classMatch[1] : functionMatch ? functionMatch[1] : pattern;
          
          this.log(`Search cache MISS for "${pattern}" - executing search`);
          const results = await this.cli.searchFiles(simplePattern, searchPath);
          
          // Cache the search results
          this._searchCache.set(cacheKey, { results, timestamp: now });
          
          // Track this search for loop detection
          this._lastSearchPattern = pattern;
          this._lastSearchFiles = results.map((r: any) => r.path);
          
          if (results.length === 0) {
            let suggestion = 'Try a different search term or use list_directory to explore.';
            
            // If searched for "class X", suggest searching just "X"
            if (classMatch) {
              suggestion = `TIP: Instead of searching for "class ${classMatch[1]}", try searching for just "${classMatch[1]}" (the class name without the keyword). Also check if the class exists in a different module or has a different name.`;
            } else if (functionMatch) {
              suggestion = `TIP: Instead of searching for "function ${functionMatch[1]}", try searching for just "${functionMatch[1]}" (the function name without the keyword).`;
            }
            
            return { result: `No files found matching pattern "${pattern}". ${suggestion}`, error: 'NO_RESULTS' };
          }
          
          // Format SearchResult objects for output
          const formatted = results.slice(0, 10).map((r: any) => {
            const rel = r.path.replace(this.workspaceRoot, '').replace(/^[/\\]/, '');
            let out = `📄 ${rel}`;
            if (r.matchedByName) {
              out += ' (file name matches)';
            } else {
              out += ` — ${r.matchCount} match(es)`;
              if (r.matches?.length) {
                for (const m of r.matches.slice(0, 3)) {
                  out += `\n  Line ${m.line}: ${m.text}`;
                  if (m.snippet) {
                    out += `\n${m.snippet.split('\\n').map((l: string) => '    ' + l).join('\\n')}`;
                  }
                }
                if (r.matches.length > 3) {
                  out += `\n  ... and ${r.matches.length - 3} more match(es)`;
                }
              }
            }
            return out;
          });
          
          // If many results found, suggest reading instead of more searching
          if (results.length > 10) {
            return { result: `Found ${results.length} files matching "${simplePattern}". Here are the first 10:\n\n${formatted.join('\\n\\n')}\n\nTIP: You found many results. Use apply_edits on the shown line numbers, or read_file with offset for full context.`, error: 'MANY_RESULTS' };
          }
          
          return { result: `Found ${results.length} file(s):\n\n${formatted.join('\\n\\n')}\n\nTIP: You found the files! Use apply_edits on the shown line numbers, or read_file with offset for full context.` };
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
          
          // Check for file lock errors (running server locking build files)
          const isFileLockError = output.includes('Unable to delete') || output.includes('file has open') || output.includes('files has open') || output.includes('Access is denied') || output.includes('used by another process');
          if (isFileLockError) {
            this.log('File lock detected in build failure - guiding agent to kill running process');
            (this as any)._pendingMessages = (this as any)._pendingMessages || [];
            (this as any)._pendingMessages.push({ 
              role: 'user', 
              content: `[AUTO] Build failed because files are locked by a running process. Use list_all_terminals to find running servers, then kill_port to free the locked files. Then re-run the build.`
            });
          }
          
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
              // Check for file lock errors (running server locking build files)
              const isFileLockError = output.includes('Unable to delete') || output.includes('file has open') || output.includes('files has open') || output.includes('Access is denied') || output.includes('used by another process');
              if (isFileLockError) {
                this.log('File lock detected in run_terminal build failure - guiding agent to kill running process');
                (this as any)._pendingMessages = (this as any)._pendingMessages || [];
                (this as any)._pendingMessages.push({ 
                  role: 'user', 
                  content: `[AUTO] Build failed because files are locked by a running process. Use list_all_terminals to find running servers, then kill_port to free the locked files. Then re-run the build.`
                });
              }
              
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
        
        case 'kill_port': {
          const port = toolCall.args.port;
          this.log(`Killing process on port ${port}...`);
          
          if (process.platform === 'win32') {
            // Windows: find PID using port, then kill it
            const findPidResult = await this.runCommandWithTimeout(
              `netstat -ano | findstr :${port}`, 5000
            );
            const pidMatch = findPidResult.stdout.match(/\s+(\d+)\s*$/m);
            if (pidMatch) {
              const pid = pidMatch[1];
              const killResult = await this.runCommandWithTimeout(
                `taskkill /PID ${pid} /F`, 5000
              );
              if (killResult.exitCode === 0) {
                return { result: `Killed process ${pid} on port ${port}.` };
              } else {
                return { result: `Failed to kill process ${pid}. ${killResult.stderr}` };
              }
            }
            return { result: `No process found on port ${port}.` };
          } else {
            // Unix/Linux/Mac: lsof to find PID, then kill
            const killResult = await this.runCommandWithTimeout(
              `lsof -ti:${port} | xargs kill -9`, 5000
            );
            if (killResult.exitCode === 0 || killResult.stdout.trim()) {
              return { result: `Killed process on port ${port}.` };
            }
            return { result: `No process found on port ${port}.` };
          }
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
      proc.stdout?.on('data', (data: Buffer) => { stdout += data.toString(); if (onOutput) onOutput(stdout); if (stdout.length > 50000) { stdout = stdout.slice(0, 50000) + '\n... (truncated)'; } });
      proc.stderr?.on('data', (data: Buffer) => { stderr += data.toString(); if (onOutput) onOutput(stderr); });
      proc.on('close', (code: number | null) => { clearTimeout(timeout); exitCode = code; resolve({ stdout, stderr, exitCode }); });
      proc.on('error', (err: Error) => { 
        clearTimeout(timeout);
        const errorMessage = `Command execution failed: ${err.message}`;
        const guidance = command.length > 1500 ? 
          `\n\n💡 For complex commands, consider:\n- Breaking into smaller steps\n- Using write_file + run_terminal pattern\n- Writing scripts to temporary files` : '';
        resolve({ stdout, stderr: errorMessage + guidance, exitCode: -1 });
      });
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
  
  /**
   * Cached file read with mtime-based invalidation.
   * Prevents redundant reads when the model re-reads the same file within a session.
   */
  private async readFileCached(filePath: string): Promise<string> {
    try {
      const stat = await fs.promises.stat(filePath);
      const mtime = stat.mtimeMs;
      const cached = this._fileReadCache.get(filePath);
      
      if (cached && cached.mtime === mtime) {
        this.log(`[CACHE HIT] ${filePath} (${cached.content.length} chars)`);
        return cached.content;
      }
      
      const content = await this.cli.readFile(filePath);
      this._fileReadCache.set(filePath, { mtime, content });
      return content;
    } catch (error: any) {
      // If stat fails, fall back to direct read
      return this.cli.readFile(filePath);
    }
  }
  
  /**
   * Invalidate cached file content after writes.
   */
  private invalidateFileCache(filePath: string): void {
    const hadCache = this._fileReadCache.delete(filePath);
    if (hadCache) {
      this.log(`[CACHE INVALIDATED] ${filePath}`);
    }
  }

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
    if (this.config.systemPromptRules?.rules?.length) {
      for (const rule of this.config.systemPromptRules.rules) {
        prompt += '\n• ' + rule;
      }
    } else {
      // Default hardcoded rules for backward compatibility
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
    }
    return prompt;
  }
}
