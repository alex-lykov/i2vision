/**
 * Stdio Transport Implementation
 * 
 * Communicates with agent server via standard input/output.
 * Used for local CLI processes and embedded servers.
 */

import { ChildProcess, spawn } from 'child_process';
import { AgentRequest, AgentResponse, AgentNotification, CancelRequest, HealthRequest, parseMessage, serializeMessage } from './Protocol';
import { AgentTransport } from './Transport';

export interface StdioTransportOptions {
  command: string;
  cwd?: string;
  env?: Record<string, string>;
  timeoutMs?: number;
}

export class StdioTransport implements AgentTransport {
  private process: ChildProcess | null = null;
  private responseHandlers: Map<string | number, {
    resolve: (response: AgentResponse) => void;
    reject: (error: Error) => void;
    timeout: NodeJS.Timeout;
  }> = new Map();
  
  private notificationHandlers: Array<(notification: AgentNotification) => void> = [];
  private options: StdioTransportOptions;
  private connected: boolean = false;
  private buffer: string = '';

  constructor(options: StdioTransportOptions) {
    this.options = {
      timeoutMs: 300000, // 5 minutes default
      ...options
    };
  }

  /**
   * Start the process
   */
  async connect(): Promise<void> {
    if (this.process) {
      throw new Error('Transport already connected');
    }

    const [executable, ...args] = this.options.command.split(' ');
    const cwd = this.options.cwd || process.cwd();
    const env = this.options.env || process.env;

    console.log(`[StdioTransport] Starting process: ${executable} ${args.join(' ')}`);
    console.log(`[StdioTransport] Working directory: ${cwd}`);

    this.process = spawn(executable, args, {
      cwd,
      env,
      stdio: ['pipe', 'pipe', 'pipe']
    });

    this.process.on('error', (error) => {
      console.error('[StdioTransport] Process error:', error);
      this.connected = false;
    });

    this.process.on('exit', (code) => {
      console.log(`[StdioTransport] Process exited with code ${code}`);
      this.connected = false;
      this.process = null;
    });

    // Handle stdout
    this.process.stdout?.on('data', (data: Buffer) => {
      this.buffer += data.toString();
      this.processBuffer();
    });

    // Handle stderr (log only)
    this.process.stderr?.on('data', (data: Buffer) => {
      const lines = data.toString().split('\n').filter(l => l.trim());
      for (const line of lines) {
        console.log(`[StdioTransport] Server: ${line}`);
      }
    });

    this.connected = true;
    console.log('[StdioTransport] Connected');
  }

  /**
   * Process buffered data for complete messages
   */
  private processBuffer(): void {
    const lines = this.buffer.split('\n');
    this.buffer = lines.pop() || ''; // Keep incomplete line in buffer

    for (const line of lines) {
      const trimmed = line.trim();
      if (!trimmed) continue;

      try {
        const message = parseMessage(trimmed);
        if (!message) {
          console.log(`[StdioTransport] Non-JSON output: ${trimmed.substring(0, 100)}`);
          continue;
        }

        if ('id' in message && message.id !== undefined) {
          // Response
          const handler = this.responseHandlers.get(message.id);
          if (handler) {
            clearTimeout(handler.timeout);
            this.responseHandlers.delete(message.id);
            handler.resolve(message as AgentResponse);
          } else {
            console.log(`[StdioTransport] No handler for response ID: ${message.id}`);
          }
        } else {
          // Notification
          const notification = message as AgentNotification;
          for (const handler of this.notificationHandlers) {
            handler(notification);
          }
        }
      } catch (error: any) {
        console.error('[StdioTransport] Error parsing message:', error.message);
      }
    }
  }

  /**
   * Send a request
   */
  async send(request: AgentRequest | CancelRequest | HealthRequest): Promise<AgentResponse> {
    if (!this.process || !this.connected) {
      throw new Error('Transport not connected');
    }

    return new Promise((resolve, reject) => {
      const timeout = setTimeout(() => {
        this.responseHandlers.delete(request.id);
        reject(new Error(`Request timeout after ${this.options.timeoutMs}ms`));
      }, this.options.timeoutMs);

      this.responseHandlers.set(request.id, {
        resolve,
        reject,
        timeout
      });

      const json = serializeMessage(request);
      console.log(`[StdioTransport] Sending: ${json.substring(0, 200)}...`);
      
      this.process!.stdin!.write(json + '\n', (error) => {
        if (error) {
          clearTimeout(timeout);
          this.responseHandlers.delete(request.id);
          reject(error);
        }
      });
    });
  }

  /**
   * Register notification callback
   */
  onNotification(callback: (notification: AgentNotification) => void): void {
    this.notificationHandlers.push(callback);
  }

  /**
   * Check if connected
   */
  isConnected(): boolean {
    return this.connected && this.process !== null;
  }

  /**
   * Clean up
   */
  dispose(): void {
    console.log('[StdioTransport] Disposing...');
    
    // Reject all pending requests
    for (const [id, handler] of this.responseHandlers) {
      clearTimeout(handler.timeout);
      handler.reject(new Error('Transport disposed'));
    }
    this.responseHandlers.clear();

    if (this.process) {
      this.process.kill();
      this.process = null;
    }
    
    this.connected = false;
    this.buffer = '';
    console.log('[StdioTransport] Disposed');
  }
}
