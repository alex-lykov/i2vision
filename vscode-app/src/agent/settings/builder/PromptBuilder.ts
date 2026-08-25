/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

// PromptBuilder - Assembles system prompts from provider-specific rules
// Replaces the static buildSystemPrompt() in AgentBridge

import type { ProviderRule } from '../model/ProviderRule';

export interface PromptBuilderOptions {
  /** Provider ID (e.g., "ollama", "deepseek", "mistral") */
  providerId: string;
  /** Template variables for interpolation (e.g., { workspaceRoot: "/path/to/project" }) */
  templateVariables: Record<string, string>;
  /** Base system prompt template */
  systemPromptTemplate: string;
  /** Rules to append after the template */
  rules: ProviderRule[];
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
      prompt += '\n\n--- RULES ---';
      for (const rule of rules) {
        prompt += `\n• ${rule.description}: ${String(rule.defaultValue)}`;
      }
    }
    
    return prompt;
  }

  /**
   * Build with default fallback rules (backward compatibility)
   * Used when no provider-specific rules are defined
   */
  static buildWithDefaults(options: PromptBuilderOptions): string {
    const { rules } = options;
    
    // If no rules defined, use hardcoded defaults
    if (rules.length === 0) {
      const defaultRules: ProviderRule[] = [
        {
          id: 'always_use_tools',
          description: 'ALWAYS use tool calls',
          providerId: options.providerId,
          defaultValue: 'Never describe plans without executing'
        },
        {
          id: 'backend_run_command',
          description: 'FOR "run backend" or "run server"',
          providerId: options.providerId,
          defaultValue: 'use run_terminal with gradlew :app:server:run (NOT run_build)'
        },
        {
          id: 'compilation_command',
          description: 'FOR compilation',
          providerId: options.providerId,
          defaultValue: 'use run_build with compileKotlin (source code ONLY, NO tests). NEVER use "build" - it runs ALL tests'
        },
        {
          id: 'apply_edits_limit',
          description: 'apply_edits',
          providerId: options.providerId,
          defaultValue: 'MAX 50 edits per call. For large changes, use write_file instead'
        },
        {
          id: 'build_failure_handling',
          description: 'When build fails',
          providerId: options.providerId,
          defaultValue: 'READ failing files, FIX code, THEN re-run compileKotlin'
        },
        {
          id: 'no_rerun_without_fix',
          description: 'NEVER re-run build without fixing first',
          providerId: options.providerId,
          defaultValue: true
        },
        {
          id: 'server_startup_workflow',
          description: 'SERVER STARTUP WORKFLOW',
          providerId: options.providerId,
          defaultValue: '1. Start server with run_terminal\n2. WAIT 20-30 seconds (Gradle servers take time!)\n3. Check terminal_status\n4. If terminal shows "not running" or BUILD FAILED: run .\\gradlew :app:server:compileKotlin to see errors\n5. Fix errors with apply_edits, then retry'
        },
        {
          id: 'search_tip',
          description: 'SEARCH TIP',
          providerId: options.providerId,
          defaultValue: 'If search_files finds files, READ them immediately. Do NOT search again with different patterns'
        },
        {
          id: 'focus_area',
          description: 'FOCUS',
          providerId: options.providerId,
          defaultValue: 'Fix source files (src/main), NOT test files (src/test), unless user specifically asks about tests'
        },
        {
          id: 'path_format',
          description: 'Paths',
          providerId: options.providerId,
          defaultValue: 'relative to workspace root, use forward slashes (/)'
        }
      ];
      
      return this.build({ ...options, rules: defaultRules });
    }
    
    return this.build(options);
  }
}
