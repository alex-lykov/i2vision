/**
 * Tests for RetryStrategy
 */

import { RetryStrategy } from '../RetryStrategy';
import { NetworkError, AuthenticationError, RateLimitError, ServerError, GenericError } from '../types';

describe('RetryStrategy', () => {
  let retryStrategy: RetryStrategy;

  beforeEach(() => {
    retryStrategy = new RetryStrategy();
  });

  describe('shouldRetry', () => {
    it('should retry network errors', () => {
      const networkError = new NetworkError('Connection failed', 'TestProvider');
      expect(retryStrategy.shouldRetry(networkError, 1)).toBe(true);
      expect(retryStrategy.shouldRetry(networkError, 2)).toBe(true);
    });

    it('should not retry authentication errors', () => {
      const authError = new AuthenticationError('Invalid API key', 'TestProvider');
      expect(retryStrategy.shouldRetry(authError, 1)).toBe(false);
    });

    it('should retry rate limit errors with retry-after', () => {
      const rateLimitError = new RateLimitError('Rate limit exceeded', 'TestProvider', undefined, 60);
      expect(retryStrategy.shouldRetry(rateLimitError, 1)).toBe(true);
    });

    it('should retry rate limit errors without retry-after up to max attempts', () => {
      const rateLimitError = new RateLimitError('Rate limit exceeded', 'TestProvider');
      expect(retryStrategy.shouldRetry(rateLimitError, 1)).toBe(true);
      expect(retryStrategy.shouldRetry(rateLimitError, 2)).toBe(true);
      expect(retryStrategy.shouldRetry(rateLimitError, 3)).toBe(true);
      expect(retryStrategy.shouldRetry(rateLimitError, 4)).toBe(false);
    });

    it('should retry server errors', () => {
      const serverError = new ServerError('Internal server error', 'TestProvider', undefined, 500);
      expect(retryStrategy.shouldRetry(serverError, 1)).toBe(true);
      expect(retryStrategy.shouldRetry(serverError, 2)).toBe(true);
    });

    it('should not retry generic errors after max attempts', () => {
      const genericError = new GenericError('Unknown error', 'TestProvider');
      expect(retryStrategy.shouldRetry(genericError, 1)).toBe(true);
      expect(retryStrategy.shouldRetry(genericError, 2)).toBe(true);
      expect(retryStrategy.shouldRetry(genericError, 3)).toBe(true);
      expect(retryStrategy.shouldRetry(genericError, 4)).toBe(false);
    });

    it('should not retry when attempt exceeds max attempts', () => {
      const networkError = new NetworkError('Connection failed', 'TestProvider');
      expect(retryStrategy.shouldRetry(networkError, 10)).toBe(false);
    });
  });

  describe('getRetryDelay', () => {
    it('should return exponential backoff delays', () => {
      const networkError = new NetworkError('Connection failed', 'TestProvider');
      
      expect(retryStrategy.getRetryDelay(networkError, 1)).toBe(1000); // 1s
      expect(retryStrategy.getRetryDelay(networkError, 2)).toBe(2000); // 2s
      expect(retryStrategy.getRetryDelay(networkError, 3)).toBe(4000); // 4s
      expect(retryStrategy.getRetryDelay(networkError, 4)).toBe(4000); // capped at 4s
    });

    it('should return retry-after delay for rate limit errors', () => {
      const rateLimitError = new RateLimitError('Rate limit exceeded', 'TestProvider', undefined, 30);
      expect(retryStrategy.getRetryDelay(rateLimitError, 1)).toBe(30000); // 30s
    });

    it('should return minimum delay for very short retry-after values', () => {
      const rateLimitError = new RateLimitError('Rate limit exceeded', 'TestProvider', undefined, 0.5);
      expect(retryStrategy.getRetryDelay(rateLimitError, 1)).toBe(1000); // minimum 1s
    });

    it('should return maximum delay for very long retry-after values', () => {
      const rateLimitError = new RateLimitError('Rate limit exceeded', 'TestProvider', undefined, 300);
      expect(retryStrategy.getRetryDelay(rateLimitError, 1)).toBe(30000); // capped at 30s
    });

    it('should return exponential backoff for rate limit errors without retry-after', () => {
      const rateLimitError = new RateLimitError('Rate limit exceeded', 'TestProvider');
      expect(retryStrategy.getRetryDelay(rateLimitError, 1)).toBe(1000);
      expect(retryStrategy.getRetryDelay(rateLimitError, 2)).toBe(2000);
    });
  });

  describe('configuration', () => {
    it('should have correct default max attempts', () => {
      expect(retryStrategy.maxAttempts).toBe(3);
    });

    it('should have correct default delays', () => {
      // Default backoff pattern is [1000, 2000, 4000]
      expect(retryStrategy.backoffPattern[0]).toBe(1000);
      expect(retryStrategy.backoffPattern[2]).toBe(4000);
    });
  });
});