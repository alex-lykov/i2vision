/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

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
import {exec} from 'child_process';

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
    createdAt?: number;
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
     * For build commands, captures initial output and returns build results.
     * For other commands, returns immediately after starting terminal.
     * 
     * @param name - Terminal name (e.g., "backend", "frontend")
     * @param command - Command to run
     * @param workingDir - Working directory
     * @param restartOnChanges - Auto-restart when source files change
     * @returns Status message or build results
     */
    async runInTerminal(
        name: string,
        command: string,
        workingDir: string,
        restartOnChanges: boolean = false
    ): Promise<string> {
        this.log(`runInTerminal: name="${name}", command="${command}", restartOnChanges=${restartOnChanges}`);
        
        // Kill existing terminal with same name
        if (this.terminals.has(name)) {
            this.log(`Terminal "${name}" already exists - killing it first`);
            this.killTerminal(name);
        }
        
        // Normalize Windows commands that need .\ prefix for local executables
        // PowerShell requires .\ for executables in current directory
        let normalizedCommand = command;
        if (process.platform === 'win32') {
            normalizedCommand = command.replace(
                /^(gradlew|mvnw|gradlew\.bat|mvnw\.cmd|\.\/gradlew|\.\/mvnw)\b/i,
                '.\\$1'
            );
            // Also handle commands starting with ./ on Windows
            normalizedCommand = normalizedCommand.replace(/^\.\//, '.\\');
        }
        
        this.log(`Normalized command: ${normalizedCommand}`);
        
        // Create new terminal
        // Prevent terminal sprawl: if we already have too many managed terminals, kill the oldest
        const MAX_MANAGED_TERMINALS = 3;
        if (this.terminals.size >= MAX_MANAGED_TERMINALS) {
            const oldest = [...this.terminals.entries()]
                .sort((a, b) => (a[1].createdAt || 0) - (b[1].createdAt || 0))[0];
            if (oldest) {
                this.log(`Max managed terminals reached (${this.terminals.size}) - killing oldest: ${oldest[0]}`);
                this.killTerminal(oldest[0]);
            }
        }

        const terminal = vscode.window.createTerminal({
            name: `i2-Vision: ${name}`,
            cwd: workingDir,
            shellPath: process.platform === 'win32' ? 'powershell.exe' : undefined
        });
        
        terminal.show(false); // Don't steal focus
        terminal.sendText(normalizedCommand);
        
        const managed: ManagedTerminal = {
            terminal,
            name,
            command,
            workingDir,
            restartOnChanges
            ,createdAt: Date.now()
        };
        
        // Set up file watcher for auto-restart
        if (restartOnChanges) {
            this.setupFileWatcher(managed);
        }
        
        this.terminals.set(name, managed);
        
        // For build commands: capture output and check for errors
        if (this.isBuildCommand(command)) {
            this.log(`Build command detected - capturing output for 15 seconds`);
            const output = await this.captureTerminalOutput(terminal, 15000);
            
            // Build: check for all failure indicators
            const hasFailure = 
                output.includes('BUILD FAILED') || 
                output.includes('FAILED') || 
                output.includes('exit value') || 
                output.includes('error:') ||
                output.includes('e: file:///') ||
                output.includes('Unresolved reference') ||
                output.includes('is not abstract') ||
                output.includes('must implement') ||
                output.includes('cannot find symbol');
            
            if (hasFailure) {
                const errors = this.extractBuildErrors(output);
                return `❌ BUILD FAILED\n\nErrors:\n${errors}\n\nFull output:\n${output.slice(-1000)}`;
            }
            
            return `✅ Build successful\n\n${output.slice(-500)}`;
        }
        
        // For SERVER commands: capture output to detect build failures, but don't
        // let the timeout kill the server. We capture for a limited time to check
        // for compilation errors, then return the result.
        if (this.isServerCommand(command)) {
            this.log(`Server command detected - capturing output for 15 seconds to check for build errors`);
            const output = await this.captureTerminalOutput(terminal, 15000);
            
            // Check for build failures in the captured output
            const hasFailure = 
                output.includes('BUILD FAILED') || 
                output.includes('FAILED') || 
                output.includes('error:') ||
                output.includes('e: file:///') ||
                output.includes('Unresolved reference');
            
            if (hasFailure) {
                const errors = this.extractBuildErrors(output);
                this.log(`Server build failed - returning errors to agent`);
                return `❌ BUILD FAILED\n\nErrors:\n${errors}\n\nFull output:\n${output.slice(-1000)}`;
            }
            
            const restartInfo = restartOnChanges ? ' (auto-restart on file changes)' : '';
            return `✅ Server starting in terminal "i2-Vision: ${name}"${restartInfo}\n\nServer is starting up. Use terminal_status to check status after 15-30 seconds.`;
        }
        
        const restartInfo = restartOnChanges ? ' (auto-restart on file changes)' : '';
        return `Terminal "i2-Vision: ${name}" started: ${normalizedCommand}${restartInfo}`;
    }

    /**
     * Check if command is a build command that should be monitored
     */
    private isBuildCommand(command: string): boolean {
        // Long-running commands (run/serve/watch) should NOT be treated as builds
        if (/\b(run|serve|server|start|watch)\b/i.test(command)) {
            return false;
        }
        return /gradlew|gradle|mvn|mvnw|npm run build|make|tsc|yarn build/i.test(command);
    }

    /**
     * Check if command is a server/run command that should be monitored for startup failures
     */
    private isServerCommand(command: string): boolean {
        return /\b(gradlew.*:run|run|serve|server|start)\b/i.test(command) && /gradlew|gradle/i.test(command);
    }

    /**
     * Capture terminal output for a specified duration by executing the command via child_process
     * This allows us to capture stdout/stderr for build commands
     */
    private async captureTerminalOutput(terminal: vscode.Terminal, timeoutMs: number): Promise<string> {
        // Get the command from the terminal's managed config
        let managedTerminal: ManagedTerminal | undefined;
        for (const [name, mt] of this.terminals.entries()) {
            if (mt.terminal === terminal) {
                managedTerminal = mt;
                break;
            }
        }
        
        if (!managedTerminal) {
            await new Promise(resolve => setTimeout(resolve, timeoutMs));
            return 'Terminal output capture not available';
        }
        
        const { command, workingDir } = managedTerminal;
        
        return new Promise<string>((resolve) => {
            this.log(`Executing command via child_process for output capture: ${command}`);
            
            // Execute command and capture output
            const child = exec(command, {
                cwd: workingDir,
                maxBuffer: 1024 * 1024, // 1MB buffer
                shell: process.platform === 'win32' ? 'powershell.exe' : '/bin/bash'
            });
            
            let stdout = '';
            let stderr = '';
            let timedOut = false;
            
            // Set timeout
            const timeout = setTimeout(() => {
                timedOut = true;
                child.kill('SIGTERM');
                this.log(`Command timed out after ${timeoutMs}ms`);
            }, timeoutMs);
            
            child.stdout?.on('data', (data: Buffer) => {
                stdout += data.toString();
            });
            
            child.stderr?.on('data', (data: Buffer) => {
                stderr += data.toString();
            });
            
            child.on('close', (code) => {
                clearTimeout(timeout);
                if (!timedOut) {
                    this.log(`Command completed with exit code: ${code}`);
                    resolve(stdout + stderr);
                } else {
                    // If timed out, resolve with whatever output we captured
                    // This prevents the promise from hanging forever
                    this.log(`Command timed out - resolving with captured output`);
                    resolve(stdout + stderr);
                }
            });
            
            child.on('error', (err) => {
                clearTimeout(timeout);
                if (!timedOut) {
                    this.log(`Command execution error: ${err.message}`);
                    resolve(`Error executing command: ${err.message}`);
                } else {
                    // If already timed out, resolve with captured output
                    this.log(`Command error after timeout: ${err.message}`);
                    resolve(stdout + stderr);
                }
            });
        });
    }

    /**
     * Extract build errors from output with specific file paths and error messages
     * This helps the LLM understand exactly which files need to be fixed
     */
    private extractBuildErrors(output: string): string {
        const lines = output.split('\n');
        const errors: string[] = [];
        const seenErrors = new Set<string>(); // Deduplicate errors
        
        for (let i = 0; i < lines.length; i++) {
            const line = lines[i];
            let errorText: string | null = null;
            
            // Kotlin compilation errors: "e: file:///path:line:col error message"
            if (line.startsWith('e: file:///') || line.startsWith('e: /')) {
                const cleanError = line.replace(/^e: file:\/\//, '').replace(/^e: /, '');
                if (!seenErrors.has(cleanError)) {
                    errors.push(cleanError);
                    seenErrors.add(cleanError);
                }
            }
            // Java compilation errors: "path/to/File.java:line: error: message"
            else if (/^[^:]+:\d+:\s*error:/i.test(line)) {
                const cleanError = line.trim();
                if (!seenErrors.has(cleanError)) {
                    errors.push(cleanError);
                    seenErrors.add(cleanError);
                }
            }
            // Gradle task failures with file info
            else if (line.includes('FAILED') && (line.includes('.kt') || line.includes('.java'))) {
                const cleanError = line.trim();
                if (!seenErrors.has(cleanError)) {
                    errors.push(cleanError);
                    seenErrors.add(cleanError);
                }
            }
            // Common error patterns
            else if (
                line.includes('Unresolved reference') ||
                line.includes('is not abstract') ||
                line.includes('must implement') ||
                line.includes('cannot find symbol') ||
                line.includes('package does not exist') ||
                line.includes('incompatible types') ||
                line.includes('cannot resolve')
            ) {
                const cleanError = line.trim();
                if (!seenErrors.has(cleanError)) {
                    errors.push(cleanError);
                    seenErrors.add(cleanError);
                }
            }
            // Look at next line after "FAILED" for error details
            else if (line.includes('FAILED') && i + 1 < lines.length) {
                const nextLine = lines[i + 1].trim();
                if (nextLine && !nextLine.startsWith('>') && nextLine.length > 10) {
                    if (!seenErrors.has(nextLine)) {
                        errors.push(nextLine);
                        seenErrors.add(nextLine);
                    }
                }
            }
        }
        
        // If no structured errors found, return last 500 chars of output
        if (errors.length === 0) {
            return output.slice(-500);
        }
        
        // Return first 15 unique errors, deduplicated
        return errors.slice(0, 15).join('\n');
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

        // If a terminal has restarted repeatedly, consider it stale and kill it to
        // avoid terminal sprawl. After 3 restarts we stop recreating it.
        if (restartCount > 3) {
            this.log(`Terminal "${name}" exceeded restart limit (${restartCount}) - killing instead of restarting`);
            // Dispose and remove completely
            managed.terminal.dispose();
            managed.watcher?.dispose();
            if (managed.restartTimeout) {
                clearTimeout(managed.restartTimeout);
            }
            this.terminals.delete(name);
            return `Terminal "i2-Vision: ${name}" killed after ${restartCount} restarts to prevent sprawl.`;
        }

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
