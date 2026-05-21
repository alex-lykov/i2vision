/**
 * Universal Agent Communication Protocol (ACP)
 * 
 * JSON-RPC 2.0 compatible protocol for agent communication.
 * Transport-agnostic: works with stdio, HTTP, WebSocket.
 */

/**
 * Agent configuration reference
 */
export interface AgentConfigRef {
  /** Path to YAML config file or config key */
  configPath: string;
  /** Agent layer */
  layer?: 'vision' | 'structure' | 'logic' | 'flow' | 'code';
  /** Runtime overrides */
  overrides?: AgentConfigOverrides;
}

/**
 * Runtime agent configuration overrides
 */
export interface AgentConfigOverrides {
  model?: {
    id: string;
    provider: string;
  };
  maxIterations?: number;
  temperature?: number;
  tools?: {
    enabled?: string[];
    disabled?: string[];
  };
}

/**
 * Context from the client environment
 */
export interface AgentContext {
  /** Workspace root path */
  workspaceRoot: string;
  /** Currently open file */
  currentFile?: string;
  /** Selected files */
  selectedFiles?: string[];
  /** Cursor position */
  cursorPosition?: {
    line: number;
    character: number;
  };
  /** Path to discovery cache */
  discoveryCache?: string;
  /** VSLFC context */
  vslfcContext?: VSLFCContext;
  /** Additional custom context */
  custom?: Record<string, any>;
}

/**
 * VSLFC Context for architecture-aware agents
 */
export interface VSLFCContext {
  filePath: string;
  component?: {
    name: string;
    type: string;
    layer: string;
  };
  layer?: {
    name: string;
    level: number;
    allowedDependencies: string[];
  };
  responsibilities?: string[];
  dependencies?: string[];
  dependents?: string[];
  metrics?: {
    complexity?: number;
    coupling?: number;
    cohesion?: number;
  };
}

/**
 * UI-specific hints (advisory, not functional)
 */
export interface UiHints {
  /** Client type */
  clientType: 'vscode' | 'intellij' | 'web' | 'cli';
  /** Whether client supports streaming responses */
  supportsStreaming: boolean;
  /** Preferred chunk size for streaming */
  preferredChunkSize?: number;
  /** Color theme preference */
  theme?: 'light' | 'dark';
  /** Locale preference */
  locale?: string;
}

/**
 * Agent request (client → server)
 */
export interface AgentRequest {
  jsonrpc: '2.0';
  id: string | number;
  method: string;
  params: {
    /** Agent configuration */
    agent: AgentConfigRef;
    /** The task to execute */
    task: string;
    /** Context from client environment */
    context: AgentContext;
    /** UI hints */
    uiHints?: UiHints;
  };
}

/**
 * Tool call record
 */
export interface ToolCallRecord {
  toolName: string;
  args: Record<string, any>;
  result?: string;
  error?: string;
  durationMs?: number;
}

/**
 * Reasoning step for UI display
 */
export interface ReasoningStep {
  step: number;
  reasoning: string;
  toolCall?: ToolCallRecord;
  timestamp: number;
}

/**
 * Agent response (server → client)
 */
export interface AgentResponse {
  jsonrpc: '2.0';
  id: string | number;
  result?: {
    /** Outcome status */
    outcome: 'success' | 'iteration_limit' | 'error' | 'cancelled';
    /** Final text response */
    finalText?: string;
    /** Number of iterations */
    iterations: number;
    /** Tool calls made */
    toolCalls: ToolCallRecord[];
    /** Duration in milliseconds */
    durationMs: number;
    /** Agent reasoning trace */
    reasoningTrace?: ReasoningStep[];
    /** Metadata */
    metadata?: Record<string, any>;
  };
  error?: {
    code: number;
    message: string;
    data?: any;
  };
}

/**
 * Agent notification (server → client, unsolicited)
 */
export interface AgentNotification {
  jsonrpc: '2.0';
  method: string;
  params?: {
    /** Notification type */
    type: 'iteration' | 'tool_call' | 'progress' | 'stream' | 'status';
    /** Notification data */
    data: any;
    /** Request ID this notification relates to */
    requestId?: string | number;
  };
}

/**
 * Cancel request (client → server)
 */
export interface CancelRequest {
  jsonrpc: '2.0';
  id: string | number;
  method: 'cancel';
  params: {
    requestId: string | number;
  };
}

/**
 * Health check request
 */
export interface HealthRequest {
  jsonrpc: '2.0';
  id: string | number;
  method: 'health';
  params?: {};
}

/**
 * Health check response
 */
export interface HealthResponse {
  jsonrpc: '2.0';
  id: string | number;
  result?: {
    status: 'healthy' | 'degraded' | 'unhealthy';
    version: string;
    loadedModels: Array<{ name: string; provider: string }>;
    uptimeMs: number;
  };
  error?: {
    code: number;
    message: string;
  };
}

/**
 * Protocol message type union
 */
export type ProtocolMessage = 
  | AgentRequest 
  | AgentResponse 
  | AgentNotification 
  | CancelRequest 
  | HealthRequest 
  | HealthResponse;

/**
 * Parse a JSON-RPC message from string
 */
export function parseMessage(data: string): ProtocolMessage | null {
  try {
    const parsed = JSON.parse(data);
    if (parsed.jsonrpc === '2.0') {
      return parsed as ProtocolMessage;
    }
    return null;
  } catch {
    return null;
  }
}

/**
 * Serialize a message to JSON string
 */
export function serializeMessage(message: ProtocolMessage): string {
  return JSON.stringify(message);
}

/**
 * Generate a unique request ID
 */
export function generateRequestId(): string {
  return `${Date.now()}-${Math.random().toString(36).substring(2, 9)}`;
}
