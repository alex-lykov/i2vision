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
  /** Whether tools are being sent in the request (for conditional prompt generation) */
  hasNativeTools?: boolean;
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
      configLoader,
      hasNativeTools = false
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

    // Append tool calling format - use conditional version if hasNativeTools
    prompt += '\n\n' + this.buildToolCallingFormat(config.toolCallingFormat, hasNativeTools);

    // Append behavioral rules
    if (config.behavioralRules.length > 0 || customRules.length > 0) {
      prompt += '\n\n## BEHAVIORAL RULES\n';
      
      // Debug: Log what we're about to process
      console.log('[ProviderPromptBuilder] Processing behavioralRules:', {
        count: config.behavioralRules.length,
        types: config.behavioralRules.map(r => typeof r),
        sample: config.behavioralRules.slice(0, 3).map(r => typeof r === 'string' ? r.substring(0, 50) : r)
      });
      
      // Debug: check if behavioralRules contains objects instead of strings
      const badRules = config.behavioralRules.filter(r => typeof r !== 'string');
      if (badRules.length > 0) {
        console.error('[ProviderPromptBuilder] YAML parsing issue: behavioralRules contains', badRules.length, 'non-string values:');
        badRules.forEach((r, i) => console.error(`  Rule ${i}:`, typeof r, JSON.stringify(r)));
      }
      
      for (const rule of config.behavioralRules) {
        // Ensure rule is a string (handle any unexpected object types from YAML parsing)
        let ruleText: string;
        if (typeof rule === 'string') {
          ruleText = rule;
        } else if (rule && typeof rule === 'object' && 'toString' in rule) {
          // Try to get a meaningful string from the object
          ruleText = String(rule);
          // If it's still [object Object], try to extract useful info
          if (ruleText === '[object Object]' && typeof (rule as any).rule === 'string') {
            ruleText = (rule as any).rule;
          }
        } else {
          ruleText = String(rule);
        }
        prompt += `- ${ruleText}\n`;
      }
      for (const rule of customRules) {
        // Debug: log what we're receiving
        if (!rule || typeof rule !== 'object' || !('description' in rule)) {
          console.warn('[ProviderPromptBuilder] Invalid rule object:', JSON.stringify(rule, null, 2));
        }
        
        // Safely convert rule properties to strings with proper null/undefined checks
        const desc = (rule.description && typeof rule.description === 'string') 
          ? rule.description 
          : (rule.id || String(rule.description || 'Unknown rule'));
        const val = rule.defaultValue !== undefined && rule.defaultValue !== null 
          ? (typeof rule.defaultValue === 'string' ? rule.defaultValue : String(rule.defaultValue))
          : '';
        prompt += `- ${desc}${val ? ': ' + val : ''}\n`;
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

  /**
   * Build conditional tool calling format based on whether native tools are available
   * 
   * When hasNativeTools=true: Emphasize using the tool_calls field
   * When hasNativeTools=false: Emphasize JSON-only text format
   */
  private static buildToolCallingFormat(baseFormat: string, hasNativeTools: boolean): string {
    if (hasNativeTools) {
      // Native tools available - instruct model to use tool_calls field
      return `## TOOL CALLING FORMAT (NATIVE TOOLS AVAILABLE)

You have access to native tool calling via the \`tool_calls\` field.

✅ CORRECT: Use the tool_calls field in your response
❌ FORBIDDEN: Do NOT output JSON in your message content

When you need to call a tool:
1. Set your response to use the tool_calls field
2. Do NOT include the tool call JSON in your message text
3. Do NOT use XML tags or markdown formatting

Note: The tool_calls field is handled automatically by the system.`;
    } else {
      // No native tools - use text-based JSON format
      return baseFormat;
    }
  }
}
