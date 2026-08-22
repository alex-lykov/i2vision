/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

import type { SettingDescriptor, SettingValidator } from '../model/SettingDescriptor';
import type { SettingValue } from '../model/SettingValue';

export class SettingItemViewModel {
  readonly descriptor: SettingDescriptor;
  readonly value: SettingValue;

  constructor(descriptor: SettingDescriptor, initialValue: unknown) {
    this.descriptor = descriptor;
    this.value = {
      key: descriptor.key,
      value: initialValue,
      isDirty: false,
      validationErrors: [],
      isValid: true
    };
  }

  update(newValue: unknown): void {
    this.value.value = newValue;
    this.value.isDirty = true;
    this.value.validationErrors = this.validate(newValue);
    this.value.isValid = this.value.validationErrors.length === 0;
  }

  reset(defaultValue?: unknown): void {
    this.value.value = defaultValue ?? this.descriptor.defaultValue;
    this.value.isDirty = false;
    this.value.validationErrors = [];
    this.value.isValid = true;
  }

  private validate(value: unknown): string[] {
    const validators: SettingValidator[] = [];

    if (this.descriptor.validator) {
      validators.push(this.descriptor.validator);
    }

    if (this.descriptor.type === 'boolean') {
      validators.push((v) => (typeof v === 'boolean' ? [] : ['Must be a boolean']));
    } else if (this.descriptor.type === 'string') {
      validators.push((v) => (typeof v === 'string' ? [] : ['Must be a string']));
    } else if (this.descriptor.type === 'number' || this.descriptor.type === 'range') {
      validators.push((v) => (typeof v === 'number' && Number.isFinite(v) ? [] : ['Must be a number']));
    } else if (this.descriptor.type === 'enum') {
      const allowed = this.descriptor.enumValues?.map((option) => option.value) ?? [];
      validators.push((v) => (allowed.includes(v as string) ? [] : [`Must be one of: ${allowed.join(', ')}`]));
    }

    if (this.descriptor.min !== undefined || this.descriptor.max !== undefined) {
      const min = this.descriptor.min;
      const max = this.descriptor.max;
      validators.push((v) => {
        if (typeof v !== 'number' || !Number.isFinite(v)) {
          return ['Must be a number'];
        }
        const errors: string[] = [];
        if (min !== undefined && v < min) {
          errors.push(`Must be at least ${min}`);
        }
        if (max !== undefined && v > max) {
          errors.push(`Must be at most ${max}`);
        }
        return errors;
      });
    }

    const errors: string[] = [];
    for (const validator of validators) {
      errors.push(...validator(value));
    }
    return errors;
  }
}
