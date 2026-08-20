/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

/**
 * AgentBridge.LLMAdapter - Encapsulates LLM communication and formatting logic.
 *
 * Extracted from AgentBridge.ts. Owns:
 *   - callLLM()
 *   - formatToolsForSystemPrompt()
 *   - extractReasoning() / extractFinalResponse()
 *   - detectProxyMisbehavior()
 *   - Token estimation and message trimming
 */

import { CLI, LLMMessage, LLMResponse, LLMTool } from '../cliIntegrationRefactored';
import { LLMProviderCapabilities } from '../types/provider-types';
import { Diagnostics } from './AgentBridge.Diagnostics';
import {assemblePrompt} from './prompt/PromptAssembler';
import {PromptContext} from './prompt/PromptPart';

export interface LLMAdapterConfig {
  modelId: string;
  provider: string;
  contextLength: number;
  maxOutputTokens: number;
  temperature: number;
  topP: number;
  thinkingEnabled: boolean;
  searchEnabled: boolean;
  /** Timeout for a single chat-completion (seconds), forwarded to the provider. */
  timeoutSeconds?: number;
  /** Per-conversation session identifier (e.g. 3D LLM proxy sticky-session key). */
  user?: string;
  /** Layered prompt parts. These are sent once / on change, not repeated per turn. */
  systemPromptTemplate?: string;
  systemPromptRules?: { rules: string[] };
  projectContext?: string;
}

export class LLMAdapter {
  private cli: CLI;
  private diag: Diagnostics;

  /** Capabilities cache — updated from outside when provider changes */
  private _lastProviderCapabilities?: LLMProviderCapabilities;

  /** Token tracking baseline for delta estimation */
  private _lastKnownPromptTokens: number = 0;
  private _lastKnownMessageCount: number = 0;
  private _lastKnownMessageChars: number = 0;
  private static readonly PER_MESSAGE_OVERHEAD = 4;

  constructor(cli: CLI, diag: Diagnostics, capabilities?: LLMProviderCapabilities) {
    this.cli = cli;
    this.diag = diag;
    this._lastProviderCapabilities = capabilities;
  }

  // --- Public accessors for AgentBridge to set state after LLM calls ---

  get providerCapabilities(): LLMProviderCapabilities | undefined {
    return this._lastProviderCapabilities;
  }

  set providerCapabilities(caps: LLMProviderCapabilities | undefined) {
    this._lastProviderCapabilities = caps;
  }

  get lastKnownPromptTokens(): number { return this._lastKnownPromptTokens; }
  set lastKnownPromptTokens(v: number) { this._lastKnownPromptTokens = v; }

  get lastKnownMessageCount(): number { return this._lastKnownMessageCount; }
  set lastKnownMessageCount(v: number) { this._lastKnownMessageCount = v; }

  get lastKnownMessageChars(): number { return this._lastKnownMessageChars; }
  set lastKnownMessageChars(v: number) { this._lastKnownMessageChars = v; }

  /** Reset the token tracking baseline (e.g. on fresh conversation) */
  resetTokenBaseline(): void {
    this._lastKnownPromptTokens = 0;
    this._lastKnownMessageCount = 0;
    this._lastKnownMessageChars = 0;
  }

  // --- LLM Call ---

  async callLLM(config: LLMAdapterConfig, messages: LLMMessage[], tools: LLMTool[]): Promise<LLMResponse> {
    const preparedMessages = this.prepareMessages(config, messages, tools);
    const result = await this.cli.callLLM(config.modelId, preparedMessages, {
      temperature: config.temperature,
      top_p: config.topP,
      max_tokens: config.maxOutputTokens,
      thinking_enabled: config.thinkingEnabled,
      search_enabled: config.searchEnabled,
      user: config.user,
      timeoutSeconds: config.timeoutSeconds
    }, tools, false, config.provider);
    if (Symbol.asyncIterator in result) throw new Error('Expected non-streaming response but got streaming generator');
    return result as LLMResponse;
  }

  /**
   * Assemble layered prompt parts once instead of re-sending every rule/tool catalog
   * on every LLM request. Core rules go to system (or first user), context chunks go
   * to the first user message, and tool protocol is sent only when tools are present.
   */
  private prepareMessages(config: LLMAdapterConfig, messages: LLMMessage[], tools: LLMTool[]): LLMMessage[] {
    const coreRules = (config.systemPromptRules?.rules || []).join('\n');
    const ctx: PromptContext = {
      userPrompt: config.modelId,
      projectContext: config.projectContext,
      toolProtocol: this.formatToolsForSystemPrompt(tools),
      coreRules: coreRules || config.systemPromptTemplate,
      useSystemPrompt: true,
      isFirstMessage: messages.length <= 1,
      toolSetChanged: tools.length > 0,
      domainChanged: false,
      hasError: false,
    };
    const assembled = assemblePrompt(ctx);
    const out: LLMMessage[] = [...messages];

    // Only inject if not already present (send once, not every turn)
    const systemText = assembled.system;
    if (systemText && !out.some(m => m.role === 'system' && (m.content as string).includes(systemText))) {
      const existingSystem = out.findIndex(m => m.role === 'system');
      if (existingSystem >= 0) {
        out[existingSystem] = { ...out[existingSystem], content: `${out[existingSystem].content}\n\n${systemText}` };
      } else {
        out.unshift({ role: 'system', content: systemText });
      }
    }

    const firstUserText = assembled.firstUser;
    if (firstUserText && !out.some(m => m.role === 'user' && (m.content as string).includes(firstUserText))) {
      const firstUserIdx = out.findIndex(m => m.role === 'user');
      if (firstUserIdx >= 0) {
        out[firstUserIdx] = { ...out[firstUserIdx], content: `${firstUserText}\n\n${out[firstUserIdx].content}` };
      } else {
        out.push({ role: 'user', content: firstUserText });
      }
    }

    return out;
  }

  // --- Token Estimation ---

  /**
   * Estimate token count for messages using actual LLM-reported prompt tokens
   * as a baseline, plus a delta estimate for new content since the last call.
   */
  estimateTokens(messages: LLMMessage[]): number {
    const baseline = this._lastKnownPromptTokens || 0;
    if (baseline === 0) {
      const msgOverhead = messages.length * LLMAdapter.PER_MESSAGE_OVERHEAD;
      return messages.reduce((sum, msg) => sum + Math.ceil(msg.content.length / 3.5), msgOverhead);
    }

    const currentChars = messages.reduce((sum, msg) => sum + msg.content.length, 0);
    const currentCount = messages.length;
    const newChars = Math.max(0, currentChars - this._lastKnownMessageChars);
    const newMessages = Math.max(0, currentCount - this._lastKnownMessageCount);

    const deltaTokens = Math.ceil(newChars / 3.5) + (newMessages * LLMAdapter.PER_MESSAGE_OVERHEAD);
    return baseline + deltaTokens;
  }

  /**
   * Summarize old conversation messages to reduce token usage.
   */
  async summarizeConversation(messages: LLMMessage[]): Promise<string> {
    if (messages.length === 0) return '';

    const toolCalls = messages
      .filter(m => m.role === 'tool' && typeof m.content === 'string')
      .map(m => {
        const content = m.content as string;
        const fileMatch = content.match(/(?:read|wrote|edited|found)\s+[^\n]+/gi);
        return fileMatch ? fileMatch.slice(0, 3).join('; ') : content.substring(0, 100);
      })
      .filter(Boolean);

    const summary = `Previous conversation covered: ${toolCalls.slice(0, 10).join(' | ')}`;
    this.diag.log(`Conversation summarized: ${toolCalls.length} tool results condensed`);
    return summary;
  }

  /**
   * Trim messages to stay within token budget, summarizing old messages.
   */
  async trimMessagesToBudget(
    contextLength: number,
    messages: LLMMessage[],
    maxTokenPercentage: number = 0.8
  ): Promise<LLMMessage[]> {
    const maxTokens = contextLength * maxTokenPercentage;
    let estimatedTokens = this.estimateTokens(messages);

    if (estimatedTokens <= maxTokens) {
      return messages;
    }

    this.diag.log(`Token warning: ${estimatedTokens} / ${contextLength} (${(estimatedTokens / contextLength * 100).toFixed(1)}%) - trimming conversation`);

    const systemMsg = messages[0];
    const recentMsgs = messages.slice(-8);
    const oldMessages = messages.slice(1, -8);

    if (oldMessages.length === 0) {
      return messages;
    }

    const summary = await this.summarizeConversation(oldMessages);
    const summaryMsg: LLMMessage = { role: 'user', content: `[Previous conversation summary: ${summary}]. Continue from recent messages above.` };

    return [systemMsg, summaryMsg, ...recentMsgs];
  }

  // --- Tool Formatting ---

  /** Format tool definitions for embedding in the system prompt (text-based tool calling) */
  formatToolsForSystemPrompt(tools: LLMTool[]): string {
    let text = '--- AVAILABLE TOOLS ---\n';
    text += 'Call tools using raw JSON on a single line: {"name":"tool_name","arguments":{"param":"value"}}\n';
    text += 'Do not use markdown code blocks or XML tags.\n\n';
    // Enforce strict JSON output: no raw newlines inside string values; escape them as \n.
    text += 'NOTE: Do NOT include raw newline characters inside JSON string values; escape them as \\n.\n';
    text += 'Available tools:\n';

    for (const tool of tools) {
      const fn = tool.function;
      text += `### ${fn.name}\n`;
      text += `${fn.description || 'No description'}\n`;
      if (fn.parameters) {
        const props = fn.parameters.properties || {};
        const req = fn.parameters.required || [];
        text += `Parameters:\n`;
        for (const [key, val] of Object.entries(props)) {
          const desc = (val as any).description || '';
          const required = req.includes(key) ? ' (required)' : '';
          text += `  - ${key}: ${desc}${required}\n`;
        }
        const exampleArgs: any = {};
        for (const [key, val] of Object.entries(props)) {
          const prop = val as any;
          if (prop.type === 'string') exampleArgs[key] = req.includes(key) ? '<value>' : '';
          else if (prop.type === 'boolean') exampleArgs[key] = false;
          else if (prop.type === 'number') exampleArgs[key] = 0;
          else if (prop.type === 'array') exampleArgs[key] = [];
          else exampleArgs[key] = '';
        }
        text += `Example: {"name":"${fn.name}","arguments":${JSON.stringify(exampleArgs)}}\n`;
      }
      text += '\n';
    }
    text += '--- END TOOLS ---\n';
    text += 'REMEMBER: Output ONLY the JSON tool call line. Nothing else. No explanations.\n';
    return text;
  }

  // --- Response Parsing ---

  extractReasoning(text: string): string {
    if (!text) return '';
    const patterns = [
      /reasoning:\s*([\s\S]*?)(?=tool_call:|EOS|$)/i,
      /thinking:\s*([\s\S]*?)(?=tool_call:|EOS|$)/i,
      /plan:\s*([\s\S]*?)(?=tool_call:|EOS|$)/i,
      /<thinking>([\s\S]*?)<\/thinking>/i,
      /<reasoning>([\s\S]*?)<\/reasoning>/i
    ];
    for (const pattern of patterns) {
      const match = text.match(pattern);
      if (match && match[1].trim()) {
        return match[1].trim();
      }
    }
    return '';
  }

  extractFinalResponse(text: string): string {
    if (!text) return '';
    text = text.replace(/reasoning:\s*/gi, '');
    text = text.replace(/\bEOS\b/gi, '');
    text = text.replace(/tool_call:\s*\{[\s\S]*?\}(?=\n|$|tool_call:)/g, '');
    text = text.replace(/^tool_calls:\s*/gmi, '');
    const blocks = text.split(/\n\n+/).filter(b => b.trim().length > 20);
    if (blocks.length > 1) return blocks.reduce((a, b) => a.length > b.length ? a : b).trim();
    return text.trim();
  }

  // --- Proxy Misbehavior Detection ---

  /**
   * Detect when DeepSeek is outputting text/plan format instead of tool calls.
   */
  detectProxyMisbehavior(text: string): boolean {
    if (!text || text.length < 10) return false;
    const lower = text.toLowerCase();
    let score = 0;
    if (/\b(calling|call)\s*[:\s]+\w+/.test(text)) score += 2;
    if (/\b(will|i'll|i will|let me)\s+\w+/.test(text) && /\b(read|list|search|check)\b/.test(lower)) score += 1;
    if (/```\s*(json)?\s*\n?\s*\{\s*"path"/.test(text)) score += 2;
    if (/\bfirst\s*,?\s*(i'll|i will|let me)/.test(text)) score += 1;
    if (/\bnext\s*,?\s*(i'll|i will)/.test(text)) score += 1;
    const hasToolParams = /"path"|"recursive"|"command"|"pattern"/.test(text);
    const hasJsonToolCall = /"name"\s*:\s*"\w+"/.test(text) && /"arguments"\s*:/.test(text);
    if (hasToolParams && !hasJsonToolCall) score += 1;
    return score >= 2;
  }
}
