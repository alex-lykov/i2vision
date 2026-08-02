/**
 * Ollama Provider implementation with unified error handling
 */

import { LLMProvider, LLMRequest, LLMResponse } from '../../types/provider-types';
import { ErrorHandler } from '../../core/ErrorHandler';
import { ErrorContext } from '../../core/types';

export class OllamaProvider implements LLMProvider {
  private baseUrl: string;
  private defaultModel: string;
  private errorHandler: ErrorHandler;

  constructor(baseUrl: string, defaultModel: string, errorHandler: ErrorHandler) {
    this.baseUrl = baseUrl;
    this.defaultModel = defaultModel;
    this.errorHandler = errorHandler;
  }

  getProviderName(): string {
    return 'Ollama';
  }

  validateConfiguration(): void {
    if (!this.baseUrl) {
      throw new Error('Ollama base URL is required');
    }
  }

  async callAPI(request: LLMRequest): Promise<LLMResponse> {
    const model = request.model || this.defaultModel;
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
          prompt: request.prompt,
          options: {
            temperature: request.temperature || 0.2,
            top_p: request.topP || 0.95,
            num_predict: request.maxTokens || 4096
          }
        };

        const response = await fetch(`${this.baseUrl}/api/generate`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(body)
        });

        if (!response.ok) {
          const errorText = await response.text();
          throw new Error(`Ollama API error: ${response.status} ${response.statusText} - ${errorText}`);
        }

        const data = await response.json();
        
        return {
          text: data.response || '',
          model: model,
          provider: 'ollama',
          usage: data.done ? {
            promptTokens: data.prompt_eval_count || 0,
            completionTokens: data.eval_count || 0,
            totalTokens: (data.prompt_eval_count || 0) + (data.eval_count || 0)
          } : undefined
        };
      },
      'Ollama',
      context
    );
  }

  async isAvailable(): Promise<boolean> {
    try {
      const response = await fetch(`${this.baseUrl}/api/tags`, {
        method: 'GET',
        headers: { 'Content-Type': 'application/json' }
      });
      return response.ok;
    } catch (error) {
      return false;
    }
  }

  async listModels(): Promise<string[]> {
    try {
      const response = await fetch(`${this.baseUrl}/api/tags`, {
        method: 'GET',
        headers: { 'Content-Type': 'application/json' }
      });
      
      if (!response.ok) {
        return [];
      }

      const data = await response.json();
      return data.models.map((m: any) => m.name);
    } catch (error) {
      return [];
    }
  }
}