/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

/**
 * ProviderPromptBuilder - Builds provider-specific system prompts
 * 
 * Loads prompt configurations from YAML files and builds complete system prompts.
 * Config files are located in: resources/provider-prompts/{providerId}.yaml
 */

import type { ProviderRule } from '../agent/settings/model/ProviderRule';
import type { ProviderProfile } from './ProviderProfile';
import { getProviderProfile } from './ProviderProfile';
import { ProviderPromptConfigLoader, type ProviderPromptFileConfig } from './ProviderPromptConfigLoader';

export interface ProviderPromptBuilderOptions {
  /** Provider ID (e.g., "ollama", "deepseek", "3dllm") */
  providerId: string;
  /** Template variables for interpolation (e.g., { currentFile: "/path/to/file", task: "..." }) */
  templateVariables: Record<string, string>;
  /** Custom rules from settings store (optional) */
  customRules?: ProviderRule[];
  /** Provider profile for tool/prompt policies (optional, auto-resolved if not provided) */
  providerProfile?: ProviderProfile;
  /** Prompt config loader (optional, for testing) */
  configLoader?: ProviderPromptConfigLoader;
}

export class ProviderPromptBuilder {
  private static configCache: Map<string, ProviderPromptFileConfig> = new Map();

  /**
   * Build a complete system prompt for the specified provider
   */
  static async build(options: ProviderPromptBuilderOptions): Promise<string> {
    const {
      providerId,
      templateVariables,
      customRules = [],
      providerProfile,
      configLoader
    } = options;

    // Resolve provider profile if not provided
    const profile = providerProfile ?? getProviderProfile(providerId);

    // Load provider prompt config from YAML file
    let config: ProviderPromptFileConfig | undefined;
    
    if (configLoader) {
      config = await configLoader.loadProviderConfig(providerId);
    } else {
      // Use cached config or load from default location
      config = this.configCache.get(providerId);
      if (!config) {
        // Fallback: create loader with extension path
        // This should be initialized by extension.ts
        throw new Error('ProviderPromptBuilder requires configLoader or pre-loaded config');
      }
    }

    // Start with base template
    let prompt = config.baseTemplate;

    // Interpolate template variables
    for (const [key, value] of Object.entries(templateVariables)) {
      prompt = prompt.replace(new RegExp(`\\$\\{${key}\\}`, 'g'), value);
    }

    // Append tool calling format
    prompt += '\n\n' + config.toolCallingFormat;

    // Append behavioral rules
    if (config.behavioralRules.length > 0 || customRules.length > 0) {
      prompt += '\n\n## BEHAVIORAL RULES\n';
      for (const rule of config.behavioralRules) {
        prompt += `- ${rule}\n`;
      }
      for (const rule of customRules) {
        // Safely convert rule properties to strings
        const desc = typeof rule.description === 'string' ? rule.description : JSON.stringify(rule.description);
        const val = rule.defaultValue !== undefined && rule.defaultValue !== null 
          ? (typeof rule.defaultValue === 'string' ? rule.defaultValue : JSON.stringify(rule.defaultValue))
          : '';
        prompt += `- ${desc}: ${val}\n`;
      }
    }

    // Append constraints
    if (config.constraints.length > 0) {
      prompt += '\n\n## CONSTRAINTS\n';
      for (const constraint of config.constraints) {
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
   * Build with default fallback (synchronous, uses cached config)
   */
  static buildWithDefaults(options: ProviderPromptBuilderOptions): string {
    // Note: This synchronous version requires config to be pre-loaded
    // Use async build() for automatic loading
    throw new Error('Use async build() method instead of buildWithDefaults()');
  }

  /**
   * Pre-load provider configs (call during extension activation)
   */
  static async preloadConfigs(configLoader: ProviderPromptConfigLoader): Promise<void> {
    const providers = configLoader.getAvailableProviders();
    for (const providerId of providers) {
      const config = await configLoader.loadProviderConfig(providerId);
      this.configCache.set(providerId, config);
    }
  }

  /**
   * Clear cached configs (useful for testing or reloading)
   */
  static clearCache(): void {
    this.configCache.clear();
  }

  /**
   * Set cached config directly (for testing)
   */
  static setConfig(providerId: string, config: ProviderPromptFileConfig): void {
    this.configCache.set(providerId, config);
  }
}
