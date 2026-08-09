/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

/**
 * Diagnostics - Logging, progress, and lifecycle management.
 * Extracted from AgentBridge.ts.
 */

import * as vscode from 'vscode';
import { AgentEvent, AgentState } from './AgentStateMachine';
import { TerminalManager } from './TerminalManager';

/** Progress event emitted by the AgentBridge during execution */
export interface DiagnosticsProgressEvent {
  type: 'tool_start' | 'tool_complete' | 'iteration_complete' | 'thinking' | 'tool_output' | 'state_change';
  iteration: number;
  toolCall?: { toolName: string; args: Record<string, any>; result?: string; error?: string; durationMs?: number; toolCallId?: string };
  message?: string;
  partialOutput?: string;
  state?: { from: string; to: string; event: string };
}

export class Diagnostics {
  private outputChannel: vscode.OutputChannel;
  private progressCallback?: (event: DiagnosticsProgressEvent) => void;
  private _terminalManager?: TerminalManager;

  constructor(outputChannel: vscode.OutputChannel) {
    this.outputChannel = outputChannel;
  }

  setProgressCallback(cb?: (event: DiagnosticsProgressEvent) => void): void {
    this.progressCallback = cb;
  }

  setTerminalManager(tm: TerminalManager): void {
    this._terminalManager = tm;
  }

  log(message: string, level: 'info' | 'warn' | 'error' = 'info'): void {
    const timestamp = new Date().toISOString();
    const levelPrefix = level === 'error' ? '❌' : level === 'warn' ? '⚠️' : 'ℹ️';
    const formatted = `[${timestamp}] [AgentBridge] ${levelPrefix} ${message}`;
    if (this.outputChannel) this.outputChannel.appendLine(formatted);
    console[level](formatted);
  }

  logStateTransition(from: AgentState, to: AgentState, event: AgentEvent, details?: string): void {
    const timestamp = new Date().toISOString();
    const stateInfo = `[${timestamp}] [StateMachine] ${from}───${event}───> ${to}`;
    const fullMessage = details ? `${stateInfo} | ${details}` : stateInfo;
    if (this.outputChannel) this.outputChannel.appendLine(fullMessage);
    console.log(fullMessage);
  }

  logToolExecution(toolName: string, args: Record<string, any>, startTime: number): void {
    const duration = Date.now() - startTime;
    const argsSummary = Object.entries(args)
      .map(([k, v]) => `${k}=${typeof v === 'string' && v.length > 50 ? `${v.substring(0, 47)}...` : v}`)
      .join(', ');
    this.log(`🔧 Tool executed: ${toolName}(${argsSummary}) [${duration}ms]`, 'info');
  }

  emitProgress(event: DiagnosticsProgressEvent): void {
    if (this.progressCallback) this.progressCallback(event);
  }

  dispose(): void {
    if (this._terminalManager) {
      this._terminalManager.dispose();
    }
  }
}
