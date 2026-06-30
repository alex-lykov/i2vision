import { LLMTool } from '../../cliIntegration';
import { TerminalManager } from '../TerminalManager';

/**
 * Tool categories for organization and filtering
 */
export type ToolCategory = 'file' | 'git' | 'build' | 'terminal' | 'edit';

/**
 * UI metadata for tool categories
 */
export interface CategoryUiConfig {
  icon: string;           // Codicon name (e.g., 'file', 'git-commit', 'terminal')
  color?: string;         // Optional color theme (e.g., 'blue', 'green', 'red')
  displayName: string;    // Human-readable category name
}

/**
 * Category icon mappings
 */
export const CATEGORY_ICONS: Record<ToolCategory, CategoryUiConfig> = {
  file: { icon: 'file', displayName: 'File Operations', color: 'blue' },
  git: { icon: 'git-commit', displayName: 'Git Operations', color: 'purple' },
  build: { icon: 'gear', displayName: 'Build & Compile', color: 'orange' },
  terminal: { icon: 'terminal', displayName: 'Terminal', color: 'green' },
  edit: { icon: 'edit', displayName: 'Edit Operations', color: 'yellow' }
};

/**
 * VSLFC layers for tool availability control
 */
export type VslfcLayer = 'VISION' | 'STRUCTURE' | 'LOGIC' | 'FLOW' | 'CODE';

/**
 * Tool execution result
 */
export interface ToolResult {
  result: string;
  error?: string;
  durationMs?: number;
  requiresConfirmation?: boolean;
  confirmationMessage?: string;
}

/**
 * Context provided to tool handlers
 */
export interface ToolContext {
  workspaceRoot: string;
  resolvePath: (p: string) => string;
  runCommand: (cmd: string, timeout: number, cwd?: string) => Promise<{ stdout: string; stderr: string; exitCode: number | null }>;
  readFile: (path: string) => Promise<string>;
  writeFile: (path: string, content: string) => Promise<void>;
  listFiles: (path: string, recursive: boolean) => Promise<string[]>;
  searchFiles: (pattern: string, path?: string) => Promise<string[]>;
  getFileContext: (path: string) => Promise<any>;
  fileExists: (path: string) => Promise<boolean>;
  terminalManager: TerminalManager;
  vscode: typeof import('vscode');
  log: (msg: string) => void;
  emitProgress: (event: ProgressEvent) => void;
  fileSnapshots: Map<string, string>;
}

/**
 * Progress event for tool execution feedback
 */
export interface ProgressEvent {
  type: string;
  toolCall?: any;
  partialOutput?: string;
  iteration?: number;
}

/**
 * Complete tool definition with handler and metadata
 */
export interface ToolDefinition {
  name: string;
  description: string;
  parameters: Record<string, any>;
  handler: (args: Record<string, any>, context: ToolContext) => Promise<ToolResult>;
  category: ToolCategory;
  isReadOnly: boolean;
  requiresConfirmation?: boolean;
  confirmationMessage?: string;
  enabledPerLayer?: VslfcLayer[];
  timeoutMs?: number;
}

/**
 * Tool call from LLM
 */
export interface ToolCall {
  id: string;
  toolName: string;
  args: Record<string, any>;
}
