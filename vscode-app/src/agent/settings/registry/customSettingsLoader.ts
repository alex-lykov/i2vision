/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

import * as fs from 'fs';
import * as path from 'path';
import type { SettingDescriptor, SettingType } from '../model/SettingDescriptor';

interface CustomSettingsFile {
  sections?: Array<{
    id?: string;
    title?: string;
    settings?: Array<{
      key: string;
      type: SettingType;
      defaultValue: unknown;
      label: string;
      description?: string;
      enumValues?: Array<{ value: string; label: string }>;
      min?: number;
      max?: number;
      step?: number;
      visibility?: 'basic' | 'advanced';
    }>;
  }>;
}

/**
 * Loads user-defined settings from workspace configuration files.
 */
export class CustomSettingsLoader {
  private static readonly FILE_NAMES = ['settings.json', 'custom-settings.json'];

  load(workspaceRoot?: string): SettingDescriptor[] {
    if (!workspaceRoot) {
      return [];
    }

    const basePath = path.join(workspaceRoot, '.vscode', 'i2vision');
    const descriptors: SettingDescriptor[] = [];

    for (const fileName of CustomSettingsLoader.FILE_NAMES) {
      const filePath = path.join(basePath, fileName);
      if (!fs.existsSync(filePath)) {
        continue;
      }

      try {
        const content = fs.readFileSync(filePath, 'utf-8');
        const parsed = JSON.parse(content) as CustomSettingsFile;

        for (const section of parsed.sections ?? []) {
          const group = section.title ?? section.id ?? 'Custom';

          for (const setting of section.settings ?? []) {
            descriptors.push({
              key: setting.key,
              type: setting.type,
              defaultValue: setting.defaultValue,
              label: setting.label,
              description: setting.description,
              group,
              order: descriptors.length,
              enumValues: setting.enumValues,
              min: setting.min,
              max: setting.max,
              step: setting.step,
              visibility: setting.visibility ?? 'advanced',
              scope: 'workspace'
            });
          }
        }
      } catch (error) {
        console.error(`[CustomSettingsLoader] Failed to load ${filePath}`, error);
      }
    }

    return descriptors;
  }
}
