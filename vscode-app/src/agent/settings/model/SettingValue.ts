/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

/**
 * Runtime state for a single setting value.
 */

export interface SettingValue {
  key: string;
  value: unknown;
  isDirty: boolean;
  validationErrors: string[];
  isValid: boolean;
}
