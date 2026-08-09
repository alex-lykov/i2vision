/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

/**
 * AgentBridge.SessionManagerBridge - Session lifecycle helpers and error detection.
 *
 * Extracted from AgentBridge.ts. Provides:
 *   - Session/context exhaustion error detection
 *   - Simplified tool prompt injection for JSON parse error recovery
 *   - Session state inspection helpers
 */

import { LLMMessage, LLMToolCall } from '../cliIntegrationRefactored';
import { SessionManager } from './SessionManager';
import { Diagnostics } from './AgentBridge.Diagnostics';

export class SessionManagerBridge {
  private diag: Diagnostics;
  private _pendingMessages: LLMMessage[] = [];

  constructor(diag: Diagnostics) {
    this.diag = diag;
  }

  // --- Pending Messages (for JSON parse recovery) ---

  get pendingMessages(): LLMMessage[] {
    return this._pendingMessages;
  }

  set pendingMessages(msgs: LLMMessage[]) {
    this._pendingMessages = msgs;
  }

  // --- Error Detection ---

  isSessionError(errorText: string): boolean {
    const sessionIndicators = [
      'session',
      'empty_response',
      '502',
      'session_reset',
      'expired',
      'invalid session',
      'exceeds_limit',
      'too long',
      'context_length',
      'token limit',
    ];
    return sessionIndicators.some((ind) => errorText.toLowerCase().includes(ind));
  }

  isContextExhaustionError(errorText: string): boolean {
    const contextIndicators = [
      'exceeds_limit',
      'too long',
      'content too long',
      'context_length',
      'token limit',
      'содержание слишком длинное', // Russian: "content too long"
      'предел длины', // Russian: "length limit"
    ];
    return contextIndicators.some((ind) => errorText.toLowerCase().includes(ind));
  }

  // --- JSON Parse Recovery ---

  injectSimplifiedToolPrompt(toolCall: LLMToolCall): void {
    const simplifiedPrompt = `Your previous tool call had malformed JSON. Retry with SIMPLIFIED arguments:
TOOL_CALL: ${toolCall.name}
arguments: {"path": "/path/to/file"}  // Keep it minimal

DO NOT include large content in arguments. Just reference files by path.`;

    if (!this._pendingMessages) {
      this._pendingMessages = [];
    }
    this._pendingMessages.push({ role: 'user', content: simplifiedPrompt });
    this.diag.log(`Injected simplified tool prompt for ${toolCall.name}`);
  }

  // --- Session State Inspection ---

  /** Check whether a session manager supports server-side sessions */
  static isStateless(sessionManager?: SessionManager): boolean {
    return !sessionManager || sessionManager.name === 'Null';
  }

  /** Resolve proxy agent ID when the config model ID differs from the proxy-assigned ID */
  static async resolveProxyAgentId(
    sessionManager: SessionManager,
    modelId: string,
    diag: Diagnostics
  ): Promise<void> {
    const mgr = sessionManager as any;
    if (typeof mgr.resolveAgentId !== 'function') return;
    try {
      const resolvedId: string = await mgr.resolveAgentId(modelId);
      if (resolvedId && resolvedId !== modelId) {
        diag.log(`Proxy agent ID resolved: "${modelId}" → "${resolvedId}"`);
      }
    } catch { /* non-blocking */ }
  }
}
