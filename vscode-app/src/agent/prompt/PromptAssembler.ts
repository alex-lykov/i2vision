/**
 * Layered prompt assembler.
 *
 * Keeps prompt construction split into small parts with explicit inclusion
 * rules so providers do not resend unchanged rules/context on every request.
 */

import {AssembledPrompt, PromptContext, PromptPart, PromptPlacement} from './PromptPart';

function joinNonEmpty(parts: string[]): string | undefined {
  const filtered = parts.filter(p => p && p.trim().length > 0);
  return filtered.length > 0 ? filtered.join('\n\n') : undefined;
}

export const coreRulesPart: PromptPart = {
  id: 'core-rules',
  priority: 10,
  placement: 'system',
  include(ctx: PromptContext): boolean {
    return !!ctx.coreRules && (ctx.useSystemPrompt || ctx.isFirstMessage);
  },
  render(ctx: PromptContext): string {
    return ctx.coreRules!;
  },
};

export const projectContextPart: PromptPart = {
  id: 'project-context',
  priority: 20,
  placement: 'context',
  include(ctx: PromptContext): boolean {
    return !!ctx.projectContext && (ctx.isFirstMessage || ctx.domainChanged);
  },
  render(ctx: PromptContext): string {
    return ctx.projectContext!;
  },
};

export const taskContextPart: PromptPart = {
  id: 'task-context',
  priority: 30,
  placement: 'context',
  include(ctx: PromptContext): boolean {
    return !!ctx.taskContext && ctx.domainChanged;
  },
  render(ctx: PromptContext): string {
    return ctx.taskContext!;
  },
};

export const dynamicLearningPart: PromptPart = {
  id: 'dynamic-learning',
  priority: 40,
  placement: 'context',
  include(ctx: PromptContext): boolean {
    return !!ctx.dynamicLearning && ctx.hasError;
  },
  render(ctx: PromptContext): string {
    return ctx.dynamicLearning!;
  },
};

export const compressedContextPart: PromptPart = {
  id: 'compressed-context',
  priority: 50,
  placement: 'tool-result',
  include(ctx: PromptContext): boolean {
    return !!ctx.compressedContext;
  },
  render(ctx: PromptContext): string {
    return ctx.compressedContext!;
  },
};

export const toolProtocolPart: PromptPart = {
  id: 'tool-protocol',
  priority: 60,
  placement: 'context',
  include(ctx: PromptContext): boolean {
    return !!ctx.toolProtocol && (ctx.toolSetChanged || ctx.isFirstMessage);
  },
  render(ctx: PromptContext): string {
    return ctx.toolProtocol!;
  },
};

export const toolRulesPart: PromptPart = {
  id: 'tool-rules',
  priority: 55,
  placement: 'system',
  include(ctx: PromptContext): boolean {
    return !!ctx.toolRules && (ctx.useSystemPrompt || ctx.isFirstMessage || ctx.toolSetChanged);
  },
  render(ctx: PromptContext): string {
    return ctx.toolRules!;
  },
};

export const defaultPromptParts: PromptPart[] = [
  coreRulesPart,
  projectContextPart,
  taskContextPart,
  dynamicLearningPart,
  compressedContextPart,
  toolRulesPart,
  toolProtocolPart,
];

function getTarget(part: PromptPart, placement: PromptPlacement): string[] | undefined {
  switch (placement) {
    case 'system':
      return ['system'];
    case 'first-user':
      return ['firstUser'];
    case 'context':
      return ['context'];
    case 'tool-result':
      return ['toolResult'];
    default:
      return undefined;
  }
}

export function assemblePrompt(
  ctx: PromptContext,
  parts: PromptPart[] = defaultPromptParts,
): AssembledPrompt {
  const sorted = [...parts].sort((a, b) => a.priority - b.priority);
  const buckets: Record<string, string[]> = {
    system: [],
    firstUser: [],
    context: [],
    toolResult: [],
  };
  const includedParts: string[] = [];
  const skippedParts: string[] = [];

  for (const part of sorted) {
    if (!part.include(ctx)) {
      skippedParts.push(part.id);
      continue;
    }

    const text = part.render(ctx);
    if (!text || text.trim().length === 0) {
      skippedParts.push(part.id);
      continue;
    }

    const placement: PromptPlacement = part.placement === 'first-user' && ctx.useSystemPrompt
      ? 'first-user'
      : part.placement === 'system' && !ctx.useSystemPrompt
        ? 'first-user'
        : part.placement;

    const bucket = getTarget(part, placement);
    if (!bucket) {
      skippedParts.push(part.id);
      continue;
    }

    for (const key of bucket) {
      buckets[key].push(text);
    }
    includedParts.push(part.id);
  }

  return {
    system: joinNonEmpty(buckets.system),
    firstUser: joinNonEmpty(buckets.firstUser),
    context: joinNonEmpty(buckets.context),
    toolResult: joinNonEmpty(buckets.toolResult),
    includedParts,
    skippedParts,
  };
}
