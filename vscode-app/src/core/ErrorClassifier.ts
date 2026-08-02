/**
 * Error classifier that normalizes different error types from various providers
 * into standardized error categories
 */

import {
  StandardizedError,
  NetworkError,
  AuthenticationError,
  RateLimitError,
  ServerError,
  ConfigurationError,
  ResponseError,
  GenericError
} from './types';

export class ErrorClassifier {
  
  classify(error: unknown, providerName: string): StandardizedError {
    try {
      if (this.isNetworkError(error)) {
        return new NetworkError(
          this.extractMessage(error),
          providerName,
          error
        );
      }

      if (this.isAuthenticationError(error)) {
        return new AuthenticationError(
          this.extractMessage(error),
          providerName,
          error
        );
      }

      if (this.isRateLimitError(error)) {
        const retryAfter = this.extractRetryAfter(error);
        return new RateLimitError(
          this.extractMessage(error),
          providerName,
          error,
          retryAfter
        );
      }

      if (this.isServerError(error)) {
        const statusCode = this.extractStatusCode(error);
        return new ServerError(
          this.extractMessage(error),
          providerName,
          error,
          statusCode
        );
      }

      if (this.isConfigurationError(error)) {
        return new ConfigurationError(
          this.extractMessage(error),
          providerName,
          error
        );
      }

      if (this.isResponseError(error)) {
        return new ResponseError(
          this.extractMessage(error),
          providerName,
          error
        );
      }

      return new GenericError(
        this.extractMessage(error),
        providerName,
        error
      );

    } catch (classificationError) {
      return new GenericError(
        `Error classification failed: ${this.safeStringify(classificationError)}`,
        providerName,
        error
      );
    }
  }

  private isNetworkError(error: unknown): boolean {
    if (typeof error !== 'object' || error === null) {
      return false;
    }

    const err = error as any;
    const networkCodes = ['ECONNREFUSED', 'ENOTFOUND', 'ECONNRESET', 'ETIMEDOUT', 'EAI_AGAIN'];
    if (networkCodes.includes(err.code)) {
      return true;
    }

    if (err.name === 'TypeError' && 
        (err.message.includes('Failed to fetch') || 
         err.message.includes('NetworkError'))) {
      return true;
    }

    if (err.isAxiosError && !err.response) {
      return true;
    }

    return false;
  }

  private isAuthenticationError(error: unknown): boolean {
    if (typeof error !== 'object' || error === null) {
      return false;
    }

    const err = error as any;
    if (err.response?.status === 401) {
      return true;
    }

    if (err.message && 
        (err.message.includes('authentication') ||
         err.message.includes('Authorization') ||
         err.message.includes('API key') ||
         err.message.includes('unauthenticated'))) {
      return true;
    }

    return false;
  }

  private isRateLimitError(error: unknown): boolean {
    if (typeof error !== 'object' || error === null) {
      return false;
    }

    const err = error as any;
    if (err.response?.status === 429) {
      return true;
    }

    if (err.message && 
        (err.message.includes('rate limit') ||
         err.message.includes('too many requests') ||
         err.message.includes('quota'))) {
      return true;
    }

    return false;
  }

  private isServerError(error: unknown): boolean {
    if (typeof error !== 'object' || error === null) {
      return false;
    }

    const err = error as any;
    if (err.response?.status >= 500 && err.response?.status < 600) {
      return true;
    }

    if (err.message && 
        (err.message.includes('Internal Server Error') ||
         err.message.includes('server error') ||
         err.message.includes('service unavailable'))) {
      return true;
    }

    return false;
  }

  private isConfigurationError(error: unknown): boolean {
    if (typeof error !== 'object' || error === null) {
      return false;
    }

    const err = error as any;
    if (err.message && 
        (err.message.includes('configuration') ||
         err.message.includes('config') ||
         err.message.includes('setup') ||
         err.message.includes('not configured') ||
         err.message.includes('missing'))) {
      return true;
    }

    return false;
  }

  private isResponseError(error: unknown): boolean {
    if (typeof error !== 'object' || error === null) {
      return false;
    }

    const err = error as any;
    if (err.name === 'SyntaxError' || 
        err.message?.includes('JSON')) {
      return true;
    }

    if (err.message && 
        (err.message.includes('invalid response') ||
         err.message.includes('malformed') ||
         err.message.includes('parse'))) {
      return true;
    }

    return false;
  }

  private extractRetryAfter(error: unknown): number | undefined {
    if (typeof error !== 'object' || error === null) {
      return undefined;
    }

    const err = error as any;
    if (err.response?.headers?.['retry-after']) {
      return parseInt(err.response.headers['retry-after']) || undefined;
    }

    if (err.response?.data?.retry_after) {
      return parseInt(err.response.data.retry_after) || undefined;
    }

    return undefined;
  }

  private extractStatusCode(error: unknown): number | undefined {
    if (typeof error !== 'object' || error === null) {
      return undefined;
    }

    const err = error as any;
    return err.response?.status;
  }

  private extractMessage(error: unknown): string {
    if (error instanceof Error) {
      return error.message || 'Unknown error occurred';
    }

    if (typeof error === 'string') {
      return error;
    }

    if (typeof error === 'object' && error !== null) {
      const err = error as any;
      if (err.response?.data?.message) {
        return err.response.data.message;
      }
      if (err.response?.data?.error) {
        return err.response.data.error;
      }
      if (err.response?.statusText) {
        return err.response.statusText;
      }
      if (err.message) {
        return err.message;
      }
      return this.safeStringify(error);
    }

    return 'Unknown error occurred';
  }

  private safeStringify(obj: any): string {
    try {
      return JSON.stringify(obj);
    } catch (e) {
      try {
        const cache = new Set();
        return JSON.stringify(obj, (key, value) => {
          if (typeof value === 'object' && value !== null) {
            if (cache.has(value)) {
              return '[Circular]';
            }
            cache.add(value);
          }
          return value;
        });
      } catch (e) {
        return '[Cannot serialize error]';
      }
    }
  }
}