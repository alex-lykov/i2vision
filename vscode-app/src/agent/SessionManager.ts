/**
 * SessionManager - Provider-agnostic interface for managing LLM session lifecycle.
 *
 * Different providers have different session semantics:
 * - Ollama / DeepSeek: stateless per-request (no-op)
 * - 3D LLM proxy: session-based with TTL and message limits
 *
 * Implementations handle provider-specific session discovery, reset,
 * message compaction, and health monitoring.
 */

export interface ProxySessionState {
  /** Proxy-assigned session identifier */
  id: string | null;

  /** Messages consumed in this session */
  messageCount: number;

  /** Session creation timestamp */
  createdAt: number;

  /** Upstream account identifier (if available) */
  accountId: string | null;

  /** Last known history buffer from proxy */
  history: Array<{ user: string; assistant: string }>;

  /** Number of auto-continuations detected */
  continuityCounter: number;

  /** Retry / reset attempts this session */
  retryAttempts: number;
}

export interface SessionHealth {
  healthy: boolean;
  /** Provider-specific diagnostics */
  diagnostics: Record<string, any>;
  warnings: string[];
}

export interface SessionLimits {
  maxMessages: number;
  ttlMs: number;
  maxPromptChars: number;
  maxHistoryLength: number;
  maxHistoryChars: number;

  /** Proactive reset triggers (before hard limits) */
  autoResetTriggers: {
    messageCount: number;
    ageMinutes: number;
    continuationLimit: number;
  };
}

export interface SessionManager {
  /** Human-readable name */
  readonly name: string;

  /** Provider this manager serves */
  readonly provider: string;

  /** Configure with provider-specific base URL and optional overrides */
  configure(baseUrl: string, limits?: Partial<SessionLimits>): void;

  /** Discover current session state from provider */
  syncSession(agentId: string): Promise<void>;

  /** Get the currently known session state (null if not yet synced) */
  getSessionState(): ProxySessionState | null;

  /** Get the configured limits */
  getLimits(): SessionLimits | null;

  /** Check provider health */
  checkHealth(): Promise<SessionHealth>;

  /**
   * Proactively manage messages before sending.
   * Returns potentially compacted / restructured messages.
   */
  manageMessages<T extends { role: string; content: string }>(messages: T[]): Promise<T[]>;

  /** Reset the upstream session */
  resetSession(agentId: string): Promise<boolean>;

  /** Record that an auto-continuation occurred */
  recordContinuation(): void;

  /** Record that a retry was needed */
  recordRetry(): void;

  /** Estimate how many messages remain before auto-reset */
  getMessagesRemaining(): number | null;

  /** Estimate time remaining before TTL expiry */
  getTimeRemaining(): number | null;

  /** Serialize state for persistence */
  serialize(): object;

  /** Deserialize state from persisted data */
  deserialize(data: any): void;
}

/**
 * NullSessionManager - No-op implementation for stateless providers
 * (Ollama, standard DeepSeek, OpenAI, etc.)
 */
export class NullSessionManager implements SessionManager {
  readonly name = 'Null';
  readonly provider: string;

  constructor(provider: string) {
    this.provider = provider;
  }

  configure(): void { /* no-op */ }

  async syncSession(): Promise<void> { /* no-op */ }

  getSessionState(): null { return null; }

  getLimits(): null { return null; }

  async checkHealth(): Promise<SessionHealth> {
    return { healthy: true, diagnostics: {}, warnings: [] };
  }

  async manageMessages<T extends { role: string; content: string }>(messages: T[]): Promise<T[]> { return messages; }

  async resetSession(): Promise<boolean> { return true; }

  recordContinuation(): void { /* no-op */ }

  recordRetry(): void { /* no-op */ }

  getMessagesRemaining(): null { return null; }

  getTimeRemaining(): null { return null; }

  serialize(): object { return { provider: this.provider }; }

  deserialize(): void { /* no-op */ }
}

/**
 * Factory that creates the right SessionManager for a provider.
 */
export function createSessionManager(provider: string, baseUrl?: string): SessionManager {
  switch (provider) {
    case '3d-llm':
      // Lazy import to avoid circular dependency
      const { ProxySessionManager } = require('./ProxySessionManager');
      const mgr = new ProxySessionManager();
      if (baseUrl) mgr.configure(baseUrl);
      return mgr;
    case 'ollama':
    case 'deepseek':
    default:
      return new NullSessionManager(provider);
  }
}
