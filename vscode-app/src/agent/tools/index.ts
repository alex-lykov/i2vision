/**
 * Tool Registry System
 * 
 * Declarative tool management replacing the switch statement approach.
 * 
 * Usage:
 * ```typescript
 * const registry = new ToolRegistry();
 * registry.registerAll(fileTools);
 * registry.registerAll(gitTools);
 * 
 * // Get LLM tools
 * const llmTools = registry.getLLMTools();
 * 
 * // Execute a tool
 * const result = await registry.execute('read_file', { path: 'src/main.kt' }, context);
 * 
 * // Load YAML configuration
 * const configLoader = new ToolConfigLoader(registry);
 * await configLoader.loadConfig(workspaceRoot);
 * ```
 */

export { ToolRegistry } from './ToolRegistry';
export { ToolConfigLoader } from './ToolConfigLoader';
export { CustomToolPluginLoader } from './CustomToolPluginLoader';
export type { ToolYamlConfig, CustomToolConfig, ToolOverrideConfig, LayerToolConfig } from './ToolConfigLoader';
export type { CustomToolPlugin } from './CustomToolPluginLoader';
export * from './ToolTypes';
export * from './builtin';
