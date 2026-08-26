/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

// PromptBuilder - Assembles system prompts from provider-specific rules
// Replaces the static buildSystemPrompt() in AgentBridge

import type { ProviderRule } from '../model/ProviderRule';
import type { LLMProviderCapabilities } from '../../../types/provider-types';

export interface PromptBuilderOptions {
  /** Provider ID (e.g., "ollama", "deepseek", "mistral") */
  providerId: string;
  /** Template variables for interpolation (e.g., { workspaceRoot: "/path/to/project" }) */
  templateVariables: Record<string, string>;
  /** Base system prompt template */
  systemPromptTemplate: string;
  /** Rules to append after the template */
  rules: ProviderRule[];
  /** Provider capabilities for conditional formatting */
  providerCapabilities?: LLMProviderCapabilities;
}

export class PromptBuilder {
  /**
   * Build a system prompt from provider rules and template variables
   */
  static build(options: PromptBuilderOptions): string {
    const { systemPromptTemplate, templateVariables, rules } = options;
    
    // Step 1: Interpolate template variables
    let prompt = systemPromptTemplate;
    for (const [key, value] of Object.entries(templateVariables)) {
      prompt = prompt.replace(new RegExp(`\\$\\{${key}\\}`, 'g'), value);
    }
    
    // Step 2: Append rules section
    if (rules.length > 0) {
      prompt += '\n\n## PROVIDER-SPECIFIC RULES\n';
      for (const rule of rules) {
        prompt += `- ${rule.description}: ${String(rule.defaultValue)}\n`;
      }
    }
    
    return prompt;
  }

  /**
   * Build with default fallback rules (backward compatibility)
   * Used when no provider-specific rules are defined
   */
  static buildWithDefaults(options: PromptBuilderOptions): string {
    const { rules, providerCapabilities } = options;
    
    // Step 1: Start with the base system prompt template from config
    let prompt = options.systemPromptTemplate;
    
    // Step 2: Interpolate template variables
    for (const [key, value] of Object.entries(options.templateVariables)) {
      prompt = prompt.replace(new RegExp(`\\$\\{${key}\\}`, 'g'), value);
    }
    
    // Step 3: Append provider-specific rules from settings (if any)
    if (rules.length > 0) {
      prompt += '\n\n## PROVIDER-SPECIFIC RULES\n';
      for (const rule of rules) {
        prompt += `- ${rule.description}: ${String(rule.defaultValue)}\n`;
      }
    }
    
    // Step 4: Append default tool protocol for providers WITHOUT native tool calls
    // (e.g., 3D LLM needs text-based JSON format, not OpenAI-style tool_calls)
    if (providerCapabilities && !providerCapabilities.nativeToolCalls) {
      prompt += '\n\n## TOOL CALLING FORMAT (STRICT)\n';
      prompt += 'You must call tools using raw JSON on a single line:\n';
      prompt += '{"name":"tool_name","arguments":{"param":"value"}}\n';
      prompt += 'Do NOT use markdown code blocks, XML tags, or "Calling:" text format.\n';
      prompt += 'Do NOT include raw newline characters inside JSON string values; escape them as \\n.\n';
      prompt += 'Output ONLY the JSON tool call line. Nothing else. No explanations.\n';
    }
    
    return prompt;
  }
}
