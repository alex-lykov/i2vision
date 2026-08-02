/**
 * DeepSeek Provider implementation with unified error handling
 */

import { LLMProvider, LLMRequest, LLMResponse } from '../../types/provider-types';
import { ErrorHandler } from '../../core/ErrorHandler';
import { ErrorContext } from '../../core/types';

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
          stream: false
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

        const data = await response.json();
        const choice = data.choices?.[0];
        
        return {
          text: choice?.message?.content || '',
          model: model,
          provider: 'deepseek',
          usage: data.usage ? {
            promptTokens: data.usage.prompt_tokens || 0,
            completionTokens: data.usage.completion_tokens || 0,
            totalTokens: data.usage.total_tokens || 0
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