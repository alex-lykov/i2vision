/**
 * Tests for UserFeedbackGenerator
 */

import { UserFeedbackGenerator } from '../UserFeedbackGenerator';
import { NetworkError, AuthenticationError, RateLimitError, ServerError, ConfigurationError, ResponseError, GenericError } from '../types';

describe('UserFeedbackGenerator', () => {
  let feedbackGenerator: UserFeedbackGenerator;

  beforeEach(() => {
    feedbackGenerator = new UserFeedbackGenerator();
  });

  describe('formatMessage', () => {
    it('should generate user-friendly message for network errors', () => {
      const error = new NetworkError('Connection refused', 'Ollama');
      const message = feedbackGenerator.formatMessage(error);
      
      expect(message).toContain('Ollama');
      expect(message).toContain('network connectivity');
      expect(message).toContain('Connection refused');
      expect(message).toContain('check your network connection');
    });

    it('should generate user-friendly message for authentication errors', () => {
      const error = new AuthenticationError('Invalid API key', 'DeepSeek');
      const message = feedbackGenerator.formatMessage(error);
      
      expect(message).toContain('DeepSeek');
      expect(message).toContain('authentication');
      expect(message).toContain('Invalid API key');
      expect(message).toContain('check your API key');
    });

    it('should generate user-friendly message for rate limit errors', () => {
      const error = new RateLimitError('Rate limit exceeded', 'DeepSeek', undefined, 60);
      const message = feedbackGenerator.formatMessage(error);
      
      expect(message).toContain('DeepSeek');
      expect(message).toContain('rate limit');
      expect(message).toContain('Rate limit exceeded');
      expect(message).toContain('60 seconds');
    });

    it('should generate user-friendly message for rate limit errors without retry-after', () => {
      const error = new RateLimitError('Too many requests', 'DeepSeek');
      const message = feedbackGenerator.formatMessage(error);
      
      expect(message).toContain('DeepSeek');
      expect(message).toContain('rate limit');
      expect(message).toContain('Too many requests');
      expect(message).toContain('try again later');
    });

    it('should generate user-friendly message for server errors', () => {
      const error = new ServerError('Internal server error', '3D LLM', undefined, 500);
      const message = feedbackGenerator.formatMessage(error);
      
      expect(message).toContain('3D LLM');
      expect(message).toContain('server error');
      expect(message).toContain('Internal server error');
      expect(message).toContain('500');
      expect(message).toContain('try again later');
    });

    it('should generate user-friendly message for configuration errors', () => {
      const error = new ConfigurationError('Missing required parameter', 'Ollama');
      const message = feedbackGenerator.formatMessage(error);
      
      expect(message).toContain('Ollama');
      expect(message).toContain('configuration');
      expect(message).toContain('Missing required parameter');
      expect(message).toContain('check your settings');
    });

    it('should generate user-friendly message for response errors', () => {
      const error = new ResponseError('Invalid JSON response', '3D LLM');
      const message = feedbackGenerator.formatMessage(error);
      
      expect(message).toContain('3D LLM');
      expect(message).toContain('response format');
      expect(message).toContain('Invalid JSON response');
    });

    it('should generate user-friendly message for generic errors', () => {
      const error = new GenericError('Unknown error occurred', 'Ollama');
      const message = feedbackGenerator.formatMessage(error);
      
      expect(message).toContain('Ollama');
      expect(message).toContain('error');
      expect(message).toContain('Unknown error occurred');
      expect(message).toContain('try again');
    });

    it('should include original error message in all cases', () => {
      const testCases = [
        new NetworkError('ECONNREFUSED', 'TestProvider'),
        new AuthenticationError('401 Unauthorized', 'TestProvider'),
        new RateLimitError('429 Too Many Requests', 'TestProvider'),
        new ServerError('500 Internal Server Error', 'TestProvider'),
        new ConfigurationError('Config not found', 'TestProvider'),
        new ResponseError('JSON parse error', 'TestProvider'),
        new GenericError('Something went wrong', 'TestProvider')
      ];

      testCases.forEach(error => {
        const message = feedbackGenerator.formatMessage(error);
        expect(message).toContain(error.message);
      });
    });

    it('should include provider name in all messages', () => {
      const providers = ['Ollama', 'DeepSeek', '3D LLM', 'TestProvider'];
      
      providers.forEach(provider => {
        const error = new GenericError('Test error', provider);
        const message = feedbackGenerator.formatMessage(error);
        expect(message).toContain(provider);
      });
    });
  });

  describe('message formatting', () => {
    it('should format messages consistently', () => {
      const error = new NetworkError('Connection timeout', 'Ollama');
      const message = feedbackGenerator.formatMessage(error);
      
      // Should start with provider name
      expect(message).toMatch(/^Ollama/);
      
      // Should contain error type
      expect(message).toMatch(/network connectivity/i);
      
      // Should contain original error
      expect(message).toContain('Connection timeout');
      
      // Should contain actionable advice
      expect(message).toMatch(/check your network connection/i);
    });

    it('should handle empty error messages gracefully', () => {
      const error = new GenericError('', 'TestProvider');
      const message = feedbackGenerator.formatMessage(error);
      
      expect(message).toContain('TestProvider');
      expect(message).toContain('error');
    });

    it('should handle undefined error messages gracefully', () => {
      const error = new GenericError(undefined as any, 'TestProvider');
      const message = feedbackGenerator.formatMessage(error);
      
      expect(message).toContain('TestProvider');
      expect(message).toContain('error');
    });
  });
});