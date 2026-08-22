/**
 * PromptBuilder – high‑level helper for constructing a full prompt string.
 *
 * It delegates the heavy‑lifting to `PromptAssembler` but offers a simple
 * public API (`build`) that returns the final prompt ready to be sent to an LLM.
 *
 * The builder can be configured with a `PromptConfig` that overrides the
 * default set of `PromptPart`s.  This makes the prompt assembly configurable
 * without touching the low‑level assembler.
 */
import { PromptContext, AssembledPrompt } from "./PromptPart";
import { assemblePrompt, defaultPromptParts } from "./PromptAssembler";

import { PromptConfig } from "./PromptConfig";

/**
 * PromptBuilder builds a prompt string from a PromptContext.
 *
 * The builder is deliberately thin – it only decides which parts to pass to
 * `assemblePrompt` and then concatenates the resulting buckets into the final
 * prompt.  This keeps the high‑level logic in one place and allows future
 * extensions (e.g., custom templates) without touching the assembler.
 */
export class PromptBuilder {
  private readonly config: PromptConfig;

  constructor(config?: PromptConfig) {
    this.config = config ?? {};
  }

  /**
   * Build the full prompt string for the given context.
   *
   * The returned string contains:
   *   1. System prompt (if any)
   *   2. First‑user message plus any context bucket (joined with a blank line)
   *   3. Tool‑result bucket (if any)
   *
   * The ordering matches the expectations of `LLMAdapter.prepareMessages`.
   */
  public build(context: PromptContext): string {
    const parts = this.config.parts ?? defaultPromptParts;
    const assembled: AssembledPrompt = assemblePrompt(context, parts);

    const sections: string[] = [];

    if (assembled.system) {
      sections.push(assembled.system);
    }

    // Combine firstUser and context buckets – they belong to the same user turn.
    const firstUser = [assembled.firstUser, assembled.context]
      .filter((s): s is string => !!s && s.trim().length > 0)
      .join('\n\n');

    if (firstUser) {
      sections.push(firstUser);
    }

    if (assembled.toolResult) {
      sections.push(assembled.toolResult);
    }

    return sections.join('\n\n');
  }
}
