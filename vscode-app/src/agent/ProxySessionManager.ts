/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

/**
 * Session manager for the 3D LLM proxy (FreeDeepseekAPI).
 *
 * Handles:
 * - Session discovery via /v1/sessions and /health
 * - Proactive reset before hitting 100-msg / 2h limits
 * - Message compaction (summarization) at 85 messages
 * - Continuation and retry tracking
 */

import {ProxySessionState, SessionHealth, SessionLimits, SessionManager} from './SessionManager';

interface ProxySessionApiEntry {
  agent: string;
  session_id: string;
  message_count: number;
  history_size: number;
  age_min: number;
  account?: string;
}

interface ProxySessionsResponse {
  agents: ProxySessionApiEntry[];
  total: number;
}

interface ProxyHealthResponse {
  status: 'ok' | string;
  model: string;
  agents: number;
}

interface ProxyResetResponse {
  status: string;
  agent: string;
  history_preserved: number;
  history: string;
}

const DEFAULT_3D_LLM_LIMITS: SessionLimits = {
  maxMessages: 100,
  ttlMs: 2 * 60 * 60 * 1000, // 2 hours
  maxPromptChars: 80000,
  maxHistoryLength: 30,
  maxHistoryChars: 80000,
  autoResetTriggers: {
    messageCount: 90,
    ageMinutes: 100,
    continuationLimit: 5,
  },
};

export class ProxySessionManager implements SessionManager {
  readonly name: string = '3d-llm-proxy';
  readonly provider: string = '3d-llm';

  private baseUrl: string = 'http://localhost:9655';
  private limits: SessionLimits = DEFAULT_3D_LLM_LIMITS;
  private proxySession: ProxySessionState = {
    id: null,
    messageCount: 0,
    createdAt: Date.now(),
    accountId: null,
    history: [],
    continuityCounter: 0,
    retryAttempts: 0,
  };

  /** Logger callback injected from AgentBridge */
  private logFn: (msg: string) => void = () => {};

  /** Auto-detected agent ID from the proxy (resolves model ID → proxy agent name mapping) */
  private _resolvedAgentId: string | null = null;

  setLogger(fn: (msg: string) => void): void {
    this.logFn = fn;
  }

  private log(msg: string): void {
    this.logFn(`[ProxySession] ${msg}`);
  }

  configure(baseUrl: string, limits?: Partial<SessionLimits>): void {
    this.baseUrl = baseUrl || this.baseUrl;
    if (limits) {
      this.limits = { ...DEFAULT_3D_LLM_LIMITS, ...limits };
    }
  }

  /**
   * Resolve the actual agent ID used by the proxy server.
   * The proxy may use a different agent identifier than the model ID
   * (e.g., model ID "deepseek-v4-pro" maps to proxy agent "dev-agent").
   * This queries /v1/sessions to discover the actual agent name.
   *
   * @param _hint - The model ID or hint to help match sessions (used as fallback if no session found)
   * @returns The resolved agent ID to use for session management calls
   */
  async resolveAgentId(_hint?: string): Promise<string> {
    // Return cached resolution if available
    if (this._resolvedAgentId) {
      return this._resolvedAgentId;
    }

    try {
      const response = await fetch(`${this.baseUrl}/v1/sessions`);
      if (!response.ok) {
        this.log(`Failed to query sessions for agent ID resolution: ${response.status}`);
        return _hint || '';
      }

      const data: ProxySessionsResponse = await response.json();
      const agents = data.agents || [];

      if (agents.length === 0) {
        this.log('No sessions found on proxy; using hint for agent ID resolution');
        return _hint || '';
      }

      // If there's only one agent, use it directly (most common case for single-user proxy)
      if (agents.length === 1) {
        this._resolvedAgentId = agents[0].agent;
        this.log(`Resolved agent ID: "${this._resolvedAgentId}" (sole agent on proxy)`);
        return this._resolvedAgentId;
      }

      // Multiple agents: try to match by hint first, then use the first one
      if (_hint) {
        const matchByHint = agents.find((a) => a.agent === _hint);
        if (matchByHint) {
          this._resolvedAgentId = matchByHint.agent;
          this.log(`Resolved agent ID: "${this._resolvedAgentId}" (matched by hint)`);
          return this._resolvedAgentId;
        }
      }

      // Fall back to first agent on the proxy
      this._resolvedAgentId = agents[0].agent;
      this.log(`Resolved agent ID: "${this._resolvedAgentId}" (first of ${agents.length} agents)`);
      return this._resolvedAgentId;
    } catch (error: any) {
      this.log(`Agent ID resolution error: ${error.message}`);
      return _hint || '';
    }
  }

  /** Get the resolved agent ID, or fall back to the provided hint */
  getResolvedAgentId(hint?: string): string {
    return this._resolvedAgentId || hint || '';
  }

  /** Clear cached agent ID resolution (call when connections change) */
  clearResolvedAgentId(): void {
    this._resolvedAgentId = null;
  }

  /**
   * Sync session state with the proxy server.
   * @param agentId - The agent identifier
   * @param forceNew - If true, skip reusing any existing session and force a fresh one via reset
   */
  async syncSession(agentId: string, forceNew: boolean = false): Promise<void> {
    try {
      // Resolve the actual agent ID from the proxy (handles model ID → proxy agent mapping)
      const resolvedId = await this.resolveAgentId(agentId);
      const effectiveAgentId = resolvedId || agentId;

      // If forceNew is set, proactively reset the session on the proxy server first
      if (forceNew) {
        this.log(`Force new session requested, resetting proxy session (agent: ${effectiveAgentId})`);
        await this.resetSession(effectiveAgentId, 'manual');
        // After reset, treat as fresh session locally
        this.proxySession.id = null;
        this.proxySession.messageCount = 0;
        this.proxySession.createdAt = Date.now();
        this.proxySession.history = [];
        this.proxySession.continuityCounter = 0;
        this.proxySession.retryAttempts = 0;
        this.proxySession.contextExhausted = false;
        this.log(`Proxy session reset complete for ${effectiveAgentId}, ready for fresh chat`);
        return;
      }

      const response = await fetch(`${this.baseUrl}/v1/sessions`);
      if (!response.ok) {
        this.log(`Failed to sync sessions: ${response.status}`);
        return;
      }

      const data: ProxySessionsResponse = await response.json();
      // Match by either the resolved agent ID or the caller-provided agentId
      const entry = data.agents?.find((a) =>
        a.agent === effectiveAgentId || a.agent === agentId
      );

      if (entry) {
        // Update cached agent ID from the actual proxy response
        if (!this._resolvedAgentId) {
          this._resolvedAgentId = entry.agent;
        }
        const actualAgentId = entry.agent;

        // Check if existing session is near limits and should be reset
        const nearLimit = entry.message_count >= this.limits.maxMessages * 0.9;
        const nearAge = (entry.age_min || 0) >= (this.limits.ttlMs / 60000) * 0.9;

        if (nearLimit || nearAge) {
          this.log(
            `Existing session for ${actualAgentId} near limits (msgs: ${entry.message_count}, age: ${entry.age_min}min), resetting`
          );
          await this.resetSession(actualAgentId, nearLimit ? 'message_limit' : 'age_limit');
          this.proxySession.id = null;
          this.proxySession.messageCount = 0;
          this.proxySession.createdAt = Date.now();
          this.proxySession.history = [];
          this.proxySession.continuityCounter = 0;
          this.proxySession.retryAttempts = 0;
          this.proxySession.contextExhausted = false;
        } else {
          // Reuse recent session
          this.proxySession.id = entry.session_id;
          this.proxySession.messageCount = entry.message_count || 0;
          this.proxySession.createdAt = Date.now() - (entry.age_min || 0) * 60000;
          if (entry.account) {
            this.proxySession.accountId = entry.account;
          }
          this.log(
            `Synced session ${entry.session_id} — ${entry.message_count} msgs, ${entry.age_min} min old`
          );
        }
      } else {
        this.log(`No session found for agent ${effectiveAgentId}`);
        // Treat as fresh session
        this.proxySession.id = null;
        this.proxySession.messageCount = 0;
        this.proxySession.createdAt = Date.now();
      }
    } catch (error: any) {
      this.log(`Sync error: ${error.message}`);
    }
  }

  getSessionState(): ProxySessionState | null {
    return { ...this.proxySession };
  }

  getLimits(): SessionLimits | null {
    return { ...this.limits };
  }

  async checkHealth(): Promise<SessionHealth> {
    try {
      const response = await fetch(`${this.baseUrl}/health`);
      if (!response.ok) {
        const errorBody = await response.text().catch(() => 'Unknown error');
        
        // Special handling for proxy connectivity issues
        if (response.status === 500 && errorBody.includes('fetch failed')) {
          return {
            healthy: false,
            diagnostics: { statusCode: response.status, error: 'FreeDeepseekAPI proxy cannot connect to DeepSeek servers' },
            warnings: ['Proxy is running but cannot reach DeepSeek API. Check your network or proxy configuration.'],
          };
        }

        // If /health returns 404, try /v1/models as a fallback health check
        if (response.status === 404) {
          try {
            const modelsResponse = await fetch(`${this.baseUrl}/v1/models`);
            if (modelsResponse.ok) {
              return {
                healthy: true,
                diagnostics: {
                  statusCode: modelsResponse.status,
                  note: `/health returned 404, /v1/models returned ${modelsResponse.status} (auth required — chat completions work without auth)`,
                },
                warnings: [],
              };
            }
            // /v1/models also returned an error for another reason
            return {
              healthy: false,
              diagnostics: { statusCode: response.status, modelsStatusCode: modelsResponse.status, error: errorBody },
              warnings: [`Proxy /health returned 404 and /v1/models returned ${modelsResponse.status}. The proxy may not be running.`],
            };
          } catch (modelsError: any) {
            // /v1/models threw — check if we can at least connect to the base URL
            return {
              healthy: false,
              diagnostics: { statusCode: response.status, modelsError: modelsError.message },
              warnings: ['Proxy /health returned 404. The proxy may be running an older version without /health endpoint.'],
            };
          }
        }

        return {
          healthy: false,
          diagnostics: { statusCode: response.status, error: errorBody },
          warnings: [`Proxy returned ${response.status}.`],
        };
      }

      const data: ProxyHealthResponse = await response.json();
      return {
        healthy: data.status === 'ok',
        diagnostics: { statusCode: response.status, model: data.model, agents: data.agents },
        warnings: [],
      };
    } catch (error: any) {
      return {
        healthy: false,
        diagnostics: { error: error.message },
        warnings: ['Cannot connect to 3D LLM proxy. Is it running on port 9655?'],
      };
    }
  }

  async manageMessages<T extends { role: string; content: string }>(messages: T[]): Promise<T[]> {
    return this.processMessages(messages);
  }

  recordContinuation(): void {
    this.proxySession.continuityCounter++;
  }

  recordRetry(): void {
    this.proxySession.retryAttempts++;
  }

  recordMessage(role: string, content: string): void {
    this.proxySession.messageCount++;
    // Store as user/assistant pair per ProxySessionState schema
    if (role === 'user') {
      this.proxySession.history.push({ user: content, assistant: '' });
    } else if (role === 'assistant') {
      // Update the last entry if it's a user message with empty assistant
      const last = this.proxySession.history[this.proxySession.history.length - 1];
      if (last && last.assistant === '') {
        last.assistant = content;
      } else {
        this.proxySession.history.push({ user: '', assistant: content });
      }
    } else {
      this.proxySession.history.push({ user: '', assistant: `[${role}]: ${content}` });
    }
  }

  checkLimits(triggers?: {
    messageWarningPercentage?: number;
    tokenWarningPercentage?: number;
    ageMinutes?: number;
    continuationLimit?: number;
  }): string[] {
    const warnings: string[] = [];

    if (!triggers) {
      triggers = {
        messageWarningPercentage: 0.85,
        tokenWarningPercentage: 0.85,
        ageMinutes: 90,
        continuationLimit: 5,
      };
    }

    // 1. Message count warning
    const msgPct = this.proxySession.messageCount / this.limits.maxMessages;
    if (msgPct >= (triggers.messageWarningPercentage || 0.85)) {
      warnings.push(
        `Message count ${this.proxySession.messageCount}/${this.limits.maxMessages} (${(msgPct * 100).toFixed(0)}%)`
      );
    }

    // 2. Session age warning
    const ageMs = Date.now() - this.proxySession.createdAt;
    const ageMin = ageMs / 60000;
    if (triggers.ageMinutes && ageMin > triggers.ageMinutes) {
      warnings.push(`Session age ${ageMin.toFixed(1)} min > ${triggers.ageMinutes} min limit`);
    }

    // 3. Continuity counter warning
    if (
      triggers.continuationLimit &&
      this.proxySession.continuityCounter >= triggers.continuationLimit
    ) {
      warnings.push(
        `Continuation limit ${this.proxySession.continuityCounter}/${triggers.continuationLimit} reached`
      );
    }

    return warnings;
  }

  checkExhaustion(triggers?: {
    messageLimitPercentage?: number;
    tokenUsagePercentage?: number;
  }): boolean {
    if (!triggers) {
      triggers = { messageLimitPercentage: 0.95, tokenUsagePercentage: 0.95 };
    }

    const msgPct = this.proxySession.messageCount / this.limits.maxMessages;
    if (msgPct >= (triggers.messageLimitPercentage || 0.95)) {
      this.proxySession.contextExhausted = true;
      return true;
    }

    if (this.proxySession.contextExhausted) {
      return true;
    }

    return false;
  }

  processMessages<T extends { role: string; content: string }>(
    messages: T[],
    triggers?: { continuationLimit?: number }
  ): T[] {
    // 1. Check for forced new session (caller already reset)
    if (!this.proxySession.id) {
      this.log('No active session, treating as fresh conversation');
      this.proxySession.continuityCounter = 0;
      return messages;
    }

    // 2. Compact if too many messages
    messages = this.compactMessages(messages);

    // 3. Increment continuity counter
    this.proxySession.continuityCounter++;

    // 4. Auto-continuation reset
    if (
      triggers?.continuationLimit &&
      this.proxySession.continuityCounter >= triggers.continuationLimit
    ) {
      this.log(
        `Auto-reset: continuation limit ${this.proxySession.continuityCounter}/${triggers.continuationLimit} reached`
      );
      // Caller should reset before this point
    }

    return messages;
  }

  /**
   * Compact messages: keep system prompt + last 15 exchanges,
   * summarize older exchanges into a single context message.
   */
  private compactMessages<T extends { role: string; content: string }>(messages: T[]): T[] {
    const systemIdx = messages.findIndex((m) => m.role === 'system');
    const systemMsg = systemIdx >= 0 ? messages[systemIdx] : undefined;
    const nonSystem = messages.filter((m) => m.role !== 'system');

    if (nonSystem.length <= this.limits.maxHistoryLength * 2) {
      this.log(`No compaction needed (${nonSystem.length} non-system messages)`);
      return messages;
    }

    const recent = nonSystem.slice(-this.limits.maxHistoryLength * 2);
    const old = nonSystem.slice(0, -this.limits.maxHistoryLength * 2);

    if (old.length === 0) return messages;

    const summary = old
      .map((m) => `${m.role}: ${m.content.substring(0, 100)}`)
      .join('\n');
    const compactionMsg = {
      role: 'user',
      content: `[Earlier conversation summarized]: ${summary}`,
    } as unknown as T;

    this.log(`Compacted ${old.length} messages into summary (${recent.length} recent kept)`);

    const result: T[] = [];
    if (systemMsg) result.push(systemMsg);
    result.push(compactionMsg);
    result.push(...recent);
    return result;
  }

  getMessagesRemaining(): number | null {
    return Math.max(0, this.limits.maxMessages - this.proxySession.messageCount);
  }

  getTimeRemaining(): number | null {
    if (this.proxySession.createdAt <= 0) return null;
    return Math.max(0, this.limits.ttlMs - (Date.now() - this.proxySession.createdAt));
  }

  async resetSession(
    agentId: string,
    reason?: 'message_limit' | 'token_limit' | 'age_limit' | 'manual'
  ): Promise<boolean> {
    // Resolve the actual proxy agent ID if not yet cached.
    // This handles the case where the proxy uses a different agent identifier than
    // the model ID (e.g., model ID "deepseek-v4-pro" → proxy agent "dev-agent").
    if (!this._resolvedAgentId) {
      await this.resolveAgentId(agentId);
    }
    const effectiveAgentId = this._resolvedAgentId || agentId;
    const resetUrl = `${this.baseUrl}/reset-session?agent=${encodeURIComponent(effectiveAgentId)}`;
    try {
      const response = await fetch(resetUrl, { method: 'POST' });
      if (!response.ok) {
        this.log(`Reset failed: ${response.status} ${response.statusText}`);
        return false;
      }
      const data: ProxyResetResponse = await response.json();
      this.log(`Session reset for ${effectiveAgentId}: ${data.history_preserved} history items preserved`);
      
      // Reset local state
      this.proxySession.id = null;
      this.proxySession.messageCount = 0;
      this.proxySession.createdAt = Date.now();
      this.proxySession.history = [];
      this.proxySession.continuityCounter = 0;
      this.proxySession.retryAttempts = 0;
      this.proxySession.contextExhausted = false;
      
      return true;
    } catch (error: any) {
      this.log(`Reset error: ${error.message}`);
      return false;
    }
  }

  serialize(): object {
    return {
      name: this.name,
      provider: this.provider,
      baseUrl: this.baseUrl,
      limits: this.limits,
      proxySession: this.proxySession,
    };
  }

  deserialize(data: any): void {
    if (!data) return;
    this.baseUrl = data.baseUrl || this.baseUrl;
    if (data.limits) {
      this.limits = { ...DEFAULT_3D_LLM_LIMITS, ...data.limits };
    }
    if (data.proxySession) {
      this.proxySession = {
        ...this.proxySession,
        ...data.proxySession,
      };
    }
  }
}
