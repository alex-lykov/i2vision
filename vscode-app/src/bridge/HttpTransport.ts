/**
 * HTTP Transport Implementation
 * 
 * Communicates with agent server via HTTP/REST.
 * Supports notifications via Server-Sent Events (SSE).
 */

import { AgentRequest, AgentResponse, AgentNotification, CancelRequest, HealthRequest, serializeMessage } from './Protocol';
import { AgentTransport } from './Transport';

export interface HttpTransportOptions {
  baseUrl: string;
  timeoutMs?: number;
  headers?: Record<string, string>;
  useSse?: boolean;
}

export class HttpTransport implements AgentTransport {
  private options: HttpTransportOptions;
  private notificationHandlers: Array<(notification: AgentNotification) => void> = [];
  private eventSource: EventSource | null = null;
  private connected: boolean = false;

  constructor(options: HttpTransportOptions) {
    this.options = {
      timeoutMs: 300000,
      useSse: true,
      ...options
    };
  }

  /**
   * Connect to the server
   */
  async connect(): Promise<void> {
    console.log(`[HttpTransport] Connecting to ${this.options.baseUrl}`);

    // Set up SSE for notifications if enabled
    if (this.options.useSse) {
      try {
        const sseUrl = `${this.options.baseUrl}/events`;
        this.eventSource = new EventSource(sseUrl);

        this.eventSource.onopen = () => {
          console.log('[HttpTransport] SSE connected');
          this.connected = true;
        };

        this.eventSource.onerror = (error) => {
          console.error('[HttpTransport] SSE error:', error);
          this.connected = false;
        };

        this.eventSource.onmessage = (event) => {
          try {
            const notification = JSON.parse(event.data) as AgentNotification;
            for (const handler of this.notificationHandlers) {
              handler(notification);
            }
          } catch (error: any) {
            console.error('[HttpTransport] Error parsing SSE message:', error.message);
          }
        };
      } catch (error: any) {
        console.warn('[HttpTransport] SSE not available, notifications disabled');
        this.connected = true; // Still allow requests
      }
    } else {
      this.connected = true;
    }

    console.log('[HttpTransport] Connected');
  }

  /**
   * Send a request
   */
  async send(request: AgentRequest | CancelRequest | HealthRequest): Promise<AgentResponse> {
    if (!this.connected) {
      throw new Error('Transport not connected');
    }

    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), this.options.timeoutMs);

    try {
      const response = await fetch(`${this.options.baseUrl}/process`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          ...this.options.headers
        },
        body: serializeMessage(request),
        signal: controller.signal
      });

      clearTimeout(timeout);

      if (!response.ok) {
        throw new Error(`HTTP ${response.status}: ${response.statusText}`);
      }

      const result = await response.json() as AgentResponse;
      console.log(`[HttpTransport] Response received for ID: ${request.id}`);
      
      return result;
    } catch (error: any) {
      clearTimeout(timeout);
      console.error('[HttpTransport] Request failed:', error.message);
      throw error;
    }
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
    return this.connected;
  }

  /**
   * Clean up
   */
  dispose(): void {
    console.log('[HttpTransport] Disposing...');

    if (this.eventSource) {
      this.eventSource.close();
      this.eventSource = null;
    }

    this.notificationHandlers = [];
    this.connected = false;
    console.log('[HttpTransport] Disposed');
  }
}
