/**
 * KotlinI2VisionAgent - TypeScript wrapper for Kotlin agent via JSON-RPC
 * 
 * This class communicates with the Kotlin agent server (DefaultAgentProvider)
 * via HTTP transport using the Universal Agent Communication Protocol (ACP).
 * 
 * Features:
 * - Request/Response processing
 * - Streaming support via SSE notifications
 * - Cancellation support
 * - Error handling with retry logic
 * - Session lifecycle management
 */

import * as vscode from 'vscode';
import { HttpTransport } from '../bridge/HttpTransport';
import {
  AgentRequest,
  AgentResponse,
  AgentNotification,
  CancelRequest,
  HealthRequest,
  HealthResponse,
  AgentConfigRef,
  AgentContext,
  UiHints,
  serializeMessage,
  generateRequestId,
  parseMessage
} from '../bridge/Protocol';
import { AgentTransport } from '../bridge/Transport';

/**
 * Agent configuration for Kotlin agent
 */
export interface KotlinAgentConfig {
  /** Agent server base URL */
  serverUrl: string;
  /** Default agent configuration ID */
  defaultConfigId?: string;
  /** Default VSLFC layer */
  defaultLayer: 'vision' | 'structure' | 'logic' | 'flow' | 'code';
  /** Request timeout in milliseconds */
  timeoutMs?: number;
  /** Enable streaming notifications */
  enableStreaming?: boolean;
  /** Maximum retry attempts */
  maxRetries?: number;
  /** Retry backoff in milliseconds */
  retryBackoffMs?: number[];
}

/**
 * Agent status information
 */
export interface AgentStatus {
  isConnected: boolean;
  isHealthy: boolean;
  serverVersion?: string;
  loadedModels?: Array<{ name: string; provider: string }>;
  uptimeMs?: number;
}

/**
 * Processing result from agent
 */
export interface ProcessingResult {
  success: boolean;
  finalText?: string;
  iterations: number;
  toolCalls: Array<{
    toolName: string;
    args: Record<string, any>;
    result?: string;
    error?: string;
  }>;
  durationMs: number;
  error?: string;
}

/**
 * KotlinI2VisionAgent - Wrapper for remote Kotlin agent
 */
export class KotlinI2VisionAgent implements vscode.Disposable {
  private config: KotlinAgentConfig;
  private transport: AgentTransport;
  private isInitialized: boolean = false;
  private outputChannel?: vscode.OutputChannel;
  private pendingRequests: Map<string | number, {
    resolve: (response: AgentResponse) => void;
    reject: (error: Error) => void;
    timeout: NodeJS.Timeout;
  }> = new Map();
  private notificationHandlers: Array<(notification: AgentNotification) => void> = [];
  private status: AgentStatus = {
    isConnected: false,
    isHealthy: false
  };

  constructor(
    config: KotlinAgentConfig,
    outputChannel?: vscode.OutputChannel
  ) {
    this.config = {
      timeoutMs: 300000, // 5 minutes
      enableStreaming: true,
      maxRetries: 3,
      retryBackoffMs: [1000, 2000, 4000],
      ...config
    };
    this.outputChannel = outputChannel;

    // Create HTTP transport
    this.transport = new HttpTransport({
      baseUrl: this.config.serverUrl,
      timeoutMs: this.config.timeoutMs,
      useSse: this.config.enableStreaming
    });

    // Register notification handler
    this.transport.onNotification((notification) => {
      this.handleNotification(notification);
    });
  }

  /**
   * Initialize the agent connection
   */
  async initialize(): Promise<void> {
    if (this.isInitialized) {
      return;
    }

    this.log('Initializing KotlinI2VisionAgent...');
    this.log(`Server URL: ${this.config.serverUrl}`);
    this.log(`Default layer: ${this.config.defaultLayer}`);

    try {
      // HttpTransport connects lazily on first request
      // Check health to verify connectivity
      const health = await this.checkHealth();
      this.status.isConnected = true;
      this.status.isHealthy = health.status === 'healthy';
      this.status.serverVersion = health.version;
      this.status.loadedModels = health.loadedModels;
      this.status.uptimeMs = health.uptimeMs;

      this.log(`Server health: ${health.status}`);
      this.log(`Server version: ${health.version}`);
      this.log(`Loaded models: ${health.loadedModels?.length || 0}`);

      this.isInitialized = true;
      this.log('KotlinI2VisionAgent initialized successfully');
    } catch (error: any) {
      this.log(`Initialization failed: ${error.message}`);
      this.status.isConnected = false;
      this.status.isHealthy = false;
      throw error;
    }
  }

  /**
   * Check server health
   */
  async checkHealth(): Promise<{
    status: 'healthy' | 'degraded' | 'unhealthy';
    version: string;
    loadedModels: Array<{ name: string; provider: string }>;
    uptimeMs: number;
  }> {
    const healthRequest: HealthRequest = {
      jsonrpc: '2.0',
      id: generateRequestId(),
      method: 'health',
      params: {}
    };

    const response = await this.transport.send(healthRequest) as HealthResponse;
    
    if (response.error) {
      throw new Error(`Health check failed: ${response.error.message}`);
    }

    if (!response.result) {
      throw new Error('Health check returned empty result');
    }

    return response.result;
  }

  /**
   * Process a task through the agent
   */
  async process(
    task: string,
    context?: Partial<AgentContext>,
    configOverrides?: AgentConfigRef['overrides']
  ): Promise<ProcessingResult> {
    if (!this.isInitialized) {
      await this.initialize();
    }

    const requestId = generateRequestId();
    this.log(`Processing task [${requestId}]: "${task.substring(0, 50)}..."`);

    const agentConfig: AgentConfigRef = {
      configPath: this.config.defaultConfigId || 'default',
      layer: this.config.defaultLayer,
      overrides: configOverrides
    };

    const agentContext: AgentContext = {
      workspaceRoot: vscode.workspace.workspaceFolders?.[0]?.uri.fsPath || '',
      currentFile: vscode.window.activeTextEditor?.document.uri.fsPath,
      ...context
    };

    const uiHints: UiHints = {
      clientType: 'vscode',
      supportsStreaming: this.config.enableStreaming || false,
      preferredChunkSize: 100,
      theme: vscode.window.activeColorTheme.kind === vscode.ColorThemeKind.Dark ? 'dark' : 'light',
      locale: vscode.env.language
    };

    const request: AgentRequest = {
      jsonrpc: '2.0',
      id: requestId,
      method: 'process',
      params: {
        agent: agentConfig,
        task: task,
        context: agentContext,
        uiHints: uiHints
      }
    };

    return this.sendWithRetry(request);
  }

  /**
   * Send request with retry logic
   */
  private async sendWithRetry(request: AgentRequest): Promise<ProcessingResult> {
    let lastError: Error | undefined;
    const maxRetries = this.config.maxRetries || 3;
    const backoffMs = this.config.retryBackoffMs || [1000, 2000, 4000];

    for (let attempt = 0; attempt <= maxRetries; attempt++) {
      try {
        if (attempt > 0) {
          this.log(`Retry attempt ${attempt}/${maxRetries}...`);
          const delay = backoffMs[Math.min(attempt - 1, backoffMs.length - 1)];
          await this.sleep(delay);
        }

        const response = await this.transport.send(request) as AgentResponse;

        if (response.error) {
          throw new Error(`Agent error: ${response.error.message}`);
        }

        if (!response.result) {
          throw new Error('Agent returned empty result');
        }

        const result: ProcessingResult = {
          success: response.result.outcome === 'success',
          finalText: response.result.finalText,
          iterations: response.result.iterations,
          toolCalls: response.result.toolCalls.map(tc => ({
            toolName: tc.toolName,
            args: tc.args,
            result: tc.result,
            error: tc.error
          })),
          durationMs: response.result.durationMs
        };

        this.log(`Task completed [${request.id}]: ${result.iterations} iterations, ${result.durationMs}ms`);
        return result;
      } catch (error: any) {
        lastError = error;
        this.log(`Attempt ${attempt + 1} failed: ${error.message}`);

        // Don't retry on cancellation
        if (error.message.includes('cancelled')) {
          break;
        }
      }
    }

    throw lastError || new Error('Request failed after all retries');
  }

  /**
   * Cancel a pending request
   */
  async cancel(requestId: string | number): Promise<void> {
    this.log(`Cancelling request [${requestId}]...`);

    const cancelRequest: CancelRequest = {
      jsonrpc: '2.0',
      id: generateRequestId(),
      method: 'cancel',
      params: {
        requestId: requestId
      }
    };

    try {
      await this.transport.send(cancelRequest);
      this.log(`Cancellation sent for [${requestId}]`);
    } catch (error: any) {
      this.log(`Cancellation failed: ${error.message}`);
      throw error;
    }
  }

  /**
   * Register notification handler
   */
  onNotification(handler: (notification: AgentNotification) => void): vscode.Disposable {
    this.notificationHandlers.push(handler);
    return {
      dispose: () => {
        const index = this.notificationHandlers.indexOf(handler);
        if (index >= 0) {
          this.notificationHandlers.splice(index, 1);
        }
      }
    };
  }

  /**
   * Handle incoming notifications
   */
  private handleNotification(notification: AgentNotification): void {
    this.log(`Notification: ${notification.method}`);

    for (const handler of this.notificationHandlers) {
      try {
        handler(notification);
      } catch (error: any) {
        this.log(`Notification handler error: ${error.message}`);
      }
    }
  }

  /**
   * Get current status
   */
  getStatus(): AgentStatus {
    return { ...this.status };
  }

  /**
   * Log a message
   */
  private log(message: string): void {
    const timestamp = new Date().toISOString();
    const logMessage = `[${timestamp}] [KotlinAgent] ${message}`;
    
    if (this.outputChannel) {
      this.outputChannel.appendLine(logMessage);
    } else {
      console.log(logMessage);
    }
  }

  /**
   * Sleep utility
   */
  private sleep(ms: number): Promise<void> {
    return new Promise(resolve => setTimeout(resolve, ms));
  }

  /**
   * Dispose resources
   */
  dispose(): void {
    this.log('Disposing KotlinI2VisionAgent...');
    
    // Cancel pending requests
    for (const [requestId, pending] of this.pendingRequests.entries()) {
      clearTimeout(pending.timeout);
      pending.reject(new Error('Agent disposed'));
    }
    this.pendingRequests.clear();

    // Dispose transport
    this.transport.dispose();

    this.isInitialized = false;
    this.status.isConnected = false;
    this.status.isHealthy = false;

    this.log('KotlinI2VisionAgent disposed');
  }
}

/**
 * Factory function to create KotlinI2VisionAgent
 */
export function createKotlinAgent(
  config: KotlinAgentConfig,
  outputChannel?: vscode.OutputChannel
): KotlinI2VisionAgent {
  return new KotlinI2VisionAgent(config, outputChannel);
}
