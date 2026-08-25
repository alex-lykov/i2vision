/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

import type { SettingsState } from '../viewmodel/SettingsViewModel';
import type { ProviderRule } from '../model/ProviderRule';

export type SettingsWebviewMessage =
  | { type: 'settingChanged'; key: string; value: unknown }
  | { type: 'resetGroup'; group: string }
  | { type: 'resetAll' }
  | { type: 'save' }
  | { type: 'export' }
  | { type: 'import'; json: string }
  | { type: 'close' }
  // Provider rule CRUD
  | { type: 'getProviderRules'; providerId: string }
  | { type: 'setProviderRules'; providerId: string; rules: ProviderRule[] }
  | { type: 'addProviderRule'; providerId: string; rule: ProviderRule }
  | { type: 'updateProviderRule'; providerId: string; ruleId: string; rule: ProviderRule }
  | { type: 'deleteProviderRule'; providerId: string; ruleId: string };

export type SettingsHostMessage =
  | { type: 'settingsState'; state: SettingsState }
  | { type: 'settingsSaved' }
  | { type: 'settingsError'; message: string };
