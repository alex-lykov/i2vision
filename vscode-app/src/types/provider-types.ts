/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

/**
 * Type definitions for LLM providers
 */

export interface LLMMessage {
  role: string;
  content: string;
  tool_call_id?: string;
  tool_calls?: {
    id: string;
    type: string;
    function: {
      name: string;
      arguments: string;
    };
  }[];
}

export interface LLMRequest {
  prompt: string;
  model?: string;
  /** Structured messages array — preferred over flat prompt string when available */
  messages?: LLMMessage[];
  temperature?: number;
  topP?: number;
  topK?: number;
  maxTokens?: number;
  timeoutSeconds?: number;
  tools?: any[]; // Tool definitions for function calling
  random_seed?: number; // Random seed for reproducibility
  [key: string]: any; // Allow provider-specific options
}

export interface LLMResponse {
  text: string;
  model: string;
  provider: string;
  /** Reasoning trace, typically from reasoning-capable models */
  reasoning?: string;
  /** Structured tool calls when the provider returns function calls */
  tool_calls?: {
    id: string;
    type?: string;
    function: {
      name: string;
      arguments: string;
    };
  }[];
  usage?: {
    promptTokens: number;
    completionTokens: number;
    totalTokens?: number;
  };
  [key: string]: any; // Allow provider-specific response data
}

/**
 * Declares what a provider supports. AgentBridge reads these capabilities to
 * adapt its behavior (streaming, tool injection, session management, etc.)
 * instead of checking provider strings.
 */
export interface LLMProviderCapabilities {
  /** Whether the provider supports SSE streaming via callAPI */
  streaming: boolean;
  /** Whether the LLM API natively returns tool_calls in structured format */
  nativeToolCalls: boolean;
  /** Whether the provider accepts structured messages[] instead of flat prompt */
  structuredMessages: boolean;
  /** Whether the provider maintains server-side session state */
  sessionManagement: boolean;
  /** Whether the provider supports server-side context compaction/reset
   *  (e.g. POST /reset-session, message summarization). When true,
   *  AgentBridge delegates compaction to the session manager rather than
   *  trimming locally via trimMessagesToBudget. */
  contextCompaction: boolean;
  /** Whether the provider requires an API key / Bearer token */
  authRequired: boolean;
  /** Maximum token count for the model's context window */
  maxContextLength: number;
  /** The chat completions endpoint path (e.g. '/v1/chat/completions') */
  chatEndpoint: string;
  /** The health/models endpoint for liveness checks */
  healthEndpoint?: string;
}

export interface LLMProvider {
  /**
   * Get the name of the provider
   */
  getProviderName(): string;

  /**
   * Declare what this provider supports so AgentBridge can adapt without
   * provider-specific branches.
   */
  getCapabilities(): LLMProviderCapabilities;

  /**
   * Validate provider configuration
   * @throws Error if configuration is invalid
   */
  validateConfiguration(): void;

  /**
   * Make an API call to the LLM provider
   * @param request The LLM request
   * @returns Promise with LLM response
   */
  callAPI(request: LLMRequest): Promise<LLMResponse | AsyncGenerator<any>>;

  /**
   * Check if the provider is available
   * @returns Promise with availability status
   */
  isAvailable?(): Promise<boolean>;

  /**
   * Get list of available models
   * @returns Promise with list of model names
   */
  listModels?(): Promise<string[]>;
}

/**
 * Returns LLMProviderCapabilities for a given provider string.
 * Used by AgentBridge to resolve capabilities without needing
 * a live LLMProvider instance.
 */
export function getProviderCapabilities(provider: string): LLMProviderCapabilities {
  switch (provider) {
    case '3d-llm':
      return {
        streaming: true,
        nativeToolCalls: false,  // Proxy prompt-emulates tools; inject into system prompt
        structuredMessages: true,
        sessionManagement: true,
        contextCompaction: true,
        authRequired: false,
        maxContextLength: 64000,
        chatEndpoint: '/v1/chat/completions',
        healthEndpoint: '/v1/models',
      };
    case 'mistral':
      return {
        streaming: false,
        nativeToolCalls: true,
        structuredMessages: false,
        sessionManagement: false,
        contextCompaction: false,
        authRequired: true,
        maxContextLength: 32768,
        chatEndpoint: '/v1/chat/completions',
        healthEndpoint: '/v1/models',
      };
    case 'deepseek':
      return {
        streaming: true,
        nativeToolCalls: false,
        structuredMessages: false,
        sessionManagement: false,
        contextCompaction: false,
        authRequired: true,
        maxContextLength: 65536,
        chatEndpoint: '/chat/completions',
        healthEndpoint: '/v1/models',
      };
    case 'ollama':
    default:
      return {
        streaming: false,
        nativeToolCalls: false,
        structuredMessages: false,
        sessionManagement: false,
        contextCompaction: false,
        authRequired: false,
        maxContextLength: 8192,
        chatEndpoint: '/api/generate',
        healthEndpoint: '/api/tags',
      };
  }
}