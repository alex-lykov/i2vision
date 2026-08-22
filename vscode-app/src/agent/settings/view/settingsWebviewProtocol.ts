/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

import type { SettingsState } from '../viewmodel/SettingsViewModel';

export type SettingsWebviewMessage =
  | { type: 'settingChanged'; key: string; value: unknown }
  | { type: 'resetGroup'; group: string }
  | { type: 'resetAll' }
  | { type: 'save' }
  | { type: 'export' }
  | { type: 'import'; json: string }
  | { type: 'close' };

export type SettingsHostMessage =
  | { type: 'settingsState'; state: SettingsState }
  | { type: 'settingsSaved' }
  | { type: 'settingsError'; message: string };
