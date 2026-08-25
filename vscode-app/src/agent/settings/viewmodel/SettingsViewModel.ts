/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

import * as vscode from 'vscode';
import {SettingsStore} from '../model/SettingsStore';
import {ProviderRulesStore} from '../model/ProviderRulesStore';
import {AgentSettings} from '../../AgentSettings';
import type {SettingsSchema} from '../model/SettingsSchema';
import {SettingsRegistry} from '../registry/SettingsRegistry';
import {BUILTIN_SETTING_DESCRIPTORS} from '../registry/builtinSettings';
import {CustomSettingsLoader} from '../registry/customSettingsLoader';
import {SettingSectionViewModel} from './SettingSectionViewModel';
import type { ProviderRules } from '../model/ProviderRules';
import type { ProviderRule } from '../model/ProviderRule';

export interface SettingsState {
  schema: SettingsSchema;
  values: Record<string, unknown>;
  isDirty: boolean;
  isSaving: boolean;
  validationErrors: Record<string, string[]>;
}

export class SettingsViewModel {
  private readonly store: SettingsStore;
  private readonly rulesStore: ProviderRulesStore;
  private readonly registry: SettingsRegistry;
  private readonly sections: SettingSectionViewModel[];
  private readonly onDidChangeEmitter = new vscode.EventEmitter<SettingsState>();
  private readonly onDidSaveEmitter = new vscode.EventEmitter<void>();
  private readonly onDidValidationChangeEmitter = new vscode.EventEmitter<Record<string, string[]>>();
  private readonly onRulesChangedEmitter = new vscode.EventEmitter<ProviderRules>();
  private readonly onProviderChangedEmitter = new vscode.EventEmitter<string>();
  private disposables: vscode.Disposable[] = [];

  readonly onDidChange = this.onDidChangeEmitter.event;
  readonly onDidSave = this.onDidSaveEmitter.event;
  readonly onDidValidationChange = this.onDidValidationChangeEmitter.event;
  readonly onRulesChanged = this.onRulesChangedEmitter.event;
  readonly onProviderChanged = this.onProviderChangedEmitter.event;

  constructor(context: vscode.ExtensionContext) {
    this.registry = new SettingsRegistry();
    this.registry.registerMany(BUILTIN_SETTING_DESCRIPTORS);

    const workspaceRoot = vscode.workspace.workspaceFolders?.[0]?.uri.fsPath;
    const customLoader = new CustomSettingsLoader();
    this.registry.registerMany(customLoader.load(workspaceRoot));

    this.store = new SettingsStore(context);
    this.rulesStore = new ProviderRulesStore(context);
    
    // Wire up rules store change events
    this.disposables.push(
      this.rulesStore.onDidChange((rules) => {
        this.onRulesChangedEmitter.fire(rules);
      })
    );
    
    const schema = this.registry.getSchema();
    const initialValues = this.store.getAll();

    this.sections = schema.sections.map(
      (section) => new SettingSectionViewModel(section, initialValues as unknown as Record<string, unknown>)
    );
  }

  // Provider rule helpers
  /** Get all rule blocks for a provider */
  getProviderRules(providerId: string): ProviderRule[] | undefined {
    return this.rulesStore.getRulesForProvider(providerId);
  }

  /** Replace rule blocks for a provider */
  async setProviderRules(providerId: string, rules: ProviderRule[]): Promise<void> {
    await this.rulesStore.setRulesForProvider(providerId, rules);
  }
  
  /** Add a single rule */
  async addProviderRule(providerId: string, rule: ProviderRule): Promise<void> {
    await this.rulesStore.addRule(providerId, rule);
  }
  
  /** Update an existing rule */
  async updateProviderRule(providerId: string, ruleId: string, rule: ProviderRule): Promise<void> {
    await this.rulesStore.updateRule(providerId, ruleId, rule);
  }
  
  /** Delete a rule */
  async deleteProviderRule(providerId: string, ruleId: string): Promise<void> {
    await this.rulesStore.deleteRule(providerId, ruleId);
  }
  
  /** Notify that provider has changed - triggers prompt rebuild */
  notifyProviderChanged(providerId: string): void {
    this.onProviderChangedEmitter.fire(providerId);
  }

  getState(): SettingsState {
    return {
      schema: this.registry.getSchema(),
      values: this.toValueRecord(),
      isDirty: this.sections.some((section) =>
        section.items.some((item) => item.value.isDirty)
      ),
      isSaving: false,
      validationErrors: this.toValidationRecord()
    };
  }

  getValue(key: string): unknown {
    for (const section of this.sections) {
      const item = section.items.find((candidate) => candidate.value.key === key);
      if (item) {
        return item.value.value;
      }
    }
    return undefined;
  }

  setValue(key: string, value: unknown): void {
    for (const section of this.sections) {
      const item = section.items.find((candidate) => candidate.value.key === key);
      if (item) {
        item.update(value);
        break;
      }
    }

    this.emitChange();
  }

  resetGroup(groupId: string): void {
    const section = this.sections.find((candidate) => candidate.id === groupId);
    if (!section) {
      return;
    }

    for (const item of section.items) {
      item.reset();
    }

    this.emitChange();
  }

  resetAll(): void {
    for (const section of this.sections) {
      for (const item of section.items) {
        item.reset();
      }
    }

    this.emitChange();
  }

  async save(): Promise<boolean> {
    const errors = this.toValidationRecord();
    if (Object.keys(errors).length > 0) {
      this.onDidValidationChangeEmitter.fire(errors);
      return false;
    }

    await this.store.update(this.toValueRecord() as Partial<AgentSettings>);

    for (const section of this.sections) {
      for (const item of section.items) {
        item.value.isDirty = false;
      }
    }

    this.emitChange();
    this.onDidSaveEmitter.fire();
    return true;
  }

  exportJson(): string {
    return JSON.stringify(this.toValueRecord(), null, 2);
  }

  importJson(json: string): void {
    const parsed = JSON.parse(json) as Record<string, unknown>;
    const flatValues = flatten(parsed);

    for (const [key, value] of Object.entries(flatValues)) {
      this.setValue(key, value);
    }
  }

  private emitChange(): void {
    this.onDidChangeEmitter.fire(this.getState());
    this.onDidValidationChangeEmitter.fire(this.toValidationRecord());
  }

  private toValueRecord(): Record<string, unknown> {
    const result: Record<string, unknown> = {};
    for (const section of this.sections) {
      for (const item of section.items) {
        result[item.value.key] = item.value.value;
      }
    }
    return result;
  }

  private toValidationRecord(): Record<string, string[]> {
    const result: Record<string, string[]> = {};
    for (const section of this.sections) {
      for (const item of section.items) {
        if (!item.value.isValid) {
          result[item.value.key] = item.value.validationErrors;
        }
      }
    }
    return result;
  }
  
  dispose(): void {
    for (const d of this.disposables) {
      d.dispose();
    }
    this.disposables = [];
  }
}

function flatten(value: unknown, prefix = '', out: Record<string, unknown> = {}): Record<string, unknown> {
  if (value && typeof value === 'object' && !Array.isArray(value)) {
    for (const [key, child] of Object.entries(value as Record<string, unknown>)) {
      const fullKey = prefix ? `${prefix}.${key}` : key;
      flatten(child, fullKey, out);
    }
    return out;
  }

  out[prefix] = value;
  return out;
}
