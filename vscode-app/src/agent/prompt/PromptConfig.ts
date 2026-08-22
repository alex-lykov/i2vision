/**
 * PromptConfig – optional configuration for PromptBuilder.
 *
 * Allows callers to override the list of PromptPart objects used for assembly.
 * Extensible in the future for other per‑model or per‑session options.
 */
import { PromptPart } from "./PromptPart";

export interface PromptConfig {
  /**
   * Explicit list of PromptPart objects to use. If omitted the defaultPromptParts
   * from PromptAssembler are used.
   */
  parts?: PromptPart[];
}
