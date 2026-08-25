/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

// ProviderRulesResolver - Resolves provider-specific rules
// Provides a clean API for AgentBridge to retrieve rules for the active provider

import * as vscode from 'vscode';
import { ProviderRulesStore } from '../model/ProviderRulesStore';
import type { ProviderRule } from '../model/ProviderRule';

export class ProviderRulesResolver {
  private readonly rulesStore: ProviderRulesStore;
  private currentProviderId: string | undefined;
  private cachedRules: ProviderRule[] | undefined;

  constructor(context: vscode.ExtensionContext) {
    this.rulesStore = new ProviderRulesStore(context);
  }

  /**
   * Get rules for the specified provider
   * Caches results for performance until rules change
   */
  getRulesForProvider(providerId: string): ProviderRule[] {
    if (this.currentProviderId === providerId && this.cachedRules !== undefined) {
      return this.cachedRules;
    }
    
    this.cachedRules = this.rulesStore.getRulesForProvider(providerId);
    this.currentProviderId = providerId;
    return this.cachedRules;
  }

  /**
   * Invalidate cache - call this when rules change
   */
  invalidateCache(): void {
    this.cachedRules = undefined;
    this.currentProviderId = undefined;
  }

  /**
   * Get all rules across all providers
   */
  getAllRules(): Record<string, ProviderRule[]> {
    return this.rulesStore.getAll();
  }

  /**
   * Subscribe to rule changes
   */
  onRulesChanged(callback: () => void): vscode.Disposable {
    return this.rulesStore.onDidChange(() => {
      this.invalidateCache();
      callback();
    });
  }
}
