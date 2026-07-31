/**
 * ProxySessionManager - Session manager for the 3D LLM proxy (FreeDeepseekAPI).
 *
 * Handles:
 * - Session discovery via /v1/sessions and /health
 * - Proactive reset before hitting 100-msg / 2h limits
 * - Message compaction (summarization) at 85 messages
 * - Continuation and retry tracking
 */

import { SessionManager, SessionHealth, SessionLimits, ProxySessionState } from './SessionManager';

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
  maxHistoryLength: 15,
  maxHistoryChars: 10000,
  autoResetTriggers: {
    messageCount: 85,
    ageMinutes: 90,
    continuationLimit: 2,
    tokenUsagePercentage: 90, // Reset when 90% of context tokens are used
    maxTokenUsage: 57600, // 90% of 64000 token context window
  },
};

export class ProxySessionManager implements SessionManager {
  readonly name = '3D LLM Proxy';
  readonly provider = '3d-llm';

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

  async syncSession(agentId: string): Promise<void> {
    try {
      const response = await fetch(`${this.baseUrl}/v1/sessions`);
      if (!response.ok) {
        this.log(`Failed to sync sessions: ${response.status}`);
        return;
      }

      const data: ProxySessionsResponse = await response.json();
      const entry = data.agents?.find((a) => a.agent === agentId);

      if (entry) {
        this.proxySession.id = entry.session_id;
        this.proxySession.messageCount = entry.message_count || 0;
        this.proxySession.createdAt = Date.now() - (entry.age_min || 0) * 60000;
        if (entry.account) {
          this.proxySession.accountId = entry.account;
        }
        this.log(
          `Synced session ${entry.session_id} — ${entry.message_count} msgs, ${entry.age_min} min old`
        );
      } else {
        this.log(`No session found for agent ${agentId}`);
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
        return {
          healthy: false,
          diagnostics: { statusCode: response.status },
          warnings: [`Proxy health check failed: ${response.status}`],
        };
      }

      const data: ProxyHealthResponse = await response.json();
      const healthy = data.status === 'ok';
      return {
        healthy,
        diagnostics: data,
        warnings: healthy ? [] : [`Proxy status: ${data.status}`],
      };
    } catch (error: any) {
      return {
        healthy: false,
        diagnostics: { error: error.message },
        warnings: [`Proxy unreachable: ${error.message}`],
      };
    }
  }

  async manageMessages<T extends { role: string; content: string }>(messages: T[]): Promise<T[]> {
    const now = Date.now();
    const triggers = this.limits.autoResetTriggers;

    // 1. Proactive message-count reset
    if (this.proxySession.messageCount >= triggers.messageCount) {
      this.log(
        `Message count at ${this.proxySession.messageCount}/${this.limits.maxMessages}, compacting...`
      );
      return this.compactMessages(messages);
    }

    // 2. Token-based reset (if token usage tracking is available)
    if (this.proxySession.tokenUsage && triggers.tokenUsagePercentage && triggers.maxTokenUsage) {
      const tokenUsagePercentage = (this.proxySession.tokenUsage.total / triggers.maxTokenUsage) * 100;
      if (tokenUsagePercentage >= triggers.tokenUsagePercentage) {
        this.log(
          `Token usage at ${this.proxySession.tokenUsage.total}/${triggers.maxTokenUsage} (${tokenUsagePercentage.toFixed(1)}%) - context exhaustion imminent`
        );
        this.proxySession.contextExhausted = true;
        this.proxySession.lastContextWarning = now;
        // Return compacted messages and mark for reset
        const compacted = this.compactMessages(messages);
        return compacted;
      } else if (tokenUsagePercentage >= 80) {
        // Warning threshold
        if (!this.proxySession.lastContextWarning || now - this.proxySession.lastContextWarning > 300000) { // 5 minutes
          this.log(
            `Token usage warning: ${this.proxySession.tokenUsage.total}/${triggers.maxTokenUsage} (${tokenUsagePercentage.toFixed(1)}%)`
          );
          this.proxySession.lastContextWarning = now;
        }
      }
    }

    // 3. Proactive age reset
    if (
      this.proxySession.createdAt > 0 &&
      now - this.proxySession.createdAt > triggers.ageMinutes * 60000
    ) {
      this.log(`Session age > ${triggers.ageMinutes} min, reset required`);
      // We can't reset here without agentId; the caller should call resetSession first
    }

    // 4. Auto-continuation reset
    if (this.proxySession.continuityCounter >= triggers.continuationLimit) {
      this.log(`Auto-continuation limit ${this.proxySession.continuityCounter}/${triggers.continuationLimit} reached`);
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

    const summary = this.summarizeConversation(old);
    const compacted: T[] = [];

    if (systemMsg) {
      compacted.push(systemMsg as T);
    }
    compacted.push({
      role: 'user',
      content: `[Previous conversation summary: ${summary}]\n\nContinue from the recent messages below.`,
    } as T);
    compacted.push(...recent);

    this.log(`Compacted: ${messages.length} → ${compacted.length} messages`);
    return compacted;
  }

  /**
   * Simple extractive summarization of old conversation turns.
   */
  private summarizeConversation(
    messages: Array<{ role: string; content: string }>
  ): string {
    // Keep user questions + first line of assistant responses
    const points: string[] = [];
    for (let i = 0; i < messages.length; i += 2) {
      const userMsg = messages[i];
      const assistantMsg = messages[i + 1];
      if (!userMsg || userMsg.role !== 'user') continue;

      const question = userMsg.content.trim().split('\n')[0].substring(0, 80);
      const answer = assistantMsg
        ? assistantMsg.content.trim().split('\n')[0].substring(0, 80)
        : '';
      points.push(`Q: ${question}${answer ? ` → A: ${answer}` : ''}`);
    }
    return points.slice(0, 5).join('; ');
  }

  async resetSession(agentId: string, reason?: 'message_limit' | 'token_limit' | 'age_limit' | 'manual'): Promise<boolean> {
    const resetUrl = `${this.baseUrl}/reset-session?agent=${encodeURIComponent(agentId)}`;
    try {
      const response = await fetch(resetUrl, { method: 'POST' });
      if (!response.ok) {
        this.log(`Proxy reset failed (${response.status}), attempting local reset only`);
        // Continue with local reset even if proxy reset failed
      } else {
        const data: ProxyResetResponse = await response.json();
        const resetReason = reason || 'manual';
        this.log(`Session reset (${resetReason}): ${data.status}, preserved ${data.history_preserved} items`);
      }

      // Reset local state
      this.proxySession.id = null;
      this.proxySession.messageCount = 0;
      this.proxySession.createdAt = Date.now();
      this.proxySession.continuityCounter = 0;
      this.proxySession.retryAttempts = 0;
      this.proxySession.history = [];

      // Reset token tracking
      this.proxySession.tokenUsage = {
        prompt: 0,
        completion: 0,
        total: 0,
        lastUpdated: Date.now(),
      };
      this.proxySession.contextExhausted = false;
      this.proxySession.lastContextWarning = undefined;

      return true;
    } catch (error: any) {
      this.log(`Proxy reset error: ${error.message}, attempting local reset only`);
      // Continue with local reset even if proxy reset failed
    }

    // Always perform local reset regardless of proxy reset success
    const resetReason = reason || 'manual';
    this.log(`Performing local session reset (${resetReason}) due to ${this.proxySession.contextExhausted ? 'context exhaustion' : 'limit reached'}`);

    // Additional context cleanup for token limit resets
    if (resetReason === 'token_limit') {
      this.log('Context exhaustion reset - clearing all context state');
    }

    return true;
  }

  recordContinuation(): void {
    this.proxySession.continuityCounter++;
    this.log(`Continuation ${this.proxySession.continuityCounter}/${this.limits.autoResetTriggers.continuationLimit}`);
  }

  recordRetry(): void {
    this.proxySession.retryAttempts++;
  }

  /**
   * Update token usage tracking
   */
  updateTokenUsage(promptTokens: number, completionTokens: number): void {
    if (!this.proxySession.tokenUsage) {
      this.proxySession.tokenUsage = {
        prompt: 0,
        completion: 0,
        total: 0,
        lastUpdated: Date.now(),
      };
    }

    this.proxySession.tokenUsage.prompt += promptTokens;
    this.proxySession.tokenUsage.completion += completionTokens;
    this.proxySession.tokenUsage.total = this.proxySession.tokenUsage.prompt + this.proxySession.tokenUsage.completion;
    this.proxySession.tokenUsage.lastUpdated = Date.now();

    const triggers = this.limits.autoResetTriggers;
    if (triggers.tokenUsagePercentage && triggers.maxTokenUsage) {
      const usagePercentage = (this.proxySession.tokenUsage.total / triggers.maxTokenUsage) * 100;
      this.log(`Token usage updated: ${this.proxySession.tokenUsage.total}/${triggers.maxTokenUsage} (${usagePercentage.toFixed(1)}%)`);
    }
  }

  /**
   * Check if context is exhausted and needs reset
   */
  isContextExhausted(): boolean {
    if (this.proxySession.contextExhausted) {
      return true;
    }

    if (this.proxySession.tokenUsage && this.limits.autoResetTriggers.tokenUsagePercentage && this.limits.autoResetTriggers.maxTokenUsage) {
      const maxTokenUsage = this.limits.autoResetTriggers.maxTokenUsage;
      const tokenUsagePercentage = this.limits.autoResetTriggers.tokenUsagePercentage;
      const usagePercentage = (this.proxySession.tokenUsage.total / maxTokenUsage) * 100;
      if (usagePercentage >= tokenUsagePercentage) {
        this.proxySession.contextExhausted = true;
        return true;
      }
    }

    return false;
  }

  getMessagesRemaining(): number | null {
    return Math.max(0, this.limits.maxMessages - this.proxySession.messageCount);
  }

  getTimeRemaining(): number | null {
    if (this.proxySession.createdAt <= 0) return null;
    return Math.max(0, this.limits.ttlMs - (Date.now() - this.proxySession.createdAt));
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


