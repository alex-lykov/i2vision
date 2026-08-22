/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

import type { SettingDescriptor } from '../model/SettingDescriptor';
import type { SettingsSchema, SettingsSection } from '../model/SettingsSchema';

/**
 * Registry for built-in and user-defined setting descriptors.
 */
export class SettingsRegistry {
  private readonly descriptors = new Map<string, SettingDescriptor>();

  register(descriptor: SettingDescriptor): void {
    if (!descriptor.key) {
      throw new Error('Setting descriptor must have a key');
    }
    this.descriptors.set(descriptor.key, descriptor);
  }

  registerMany(descriptors: SettingDescriptor[]): void {
    for (const descriptor of descriptors) {
      this.register(descriptor);
    }
  }

  unregister(key: string): void {
    this.descriptors.delete(key);
  }

  get(key: string): SettingDescriptor | undefined {
    return this.descriptors.get(key);
  }

  getSchema(): SettingsSchema {
    const sections = new Map<string, SettingsSection>();

    for (const descriptor of this.descriptors.values()) {
      const section = sections.get(descriptor.group) ?? {
        id: descriptor.group,
        title: descriptor.group,
        order: Number.MAX_SAFE_INTEGER,
        settings: []
      };

      section.settings.push(descriptor);
      sections.set(descriptor.group, section);
    }

    const orderedSections = Array.from(sections.values())
      .map((section) => ({
        ...section,
        settings: section.settings.sort((a, b) => a.order - b.order)
      }))
      .sort((a, b) => a.order - b.order);

    return { sections: orderedSections };
  }

  getAll(): SettingDescriptor[] {
    return Array.from(this.descriptors.values());
  }

  clear(): void {
    this.descriptors.clear();
  }
}
