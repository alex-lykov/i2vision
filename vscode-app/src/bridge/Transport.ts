/**
 * Transport layer for Agent Communication Protocol
 * 
 * Transport-agnostic interface with concrete implementations
 * for stdio, HTTP, and WebSocket.
 */

import { AgentRequest, AgentResponse, AgentNotification, CancelRequest, HealthRequest } from './Protocol';

/**
 * Transport interface - implement this for any transport mechanism
 */
export interface AgentTransport {
  /**
   * Send a request and wait for response
   */
  send(request: AgentRequest | CancelRequest | HealthRequest): Promise<AgentResponse>;
  
  /**
   * Register notification callback
   */
  onNotification(callback: (notification: AgentNotification) => void): void;
  
  /**
   * Check if transport is connected/available
   */
  isConnected(): boolean;
  
  /**
   * Clean up resources
   */
  dispose(): void;
}

/**
 * Transport events for reactive handling
 */
export interface TransportEvents {
  onConnected?: () => void;
  onDisconnected?: () => void;
  onError?: (error: Error) => void;
  onNotification?: (notification: AgentNotification) => void;
}
