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
  callAPI(request: LLMRequest): Promise<LLMResponse>;

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