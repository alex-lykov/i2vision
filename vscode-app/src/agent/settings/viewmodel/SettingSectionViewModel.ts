/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

import type { SettingsSection } from '../model/SettingsSchema';
import { SettingItemViewModel } from './SettingItemViewModel';

export class SettingSectionViewModel {
  readonly id: string;
  readonly title: string;
  readonly items: SettingItemViewModel[];

  constructor(section: SettingsSection, values: Record<string, unknown>) {
    this.id = section.id;
    this.title = section.title;
    this.items = section.settings.map((descriptor) => {
      const initialValue = values[descriptor.key] ?? descriptor.defaultValue;
      return new SettingItemViewModel(descriptor, initialValue);
    });
  }
}
