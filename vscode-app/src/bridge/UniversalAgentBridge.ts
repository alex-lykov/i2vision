/**
 * Universal Agent Bridge
 * 
 * Abstract bridge that handles protocol-level concerns.
 * Transport-specific implementations only need to implement buildContext().
 */

import * as vscode from 'vscode';
import { 
  AgentRequest, 
  AgentResponse, 
  AgentContext, 
  VSLFCContext,
  UiHints,
  AgentNotification,
  CancelRequest,
  HealthRequest,
  generateRequestId
} from './Protocol';
import { AgentTransport } from './Transport';
import { I2VisionUiConfig } from './I2VisionUiConfig';

/**
 * Abstract base class for agent bridges
 */
export abstract class UniversalAgentBridge {
  protected transport: AgentTransport;
  protected uiConfig: I2VisionUiConfig;
  private activeRequests: Map<string, AgentRequest> = new Map();

  constructor(transport: AgentTransport, uiConfig: I2VisionUiConfig) {
    this.transport = transport;
    this.uiConfig = uiConfig;

    // Set up notification handling
    this.transport.onNotification((notification) => {
      this.handleNotification(notification);
    });
  }

  /**
   * Build context from the client environment.
   * THIS IS THE ONLY CLIENT-SPECIFIC PART.
   */
  protected abstract buildContext(): Promise<AgentContext>;

  /**
   * Process a user task through the agent.
   */
  async processTask(
    task: string,
    agentLayer?: 'vision' | 'structure' | 'logic' | 'flow' | 'code'
  ): Promise<AgentResponse> {
    const context = await this.buildContext();
    
    const requestId = generateRequestId();
    
    const request: AgentRequest = {
      jsonrpc: '2.0',
      id: requestId,
      method: 'process',
      params: {
        agent: {
          configPath: this.getConfigPath(agentLayer),
          layer: agentLayer,
          overrides: this.getRuntimeOverrides()
        },
        task,
        context,
        uiHints: this.getUiHints()
      }
    };

    // Track active request
    this.activeRequests.set(requestId, request);

    console.log(`[UniversalAgentBridge] Processing task: ${task.substring(0, 100)}...`);
    console.log(`[UniversalAgentBridge] Agent layer: ${agentLayer || 'default'}`);
    console.log(`[UniversalAgentBridge] Context: workspace=${context.workspaceRoot}`);

    try {
      const response = await this.transport.send(request);
      
      // Clean up tracking
      this.activeRequests.delete(requestId);

      console.log(`[UniversalAgentBridge] Response outcome: ${response.result?.outcome || 'error'}`);
      console.log(`[UniversalAgentBridge] Iterations: ${response.result?.iterations || 0}`);
      console.log(`[UniversalAgentBridge] Duration: ${response.result?.durationMs || 0}ms`);

      return response;
    } catch (error: any) {
      // Clean up tracking on error
      this.activeRequests.delete(requestId);
      
      console.error('[UniversalAgentBridge] Request failed:', error.message);
      throw error;
    }
  }

  /**
   * Cancel a running agent task.
   */
  async cancelTask(requestId: string): Promise<void> {
    const request = this.activeRequests.get(requestId);
    if (!request) {
      console.log(`[UniversalAgentBridge] No active request found for ID: ${requestId}`);
      return;
    }

    const cancelRequest: CancelRequest = {
      jsonrpc: '2.0',
      id: generateRequestId(),
      method: 'cancel',
      params: { requestId }
    };

    console.log(`[UniversalAgentBridge] Cancelling request: ${requestId}`);
    await this.transport.send(cancelRequest);
    this.activeRequests.delete(requestId);
  }

  /**
   * Check server health
   */
  async checkHealth(): Promise<any> {
    const healthRequest: HealthRequest = {
      jsonrpc: '2.0',
      id: generateRequestId(),
      method: 'health',
      params: {}
    };

    try {
      const response = await this.transport.send(healthRequest);
      return response.result;
    } catch (error: any) {
      console.error('[UniversalAgentBridge] Health check failed:', error.message);
      throw error;
    }
  }

  /**
   * Handle notifications from server
   */
  private handleNotification(notification: AgentNotification): void {
    console.log(`[UniversalAgentBridge] Notification: ${notification.method}`, notification.params);

    // Emit events based on notification type
    const type = notification.params?.type;
    const data = notification.params?.data;

    switch (type) {
      case 'iteration':
        this.onIteration(data);
        break;
      case 'tool_call':
        this.onToolCall(data);
        break;
      case 'progress':
        this.onProgress(data);
        break;
      case 'stream':
        this.onStream(data);
        break;
      case 'status':
        this.onStatus(data);
        break;
    }
  }

  /**
   * Override these methods in subclasses to handle notifications
   */
  protected onIteration(data: any): void {}
  protected onToolCall(data: any): void {}
  protected onProgress(data: any): void {}
  protected onStream(data: any): void {}
  protected onStatus(data: any): void {}

  /**
   * Get config path for agent layer
   */
  private getConfigPath(layer?: string): string {
    const config = vscode.workspace.getConfiguration('i2vision');
    const configPath = config.get<string>('agents.configPath', '.vscode/i2vision/agents');
    
    if (layer) {
      return `${configPath}/${layer}-agent.yaml`;
    }
    return `${configPath}/coding-agent.yaml`;
  }

  /**
   * Get runtime overrides from VS Code settings
   */
  private getRuntimeOverrides() {
    const config = vscode.workspace.getConfiguration('i2vision');
    
    const modelOverride = config.get<string>('model.override');
    const modelId = config.get<string>('model.id');
    const modelProvider = config.get<string>('model.provider');

    return {
      model: modelOverride ? {
        id: modelId || 'codellama:13b',
        provider: modelProvider || 'ollama'
      } : undefined,
      maxIterations: config.get<number>('agent.maxIterations'),
      temperature: config.get<number>('model.temperature')
    };
  }

  /**
   * Get UI hints for the server
   */
  private getUiHints(): UiHints {
    return {
      clientType: 'vscode',
      supportsStreaming: this.uiConfig.streaming.enabled,
      preferredChunkSize: this.uiConfig.streaming.chunkSize,
      theme: vscode.window.activeColorTheme.kind === vscode.ColorThemeKind.Dark ? 'dark' : 'light',
      locale: vscode.env.language
    };
  }

  /**
   * Check if transport is connected
   */
  isConnected(): boolean {
    return this.transport.isConnected();
  }

  /**
   * Clean up resources
   */
  dispose(): void {
    console.log('[UniversalAgentBridge] Disposing...');
    
    // Cancel all active requests
    for (const [requestId] of this.activeRequests) {
      this.cancelTask(requestId).catch(console.error);
    }
    this.activeRequests.clear();

    this.transport.dispose();
  }
}
