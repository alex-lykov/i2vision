/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

import * as vscode from 'vscode';
import { AgentSettingsManager } from '../../AgentSettings';

/**
 * Persistence adapter for settings values.
 *
 * Initially wraps the existing AgentSettingsManager so the MVVM migration can
 * proceed without losing current persistence behavior.
 */
export class SettingsStore {
  private readonly legacyManager: AgentSettingsManager;

  constructor(context: vscode.ExtensionContext) {
    this.legacyManager = AgentSettingsManager.getInstance(context);
  }

  getAll(): Record<string, unknown> {
    return this.toRecord(this.legacyManager.getSettings());
  }

  async update(values: Record<string, unknown>): Promise<void> {
    const nested = this.fromFlatRecord(values);
    await this.legacyManager.updateSettings(nested as never);
  }

  async resetToDefaults(): Promise<void> {
    await this.legacyManager.resetToDefaults();
  }

  private toRecord(value: unknown): Record<string, unknown> {
    const result: Record<string, unknown> = {};

    const visit = (prefix: string, current: unknown): void => {
      if (current && typeof current === 'object' && !Array.isArray(current)) {
        for (const [key, child] of Object.entries(current as Record<string, unknown>)) {
          const fullKey = prefix ? `${prefix}.${key}` : key;
          visit(fullKey, child);
        }
        return;
      }

      result[prefix] = current;
    };

    visit('', value);
    return result;
  }

  private fromFlatRecord(values: Record<string, unknown>): Record<string, unknown> {
    const result: Record<string, unknown> = {};

    for (const [key, value] of Object.entries(values)) {
      const parts = key.split('.');
      let target: Record<string, unknown> = result;

      for (let i = 0; i < parts.length - 1; i++) {
        const part = parts[i];
        target[part] = target[part] ?? {};
        target = target[part] as Record<string, unknown>;
      }

      target[parts[parts.length - 1]] = value;
    }

    return result;
  }
}
