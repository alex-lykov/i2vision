/**
 * User feedback generator that creates consistent, user-friendly error messages
 * across all LLM providers
 */

import { StandardizedError } from './types';

export class UserFeedbackGenerator {
  private providerSpecificMessages: Record<string, Record<string, string>>;

  constructor() {
    this.providerSpecificMessages = {
      'Ollama': {
        'NetworkError': 'Cannot connect to Ollama server at {url}. Please check if the server is running.',
        'AuthenticationError': 'Ollama authentication failed. Please check your configuration.',
        'ConfigurationError': 'Ollama is not properly configured. Please set up the Ollama provider.'
      },
      'DeepSeek': {
        'NetworkError': 'Cannot connect to DeepSeek API. Please check your internet connection.',
        'AuthenticationError': 'DeepSeek API key is invalid or missing. Please check your API key configuration.',
        'RateLimitError': 'DeepSeek API rate limit exceeded. Please try again later.',
        'ConfigurationError': 'DeepSeek provider is not configured. Please add your API key.'
      },
      '3D LLM': {
        'NetworkError': 'Cannot connect to 3D LLM proxy. Please check if the proxy server is running.',
        'AuthenticationError': '3D LLM authentication failed. Please verify your proxy configuration.',
        'ConfigurationError': '3D LLM provider needs configuration. Please set up the proxy URL.'
      }
    };
  }

  public formatMessage(error: StandardizedError): string {
    const baseMessage = this.getBaseMessage(error);
    const actionMessage = this.getActionMessage(error);
    const providerMessage = this.getProviderSpecificMessage(error);

    const messages = [baseMessage];
    
    // Include original error message if it differs from the base
    if (error.message && !baseMessage.includes(error.message)) {
      messages.push(`(${error.message})`);
    }

    if (providerMessage) {
      messages.push(providerMessage);
    }

    if (actionMessage) {
      messages.push(actionMessage);
    }

    if (error.isRetryable && error.retryCount !== undefined) {
      messages.push(`(Attempt ${error.retryCount} of ${this.getMaxRetries(error)})`);
    }

    return messages.join(' ');
  }

  private getBaseMessage(error: StandardizedError): string {
    const messages: Record<string, string> = {
      'NetworkError': 'Network connection failed',
      'AuthenticationError': 'Authentication failed',
      'RateLimitError': 'Rate limit exceeded',
      'ServerError': 'Server error occurred',
      'ConfigurationError': 'Configuration error',
      'ResponseError': 'Invalid response received',
      'GenericError': 'An error occurred'
    };

    return messages[error.type] || 'An error occurred';
  }

  private getProviderSpecificMessage(error: StandardizedError): string | null {
    const providerMessages = this.providerSpecificMessages[error.provider];
    if (!providerMessages) {
      return null;
    }

    const specificMessage = providerMessages[error.type];
    if (!specificMessage) {
      return null;
    }

    return specificMessage
      .replace('{url}', this.getProviderUrl(error.provider))
      .replace('{error}', error.message);
  }

  private getActionMessage(error: StandardizedError): string | null {
    const actions: Record<string, string> = {
      'NetworkError': 'Please check your network connection and server status.',
      'AuthenticationError': 'Please verify your API key or credentials.',
      'RateLimitError': 'Please wait and try again later.',
      'ServerError': 'The service may be temporarily unavailable. Please try again later.',
      'ConfigurationError': 'Please check your provider configuration.',
      'ResponseError': 'The response format was unexpected. This may indicate a provider issue.'
    };

    return actions[error.type] || null;
  }

  private getProviderUrl(provider: string): string {
    const urls: Record<string, string> = {
      'Ollama': 'http://localhost:11434',
      'DeepSeek': 'https://api.deepseek.com',
      '3D LLM': 'http://localhost:9655'
    };

    return urls[provider] || 'the provider server';
  }

  private getMaxRetries(error: StandardizedError): number {
    return 3;
  }

  public formatLogMessage(error: StandardizedError): string {
    const timestamp = error.timestamp.toISOString();
    const provider = error.provider;
    const type = error.type;
    const message = error.message;

    return `[${timestamp}] [${provider}] [${type}] ${message}`;
  }

  public formatDebugMessage(error: StandardizedError): string {
    const base = this.formatLogMessage(error);
    const details = [];

    if (error.originalError) {
      details.push(`Original error: ${this.safeStringify(error.originalError)}`);
    }

    if (error.retryCount !== undefined) {
      details.push(`Retry count: ${error.retryCount}`);
    }

    if (error.isRetryable) {
      details.push('This error is retryable');
    } else {
      details.push('This error is not retryable');
    }

    return details.length > 0 
      ? `${base}\n${details.join('\n')}`
      : base;
  }

  private safeStringify(obj: any): string {
    try {
      return JSON.stringify(obj, null, 2);
    } catch (e) {
      return String(obj);
    }
  }

  public addProviderMessages(provider: string, messages: Record<string, string>): void {
    if (!this.providerSpecificMessages[provider]) {
      this.providerSpecificMessages[provider] = {};
    }
    
    Object.assign(this.providerSpecificMessages[provider], messages);
  }

  public getAllProviderMessages(): Record<string, Record<string, string>> {
    return { ...this.providerSpecificMessages };
  }
}