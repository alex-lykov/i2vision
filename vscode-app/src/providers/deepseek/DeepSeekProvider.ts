/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

/**
 * DeepSeek Provider implementation with unified error handling
 */

import {LLMProvider, LLMProviderCapabilities, LLMRequest, LLMResponse} from '../../types/provider-types';
import {ErrorHandler} from '../../core/ErrorHandler';
import {ErrorContext} from '../../core/types';

export class DeepSeekProvider implements LLMProvider {
  private apiKey: string;
  private baseUrl: string;
  private errorHandler: ErrorHandler;

  constructor(apiKey: string, baseUrl: string, errorHandler: ErrorHandler) {
    this.apiKey = apiKey;
    this.baseUrl = baseUrl;
    this.errorHandler = errorHandler;
  }

  getProviderName(): string {
    return 'DeepSeek';
  }

  getCapabilities(): LLMProviderCapabilities {
    return {
      streaming: true,
      nativeToolCalls: false,
      structuredMessages: false, // uses flat prompt string
      sessionManagement: false,
      contextCompaction: false,
      authRequired: true,
      maxContextLength: 65536,    // DeepSeek V3 context
      chatEndpoint: '/chat/completions', // NOTE: bare path, no /v1/ prefix
      healthEndpoint: '/v1/models',
    };
  }

  validateConfiguration(): void {
    if (!this.apiKey) {
      throw new Error('DeepSeek API key is required');
    }
    if (!this.baseUrl) {
      throw new Error('DeepSeek base URL is required');
    }
  }

  async callAPI(request: LLMRequest): Promise<LLMResponse> {
    const model = request.model || 'deepseek-chat';
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
        const body = {
          model: model,
          messages: [{ role: 'user', content: request.prompt }],
          temperature: request.temperature || 0.2,
          top_p: request.topP || 0.95,
          max_tokens: request.maxTokens || 4096,
          stream: true
        };

        const response = await fetch(`${this.baseUrl}/chat/completions`, {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            'Authorization': `Bearer ${this.apiKey}`
          },
          body: JSON.stringify(body)
        });

        if (!response.ok) {
          const errorText = await response.text();
          let errorMessage = `DeepSeek API error: ${response.status} ${response.statusText}`;
          
          if (response.status === 402) {
            errorMessage = 'DeepSeek API error: 402 Payment Required - Your API key has insufficient credits.';
          } else if (response.status === 401) {
            errorMessage = 'DeepSeek API error: 401 Unauthorized - Invalid API key.';
          }
          
          throw new Error(errorMessage + (errorText ? ` - ${errorText}` : ''));
        }

        const reader = response.body?.getReader();
        if (!reader) {
          throw new Error('DeepSeek API error: streaming response body is not readable');
        }

        const decoder = new TextDecoder();
        let content = '';
        let reasoning = '';
        let promptTokens = 0;
        let completionTokens = 0;
        let buffer = '';

        while (true) {
          const { done, value } = await reader.read();
          if (done) break;

          buffer += decoder.decode(value, { stream: true });
          const lines = buffer.split('\n');
          buffer = lines.pop() || '';

          for (const line of lines) {
            const trimmed = line.trim();
            if (!trimmed.startsWith('data:')) continue;

            const payload = trimmed.slice(5).trim();
            if (!payload || payload === '[DONE]') continue;

            try {
              const chunk = JSON.parse(payload);
              const delta = chunk.choices?.[0]?.delta;
              if (delta?.content) content += delta.content;
              if (delta?.reasoning_content) reasoning += delta.reasoning_content;

              if (chunk.usage?.prompt_tokens) promptTokens = chunk.usage.prompt_tokens;
              if (chunk.usage?.completion_tokens) completionTokens = chunk.usage.completion_tokens;
            } catch (e) {
              // Ignore malformed SSE lines.
            }
          }
        }

        // Flush any remaining buffered SSE line after stream ends.
        const finalLine = buffer.trim();
        if (finalLine.startsWith('data:')) {
          const payload = finalLine.slice(5).trim();
          if (payload && payload !== '[DONE]') {
            try {
              const chunk = JSON.parse(payload);
              const delta = chunk.choices?.[0]?.delta;
              if (delta?.content) content += delta.content;
              if (delta?.reasoning_content) reasoning += delta.reasoning_content;
              if (chunk.usage?.prompt_tokens) promptTokens = chunk.usage.prompt_tokens;
              if (chunk.usage?.completion_tokens) completionTokens = chunk.usage.completion_tokens;
            } catch (e) {
              // Ignore malformed SSE lines.
            }
          }
        }

        return {
          text: content,
          reasoning: reasoning.trim() || undefined,
          model: model,
          provider: 'deepseek',
          usage: (promptTokens > 0 || completionTokens > 0) ? {
            promptTokens: promptTokens,
            completionTokens: completionTokens,
            totalTokens: promptTokens + completionTokens
          } : undefined
        };
      },
      'DeepSeek',
      context
    );
  }

  async isAvailable(): Promise<boolean> {
    try {
      // Simple connectivity check
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
      return [];
    }
  }
}