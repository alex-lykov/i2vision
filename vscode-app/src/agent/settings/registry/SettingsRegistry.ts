/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

// SettingsRegistry - aggregates provider descriptors and UI sections

import {SettingDescriptor} from '../model/SettingDescriptor';
import {BUILTIN_SETTING_DESCRIPTORS} from './builtinSettings';

import {SettingsSchema, SettingsSection} from '../model/SettingsSchema';

export class SettingsRegistry {
  private descriptors: SettingDescriptor[] = [];

  constructor() {
    // Load built-in setting descriptors
    this.descriptors.push(...BUILTIN_SETTING_DESCRIPTORS);
    // TODO: load custom descriptors from workspace config
  }

  /** Register additional descriptors */
  registerMany(descriptors: SettingDescriptor[]): void {
    this.descriptors.push(...descriptors);
  }

  /** Get all descriptors */
  getDescriptors(): SettingDescriptor[] {
    return this.descriptors;
  }

  /** Build a schema grouping descriptors by their section */
  getSchema(): SettingsSchema {
    const sectionsMap: Map<string, SettingsSection> = new Map();
    for (const desc of this.descriptors) {
      const sectionId = desc.section.toLowerCase().replace(/\s+/g, '-');
      let section = sectionsMap.get(sectionId);
      if (!section) {
        section = {
          id: sectionId,
          title: desc.section,
          order: 0,
          settings: []
        };
        sectionsMap.set(sectionId, section);
      }
      section.settings.push(desc);
    }
    const sections = Array.from(sectionsMap.values()).sort((a, b) => a.title.localeCompare(b.title));
    return { sections };
  }
}
