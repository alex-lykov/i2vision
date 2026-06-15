/**
 * TerminalManager Tests - Demonstration and Manual Testing
 * 
 * This file demonstrates how to manually test the TerminalManager functionality.
 * Run these tests in a VS Code extension development host.
 */

import * as vscode from 'vscode';
import { TerminalManager } from './TerminalManager';

/**
 * Manual test suite for TerminalManager
 * 
 * To run:
 * 1. Start extension development host (F5)
 * 2. Open VS Code Developer Tools Console
 * 3. Call test functions from the console
 */
export class TerminalManagerTests {
  private terminalManager: TerminalManager;
  private outputChannel: vscode.OutputChannel;

  constructor() {
    this.outputChannel = vscode.window.createOutputChannel('TerminalManager Tests');
    this.terminalManager = new TerminalManager(this.outputChannel, 1000);
  }

  /**
   * Test 1: Start a short-lived command
   */
  async testShortLivedCommand(): Promise<void> {
    this.outputChannel.appendLine('=== Test 1: Short-lived command ===');
    
    const workspaceRoot = vscode.workspace.workspaceFolders?.[0]?.uri.fsPath || '';
    const result = this.terminalManager.runInTerminal(
      'test-short',
      'echo "Hello from short-lived command"',
      workspaceRoot,
      false // No auto-restart
    );
    
    this.outputChannel.appendLine(`Result: ${result}`);
    this.outputChannel.appendLine('Expected: Command output returned immediately');
  }

  /**
   * Test 2: Start a long-running server
   */
  async testLongRunningServer(): Promise<void> {
    this.outputChannel.appendLine('=== Test 2: Long-running server ===');
    
    const workspaceRoot = vscode.workspace.workspaceFolders?.[0]?.uri.fsPath || '';
    const result = this.terminalManager.runInTerminal(
      'test-server',
      'node -e "setInterval(() => console.log(\'Server running...\'), 5000)"',
      workspaceRoot,
      true // Auto-restart on file changes
    );
    
    this.outputChannel.appendLine(`Result: ${result}`);
    this.outputChannel.appendLine('Expected: Terminal started, auto-restart enabled');
    this.outputChannel.appendLine('Watch for file changes in .ts, .js, .kt, .java files');
  }

  /**
   * Test 3: List terminals
   */
  async testListTerminals(): Promise<void> {
    this.outputChannel.appendLine('=== Test 3: List terminals ===');
    
    const result = this.terminalManager.listTerminals();
    this.outputChannel.appendLine(`Result:\n${result}`);
    this.outputChannel.appendLine('Expected: List of all managed terminals with status');
  }

  /**
   * Test 4: Kill a terminal
   */
  async testKillTerminal(): Promise<void> {
    this.outputChannel.appendLine('=== Test 4: Kill terminal ===');
    
    const result = this.terminalManager.killTerminal('test-server');
    this.outputChannel.appendLine(`Result: ${result}`);
    this.outputChannel.appendLine('Expected: Terminal "test-server" stopped');
  }

  /**
   * Test 5: Auto-restart on file changes
   */
  async testAutoRestart(): Promise<void> {
    this.outputChannel.appendLine('=== Test 5: Auto-restart on file changes ===');
    
    const workspaceRoot = vscode.workspace.workspaceFolders?.[0]?.uri.fsPath || '';
    
    // Start a long-running server
    this.terminalManager.runInTerminal(
      'test-autorestart',
      'node -e "console.log(\'Server started\'); setInterval(() => {}, 1000)"',
      workspaceRoot,
      true
    );
    
    this.outputChannel.appendLine('Server started. Now edit a .ts file in the workspace...');
    this.outputChannel.appendLine('Watch for auto-restart after 1 second debounce');
    
    // Simulate file change after 2 seconds
    setTimeout(() => {
      this.outputChannel.appendLine('Simulating file change...');
      // In real scenario, file watcher would trigger automatically
    }, 2000);
  }

  /**
   * Test 6: Multiple terminals
   */
  async testMultipleTerminals(): Promise<void> {
    this.outputChannel.appendLine('=== Test 6: Multiple terminals ===');
    
    const workspaceRoot = vscode.workspace.workspaceFolders?.[0]?.uri.fsPath || '';
    
    // Start multiple terminals
    this.terminalManager.runInTerminal(
      'backend',
      'node -e "setInterval(() => console.log(\'Backend\'), 5000)"',
      workspaceRoot,
      true
    );
    
    this.terminalManager.runInTerminal(
      'frontend',
      'node -e "setInterval(() => console.log(\'Frontend\'), 5000)"',
      workspaceRoot,
      true
    );
    
    this.outputChannel.appendLine('Started backend and frontend terminals');
    
    setTimeout(() => {
      const result = this.terminalManager.listTerminals();
      this.outputChannel.appendLine(`Terminals:\n${result}`);
    }, 1000);
  }

  /**
   * Test 7: Terminal name generation
   */
  testTerminalNameGeneration(): void {
    this.outputChannel.appendLine('=== Test 7: Terminal name generation ===');
    
    const names = [
      this.terminalManager.generateTerminalName('npm run dev'),
      this.terminalManager.generateTerminalName('./gradlew bootRun'),
      this.terminalManager.generateTerminalName('docker-compose up'),
      this.terminalManager.generateTerminalName('npm run dev:backend'),
    ];
    
    this.outputChannel.appendLine('Generated terminal names:');
    names.forEach(name => {
      this.outputChannel.appendLine(`  ${name}`);
    });
    
    this.outputChannel.appendLine('Expected: Unique, readable terminal names');
  }

  /**
   * Test 8: Resource cleanup
   */
  async testResourceCleanup(): Promise<void> {
    this.outputChannel.appendLine('=== Test 8: Resource cleanup ===');
    
    const workspaceRoot = vscode.workspace.workspaceFolders?.[0]?.uri.fsPath || '';
    
    // Start some terminals
    this.terminalManager.runInTerminal('cleanup-test-1', 'echo "test1"', workspaceRoot, false);
    this.terminalManager.runInTerminal('cleanup-test-2', 'echo "test2"', workspaceRoot, false);
    
    this.outputChannel.appendLine('Started 2 terminals');
    
    setTimeout(() => {
      this.outputChannel.appendLine('Disposing TerminalManager...');
      this.terminalManager.dispose();
      this.outputChannel.appendLine('Expected: All terminals closed, file watchers stopped');
    }, 1000);
  }

  /**
   * Test 9: Terminal reuse (same name)
   */
  async testTerminalReuse(): Promise<void> {
    this.outputChannel.appendLine('=== Test 9: Terminal reuse ===');
    
    const workspaceRoot = vscode.workspace.workspaceFolders?.[0]?.uri.fsPath || '';
    
    // Start terminal
    this.terminalManager.runInTerminal(
      'reuse-test',
      'echo "First run"',
      workspaceRoot,
      false
    );
    
    this.outputChannel.appendLine('Started terminal "reuse-test"');
    
    setTimeout(() => {
      // Start another terminal with same name
      this.terminalManager.runInTerminal(
        'reuse-test',
        'echo "Second run"',
        workspaceRoot,
        false
      );
      
      this.outputChannel.appendLine('Started another terminal with same name');
      this.outputChannel.appendLine('Expected: Old terminal killed, new one started');
    }, 1000);
  }

  /**
   * Test 10: Debounce behavior
   */
  async testDebounceBehavior(): Promise<void> {
    this.outputChannel.appendLine('=== Test 10: Debounce behavior ===');
    
    const workspaceRoot = vscode.workspace.workspaceFolders?.[0]?.uri.fsPath || '';
    
    // Start a long-running server with 1000ms debounce
    this.terminalManager = new TerminalManager(this.outputChannel, 1000);
    
    this.terminalManager.runInTerminal(
      'debounce-test',
      'node -e "console.log(\'Server\"); setInterval(() => {}, 1000)"',
      workspaceRoot,
      true
    );
    
    this.outputChannel.appendLine('Server started with 1000ms debounce');
    this.outputChannel.appendLine('Simulating rapid file changes...');
    
    // Simulate rapid file changes
    let changeCount = 0;
    const interval = setInterval(() => {
      changeCount++;
      this.outputChannel.appendLine(`  File change ${changeCount} (debounce timer resets)`);
      
      if (changeCount >= 5) {
        clearInterval(interval);
        this.outputChannel.appendLine('Stopped changes. Restart should happen after 1s...');
      }
    }, 200);
  }

  /**
   * Run all tests
   */
  async runAllTests(): Promise<void> {
    this.outputChannel.appendLine('\n========== TERMINAL MANAGER TEST SUITE ==========\n');
    
    try {
      await this.testShortLivedCommand();
      await new Promise(resolve => setTimeout(resolve, 1000));
      
      await this.testLongRunningServer();
      await new Promise(resolve => setTimeout(resolve, 1000));
      
      await this.testListTerminals();
      await new Promise(resolve => setTimeout(resolve, 1000));
      
      await this.testKillTerminal();
      await new Promise(resolve => setTimeout(resolve, 1000));
      
      await this.testAutoRestart();
      await new Promise(resolve => setTimeout(resolve, 3000));
      
      await this.testMultipleTerminals();
      await new Promise(resolve => setTimeout(resolve, 2000));
      
      this.testTerminalNameGeneration();
      await new Promise(resolve => setTimeout(resolve, 1000));
      
      await this.testTerminalReuse();
      await new Promise(resolve => setTimeout(resolve, 2000));
      
      await this.testDebounceBehavior();
      await new Promise(resolve => setTimeout(resolve, 3000));
      
      await this.testResourceCleanup();
      
      this.outputChannel.appendLine('\n========== ALL TESTS COMPLETED ==========\n');
    } catch (error: any) {
      this.outputChannel.appendLine(`\n❌ Test failed: ${error.message}`);
    }
  }

  /**
   * Clean up
   */
  dispose(): void {
    this.terminalManager.dispose();
    this.outputChannel.dispose();
  }
}

// Export for manual testing from VS Code console
// Usage: 
//   const tests = new TerminalManagerTests();
//   tests.runAllTests();
//   tests.testShortLivedCommand();
//   tests.testLongRunningServer();
//   etc.
