/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

/**
 * 3D LLM Provider implementation (FreeDeepseekAPI proxy) with unified error handling.
 *
 * Ported from cliIntegration.ts call3DLlm — includes:
 *  - Message sanitization (strip XML/file_action format)
 *  - 5-strategy tool call extraction from text responses
 *  - Server error tracking with cooldown
 */

import {LLMProvider, LLMProviderCapabilities, LLMRequest, LLMResponse} from '../../types/provider-types';
import {ErrorHandler} from '../../core/ErrorHandler';
import {ErrorContext} from '../../core/types';
import {extractToolCallFromText} from '../common/toolCalls';
import {LLMAdapter, LLMAdapterConfig} from '../../agent/AgentBridge.LLMAdapter';
import {CLI} from '../../cliIntegrationRefactored';
import {Diagnostics} from '../../agent/AgentBridge.Diagnostics';

export class ThreeDLlmProvider implements LLMProvider {
  private baseUrl: string;
  private defaultModel: string;
  private errorHandler: ErrorHandler;
  private outputChannel?: any;
  private serverErrorCount: number = 0;
  private lastServerErrorTime: number = 0;
  private readonly MAX_SERVER_ERRORS: number = 3;
  private readonly SERVER_ERROR_COOLDOWN: number = 300000; // 5 minutes
  private readonly REQUEST_TIMEOUT_MS: number = 120000; // 2 min for full chat completion (streaming)
  private readonly MAX_RETRY_ATTEMPTS: number = 3;
  private readonly BASE_RETRY_DELAY_MS: number = 1000; // 1 second base delay
  private readonly MAX_RETRY_DELAY_MS: number = 10000; // 10 seconds max delay
  private llmAdapter: LLMAdapter;

  constructor(baseUrl: string, defaultModel: string, errorHandler: ErrorHandler, outputChannel?: any) {
    this.baseUrl = baseUrl;
    this.defaultModel = defaultModel;
    this.errorHandler = errorHandler;
    this.outputChannel = outputChannel;
    // Initialise LLMAdapter for prompt compaction. Passing null for CLI and Diagnostics because
    // we only need the prepareMessages method which does not depend on them.
    this.llmAdapter = new LLMAdapter(null as any, null as any);
  }

  getProviderName(): string {
    return '3D LLM';
  }

  getCapabilities(): LLMProviderCapabilities {
    return {
      streaming: true,
      nativeToolCalls: true,  // Proxy prompt-emulates tools; inject into system prompt
      structuredMessages: true,
      sessionManagement: true, // proxy sessions via /v1/sessions
      contextCompaction: true, // POST /reset-session + compactMessages()
      authRequired: false,
      maxContextLength: 64000,
      chatEndpoint: '/v1/chat/completions',
      healthEndpoint: '/v1/models',
    };
  }

  validateConfiguration(): void {
    if (!this.baseUrl) {
      throw new Error('3D LLM base URL is required');
    }
  }

  async callAPI(request: LLMRequest): Promise<LLMResponse | AsyncGenerator<any>> {
    const model = request.model || this.defaultModel;
    const callId = `call_${Date.now().toString(36)}_${Math.random().toString(36).substr(2, 6)}`;
    this.log(`[3D LLM] callAPI START id=${callId} model=${model}`);
    const context: ErrorContext = {
      request: {
        model: model,
        prompt: request.prompt,
        options: {
          temperature: request.temperature,
          top_p: request.topP,
          max_tokens: request.maxTokens
        }
      }
    };

    // Exponential backoff retry logic for transient errors
    let lastError: Error | null = null;
    for (let attempt = 0; attempt <= this.MAX_RETRY_ATTEMPTS; attempt++) {
      try {
        return await this.errorHandler.handleError(
          async () => {
        // Use structured messages if available, otherwise fall back to flat prompt string
        let messages = request.messages && request.messages.length > 0
          ? request.messages.map(msg => ({
              role: msg.role,
              content: msg.content,
              ...(msg.tool_calls ? { tool_calls: msg.tool_calls } : {}),
              ...(msg.tool_call_id ? { tool_call_id: msg.tool_call_id } : {}),
            }))
          : [{ role: 'user', content: request.prompt }];

        // Sanitize messages before sending (strip XML/file_action contamination)
        messages = this.sanitizeMessages(messages);

        this.log(`[3D LLM] Sending to ${this.baseUrl}/v1/chat/completions: model=${model}, messages=${messages.length}, roles=[${messages.map(m => m.role).join(',')}]`);

        // Respect the stream parameter from the request (default to true for better proxy behavior)
        const useStream = (request as any).stream !== undefined ? (request as any).stream : true;

        const body: any = {
          model: model,
          messages: messages,
          stream: useStream,
          temperature: request.temperature || 0.2,
          top_p: request.topP || 0.95,
          max_tokens: request.maxTokens || 4096
        };

        // Forward tools if provided (OpenAI-compatible tools parameter)
        if (request.tools && request.tools.length > 0) {
          body.tools = request.tools;
        }

        // Forward thinking/search toggle if specified (supported by DeepSeek Web API proxy).
        // The FreeDeepseekAPI proxy expects `thinking` and `search` booleans, but
        // accept the legacy *_enabled aliases as well for backward compatibility.
        const options = request as any;
        const thinking = options.thinking ?? options.thinking_enabled;
        if (thinking !== undefined) {
          body.thinking = thinking;
        }
        const search = options.search ?? options.search_enabled;
        if (search !== undefined) {
          body.search = search;
        }
        // Forward user/session identifier. The 3D LLM proxy keys session reuse on
        // this value ("sticky per x-agent-session/user"): omitting it causes all
        // chats for a model to share the same sticky session.
        if (options.user) {
          body.user = options.user;
        }

        const bodyStr = JSON.stringify(body);
        const toolNames = body.tools?.map((t: any) => t.function?.name || t.name).join(',') || 'none';
        this.log(`[3D LLM] Body size: ${bodyStr.length} chars, tools: [${toolNames}]`);

        // Abort the request if the proxy hangs (connects but never responds).
        // Without this, a frozen proxy blocks the agent silently forever.
        const controller = new AbortController();
        const timeoutMs = (request as any).timeoutSeconds
          ? (request as any).timeoutSeconds * 1000
          : this.REQUEST_TIMEOUT_MS;
        const timeoutHandle = setTimeout(() => controller.abort(), timeoutMs);

        let response: Response;
        try {
          response = await fetch(`${this.baseUrl}/v1/chat/completions`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: bodyStr,
            signal: controller.signal
          });
        } catch (error: any) {
          clearTimeout(timeoutHandle);
          if (error?.name === 'AbortError') {
            throw this.timeoutError(timeoutMs);
          }
          throw new Error(`3D LLM connection failed: ${error?.message || error}`);
        }

        if (!response.ok) {
          const errorText = await response.text();
          let errorMessage = `3D LLM API error: ${response.status} ${response.statusText}`;

          if (response.status === 404) {
            errorMessage = '3D LLM API error: 404 Not Found - The FreeDeepseekAPI server is not running or the URL is incorrect.';
          } else if (response.status === 500) {
            errorMessage = '3D LLM API error: 500 Internal Server Error - The FreeDeepseekAPI server encountered an error.';
            this.recordServerError();
          } else if (response.status === 0) {
            errorMessage = '3D LLM API error: Connection refused - Cannot connect to the FreeDeepseekAPI server.';
          } else if (errorText.includes('ECONNREFUSED')) {
            errorMessage = '3D LLM API error: Connection refused - Cannot connect to the FreeDeepseekAPI server.';
            this.recordServerError();
          } else if (errorText.includes('ENOTFOUND') || errorText.includes('getaddrinfo')) {
            errorMessage = '3D LLM API error: Host not found - The FreeDeepseekAPI server URL is invalid.';
            this.recordServerError();
          }

          throw new Error(errorMessage + (errorText ? ` - ${errorText}` : ''));
        }

        let content = '';
        let toolCallsData: any[] = [];
        let usage: any = undefined;
        let accumulatedReasoning = '';

        if (useStream && response.body) {
          return this.stream3DLlmResponseAsync(response, Date.now(), timeoutHandle) as any;
        } else if (false) {
          // Handle SSE streaming response
          const reader = response.body!.getReader();
          const decoder = new TextDecoder('utf-8');
          let buffer = '';
          let accumulatedText = '';
          let promptTokens = 0;
          let completionTokens = 0;
          let reasoningTokens = 0;

          // Index-based accumulator for SSE tool_call deltas (OpenAI streaming protocol)
          const toolCallAccumulator = new Map<number, { id: string; name: string; argsStr: string }>();

          while (true) {
            let chunk;
            try {
              chunk = await reader.read();
            } catch (error: any) {
              clearTimeout(timeoutHandle);
              if (error?.name === 'AbortError') {
                throw this.timeoutError(timeoutMs);
              }
              throw error;
            }
            const { done, value } = chunk;
            if (done) break;

            const decoded = decoder.decode(value, { stream: true });
            buffer += decoded;
            const lines = buffer.split('\n');
            buffer = lines.pop() || '';

            for (const line of lines) {
              if (!line.trim() || line === 'data: [DONE]') continue;

              try {
                const chunkStr = line.startsWith('data: ') ? line.slice(6) : line;
                const chunk = JSON.parse(chunkStr);
                const choice = chunk.choices?.[0];
                const delta = choice?.delta || {};
                const deltaContent = delta.content || '';
                const deltaReasoning = delta.reasoning_content || '';

                if (deltaContent) {
                  accumulatedText += deltaContent;
                }
                if (deltaReasoning) {
                  accumulatedReasoning += deltaReasoning;
                }

                // Track native tool_calls from SSE delta with index-based accumulation
                if (choice?.delta?.tool_calls) {
                  for (const tc of choice.delta.tool_calls) {
                    const index = tc.index ?? 0;
                    if (!toolCallAccumulator.has(index)) {
                      toolCallAccumulator.set(index, { id: '', name: '', argsStr: '' });
                    }
                    const acc = toolCallAccumulator.get(index)!;
                    if (tc.id) acc.id = tc.id;
                    if (tc.function?.name) acc.name = tc.function.name;
                    if (tc.function?.arguments) acc.argsStr += tc.function.arguments;
                  }
                }

                // Track usage from SSE chunks (including reasoning_tokens)
                if (chunk.usage?.prompt_tokens) {
                  promptTokens = chunk.usage.prompt_tokens;
                }
                if (chunk.usage?.completion_tokens) {
                  completionTokens = chunk.usage.completion_tokens;
                }
                if (chunk.usage?.completion_tokens_details?.reasoning_tokens) {
                  reasoningTokens = chunk.usage.completion_tokens_details.reasoning_tokens;
                }
              } catch {
                // Skip unparseable chunks
              }
            }
          }

          // Convert accumulated tool calls from the map to toolCallsData
          for (const [, acc] of toolCallAccumulator) {
            if (acc.name) {
              let parsedArgs: any = {};
              if (acc.argsStr) {
                try { parsedArgs = JSON.parse(acc.argsStr); } catch { parsedArgs = acc.argsStr; }
              }
              toolCallsData.push({
                id: acc.id || `call_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`,
                type: 'function',
                function: {
                  name: acc.name,
                  arguments: JSON.stringify(parsedArgs)
                }
              });
            }
          }

          content = accumulatedText;
          if (promptTokens > 0 || completionTokens > 0) {
            usage = {
              promptTokens: promptTokens,
              completionTokens: completionTokens,
              totalTokens: promptTokens + completionTokens + reasoningTokens,
              reasoningTokens: reasoningTokens
            };
          }
        } else {
          // Handle non-streaming JSON response
          const data = await response.json();
          const choice = data.choices?.[0];
          const msg = choice?.message || {};

          // DeepSeek reasoning models may put output in reasoning_content instead of content
          content = msg.content || msg.reasoning_content || '';
          toolCallsData = msg.tool_calls || [];
          usage = data.usage ? {
            promptTokens: data.usage.prompt_tokens || 0,
            completionTokens: data.usage.completion_tokens || 0,
            totalTokens: data.usage.total_tokens || 0
          } : undefined;
        }

        // Response fully consumed — cancel the hang-detection timer.
        clearTimeout(timeoutHandle);

        // Extract tool calls from text response (proxy returns tools as text, not native tool_calls)
        if (toolCallsData.length === 0 && content) {
          toolCallsData = this.extractToolCallsFromText(content);
        }

        this.log(`[3D LLM] Raw content (${content.length} chars): ${content.substring(0, 300)}${content.length > 300 ? '...' : ''}`);
        this.log(`[3D LLM] Extracted tool_calls: ${toolCallsData.length}, displayText will be: ${content.length} chars before strip`);

        // Strip tool call blocks from display text so user sees clean output
        let displayText = content;
        if (toolCallsData.length > 0) {
          displayText = this.stripToolCallFromText(content);
        }

        // Debug logging for empty responses
        if (!displayText) {
          this.log(`[3D LLM] Empty response — content: "${content.substring(0, 200)}", tool_calls: ${toolCallsData.length}`);
          if (toolCallsData.length === 0 && content) {
            this.log(`[3D LLM] Full raw content (${content.length} chars):`, JSON.stringify(content).substring(0, 500));
          }
        }

        this.log(`[3D LLM] callAPI COMPLETE id=${callId} text=${displayText.length} reasoning=${accumulatedReasoning.length} toolCalls=${toolCallsData.length}`);

        // Per-request token logging for optimization tracking
        if (usage) {
          this.log(`[3D LLM] Token usage: prompt=${usage.promptTokens}, completion=${usage.completionTokens}, total=${usage.totalTokens}, reasoning=${usage.reasoningTokens || 0}`);
        } else {
          this.log(`[3D LLM] Token usage: not available`);
        }

        return {
          text: displayText,
          reasoning: (accumulatedReasoning || undefined) as any,
          model: model,
          provider: '3dllm',
          tool_calls: toolCallsData.map((tc: any) => ({
            id: tc.id || `call_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`,
            type: tc.type || 'function',
            function: {
              name: tc.function?.name || tc.name || '',
              arguments: typeof tc.function?.arguments === 'string'
                ? tc.function.arguments
                : typeof tc.arguments === 'string'
                  ? tc.arguments
                  : JSON.stringify(tc.function?.arguments || tc.arguments || {})
            }
          })),
          usage: usage
        };
      },
      '3D LLM',
      context
    );
      } catch (error: any) {
        lastError = error;
        const isRetryable = this.isRetryableError(error);

        if (attempt < this.MAX_RETRY_ATTEMPTS && isRetryable) {
          const delay = this.calculateRetryDelay(attempt);
          this.log(`[3D LLM] Retry attempt ${attempt + 1}/${this.MAX_RETRY_ATTEMPTS} after ${delay}ms. Error: ${error.message}`);
          await this.sleep(delay);
          continue;
        }

        this.log(`[3D LLM] callAPI FAILED after ${attempt + 1} attempts. Error: ${error.message}`);
        throw error;
      }
    }

    // Should never reach here, but satisfy TypeScript
    throw lastError || new Error('Unknown error during API call');
  }

  private async *stream3DLlmResponseAsync(res: Response, startTime: number, timeoutHandle?: any): AsyncGenerator<any> {
    if (!res.body) {
      throw new Error('3D LLM streaming response body is not readable');
    }
    const reader = res.body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';
    let fullContent = '';
    let fullReasoning = '';
    let usage: any = undefined;
    let done = false;

    try {
      while (!done) {
        const { value, done: readerDone } = await reader.read();
        if (readerDone) break;

        buffer += decoder.decode(value, { stream: true });
        const lines = buffer.split('\n');
        buffer = lines.pop() || '';

        for (const line of lines) {
          const trimmed = line.trim();
          if (!trimmed.startsWith('data:')) continue;
          const payload = trimmed.slice(5).trim();
          if (!payload || payload === '[DONE]') {
            done = true;
            break;
          }
          try {
            const chunk = JSON.parse(payload);
            const delta = chunk.choices?.[0]?.delta;
            const content = delta?.content || '';
            const reasoning = delta?.reasoning_content || '';
            if (content) {
              fullContent += content;
              yield { text: content, done: false };
            }
            if (reasoning) fullReasoning += reasoning;
            if (chunk.usage) {
              usage = {
                promptTokens: chunk.usage.prompt_tokens || 0,
                completionTokens: chunk.usage.completion_tokens || 0,
                totalTokens: chunk.usage.total_tokens || 0
              };
            }
          } catch (e) {
            // ignore malformed SSE line
          }
        }
      }

      // flush remaining buffer
      const finalLine = buffer.trim();
      if (finalLine.startsWith('data:')) {
        const payload = finalLine.slice(5).trim();
        if (payload && payload !== '[DONE]') {
          try {
            const chunk = JSON.parse(payload);
            const delta = chunk.choices?.[0]?.delta;
            if (delta?.content) {
              fullContent += delta.content;
              yield { text: delta.content, done: false };
            }
            if (delta?.reasoning_content) fullReasoning += delta.reasoning_content;
            if (chunk.usage) {
              usage = {
                promptTokens: chunk.usage.prompt_tokens || 0,
                completionTokens: chunk.usage.completion_tokens || 0,
                totalTokens: chunk.usage.total_tokens || 0
              };
            }
          } catch (e) {}
        }
      }

      // Extract tool calls from the accumulated content
      const sharedExtracted = extractToolCallFromText(fullContent);
      const extractedToolCalls = sharedExtracted.length > 0
        ? sharedExtracted
        : this.extractToolCallsFromText(fullContent);
      
      // Debug logging for streaming tool call extraction
      this.log(`[3D LLM STREAMING] Full content length: ${fullContent.length}`);
      this.log(`[3D LLM STREAMING] Full content preview: ${fullContent.substring(0, 200)}...`);
      this.log(`[3D LLM STREAMING] Extracted tool calls: ${extractedToolCalls.length}`);
      if (extractedToolCalls.length > 0) {
        this.log(`[3D LLM STREAMING] Tool calls: ${JSON.stringify(extractedToolCalls)}`);
      }

      // Per-request token logging for streaming responses
      if (usage) {
        this.log(`[3D LLM STREAMING] Token usage: prompt=${usage.promptTokens}, completion=${usage.completionTokens}, total=${usage.totalTokens}`);
      }

      yield {
        text: '',
        reasoning: fullReasoning || undefined,
        model: '',
        provider: '3dllm',
        toolCalls: extractedToolCalls,
        tokenUsage: usage,
        done: true
      };
    } finally {
      if (timeoutHandle) {
        clearTimeout(timeoutHandle);
      }
      reader.releaseLock();
    }
  }

  // ---- Timeout error helper ----

  private timeoutError(timeoutMs: number): Error {
    return new Error(
      `3D LLM proxy timed out after ${Math.round(timeoutMs / 1000)}s without responding. ` +
      'The FreeDeepseekAPI proxy may be hung or unreachable — check that it is running and restart it if needed.'
    );
  }

  // ---- Message sanitization (from cliIntegration.ts) ----

  private sanitizeMessages(messages: any[]): any[] {
    return messages.map(m => {
      if (!m.content || typeof m.content !== 'string') return m;
      let content = m.content;
      // Remove XML tags that teach the model wrong format
      content = content.replace(/<\/?file_action\b[^>]*>/gi, '');
      content = content.replace(/<\/?action\b[^>]*>/gi, '');
      content = content.replace(/<\/?invoke\b[^>]*>/gi, '');
      content = content.replace(/<\/?parameter\b[^>]*>/gi, '');
      content = content.replace(/<\/?DSML\b[^>]*>/gi, '');
      // Remove markdown code blocks containing tool calls
      content = content.replace(/```(?:json)?\s*\n?\s*\{\s*"name"[\s\S]*?```/g, '');
      // Remove "Calling:" patterns
      content = content.replace(/Calling:\s*\w+\s*\n?\s*```[\s\S]*?```/gi, '');
      // Clean up multiple consecutive blank lines
      content = content.replace(/\n{3,}/g, '\n\n');
      return { ...m, content };
    });
  }

  // ---- Tool call extraction from text (from cliIntegration.ts) ----
  // 
  // OPTIMIZATION NOTE (Phase 2):
  // Strategies 0b, 1, 2b, 6, 7 support XML formats which we want to discourage.
  // With conditional prompts (hasNativeTools=true) and stronger JSON-only instructions,
  // these XML strategies should rarely trigger. Consider removing them after testing.

  private extractToolCallsFromText(text: string): any[] {
    // Strategy 0: Numbered list with code blocks (deepseek-v4-pro conversational style)
    // Matches patterns like:
    //   1. Read the file
    //   bash
    //   type "path/to/file"
    // or:
    //   1. Search for something
    //   ```bash
    //   dir /s pattern
    //   ```
    const numberedListPattern = /(?:\d+[\.)]\s*[^\n]*\n)(?:```(?:bash|shell|sh|cmd|powershell|bat)?\s*\n?([\s\S]*?)```|(?:bash|shell|cmd)\s*\n([\s\S]*?)(?=\n\n|\n\d+[\.)]|$))/gi;
    let nlMatch;
    const results: any[] = [];
    while ((nlMatch = numberedListPattern.exec(text)) !== null) {
      try {
        let cmd = (nlMatch[1] || nlMatch[2] || '').trim();
        if (!cmd) continue;

        // Split multiple commands in a single code block
        const commands = cmd.split('\n').map(c => c.trim()).filter(c => c && !c.startsWith('#') && !c.startsWith('//'));

        for (const command of commands) {
          // Detect the tool type from the command
          let toolName: string | null = null;
          let toolArgs: any = {};

          // type / cat / more -> read_file
          if (/^(type|cat|more|get-content)\s+/i.test(command)) {
            toolName = 'read_file';
            const pathMatch = command.match(/^(?:type|cat|more|get-content)\s+["']?([^"'\n]+)["']?/i);
            if (pathMatch) toolArgs.file_path = pathMatch[1].trim();
          }
          // dir / ls -> list_directory
          else if (/^(dir|ls)\s+/i.test(command)) {
            toolName = 'list_directory';
            const pathMatch = command.match(/^(?:dir|ls)\s+([^/\n]+)/i);
            if (pathMatch) toolArgs.path = pathMatch[1].trim();
            else toolArgs.path = '.';
            toolArgs.recursive = /\/s|-R|--recursive/i.test(command);
          }
          // find / grep / findstr / search -> search_files
          else if (/^(find|findstr|grep|rg|ag)\s+/i.test(command)) {
            toolName = 'search_files';
            const patternMatch = command.match(/(?:find|findstr|grep|rg|ag)\s+["']?([^"'\s]+)["']?/i);
            if (patternMatch) toolArgs.pattern = patternMatch[1].trim();
            else toolArgs.pattern = command.split(/\s+/)[1] || '';
          }
          // cd + cat/type pattern -> read_file with path
          // npm / gradle / mvn / make / go -> run_build
          else if (/^(npm|yarn|pnpm|gradle|mvn|make|go|cargo|dotnet)\s+/i.test(command)) {
            toolName = 'run_build';
            toolArgs.command = command;
          }
          // git commands -> git_status / git_diff / etc
          else if (/^git\s+/i.test(command)) {
            if (/\b(status|st)\b/i.test(command)) {
              toolName = 'git_status';
            } else if (/\b(diff|d)\b/i.test(command)) {
              toolName = 'git_diff';
            } else if (/\b(log|l)\b/i.test(command)) {
              toolName = 'git_log';
            } else if (/\b(branch|br)\b/i.test(command)) {
              toolName = 'git_branch';
            } else if (/\bcommit\b/i.test(command)) {
              toolName = 'git_commit';
              const msgMatch = command.match(/-m\s+["']([^"']+)["']/);
              if (msgMatch) toolArgs.message = msgMatch[1];
            }
          }
          // Everything else that looks like a terminal command -> run_terminal
          else if (command.length > 3 && /^[a-zA-Z_./\\]/.test(command)) {
            // Filter out explanatory prose lines (must look like a real command)
            const looksLikeCommand = /^[a-zA-Z_./\\][a-zA-Z0-9_.\-\/\\]+(\s+|$)/.test(command) ||
                                     /^(echo|exit|set|export|cd|copy|del|move|xcopy|robocopy|mkdir|rmdir|ren|call|start|pause)\s/i.test(command) ||
                                     /^(python|node|java|ruby|perl|php)\s/i.test(command);
            if (looksLikeCommand) {
              toolName = 'run_terminal';
              toolArgs.command = command;
            }
          }

          if (toolName) {
            results.push({
              id: `call_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`,
              type: 'function',
              function: {
                name: toolName,
                arguments: JSON.stringify(toolArgs)
              }
            });
          }
        }
      } catch (e: any) {}
    }
    if (results.length > 0) return results;

    // Strategy 0b: <invoke name="tool"> with <parameter name="key">value</parameter> children.
    // Handles both bare <invoke> and <tool_calls> wrapped forms, which deepseek-v4-pro
    // emits in practice:
    //   <invoke name="list_directory"><parameter name="path">.</parameter></invoke>
    //   <tool_calls><invoke name="list_directory">...</invoke></tool_calls>
    const invokePattern = /<invoke\s+([^>]*?)>([\s\S]*?)<\/invoke>/gi;
    let invokeMatch;
    const invokeResults: any[] = [];
    while ((invokeMatch = invokePattern.exec(text)) !== null) {
      try {
        const attrs = invokeMatch[1] || '';
        const inner = invokeMatch[2] || '';
        const nameMatch = attrs.match(/name\s*=\s*["']([^"']+)["']/i);
        if (!nameMatch) continue;
        const toolName = nameMatch[1].trim();
        const args: any = {};

        // Parse <parameter name="key">value</parameter> children
        const paramPattern = /<parameter\s+([^>]*?)>([\s\S]*?)<\/parameter>/gi;
        let pm;
        while ((pm = paramPattern.exec(inner)) !== null) {
          const paramAttrs = pm[1] || '';
          const keyMatch = paramAttrs.match(/name\s*=\s*["']([^"']+)["']/i);
          if (!keyMatch) continue;
          const key = keyMatch[1].trim();
          const value = (pm[2] || '').trim();
          // Parse booleans/numbers, fallback to string
          if (value === 'true') args[key] = true;
          else if (value === 'false') args[key] = false;
          else if (/^-?\d+$/.test(value)) args[key] = parseInt(value, 10);
          else args[key] = value;
        }

        invokeResults.push({
          id: `call_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`,
          type: 'function',
          function: { name: toolName, arguments: JSON.stringify(args) }
        });
      } catch (e: any) {}
    }
    if (invokeResults.length > 0) return invokeResults;

    // Strategy 1: <file_action> XML format (DeepSeek v4-pro)
    const fileActionPattern = /<file_action>\s*<action>([^<]+)<\/action>(.*?)<\/file_action>/gs;
    let faMatch;
    while ((faMatch = fileActionPattern.exec(text)) !== null) {
      try {
        const toolName = faMatch[1].trim();
        const innerContent = faMatch[2];
        const args: any = {};
        const paramPattern = /<([a-zA-Z_][a-zA-Z0-9_]*)\s*>(.*?)<\/\1\s*>/gs;
        let pmMatch;
        while ((pmMatch = paramPattern.exec(innerContent)) !== null) {
          const key = pmMatch[1].trim();
          const value = pmMatch[2].trim();
          if (key && key !== 'action') {
            args[key] = value;
          }
        }

        return [{
          id: `call_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`,
          type: 'function',
          function: {
            name: toolName,
            arguments: JSON.stringify(args)
          }
        }];
      } catch (e: any) {}
    }

    // Strategy 2: JSON objects inside markdown code blocks
    const codeBlockPattern = /```(?:json)?\s*\n?([\s\S]*?)```/g;
    let cbMatch;
    while ((cbMatch = codeBlockPattern.exec(text)) !== null) {
      try {
        const blockContent = cbMatch[1].trim();
        const toolCallObj = JSON.parse(blockContent);
        if (toolCallObj.name && typeof toolCallObj.name === 'string') {
          const toolName = toolCallObj.name;
          let toolArgs = toolCallObj.arguments || {};
          if (typeof toolArgs === 'string') {
            try { toolArgs = JSON.parse(toolArgs); } catch {}
          }
          let finalArgs = toolArgs;
          if (toolName === 'apply_edits' && typeof toolArgs === 'object' && toolArgs.edits) {
            finalArgs = toolArgs;
          } else if (typeof toolArgs === 'object') {
            finalArgs = JSON.stringify(toolArgs);
          }
          return [{
            id: `call_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`,
            type: 'function',
            function: { name: toolName, arguments: finalArgs }
          }];
        } else if (Array.isArray(toolCallObj)) {
          const results: any[] = [];
          for (const tc of toolCallObj) {
            if (tc.name && typeof tc.name === 'string') {
              const toolName = tc.name;
              let toolArgs = tc.arguments || {};
              if (typeof toolArgs === 'string') {
                try { toolArgs = JSON.parse(toolArgs); } catch {}
              }
              let finalArgs = toolArgs;
              if (toolName === 'apply_edits' && typeof toolArgs === 'object' && toolArgs.edits) {
                finalArgs = toolArgs;
              } else if (typeof toolArgs === 'object') {
                finalArgs = JSON.stringify(toolArgs);
              }
              results.push({
                id: `call_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`,
                type: 'function',
                function: { name: toolName, arguments: finalArgs }
              });
            }
          }
          if (results.length > 0) return results;
        }
      } catch (e: any) {}
    }

    // Strategy 2b: Generic XML wrapper parser for accidental tool-call wrapping.
    const fnXmlTagPatterns: Array<{ open: RegExp; close: string }> = [
      { open: /<function-call\b[^>]*>/gi, close: '</function-call>' },
      { open: /<function_calls\b[^>]*>/gi, close: '</function_calls>' },
      { open: /<invoke\b[^>]*>/gi, close: '</invoke>' }
    ];
    for (const tagPattern of fnXmlTagPatterns) {
      let fnCallXmlMatch;
      while ((fnCallXmlMatch = tagPattern.open.exec(text)) !== null) {
        const openTag = fnCallXmlMatch[0];
        const fnCallXmlJsonStart = fnCallXmlMatch.index + openTag.length;
        const fnCallXmlEnd = text.indexOf(tagPattern.close, fnCallXmlJsonStart);
        if (fnCallXmlEnd === -1) continue;
        const fnCallXmlJson = text.substring(fnCallXmlJsonStart, fnCallXmlEnd).trim();
        let toolName = '';
        let toolArgs: any = {};
        if (fnCallXmlJson.startsWith('{') || fnCallXmlJson.startsWith('[')) {
          try {
            const fnCallXmlObj = JSON.parse(fnCallXmlJson);
            if (fnCallXmlObj.name && typeof fnCallXmlObj.name === 'string') {
              toolName = fnCallXmlObj.name;
              toolArgs = fnCallXmlObj.arguments || {};
            }
          } catch (e: any) {}
        }
        if (!toolName) {
          const nameMatch = openTag.match(/name\s*=\s*["']([^"']+)["']/i);
          if (nameMatch) toolName = nameMatch[1];
        }
        if (toolName) {
          results.push({
            id: `call_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`,
            type: 'function',
            function: { name: toolName, arguments: JSON.stringify(toolArgs) }
          });
        }
      }
    }

    if (results.length > 0) return results;

    // Strategy 3: Balance-brace JSON extraction (handles nested objects)
    const lines = text.split('\n');
    for (let i = 0; i < lines.length; i++) {
      const line = lines[i].trim();
      if (line === '{' || line.startsWith('{')) {
        let braceCount = 0;
        let jsonStr = '';
        for (let j = i; j < lines.length; j++) {
          for (const char of lines[j]) {
            if (char === '{') braceCount++;
            if (char === '}') braceCount--;
            jsonStr += char;
            if (braceCount === 0 && jsonStr.includes('"name"')) {
              try {
                const toolCallObj = JSON.parse(jsonStr);
                if (toolCallObj.name && typeof toolCallObj.name === 'string') {
                  let toolArgs = toolCallObj.arguments || {};
                  let finalArgs = toolArgs;
                  if (toolCallObj.name === 'apply_edits' && typeof toolArgs === 'object' && toolArgs.edits) {
                    finalArgs = toolArgs;
                  } else if (typeof toolArgs === 'object') {
                    finalArgs = JSON.stringify(toolArgs);
                  } else {
                    finalArgs = JSON.stringify(toolArgs);
                  }
                  return [{
                    id: `call_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`,
                    type: 'function',
                    function: { name: toolCallObj.name, arguments: finalArgs }
                  }];
                }
              } catch (e: any) {}
            }
          }
        }
      }
    }

    // Strategy 4: "Calling:" format
    const callingPattern = /Calling:\s*(\w+)\s*\n?\s*```(?:json)?\s*\n?([\s\S]*?)```|Calling:\s*(\w+)\s*\n?\s*(\{[\s\S]*?\})/gi;
    let cmatch;
    while ((cmatch = callingPattern.exec(text)) !== null) {
      try {
        const toolName = cmatch[1] || cmatch[3];
        const jsonStr = (cmatch[2] || cmatch[4]).trim();
        const args = JSON.parse(jsonStr);
        if (toolName) {
          return [{
            id: `call_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`,
            type: 'function',
            function: { name: toolName, arguments: JSON.stringify(args || {}) }
          }];
        }
      } catch (e: any) {}
    }

    // Strategy 5: Inline JSON with tool-revealing keys (path+edits, command, pattern)
    const inlineJsonPattern1 = /\{["']path["']\s*:\s*["'][^"']+["'][^}]*["']edits["']\s*:\s*\[/g;
    let jsonMatch;
    while ((jsonMatch = inlineJsonPattern1.exec(text)) !== null) {
      try {
        let braceCount = 0;
        let jsonStr = '';
        let startPos = jsonMatch.index;
        for (let i = startPos; i < text.length; i++) {
          const char = text[i];
          if (char === '{') braceCount++;
          if (char === '}') braceCount--;
          jsonStr += char;
          if (braceCount === 0) break;
        }
        if (jsonStr.trim()) {
          const toolParams = JSON.parse(jsonStr);
          let inferredToolName: string | null = null;
          if (toolParams.path && Array.isArray(toolParams.edits)) {
            inferredToolName = 'apply_edits';
          } else if (toolParams.path && toolParams.recursive !== undefined) {
            inferredToolName = 'list_directory';
          } else if (toolParams.command) {
            inferredToolName = 'run_terminal';
          } else if (toolParams.pattern) {
            inferredToolName = 'search_files';
          }
          if (inferredToolName) {
            return [{
              id: `call_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`,
              type: 'function',
              function: { name: inferredToolName, arguments: JSON.stringify(toolParams) }
            }];
          }
        }
      } catch (e: any) {}
    }

    // Strategy 6: Bare XML tags (self-closing or open/close) — e.g.
    // <search_files pattern=".*" recursive="true" path="." />
    // <run_terminal><command>npm install</command></run_terminal>
    const bareXmlPattern = /<([a-zA-Z_][a-zA-Z0-9_]*)\b([^>]*?)(\/\s*>|>)/gs;
    let xmlMatch;
    const knownToolNames = ['search_files', 'list_directory', 'read_file', 'get_file_context',
      'write_file', 'apply_edits', 'run_terminal', 'run_build', 'git_commit',
      'git_status', 'git_diff', 'search_content'];
    while ((xmlMatch = bareXmlPattern.exec(text)) !== null) {
      try {
        const tagName = xmlMatch[1].trim();
        if (!knownToolNames.includes(tagName)) continue;
        const attrStr = xmlMatch[2] || '';
        const isSelfClosing = xmlMatch[3]?.startsWith('/');
        const args: any = {};

        // Parse attributes: attr="value" or attr='value'
        const attrPattern = /([a-zA-Z_][a-zA-Z0-9_]*)\s*=\s*["']([^"']*?)["']/g;
        let attrMatch;
        while ((attrMatch = attrPattern.exec(attrStr)) !== null) {
          const key = attrMatch[1];
          const value = attrMatch[2];
          // Try to parse as boolean/number, fallback to string
          if (value === 'true') args[key] = true;
          else if (value === 'false') args[key] = false;
          else if (/^-?\d+$/.test(value)) args[key] = parseInt(value, 10);
          else args[key] = value;
        }

        if (Object.keys(args).length > 0 || tagName === 'git_status' || tagName === 'git_diff') {
          return [{
            id: `call_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`,
            type: 'function',
            function: { name: tagName, arguments: JSON.stringify(args) }
          }];
        }
      } catch (e: any) {}
    }

    // Strategy 7: Text mentions tools by name in prose without a structured call.
    // E.g. "Let me explore... <search_files pattern=.* path=. recursive=true />"
    // or "I'll call search_files" without the actual call.
    // This is a last-resort heuristic — we look for known tool names in
    // angle-bracket blocks that contain = signs (attribute-like).
    const proseToolPattern = /<([a-zA-Z_][a-zA-Z0-9_]*)\s+([^>]+?)>/g;
    let proseMatch;
    while ((proseMatch = proseToolPattern.exec(text)) !== null) {
      try {
        const toolName = proseMatch[1].trim();
        if (!knownToolNames.includes(toolName)) continue;
        const content = proseMatch[2];
        // Check if content looks like attributes (has = signs)
        const args: any = {};
        // Handle both quoted and unquoted attr values: attr=value attr="value"
        const looseAttrPattern = /([a-zA-Z_][a-zA-Z0-9_]*)\s*=\s*(?:"([^"]*?)"|'([^']*?)'|(\S+))/g;
        let lMatch;
        while ((lMatch = looseAttrPattern.exec(content)) !== null) {
          const key = lMatch[1];
          const value = lMatch[2] || lMatch[3] || lMatch[4];
          if (value === 'true') args[key] = true;
          else if (value === 'false') args[key] = false;
          else if (/^-?\d+$/.test(value)) args[key] = parseInt(value, 10);
          else args[key] = value;
        }
        // Even if no args parsed, a known tool name in angle brackets is a signal
        if (Object.keys(args).length > 0) {
          return [{
            id: `call_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`,
            type: 'function',
            function: { name: toolName, arguments: JSON.stringify(args) }
          }];
        }
      } catch (e: any) {}
    }

    // Strategy 8: Inline JSON tool call prefixed by prose (e.g. "Executed: {\"name\":...}")
    // The DeepSeek model sometimes outputs prose followed by a JSON object with
    // "name" and "arguments" keys directly in the text (not in code fences).
    // Match: word-boundary, "name":, optional whitespace, "toolName", then
    // capture everything from the opening { to the balancing }.
    const prosePrefixJsonPattern = /\b(Executed|Calling|Running|I(?:'ll| will)\s+(?:call|run|use))\s*[:\s]*(\{\s*"name"\s*:\s*"([^"]+)"[\s\S]+?\})/gi;
    let ppMatch;
    while ((ppMatch = prosePrefixJsonPattern.exec(text)) !== null) {
      try {
        const jsonCandidate = ppMatch[2];
        const toolName = ppMatch[3];
        // Balance braces to extract the complete JSON object
        let braceCount = 0;
        let jsonStr = '';
        let foundBrace = false;
        const startIndex = text.indexOf(jsonCandidate);
        if (startIndex === -1) continue;
        for (let i = startIndex; i < text.length; i++) {
          const char = text[i];
          if (char === '{') { braceCount++; foundBrace = true; }
          if (char === '}') braceCount--;
          jsonStr += char;
          if (foundBrace && braceCount === 0) break;
        }
        if (jsonStr && jsonStr.includes('"arguments"')) {
          const toolCallObj = JSON.parse(jsonStr);
          if (toolCallObj.name && typeof toolCallObj.name === 'string') {
            let toolArgs = toolCallObj.arguments || {};
            let finalArgs = toolArgs;
            if (typeof toolArgs === 'object') {
              finalArgs = JSON.stringify(toolArgs);
            }
            return [{
              id: `call_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`,
              type: 'function',
              function: { name: toolCallObj.name, arguments: finalArgs }
            }];
          }
        }
      } catch (e: any) {}
    }

    return [];
  }

  // ---- Strip tool call blocks from display text ----

  private stripToolCallFromText(text: string): string {
    let result = text;
    // Remove <file_action> blocks
    result = result.replace(/<file_action>[\s\S]*?<\/file_action>/g, '');
    // Remove bare XML tool tags (self-closing or open/close)
    result = result.replace(/<(?:search_files|list_directory|read_file|get_file_context|write_file|apply_edits|run_terminal|run_build|git_commit|git_status|git_diff|search_content)\b[^>]*\/?\s*>/gi, '');
    // Remove XML tool open/close blocks with content
    result = result.replace(/<(?:run_terminal|write_file)\b[^>]*>[\s\S]*?<\/(?:run_terminal|write_file)>/gi, '');
    // Remove JSON code blocks
    result = result.replace(/```(?:json)?\s*\n?\s*\{[\s\S]*?\}\s*```/g, '');
    // Remove brace-balanced JSON tool blocks
    result = result.replace(/\n\{\s*"name"\s*:\s*"[^"]+"[\s\S]*?\n\}/g, '');
    // Remove "Calling:" patterns
    result = result.replace(/Calling:\s*\w+\s*\n?\s*```[\s\S]*?```/gi, '');
    // Remove inline JSON with tool keys
    result = result.replace(/\n?\s*\{[^}]*"(?:path|command|pattern|edits)"[\s\S]*?\n?\}/g, '');
    // Clean up triple+ newlines
    result = result.replace(/\n{3,}/g, '\n\n');
    return result.trim();
  }

  private log(message: string, ...args: any[]): void {
    const line = args.length > 0 ? `${message} ${args.map(a => typeof a === 'string' ? a : JSON.stringify(a)).join(' ')}` : message;
    if (this.outputChannel) {
      this.outputChannel.appendLine(line);
    }
    console.log(line);
  }

  // ---- Server error tracking ----

  async isAvailable(): Promise<boolean> {
    try {
      const response = await fetch(`${this.baseUrl}/v1/models`, {
        method: 'GET',
        headers: { 'Content-Type': 'application/json' }
      });
      return response.ok;
    } catch (error) {
      return false;
    }
  }

  async listModels(): Promise<string[]> {
    try {
      const response = await fetch(`${this.baseUrl}/v1/models`, {
        method: 'GET',
        headers: { 'Content-Type': 'application/json' }
      });
      if (!response.ok) {
        return [];
      }
      const data = await response.json();
      return data.data.map((m: any) => m.id);
    } catch (error) {
      return [];
    }
  }

  private shouldRetryAfterServerError(): boolean {
    const now = Date.now();
    if (now - this.lastServerErrorTime > this.SERVER_ERROR_COOLDOWN) {
      this.serverErrorCount = 0;
      this.lastServerErrorTime = 0;
    }
    if (this.serverErrorCount >= this.MAX_SERVER_ERRORS) {
      return false;
    }
    return true;
  }

  private recordServerError(): void {
    const now = Date.now();
    if (now - this.lastServerErrorTime > this.SERVER_ERROR_COOLDOWN) {
      this.serverErrorCount = 0;
    }
    this.serverErrorCount++;
    this.lastServerErrorTime = now;
    console.log(`Server error recorded (count: ${this.serverErrorCount}/${this.MAX_SERVER_ERRORS})`);
  }

  private isServerConnectivityError(error: any): boolean {
    if (!error || !error.message) return false;
    const message = error.message.toLowerCase();
    return message.includes('500 internal server error') ||
           message.includes('connection refused') ||
           message.includes('econnrefused') ||
           message.includes('fetch failed') ||
           message.includes('host not found') ||
           message.includes('enotfound') ||
           message.includes('getaddrinfo') ||
           message.includes('network error');
  }

  private isRetryableError(error: any): boolean {
    if (!error || !error.message) return false;
    const message = error.message.toLowerCase();
    // Retry on transient errors: network issues, server errors, timeouts
    return message.includes('connection refused') ||
           message.includes('econnrefused') ||
           message.includes('fetch failed') ||
           message.includes('network error') ||
           message.includes('500') ||
           message.includes('502') ||
           message.includes('503') ||
           message.includes('504') ||
           message.includes('timed out') ||
           message.includes('abort');
  }

  private calculateRetryDelay(attempt: number): number {
    // Exponential backoff: baseDelay * 2^attempt + jitter
    const exponentialDelay = this.BASE_RETRY_DELAY_MS * Math.pow(2, attempt);
    // Add jitter: ±20% randomization to prevent thundering herd
    const jitter = (Math.random() - 0.5) * 0.4 * exponentialDelay;
    const delay = Math.min(exponentialDelay + jitter, this.MAX_RETRY_DELAY_MS);
    return Math.max(0, Math.round(delay));
  }

  private sleep(ms: number): Promise<void> {
    return new Promise(resolve => setTimeout(resolve, ms));
  }
}
