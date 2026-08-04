/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

/**
 * Mistral Cloud API Provider implementation with unified error handling
 * 
 * Official Mistral API Documentation: https://docs.mistral.ai/api/
 * 
 * Features:
 * - Supports all Mistral API endpoints (chat, embeddings, etc.)
 * - Handles API key authentication
 * - Integrated with unified error handling system
 * - Automatic retry logic for rate limits and network issues
 */

import {LLMProvider, LLMProviderCapabilities, LLMRequest, LLMResponse} from '../../types/provider-types';
import {ErrorHandler} from '../../core/ErrorHandler';
import {ErrorContext} from '../../core/types';

export class MistralProvider implements LLMProvider {
  private apiKey: string;
  private baseUrl: string;
  private errorHandler: ErrorHandler;

  constructor(apiKey: string, baseUrl: string = 'https://api.mistral.ai', errorHandler: ErrorHandler) {
    this.apiKey = apiKey;
    this.baseUrl = baseUrl;
    this.errorHandler = errorHandler;
  }

  getProviderName(): string {
    return 'Mistral';
  }

  getCapabilities(): LLMProviderCapabilities {
    return {
      streaming: false,
      nativeToolCalls: true,    // choice.message.tool_calls
      structuredMessages: false, // uses flat prompt string
      sessionManagement: false,
      contextCompaction: false,
      authRequired: true,
      maxContextLength: 32768,
      chatEndpoint: '/v1/chat/completions',
      healthEndpoint: '/v1/models',
    };
  }

  validateConfiguration(): void {
    if (!this.apiKey) {
      throw new Error('Mistral API key is required');
    }
    if (!this.baseUrl) {
      throw new Error('Mistral base URL is required');
    }
  }

  async callAPI(request: LLMRequest): Promise<LLMResponse> {
    const model = request.model || 'mistral-tiny'; // Default Mistral model
    const context: ErrorContext = {
      request: {
        model: model,
        prompt: request.prompt,
        options: {
          temperature: request.temperature,
          top_p: request.topP,
          max_tokens: request.maxTokens
        }
      }
    };

    return this.errorHandler.handleError(
      async () => {
        // Convert the request to Mistral API format
        const messages = [{ role: 'user', content: request.prompt }];
        
        const body = {
          model: model,
          messages: messages,
          temperature: request.temperature || 0.7, // Mistral default
          top_p: request.topP || 1.0,
          max_tokens: request.maxTokens || 2048,
          safe_prompt: false, // Allow unrestricted content by default
          random_seed: request.random_seed
        };

        // Add tool calling support if tools are provided
        if (request.tools && request.tools.length > 0) {
          (body as any)['tools'] = request.tools;
        }

        const response = await fetch(`${this.baseUrl}/v1/chat/completions`, {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            'Authorization': `Bearer ${this.apiKey}`,
            'Accept': 'application/json'
          },
          body: JSON.stringify(body)
        });

        if (!response.ok) {
          const errorText = await response.text();
          let errorMessage = `Mistral API error: ${response.status} ${response.statusText}`;
          
          // Handle specific Mistral API error codes
          if (response.status === 401) {
            errorMessage = 'Mistral API error: 401 Unauthorized - Invalid API key. Check your MISTRAL_API_KEY.';
          } else if (response.status === 402) {
            errorMessage = 'Mistral API error: 402 Payment Required - Insufficient credits. Add credits to your Mistral account.';
          } else if (response.status === 429) {
            errorMessage = 'Mistral API error: 429 Too Many Requests - Rate limit exceeded.';
          } else if (response.status === 404) {
            errorMessage = 'Mistral API error: 404 Not Found - Model not found or invalid endpoint.';
          }
          
          throw new Error(errorMessage + (errorText ? ` - ${errorText}` : ''));
        }

        const data = await response.json();
        const choice = data.choices?.[0];
        
        // Extract tool calls if present
        const toolCalls = choice?.message?.tool_calls?.map((tc: any) => ({
          id: tc.id || `call_${Date.now()}`,
          name: tc.function?.name || '',
          arguments: tc.function?.arguments || {}
        })) || [];
        
        return {
          text: choice?.message?.content || '',
          model: model,
          provider: 'mistral',
          usage: data.usage ? {
            promptTokens: data.usage.prompt_tokens || 0,
            completionTokens: data.usage.completion_tokens || 0,
            totalTokens: data.usage.total_tokens || 0
          } : undefined,
          // Include Mistral-specific response data
          finish_reason: choice?.finish_reason,
          tool_calls: toolCalls.length > 0 ? toolCalls : undefined
        };
      },
      'Mistral',
      context
    );
  }

  /**
   * List available Mistral models
   */
  async listModels(): Promise<string[]> {
    try {
      const response = await fetch(`${this.baseUrl}/v1/models`, {
        method: 'GET',
        headers: {
          'Authorization': `Bearer ${this.apiKey}`
        }
      });
      
      if (!response.ok) {
        return [];
      }

      const data = await response.json();
      return data.data.map((m: any) => m.id);
    } catch (error) {
      console.error('Failed to list Mistral models:', error);
      return [];
    }
  }

  /**
   * Check if Mistral API is available
   */
  async isAvailable(): Promise<boolean> {
    try {
      // Simple connectivity check to models endpoint
      const response = await fetch(`${this.baseUrl}/v1/models`, {
        method: 'GET',
        headers: {
          'Authorization': `Bearer ${this.apiKey}`
        }
      });
      return response.ok;
    } catch (error) {
      return false;
    }
  }

  /**
   * Create embeddings using Mistral API
   */
  async createEmbeddings(input: string | string[]): Promise<any> {
    const inputs = Array.isArray(input) ? input : [input];
    
    return this.errorHandler.handleError(
      async () => {
        const response = await fetch(`${this.baseUrl}/v1/embeddings`, {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            'Authorization': `Bearer ${this.apiKey}`
          },
          body: JSON.stringify({
            model: 'mistral-embed',
            input: inputs
          })
        });

        if (!response.ok) {
          const errorText = await response.text();
          throw new Error(`Mistral embeddings error: ${response.status} - ${errorText}`);
        }

        return response.json();
      },
      'Mistral',
      { request: { operation: 'createEmbeddings', input: inputs } }
    );
  }

  /**
   * Get Mistral model information
   */
  async getModelInfo(modelId: string): Promise<any> {
    return this.errorHandler.handleError(
      async () => {
        const response = await fetch(`${this.baseUrl}/v1/models/${modelId}`, {
          method: 'GET',
          headers: {
            'Authorization': `Bearer ${this.apiKey}`
          }
        });

        if (!response.ok) {
          const errorText = await response.text();
          throw new Error(`Mistral model info error: ${response.status} - ${errorText}`);
        }

        return response.json();
      },
      'Mistral',
      { request: { operation: 'getModelInfo', modelId } }
    );
  }

  /**
   * Check if a model ID is a Mistral model
   */
  static isMistralModel(modelId: string): boolean {
    return modelId === 'mistral-tiny' ||
           modelId === 'mistral-small' ||
           modelId === 'mistral-medium' ||
           modelId === 'mistral-large' ||
           modelId === 'mistral-embed' ||
           modelId.startsWith('mistral:tiny') ||
           modelId.startsWith('mistral:small') ||
           modelId.startsWith('mistral:medium') ||
           modelId.startsWith('mistral:large') ||
           modelId.startsWith('mistral:embed');
  }
}