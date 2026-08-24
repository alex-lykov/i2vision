/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

// SettingsStore - MVVM store for agent settings
// Provides loading, persistence, and reactive updates.

import * as vscode from 'vscode';
import * as fs from 'fs';
import * as path from 'path';
import {AgentSettings, DEFAULT_SETTINGS} from '../../AgentSettings';

export class SettingsStore {
  private settings: AgentSettings;
  private settingsPath: string;
  private onDidChangeEmitter: vscode.EventEmitter<Partial<AgentSettings>>;

  constructor(context: vscode.ExtensionContext) {
    this.settingsPath = this.getSettingsPath(context);
    this.onDidChangeEmitter = new vscode.EventEmitter<Partial<AgentSettings>>();
    this.settings = { ...DEFAULT_SETTINGS };
    this.load();
  }

  /** Event for external listeners */
  get onDidChange(): vscode.Event<Partial<AgentSettings>> {
    return this.onDidChangeEmitter.event;
  }

  private getSettingsPath(context: vscode.ExtensionContext): string {
    const workspaceRoot = vscode.workspace.workspaceFolders?.[0]?.uri.fsPath;
    if (!workspaceRoot) {
      return path.join(context.storagePath || context.extensionPath, 'i2-vision-settings.json');
    }
    return path.join(workspaceRoot, '.vscode', 'i2-vision-settings.json');
  }

  private load(): void {
    try {
      if (fs.existsSync(this.settingsPath)) {
        const content = fs.readFileSync(this.settingsPath, 'utf-8');
        const user = JSON.parse(content);
        this.settings = this.deepMerge(this.settings, user);
      }
    } catch (e: any) {
      console.error('SettingsStore load error:', e.message);
    }
  }

  private save(): void {
    try {
      const dir = path.dirname(this.settingsPath);
      if (!fs.existsSync(dir)) {
        fs.mkdirSync(dir, { recursive: true });
      }
      fs.writeFileSync(this.settingsPath, JSON.stringify(this.settings, null, 2), 'utf-8');
    } catch (e: any) {
      console.error('SettingsStore save error:', e.message);
    }
  }

  /** Deep merge helper */
  private deepMerge<T extends object>(target: T, source: Partial<T>): T {
    const result = { ...target } as any;
    for (const key in source) {
      if (Object.prototype.hasOwnProperty.call(source, key)) {
        const srcVal = (source as any)[key];
        const tgtVal = result[key];
        if (srcVal && typeof srcVal === 'object' && !Array.isArray(srcVal) && tgtVal && typeof tgtVal === 'object' && !Array.isArray(tgtVal)) {
          result[key] = this.deepMerge(tgtVal, srcVal);
        } else {
          result[key] = srcVal;
        }
      }
    }
    return result as T;
  }

  /** Get a shallow copy of all settings */
  getAll(): AgentSettings {
    return { ...this.settings };
  }

  /** Update partial settings */
  async update(partial: Partial<AgentSettings>): Promise<void> {
    this.settings = this.deepMerge(this.settings, partial);
    this.save();
    this.onDidChangeEmitter.fire(partial);
  }

  /** Reset to defaults */
  async reset(): Promise<void> {
    this.settings = { ...DEFAULT_SETTINGS };
    this.save();
    this.onDidChangeEmitter.fire(this.settings);
  }
}
