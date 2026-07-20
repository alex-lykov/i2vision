/**
 * ContextMeter - Comprehensive context usage tracking and metering model
 *
 * Tracks token usage, session health, latency, and provider-specific limits
 * across the conversation lifecycle. Provides predictive warnings and
 * actionable summaries for context management.
 */

export interface TokenUsageSnapshot {
  prompt: number;
  completion: number;
  total: number;
  reasoning?: number;
  timestamp: number;
}

export interface SessionMetrics {
  messageCount: number;
  maxMessages: number;
  sessionAgeMs: number;
  sessionTtlMs: number;
  lastResetAt: number;
  retryCount: number;
}

export interface LatencyMetrics {
  lastLatencyMs: number;
  avgLatencyMs: number;
  minLatencyMs: number;
  maxLatencyMs: number;
  samples: number;
}

export interface ProviderLimits {
  maxContextTokens: number;
  maxMessages: number;
  sessionTtlMs: number;
  maxPromptChars: number;
}

export interface ContextUsageSummary {
  /** Overall health: 'healthy' | 'warning' | 'critical' | 'exhausted' */
  status: 'healthy' | 'warning' | 'critical' | 'exhausted';

  /** Human-readable status description */
  statusText: string;

  /** Token usage details */
  tokens: {
    used: number;
    total: number;
    percentage: number;
    prompt: number;
    completion: number;
    reasoning?: number;
    remaining: number;
  };

  /** Session message usage */
  messages: {
    used: number;
    total: number;
    percentage: number;
    remaining: number;
  };

  /** Session time-to-live */
  sessionTtl: {
    remainingMs: number;
    totalMs: number;
    percentage: number;
    expiresAt: Date;
  };

  /** Latency summary */
  latency: {
    lastMs: number;
    avgMs: number;
    minMs: number;
    maxMs: number;
  };

  /** Retry tracking */
  retries: number;

  /** Warnings and recommendations */
  warnings: string[];

  /** Timestamp of this summary */
  timestamp: number;
}

/**
 * Default provider limits.
 * Can be overridden per-provider.
 */
const DEFAULT_LIMITS: Record<string, ProviderLimits> = {
  ollama: {
    maxContextTokens: 32768,
    maxMessages: Infinity,
    sessionTtlMs: Infinity,
    maxPromptChars: Infinity,
  },
  deepseek: {
    maxContextTokens: 64000,
    maxMessages: Infinity,
    sessionTtlMs: Infinity,
    maxPromptChars: Infinity,
  },
  '3d-llm': {
    maxContextTokens: 64000,       // DeepSeek V3 context
    maxMessages: 100,              // Auto-reset at 100 messages
    sessionTtlMs: 2 * 60 * 60 * 1000, // 2 hours
    maxPromptChars: 80000,         // DEEPSEEK_MAX_PROMPT_CHARS
  },
};

export class ContextMeter {
  private provider: string = 'ollama';
  private modelId: string = '';
  private contextLength: number = 32768;

  /** Token usage history */
  private tokenHistory: TokenUsageSnapshot[] = [];

  /** Current cumulative token usage */
  private currentTokens: TokenUsageSnapshot = {
    prompt: 0,
    completion: 0,
    total: 0,
    reasoning: 0,
    timestamp: Date.now(),
  };

  /** Session metrics */
  private sessionMetrics: SessionMetrics = {
    messageCount: 0,
    maxMessages: Infinity,
    sessionAgeMs: 0,
    sessionTtlMs: Infinity,
    lastResetAt: Date.now(),
    retryCount: 0,
  };

  /** Latency tracking */
  private latencyMetrics: LatencyMetrics = {
    lastLatencyMs: 0,
    avgLatencyMs: 0,
    minLatencyMs: Infinity,
    maxLatencyMs: 0,
    samples: 0,
  };

  /** Provider-specific limits */
  private limits: ProviderLimits = DEFAULT_LIMITS.ollama;

  /** Custom limits override */
  private customLimits?: Partial<ProviderLimits>;

  /**
   * Initialize or reconfigure the meter for a specific provider/model.
   */
  configure(
    provider: string,
    modelId: string,
    contextLength: number,
    customLimits?: Partial<ProviderLimits>
  ): void {
    this.provider = provider;
    this.modelId = modelId;
    this.contextLength = contextLength;
    this.customLimits = customLimits;

    const baseLimits = DEFAULT_LIMITS[provider] || DEFAULT_LIMITS.ollama;
    this.limits = {
      ...baseLimits,
      maxContextTokens: contextLength,
      ...customLimits,
    };

    // Reset session metrics on provider change
    this.sessionMetrics = {
      messageCount: 0,
      maxMessages: this.limits.maxMessages,
      sessionAgeMs: 0,
      sessionTtlMs: this.limits.sessionTtlMs,
      lastResetAt: Date.now(),
      retryCount: 0,
    };

    this.tokenHistory = [];
    this.currentTokens = { prompt: 0, completion: 0, total: 0, timestamp: Date.now() };
    this.latencyMetrics = { lastLatencyMs: 0, avgLatencyMs: 0, minLatencyMs: Infinity, maxLatencyMs: 0, samples: 0 };
  }

  /**
   * Record a new token usage snapshot from an LLM response.
   */
  recordTokenUsage(usage: { prompt: number; completion: number; total: number; reasoning?: number }): void {
    const snapshot: TokenUsageSnapshot = {
      prompt: usage.prompt,
      completion: usage.completion,
      total: usage.total,
      reasoning: usage.reasoning,
      timestamp: Date.now(),
    };

    this.tokenHistory.push(snapshot);
    // Replace with the latest snapshot — usage.total from the LLM already
    // reflects the full prompt context (including conversation history).
    // Accumulating would double-count tokens across turns.
    this.currentTokens.prompt = usage.prompt;
    this.currentTokens.completion = usage.completion;
    this.currentTokens.total = usage.total;
    if (usage.reasoning !== undefined) {
      this.currentTokens.reasoning = usage.reasoning;
    }
    this.currentTokens.timestamp = Date.now();

    // Increment message count for each token usage event
    this.sessionMetrics.messageCount++;
  }

  /**
   * Estimate token usage from raw text (for streaming or pre-send estimates).
   */
  estimateTokensFromText(text: string): number {
    // Conservative: ~4 chars per token for mixed text/code
    return Math.ceil(text.length / 4);
  }

  /**
   * Record request latency.
   */
  recordLatency(latencyMs: number): void {
    const lm = this.latencyMetrics;
    lm.lastLatencyMs = latencyMs;
    lm.minLatencyMs = Math.min(lm.minLatencyMs, latencyMs);
    lm.maxLatencyMs = Math.max(lm.maxLatencyMs, latencyMs);
    lm.samples++;
    // Rolling average
    lm.avgLatencyMs = (lm.avgLatencyMs * (lm.samples - 1) + latencyMs) / lm.samples;
  }

  /**
   * Record a retry event.
   */
  recordRetry(): void {
    this.sessionMetrics.retryCount++;
  }

  /**
   * Record session data fetched from provider API.
   */
  recordSessionData(data: {
    messageCount?: number;
    sessionAgeMs?: number;
    sessionTtlMs?: number;
  }): void {
    if (data.messageCount !== undefined) {
      this.sessionMetrics.messageCount = data.messageCount;
    }
    if (data.sessionAgeMs !== undefined) {
      this.sessionMetrics.sessionAgeMs = data.sessionAgeMs;
    }
    if (data.sessionTtlMs !== undefined) {
      this.sessionMetrics.sessionTtlMs = data.sessionTtlMs;
    }
  }

  /**
   * Mark session as reset.
   */
  markSessionReset(): void {
    this.sessionMetrics.lastResetAt = Date.now();
    this.sessionMetrics.messageCount = 0;
    this.sessionMetrics.retryCount = 0;
    this.currentTokens = { prompt: 0, completion: 0, total: 0, timestamp: Date.now() };
    this.tokenHistory = [];
    // Keep latency stats
  }

  /**
   * Get the overall usage summary with status and warnings.
   */
  getUsageSummary(): ContextUsageSummary {
    const now = Date.now();
    const warnings: string[] = [];

    // Token calculations
    const tokenUsed = this.currentTokens.total || 0;
    const tokenTotal = this.contextLength;
    const tokenPercentage = tokenTotal > 0 ? (tokenUsed / tokenTotal) * 100 : 0;
    const tokenRemaining = Math.max(0, tokenTotal - tokenUsed);

    // Message calculations
    const msgUsed = this.sessionMetrics.messageCount;
    const msgTotal = this.limits.maxMessages;
    const msgPercentage = msgTotal !== Infinity && msgTotal > 0 ? (msgUsed / msgTotal) * 100 : 0;
    const msgRemaining = msgTotal !== Infinity ? Math.max(0, msgTotal - msgUsed) : Infinity;

    // TTL calculations
    const sessionAge = now - this.sessionMetrics.lastResetAt;
    const ttlTotal = this.limits.sessionTtlMs;
    const ttlRemaining = ttlTotal !== Infinity ? Math.max(0, ttlTotal - sessionAge) : Infinity;
    const ttlPercentage = ttlTotal !== Infinity && ttlTotal > 0 ? (sessionAge / ttlTotal) * 100 : 0;
    const expiresAt = new Date(this.sessionMetrics.lastResetAt + (ttlTotal !== Infinity ? ttlTotal : 0));

    // Determine overall status
    let status: ContextUsageSummary['status'] = 'healthy';
    let statusText = 'Context usage is healthy';

    if (tokenPercentage >= 100 || msgPercentage >= 100 || (ttlRemaining === 0 && ttlTotal !== Infinity)) {
      status = 'exhausted';
      statusText = 'Context exhausted — reset required';
    } else if (tokenPercentage >= 80 || msgPercentage >= 80 || (ttlRemaining <= 10 * 60 * 1000 && ttlTotal !== Infinity)) {
      status = 'critical';
      statusText = 'Context critical — approaching limits';
    } else if (tokenPercentage >= 50 || msgPercentage >= 50 || (ttlRemaining <= 30 * 60 * 1000 && ttlTotal !== Infinity)) {
      status = 'warning';
      statusText = 'Context usage elevated — monitor closely';
    }

    // Build warnings
    if (tokenPercentage >= 80) {
      warnings.push(`Token usage at ${tokenPercentage.toFixed(1)}% (${tokenUsed.toLocaleString()} / ${tokenTotal.toLocaleString()})`);
    }
    if (msgTotal !== Infinity && msgRemaining <= 20) {
      warnings.push(`Only ${msgRemaining} messages remaining before auto-reset`);
    }
    if (ttlTotal !== Infinity && ttlRemaining <= 10 * 60 * 1000) {
      const mins = Math.ceil(ttlRemaining / 60000);
      warnings.push(`Session expires in ~${mins} minute${mins > 1 ? 's' : ''}`);
    }
    if (this.sessionMetrics.retryCount > 0) {
      warnings.push(`${this.sessionMetrics.retryCount} auto-retry${this.sessionMetrics.retryCount > 1 ? 'ies' : ''} occurred`);
    }
    if (this.latencyMetrics.avgLatencyMs > 10000) {
      warnings.push(`Average latency is ${(this.latencyMetrics.avgLatencyMs / 1000).toFixed(1)}s (high)`);
    }

    return {
      status,
      statusText,
      tokens: {
        used: tokenUsed,
        total: tokenTotal,
        percentage: tokenPercentage,
        prompt: this.currentTokens.prompt,
        completion: this.currentTokens.completion,
        reasoning: this.currentTokens.reasoning,
        remaining: tokenRemaining,
      },
      messages: {
        used: msgUsed,
        total: msgTotal,
        percentage: msgPercentage,
        remaining: msgRemaining,
      },
      sessionTtl: {
        remainingMs: ttlRemaining,
        totalMs: ttlTotal,
        percentage: ttlPercentage,
        expiresAt,
      },
      latency: {
        lastMs: this.latencyMetrics.lastLatencyMs,
        avgMs: this.latencyMetrics.avgLatencyMs,
        minMs: this.latencyMetrics.minLatencyMs === Infinity ? 0 : this.latencyMetrics.minLatencyMs,
        maxMs: this.latencyMetrics.maxLatencyMs,
      },
      retries: this.sessionMetrics.retryCount,
      warnings,
      timestamp: now,
    };
  }

  /**
   * Check if any limit is near exhaustion.
   */
  isNearLimit(thresholdPercentage: number = 80): boolean {
    const summary = this.getUsageSummary();
    return (
      summary.tokens.percentage >= thresholdPercentage ||
      summary.messages.percentage >= thresholdPercentage ||
      (summary.sessionTtl.percentage >= thresholdPercentage && this.limits.sessionTtlMs !== Infinity)
    );
  }

  /**
   * Check if context is fully exhausted.
   */
  isExhausted(): boolean {
    const summary = this.getUsageSummary();
    return summary.status === 'exhausted';
  }

  /**
   * Get estimated time until next auto-reset (for providers with TTL).
   */
  getEstimatedTimeToReset(): number | null {
    if (this.limits.sessionTtlMs === Infinity) {
      return null;
    }
    const elapsed = Date.now() - this.sessionMetrics.lastResetAt;
    const remaining = Math.max(0, this.limits.sessionTtlMs - elapsed);
    return remaining;
  }

  /**
   * Get estimated messages remaining before auto-reset.
   */
  getEstimatedMessagesRemaining(): number | null {
    if (this.limits.maxMessages === Infinity) {
      return null;
    }
    return Math.max(0, this.limits.maxMessages - this.sessionMetrics.messageCount);
  }

  /**
   * Get token usage history.
   */
  getTokenHistory(): TokenUsageSnapshot[] {
    return [...this.tokenHistory];
  }

  /**
   * Get the current provider being tracked.
   */
  getProvider(): string {
    return this.provider;
  }

  /**
   * Get the current model being tracked.
   */
  getModelId(): string {
    return this.modelId;
  }

  /**
   * Serialize meter state for persistence.
   */
  serialize(): object {
    return {
      provider: this.provider,
      modelId: this.modelId,
      contextLength: this.contextLength,
      tokenHistory: this.tokenHistory,
      currentTokens: this.currentTokens,
      sessionMetrics: this.sessionMetrics,
      latencyMetrics: this.latencyMetrics,
      customLimits: this.customLimits,
    };
  }

  /**
   * Deserialize meter state from persisted data.
   */
  deserialize(data: any): void {
    if (!data) return;
    this.provider = data.provider || 'ollama';
    this.modelId = data.modelId || '';
    this.contextLength = data.contextLength || 32768;
    this.tokenHistory = data.tokenHistory || [];
    this.currentTokens = data.currentTokens || { prompt: 0, completion: 0, total: 0, timestamp: Date.now() };
    this.sessionMetrics = data.sessionMetrics || {
      messageCount: 0,
      maxMessages: Infinity,
      sessionAgeMs: 0,
      sessionTtlMs: Infinity,
      lastResetAt: Date.now(),
      retryCount: 0,
    };
    this.latencyMetrics = data.latencyMetrics || {
      lastLatencyMs: 0,
      avgLatencyMs: 0,
      minLatencyMs: Infinity,
      maxLatencyMs: 0,
      samples: 0,
    };
    this.customLimits = data.customLimits;

    // Re-derive limits
    const baseLimits = DEFAULT_LIMITS[this.provider] || DEFAULT_LIMITS.ollama;
    this.limits = {
      ...baseLimits,
      maxContextTokens: this.contextLength,
      ...this.customLimits,
    };
  }
}
