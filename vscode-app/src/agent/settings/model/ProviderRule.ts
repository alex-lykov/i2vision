/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

// ProviderRule.ts
// Definition of a single rule belonging to a specific LLM provider.

export interface ProviderRule {
  /** Unique identifier for the rule (e.g., "maxTokens") */
  id: string;
  /** Human-readable description shown in the UI */
  description: string;
  /** The provider this rule belongs to (e.g., "ollama", "deepseek") */
  providerId: string;
  /** Default value for the rule */
  defaultValue: unknown;
  /** Optional validation constraints – similar to SettingDescriptor.validation */
  validation?: {
    min?: number;
    max?: number;
    enumValues?: string[];
  };
}
