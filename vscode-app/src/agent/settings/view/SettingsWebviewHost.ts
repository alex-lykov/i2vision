/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

import * as vscode from 'vscode';
import { SettingsViewModel } from '../viewmodel/SettingsViewModel';
import { buildSettingsView } from './settingsViewBuilder';
import type { ProviderRule } from '../model/ProviderRule';

interface WebviewToHostMessage {
  type:
    | 'settingChanged'
    | 'save'
    | 'export'
    | 'import'
    | 'resetAll'
    | 'close'
    // Provider rule CRUD
    | 'getProviderRules'
    | 'setProviderRules'
    | 'addProviderRule'
    | 'updateProviderRule'
    | 'deleteProviderRule';
  key?: string;
  value?: unknown;
  json?: string;
  // Provider rule payloads
  providerId?: string;
  rules?: ProviderRule[];
  rule?: ProviderRule;
  ruleId?: string;
}

export class SettingsWebviewHost {
  private static currentPanel: SettingsWebviewHost | undefined;
  private static readonly viewType = 'i2visionSettings';

  private readonly panel: vscode.WebviewPanel;
  private readonly viewModel: SettingsViewModel;
  private readonly disposables: vscode.Disposable[] = [];

  private constructor(context: vscode.ExtensionContext) {
    this.viewModel = new SettingsViewModel(context);

    this.panel = vscode.window.createWebviewPanel(
      SettingsWebviewHost.viewType,
      'i2-Vision Agent Settings',
      vscode.ViewColumn.One,
      {
        enableScripts: true,
        retainContextWhenHidden: true,
        localResourceRoots: [
          vscode.Uri.file(context.extensionPath)
        ]
      }
    );

    this.panel.onDidDispose(() => this.dispose(), null, this.disposables);

    this.disposables.push(
      this.viewModel.onDidChange((state) => {
        this.panel.webview.postMessage({ type: 'settingsState', state });
      })
    );

    this.panel.webview.onDidReceiveMessage(
      (message: WebviewToHostMessage) => this.handleMessage(message),
      null,
      this.disposables
    );

    this.updateWebview();
  }

  public static show(context: vscode.ExtensionContext): void {
    if (SettingsWebviewHost.currentPanel) {
      SettingsWebviewHost.currentPanel.panel.reveal(vscode.ViewColumn.One);
      return;
    }

    SettingsWebviewHost.currentPanel = new SettingsWebviewHost(context);
  }

  private updateWebview(): void {
    this.panel.webview.html = buildSettingsView(this.viewModel.getState());
  }

  private async handleMessage(message: WebviewToHostMessage): Promise<void> {
    switch (message.type) {
      case 'settingChanged':
        if (typeof message.key === 'string') {
          this.viewModel.setValue(message.key, message.value);
        }
        break;

      case 'save': {
        const saved = await this.viewModel.save();
        if (saved) {
          vscode.window.showInformationMessage('i2-Vision settings saved successfully');
        }
        break;
      }

      case 'export':
        await vscode.env.clipboard.writeText(this.viewModel.exportJson());
        vscode.window.showInformationMessage('Settings copied to clipboard');
        break;

      case 'import':
        if (typeof message.json === 'string') {
          try {
            this.viewModel.importJson(message.json);
          } catch {
            vscode.window.showErrorMessage('Invalid settings JSON');
          }
        }
        break;

      case 'resetAll':
        this.viewModel.resetAll();
        break;

      case 'close':
        this.dispose();
        break;

      // Provider rule CRUD handling
      case 'getProviderRules': {
        const rules = this.viewModel.getProviderRules(message.providerId ?? '');
        this.panel.webview.postMessage({ type: 'providerRules', providerId: message.providerId, rules });
        break;
      }
      case 'setProviderRules': {
        await this.viewModel.setProviderRules(message.providerId ?? '', message.rules ?? []);
        this.panel.webview.postMessage({ type: 'providerRulesUpdated', providerId: message.providerId });
        break;
      }
      case 'addProviderRule': {
        const existing = this.viewModel.getProviderRules(message.providerId ?? '') ?? [];
        const updated = [...existing, message.rule!];
        await this.viewModel.setProviderRules(message.providerId ?? '', updated);
        this.panel.webview.postMessage({ type: 'providerRuleAdded', providerId: message.providerId, rule: message.rule });
        break;
      }
      case 'updateProviderRule': {
        const existing = this.viewModel.getProviderRules(message.providerId ?? '') ?? [];
        const updated = existing.map(r => r.id === message.ruleId ? message.rule! : r);
        await this.viewModel.setProviderRules(message.providerId ?? '', updated);
        this.panel.webview.postMessage({ type: 'providerRuleUpdated', providerId: message.providerId, rule: message.rule });
        break;
      }
      case 'deleteProviderRule': {
        const existing = this.viewModel.getProviderRules(message.providerId ?? '') ?? [];
        const updated = existing.filter(r => r.id !== message.ruleId);
        await this.viewModel.setProviderRules(message.providerId ?? '', updated);
        this.panel.webview.postMessage({ type: 'providerRuleDeleted', providerId: message.providerId, ruleId: message.ruleId });
        break;
      }
    }
  }

  public dispose(): void {
    SettingsWebviewHost.currentPanel = undefined;
    this.panel.dispose();

    while (this.disposables.length) {
      const disposable = this.disposables.pop();
      if (disposable) {
        disposable.dispose();
      }
    }
  }
}
