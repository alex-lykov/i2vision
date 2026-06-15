/**
 * TerminalManager - Manages persistent VS Code terminals for long-running processes
 * 
 * Handles:
 * - Creating named terminals for servers/watchers
 * - Auto-restart on file changes with debouncing
 * - Terminal lifecycle (start, stop, restart, list)
 * - Resource cleanup on extension dispose
 * 
 * Architecture:
 * - Terminals are named and reusable
 * - File watchers trigger auto-restart with configurable debounce
 * - Multiple terminals can run concurrently
 * - Clean disposal prevents resource leaks
 */

import * as vscode from 'vscode';
import * as path from 'path';

/**
 * Managed terminal instance
 */
interface ManagedTerminal {
    terminal: vscode.Terminal;
    name: string;
    command: string;
    workingDir: string;
    restartOnChanges: boolean;
    watcher?: vscode.FileSystemWatcher;
    restartTimeout?: NodeJS.Timeout;
    restartCount?: number;
}

/**
 * TerminalManager - Singleton for managing VS Code terminals
 */
export class TerminalManager {
    private terminals: Map<string, ManagedTerminal> = new Map();
    private outputChannel?: vscode.OutputChannel;
    private debounceMs: number = 1000; // Default 1 second debounce for Gradle projects

    constructor(outputChannel?: vscode.OutputChannel, debounceMs?: number) {
        this.outputChannel = outputChannel;
        if (debounceMs) {
            this.debounceMs = debounceMs;
        }
    }

    /**
     * Set debounce delay for auto-restart
     */
    setDebounce(delayMs: number): void {
        this.debounceMs = delayMs;
    }

    /**
     * Run a command in a named terminal. If a terminal with the same name exists,
     * kill it and create a new one.
     * 
     * @param name - Terminal name (e.g., "backend", "frontend")
     * @param command - Command to run
     * @param workingDir - Working directory
     * @param restartOnChanges - Auto-restart when source files change
     * @returns Status message
     */
    runInTerminal(
        name: string,
        command: string,
        workingDir: string,
        restartOnChanges: boolean = false
    ): string {
        this.log(`runInTerminal: name="${name}", command="${command}", restartOnChanges=${restartOnChanges}`);
        
        // Kill existing terminal with same name
        if (this.terminals.has(name)) {
            this.log(`Terminal "${name}" already exists - killing it first`);
            this.killTerminal(name);
        }
        
        // Create new terminal
        const terminal = vscode.window.createTerminal({
            name: `i2-Vision: ${name}`,
            cwd: workingDir,
            shellPath: process.platform === 'win32' ? 'powershell.exe' : undefined
        });
        
        terminal.show(false); // Don't steal focus
        terminal.sendText(command);
        
        const managed: ManagedTerminal = {
            terminal,
            name,
            command,
            workingDir,
            restartOnChanges
        };
        
        // Set up file watcher for auto-restart
        if (restartOnChanges) {
            this.setupFileWatcher(managed);
        }
        
        this.terminals.set(name, managed);
        
        const restartInfo = restartOnChanges ? ' (auto-restart on file changes)' : '';
        return `Terminal "i2-Vision: ${name}" started: ${command}${restartInfo}`;
    }

    /**
     * Set up file watcher for auto-restart on source file changes
     */
    private setupFileWatcher(managed: ManagedTerminal): void {
        // Watch common source file extensions
        const pattern = new vscode.RelativePattern(
            managed.workingDir,
            '**/*.{kt,kts,java,ts,tsx,js,jsx,py,go,rs,cpp,h,hpp}'
        );
        
        const watcher = vscode.workspace.createFileSystemWatcher(pattern);
        
        watcher.onDidChange((uri) => {
            this.log(`File changed: ${uri.fsPath} - scheduling restart of "${managed.name}"`);
            
            // Debounce - wait after last change before restarting
            if (managed.restartTimeout) {
                clearTimeout(managed.restartTimeout);
            }
            
            managed.restartTimeout = setTimeout(() => {
                this.log(`Debounced restart triggered for "${managed.name}"`);
                this.restartTerminal(managed.name);
            }, this.debounceMs);
        });
        
        // Also watch for file creation (new source files)
        watcher.onDidCreate((uri) => {
            this.log(`File created: ${uri.fsPath} - scheduling restart of "${managed.name}"`);
            
            if (managed.restartTimeout) {
                clearTimeout(managed.restartTimeout);
            }
            
            managed.restartTimeout = setTimeout(() => {
                this.restartTerminal(managed.name);
            }, this.debounceMs);
        });
        
        managed.watcher = watcher;
        this.log(`File watcher set up for terminal "${managed.name}"`);
    }

    /**
     * Kill and restart a terminal with the same command.
     * 
     * @param name - Terminal name
     * @returns Status message
     */
    restartTerminal(name: string): string {
        this.log(`restartTerminal: "${name}"`);
        
        const managed = this.terminals.get(name);
        if (!managed) {
            return `Terminal "${name}" not found. Start it first.`;
        }
        
        // Preserve config before disposal
        const { command, workingDir, restartOnChanges } = managed;
        const restartCount = (managed.restartCount || 0) + 1;
        
        // Dispose existing terminal and watcher
        managed.terminal.dispose();
        managed.watcher?.dispose();
        if (managed.restartTimeout) {
            clearTimeout(managed.restartTimeout);
        }
        
        // Create new terminal with same config
        this.runInTerminal(name, command, workingDir, restartOnChanges);
        
        // Update restart count
        const updated = this.terminals.get(name);
        if (updated) {
            updated.restartCount = restartCount;
        }
        
        return `Terminal "i2-Vision: ${name}" restarted (restart #${restartCount}).`;
    }

    /**
     * Kill a terminal and clean up its watcher.
     * 
     * @param name - Terminal name
     * @returns Status message
     */
    killTerminal(name: string): string {
        this.log(`killTerminal: "${name}"`);
        
        const managed = this.terminals.get(name);
        if (!managed) {
            return `Terminal "${name}" not running.`;
        }
        
        managed.terminal.dispose();
        managed.watcher?.dispose();
        if (managed.restartTimeout) {
            clearTimeout(managed.restartTimeout);
        }
        
        this.terminals.delete(name);
        return `Terminal "i2-Vision: ${name}" stopped.`;
    }

    /**
     * List all managed terminals.
     * 
     * @returns Formatted list of terminals
     */
    listTerminals(): string {
        if (this.terminals.size === 0) {
            return 'No managed terminals running.';
        }
        
        const lines = Array.from(this.terminals.entries()).map(([name, m]) => {
            const restartInfo = m.restartOnChanges ? ' [auto-restart: ON]' : '';
            return `• ${name}: ${m.command}${restartInfo}`;
        });
        
        return `Managed terminals:\n${lines.join('\n')}`;
    }

    /**
     * Get terminal by name (for external access)
     */
    getTerminal(name: string): ManagedTerminal | undefined {
        return this.terminals.get(name);
    }

    /**
     * Check if a terminal is running
     */
    isRunning(name: string): boolean {
        return this.terminals.has(name);
    }

    /**
     * Get terminal status info
     */
    getTerminalStatus(name: string): { running: boolean; command?: string; autoRestart?: boolean; restartCount?: number } | undefined {
        const managed = this.terminals.get(name);
        if (!managed) {
            return undefined;
        }
        return {
            running: true,
            command: managed.command,
            autoRestart: managed.restartOnChanges,
            restartCount: managed.restartCount || 0
        };
    }

    /**
     * Get all terminal names
     */
    getTerminalNames(): string[] {
        return Array.from(this.terminals.keys());
    }

    /**
     * Generate unique terminal name from command
     * Uses command hash + timestamp for uniqueness
     */
    generateTerminalName(command: string): string {
        // Extract meaningful part of command (first 20 chars, alphanumeric only)
        const hash = command
            .split(' ')[0] // Take first word (the command)
            .substring(0, 20)
            .replace(/[^a-zA-Z0-9]/g, '-');
        
        // Add timestamp suffix for uniqueness
        const timestamp = Date.now().toString(36);
        
        return `${hash}-${timestamp}`;
    }

    /**
     * Dispose all terminals and clean up resources
     * Call this on extension deactivate
     */
    dispose(): void {
        this.log(`Disposing TerminalManager - cleaning up ${this.terminals.size} terminals`);
        
        for (const [name] of this.terminals) {
            this.killTerminal(name);
        }
        
        this.terminals.clear();
        this.log('TerminalManager disposed');
    }

    /**
     * Log a message
     */
    private log(message: string): void {
        const timestamp = new Date().toLocaleTimeString();
        const formatted = `[${timestamp}] [TerminalManager] ${message}`;
        if (this.outputChannel) {
            this.outputChannel.appendLine(formatted);
        }
        console.log(formatted);
    }
}
