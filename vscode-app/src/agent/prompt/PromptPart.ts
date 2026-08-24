/**
 * Shared types for layered prompt construction.
 */

export type PromptPlacement = 'system' | 'first-user' | 'context' | 'tool-result';

export interface PromptContext {
  userPrompt: string;
  projectContext?: string;
  taskContext?: string;
  compressedContext?: string;
  dynamicLearning?: string;
  toolProtocol?: string;
  toolRules?: string;
  coreRules?: string;
  useSystemPrompt: boolean;
  isFirstMessage: boolean;
  toolSetChanged: boolean;
  domainChanged: boolean;
  hasError: boolean;
}

export interface PromptPart {
  id: string;
  priority: number;
  placement: PromptPlacement;
  include(ctx: PromptContext): boolean;
  render(ctx: PromptContext): string;
}

export interface AssembledPrompt {
  system?: string;
  firstUser?: string;
  context?: string;
  toolResult?: string;
  includedParts: string[];
  skippedParts: string[];
}
