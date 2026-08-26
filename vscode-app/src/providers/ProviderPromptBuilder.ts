/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

/**
 * ProviderPromptBuilder - Builds provider-specific system prompts
 * 
 * Combines:
 *   1. Provider-specific base template from VSCode settings (or defaults)
 *   2. Custom rules from settings store
 */

import type { ProviderRule } from '../agent/settings/model/ProviderRule';
import { DEFAULT_PROVIDER_PROMPTS, ProviderPromptSettingsData } from '../settings/ProviderPromptSettings';
import type { ProviderProfile } from './ProviderProfile';
import { getProviderProfile } from './ProviderProfile';

export interface ProviderPromptBuilderOptions {
  /** Provider ID (e.g., "ollama", "deepseek", "3dllm") */
  providerId: string;
  /** Template variables for interpolation (e.g., { currentFile: "/path/to/file", task: "..." }) */
  templateVariables: Record<string, string>;
  /** Custom rules from settings store (optional) */
  customRules?: ProviderRule[];
  /** Provider profile for tool/prompt policies (optional, auto-resolved if not provided) */
  providerProfile?: ProviderProfile;
  /** Provider prompt settings (optional, uses defaults if not provided) */
  providerPromptSettings?: ProviderPromptSettingsData;
}

export class ProviderPromptBuilder {
  /**
   * Build a complete system prompt for the specified provider
   */
  static build(options: ProviderPromptBuilderOptions): string {
    const {
      providerId,
      templateVariables,
      customRules = [],
      providerProfile,
      providerPromptSettings
    } = options;

    // Resolve provider profile if not provided
    const profile = providerProfile ?? getProviderProfile(providerId);

    // Get provider prompt settings (or use defaults)
    const settings = providerPromptSettings ?? DEFAULT_PROVIDER_PROMPTS[providerId] ?? DEFAULT_PROVIDER_PROMPTS['ollama'];

    // Start with base template
    let prompt = settings.baseTemplate;

    // Interpolate template variables
    for (const [key, value] of Object.entries(templateVariables)) {
      prompt = prompt.replace(new RegExp(`\\$\\{${key}\\}`, 'g'), value);
    }

    // Append tool calling format
    prompt += '\n\n' + settings.toolCallingFormat;

    // Append behavioral rules
    if (settings.behavioralRules.length > 0 || customRules.length > 0) {
      prompt += '\n\n## BEHAVIORAL RULES\n';
      for (const rule of settings.behavioralRules) {
        prompt += `- ${rule}\n`;
      }
      for (const rule of customRules) {
        prompt += `- ${rule.description}: ${String(rule.defaultValue)}\n`;
      }
    }

    // Append constraints
    if (settings.constraints.length > 0) {
      prompt += '\n\n## CONSTRAINTS\n';
      for (const constraint of settings.constraints) {
        prompt += `- ${constraint}\n`;
      }
    }

    // Append provider-specific tool rules from ProviderProfile
    const toolRules = profile.prompt?.toolRules ?? profile.toolPolicy.promptRules;
    if (toolRules) {
      prompt += '\n\n## TOOL RULES (PROVIDER-SPECIFIC)\n';
      prompt += toolRules;
    }

    return prompt;
  }

  /**
   * Build with default fallback rules (backward compatibility)
   * Used when no custom rules are defined in settings
   */
  static buildWithDefaults(options: ProviderPromptBuilderOptions): string {
    return this.build(options);
  }
}
