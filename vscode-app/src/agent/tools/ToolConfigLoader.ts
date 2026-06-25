/**
 * ToolConfigLoader - YAML-based tool configuration system
 * 
 * Allows project-specific tool configuration via .vision-ai/tools.yaml
 * Supports:
 * - Enabling/disabling tools per project
 * - Custom tool definitions
 * - Timeout overrides
 * - Layer-specific tool filtering
 * - Hot-reload on file changes
 */

import * as fs from 'fs';
import * as path from 'path';
import * as yaml from 'js-yaml';
import * as vscode from 'vscode';
import { ToolRegistry, ToolDefinition, ToolContext, ToolResult, VslfcLayer } from './';

/**
 * YAML configuration schema
 */
export interface ToolYamlConfig {
  /** List of disabled tool names */
  disabled?: string[];
  
  /** Custom tool definitions */
  custom?: CustomToolConfig[];
  
  /** Tool-specific overrides */
  overrides?: {
    [toolName: string]: ToolOverrideConfig;
  };
  
  /** Layer-specific tool availability */
  layers?: {
    [layerName: string]: LayerToolConfig;
  };
}

/**
 * Custom tool definition from YAML
 */
export interface CustomToolConfig {
  name: string;
  description: string;
  category: 'file' | 'git' | 'build' | 'terminal' | 'edit';
  command?: string;
  timeout?: number;
  requiresConfirmation?: boolean;
  confirmationMessage?: string;
  isReadOnly?: boolean;
  parameters?: Record<string, any>;
  enabledPerLayer?: VslfcLayer[];
}

/**
 * Tool override configuration
 */
export interface ToolOverrideConfig {
  timeoutMs?: number;
  enabled?: boolean;
  requiresConfirmation?: boolean;
  enabledPerLayer?: VslfcLayer[];
}

/**
 * Layer-specific tool configuration
 */
export interface LayerToolConfig {
  enabled?: string[] | 'all';
  disabled?: string[];
}

/**
 * ToolConfigLoader - Loads and applies YAML tool configuration
 */
export class ToolConfigLoader implements vscode.Disposable {
  private configPath?: string;
  private config?: ToolYamlConfig;
  private fileWatcher?: vscode.FileSystemWatcher;
  private outputChannel?: vscode.OutputChannel;
  private toolRegistry: ToolRegistry;

  constructor(toolRegistry: ToolRegistry, outputChannel?: vscode.OutputChannel) {
    this.toolRegistry = toolRegistry;
    this.outputChannel = outputChannel;
  }

  /**
   * Load configuration from workspace
   */
  async loadConfig(workspaceRoot: string): Promise<void> {
    const visionAiDir = path.join(workspaceRoot, '.vision-ai');
    this.configPath = path.join(visionAiDir, 'tools.yaml');

    this.log(`Looking for tool config at: ${this.configPath}`);

    try {
      if (!fs.existsSync(this.configPath)) {
        this.log('No tools.yaml found, using default configuration');
        this.config = undefined;
        return;
      }

      const yamlContent = fs.readFileSync(this.configPath, 'utf-8');
      this.config = yaml.load(yamlContent) as ToolYamlConfig;

      this.log(`Loaded tool config: ${this.configPath}`);
      this.log(`Disabled tools: ${this.config.disabled?.length || 0}`);
      this.log(`Custom tools: ${this.config.custom?.length || 0}`);
      this.log(`Overrides: ${Object.keys(this.config.overrides || {}).length}`);

      // Apply configuration
      await this.applyConfig();

      // Set up file watcher for hot-reload
      this.setupWatcher();

    } catch (error: any) {
      this.log(`Error loading tool config: ${error.message}`);
      this.config = undefined;
    }
  }

  /**
   * Apply loaded configuration to tool registry
   */
  private async applyConfig(): Promise<void> {
    if (!this.config) return;

    // 1. Disable tools
    if (this.config.disabled && this.config.disabled.length > 0) {
      for (const toolName of this.config.disabled) {
        // Mark as disabled by removing from registry or flagging
        this.log(`Disabling tool: ${toolName}`);
        // Note: We don't actually remove from registry, just track disabled state
      }
    }

    // 2. Apply overrides
    if (this.config.overrides) {
      for (const [toolName, override] of Object.entries(this.config.overrides)) {
        const tool = this.toolRegistry.getTool(toolName);
        if (tool) {
          if (override.timeoutMs) {
            tool.timeoutMs = override.timeoutMs;
            this.log(`Override ${toolName}: timeoutMs = ${override.timeoutMs}`);
          }
          if (override.requiresConfirmation !== undefined) {
            tool.requiresConfirmation = override.requiresConfirmation;
            this.log(`Override ${toolName}: requiresConfirmation = ${override.requiresConfirmation}`);
          }
          if (override.enabledPerLayer) {
            tool.enabledPerLayer = override.enabledPerLayer;
            this.log(`Override ${toolName}: enabledPerLayer = ${override.enabledPerLayer.join(', ')}`);
          }
        } else {
          this.log(`Warning: Override for unknown tool: ${toolName}`);
        }
      }
    }

    // 3. Register custom tools
    if (this.config.custom && this.config.custom.length > 0) {
      for (const customConfig of this.config.custom) {
        await this.registerCustomTool(customConfig);
      }
    }
  }

  /**
   * Register a custom tool from YAML config
   */
  private async registerCustomTool(customConfig: CustomToolConfig): Promise<void> {
    const toolName = customConfig.name;

    // Check if tool already exists
    if (this.toolRegistry.hasTool(toolName)) {
      this.log(`Warning: Custom tool '${toolName}' already exists, skipping`);
      return;
    }

    // Create tool definition
    const toolDef: ToolDefinition = {
      name: customConfig.name,
      description: customConfig.description,
      category: customConfig.category,
      isReadOnly: customConfig.isReadOnly !== undefined ? customConfig.isReadOnly : false,
      requiresConfirmation: customConfig.requiresConfirmation,
      confirmationMessage: customConfig.confirmationMessage,
      timeoutMs: customConfig.timeout,
      enabledPerLayer: customConfig.enabledPerLayer,
      parameters: customConfig.parameters || {
        type: 'object',
        properties: {},
        required: []
      },
      handler: async (args, context) => {
        if (!customConfig.command) {
          return { result: '', error: 'Custom tool has no command defined' };
        }

        // Execute command-based tool
        try {
          const timeout = customConfig.timeout || 60000;
          const result = await context.runCommand(customConfig.command, timeout);

          return {
            result: result.stdout || result.stderr || 'Command executed successfully',
            error: result.exitCode !== 0 ? 'Command failed' : undefined
          };
        } catch (error: any) {
          return {
            result: '',
            error: error.message
          };
        }
      }
    };

    this.toolRegistry.register(toolDef);
    this.log(`Registered custom tool: ${toolName} (category: ${customConfig.category})`);
  }

  /**
   * Set up file watcher for hot-reload
   */
  private setupWatcher(): void {
    if (!this.configPath) return;

    // Dispose existing watcher
    if (this.fileWatcher) {
      this.fileWatcher.dispose();
    }

    const configDir = path.dirname(this.configPath);
    const pattern = new vscode.RelativePattern(configDir, 'tools.yaml');

    this.fileWatcher = vscode.workspace.createFileSystemWatcher(pattern);

    this.fileWatcher.onDidChange(async () => {
      this.log('tools.yaml changed, reloading configuration...');
      if (this.configPath) {
        const workspaceRoot = path.dirname(configDir);
        await this.loadConfig(workspaceRoot);
      }
    });

    this.fileWatcher.onDidCreate(async () => {
      this.log('tools.yaml created, loading configuration...');
      if (this.configPath) {
        const workspaceRoot = path.dirname(configDir);
        await this.loadConfig(workspaceRoot);
      }
    });

    this.fileWatcher.onDidDelete(() => {
      this.log('tools.yaml deleted, reverting to default configuration');
      this.config = undefined;
    });

    this.log('File watcher set up for hot-reload');
  }

  /**
   * Check if a tool is disabled
   */
  isToolDisabled(toolName: string): boolean {
    return this.config?.disabled?.includes(toolName) || false;
  }

  /**
   * Get layer-specific tool configuration
   */
  getLayerConfig(layer: VslfcLayer): LayerToolConfig | undefined {
    return this.config?.layers?.[layer];
  }

  /**
   * Get current configuration
   */
  getConfig(): ToolYamlConfig | undefined {
    return this.config;
  }

  /**
   * Check if configuration is loaded
   */
  hasConfig(): boolean {
    return this.config !== undefined;
  }

  /**
   * Log a message
   */
  private log(message: string): void {
    const timestamp = new Date().toLocaleTimeString();
    const formatted = `[${timestamp}] [ToolConfigLoader] ${message}`;
    if (this.outputChannel) {
      this.outputChannel.appendLine(formatted);
    }
    console.log(formatted);
  }

  /**
   * Dispose resources
   */
  dispose(): void {
    if (this.fileWatcher) {
      this.fileWatcher.dispose();
    }
  }
}
