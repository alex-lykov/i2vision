/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

/**
 * Composable validators for setting descriptors.
 */

export function booleanValidator(value: unknown): string[] {
  return typeof value === 'boolean' ? [] : ['Must be a boolean'];
}

export function stringValidator(value: unknown): string[] {
  return typeof value === 'string' ? [] : ['Must be a string'];
}

export function numberValidator(value: unknown): string[] {
  return typeof value === 'number' && Number.isFinite(value) ? [] : ['Must be a number'];
}

export function minMaxValidator(min: number, max: number) {
  return (value: unknown): string[] => {
    if (typeof value !== 'number' || !Number.isFinite(value)) {
      return ['Must be a number'];
    }

    const errors: string[] = [];
    if (value < min) {
      errors.push(`Must be at least ${min}`);
    }
    if (value > max) {
      errors.push(`Must be at most ${max}`);
    }
    return errors;
  };
}

export function enumValidator(enumValues: ReadonlyArray<string | number>) {
  return (value: unknown): string[] => {
    return enumValues.includes(value as string | number)
      ? []
      : [`Must be one of: ${enumValues.join(', ')}`];
  };
}
