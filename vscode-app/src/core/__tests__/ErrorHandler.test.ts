/**
 * Tests for ErrorHandler
 */

import { ErrorHandler } from '../ErrorHandler';
import { ErrorClassifier } from '../ErrorClassifier';
import { RetryStrategy } from '../RetryStrategy';
import { UserFeedbackGenerator } from '../UserFeedbackGenerator';
import { NetworkError, AuthenticationError, RateLimitError, ServerError } from '../types';

describe('ErrorHandler', () => {
  let errorHandler: ErrorHandler;
  let mockErrorClassifier: jest.Mocked<ErrorClassifier>;
  let mockRetryStrategy: jest.Mocked<RetryStrategy>;
  let mockUserFeedbackGenerator: jest.Mocked<UserFeedbackGenerator>;

  beforeEach(() => {
    errorHandler = new ErrorHandler();
    
    // Mock the dependencies
    mockErrorClassifier = {
      classify: jest.fn()
    } as any;
    mockRetryStrategy = {
      maxAttempts: 3,
      shouldRetry: jest.fn(),
      getRetryDelay: jest.fn()
    } as any;
    mockUserFeedbackGenerator = {
      formatMessage: jest.fn()
    } as any;
    
    // Replace the real instances with mocks
    (errorHandler as any).errorClassifier = mockErrorClassifier;
    (errorHandler as any).retryStrategy = mockRetryStrategy;
    (errorHandler as any).userFeedbackGenerator = mockUserFeedbackGenerator;
  });

  describe('handleError', () => {
    it('should return successful result when operation succeeds', async () => {
      const result = await errorHandler.handleError(
        async () => 'success',
        'TestProvider',
        { request: { model: 'test-model' } }
      );
      
      expect(result).toBe('success');
    });

    it('should retry on failure when shouldRetry returns true', async () => {
      let attempt = 0;
      mockErrorClassifier.classify.mockReturnValue(new NetworkError('Network error', 'TestProvider'));
      mockRetryStrategy.shouldRetry.mockImplementation((error, attemptNum) => attemptNum < 3);
      mockRetryStrategy.getRetryDelay.mockReturnValue(0);
      mockUserFeedbackGenerator.formatMessage.mockReturnValue('User-friendly message');

      const result = await errorHandler.handleError(
        async () => {
          attempt++;
          if (attempt < 3) throw new Error('Network error');
          return 'success after retries';
        },
        'TestProvider',
        { request: { model: 'test-model' } }
      );

      expect(result).toBe('success after retries');
      expect(attempt).toBe(3);
    });

    it('should throw user-friendly error when all retries fail', async () => {
      const testError = new NetworkError('Network error', 'TestProvider');
      mockErrorClassifier.classify.mockReturnValue(testError);
      mockRetryStrategy.shouldRetry.mockReturnValue(true);
      mockRetryStrategy.getRetryDelay.mockReturnValue(0);
      mockUserFeedbackGenerator.formatMessage.mockReturnValue('User-friendly network error message');

      await expect(errorHandler.handleError(
        async () => { throw new Error('Network error'); },
        'TestProvider',
        { request: { model: 'test-model' } }
      )).rejects.toThrow('User-friendly network error message');
    });

    it('should handle different error types correctly', async () => {
      const testCases = [
        { error: new NetworkError('Network error', 'TestProvider'), expectedType: 'NetworkError' },
        { error: new AuthenticationError('Auth error', 'TestProvider'), expectedType: 'AuthenticationError' },
        { error: new RateLimitError('Rate limit', 'TestProvider', undefined, 60), expectedType: 'RateLimitError' },
        { error: new ServerError('Server error', 'TestProvider', undefined, 500), expectedType: 'ServerError' }
      ];

      for (const testCase of testCases) {
        mockErrorClassifier.classify.mockReturnValue(testCase.error);
        mockRetryStrategy.shouldRetry.mockReturnValue(false);
        mockUserFeedbackGenerator.formatMessage.mockReturnValue(`User-friendly ${testCase.expectedType}`);

        await expect(errorHandler.handleError(
          async () => { throw new Error('Test error'); },
          'TestProvider',
          { request: { model: 'test-model' } }
        )).rejects.toThrow(`User-friendly ${testCase.expectedType}`);
      }
    });
  });

  describe('error reporting', () => {
    let consoleErrorSpy: jest.SpyInstance;

    beforeEach(() => {
      consoleErrorSpy = jest.spyOn(console, 'error').mockImplementation();
    });

    afterEach(() => {
      consoleErrorSpy.mockRestore();
    });

    it('should log errors with provider and type information', async () => {
      const testError = new NetworkError('Test network error', 'TestProvider');
      mockErrorClassifier.classify.mockReturnValue(testError);
      mockRetryStrategy.shouldRetry.mockReturnValue(false);
      mockUserFeedbackGenerator.formatMessage.mockReturnValue('User-friendly message');

      try {
        await errorHandler.handleError(
          async () => { throw new Error('Test error'); },
          'TestProvider',
          { request: { model: 'test-model', prompt: 'test prompt' } }
        );
      } catch (error) {
        // Expected to throw
      }

      expect(consoleErrorSpy).toHaveBeenCalledWith(
        expect.stringContaining('[TestProvider] [NetworkError] Test network error')
      );
    });
  });
});