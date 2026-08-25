/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

import type { SettingDescriptor } from './SettingDescriptor';

export interface SettingsSection {
  id: string;
  title: string;
  order: number;
  settings: SettingDescriptor[];
}

import type { ProviderRules } from './ProviderRules';

export interface SettingsSchema {
  sections: SettingsSection[];
  /** Provider‑specific rule collections */
  providerRules?: ProviderRules;
}
