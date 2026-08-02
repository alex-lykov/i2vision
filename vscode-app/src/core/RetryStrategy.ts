/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

/**
 * Retry strategy for LLM API calls
 * Implements exponential backoff with cooldown periods
 */

import {RetryConfig, StandardizedError} from './types';

export class RetryStrategy {
  public maxAttempts: number;
  public backoffPattern: number[];
  public cooldownPeriodMs: number;
  public currentAttempts: number = 0;
  private lastRetryTime: number = 0;

  constructor(config: Partial<RetryConfig> = {}) {
    this.maxAttempts = config.maxAttempts || 3;
    this.backoffPattern = config.backoffPattern || [1000, 2000, 4000];
    this.cooldownPeriodMs = config.cooldownPeriodMs || 300000;
    this.currentAttempts = 0;
  }

  public shouldRetry(error: StandardizedError, attemptNumber: number): boolean {
    if (!error.isRetryable) {
      return false;
    }

    if (this.isInCooldownPeriod()) {
      return false;
    }

    if (attemptNumber >= this.maxAttempts) {
      this.startCooldown();
      return false;
    }

    return true;
  }

  public getRetryDelay(error: StandardizedError, attemptNumber: number): number {
    if (error.type === 'RateLimitError' && (error as any).retryAfter !== undefined) {
      return (error as any).retryAfter * 1000;
    }

    const index = Math.min(attemptNumber - 1, this.backoffPattern.length - 1);
    return this.backoffPattern[index];
  }

  private isInCooldownPeriod(): boolean {
    if (this.lastRetryTime === 0) {
      return false;
    }

    const currentTime = Date.now();
    return currentTime - this.lastRetryTime < this.cooldownPeriodMs;
  }

  private startCooldown(): void {
    this.lastRetryTime = Date.now();
    this.currentAttempts = 0;
  }

  public reset(): void {
    this.lastRetryTime = 0;
    this.currentAttempts = 0;
  }

  public getCooldownRemainingMs(): number {
    if (this.lastRetryTime === 0) {
      return 0;
    }

    const currentTime = Date.now();
    const remaining = this.lastRetryTime + this.cooldownPeriodMs - currentTime;
    return Math.max(0, remaining);
  }

  public getStats(): {
    attempts: number;
    maxAttempts: number;
    inCooldown: boolean;
    cooldownRemainingMs: number
  } {
    return {
      attempts: this.currentAttempts,
      maxAttempts: this.maxAttempts,
      inCooldown: this.isInCooldownPeriod(),
      cooldownRemainingMs: this.getCooldownRemainingMs()
    };
  }

  public updateConfig(config: Partial<RetryConfig>): void {
    if (config.maxAttempts !== undefined) {
      this.maxAttempts = config.maxAttempts;
    }
    if (config.backoffPattern !== undefined) {
      this.backoffPattern = config.backoffPattern;
    }
    if (config.cooldownPeriodMs !== undefined) {
      this.cooldownPeriodMs = config.cooldownPeriodMs;
    }
  }
}