/**
 * Tests for ErrorClassifier
 */

import { ErrorClassifier } from '../ErrorClassifier';
import { NetworkError, AuthenticationError, RateLimitError, ServerError, ConfigurationError, ResponseError, GenericError } from '../types';

describe('ErrorClassifier', () => {
  let classifier: ErrorClassifier;

  beforeEach(() => {
    classifier = new ErrorClassifier();
  });

  describe('classify', () => {
    it('should classify network errors correctly', () => {
      const networkErrors = [
        { code: 'ECONNREFUSED' },
        { code: 'ENOTFOUND' },
        { code: 'ECONNRESET' },
        { code: 'ETIMEDOUT' },
        { code: 'EAI_AGAIN' },
        { name: 'TypeError', message: 'Failed to fetch' },
        { name: 'TypeError', message: 'NetworkError when attempting to fetch resource' },
        { isAxiosError: true }
      ];

      networkErrors.forEach(error => {
        const result = classifier.classify(error, 'TestProvider');
        expect(result).toBeInstanceOf(NetworkError);
        expect(result.type).toBe('NetworkError');
        expect(result.provider).toBe('TestProvider');
      });
    });

    it('should classify authentication errors correctly', () => {
      const authErrors = [
        { response: { status: 401 } },
        { message: 'authentication failed' },
        { message: 'Authorization required' },
        { message: 'Invalid API key' },
        { message: 'unauthenticated request' }
      ];

      authErrors.forEach(error => {
        const result = classifier.classify(error, 'TestProvider');
        expect(result).toBeInstanceOf(AuthenticationError);
        expect(result.type).toBe('AuthenticationError');
        expect(result.provider).toBe('TestProvider');
      });
    });

    it('should classify rate limit errors correctly', () => {
      const rateLimitErrors = [
        { response: { status: 429 } },
        { message: 'rate limit exceeded' },
        { message: 'too many requests' },
        { message: 'quota exceeded' }
      ];

      rateLimitErrors.forEach(error => {
        const result = classifier.classify(error, 'TestProvider');
        expect(result).toBeInstanceOf(RateLimitError);
        expect(result.type).toBe('RateLimitError');
        expect(result.provider).toBe('TestProvider');
      });
    });

    it('should classify server errors correctly', () => {
      const serverErrors = [
        { response: { status: 500 } },
        { response: { status: 503 } },
        { message: 'Internal Server Error' },
        { message: 'service unavailable' }
      ];

      serverErrors.forEach(error => {
        const result = classifier.classify(error, 'TestProvider');
        expect(result).toBeInstanceOf(ServerError);
        expect(result.type).toBe('ServerError');
        expect(result.provider).toBe('TestProvider');
      });
    });

    it('should classify configuration errors correctly', () => {
      const configErrors = [
        { message: 'configuration error' },
        { message: 'config not found' },
        { message: 'not configured properly' },
        { message: 'missing required parameter' }
      ];

      configErrors.forEach(error => {
        const result = classifier.classify(error, 'TestProvider');
        expect(result).toBeInstanceOf(ConfigurationError);
        expect(result.type).toBe('ConfigurationError');
        expect(result.provider).toBe('TestProvider');
      });
    });

    it('should classify response errors correctly', () => {
      const responseErrors = [
        { name: 'SyntaxError', message: 'Unexpected token < in JSON' },
        { message: 'invalid response format' },
        { message: 'malformed JSON response' },
        { message: 'failed to parse response' }
      ];

      responseErrors.forEach(error => {
        const result = classifier.classify(error, 'TestProvider');
        expect(result).toBeInstanceOf(ResponseError);
        expect(result.type).toBe('ResponseError');
        expect(result.provider).toBe('TestProvider');
      });
    });

    it('should classify generic errors for unknown error types', () => {
      const genericErrors = [
        'Simple string error',
        { message: 'some random error' },
        { some: 'unknown', structure: 'error' }
      ];

      genericErrors.forEach(error => {
        const result = classifier.classify(error, 'TestProvider');
        expect(result).toBeInstanceOf(GenericError);
        expect(result.type).toBe('GenericError');
        expect(result.provider).toBe('TestProvider');
      });
    });

    it('should extract retry-after header for rate limit errors', () => {
      const errorWithRetryAfter = {
        response: {
          status: 429,
          headers: { 'retry-after': '60' }
        }
      };

      const result = classifier.classify(errorWithRetryAfter, 'TestProvider') as RateLimitError;
      expect(result).toBeInstanceOf(RateLimitError);
      expect(result.retryAfter).toBe(60);
    });

    it('should extract status code for server errors', () => {
      const errorWithStatus = {
        response: { status: 503 }
      };

      const result = classifier.classify(errorWithStatus, 'TestProvider') as ServerError;
      expect(result).toBeInstanceOf(ServerError);
      expect(result.statusCode).toBe(503);
    });

    it('should fall back to GenericError for unclassifiable errors', () => {
      const problematicError = {
        toString: () => { throw new Error('Cannot convert to string'); }
      };

      const result = classifier.classify(problematicError, 'TestProvider');
      expect(result).toBeInstanceOf(GenericError);
      expect(result.message).toBeDefined();
    });
  });

  describe('message extraction', () => {
    it('should extract messages from Error objects', () => {
      const error = new Error('Test error message');
      const result = classifier.classify(error, 'TestProvider');
      expect(result.message).toBe('Test error message');
    });

    it('should extract messages from string errors', () => {
      const result = classifier.classify('Simple string error', 'TestProvider');
      expect(result.message).toBe('Simple string error');
    });

    it('should extract messages from response data', () => {
      const error = {
        response: {
          data: { message: 'API error message' }
        }
      };
      const result = classifier.classify(error, 'TestProvider');
      expect(result.message).toBe('API error message');
    });

    it('should handle circular references in error objects', () => {
      const error: any = { message: 'Test error' };
      error.self = error; // Create circular reference
      
      const result = classifier.classify(error, 'TestProvider');
      expect(result.message).toContain('Test error');
    });
  });
});