/**
 * Type definitions for error handling system
 */

export interface ErrorContext {
  request?: any;
  endpoint?: string;
  timestamp?: Date;
  additionalInfo?: Record<string, any>;
}

export interface StandardizedError {
  type: string;
  message: string;
  provider: string;
  originalError?: any;
  timestamp: Date;
  isRetryable: boolean;
  retryCount?: number;
}

export interface RetryConfig {
  maxAttempts: number;
  backoffPattern: number[];
  cooldownPeriodMs: number;
}

export class NetworkError implements StandardizedError {
  type = 'NetworkError';
  isRetryable = true;

  constructor(
    public message: string,
    public provider: string,
    public originalError?: any
  ) {
    this.timestamp = new Date();
  }

  timestamp: Date;
  retryCount?: number;
}

export class AuthenticationError implements StandardizedError {
  type = 'AuthenticationError';
  isRetryable = false;

  constructor(
    public message: string,
    public provider: string,
    public originalError?: any
  ) {
    this.timestamp = new Date();
  }

  timestamp: Date;
  retryCount?: number;
}

export class RateLimitError implements StandardizedError {
  type = 'RateLimitError';
  isRetryable = true;

  constructor(
    public message: string,
    public provider: string,
    public originalError?: any,
    public retryAfter?: number
  ) {
    this.timestamp = new Date();
  }

  timestamp: Date;
  retryCount?: number;
}

export class ServerError implements StandardizedError {
  type = 'ServerError';
  isRetryable = true;

  constructor(
    public message: string,
    public provider: string,
    public originalError?: any,
    public statusCode?: number
  ) {
    this.timestamp = new Date();
  }

  timestamp: Date;
  retryCount?: number;
}

export class ConfigurationError implements StandardizedError {
  type = 'ConfigurationError';
  isRetryable = false;

  constructor(
    public message: string,
    public provider: string,
    public originalError?: any
  ) {
    this.timestamp = new Date();
  }

  timestamp: Date;
  retryCount?: number;
}

export class ResponseError implements StandardizedError {
  type = 'ResponseError';
  isRetryable = false;

  constructor(
    public message: string,
    public provider: string,
    public originalError?: any
  ) {
    this.timestamp = new Date();
  }

  timestamp: Date;
  retryCount?: number;
}

export class GenericError implements StandardizedError {
  type = 'GenericError';
  isRetryable = false;

  constructor(
    public message: string,
    public provider: string,
    public originalError?: any
  ) {
    this.timestamp = new Date();
  }

  timestamp: Date;
  retryCount?: number;
}