/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

// SettingDescriptor - describes a single configurable setting for MVVM UI

export type SettingType = 'boolean' | 'number' | 'string' | 'enum' | 'range';
export type SettingValidator = (value: any) => string[];

export interface SettingDescriptor {
  /** Unique key used for storage and retrieval */
  key: string;
  /** UI section name (e.g., "Ollama Provider") */
  section: string;
  /** Human readable label */
  label: string;
  /** Optional description shown in UI */
  description?: string;
  /** Data type */
  type: SettingType;
  /** Default value */
  defaultValue: any;
  /** Validation constraints */
  validation?: {
    min?: number;
    max?: number;
    enumValues?: string[];
  };
  /** UI hints */
  ui?: {
    order?: number;
    placeholder?: string;
    group?: string;
  };
  /** Additional properties used by view and validation */
  enumValues?: { value: string; label: string }[];
  min?: number;
  max?: number;
  step?: number;
  validator?: SettingValidator;
  group?: string;
  order?: number;
  visibility?: 'basic' | 'advanced';
  scope?: 'workspace' | 'user';
}
