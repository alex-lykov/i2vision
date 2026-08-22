/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

/**
 * Declarative metadata describing a single user-configurable setting.
 */

export type SettingType = 'boolean' | 'number' | 'string' | 'enum' | 'range';

export interface SettingEnumOption {
  value: string;
  label: string;
}

export type SettingValidator = (value: unknown) => string[];

export interface SettingDescriptor {
  key: string;
  type: SettingType;
  defaultValue: unknown;
  label: string;
  description?: string;
  group: string;
  order: number;
  validator?: SettingValidator;
  enumValues?: ReadonlyArray<SettingEnumOption>;
  min?: number;
  max?: number;
  step?: number;
  scope?: 'workspace' | 'global';
  visibility?: 'basic' | 'advanced';
  tags?: string[];
}
