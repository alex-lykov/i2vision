/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

// ProviderRulesStore - Persistence layer for provider-specific rules
// Stores rule collections per provider ID and emits change events

import * as vscode from 'vscode';
import * as fs from 'fs';
import * as path from 'path';
import type { ProviderRules } from './ProviderRules';
import type { ProviderRule } from './ProviderRule';

export class ProviderRulesStore {
  private rules: ProviderRules;
  private rulesPath: string;
  private onDidChangeEmitter: vscode.EventEmitter<ProviderRules>;

  constructor(context: vscode.ExtensionContext) {
    this.rulesPath = this.getRulesPath(context);
    this.onDidChangeEmitter = new vscode.EventEmitter<ProviderRules>();
    this.rules = {};
    this.load();
  }

  /** Event for external listeners */
  get onDidChange(): vscode.Event<ProviderRules> {
    return this.onDidChangeEmitter.event;
  }

  private getRulesPath(context: vscode.ExtensionContext): string {
    const workspaceRoot = vscode.workspace.workspaceFolders?.[0]?.uri.fsPath;
    if (!workspaceRoot) {
      return path.join(context.storagePath || context.extensionPath, 'i2-vision-provider-rules.json');
    }
    return path.join(workspaceRoot, '.vscode', 'i2-vision-provider-rules.json');
  }

  private load(): void {
    try {
      if (fs.existsSync(this.rulesPath)) {
        const content = fs.readFileSync(this.rulesPath, 'utf-8');
        this.rules = JSON.parse(content);
      }
    } catch (e: any) {
      console.error('ProviderRulesStore load error:', e.message);
    }
  }

  private save(): void {
    try {
      const dir = path.dirname(this.rulesPath);
      if (!fs.existsSync(dir)) {
        fs.mkdirSync(dir, { recursive: true });
      }
      fs.writeFileSync(this.rulesPath, JSON.stringify(this.rules, null, 2), 'utf-8');
    } catch (e: any) {
      console.error('ProviderRulesStore save error:', e.message);
    }
  }

  /** Get all rules */
  getAll(): ProviderRules {
    return { ...this.rules };
  }

  /** Get rules for a specific provider */
  getRulesForProvider(providerId: string): ProviderRule[] {
    return this.rules[providerId] ?? [];
  }

  /** Set all rules for a provider (replaces existing rules) */
  async setRulesForProvider(providerId: string, rules: ProviderRule[]): Promise<void> {
    this.rules = { ...this.rules, [providerId]: rules };
    this.save();
    this.onDidChangeEmitter.fire(this.rules);
  }

  /** Add a single rule to a provider */
  async addRule(providerId: string, rule: ProviderRule): Promise<void> {
    const currentRules = this.getRulesForProvider(providerId);
    const updatedRules = [...currentRules, rule];
    await this.setRulesForProvider(providerId, updatedRules);
  }

  /** Update an existing rule */
  async updateRule(providerId: string, ruleId: string, updatedRule: ProviderRule): Promise<void> {
    const currentRules = this.getRulesForProvider(providerId);
    const updatedRules = currentRules.map(r => r.id === ruleId ? updatedRule : r);
    await this.setRulesForProvider(providerId, updatedRules);
  }

  /** Delete a rule */
  async deleteRule(providerId: string, ruleId: string): Promise<void> {
    const currentRules = this.getRulesForProvider(providerId);
    const updatedRules = currentRules.filter(r => r.id !== ruleId);
    await this.setRulesForProvider(providerId, updatedRules);
  }

  /** Reset all rules */
  async reset(): Promise<void> {
    this.rules = {};
    this.save();
    this.onDidChangeEmitter.fire(this.rules);
  }
}
