/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

/**
 * AgentStateMachine - Robust state machine for LLM task lifecycle
 * 
 * Comprehensive Flow: Intent → Plan → Constraints → Sequence → Output
 * 
 * States:
 *   IDLE → INTENT → PLAN → CONSTRAINTS → SEQUENCE → EXECUTE → VERIFY → COMPLETE
 *                    ↓          ↓            ↓          ↓         ↓
 *                  REFINE    VALIDATE     ADJUST     RETRY    FAILED
 * 
 * Each state has:
 *   - Entry conditions (guards)
 *   - Exit conditions (transitions)
 *   - Allowed actions
 *   - Constraints validation
 *   - Context mutations
 */

export enum AgentState {
  /** Initial state, waiting for user input */
  IDLE = 'IDLE',
  
  /** Understanding user intent, classifying task type, gathering initial context */
  INTENT = 'INTENT',
  
  /** LLM is formulating a plan (analyzing task, selecting tools) */
  PLAN = 'PLAN',
  
  /** Validating plan against constraints (safety, resource limits, tool availability) */
  CONSTRAINTS = 'CONSTRAINTS',
  
  /** Ordering tool calls into optimal execution sequence */
  SEQUENCE = 'SEQUENCE',
  
  /** Executing tool calls from the LLM */
  EXECUTE = 'EXECUTE',
  
  /** Verifying build/test/server results */
  VERIFY = 'VERIFY',
  
  /** Task completed successfully */
  COMPLETE = 'COMPLETE',
  
  /** Task failed unrecoverably */
  FAILED = 'FAILED',
  
  /** Paused waiting for user confirmation (for destructive operations) */
  PAUSED = 'PAUSED',
}

export enum AgentEvent {
  /** User submitted input */
  USER_INPUT = 'USER_INPUT',
  
  /** Intent classified (explore/create/debug/refactor/test/run) */
  INTENT_CLASSIFIED = 'INTENT_CLASSIFIED',
  
  /** LLM returned tool calls */
  TOOL_CALLS_RECEIVED = 'TOOL_CALLS_RECEIVED',
  
  /** LLM returned text only (no tool calls) */
  TEXT_ONLY = 'TEXT_ONLY',
  
  /** LLM returned plan-only text (describing what it will do) */
  PLAN_ONLY = 'PLAN_ONLY',
  
  /** Plan validated against constraints */
  PLAN_VALIDATED = 'PLAN_VALIDATED',
  
  /** Plan failed constraint validation */
  PLAN_INVALID = 'PLAN_INVALID',
  
  /** Sequence determined for tool execution */
  SEQUENCE_READY = 'SEQUENCE_READY',
  
  /** Tool execution completed */
  TOOLS_EXECUTED = 'TOOLS_EXECUTED',
  
  /** Individual tool completed */
  TOOL_COMPLETED = 'TOOL_COMPLETED',
  
  /** Individual tool failed */
  TOOL_FAILED = 'TOOL_FAILED',
  
  /** Build/compilation succeeded */
  BUILD_SUCCESS = 'BUILD_SUCCESS',
  
  /** Build/compilation failed */
  BUILD_FAILURE = 'BUILD_FAILURE',
  
  /** Server startup failed */
  SERVER_FAILURE = 'SERVER_FAILURE',
  
  /** Server started successfully */
  SERVER_STARTED = 'SERVER_STARTED',
  
  /** Edit applied successfully */
  EDIT_SUCCESS = 'EDIT_SUCCESS',
  
  /** Edit failed (search text not found) */
  EDIT_FAILURE = 'EDIT_FAILURE',
  
  /** File written successfully */
  WRITE_SUCCESS = 'WRITE_SUCCESS',
  
  /** File write failed */
  WRITE_FAILURE = 'WRITE_FAILURE',
  
  /** Repeated same tool call detected */
  LOOP_DETECTED = 'LOOP_DETECTED',
  
  /** Search loop detected (same pattern multiple times) */
  SEARCH_LOOP = 'SEARCH_LOOP',
  
  /** Max iterations reached */
  MAX_ITERATIONS = 'MAX_ITERATIONS',
  
  /** Max consecutive failures reached */
  MAX_FAILURES = 'MAX_FAILURES',
  
  /** User cancelled */
  CANCELLED = 'CANCELLED',
  
  /** User confirmed action (from PAUSED state) */
  USER_CONFIRMED = 'USER_CONFIRMED',
  
  /** User rejected action (from PAUSED state) */
  USER_REJECTED = 'USER_REJECTED',
  
  /** Timeout occurred */
  TIMEOUT = 'TIMEOUT',
  
  /** Retry requested */
  RETRY = 'RETRY',
}

export interface ToolCallRecord {
  toolName: string;
  args: Record<string, any>;
  result?: string;
  error?: string;
  durationMs?: number;
  iteration: number;
  sequenceIndex: number;
}

export interface StateContext {
  /** Current iteration number */
  iteration: number;
  
  /** Number of consecutive successful edits */
  consecutiveEdits: number;
  
  /** Number of consecutive plan-only responses */
  consecutivePlans: number;
  
  /** Number of build failures in a row */
  buildFailures: number;
  
  /** Number of failed edit attempts on current file */
  failedEditAttempts: number;
  
  /** Files pending fix (from build errors) */
  pendingFixes: string[];
  
  /** Last build error output */
  lastBuildErrors: string;
  
  /** Whether a server was just started */
  serverJustStarted: string | null;
  
  /** Last search pattern (for loop detection) */
  lastSearchPattern: string | null;
  
  /** Last search results (for loop detection) */
  lastSearchFiles: string[];
  
  /** Whether we're in a plan-only loop */
  inPlanLoop: boolean;
  
  /** Whether we're in a build-fix cycle */
  inBuildFixCycle: boolean;
  
  /** Whether we're in a search loop */
  inSearchLoop: boolean;
  
  /** Whether build failed due to environmental issue (file lock, not code) */
  inEnvironmentalError: boolean;
  
  /** Detected intent type (explore/create/debug/refactor/test/run) */
  intentType: string;
  
  /** Current task description */
  taskDescription: string;
  
  /** Validated tool calls ready for execution */
  validatedToolCalls: Array<{ toolName: string; args: Record<string, any>; toolCallId: string }>;
  
  /** Execution sequence (ordered tool calls) */
  executionSequence: Array<{ toolName: string; args: Record<string, any>; toolCallId: string; dependencies?: string[] }>;
  
  /** Tool call history for loop detection */
  toolCallHistory: ToolCallRecord[];
  
  /** Constraints validation results */
  constraintsValidation: {
    passed: boolean;
    violations: string[];
    warnings: string[];
    safetyFlags?: {
      requiresConfirmation: boolean;
      isDestructive: boolean;
      modifiesFiles: boolean;
      runsExternalProcess: boolean;
    };
  };
  
  /** Safety flags */
  safetyFlags: {
    requiresConfirmation: boolean;
    isDestructive: boolean;
    modifiesFiles: boolean;
    runsExternalProcess: boolean;
  };
  
  /** Performance metrics */
  metrics: {
    totalToolCalls: number;
    successfulToolCalls: number;
    failedToolCalls: number;
    averageToolDurationMs: number;
    totalIterations: number;
  };
}

export function createInitialContext(): StateContext {
  return {
    iteration: 0,
    consecutiveEdits: 0,
    consecutivePlans: 0,
    buildFailures: 0,
    failedEditAttempts: 0,
    pendingFixes: [],
    lastBuildErrors: '',
    serverJustStarted: null,
    lastSearchPattern: null,
    lastSearchFiles: [],
    inPlanLoop: false,
    inBuildFixCycle: false,
    inSearchLoop: false,
    inEnvironmentalError: false,
    intentType: 'default',
    taskDescription: '',
    validatedToolCalls: [],
    executionSequence: [],
    toolCallHistory: [],
    constraintsValidation: {
      passed: true,
      violations: [],
      warnings: [],
    },
    safetyFlags: {
      requiresConfirmation: false,
      isDestructive: false,
      modifiesFiles: false,
      runsExternalProcess: false,
    },
    metrics: {
      totalToolCalls: 0,
      successfulToolCalls: 0,
      failedToolCalls: 0,
      averageToolDurationMs: 0,
      totalIterations: 0,
    },
  };
}

export interface StateTransition {
  from: AgentState;
  to: AgentState;
  event: AgentEvent;
  guard?: (ctx: StateContext) => boolean;
  action?: (ctx: StateContext) => Partial<StateContext>;
  description?: string;
}

/**
 * CONSTRAINTS VALIDATION
 * Validates tool calls against safety and resource constraints
 */
function validateConstraints(toolCalls: Array<{ toolName: string; args: Record<string, any> }>, ctx: StateContext): {
  passed: boolean;
  violations: string[];
  warnings: string[];
  safetyFlags: StateContext['safetyFlags'];
} {
  const violations: string[] = [];
  const warnings: string[] = [];
  const safetyFlags: StateContext['safetyFlags'] = {
    requiresConfirmation: false,
    isDestructive: false,
    modifiesFiles: false,
    runsExternalProcess: false,
  };

  for (const toolCall of toolCalls) {
    const { toolName, args } = toolCall;

    // Check for destructive commands
    if (toolName === 'run_terminal' || toolName === 'run_build') {
      const command = args.command || '';
      safetyFlags.runsExternalProcess = true;

      if (command.includes('rm -rf') || command.includes('del /F') || command.includes('format')) {
        violations.push(`Destructive command blocked: ${command}`);
        safetyFlags.isDestructive = true;
        safetyFlags.requiresConfirmation = true;
      }

      if (command.includes('sudo') || command.includes('runas')) {
        warnings.push(`Command requires elevated privileges: ${command}`);
        safetyFlags.requiresConfirmation = true;
      }
    }

    // Check for file modifications
    if (toolName === 'write_file' || toolName === 'apply_edits') {
      safetyFlags.modifiesFiles = true;
      
      // Check edit count limits
      if (toolName === 'apply_edits' && args.edits) {
        const editCount = Array.isArray(args.edits) ? args.edits.length : 0;
        if (editCount > 50) {
          violations.push(`Too many edits (${editCount}). Maximum 50 edits per apply_edits call. Use write_file for large changes.`);
        }
      }
    }

    // Check for search loops
    if (toolName === 'search_files') {
      const pattern = args.pattern;
      if (ctx.lastSearchPattern === pattern && ctx.lastSearchFiles.length > 0) {
        warnings.push(`Search pattern "${pattern}" already used. Found ${ctx.lastSearchFiles.length} files previously.`);
      }
    }
  }

  return {
    passed: violations.length === 0,
    violations,
    warnings,
    safetyFlags,
  };
}

/**
 * SEQUENCE OPTIMIZATION
 * Orders tool calls for optimal execution
 */
function optimizeSequence(toolCalls: Array<{ toolName: string; args: Record<string, any>; toolCallId: string }>): Array<{
  toolName: string;
  args: Record<string, any>;
  toolCallId: string;
  dependencies?: string[];
}> {
  // Priority order: read operations → write operations → build/test → run
  const priorityOrder: Record<string, number> = {
    'read_file': 1,
    'list_directory': 1,
    'search_files': 1,
    'get_file_context': 1,
    'git_status': 1,
    'git_diff': 1,
    'apply_edits': 2,
    'write_file': 2,
    'revert_file': 2,
    'run_build': 3,
    'run_terminal': 4,
  };

  return toolCalls
    .map(tc => ({
      ...tc,
      _priority: priorityOrder[tc.toolName] || 5,
    }))
    .sort((a, b) => a._priority - b._priority)
    .map(({ _priority, ...tc }) => tc);
}

/**
 * State machine definition
 * 
 * Comprehensive flow:
 *   IDLE ──USER_INPUT──→ INTENT
 *   INTENT ──INTENT_CLASSIFIED──→ PLAN
 *   PLAN ──TOOL_CALLS──→ CONSTRAINTS
 *   PLAN ──TEXT_ONLY──→ COMPLETE
 *   PLAN ──PLAN_ONLY──→ PLAN (nudge to use tools)
 *   CONSTRAINTS ──PLAN_VALIDATED──→ SEQUENCE
 *   CONSTRAINTS ──PLAN_INVALID──→ PLAN (with violations)
 *   SEQUENCE ──SEQUENCE_READY──→ EXECUTE
 *   EXECUTE ──TOOLS_EXECUTED──→ VERIFY
 *   VERIFY ──BUILD_SUCCESS──→ COMPLETE
 *   VERIFY ──BUILD_FAILURE──→ PLAN (with errors context)
 *   VERIFY ──SERVER_FAILURE──→ PLAN (with errors, no fixMode)
 *   VERIFY ──SERVER_STARTED──→ COMPLETE (server running)
 *   VERIFY ──EDIT_SUCCESS──→ PLAN (continue editing)
 *   VERIFY ──EDIT_FAILURE──→ PLAN (retry with correct text)
 *   Any ──LOOP_DETECTED──→ FAILED
 *   Any ──MAX_ITERATIONS──→ FAILED
 *   Any ──CANCELLED──→ FAILED
 */
export const TRANSITIONS: StateTransition[] = [
  // IDLE → INTENT
  { 
    from: AgentState.IDLE, 
    to: AgentState.INTENT, 
    event: AgentEvent.USER_INPUT,
    description: 'User input received, classifying intent',
    action: (ctx) => ({ 
      iteration: ctx.iteration + 1,
      metrics: { ...ctx.metrics, totalIterations: ctx.metrics.totalIterations + 1 },
    }),
  },
  
  // INTENT → PLAN
  { 
    from: AgentState.INTENT, 
    to: AgentState.PLAN, 
    event: AgentEvent.INTENT_CLASSIFIED,
    description: 'Intent classified, ready for planning',
  },
  
  // PLAN → CONSTRAINTS (LLM returned tool calls)
  { 
    from: AgentState.PLAN, 
    to: AgentState.CONSTRAINTS, 
    event: AgentEvent.TOOL_CALLS_RECEIVED,
    description: 'Tool calls received, validating constraints',
    action: (ctx) => ({
      consecutivePlans: 0, // Reset plan counter on valid tool calls
    }),
  },
  
  // PLAN → COMPLETE (LLM answered naturally, no tools needed)
  { 
    from: AgentState.PLAN, 
    to: AgentState.COMPLETE, 
    event: AgentEvent.TEXT_ONLY,
    description: 'Natural language response, task complete',
  },
  
  // PLAN → PLAN (LLM described plans instead of calling tools)
  { 
    from: AgentState.PLAN, 
    to: AgentState.PLAN, 
    event: AgentEvent.PLAN_ONLY,
    description: 'Plan-only response, nudging to use tools',
    action: (ctx) => ({ 
      consecutivePlans: ctx.consecutivePlans + 1,
      inPlanLoop: (ctx.consecutivePlans + 1) >= 2, // Check NEW value
    }),
  },
  
  // CONSTRAINTS → SEQUENCE (plan validated)
  { 
    from: AgentState.CONSTRAINTS, 
    to: AgentState.SEQUENCE, 
    event: AgentEvent.PLAN_VALIDATED,
    description: 'Constraints validated, optimizing sequence',
    action: (ctx) => ({
      constraintsValidation: { passed: true, violations: [], warnings: [] },
    }),
  },
  
  // CONSTRAINTS → PLAN (plan failed validation)
  { 
    from: AgentState.CONSTRAINTS, 
    to: AgentState.PLAN, 
    event: AgentEvent.PLAN_INVALID,
    description: 'Constraints violated, returning to planning',
    action: (ctx) => ({
      constraintsValidation: { ...ctx.constraintsValidation, passed: false },
    }),
  },
  
  // SEQUENCE → EXECUTE (sequence ready)
  { 
    from: AgentState.SEQUENCE, 
    to: AgentState.EXECUTE, 
    event: AgentEvent.SEQUENCE_READY,
    description: 'Sequence optimized, executing tools',
    action: (ctx) => ({
      executionSequence: [], // Clear after moving to execute
    }),
  },
  
  // EXECUTE → VERIFY (tools executed)
  { 
    from: AgentState.EXECUTE, 
    to: AgentState.VERIFY, 
    event: AgentEvent.TOOLS_EXECUTED,
    description: 'Tools executed, verifying results',
    action: (ctx) => ({
      metrics: {
        ...ctx.metrics,
        totalToolCalls: ctx.metrics.totalToolCalls + ctx.validatedToolCalls.length,
      },
    }),
  },
  
  // EXECUTE → PLAN (individual tool failed, retry)
  { 
    from: AgentState.EXECUTE, 
    to: AgentState.PLAN, 
    event: AgentEvent.TOOL_FAILED,
    description: 'Tool failed, replanning',
  },
  
  // VERIFY → COMPLETE (build passed)
  { 
    from: AgentState.VERIFY, 
    to: AgentState.COMPLETE, 
    event: AgentEvent.BUILD_SUCCESS,
    description: 'Build successful, task complete',
    action: (ctx) => ({
      buildFailures: 0,
      consecutiveEdits: 0,
      inBuildFixCycle: false,
    }),
  },
  
  // VERIFY → COMPLETE (server started)
  { 
    from: AgentState.VERIFY, 
    to: AgentState.COMPLETE, 
    event: AgentEvent.SERVER_STARTED,
    description: 'Server started successfully',
    action: (ctx) => ({
      serverJustStarted: null,
    }),
  },
  
  // VERIFY → PLAN (build failed, go back to planning fixes)
  { 
    from: AgentState.VERIFY, 
    to: AgentState.PLAN, 
    event: AgentEvent.BUILD_FAILURE,
    description: 'Build failed, planning fixes',
    guard: (ctx) => !ctx.inEnvironmentalError, // Don't enter fix mode for environmental errors
    action: (ctx) => ({
      buildFailures: ctx.buildFailures + 1,
      consecutiveEdits: 0,
      inBuildFixCycle: true,
    }),
  },
  
  // VERIFY → PLAN (build failed due to file lock - don't enter fix mode)
  { 
    from: AgentState.VERIFY, 
    to: AgentState.PLAN, 
    event: AgentEvent.BUILD_FAILURE,
    guard: (ctx) => ctx.inEnvironmentalError,
    description: 'Build failed due to file lock - environmental issue',
    action: (ctx) => ({
      buildFailures: ctx.buildFailures + 1,
      consecutiveEdits: 0,
      inBuildFixCycle: false, // Don't enter code fix mode
      inEnvironmentalError: true,
    }),
  },
  
  // PLAN → PLAN (build failure received while in plan state - increment counter)
  {
    from: AgentState.PLAN,
    to: AgentState.PLAN,
    event: AgentEvent.BUILD_FAILURE,
    guard: (ctx) => !ctx.inEnvironmentalError,
    description: 'Build failure tracked in plan state',
    action: (ctx) => ({
      buildFailures: ctx.buildFailures + 1,
      inBuildFixCycle: true,
    }),
  },
  
  // PLAN → PLAN (build failure due to file lock - reset environmental flag)
  {
    from: AgentState.PLAN,
    to: AgentState.PLAN,
    event: AgentEvent.BUILD_FAILURE,
    guard: (ctx) => ctx.inEnvironmentalError,
    description: 'Build failure (environmental) - reset flag',
    action: (ctx) => ({
      buildFailures: ctx.buildFailures + 1,
      inEnvironmentalError: false, // Reset for next build attempt
    }),
  },
  
  // VERIFY → PLAN (server failed, go back without fixMode)
  { 
    from: AgentState.VERIFY, 
    to: AgentState.PLAN, 
    event: AgentEvent.SERVER_FAILURE,
    description: 'Server failed, replanning',
    action: (ctx) => ({
      consecutiveEdits: 0,
      inBuildFixCycle: false,
    }),
  },
  
  // VERIFY → PLAN (edit succeeded, continue)
  { 
    from: AgentState.VERIFY, 
    to: AgentState.PLAN, 
    event: AgentEvent.EDIT_SUCCESS,
    description: 'Edit successful, continuing',
    action: (ctx) => ({
      consecutiveEdits: ctx.consecutiveEdits + 1,
      failedEditAttempts: 0,
    }),
  },
  
  // PLAN → PLAN (edit succeeded in plan loop - for consecutive edits tracking)
  { 
    from: AgentState.PLAN, 
    to: AgentState.PLAN, 
    event: AgentEvent.EDIT_SUCCESS,
    description: 'Edit successful in plan state, tracking consecutive edits',
    action: (ctx) => ({
      consecutiveEdits: ctx.consecutiveEdits + 1,
      failedEditAttempts: 0,
    }),
  },
  
  // VERIFY → PLAN (edit failed, retry)
  { 
    from: AgentState.VERIFY, 
    to: AgentState.PLAN, 
    event: AgentEvent.EDIT_FAILURE,
    description: 'Edit failed, retrying',
    action: (ctx) => ({
      failedEditAttempts: ctx.failedEditAttempts + 1,
      consecutiveEdits: 0,
    }),
  },
  
  // VERIFY → PLAN (write succeeded)
  { 
    from: AgentState.VERIFY, 
    to: AgentState.PLAN, 
    event: AgentEvent.WRITE_SUCCESS,
    description: 'Write successful, continuing',
    action: (ctx) => ({
      consecutiveEdits: ctx.consecutiveEdits + 1,
      failedEditAttempts: 0,
    }),
  },
  
  // Any → PAUSED (requires confirmation)
  { 
    from: AgentState.CONSTRAINTS, 
    to: AgentState.PAUSED, 
    event: AgentEvent.PLAN_INVALID,
    guard: (ctx) => ctx.constraintsValidation.safetyFlags?.requiresConfirmation ?? false,
    description: 'Action requires user confirmation',
  },
  
  // PAUSED → EXECUTE (user confirmed)
  { 
    from: AgentState.PAUSED, 
    to: AgentState.EXECUTE, 
    event: AgentEvent.USER_CONFIRMED,
    description: 'User confirmed, proceeding with execution',
  },
  
  // PAUSED → PLAN (user rejected)
  { 
    from: AgentState.PAUSED, 
    to: AgentState.PLAN, 
    event: AgentEvent.USER_REJECTED,
    description: 'User rejected, replanning',
  },
  
  // Any → FAILED (loop detected)
  { 
    from: AgentState.PLAN, 
    to: AgentState.FAILED, 
    event: AgentEvent.LOOP_DETECTED,
    description: 'Loop detected, failing',
  },
  { 
    from: AgentState.EXECUTE, 
    to: AgentState.FAILED, 
    event: AgentEvent.LOOP_DETECTED,
    description: 'Loop detected during execution',
  },
  {
    from: AgentState.PLAN,
    to: AgentState.FAILED,
    event: AgentEvent.SEARCH_LOOP,
    description: 'Search loop detected',
  },
  
  // Any → FAILED (max iterations)
  { 
    from: AgentState.PLAN, 
    to: AgentState.FAILED, 
    event: AgentEvent.MAX_ITERATIONS,
    description: 'Max iterations reached',
  },
  { 
    from: AgentState.EXECUTE, 
    to: AgentState.FAILED, 
    event: AgentEvent.MAX_ITERATIONS,
    description: 'Max iterations during execution',
  },
  { 
    from: AgentState.VERIFY, 
    to: AgentState.FAILED, 
    event: AgentEvent.MAX_ITERATIONS,
    description: 'Max iterations during verification',
  },
  
  // Any → FAILED (max failures)
  { 
    from: AgentState.VERIFY, 
    to: AgentState.FAILED, 
    event: AgentEvent.MAX_FAILURES,
    description: 'Max consecutive failures reached',
  },
  {
    from: AgentState.PLAN,
    to: AgentState.FAILED,
    event: AgentEvent.MAX_FAILURES,
    description: 'Max consecutive failures reached from PLAN',
  },
  
  // Any → FAILED (cancelled)
  { 
    from: AgentState.PLAN, 
    to: AgentState.FAILED, 
    event: AgentEvent.CANCELLED,
    description: 'User cancelled',
  },
  { 
    from: AgentState.EXECUTE, 
    to: AgentState.FAILED, 
    event: AgentEvent.CANCELLED,
    description: 'User cancelled during execution',
  },
  { 
    from: AgentState.VERIFY, 
    to: AgentState.FAILED, 
    event: AgentEvent.CANCELLED,
    description: 'User cancelled during verification',
  },
  
  // Any → FAILED (timeout)
  { 
    from: AgentState.EXECUTE, 
    to: AgentState.FAILED, 
    event: AgentEvent.TIMEOUT,
    description: 'Tool execution timeout',
  },
  
  // FAILED → IDLE (retry)
  { 
    from: AgentState.FAILED, 
    to: AgentState.IDLE, 
    event: AgentEvent.RETRY,
    description: 'Retrying from failure',
    action: (ctx) => createInitialContext(),
  },
];

/**
 * AgentStateMachine - Manages state transitions and context
 * 
 * Provides comprehensive flow control for LLM task lifecycle:
 *   1. INTENT: Classify user input, determine task type
 *   2. PLAN: LLM formulates tool calls
 *   3. CONSTRAINTS: Validate safety, resource limits, policies
 *   4. SEQUENCE: Optimize tool execution order
 *   5. EXECUTE: Run tools with monitoring
 *   6. VERIFY: Check results, determine next state
 */
export class AgentStateMachine {
  private _state: AgentState = AgentState.IDLE;
  private _context: StateContext;
  private _history: Array<{ 
    from: AgentState; 
    to: AgentState; 
    event: AgentEvent; 
    timestamp: number;
    description?: string;
  }> = [];
  
  constructor() {
    this._context = createInitialContext();
  }
  
  get state(): AgentState { return this._state; }
  get context(): StateContext { return { ...this._context }; }
  get history(): ReadonlyArray<{ 
    from: AgentState; 
    to: AgentState; 
    event: AgentEvent; 
    timestamp: number;
    description?: string;
  }> {
    return this._history;
  }
  
  /** Reset to initial state */
  reset(): void {
    this._state = AgentState.IDLE;
    this._context = createInitialContext();
    this._history = [];
  }
  
  /** Send an event to the state machine */
  dispatch(
    event: AgentEvent, 
    contextUpdates?: Partial<StateContext>
  ): { state: AgentState; context: StateContext; transition?: StateTransition } {
    // Find matching transitions
    const transitions = TRANSITIONS.filter(t => t.from === this._state && t.event === event);
    
    if (transitions.length === 0) {
      // No valid transition - stay in current state
      console.log(`[StateMachine] No transition from ${this._state} on ${event}`);
      return { state: this._state, context: this._context };
    }
    
    // Try each transition (first matching guard wins)
    for (const transition of transitions) {
      // Check guard
      if (transition.guard && !transition.guard(this._context)) {
        continue;
      }
      
      // Apply action
      if (transition.action) {
        const updates = transition.action(this._context);
        this._context = { ...this._context, ...updates };
      }
      
      // Apply context updates from caller
      if (contextUpdates) {
        this._context = { ...this._context, ...contextUpdates };
      }
      
      // Record transition
      this._history.push({
        from: this._state,
        to: transition.to,
        event,
        timestamp: Date.now(),
        description: transition.description,
      });
      
      // Update state
      const previousState = this._state;
      this._state = transition.to;
      
      console.log(`[StateMachine] ${previousState} ──${event}──→ ${this._state}${transition.description ? ` (${transition.description})` : ''}`);
      
      return { state: this._state, context: this._context, transition };
    }
    
    // No transition matched (guard failed)
    console.log(`[StateMachine] No matching transition from ${this._state} on ${event} (guard failed)`);
    return { state: this._state, context: this._context };
  }
  
  /** Convenience: check if in a specific state */
  is(state: AgentState): boolean {
    return this._state === state;
  }
  
  /** Convenience: check if in any of the given states */
  isAny(...states: AgentState[]): boolean {
    return states.includes(this._state);
  }
  
  /** Convenience: check if terminal state */
  isTerminal(): boolean {
    return this._state === AgentState.COMPLETE || this._state === AgentState.FAILED;
  }
  
  /** Get the number of transitions to a specific state */
  countTransitionsTo(state: AgentState): number {
    return this._history.filter(h => h.to === state).length;
  }
  
  /** Get the last event that caused a transition */
  get lastEvent(): AgentEvent | null {
    if (this._history.length === 0) return null;
    return this._history[this._history.length - 1].event;
  }
  
  /** Get the last N state transitions */
  getLastTransitions(n: number): Array<{ from: AgentState; to: AgentState; event: AgentEvent; timestamp: number }> {
    return this._history.slice(-n);
  }
  
  /** Update context directly (for external state changes) */
  updateContext(updates: Partial<StateContext>): void {
    this._context = { ...this._context, ...updates };
  }
  
  /** Increment iteration counter */
  incrementIteration(): void {
    this._context.iteration++;
  }
  
  /** Classify user intent from input */
  classifyIntent(userInput: string): string {
    const input = userInput.toLowerCase();
    if (/\b(refactor|rename|extract|move)\b/i.test(input)) return 'refactor';
    if (/\b(debug|fix|bug|error|crash|fail)\b/i.test(input)) return 'debug';
    if (/\b(explain|what|how|explore|find|show)\b/i.test(input)) return 'explore';
    // Check for test intent BEFORE create (since "write tests" contains "write")
    if (/\b(test|tests|testing|spec|unit|integration)\b/i.test(input)) return 'test';
    if (/\b(write|create|add|implement|build|generate)\b/i.test(input)) return 'create';
    if (/\b(run|start|serve|launch)\b/i.test(input)) return 'run';
    return 'default';
  }
  
  /** Determine event from LLM response */
  classifyLLMResponse(
    text: string,
    hasToolCalls: boolean,
    context: StateContext
  ): AgentEvent {
    if (hasToolCalls) {
      return AgentEvent.TOOL_CALLS_RECEIVED;
    }
    
    const trimmed = text.trim();
    
    // Check for plan-only (describing intent without calling tools)
    const isPlanOnly = trimmed.length < 200 && (
      /^(I will|I'll|Let me|First,? I)/i.test(trimmed) ||
      trimmed.toLowerCase().includes('calling ') ||
      trimmed.toLowerCase().includes('going to') ||
      trimmed.toLowerCase().includes('i need to')
    );
    
    if (isPlanOnly) {
      return AgentEvent.PLAN_ONLY;
    }
    
    // Natural text response (answer)
    return AgentEvent.TEXT_ONLY;
  }
  
  /** Determine event from tool execution result */
  classifyToolResult(
    toolName: string,
    result: string,
    error: string | undefined
  ): { event: AgentEvent; contextUpdates?: Partial<StateContext> } {
    
    // Build commands
    if (toolName === 'run_build' || (toolName === 'run_terminal' && result.includes('BUILD'))) {
      // Check for file lock errors FIRST (environmental issue, not code error)
      const isFileLockError = result.includes('Unable to delete') || 
                              result.includes('file has open') || 
                              result.includes('files has open') || 
                              result.includes('Access is denied') || 
                              result.includes('used by another process');
      
      if (isFileLockError) {
        return { 
          event: AgentEvent.BUILD_FAILURE,
          contextUpdates: {
            inEnvironmentalError: true,
            lastBuildErrors: 'FILE_LOCKED: A running process is holding build files. Kill the server first before fixing code.',
            inBuildFixCycle: false // Don't enter code fix mode - this is an environmental issue
          }
        };
      }
      
      if (result.includes('✅') || result.includes('BUILD SUCCESSFUL')) {
        return { event: AgentEvent.BUILD_SUCCESS, contextUpdates: { inEnvironmentalError: false } };
      }
      if (result.includes('❌') || result.includes('BUILD FAILED')) {
        return { 
          event: AgentEvent.BUILD_FAILURE,
          contextUpdates: { inEnvironmentalError: false }
        };
      }
    }
    
    // Server commands
    if (toolName === 'run_terminal' && (result.includes('server') || result.includes('Server'))) {
      if (result.includes('❌') || result.includes('BUILD FAILED') || result.includes('FAILED')) {
        return { event: AgentEvent.SERVER_FAILURE };
      }
      if (result.includes('running') || result.includes('started')) {
        return { event: AgentEvent.SERVER_STARTED };
      }
    }
    
    // Edit commands
    if (toolName === 'apply_edits' || toolName === 'write_file') {
      if (result.includes('✅') || result.includes('Successfully') || result.includes('Applied')) {
        return { event: toolName === 'write_file' ? AgentEvent.WRITE_SUCCESS : AgentEvent.EDIT_SUCCESS };
      }
      if (result.includes('❌') || result.includes('failed') || error) {
        return { event: toolName === 'write_file' ? AgentEvent.WRITE_FAILURE : AgentEvent.EDIT_FAILURE };
      }
    }
    
    // Default: tools executed, go to verify
    return { event: AgentEvent.TOOLS_EXECUTED };
  }
  
  /** Validate tool calls against constraints */
  validateConstraints(
    toolCalls: Array<{ toolName: string; args: Record<string, any> }>
  ): { passed: boolean; violations: string[]; warnings: string[]; safetyFlags: StateContext['safetyFlags'] } {
    return validateConstraints(toolCalls, this._context);
  }
  
  /** Optimize tool execution sequence */
  optimizeSequence(
    toolCalls: Array<{ toolName: string; args: Record<string, any>; toolCallId: string }>
  ): Array<{ toolName: string; args: Record<string, any>; toolCallId: string; dependencies?: string[] }> {
    return optimizeSequence(toolCalls);
  }
  
  /** Check if we should force build verification */
  shouldForceBuild(): boolean {
    return this._context.consecutiveEdits >= 2 && !this._context.inBuildFixCycle;
  }
  
  /** Check if we're in a plan loop that needs intervention */
  isPlanLoopStuck(): boolean {
    return this._context.inPlanLoop && this._context.consecutivePlans >= 3;
  }
  
  /** Check if we're in a build-fix cycle that needs intervention */
  isBuildFixCycleStuck(): boolean {
    return this._context.inBuildFixCycle && this._context.buildFailures >= 3;
  }
  
  /** Check if edit failures are excessive */
  isEditFailureStuck(): boolean {
    return this._context.failedEditAttempts >= 3;
  }
  
  /** Check if search is looping */
  isSearchLooping(pattern: string): boolean {
    return this._context.lastSearchPattern === pattern && this._context.lastSearchFiles.length > 0;
  }
  
  /** Get the tool filter mode based on state */
  getToolFilter(): 'all' | 'fix_only' | 'read_only' | 'action_only' {
    if (this._state === AgentState.COMPLETE || this._state === AgentState.FAILED) {
      return 'read_only';
    }
    if (this._context.inPlanLoop && this._context.consecutivePlans >= 2) {
      return 'fix_only'; // Only editing tools when stuck in plan loop
    }
    return 'all';
  }
  
  /** Record tool call for history and loop detection */
  recordToolCall(toolName: string, args: Record<string, any>, result?: string, error?: string, durationMs?: number): void {
    const record: ToolCallRecord = {
      toolName,
      args,
      result,
      error,
      durationMs,
      iteration: this._context.iteration,
      sequenceIndex: this._context.toolCallHistory.length,
    };
    
    this._context.toolCallHistory.push(record);
    
    // Update metrics
    this._context.metrics.totalToolCalls++;
    if (error) {
      this._context.metrics.failedToolCalls++;
    } else {
      this._context.metrics.successfulToolCalls++;
    }
    
    // Recalculate average duration
    const totalDuration = this._context.toolCallHistory
      .filter(t => t.durationMs !== undefined)
      .reduce((sum, t) => sum + (t.durationMs || 0), 0);
    const countWithDuration = this._context.toolCallHistory.filter(t => t.durationMs !== undefined).length;
    this._context.metrics.averageToolDurationMs = countWithDuration > 0 ? totalDuration / countWithDuration : 0;
  }
  
  /** Check for tool call loops */
  detectLoop(toolName: string, args: Record<string, any>, iteration: number): boolean {
    // EXCLUDE polling/status-checking tools from loop detection
    // These are legitimate repeated calls while waiting for state changes
    const pollingTools = [
      'terminal_status',
      'get_terminal_output',
      'run_build',
      'get_build_status',
      'check_server',
      'get_server_status',
      // Git tools: calling git_diff/git_status repeatedly to check workspace
      // state after edits is a normal workflow, not a loop.
      'git_diff',
      'git_status',
      'git_log',
      'git_branch',
    ];
    
    const normalizedToolName = toolName.toLowerCase().replace(/[_-]/g, '');
    if (pollingTools.some(t => t.toLowerCase().replace(/[_-]/g, '') === normalizedToolName)) {
      return false; // Don't flag polling as loops
    }
    
    const argsSignature = JSON.stringify(args);
    
    // For tools with no args (empty object), don't count as a loop until
    // the 5th repetition. Empty args means the tool has no configurable
    // parameters — calling it multiple times is often legitimate.
    const isEmptyArgs = argsSignature === '{}';
    const loopThreshold = isEmptyArgs ? 5 : 2;
    const hasRecentIterations = this._context.toolCallHistory.some(h => h.iteration >= iteration - 2);
    
    const recentCalls = this._context.toolCallHistory.filter(h => {
      const iterationMatch = hasRecentIterations ? h.iteration >= iteration - 2 : true;
      const toolNameMatch = h.toolName.toLowerCase().replace(/[_-]/g, '') === normalizedToolName;
      const argsMatch = JSON.stringify(h.args) === argsSignature;
      return iterationMatch && toolNameMatch && argsMatch;
    });
    
    // If we're checking for a loop and already have N+ matching calls in history, it's a loop
    // The current call being checked is not yet in history, so we check for >= threshold
    return recentCalls.length >= loopThreshold;
  }
  
  /** Get state diagram as text */
  getStateDiagram(): string {
    return `
Agent State Machine Flow:

IDLE ──USER_INPUT──→ INTENT ──INTENT_CLASSIFIED──→ PLAN
                                              │
                                              ├──TOOL_CALLS──→ CONSTRAINTS ──VALIDATED──→ SEQUENCE ──READY──→ EXECUTE ──TOOLS_EXECUTED──→ VERIFY
                                              │                                            │                                              │
                                              ├──TEXT_ONLY──→ COMPLETE                     ├──INVALID──→ PLAN                               ├──BUILD_SUCCESS──→ COMPLETE
                                              │                                                                                             ├──BUILD_FAILURE──→ PLAN
                                              └──PLAN_ONLY──→ PLAN                                                                                          ├──SERVER_FAILURE──→ PLAN
                                                                                                                                                            ├──EDIT_SUCCESS──→ PLAN
                                                                                                                                                            └──EDIT_FAILURE──→ PLAN
    `;
  }
}
