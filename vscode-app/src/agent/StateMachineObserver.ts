/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

/**
 * StateMachineObserver - Monitor and debug AgentStateMachine
 * 
 * Provides real-time state monitoring, metrics tracking, and debugging capabilities.
 */

import { AgentStateMachine, AgentState, AgentEvent, StateContext } from './AgentStateMachine';

export interface StateSnapshot {
  timestamp: number;
  state: AgentState;
  event: AgentEvent | null;
  context: Partial<StateContext>;
  metrics: StateContext['metrics'];
}

export interface StateTransitionLog {
  timestamp: number;
  from: AgentState;
  to: AgentState;
  event: AgentEvent;
  durationMs: number;
  description?: string;
}

export interface ObserverMetrics {
  totalTransitions: number;
  stateDistribution: Record<AgentState, number>;
  averageStateDurationMs: number;
  mostFrequentEvents: Array<{ event: AgentEvent; count: number }>;
  loopDetections: number;
  constraintViolations: number;
  buildFailures: number;
  successRate: number;
}

export type StateChangeCallback = (
  from: AgentState,
  to: AgentState,
  event: AgentEvent,
  context: StateContext
) => void;

export type ErrorCallback = (
  error: string,
  state: AgentState,
  context: StateContext
) => void;

export class StateMachineObserver {
  private stateMachine: AgentStateMachine;
  private snapshots: StateSnapshot[] = [];
  private transitionLogs: StateTransitionLog[] = [];
  private stateChangeCallbacks: StateChangeCallback[] = [];
  private errorCallbacks: ErrorCallback[] = [];
  private startTime: number = Date.now();
  private currentStateStartTime: number = Date.now();
  private _loopDetections: number = 0;
  private _constraintViolations: number = 0;
  private _buildFailures: number = 0;

  constructor(stateMachine: AgentStateMachine) {
    this.stateMachine = stateMachine;
    this.takeSnapshot();
  }

  /** Subscribe to state changes */
  onStateChange(callback: StateChangeCallback): void {
    this.stateChangeCallbacks.push(callback);
  }

  /** Subscribe to errors */
  onError(callback: ErrorCallback): void {
    this.errorCallbacks.push(callback);
  }

  /** Take a snapshot of current state */
  takeSnapshot(): StateSnapshot {
    const context = this.stateMachine.context;
    const snapshot: StateSnapshot = {
      timestamp: Date.now(),
      state: this.stateMachine.state,
      event: this.stateMachine.lastEvent,
      context: {
        iteration: context.iteration,
        consecutiveEdits: context.consecutiveEdits,
        consecutivePlans: context.consecutivePlans,
        buildFailures: context.buildFailures,
        failedEditAttempts: context.failedEditAttempts,
        pendingFixes: context.pendingFixes,
        intentType: context.intentType,
        inBuildFixCycle: context.inBuildFixCycle,
        inPlanLoop: context.inPlanLoop,
        inSearchLoop: context.inSearchLoop,
      },
      metrics: { ...context.metrics },
    };

    this.snapshots.push(snapshot);
    return snapshot;
  }

  /** Log a state transition */
  logTransition(from: AgentState, to: AgentState, event: AgentEvent, description?: string): void {
    const durationMs = Date.now() - this.currentStateStartTime;
    
    const log: StateTransitionLog = {
      timestamp: Date.now(),
      from,
      to,
      event,
      durationMs,
      description,
    };

    this.transitionLogs.push(log);
    this.currentStateStartTime = Date.now();

    // Take snapshot after transition
    this.takeSnapshot();

    // Notify callbacks
    const context = this.stateMachine.context;
    this.stateChangeCallbacks.forEach(callback => callback(from, to, event, context));

    // Track specific events
    if (event === AgentEvent.LOOP_DETECTED || event === AgentEvent.SEARCH_LOOP) {
      this._loopDetections++;
    }
    if (event === AgentEvent.PLAN_INVALID) {
      this._constraintViolations++;
    }
    if (event === AgentEvent.BUILD_FAILURE) {
      this._buildFailures++;
    }

    // Log to console in debug mode
    if (process.env.DEBUG === 'agent-state') {
      console.log(`[StateObserver] ${from} ──${event}──→ ${to}${description ? ` (${description})` : ''} [${durationMs}ms]`);
    }
  }

  /** Report an error */
  reportError(error: string): void {
    const state = this.stateMachine.state;
    const context = this.stateMachine.context;
    
    this.errorCallbacks.forEach(callback => callback(error, state, context));

    if (process.env.DEBUG === 'agent-state') {
      console.error(`[StateObserver] ERROR in ${state}: ${error}`);
    }
  }

  /** Get observer metrics */
  getMetrics(): ObserverMetrics {
    const stateDistribution: Record<AgentState, number> = {
      [AgentState.IDLE]: 0,
      [AgentState.INTENT]: 0,
      [AgentState.PLAN]: 0,
      [AgentState.CONSTRAINTS]: 0,
      [AgentState.SEQUENCE]: 0,
      [AgentState.EXECUTE]: 0,
      [AgentState.VERIFY]: 0,
      [AgentState.COMPLETE]: 0,
      [AgentState.FAILED]: 0,
      [AgentState.PAUSED]: 0,
    };

    const eventCounts = new Map<AgentEvent, number>();

    this.transitionLogs.forEach(log => {
      stateDistribution[log.to] = (stateDistribution[log.to] || 0) + 1;
      eventCounts.set(log.event, (eventCounts.get(log.event) || 0) + 1);
    });

    const mostFrequentEvents = Array.from(eventCounts.entries())
      .map(([event, count]) => ({ event, count }))
      .sort((a, b) => b.count - a.count)
      .slice(0, 10);

    const totalDuration = this.transitionLogs.reduce((sum, log) => sum + log.durationMs, 0);
    const averageDuration = this.transitionLogs.length > 0 
      ? totalDuration / this.transitionLogs.length 
      : 0;

    const totalTransitions = this.transitionLogs.length;
    const successfulCompletions = stateDistribution[AgentState.COMPLETE];
    const failures = stateDistribution[AgentState.FAILED];
    const successRate = totalTransitions > 0 
      ? (successfulCompletions / (successfulCompletions + failures)) * 100 
      : 0;

    return {
      totalTransitions,
      stateDistribution,
      averageStateDurationMs: averageDuration,
      mostFrequentEvents,
      loopDetections: this._loopDetections,
      constraintViolations: this._constraintViolations,
      buildFailures: this._buildFailures,
      successRate,
    };
  }

  /** Get state history */
  getHistory(limit: number = 50): StateTransitionLog[] {
    return this.transitionLogs.slice(-limit);
  }

  /** Get snapshots */
  getSnapshots(limit: number = 100): StateSnapshot[] {
    return this.snapshots.slice(-limit);
  }

  /** Get current state duration */
  getCurrentStateDuration(): number {
    return Date.now() - this.currentStateStartTime;
  }

  /** Get session duration */
  getSessionDuration(): number {
    return Date.now() - this.startTime;
  }

  /** Export state history to JSON */
  exportToJson(): string {
    const data = {
      sessionStart: this.startTime,
      sessionDuration: this.getSessionDuration(),
      currentState: this.stateMachine.state,
      context: this.stateMachine.context,
      history: this.transitionLogs,
      snapshots: this.snapshots,
      metrics: this.getMetrics(),
    };
    return JSON.stringify(data, null, 2);
  }

  /** Import state history from JSON (for debugging) */
  importFromJson(json: string): void {
    try {
      const data = JSON.parse(json);
      if (data.history) {
        this.transitionLogs = data.history;
      }
      if (data.snapshots) {
        this.snapshots = data.snapshots;
      }
      if (data.metrics) {
        this._loopDetections = data.metrics.loopDetections || 0;
        this._constraintViolations = data.metrics.constraintViolations || 0;
        this._buildFailures = data.metrics.buildFailures || 0;
      }
    } catch (error: any) {
      this.reportError(`Failed to import state history: ${error.message}`);
    }
  }

  /** Reset observer */
  reset(): void {
    this.snapshots = [];
    this.transitionLogs = [];
    this.startTime = Date.now();
    this.currentStateStartTime = Date.now();
    this._loopDetections = 0;
    this._constraintViolations = 0;
    this._buildFailures = 0;
    this.takeSnapshot();
  }

  /** Generate debug report */
  generateDebugReport(): string {
    const metrics = this.getMetrics();
    const history = this.getHistory(20);
    const context = this.stateMachine.context;

    let report = `
╔══════════════════════════════════════════════════════════════╗
║           AGENT STATE MACHINE DEBUG REPORT                   ║
╚══════════════════════════════════════════════════════════════╝

SESSION INFO
────────────
Session Duration: ${Math.round(this.getSessionDuration() / 1000)}s
Current State: ${this.stateMachine.state}
Current State Duration: ${this.getCurrentStateDuration()}ms
Total Transitions: ${metrics.totalTransitions}

CONTEXT
────────────
Iteration: ${context.iteration}
Intent Type: ${context.intentType}
Consecutive Edits: ${context.consecutiveEdits}
Consecutive Plans: ${context.consecutivePlans}
Build Failures: ${context.buildFailures}
Failed Edit Attempts: ${context.failedEditAttempts}
Pending Fixes: ${context.pendingFixes.length}
In Build-Fix Cycle: ${context.inBuildFixCycle}
In Plan Loop: ${context.inPlanLoop}
In Search Loop: ${context.inSearchLoop}

METRICS
────────────
Success Rate: ${metrics.successRate.toFixed(1)}%
Loop Detections: ${metrics.loopDetections}
Constraint Violations: ${metrics.constraintViolations}
Build Failures: ${metrics.buildFailures}
Avg State Duration: ${Math.round(metrics.averageStateDurationMs)}ms

STATE DISTRIBUTION
────────────
`;

    Object.entries(metrics.stateDistribution).forEach(([state, count]) => {
      if (count > 0) {
        report += `  ${state}: ${count}\n`;
      }
    });

    report += `
MOST FREQUENT EVENTS
────────────
`;

    metrics.mostFrequentEvents.slice(0, 5).forEach(({ event, count }) => {
      report += `  ${event}: ${count}\n`;
    });

    report += `
RECENT TRANSITIONS
────────────
`;

    history.forEach(log => {
      report += `  ${log.from} ──${log.event}──→ ${log.to} [${log.durationMs}ms]${log.description ? ` (${log.description})` : ''}\n`;
    });

    report += `
╚══════════════════════════════════════════════════════════════╝
`;

    return report;
  }

  /** Check for anomalies */
  detectAnomalies(): string[] {
    const anomalies: string[] = [];
    const context = this.stateMachine.context;
    const metrics = this.getMetrics();

    // Check for excessive plan-only responses
    if (context.consecutivePlans >= 3) {
      anomalies.push(`⚠️ Plan-only loop detected (${context.consecutivePlans} consecutive plans)`);
    }

    // Check for excessive build failures
    if (context.buildFailures >= 3) {
      anomalies.push(`⚠️ Build-fix cycle stuck (${context.buildFailures} consecutive failures)`);
    }

    // Check for excessive edit failures
    if (context.failedEditAttempts >= 3) {
      anomalies.push(`⚠️ Edit failures excessive (${context.failedEditAttempts} failed attempts)`);
    }

    // Check for search loops
    if (context.inSearchLoop) {
      anomalies.push(`⚠️ Search loop detected`);
    }

    // Check for low success rate
    if (metrics.successRate < 50 && metrics.totalTransitions > 10) {
      anomalies.push(`⚠️ Low success rate (${metrics.successRate.toFixed(1)}%)`);
    }

    // Check for excessive loop detections
    if (metrics.loopDetections >= 3) {
      anomalies.push(`⚠️ Multiple loop detections (${metrics.loopDetections})`);
    }

    // Check for long state durations
    const currentDuration = this.getCurrentStateDuration();
    if (currentDuration > 60000) { // 1 minute
      anomalies.push(`⚠️ Current state duration excessive (${Math.round(currentDuration / 1000)}s)`);
    }

    return anomalies;
  }

  /** Print debug report to console */
  printDebugReport(): void {
    console.log(this.generateDebugReport());
    
    const anomalies = this.detectAnomalies();
    if (anomalies.length > 0) {
      console.log('\n⚠️ ANOMALIES DETECTED:');
      anomalies.forEach(a => console.log(`  ${a}`));
    }
  }
}

/**
 * Auto-monitoring wrapper for AgentStateMachine
 * Automatically logs transitions and detects anomalies
 */
export class AutoMonitoringStateMachine {
  private _stateMachine: AgentStateMachine;
  private _observer: StateMachineObserver;

  constructor() {
    this._stateMachine = new AgentStateMachine();
    this._observer = new StateMachineObserver(this._stateMachine);
    
    // Auto-log transitions
    this._observer.onStateChange((from, to, event) => {
      console.log(`[AgentState] ${from} ──${event}──→ ${to}`);
    });

    // Auto-detect anomalies
    this._observer.onStateChange((from, to, event, context) => {
      const anomalies = this._observer.detectAnomalies();
      if (anomalies.length > 0) {
        console.warn('[AgentState] Anomalies detected:', anomalies);
      }
    });
  }

  get stateMachine(): AgentStateMachine {
    return this._stateMachine;
  }

  get observer(): StateMachineObserver {
    return this._observer;
  }

  reset(): void {
    this._stateMachine.reset();
    this._observer.reset();
  }
}
