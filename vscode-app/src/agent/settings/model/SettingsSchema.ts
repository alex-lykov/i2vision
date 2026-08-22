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

export interface SettingsSchema {
  sections: SettingsSection[];
}
