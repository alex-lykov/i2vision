import { LLMTool } from '../../cliIntegration';
import { ToolDefinition, ToolResult, ToolContext, ToolCall, VslfcLayer } from './ToolTypes';

/**
 * Registry for managing all available tools
 * 
 * Provides declarative tool registration and execution,
 * replacing the large switch statement in AgentBridge.
 */
export class ToolRegistry {
  private tools: Map<string, ToolDefinition> = new Map();
  private disabledTools: Set<string> = new Set();

  /**
   * Register a single tool
   */
  register(tool: ToolDefinition): void {
    if (this.tools.has(tool.name)) {
      throw new Error(`Tool '${tool.name}' is already registered`);
    }
    this.tools.set(tool.name, tool);
  }

  /**
   * Register multiple tools at once
   */
  registerAll(tools: ToolDefinition[]): void {
    for (const tool of tools) {
      this.register(tool);
    }
  }

  /**
   * Disable a tool without removing it from registry
   */
  disableTool(toolName: string): void {
    this.disabledTools.add(toolName);
  }

  /**
   * Enable a previously disabled tool
   */
  enableTool(toolName: string): void {
    this.disabledTools.delete(toolName);
  }

  /**
   * Check if a tool is disabled
   */
  isToolDisabled(toolName: string): boolean {
    return this.disabledTools.has(toolName);
  }

  /**
   * Get LLM tool definitions for all registered tools
   * Optionally filtered by VSLFC layer
   */
  getLLMTools(layer?: VslfcLayer): LLMTool[] {
    return Array.from(this.tools.values())
      .filter(t => !this.disabledTools.has(t.name))
      .filter(t => !layer || !t.enabledPerLayer || t.enabledPerLayer.includes(layer))
      .map(t => {
        const params = t.parameters as any;
        return {
          type: 'function',
          function: {
            name: t.name,
            description: t.description,
            parameters: {
              type: params.type || 'object',
              properties: params.properties || {},
              required: params.required || []
            }
          }
        } as LLMTool;
      });
  }

  /**
   * Get LLM tool definitions filtered by tool names
   */
  getLLMToolsByName(names: string[]): LLMTool[] {
    return Array.from(this.tools.values())
      .filter(t => names.includes(t.name))
      .map(t => {
        const params = t.parameters as any;
        return {
          type: 'function',
          function: {
            name: t.name,
            description: t.description,
            parameters: {
              type: params.type || 'object',
              properties: params.properties || {},
              required: params.required || []
            }
          }
        } as LLMTool;
      });
  }

  /**
   * Get read-only tools (non-destructive operations)
   */
  getReadOnlyTools(): LLMTool[] {
    return Array.from(this.tools.values())
      .filter(t => t.isReadOnly)
      .map(t => {
        const params = t.parameters as any;
        return {
          type: 'function',
          function: {
            name: t.name,
            description: t.description,
            parameters: {
              type: params.type || 'object',
              properties: params.properties || {},
              required: params.required || []
            }
          }
        } as LLMTool;
      });
  }

  /**
   * Execute a tool by name
   */
  async execute(name: string, args: Record<string, any>, context: ToolContext): Promise<ToolResult> {
    const tool = this.tools.get(name);
    if (!tool) {
      return { 
        result: '', 
        error: `Unknown tool: ${name}. Available tools: ${this.getToolNames().join(', ')}` 
      };
    }

    const startTime = Date.now();
    try {
      const result = await tool.handler(args, context);
      result.durationMs = Date.now() - startTime;
      return result;
    } catch (e: any) {
      return { 
        result: '', 
        error: e.message, 
        durationMs: Date.now() - startTime 
      };
    }
  }

  /**
   * Get all registered tool names
   */
  getToolNames(): string[] {
    return Array.from(this.tools.keys());
  }

  /**
   * Check if a tool is registered
   */
  hasTool(name: string): boolean {
    return this.tools.has(name);
  }

  /**
   * Get a tool definition by name
   */
  getTool(name: string): ToolDefinition | undefined {
    return this.tools.get(name);
  }

  /**
   * Get all tools
   */
  getAllTools(): ToolDefinition[] {
    return Array.from(this.tools.values());
  }

  /**
   * Get tools by category
   */
  getToolsByCategory(category: string): ToolDefinition[] {
    return Array.from(this.tools.values()).filter(t => t.category === category);
  }

  /**
   * Clear all registered tools (useful for testing)
   */
  clear(): void {
    this.tools.clear();
  }
}
