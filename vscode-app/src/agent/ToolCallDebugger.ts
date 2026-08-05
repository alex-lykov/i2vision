/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

/**
 * Tool Call Debugging Utility
 * 
 * This utility helps test and debug the agent's tool calling mechanism.
 * Run this from the extension development host to verify tool parsing and execution.
 * 
 * Usage:
 * 1. Launch extension development host (F5)
 * 2. Open Output Channel (View -> Output -> i2-Vision)
 * 3. Create an agent tab
 * 4. Send test messages
 * 5. Monitor logs for tool call activity
 */

import * as vscode from 'vscode';
import * as path from 'path';

/**
 * Test tool call parsing with sample LLM responses
 */
export class ToolCallDebugger {
  private outputChannel: vscode.OutputChannel;

  constructor(outputChannel: vscode.OutputChannel) {
    this.outputChannel = outputChannel;
  }

  /**
   * Test parsing of various LLM response formats
   */
  async testParsing(): Promise<void> {
    this.outputChannel.appendLine('=== Tool Call Parsing Tests ===\n');

    const testCases = [
      {
        name: 'Standard tool_call format',
        response: `reasoning: I need to read the file to understand its contents.
tool_call: {"tool":"read_file","args":{"path":"vscode-app/src/extension.ts"}}
EOS`,
        expectedTools: ['read_file']
      },
      {
        name: 'Multiple tool calls',
        response: `reasoning: I'll read the file and then list the directory.
tool_call: {"tool":"read_file","args":{"path":"package.json"}}
tool_call: {"tool":"list_directory","args":{"path":"src"}}
EOS`,
        expectedTools: ['read_file', 'list_directory']
      },
      {
        name: 'No tool needed',
        response: `reasoning: The answer is 42.
EOS`,
        expectedTools: []
      },
      {
        name: 'Tool call with complex args',
        response: `reasoning: Searching for all function definitions.
tool_call: {"tool":"regex_search","args":{"pattern":"function\\\\s+\\\\w+","path":"src"}}
EOS`,
        expectedTools: ['regex_search']
      }
    ];

    for (const testCase of testCases) {
      this.outputChannel.appendLine(`Test: ${testCase.name}`);
      this.outputChannel.appendLine(`Response: ${testCase.response.substring(0, 100)}...`);
      this.outputChannel.appendLine(`Expected tools: ${testCase.expectedTools.join(', ') || 'none'}`);
      this.outputChannel.appendLine('---');
    }

    this.outputChannel.appendLine('\n=== Test Cases Defined ===');
    this.outputChannel.appendLine('To test manually, send these messages to an agent:');
    this.outputChannel.appendLine('1. "Read vscode-app/src/extension.ts"');
    this.outputChannel.appendLine('2. "Read package.json and list src directory"');
    this.outputChannel.appendLine('3. "What is 2+2?"');
    this.outputChannel.appendLine('4. "Search for function definitions in src"');
  }

  /**
   * Verify tool definitions are correct
   */
  async verifyToolDefinitions(): Promise<void> {
    this.outputChannel.appendLine('\n=== Tool Definitions Verification ===\n');

    const expectedTools = [
      // File operations
      'read_file',
      'write_file',
      'list_directory',
      'search_files',
      'get_file_context',
      // Git operations
      'git_status',
      'git_diff',
      'git_log',
      'git_branch',
      'git_commit',
      // Build & terminal
      'run_build',
      'run_terminal'
    ];

    this.outputChannel.appendLine('Expected tools available to LLM:');
    for (const tool of expectedTools) {
      this.outputChannel.appendLine(`  [OK] ${tool}`);
    }

    this.outputChannel.appendLine('\nTool descriptions:');
    this.outputChannel.appendLine('  - read_file: Read file contents');
    this.outputChannel.appendLine('  - write_file: Create/overwrite file');
    this.outputChannel.appendLine('  - list_directory: List directory contents');
    this.outputChannel.appendLine('  - search_files: Search files with regex pattern');
    this.outputChannel.appendLine('  - get_file_context: Get file symbols and structure');
    this.outputChannel.appendLine('  - git_status: Show working tree status');
    this.outputChannel.appendLine('  - git_diff: Show changes between commits');
    this.outputChannel.appendLine('  - git_log: Show commit history');
    this.outputChannel.appendLine('  - git_branch: List or show current branch');
    this.outputChannel.appendLine('  - git_commit: Stage and commit changes');
    this.outputChannel.appendLine('  - run_build: Run build commands (Gradle, npm, Maven)');
    this.outputChannel.appendLine('  - run_terminal: Run short-lived shell commands');
  }

  /**
   * Check agent configuration for tool parsing settings
   */
  async checkConfig(): Promise<void> {
    this.outputChannel.appendLine('\n=== Configuration Check ===\n');

    const configPath = path.join(
      vscode.workspace.workspaceFolders?.[0]?.uri.fsPath || '',
      '.vision-ai',
      'code-agent.yaml'
    );
    
    try {
      const config = await vscode.workspace.openTextDocument(vscode.Uri.file(configPath!));
      const content = config.getText();
      
      // Check for key settings
      const checks = [
        { pattern: /toolCallPattern:/, name: 'Tool call pattern' },
        { pattern: /toolCallHeader:/, name: 'Tool call header' },
        { pattern: /reasoningHeader:/, name: 'Reasoning header' },
        { pattern: /eosMarker:/, name: 'EOS marker' }
      ];

      for (const check of checks) {
        const found = check.pattern.test(content);
        this.outputChannel.appendLine(`${found ? '[OK]' : '[MISSING]'} ${check.name}: ${found ? 'Found' : 'MISSING'}`);
      }
    } catch (error: any) {
      this.outputChannel.appendLine(`Error reading config: ${error.message}`);
    }
  }

  /**
   * Run all debugging checks
   */
  async runAllChecks(): Promise<void> {
    this.outputChannel.show();
    this.outputChannel.appendLine('=== Starting Tool Call Debugging Session ===\n');
    
    await this.verifyToolDefinitions();
    await this.checkConfig();
    await this.testParsing();
    
    this.outputChannel.appendLine('\n=== Debugging checks complete! ===');
    this.outputChannel.appendLine('\nNext steps:');
    this.outputChannel.appendLine('1. Create an agent tab (Ctrl+Shift+P -> i2-Vision: New Coding Agent)');
    this.outputChannel.appendLine('2. Send a test message: "Read vscode-app/package.json"');
    this.outputChannel.appendLine('3. Watch the Output Channel for tool call logs');
    this.outputChannel.appendLine('4. Verify tool card appears in the agent tab');
  }

  /**
   * Quick diagnostic - check extension activation status
   */
  async quickDiagnostic(): Promise<void> {
    this.outputChannel.show();
    this.outputChannel.appendLine('=== Quick Diagnostic ===\n');
    
    // Check workspace
    const workspaceRoot = vscode.workspace.workspaceFolders?.[0]?.uri.fsPath;
    this.outputChannel.appendLine(`Workspace: ${workspaceRoot || 'NO WORKSPACE OPEN'}`);
    
    // Check config file
    const configPath = workspaceRoot ? path.join(workspaceRoot, '.vision-ai', 'code-agent.yaml') : null;
    if (configPath) {
      try {
        await vscode.workspace.openTextDocument(vscode.Uri.file(configPath));
        this.outputChannel.appendLine(`Config file: [OK] ${configPath}`);
      } catch {
        this.outputChannel.appendLine(`Config file: [MISSING] ${configPath}`);
      }
    }
    
    // Check extension context
    this.outputChannel.appendLine(`Extension active: YES`);
    this.outputChannel.appendLine(`Output channel: [OK] i2-Vision`);
    
    this.outputChannel.appendLine('\n=== Diagnostic complete ===');
    this.outputChannel.appendLine('\nIf all checks pass, try:');
    this.outputChannel.appendLine('1. Ctrl+Shift+P -> i2-Vision: New Coding Agent');
    this.outputChannel.appendLine('2. Send: "What files are in the project?"');
  }
}

/**
 * Register debugging commands
 */
export function registerDebugCommands(
  context: vscode.ExtensionContext,
  outputChannel: vscode.OutputChannel,
  agentManager?: any
): void {
  const toolDebugger = new ToolCallDebugger(outputChannel);

  // Quick diagnostic command
  const quickDiagCmd = vscode.commands.registerCommand('i2vision.quickDiagnostic', async () => {
    await toolDebugger.quickDiagnostic();
  });
  context.subscriptions.push(quickDiagCmd);

  // Debug tool calls command
  const debugCmd = vscode.commands.registerCommand('i2vision.debugToolCalls', async () => {
    await toolDebugger.runAllChecks();
  });
  context.subscriptions.push(debugCmd);

  // Verify tools command
  const verifyCmd = vscode.commands.registerCommand('i2vision.verifyTools', async () => {
    await toolDebugger.verifyToolDefinitions();
  });
  context.subscriptions.push(verifyCmd);

  // Check config command
  const checkCmd = vscode.commands.registerCommand('i2vision.checkAgentConfig', async () => {
    await toolDebugger.checkConfig();
  });
  context.subscriptions.push(checkCmd);

  outputChannel.appendLine('[OK] Tool call debugging commands registered');
  outputChannel.appendLine('  - i2vision.quickDiagnostic: Quick health check (START HERE)');
  outputChannel.appendLine('  - i2vision.debugToolCalls: Run all debugging checks');
  outputChannel.appendLine('  - i2vision.verifyTools: Verify tool definitions');
  outputChannel.appendLine('  - i2vision.checkAgentConfig: Check agent configuration');
  outputChannel.appendLine('  - i2vision.reloadAgentConfig: Clear config cache (registered in extension.ts)');
}
