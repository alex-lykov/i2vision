/**
 * Provider-agnostic error handler for LLM providers
 * Implements consistent error handling, retry logic, and user feedback
 */

import { ErrorClassifier } from './ErrorClassifier';
import { RetryStrategy } from './RetryStrategy';
import { UserFeedbackGenerator } from './UserFeedbackGenerator';
import { StandardizedError, ErrorContext } from './types';

export class ErrorHandler {
  private errorClassifier: ErrorClassifier;
  private retryStrategy: RetryStrategy;
  private userFeedbackGenerator: UserFeedbackGenerator;

  constructor() {
    this.errorClassifier = new ErrorClassifier();
    this.retryStrategy = new RetryStrategy();
    this.userFeedbackGenerator = new UserFeedbackGenerator();
  }

  /**
   * Handle errors with automatic retry and consistent error reporting
   * @param operation The async operation to execute
   * @param providerName Name of the LLM provider
   * @param context Additional context for error reporting
   * @returns Promise with the operation result
   */
  public async handleError<T>(
    operation: () => Promise<T>,
    providerName: string,
    context: ErrorContext
  ): Promise<T> {
    let lastError: StandardizedError | null = null;
    let attempt = 0;

    while (attempt < this.retryStrategy.maxAttempts) {
      try {
        return await operation();
      } catch (error) {
        lastError = this.errorClassifier.classify(error, providerName);
        attempt++;

        if (!this.retryStrategy.shouldRetry(lastError, attempt)) {
          break;
        }

        const delay = this.retryStrategy.getRetryDelay(lastError, attempt);
        await this.sleep(delay);
      }
    }

    if (lastError) {
      this.reportError(lastError, context);
      throw this.createUserFriendlyError(lastError);
    }

    throw new Error('Unknown error occurred');
  }

  /**
   * Report error to logging and monitoring systems
   */
  private reportError(error: StandardizedError, context: ErrorContext): void {
    console.error(`[${error.provider}] [${error.type}] ${error.message}`);
    if (context.request) {
      console.debug('Request context:', context);
    }
  }

  /**
   * Create user-friendly error from standardized error
   */
  private createUserFriendlyError(error: StandardizedError): Error {
    const userMessage = this.userFeedbackGenerator.formatMessage(error);
    return new Error(userMessage);
  }

  /**
   * Sleep for specified milliseconds
   */
  private sleep(ms: number): Promise<void> {
    return new Promise(resolve => setTimeout(resolve, ms));
  }
}