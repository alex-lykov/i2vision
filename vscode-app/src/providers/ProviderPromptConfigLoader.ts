/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

/**
 * Provider Prompt Config Loader
 * 
 * Loads provider-specific prompt configurations from YAML files.
 * Files are located in: resources/provider-prompts/{providerId}.yaml
 * 
 * Users can edit these files directly or via VSCode settings UI.
 * On first load, missing files are created from defaults.
 */

import * as vscode from 'vscode';
import * as fs from 'fs';
import * as path from 'path';
import * as yaml from 'js-yaml';

export interface ProviderPromptFileConfig {
  providerId: string;
  baseTemplate: string;
  toolCallingFormat: string;
  behavioralRules: string[];
  constraints: string[];
}

export class ProviderPromptConfigLoader {
  private extensionPath: string;
  private configDir: string;
  private cache: Map<string, ProviderPromptFileConfig> = new Map();

  constructor(extensionPath: string) {
    this.extensionPath = extensionPath;
    this.configDir = path.join(extensionPath, 'resources', 'provider-prompts');
  }

  /**
   * Initialize config directory and create missing default files
   */
  async initialize(): Promise<void> {
    // Create directory if it doesn't exist
    if (!fs.existsSync(this.configDir)) {
      fs.mkdirSync(this.configDir, { recursive: true });
    }

    // Create default config files for providers that don't have one
    const providers = this.getAvailableProviders();
    for (const providerId of providers) {
      const configPath = this.getConfigFilePath(providerId);
      
      if (!fs.existsSync(configPath)) {
        await this.resetToDefaults(providerId);
      }
    }
  }

  /**
   * Load prompt configuration for a specific provider
   */
  async loadProviderConfig(providerId: string): Promise<ProviderPromptFileConfig> {
    // Check cache first
    const cached = this.cache.get(providerId);
    if (cached) {
      return cached;
    }

    // Normalize provider ID (handle both '3d-llm' and '3dllm' formats)
    const normalizedProviderId = this.normalizeProviderId(providerId);
    const configPath = this.getConfigFilePath(normalizedProviderId);
    
    // If file doesn't exist, create it from defaults
    if (!fs.existsSync(configPath)) {
      await this.resetToDefaults(normalizedProviderId);
    }

    // Load from YAML file
    try {
      const content = await fs.promises.readFile(configPath, 'utf-8');
      const config = yaml.load(content) as ProviderPromptFileConfig;
      
      if (!config || !config.baseTemplate) {
        throw new Error('Invalid config structure');
      }
      
      this.cache.set(providerId, config);
      return config;
    } catch (error: any) {
      console.error(`Failed to load provider prompt config for ${providerId}:`, error.message);
      // Fallback to defaults
      await this.resetToDefaults(normalizedProviderId);
      return await this.loadProviderConfig(providerId);
    }
  }

  /**
   * Normalize provider ID to handle variations
   */
  private normalizeProviderId(providerId: string): string {
    // Handle common variations
    return providerId
      .toLowerCase()
      .replace(/-/g, ''); // Remove dashes: '3d-llm' -> '3dllm'
  }

  /**
   * Save prompt configuration for a specific provider
   */
  async saveProviderConfig(providerId: string, config: ProviderPromptFileConfig): Promise<void> {
    const configPath = this.getConfigFilePath(providerId);
    
    // Ensure config directory exists
    if (!fs.existsSync(this.configDir)) {
      fs.mkdirSync(this.configDir, { recursive: true });
    }

    const content = yaml.dump(config, {
      lineWidth: -1, // Don't wrap long lines
      quotingType: '"',
      forceQuotes: false
    });
    
    await fs.promises.writeFile(configPath, content, 'utf-8');
    
    // Invalidate cache
    this.cache.delete(providerId);
  }

  /**
   * Get the path to a provider's config file
   */
  getConfigFilePath(providerId: string): string {
    return path.join(this.configDir, `${providerId}.yaml`);
  }

  /**
   * Check if a provider config file exists
   */
  configExists(providerId: string): boolean {
    const configPath = this.getConfigFilePath(providerId);
    return fs.existsSync(configPath);
  }

  /**
   * Reset a provider config to defaults
   */
  async resetToDefaults(providerId: string): Promise<void> {
    const defaultConfig = this.getDefaultConfig(providerId);
    if (defaultConfig) {
      await this.saveProviderConfig(providerId, defaultConfig);
    }
  }

  /**
   * Get default configuration for a provider
   */
  private getDefaultConfig(providerId: string): ProviderPromptFileConfig | null {
    switch (providerId) {
      case '3dllm':
        return {
          providerId: '3dllm',
          baseTemplate: `You are an AI assistant for the VSLFC (Vision-Structure-Logic-Flow-Code) architecture.
You help analyze, design, and implement software systems following formal contract specifications.

## UNIVERSAL RULES (STRICT - NEVER IGNORE)

### NEVER
- **Use relative paths** or \`working_dir\` → **Absolute paths only**
- **Commit** without being asked
- **Run tests** unless explicitly asked
- **Explore beyond 10 calls** without making an edit
- **Describe plans without executing** - Always use tool calls

### ALWAYS
- Use tool calls for all filesystem, terminal, build, or edit operations
- Output ONLY raw JSON for tool calls - no explanations, no markdown
- Fix source files (src/main), NOT test files (src/test), unless user asks

## WORKFLOW

### Reading Files
- Use \`read_file\` (with absolute path) to read file contents
- For file structure overview: use \`get_file_context\`
- For finding code: use \`search_files\` with pattern matching
- For directory listings: use \`list_directory\` with \`recursive:true\`

### Editing Files
1. Read with \`read_file\` to see current content
2. Modify with \`write_file\` or \`apply_edits\`
3. Build (\`run_build\`), fix errors if any, rebuild

### Build Verification
- Use \`run_build\` with the compile command (e.g., \`.\\gradlew compileKotlin\`)
- If the same build command fails 3 times with the same error, STOP and report

### Pre-Existing Errors
- If build fails on files you didn't edit, report "BUILD FAILED with pre-existing errors only"

### Restart Servers
1. Ask user to close the terminal
2. Wait for confirmation
3. Start server, output "BUILD PASSED. Server running."

When given a task:
1. Use i2vision_get_context to understand the current code architecture
2. Read relevant files before making changes
3. Make focused, minimal edits
4. Verify your changes make sense in the broader architecture

Available context:
- Project: \${projectName}
- Current file: \${currentFile}
- Task: \${task}`,

          toolCallingFormat: `## TOOL CALLING FORMAT (STRICT)

You must call tools using raw JSON on a single line:
{"name":"tool_name","arguments":{"param":"value"}}

CRITICAL RULES:
- Do NOT use markdown code blocks (no \`\`\`json)
- Do NOT use XML tags (no <file_action>, <invoke>, etc.)
- Do NOT use "Calling:" text format
- Do NOT include raw newline characters inside JSON string values; escape them as \\n
- Output ONLY the JSON tool call line. Nothing else. No explanations.`,

          behavioralRules: [
            'ALWAYS use tool calls. Never describe plans without executing',
            'FOR "run backend" or "run server": use run_terminal with gradlew :app:server:run (NOT run_build)',
            'FOR compilation: use run_build with compileKotlin (source code ONLY, NO tests). NEVER use "build"',
            'apply_edits: MAX 50 edits per call. For large changes, use write_file instead',
            'When build fails: READ failing files, FIX code, THEN re-run compileKotlin',
            'NEVER re-run build without fixing first',
            'SEARCH TIP: If search_files finds files, READ them immediately',
            'Paths: relative to workspace root, use forward slashes (/)'
          ],

          constraints: [
            'Maximum 10 exploration tool calls before making an edit',
            'No test file modifications unless explicitly requested',
            'Absolute paths required for all file operations'
          ]
        };

      case 'ollama':
        return {
          providerId: 'ollama',
          baseTemplate: `You are an AI assistant for the VSLFC (Vision-Structure-Logic-Flow-Code) architecture.
You help analyze, design, and implement software systems following formal contract specifications.

## UNIVERSAL RULES (STRICT - NEVER IGNORE)

### NEVER
- **Use relative paths** → **Absolute paths only**
- **Commit** without being asked
- **Run tests** unless explicitly asked
- **Explore beyond 10 calls** without making an edit

### ALWAYS
- Use tool calls for all operations
- Fix source files (src/main), NOT test files (src/test), unless user asks`,

          toolCallingFormat: `## TOOL CALLING FORMAT

Call tools using the tool-call protocol described in system instructions.
- Use only tools that are currently available
- When a tool is required, return exactly one JSON tool call
- Format: {"name":"tool_name","arguments":{"param":"value"}}`,

          behavioralRules: [
            'ALWAYS use tool calls. Never describe plans without executing',
            'FOR compilation: use run_build with compileKotlin (source code ONLY, NO tests)',
            'apply_edits: MAX 50 edits per call',
            'When build fails: READ failing files, FIX code, THEN re-run build',
            'Paths: relative to workspace root, use forward slashes (/)'
          ],

          constraints: [
            'Maximum 10 exploration tool calls before making an edit',
            'No test file modifications unless explicitly requested'
          ]
        };

      case 'deepseek':
        return {
          providerId: 'deepseek',
          baseTemplate: `You are an AI assistant for the VSLFC (Vision-Structure-Logic-Flow-Code) architecture.
You help analyze, design, and implement software systems following formal contract specifications.

## UNIVERSAL RULES (STRICT - NEVER IGNORE)

### NEVER
- **Use relative paths** → **Absolute paths only**
- **Commit** without being asked
- **Run tests** unless explicitly asked
- **Explore beyond 10 calls** without making an edit

### ALWAYS
- Use tool calls for all operations
- Fix source files (src/main), NOT test files (src/test), unless user asks`,

          toolCallingFormat: `## TOOL CALLING FORMAT

Call tools using JSON format:
{"name":"tool_name","arguments":{"param":"value"}}

Rules:
- Use only available tools
- One tool call per response when action is needed`,

          behavioralRules: [
            'ALWAYS use tool calls. Never describe plans without executing',
            'FOR compilation: use run_build with compileKotlin (NO tests)',
            'apply_edits: MAX 50 edits per call',
            'When build fails: READ failing files, FIX code, THEN re-run build',
            'Paths: use forward slashes (/)'
          ],

          constraints: [
            'Maximum 10 exploration calls before action',
            'No test modifications unless requested'
          ]
        };

      case 'mistral':
        return {
          providerId: 'mistral',
          baseTemplate: `You are an AI assistant for the VSLFC (Vision-Structure-Logic-Flow-Code) architecture.
You help analyze, design, and implement software systems following formal contract specifications.

## UNIVERSAL RULES (STRICT - NEVER IGNORE)

### NEVER
- **Use relative paths** → **Absolute paths only**
- **Commit** without being asked
- **Run tests** unless explicitly asked

### ALWAYS
- Use tool calls for all operations
- Fix source files (src/main), NOT test files`,

          toolCallingFormat: `## TOOL CALLING FORMAT

Call tools using JSON:
{"name":"tool_name","arguments":{"param":"value"}}

- Use available tools only
- One tool call when action needed`,

          behavioralRules: [
            'ALWAYS use tool calls',
            'FOR compilation: use compileKotlin (NO tests)',
            'apply_edits: MAX 50 edits',
            'Paths: forward slashes (/)'
          ],

          constraints: [
            'Maximum 10 exploration calls',
            'No test modifications'
          ]
        };

      default:
        return null;
    }
  }

  /**
   * Get all available provider IDs
   */
  getAvailableProviders(): string[] {
    return ['3dllm', 'ollama', 'deepseek', 'mistral'];
  }

  /**
   * Clear the cache (useful for testing or reloading configs)
   */
  clearCache(): void {
    this.cache.clear();
  }
}
