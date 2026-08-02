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
      expect(message).toContain('Network connection failed');
      expect(message).toContain('check your network connection');
    });

    it('should generate user-friendly message for authentication errors', () => {
      const error = new AuthenticationError('Invalid API key', 'DeepSeek');
      const message = feedbackGenerator.formatMessage(error);
      
      expect(message).toContain('DeepSeek');
      expect(message).toContain('Authentication failed');
      expect(message).toContain('verify your API key');
    });

    it('should generate user-friendly message for rate limit errors', () => {
      const error = new RateLimitError('Rate limit exceeded', 'DeepSeek', undefined, 60);
      const message = feedbackGenerator.formatMessage(error);
      
      expect(message).toContain('DeepSeek');
      expect(message).toContain('Rate limit exceeded');
      expect(message).toContain('try again later');
    });

    it('should generate user-friendly message for rate limit errors without retry-after', () => {
      const error = new RateLimitError('Too many requests', 'DeepSeek');
      const message = feedbackGenerator.formatMessage(error);
      
      expect(message).toContain('DeepSeek');
      expect(message).toContain('rate limit');
      expect(message).toContain('try again later');
    });

    it('should generate user-friendly message for server errors', () => {
      const error = new ServerError('Internal server error', '3D LLM', undefined, 500);
      const message = feedbackGenerator.formatMessage(error);
      
      expect(message).toContain('Server error occurred');
      expect(message).toContain('try again later');
    });

    it('should generate user-friendly message for configuration errors', () => {
      const error = new ConfigurationError('Missing required parameter', 'Ollama');
      const message = feedbackGenerator.formatMessage(error);
      
      expect(message).toContain('Ollama');
      expect(message).toContain('Configuration error');
      expect(message).toContain('check your provider configuration');
    });

    it('should generate user-friendly message for response errors', () => {
      const error = new ResponseError('Invalid JSON response', '3D LLM');
      const message = feedbackGenerator.formatMessage(error);
      
      expect(message).toContain('Invalid response received');
      expect(message).toContain('response format');
    });

    it('should generate user-friendly message for generic errors', () => {
      const error = new GenericError('Unknown error occurred', 'Ollama');
      const message = feedbackGenerator.formatMessage(error);
      
      expect(message).toContain('error');
      expect(message).toContain('Unknown error occurred');
    });

    it('should include provider name when available', () => {
      const providers = ['Ollama', 'DeepSeek', '3D LLM'];
      
      providers.forEach(provider => {
        const error = new NetworkError('Test error', provider);
        const message = feedbackGenerator.formatMessage(error);
        expect(message).toContain(provider);
      });
    });
  });

  describe('message formatting', () => {
    it('should format messages consistently', () => {
      const error = new NetworkError('Connection timeout', 'Ollama');
      const message = feedbackGenerator.formatMessage(error);
      
      // Should contain error type description
      expect(message).toMatch(/Network connection failed/i);
      
      // Should contain provider reference
      expect(message).toContain('Ollama');
      
      // Should contain actionable advice
      expect(message).toMatch(/check your network connection/i);
    });

    it('should handle empty error messages gracefully', () => {
      const error = new GenericError('', 'TestProvider');
      const message = feedbackGenerator.formatMessage(error);
      
      expect(message).toContain('error');
    });

    it('should handle undefined error messages gracefully', () => {
      const error = new GenericError(undefined as any, 'TestProvider');
      const message = feedbackGenerator.formatMessage(error);
      
      expect(message).toContain('error');
    });
  });
});
