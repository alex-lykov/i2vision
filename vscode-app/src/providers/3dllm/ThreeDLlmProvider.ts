/**
 * 3D LLM Provider implementation (FreeDeepseekAPI proxy) with unified error handling
 */

import { LLMProvider, LLMRequest, LLMResponse } from '../../types/provider-types';
import { ErrorHandler } from '../../core/ErrorHandler';
import { ErrorContext } from '../../core/types';

export class ThreeDLlmProvider implements LLMProvider {
  private baseUrl: string;
  private defaultModel: string;
  private errorHandler: ErrorHandler;
  private serverErrorCount: number = 0;
  private lastServerErrorTime: number = 0;
  private readonly MAX_SERVER_ERRORS: number = 3;
  private readonly SERVER_ERROR_COOLDOWN: number = 300000; // 5 minutes

  constructor(baseUrl: string, defaultModel: string, errorHandler: ErrorHandler) {
    this.baseUrl = baseUrl;
    this.defaultModel = defaultModel;
    this.errorHandler = errorHandler;
  }

  getProviderName(): string {
    return '3D LLM';
  }

  validateConfiguration(): void {
    if (!this.baseUrl) {
      throw new Error('3D LLM base URL is required');
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
          messages: [{ role: 'user', content: request.prompt }],
          temperature: request.temperature || 0.2,
          top_p: request.topP || 0.95,
          max_tokens: request.maxTokens || 4096,
          stream: false
        };

        const response = await fetch(`${this.baseUrl}/v1/chat/completions`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(body)
        });

        if (!response.ok) {
          const errorText = await response.text();
          let errorMessage = `3D LLM API error: ${response.status} ${response.statusText}`;
          
          if (response.status === 404) {
            errorMessage = '3D LLM API error: 404 Not Found - The FreeDeepseekAPI server is not running or the URL is incorrect.';
          } else if (response.status === 500) {
            errorMessage = '3D LLM API error: 500 Internal Server Error - The FreeDeepseekAPI server encountered an error.';
            this.recordServerError();
          } else if (response.status === 0) {
            errorMessage = '3D LLM API error: Connection refused - Cannot connect to the FreeDeepseekAPI server.';
          } else if (errorText.includes('ECONNREFUSED')) {
            errorMessage = '3D LLM API error: Connection refused - Cannot connect to the FreeDeepseekAPI server.';
            this.recordServerError();
          } else if (errorText.includes('ENOTFOUND') || errorText.includes('getaddrinfo')) {
            errorMessage = '3D LLM API error: Host not found - The FreeDeepseekAPI server URL is invalid.';
            this.recordServerError();
          }
          
          throw new Error(errorMessage + (errorText ? ` - ${errorText}` : ''));
        }

        const data = await response.json();
        const choice = data.choices?.[0];
        
        return {
          text: choice?.message?.content || '',
          model: model,
          provider: '3dllm',
          usage: data.usage ? {
            promptTokens: data.usage.prompt_tokens || 0,
            completionTokens: data.usage.completion_tokens || 0,
            totalTokens: data.usage.total_tokens || 0
          } : undefined
        };
      },
      '3D LLM',
      context
    );
  }

  async isAvailable(): Promise<boolean> {
    try {
      // Simple connectivity check
      const response = await fetch(`${this.baseUrl}/v1/models`, {
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
      const response = await fetch(`${this.baseUrl}/v1/models`, {
        method: 'GET',
        headers: { 'Content-Type': 'application/json' }
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

  /**
   * Check if we should continue retrying based on server error history
   */
  private shouldRetryAfterServerError(): boolean {
    const now = Date.now();
    
    // Reset error count if we're past the cooldown period
    if (now - this.lastServerErrorTime > this.SERVER_ERROR_COOLDOWN) {
      this.serverErrorCount = 0;
      this.lastServerErrorTime = 0;
    }
    
    // If we've had too many recent server errors, stop retrying
    if (this.serverErrorCount >= this.MAX_SERVER_ERRORS) {
      return false;
    }
    
    return true;
  }

  /**
   * Record a server error for tracking purposes
   */
  private recordServerError(): void {
    const now = Date.now();
    
    // Reset count if we're past the cooldown period
    if (now - this.lastServerErrorTime > this.SERVER_ERROR_COOLDOWN) {
      this.serverErrorCount = 0;
    }
    
    this.serverErrorCount++;
    this.lastServerErrorTime = now;
    console.log(`Server error recorded (count: ${this.serverErrorCount}/${this.MAX_SERVER_ERRORS})`);
  }

  /**
   * Check if an error indicates a server connectivity issue
   */
  private isServerConnectivityError(error: any): boolean {
    if (!error || !error.message) return false;
    
    const message = error.message.toLowerCase();
    return message.includes('500 internal server error') ||
           message.includes('connection refused') ||
           message.includes('econnrefused') ||
           message.includes('fetch failed') ||
           message.includes('host not found') ||
           message.includes('enotfound') ||
           message.includes('getaddrinfo') ||
           message.includes('network error');
  }
}