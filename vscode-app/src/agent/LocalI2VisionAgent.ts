/**
 * LocalI2VisionAgent - TypeScript implementation of I2VisionAgent
 * 
 * This agent runs entirely in-process using the existing AgentBridge
 * infrastructure. It communicates directly with Ollama via the CLI
 * integration, bypassing the need for a Kotlin backend.
 * 
 * UPDATED: Added progress callback support for real-time tool call streaming
 */

import * as vscode from 'vscode';
import { AgentBridge, AgentConfig, AgentResponse as BridgeAgentResponse, ProgressCallback } from './AgentBridge';
import type { AgentChunk } from './AgentBridge';

/**
 * VSLFC Layer enumeration (matches Kotlin VslfcLayer)
 */
export enum VslfcLayer {
  VISION = 'VISION',
  STRUCTURE = 'STRUCTURE',
  LOGIC = 'LOGIC',
  FLOW = 'FLOW',
  CODE = 'CODE'
}

/**
 * Get display name for a VSLFC layer
 */
export function getLayerDisplayName(layer: VslfcLayer): string {
  const displayNames: Record<VslfcLayer, string> = {
    [VslfcLayer.VISION]: 'Vision',
    [VslfcLayer.STRUCTURE]: 'Structure',
    [VslfcLayer.LOGIC]: 'Logic',
    [VslfcLayer.FLOW]: 'Flow',
    [VslfcLayer.CODE]: 'Code'
  };
  return displayNames[layer];
}

/**
 * Get layer name (lowercase) for file naming
 */
export function getLayerName(layer: VslfcLayer): string {
  return layer.toLowerCase();
}

/**
 * Agent capabilities declaration
 */
export interface AgentCapabilities {
  supportsStreaming: boolean;
  supportsCancellation: boolean;
  maxIterations: number;
  availableTools: string[];
}

/**
 * Agent request (matches Kotlin AgentRequest)
 */
export interface AgentRequest {
  id: string;
  task: string;
  context: AgentContext;
  config?: Partial<AgentConfig>;
}

/**
 * Agent context (matches Kotlin AgentContext)
 */
export interface AgentContext {
  workspaceRoot: string;
  currentFile?: string;
  sessionId?: string;
  [key: string]: any;
}

/**
 * Agent configuration overrides (matches Kotlin AgentConfigOverrides)
 */
export interface AgentConfigOverrides {
  maxIterations?: number;
  toolTimeoutSeconds?: number;
  enableBuildVerification?: boolean;
  [key: string]: any;
}

/**
 * LocalI2VisionAgent - In-process TypeScript agent
 */
export class LocalI2VisionAgent implements vscode.Disposable {
  /** Unique agent instance identifier */
  readonly id: string;
  
  /** VSLFC layer this agent specializes in */
  readonly layer: VslfcLayer;
  
  /** Human-readable name for UI display */
  readonly displayName: string;
  
  /** Agent capabilities declaration */
  readonly capabilities: AgentCapabilities;
  
  private config: AgentConfig;
  private bridge: AgentBridge;
  private isInitialized: boolean = false;
  private outputChannel?: vscode.OutputChannel;
  private pendingRequests: Map<string, boolean> = new Map();

  constructor(
    layer: VslfcLayer,
    config: AgentConfig,
    outputChannel?: vscode.OutputChannel
  ) {
    this.id = this.generateAgentId(layer);
    this.layer = layer;
    this.config = config;
    this.outputChannel = outputChannel;
    
    // Create display name
    this.displayName = `${getLayerDisplayName(layer)} Agent (Ollama ${config.model.id})`;
    
    // Declare capabilities
    this.capabilities = {
      supportsStreaming: config.streaming.enabled,
      supportsCancellation: true,
      maxIterations: config.iterationSettings.maxIterations,
      availableTools: this.extractAvailableTools(config)
    };
    
    // Create the agent bridge
    // Pass extension root so agent can access extension source files
    // CRITICAL: workspaceRoot MUST be provided - get from workspace folders
    const workspaceRoot = vscode.workspace.workspaceFolders?.[0]?.uri.fsPath || '';
    if (!workspaceRoot) {
      throw new Error('CRITICAL: No workspace folder open. Please open a project folder first.');
    }
    
    this.bridge = new AgentBridge(
      config, 
      outputChannel,
      vscode.extensions.getExtension('i2vision.i2-vision-vscode')?.extensionPath,
      workspaceRoot
    );
    
    this.log(`LocalI2VisionAgent created: ${this.id}`);
  }

  /**
   * Initialize the agent
   */
  async initialize(): Promise<void> {
    if (this.isInitialized) {
      return;
    }

    this.log(`Initializing agent: ${this.displayName}`);
    await this.bridge.initialize();
    this.isInitialized = true;
    this.log(`Agent initialized: ${this.id}`);
  }

  /**
   * Process a task synchronously with optional progress callback
   */
  async process(
    request: AgentRequest, 
    _streamCallback?: (chunk: AgentChunk) => void,
    onProgress?: ProgressCallback
  ): Promise<BridgeAgentResponse> {
    if (!this.isInitialized) {
      await this.initialize();
    }

    this.log(`Processing request [${request.id}]: "${request.task.substring(0, 50)}..."`);
    this.pendingRequests.set(request.id, true);

    try {
      // Apply config overrides if provided
      if (request.config) {
        await this.updateConfig(request.config);
      }

      // Process through the bridge with progress callback
      const response = await this.bridge.process(
        request.task,
        request.context.currentFile,
        onProgress
      );

      this.log(`Request completed [${request.id}]: ${response.iterations} iterations, ${response.durationMs}ms`);
      return response;
    } catch (error: any) {
      this.log(`Request failed [${request.id}]: ${error.message}`);
      throw error;
    } finally {
      this.pendingRequests.delete(request.id);
    }
  }

  /**
   * Process a task with streaming responses
   */
  async *processStreaming(request: AgentRequest): AsyncGenerator<AgentChunk> {
    if (!this.isInitialized) {
      await this.initialize();
    }

    this.log(`Starting streaming request [${request.id}]`);
    this.pendingRequests.set(request.id, true);

    try {
      const startTime = Date.now();

      // Use the bridge's streaming method directly
      for await (const chunk of this.bridge.processStreaming(
        request.task,
        request.context.currentFile
      )) {
        yield chunk;
      }

      this.log(`Streaming completed [${request.id}]: ${Date.now() - startTime}ms`);
    } catch (error: any) {
      this.log(`Streaming failed [${request.id}]: ${error.message}`);
      yield {
        type: 'error',
        error: error.message,
        timestamp: Date.now()
      };
    } finally {
      this.pendingRequests.delete(request.id);
    }
  }

  /**
   * Cancel a running task
   */
  async cancel(requestId: string): Promise<boolean> {
    const isPending = this.pendingRequests.get(requestId);
    
    if (isPending) {
      this.log(`Cancelling request [${requestId}]`);
      this.pendingRequests.delete(requestId);
      // Note: AgentBridge doesn't support cancellation yet
      return true;
    }
    
    return false;
  }

  /**
   * Get the agent's current configuration
   */
  getConfig(): AgentConfig {
    return { ...this.config };
  }

  /**
   * Update runtime configuration
   */
  async updateConfig(overrides: AgentConfigOverrides): Promise<AgentConfig> {
    this.log(`Updating config with overrides: ${JSON.stringify(overrides)}`);
    
    // Apply overrides
    if (overrides.maxIterations !== undefined) {
      this.config.iterationSettings.maxIterations = overrides.maxIterations;
    }
    if (overrides.toolTimeoutSeconds !== undefined) {
      this.config.toolSelection.toolTimeoutSeconds = overrides.toolTimeoutSeconds;
    }
    if (overrides.enableBuildVerification !== undefined) {
      this.config.execution.enableBuildVerification = overrides.enableBuildVerification;
    }
    
    // Reinitialize bridge with new config
    await this.bridge.initialize();
    
    this.log(`Config updated successfully`);
    return { ...this.config };
  }

  /**
   * Dispose of agent resources
   */
  async dispose(): Promise<void> {
    this.log(`Disposing agent: ${this.id}`);
    
    // Cancel pending requests
    for (const [requestId] of this.pendingRequests) {
      await this.cancel(requestId);
    }
    
    this.log(`Agent disposed: ${this.id}`);
  }

  /**
   * Generate a unique agent ID
   */
  private generateAgentId(layer: VslfcLayer): string {
    const timestamp = Date.now();
    const random = Math.random().toString(16).substring(2, 6);
    return `agent-${layer}-${timestamp}-${random}`;
  }

  /**
   * Extract available tools from config
   */
  private extractAvailableTools(config: AgentConfig): string[] {
    const tools = [
      'list_directory',
      'read_file',
      'write_file',
      'edit_file',
      'search_files'
    ];
    
    // Filter based on config
    if (!config.execution.fileOperations.shell.listDirectoryEnabled) {
      tools.splice(tools.indexOf('list_directory'), 1);
    }
    if (!config.execution.fileOperations.shell.readFileEnabled) {
      tools.splice(tools.indexOf('read_file'), 1);
    }
    if (!config.execution.fileOperations.shell.writeFileEnabled) {
      tools.splice(tools.indexOf('write_file'), 1);
    }
    if (!config.execution.fileOperations.shell.regexSearchEnabled) {
      tools.splice(tools.indexOf('search_files'), 1);
    }
    
    return tools;
  }

  /**
   * Log message to output channel
   */
  private log(message: string): void {
    if (this.outputChannel) {
      const timestamp = new Date().toLocaleTimeString('en-US', { hour12: false });
      this.outputChannel.appendLine(`[${timestamp}] [${this.displayName}] ${message}`);
    }
  }
}
