/**
 * CustomToolPlugin - Plugin system for JavaScript/TypeScript custom tools
 * 
 * Allows users to create custom tools with full programmatic control,
 * beyond simple command execution.
 * 
 * Usage:
 * 1. Create a .ts or .js file in .vision-ai/tools/
 * 2. Export a CustomToolPlugin definition
 * 3. Tool is automatically loaded and registered
 */

import * as fs from 'fs';
import * as path from 'path';
import * as vm from 'vm';
import { ToolDefinition, ToolContext, ToolResult, ToolCategory } from './';

/**
 * Custom tool plugin definition
 */
export interface CustomToolPlugin {
  /** Unique tool name */
  name: string;
  
  /** Tool description shown to LLM */
  description: string;
  
  /** Tool category for UI organization */
  category: ToolCategory;
  
  /** Whether tool is read-only (no side effects) */
  isReadOnly: boolean;
  
  /** Whether tool requires user confirmation */
  requiresConfirmation?: boolean;
  
  /** Confirmation message if requiresConfirmation is true */
  confirmationMessage?: string;
  
  /** JSON schema for tool parameters */
  parameters: {
    type: 'object';
    properties: Record<string, {
      type: string;
      description?: string;
      enum?: string[];
    }>;
    required?: string[];
  };
  
  /** Tool execution function */
  execute: (args: Record<string, any>, context: ToolContext) => Promise<ToolResult>;
  
  /** Optional: timeout in milliseconds */
  timeoutMs?: number;
  
  /** Optional: which VSLFC layers can use this tool */
  enabledPerLayer?: string[];
}

/**
 * Sandbox context for safe tool execution
 */
export interface SandboxContext {
  console: typeof console;
  setTimeout: typeof setTimeout;
  clearTimeout: typeof clearTimeout;
  Buffer: typeof Buffer;
  __toolContext: ToolContext;
}

/**
 * CustomToolPluginLoader - Loads and registers custom tool plugins
 */
export class CustomToolPluginLoader {
  private toolsDir: string;
  private outputChannel?: any;
  private loadedPlugins: Map<string, CustomToolPlugin> = new Map();

  constructor(workspaceRoot: string, outputChannel?: any) {
    this.toolsDir = path.join(workspaceRoot, '.vision-ai', 'tools');
    this.outputChannel = outputChannel;
  }

  /**
   * Load all custom tool plugins from .vision-ai/tools/
   */
  async loadPlugins(): Promise<CustomToolPlugin[]> {
    this.log(`Loading custom tool plugins from: ${this.toolsDir}`);

    if (!fs.existsSync(this.toolsDir)) {
      this.log('Custom tools directory does not exist, skipping');
      return [];
    }

    const plugins: CustomToolPlugin[] = [];
    const files = fs.readdirSync(this.toolsDir);

    for (const file of files) {
      if (file.endsWith('.ts') || file.endsWith('.js')) {
        if (file.endsWith('.d.ts')) continue; // Skip TypeScript declaration files

        try {
          const plugin = await this.loadPlugin(file);
          if (plugin) {
            plugins.push(plugin);
            this.loadedPlugins.set(plugin.name, plugin);
          }
        } catch (error: any) {
          this.log(`Error loading plugin ${file}: ${error.message}`);
        }
      }
    }

    this.log(`Loaded ${plugins.length} custom tool plugin(s)`);
    return plugins;
  }

  /**
   * Load a single plugin file
   */
  private async loadPlugin(filename: string): Promise<CustomToolPlugin | null> {
    const filePath = path.join(this.toolsDir, filename);
    this.log(`Loading plugin: ${filename}`);

    try {
      // Read the file content
      const content = fs.readFileSync(filePath, 'utf-8');

      // For TypeScript files, we need to compile first
      // For now, we'll use a simple approach - in production, use ts-node or esbuild
      if (filename.endsWith('.ts')) {
        return this.loadTypeScriptPlugin(filePath, content);
      } else {
        return this.loadJavaScriptPlugin(filePath, content);
      }
    } catch (error: any) {
      this.log(`Failed to load plugin ${filename}: ${error.message}`);
      return null;
    }
  }

  /**
   * Load a TypeScript plugin (requires compilation)
   */
  private loadTypeScriptPlugin(filePath: string, content: string): CustomToolPlugin | null {
    // Note: In a real implementation, you would:
    // 1. Use ts-node or esbuild to compile TypeScript
    // 2. Execute the compiled JavaScript
    // 3. Extract the exported plugin definition
    
    // For now, we'll log a message that TypeScript support requires additional setup
    this.log(`TypeScript plugin detected: ${filePath}`);
    this.log('Note: TypeScript plugins require ts-node or esbuild. Please compile to JavaScript first.');
    
    return null;
  }

  /**
   * Load a JavaScript plugin using vm module for sandboxing
   */
  private loadJavaScriptPlugin(filePath: string, content: string): CustomToolPlugin | null {
    try {
      // Create a sandboxed context
      const sandbox: SandboxContext = {
        console,
        setTimeout,
        clearTimeout,
        Buffer,
        __toolContext: {} as ToolContext // Placeholder, will be set at execution time
      };

      // Create VM context
      const context = vm.createContext(sandbox);

      // Wrap the plugin code to export the plugin definition
      const wrappedCode = `
        (function() {
          const module = { exports: {} };
          const exports = module.exports;
          
          ${content}
          
          return module.exports.default || module.exports;
        })()
      `;

      // Execute the code in the sandbox
      const plugin = vm.runInContext(wrappedCode, context, {
        filename: filePath,
        timeout: 5000 // 5 second timeout for plugin loading
      }) as CustomToolPlugin;

      // Validate the plugin
      if (!this.validatePlugin(plugin, filePath)) {
        return null;
      }

      this.log(`Successfully loaded plugin: ${plugin.name}`);
      return plugin;
    } catch (error: any) {
      this.log(`Error executing plugin ${filePath}: ${error.message}`);
      return null;
    }
  }

  /**
   * Validate a plugin definition
   */
  private validatePlugin(plugin: any, filePath: string): boolean {
    const required = ['name', 'description', 'category', 'isReadOnly', 'parameters', 'execute'];
    
    for (const field of required) {
      if (!plugin || !(field in plugin)) {
        this.log(`Plugin ${filePath} missing required field: ${field}`);
        return false;
      }
    }

    if (typeof plugin.name !== 'string' || plugin.name.length === 0) {
      this.log(`Plugin ${filePath} has invalid name`);
      return false;
    }

    if (typeof plugin.execute !== 'function') {
      this.log(`Plugin ${filePath} execute must be a function`);
      return false;
    }

    const validCategories = ['file', 'git', 'build', 'terminal', 'edit'];
    if (!validCategories.includes(plugin.category)) {
      this.log(`Plugin ${filePath} has invalid category: ${plugin.category}`);
      return false;
    }

    return true;
  }

  /**
   * Convert plugin to ToolDefinition for registry
   */
  convertToToolDefinition(plugin: CustomToolPlugin): ToolDefinition {
    return {
      name: plugin.name,
      description: plugin.description,
      category: plugin.category,
      isReadOnly: plugin.isReadOnly,
      requiresConfirmation: plugin.requiresConfirmation,
      confirmationMessage: plugin.confirmationMessage,
      timeoutMs: plugin.timeoutMs,
      enabledPerLayer: plugin.enabledPerLayer as any,
      parameters: plugin.parameters,
      handler: async (args, context) => {
        try {
          // Create sandbox context with real tool context
          const sandbox: SandboxContext = {
            console,
            setTimeout,
            clearTimeout,
            Buffer,
            __toolContext: context
          };

          // Execute plugin with timeout
          const timeout = plugin.timeoutMs || 30000;
          const result = await Promise.race([
            plugin.execute(args, context),
            new Promise<ToolResult>((_, reject) => 
              setTimeout(() => reject(new Error('Tool execution timeout')), timeout)
            )
          ]);

          return result;
        } catch (error: any) {
          return {
            result: '',
            error: error.message
          };
        }
      }
    };
  }

  /**
   * Get a loaded plugin by name
   */
  getPlugin(name: string): CustomToolPlugin | undefined {
    return this.loadedPlugins.get(name);
  }

  /**
   * Reload all plugins
   */
  async reloadPlugins(): Promise<CustomToolPlugin[]> {
    this.loadedPlugins.clear();
    return this.loadPlugins();
  }

  /**
   * Log a message
   */
  private log(message: string): void {
    const timestamp = new Date().toLocaleTimeString();
    const formatted = `[${timestamp}] [CustomToolPluginLoader] ${message}`;
    if (this.outputChannel) {
      this.outputChannel.appendLine(formatted);
    }
    console.log(formatted);
  }
}
