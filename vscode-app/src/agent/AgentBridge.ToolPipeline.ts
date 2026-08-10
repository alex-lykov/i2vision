/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

/**
 * AgentBridge.ToolPipeline - Tool execution, monitoring, validation, stats, and rate limiting.
 * Extracted from AgentBridge.ts.
 */

import * as fs from 'fs';
import { LLMToolCall } from '../cliIntegrationRefactored';
import { ToolContext } from './tools';
import { ToolResultCompressor } from './ToolResultCompressor';
import { SessionManagerBridge } from './AgentBridge.SessionManagerBridge';

// -- Shared state exposed by both AgentBridge and ToolPipeline -----------------

export interface ToolPipelineState {
  _toolExecutionQueue: Array<{ toolCall: LLMToolCall; resolve: (result: any) => void; reject: (error: any) => void }>;
  _activeToolExecutions: number;
  _lastToolExecutionTime: number;
  _toolExecutionRateLimit: number;
  _toolCallHistory: Array<{ toolName: string; iteration: number; timestamp?: number; hasError?: boolean; resultLength?: number }>;
  _consecutiveToolErrors: number;
  _toolExecutionStartTimes: Map<string, number>;
  currentIteration: number;
}

// -- Host interface – provided by AgentBridge at runtime ------------------------

export interface ToolPipelineHost {
  workspaceRoot: string;
  resolvePath(relativePath: string): string;
  readFileCached(filePath: string): Promise<string>;
  invalidateFileCache(filePath: string): void;
  cli: any;
  terminalManager: any;
  toolRegistry: any; // ToolRegistry
  toolCompressor: ToolResultCompressor;
  llmAdapter: any; // LLMAdapter
  sessionManager: any;
  sessionBridge: SessionManagerBridge;
  config: { model: { id: string } };

  // Mutable AgentBridge fields shared with the pipeline
  _forceActionMode: boolean;
  _hasCheckedRunningServers: boolean;
  _readFileCount: Map<string, number>;
  _autoNudge: string | null;
  _fileSnapshots: Map<string, string>;

  // Callbacks
  log(message: string, level?: 'info' | 'warn' | 'error'): void;
  emitProgress(event: any): void;

}

// -- Module --------------------------------------------------------------------

export class ToolPipeline {
  // Mutable shared state (accessible from AgentBridge)
  _toolExecutionQueue: Array<{ toolCall: LLMToolCall; resolve: (result: any) => void; reject: (error: any) => void }> = [];
  _activeToolExecutions: number = 0;
  _lastToolExecutionTime: number = 0;
  _toolExecutionRateLimit: number = 1000;
  _toolCallHistory: Array<{ toolName: string; iteration: number; timestamp?: number; hasError?: boolean; resultLength?: number }> = [];
  _consecutiveToolErrors: number = 0;
  _toolExecutionStartTimes: Map<string, number> = new Map();
  currentIteration: number = 1;

  private static readonly MAX_CONCURRENT_TOOLS = 3;
  private static readonly MAX_READS_PER_FILE = 3;
  private host!: ToolPipelineHost;

  /** Set the host reference. Called from AgentBridge constructor after both objects exist. */
  setHost(h: ToolPipelineHost): void {
    this.host = h;
  }

  private log(msg: string, level?: 'info' | 'warn' | 'error'): void {
    this.host.log(msg, level);
  }

  // ---- Tool Execution -------------------------------------------------------

  async executeTool(toolCall: LLMToolCall): Promise<{ result: string; error?: string }> {
    try {
      if (this.host._forceActionMode) {
        const allowedTools = ['apply_edits', 'write_file', 'run_terminal', 'run_build', 'git_commit'];
        if (!allowedTools.includes(toolCall.name)) {
          this.log(`TOOL FILTER BLOCKED: ${toolCall.name} not in action_only set. Allowed: ${allowedTools.join(', ')}`);
          return { result: '', error: `⚠️ TOOL NOT AVAILABLE: "${toolCall.name}" is blocked in action-only mode. You must use one of the following allowed tools: ${allowedTools.join(', ')}. Stop exploring and take action now.` };
        }
      }

      if (toolCall.name === 'read_file' || toolCall.name === 'get_file_context') {
        const filePath = this.host.resolvePath(toolCall.arguments.path);
        const count = this.host._readFileCount.get(filePath) || 0;
        if (count >= ToolPipeline.MAX_READS_PER_FILE) {
          this.log(`READ LIMIT: "${filePath}" already read ${count} times (via ${toolCall.name}). Forcing action.`);
          return { result: '', error: `⚠️ READ LIMIT: "${toolCall.arguments.path}" has been read ${count} times already. You have enough information. Use apply_edits or write_file to make changes. Stop reading and take action now.` };
        }
      }

      if (toolCall.name === 'run_terminal') {
        const command = toolCall.arguments.command as string;
        const isServerStartCommand = /gradlew.*:run|npm\s+(run\s+)?(dev|start)|yarn\s+(dev|start)|vite|next\s+dev|react-scripts\s+start/i.test(command);
        if (isServerStartCommand && !this.host._hasCheckedRunningServers) {
          this.host._hasCheckedRunningServers = true;
          const vscode = require('vscode');
          const allTerminals = vscode.window.terminals;
          const runningTerminals = allTerminals.filter((t: any) =>
            !t.exitStatus &&
            (t.name.toLowerCase().includes('gradlew') || t.name.toLowerCase().includes('npm') ||
             t.name.toLowerCase().includes('node') || t.name.toLowerCase().includes('vite') ||
             t.name.toLowerCase().includes('java'))
          );
          if (runningTerminals.length > 0) {
            this.log(`Pre-flight check: Found ${runningTerminals.length} existing terminal(s) that may be servers`);
            const terminalList = runningTerminals.map((t: any) => `- ${t.name}`).join('\n');
            const preflightMessage = `[AUTO] Found ${runningTerminals.length} existing terminal(s) that may be running servers:\n${terminalList}\n\n**Check if the server is already running before starting a new one.**\n\nUse list_all_terminals to inspect them, or check the browser/application to see if it's responding.`;
            this.host.sessionBridge.pendingMessages.push({ role: 'tool', content: preflightMessage, tool_call_id: `auto_preflight_${Date.now()}` });
            this.host._autoNudge = `⚠️ Found ${runningTerminals.length} existing terminal(s). Check list_all_terminals before starting a new server!`;
            return { result: `Pre-flight check: Found ${runningTerminals.length} existing terminal(s). Use list_all_terminals to inspect them before starting a new server.` };
          }
        }
      }

      const context: ToolContext = {
        workspaceRoot: this.host.workspaceRoot,
        resolvePath: (p: string) => this.host.resolvePath(p),
        runCommand: (cmd: string, timeout: number, cwd?: string) => {
          const { exec } = require('child_process');
          const opts: any = { timeout: timeout > 0 ? timeout : undefined };
          if (cwd) opts.cwd = cwd;
          return new Promise<any>((resolve, reject) => {
            const child = exec(cmd, opts, (error: any, stdout: string, stderr: string) => {
              resolve({ stdout: stdout || '', stderr: stderr || '', exitCode: error?.code || 0 });
            });
          });
        },
        readFile: (p: string) => this.host.readFileCached(p),
        writeFile: (p: string, c: string) => { this.host.invalidateFileCache(p); return this.host.cli.writeFile(p, c); },
        listFiles: (p: string, r: boolean) => this.host.cli.listFiles(p, r),
        searchFiles: (p: string, d?: string) => this.host.cli.searchFiles(p, d),
        getFileContext: (p: string) => this.host.cli.getContext(p),
        fileExists: async (p: string) => fs.existsSync(p),
        terminalManager: this.host.terminalManager,
        vscode: require('vscode'),
        log: (msg: string) => this.log(msg),
        emitProgress: (e: any) => this.host.emitProgress(e),
        fileSnapshots: this.host._fileSnapshots,
      };

      const rawResult = await this.host.toolRegistry.execute(toolCall.name, toolCall.arguments, context);

      if ((toolCall.name === 'read_file' || toolCall.name === 'get_file_context') && !rawResult.error) {
        const filePath = this.host.resolvePath(toolCall.arguments.path);
        const currentCount = this.host._readFileCount.get(filePath) || 0;
        this.host._readFileCount.set(filePath, currentCount + 1);
      }

      this.monitorToolExecution(toolCall, rawResult);

      if (rawResult.result && this.host.llmAdapter.providerCapabilities) {
        const compressed = this.host.toolCompressor.compress(rawResult.result);
        if (compressed.wasCompressed) {
          this.log(`Tool result compressed: ${compressed.originalLength} → ${compressed.compressedLength} chars (${compressed.technique})`);
        }
        return { result: compressed.compressed, error: rawResult.error };
      }

      return this.validateToolResult(rawResult, toolCall);
    } catch (error: any) {
      this.log(`  Tool error: ${error.message}`);
      return { result: '', error: error.message };
    }
  }

  // ---- Monitoring -----------------------------------------------------------

  monitorToolExecution(toolCall: LLMToolCall, result: { result: string; error?: string }): void {
    const now = Date.now();
    const toolName = toolCall.name;
    const hasError = !!result.error;
    const resultLength = result.result?.length || 0;

    if (!this._toolCallHistory) this._toolCallHistory = [];
    this._toolCallHistory.push({ toolName, timestamp: now, hasError, resultLength, iteration: this.currentIteration });
    if (this._toolCallHistory.length > 50) this._toolCallHistory = this._toolCallHistory.slice(-50);

    const status = hasError ? 'ERROR' : 'SUCCESS';
    const sizeInfo = resultLength > 1000 ? `${(resultLength / 1000).toFixed(1)}KB` : `${resultLength}B`;
    this.log(`[TOOL_MONITOR] ${status} | ${toolName} | ${sizeInfo} | Iteration ${this.currentIteration}`);

    if (!hasError && resultLength === 0) this.log(`[TOOL_MONITOR] ⚠️ Empty result from ${toolName} - potential issue`);
    if (hasError && this._consecutiveToolErrors >= 3) this.log(`[TOOL_MONITOR] ⚠️ Multiple consecutive tool errors (${this._consecutiveToolErrors}) - consider changing approach`);

    if (hasError) { this._consecutiveToolErrors = (this._consecutiveToolErrors || 0) + 1; }
    else { this._consecutiveToolErrors = 0; }
  }

  // ---- Validation -----------------------------------------------------------

  validateToolResult(result: { result: string; error?: string }, toolCall: LLMToolCall): { result: string; error?: string } {
    if (result.error) return result;
    if (!result.result || result.result.trim().length === 0) {
      const emptyResultTools = ['read_file', 'list_directory', 'search_files'];
      if (emptyResultTools.includes(toolCall.name)) {
        this.log(`⚠️ Empty result from tool ${toolCall.name} - this may indicate a file not found or permission issue`);
        return { result: result.result, error: `Empty result from ${toolCall.name} - file may not exist or may be inaccessible` };
      }
    }
    const errorPatterns = ['ENOENT', 'no such file', 'not found', 'permission denied', 'access denied', 'command not found', 'not recognized'];
    const resultLower = result.result.toLowerCase();
    for (const pattern of errorPatterns) {
      if (resultLower.includes(pattern)) {
        this.log(`⚠️ Potential error detected in tool result: ${result.result}`);
        return { result: result.result, error: `Tool ${toolCall.name} returned potential error: ${result.result}` };
      }
    }
    return result;
  }

  // ---- Rate Limiting --------------------------------------------------------

  async executeToolRateLimited(toolCall: LLMToolCall): Promise<{ result: string; error?: string }> {
    const now = Date.now();
    const timeSinceLastExecution = now - this._lastToolExecutionTime;
    if (timeSinceLastExecution < this._toolExecutionRateLimit) {
      const delay = this._toolExecutionRateLimit - timeSinceLastExecution;
      this.log(`Rate limiting: delaying tool execution by ${delay}ms`);
      await new Promise(resolve => setTimeout(resolve, delay));
    }
    if (this._activeToolExecutions >= ToolPipeline.MAX_CONCURRENT_TOOLS) {
      this.log(`Concurrency limit reached (${this._activeToolExecutions}/${ToolPipeline.MAX_CONCURRENT_TOOLS}), queuing tool call`);
      return new Promise((resolve, reject) => {
        this._toolExecutionQueue.push({ toolCall, resolve, reject });
        this.processToolQueue();
      });
    }
    this._activeToolExecutions++;
    this._lastToolExecutionTime = Date.now();
    try {
      return await this.executeToolWithRetry(toolCall);
    } finally {
      this._activeToolExecutions--;
      this.processToolQueue();
    }
  }

  // ---- Queue Processing -----------------------------------------------------

  processToolQueue(): void {
    if (this._toolExecutionQueue.length === 0) return;
    if (this._activeToolExecutions < ToolPipeline.MAX_CONCURRENT_TOOLS) {
      const nextItem = this._toolExecutionQueue.shift();
      if (nextItem) {
        this._activeToolExecutions++;
        this._lastToolExecutionTime = Date.now();
        this.executeToolWithRetry(nextItem.toolCall)
          .then(result => { nextItem.resolve(result); this._activeToolExecutions--; this.processToolQueue(); })
          .catch(error => { nextItem.reject(error); this._activeToolExecutions--; this.processToolQueue(); });
      }
    }
  }

  // ---- Retry Logic ----------------------------------------------------------

  async executeToolWithRetry(toolCall: LLMToolCall, maxRetries: number = 3): Promise<{ result: string; error?: string }> {
    let attempt = 0;
    let lastError = '';
    while (attempt < maxRetries) {
      attempt++;
      try {
        const result = await this.executeTool({ id: `retry_${Date.now()}`, name: toolCall.name, arguments: toolCall.arguments });
        if (result.error && this.host.sessionBridge.isSessionError(result.error)) {
          this.log(`Session error in tool result (attempt ${attempt}/${maxRetries})`);
          if (this.host.sessionBridge.isContextExhaustionError(result.error)) {
            this.log(`Context exhaustion detected in tool result: ${result.error}`);
            if (this.host.sessionManager && attempt < maxRetries) {
              const resetOk = await (this.host.sessionManager as any).resetSession(this.host.config.model.id, 'token_limit');
              if (resetOk) { this.log('Context exhaustion - session reset successful, retrying with clean context...'); this.host.sessionManager?.recordRetry(); continue; }
            }
          } else if (this.host.sessionManager && attempt < maxRetries) {
            const resetOk = await this.host.sessionManager.resetSession(this.host.config.model.id);
            if (resetOk) { this.log('Session reset after tool error, retrying...'); this.host.sessionManager?.recordRetry(); continue; }
          }
        }
        return result;
      } catch (error: any) {
        lastError = error.message;
        if (this.host.sessionBridge.isSessionError(lastError)) {
          this.log(`Session-level error (attempt ${attempt}/${maxRetries}): ${lastError}`);
          if (this.host.sessionBridge.isContextExhaustionError(lastError)) {
            this.log(`Context exhaustion detected in exception: ${lastError}`);
            if (this.host.sessionManager && attempt < maxRetries) {
              const resetOk = await (this.host.sessionManager as any).resetSession(this.host.config.model.id, 'token_limit');
              if (resetOk) { this.log('Context exhaustion - session reset successful, retrying tool with clean context...'); this.host.sessionManager?.recordRetry(); continue; }
            }
          } else if (this.host.sessionManager && attempt < maxRetries) {
            const resetOk = await this.host.sessionManager.resetSession(this.host.config.model.id);
            if (resetOk) { this.log('Session reset, retrying tool...'); this.host.sessionManager?.recordRetry(); continue; }
          }
        }
        if (lastError.includes('JSON') || lastError.includes('parse')) {
          this.log(`JSON error (attempt ${attempt}/${maxRetries})`);
          if (attempt < maxRetries) { this.host.sessionBridge.injectSimplifiedToolPrompt(toolCall); continue; }
        }
        throw error;
      }
    }
    return { result: '', error: `Tool failed after ${maxRetries} attempts: ${lastError}` };
  }

  // ---- Stats and Reporting --------------------------------------------------

  getToolExecutionStats(): {
    totalToolsExecuted: number; errorRate: number;
    recentTools: Array<{ toolName: string; success: boolean; durationMs?: number }>;
    consecutiveErrors: number; queueLength: number; activeExecutions: number;
  } {
    const totalTools = this._toolCallHistory.length;
    const errorCount = this._toolCallHistory.filter(t => t.hasError).length;
    const recentTools = this._toolCallHistory.slice(-10).map(t => ({
      toolName: t.toolName, success: !t.hasError,
      durationMs: t.timestamp ? Date.now() - t.timestamp : undefined
    }));
    return {
      totalToolsExecuted: totalTools, errorRate: totalTools > 0 ? errorCount / totalTools : 0,
      recentTools, consecutiveErrors: this._consecutiveToolErrors || 0,
      queueLength: this._toolExecutionQueue.length, activeExecutions: this._activeToolExecutions
    };
  }

  getCurrentToolStatus(): string {
    const stats = this.getToolExecutionStats();
    return `Tools: ${stats.totalToolsExecuted} executed, ${(stats.errorRate * 100).toFixed(1)}% error rate, ` +
           `${stats.activeExecutions} active, ${stats.queueLength} queued, ${stats.consecutiveErrors} consecutive errors`;
  }

  getToolExecutionReport(): string {
    const stats = this.getToolExecutionStats();
    let report = `=== TOOL EXECUTION REPORT ===\n`;
    report += `Total Tools Executed: ${stats.totalToolsExecuted}\n`;
    report += `Error Rate: ${(stats.errorRate * 100).toFixed(1)}%\n`;
    report += `Consecutive Errors: ${stats.consecutiveErrors}\n`;
    report += `Active Executions: ${stats.activeExecutions}\n`;
    report += `Queue Length: ${stats.queueLength}\n\n`;
    if (stats.recentTools.length > 0) {
      report += `Recent Tool Executions:\n`;
      stats.recentTools.forEach((tool, index) => {
        const status = tool.success ? '✅' : '❌';
        const duration = tool.durationMs ? `${tool.durationMs}ms` : 'N/A';
        report += `  ${index + 1}. ${status} ${tool.toolName} (${duration})\n`;
      });
    }
    if (stats.queueLength > 0) {
      report += `\nQueued Tools (${stats.queueLength}):\n`;
      this._toolExecutionQueue.forEach((item, index) => { report += `  ${index + 1}. ${item.toolCall.name}\n`; });
    }
    return report;
  }
}
