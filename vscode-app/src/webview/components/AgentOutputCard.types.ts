/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

/**
 * Agent Output Card Types
 * 
 * Defines the structure for unified agent output display with configurable formatting,
 * collapsing, and display options.
 */

/**
 * Provider badge color coding
 */
export type ProviderType = 'ollama' | 'deepseek' | 'openai' | 'other';

/**
 * Agent response status
 */
export type AgentStatus = 'success' | 'error' | 'partial' | 'stopped';

/**
 * Display theme options
 */
export type OutputTheme = 'system' | 'light' | 'dark' | 'compact';

/**
 * Font size options
 */
export type FontSize = 'small' | 'medium' | 'large';

/**
 * Tool call representation
 */
export interface ToolCallData {
  toolName: string;
  args: Record<string, any>;
  result?: string;
  durationMs?: number;
  success?: boolean;
  error?: string;
  toolCallId?: string; // OpenAI-compatible ID for linking results to tool calls
}

/**
 * Flat agent output used by AgentOutputCard's simplified render path.
 * NOTE: This is distinct from the richer AgentOutputCard/CardContent model; it is
 * the shape consumed by renderAgentOutputCard().
 */
export interface AgentOutput {
  toolCalls: Array<{ name: string; args: Record<string, any> }>;
  reasoning?: string;
  response?: string;
  diff?: DiffCardData;
  applyActionId?: string;
  isFinal: boolean;
}

/**
 * Minimal diff representation for a single file in the agent output card.
 */
export interface DiffCardData {
  filePath: string;
  diff: string;
}

/**
 * Code block with syntax highlighting info
 */
export interface CodeBlock {
  language: string;
  code: string;
  filePath?: string;
  startLine?: number;
}

/**
 * Output card header information
 */
export interface CardHeader {
  provider: ProviderType;
  providerName: string;
  model: string;
  timestamp: number;
  durationMs: number;
  iterations: number;
  status: AgentStatus;
}

/**
 * Output card main content
 */
export interface CardContent {
  text: string;
  toolCalls: ToolCallData[];
  buildOutput?: string;
  codeBlocks?: CodeBlock[];
  error?: string;
  /** DeepSeek/OpenAI reasoning/thinking stream content — shown in collapsible section */
  thinkingStream?: string;
}

/**
 * Output card footer actions
 */
export interface CardFooter {
  tokensUsed?: number;
  confidence?: number;
  actions: FooterAction[];
}

/**
 * Footer action button
 */
export interface FooterAction {
  id: string;
  label: string;
  icon: string;
  enabled: boolean;
}

/**
 * Display configuration from user settings
 */
export interface DisplayConfig {
  collapsed: boolean;
  showReasoning: boolean;
  showToolDetails: boolean;
  showTokenCount: boolean;
  showConfidence: boolean;
  theme: OutputTheme;
  fontSize: FontSize;
  autoCollapseAfter: number;
  maxPreviewLines: number;
  codeHighlight: boolean;
}

/**
 * Complete output card data structure
 */
export interface AgentOutputCard {
  header: CardHeader;
  content: CardContent;
  footer: CardFooter;
  display: DisplayConfig;
}

/**
 * User settings from VSCode configuration
 */
export interface OutputSettings {
  'i2vision.output.showReasoning': boolean;
  'i2vision.output.autoCollapse': number;
  'i2vision.output.maxPreviewLines': number;
  'i2vision.output.theme': OutputTheme;
  'i2vision.output.fontSize': FontSize;
  'i2vision.output.showTokenCount': boolean;
  'i2vision.output.showConfidence': boolean;
  'i2vision.output.showToolDetails': boolean;
  'i2vision.output.codeHighlight': boolean;
}

/**
 * Default display configuration
 */
export const DEFAULT_DISPLAY_CONFIG: DisplayConfig = {
  collapsed: false,
  showReasoning: false,
  showToolDetails: true,
  showTokenCount: false,
  showConfidence: false,
  theme: 'system',
  fontSize: 'medium',
  autoCollapseAfter: 500,
  maxPreviewLines: 10,
  codeHighlight: true,
};

/**
 * Convert provider ID to display name
 */
export function getProviderDisplayName(provider: ProviderType): string {
  const names: Record<ProviderType, string> = {
    ollama: 'Ollama',
    deepseek: 'DeepSeek',
    openai: 'OpenAI',
    other: 'Unknown',
  };
  return names[provider] || provider;
}

/**
 * Get provider badge color based on type
 */
export function getProviderColor(provider: ProviderType): string {
  const colors: Record<ProviderType, string> = {
    ollama: '#27ae60',      // Green for local
    deepseek: '#3498db',    // Blue for cloud
    openai: '#9b59b6',      // Purple
    other: '#95a5a6',       // Gray
  };
  return colors[provider] || colors.other;
}

/**
 * Get status icon
 */
export function getStatusIcon(status: AgentStatus): string {
  // Return empty placeholder; actual SVG icons are injected by AgentOutputCard.
  // This function is kept for backward compatibility.
  return `<span class="status-icon-placeholder" data-status="${status}"></span>`;
}

/**
 * Format duration for display
 */
export function formatDuration(ms: number): string {
  if (ms < 1000) {
    return `${ms}ms`;
  }
  return `${(ms / 1000).toFixed(1)}s`;
}

/**
 * Check if content should be auto-collapsed
 */
export function shouldAutoCollapse(text: string, config: DisplayConfig): boolean {
  return text.length > config.autoCollapseAfter;
}

/**
 * Get preview text (first N lines)
 */
export function getPreviewText(text: string, maxLines: number): string {
  const lines = text.split('\n');
  if (lines.length <= maxLines) {
    return text;
  }
  return lines.slice(0, maxLines).join('\n') + '\n\n... (expand to show more)';
}
