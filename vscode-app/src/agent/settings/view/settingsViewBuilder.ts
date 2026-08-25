/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

import type { SettingDescriptor } from '../model/SettingDescriptor';
import type { SettingsState } from '../viewmodel/SettingsViewModel';

function escapeHtml(value: unknown): string {
  return String(value)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

function renderControl(descriptor: SettingDescriptor, currentValue: unknown): string {
  const value = currentValue ?? descriptor.defaultValue;
  const key = descriptor.key;

  switch (descriptor.type) {
    case 'boolean':
      return `<input type="checkbox" data-setting="${key}" ${value === true ? 'checked' : ''}>`;

    case 'enum':
      return `<select data-setting="${key}">${(descriptor.enumValues ?? []).map((option) => `<option value="${escapeHtml(option.value)}" ${value === option.value ? 'selected' : ''}>${escapeHtml(option.label)}</option>`).join('')}</select>`;

    case 'range':
      return `<input type="range" data-setting="${key}" min="${descriptor.min ?? 0}" max="${descriptor.max ?? 100}" step="${descriptor.step ?? 1}" value="${escapeHtml(value)}"><span class="range-value" data-range-value="${key}">${escapeHtml(value)}</span>`;

    case 'number':
      return `<input type="number" data-setting="${key}" value="${escapeHtml(value)}">`;

    case 'string':
    default:
      return `<input type="text" data-setting="${key}" value="${escapeHtml(value)}">`;
  }
}

function renderSection(section: { id: string; title: string; settings: SettingDescriptor[] }, values: Record<string, unknown>): string {
  const rows = section.settings.map((descriptor) => {
    const currentValue = values[descriptor.key] ?? descriptor.defaultValue;
    return `<div class="setting-row">
      <div class="setting-label">
        <div>${escapeHtml(descriptor.label)}</div>
        ${descriptor.description ? `<div class="setting-description">${escapeHtml(descriptor.description)}</div>` : ''}
      </div>
      <div class="setting-control">${renderControl(descriptor, currentValue)}</div>
    </div>`;
  }).join('');

  return `<div class="section" data-section="${escapeHtml(section.id)}">
    <h2>${escapeHtml(section.title)}</h2>
    ${rows}
  </div>`;
}

export function buildSettingsView(state: SettingsState): string {
  const sectionsHtml = state.schema.sections.map((section) => renderSection(section, state.values)).join('');

  return `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>i2-Vision Agent Settings</title>
  <style>
    /* ===== COMMON WEBVIEW STYLES ===== */
    body { font-family: var(--vscode-font-family); color: var(--vscode-foreground); background: var(--vscode-editor-background); padding: 0; margin: 0; line-height: 1.6; }
    h1, h2, h3 { color: var(--vscode-foreground); font-weight: 600; margin-top: 24px; margin-bottom: 12px; }
    h2 { font-size: 1.3em; }
    a { color: var(--vscode-textLink-foreground); text-decoration: none; }
    a:hover { text-decoration: underline; }
    input[type="text"], input[type="number"], select { width: 100%; padding: 6px 10px; background: var(--vscode-input-background); color: var(--vscode-input-foreground); border: 1px solid var(--vscode-input-border); border-radius: 4px; font-size: 13px; }
    input[type="checkbox"] { width: 16px; height: 16px; accent-color: var(--vscode-checkbox-background); }
    input[type="range"] { width: 100%; height: 4px; background: var(--vscode-input-background); border-radius: 2px; }
    input[type="range"]::-webkit-slider-thumb { -webkit-appearance: none; width: 16px; height: 16px; background: var(--vscode-button-background); border-radius: 50%; cursor: pointer; }
    button { padding: 6px 16px; border: none; border-radius: 4px; cursor: pointer; font-size: 13px; font-weight: 500; }
    button.primary { background: var(--vscode-button-background); color: var(--vscode-button-foreground); }
    button.primary:hover { background: var(--vscode-button-hoverBackground); }
    button.secondary { background: var(--vscode-button-secondaryBackground); color: var(--vscode-button-secondaryForeground); }
    button.secondary:hover { background: var(--vscode-button-secondaryHoverBackground); }
    .text-muted { color: var(--vscode-descriptionForeground); font-size: 0.9em; }
    .flex { display: flex; }
    .items-center { align-items: center; }
    .justify-between { justify-content: space-between; }
    .gap-2 { gap: 8px; }
    
    /* ===== SETTINGS-SPECIFIC STYLES ===== */
    .settings-sidebar { display: flex; flex-direction: column; height: 100vh; }
    .settings-header { display: flex; justify-content: space-between; align-items: center; padding: 10px 16px; border-bottom: 1px solid var(--vscode-editorWidget-border); background: var(--vscode-sideBar-background); }
    .settings-title { font-weight: 600; font-size: 14px; }
    .settings-close-btn { background: none; border: none; cursor: pointer; color: var(--vscode-foreground); font-size: 16px; padding: 4px 8px; }
    .settings-close-btn:hover { background: var(--vscode-button-secondaryBackground); }
    .settings-content { flex: 1; overflow-y: auto; padding: 16px; }
    .section { margin-bottom: 24px; padding: 16px; background: var(--vscode-editor-background); border: 1px solid var(--vscode-widget-border); border-radius: 6px; }
    .section h2 { font-size: 1.2em; margin: 0 0 12px 0; padding-bottom: 8px; border-bottom: 1px solid var(--vscode-widget-border); }
    .setting-row { display: flex; justify-content: space-between; align-items: center; padding: 10px 0; border-bottom: 1px solid var(--vscode-editorWidget-border); }
    .setting-row:last-child { border-bottom: none; }
    .setting-label { flex: 1; padding-right: 16px; }
    .setting-description { font-size: 0.85em; color: var(--vscode-descriptionForeground); margin-top: 4px; }
    .setting-control { flex: 0 0 220px; display: flex; align-items: center; gap: 8px; }
    .range-value { font-size: 0.8em; color: var(--vscode-descriptionForeground); min-width: 40px; text-align: right; font-family: var(--vscode-editor-font-family); }
    .button-row { display: flex; gap: 8px; justify-content: flex-end; padding: 16px; border-top: 1px solid var(--vscode-editorWidget-border); background: var(--vscode-sideBar-background); }
  </style>
</head>
<body>
  <div class="settings-sidebar">
    <div class="settings-header">
      <span class="settings-title">&#9881; i2-Vision Agent Settings</span>
      <button class="settings-close-btn" id="close" title="Close">&#10005;</button>
    </div>
    <div class="settings-content">${sectionsHtml}</div>
    <div class="button-row">
      <button id="save" class="primary">Save</button>
      <button id="export" class="secondary">Export</button>
      <button id="import" class="secondary">Import</button>
      <button id="reset-all" class="secondary">Reset All</button>
    </div>
  </div>
  <script>
    const vscode = acquireVsCodeApi();

    document.addEventListener('change', (event) => {
      const target = event.target;
      if (!(target instanceof HTMLElement) || !target.dataset.setting) return;
      const key = target.dataset.setting;
      let value;
      if (target instanceof HTMLInputElement && target.type === 'checkbox') value = target.checked;
      else if (target instanceof HTMLInputElement && target.type === 'range') value = Number(target.value);
      else if (target instanceof HTMLInputElement && target.type === 'number') value = Number(target.value);
      else value = target.value;
      vscode.postMessage({ type: 'settingChanged', key, value });
      const rangeValue = document.querySelector('[data-range-value="' + key + '"]');
      if (rangeValue) rangeValue.textContent = value;
    });

    document.getElementById('save').addEventListener('click', () => vscode.postMessage({ type: 'save' }));
    document.getElementById('export').addEventListener('click', () => vscode.postMessage({ type: 'export' }));
    document.getElementById('import').addEventListener('click', () => {
      const json = window.prompt('Paste settings JSON:');
      if (json) vscode.postMessage({ type: 'import', json });
    });
    document.getElementById('reset-all').addEventListener('click', () => vscode.postMessage({ type: 'resetAll' }));
    document.getElementById('close').addEventListener('click', () => vscode.postMessage({ type: 'close' }));

    window.addEventListener('message', (event) => {
      const message = event.data;
      if (message.type === 'settingsState') {
        const state = message.state;
        for (const [key, value] of Object.entries(state.values)) {
          const control = document.querySelector('[data-setting="' + key + '"]');
          if (!control) continue;
          if (control instanceof HTMLInputElement && control.type === 'checkbox') control.checked = value === true;
          else if (control instanceof HTMLInputElement || control instanceof HTMLSelectElement) control.value = String(value);
          const rangeValue = document.querySelector('[data-range-value="' + key + '"]');
          if (rangeValue) rangeValue.textContent = value;
        }
      }
    });
  </script>
</body>
</html>`;
}
