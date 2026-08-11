/*
* Copyright (c) 2026.Oleksii Lykov.*
* Licensed under the MIT License.* SPDX-License-Identifier: MIT
*/
/**
* AgentBridge.CommandExecutor - Command execution helpers.*
* Extracted from AgentBridge.ts.Provides:
*   - build command execution (compileKotlin, gradle build, etc.)
*   - terminal command execution via TerminalManager
*   - git commit execution
*   - command safety validation (blocked patterns, long-running detection)
*   - timeout handling and output capture
*/
import * as vscode from 'vscode';
import { TerminalManager } from './TerminalManager';
import { Diagnostics } from './AgentBridge.Diagnostics';
export interface CommandResult {
stdout: string;
stderr: string;
exitCode: number;
timedOut?: boolean;
}
export interface BuildResult {
success: boolean;
output: string;
exitCode: number;
durationMs: number;
}
export class CommandExecutor {
private diag: Diagnostics;
private terminalManager: TerminalManager;
private workspaceRoot: string;
private extensionRoot: string;
private readonly blockedPatterns: string[];
private readonly longRunningPatterns: string[];

static readonly DEFAULT_BLOCKED_PATTERNS = [
'rm -rf /', 'del /F /S /Q C:\\*', 'format', 'mkfs', 'dd if=/dev/zero'
];
static readonly DEFAULT_LONG_RUNNING_PATTERNS = [
'run', 'serve', 'dev', 'start', 'watch', 'nodemon', 'vite', 'next dev',
'spring-boot:run', 'jetty:run', 'webpack --watch', 'tsc --watch',
'gulp watch', 'grunt watch', 'cargo run', 'go run', 'python -m uvicorn', 'poetry run'
];
constructor(
diag: Diagnostics,
terminalManager: TerminalManager,
workspaceRoot: string,
extensionRoot: string,
customLongRunningPatterns?: string[]
) {
this.diag = diag;
this.terminalManager = terminalManager;
this.workspaceRoot = workspaceRoot;
this.extensionRoot = extensionRoot;
this.blockedPatterns = CommandExecutor.DEFAULT_BLOCKED_PATTERNS;
this.longRunningPatterns = customLongRunningPatterns && customLongRunningPatterns.length > 0
? customLongRunningPatterns
: CommandExecutor.DEFAULT_LONG_RUNNING_PATTERNS;
}
// --- Safety Validation ---
/** Check if a command matches blocked patterns (destructive operations) */
isBlockedCommand(command: string): boolean {
return this.blockedPatterns.some(pattern =>
command.toLowerCase().includes(pattern.toLowerCase())
);
}
/** Check if a command is a long-running server/process */
isLongRunningCommand(command: string): boolean {
return this.longRunningPatterns.some(pattern =>
command.toLowerCase().includes(pattern.toLowerCase())
);
}
/** Validate a command before execution */
validateCommand(command: string): { valid: boolean; reason?: string } {
if (!command || command.trim().length === 0) {
return { valid: false, reason: 'Empty command' };
}
if (this.isBlockedCommand(command)) {
this.diag.log(`BLOCKED: "${command}" matches destructive pattern`, 'error');
return { valid: false, reason: `Command blocked for safety: "${command}"` };
}
if (this.isLongRunningCommand(command)) {
this.diag.log(`WARNING: "${command}" appears to be a long-running process`, 'warn');
return { valid: true, reason: 'Long-running process detected; ensure terminal is available for output' };
}
return { valid: true };
}
// --- Terminal Execution ---
/** Execute a command in the managed terminal (for long-running processes) */
async runInTerminal(name: string, command: string, workingDir: string, restartOnChanges: boolean = false): Promise<string> {
const validation = this.validateCommand(command);
if (!validation.valid) {
return validation.reason || 'Command blocked';
}
this.diag.log(`Starting terminal "${name}": "${command}" in ${workingDir}`);
try {
const result = await this.terminalManager.runInTerminal(name, command, workingDir, restartOnChanges);
return result;
} catch (error: any) {
const errorMsg = error?.message || String(error);
this.diag.log(`Terminal "${name}" failed: ${errorMsg}`, 'error');
return `Error: ${errorMsg}`;
}
}
/** Execute a command and capture output (for short-lived commands) */
async runTerminal(command: string, cwd?: string, timeoutSeconds: number = 30): Promise<CommandResult> {
return this.runCommand(command, timeoutSeconds, cwd);
}
/** Run a command via child_process for simple cases (not via TerminalManager) */
async runCommand(cmd: string, timeoutSeconds: number = 30, cwd?: string): Promise<CommandResult> {
const validation = this.validateCommand(cmd);
if (!validation.valid) {
return { stdout: '', stderr: validation.reason || 'Command blocked', exitCode: 1 };
}
const { exec } = require('child_process');
const opts: any = { timeout: timeoutSeconds > 0 ?timeoutSeconds * 1000 : undefined };
if (cwd) opts.cwd = cwd;
this.diag.log(`Running command: "${cmd}"`);
return new Promise<CommandResult>((resolve) => {
const child = exec(cmd, opts, (error: any, stdout: string, stderr: string) => {
resolve({
stdout: stdout || '',
stderr: stderr || '',
exitCode: error?.code || 0,
timedOut: error?.killed || false
});
});
});
}
// --- Build Commands ---
/** Execute a build command (e.g., ./gradlew compileKotlin, npm run build) */
async runBuild(buildCommand: string, timeoutSeconds: number = 120): Promise<BuildResult> {
this.diag.log(`Running build: "${buildCommand}"`);
const startTime = Date.now();
const result = await this.runTerminal(buildCommand, this.workspaceRoot, timeoutSeconds);
const durationMs = Date.now() - startTime;
const success = result.exitCode === 0;
if (success) {
this.diag.log(`Build succeeded in ${durationMs}ms`, 'info');
} else {
this.diag.log(`Build failed (exit ${result.exitCode}) after ${durationMs}ms`, 'error');
}
return {
success,
output: result.stdout + (result.stderr ?'\n' + result.stderr : ''),
exitCode: result.exitCode,
durationMs
};
}
/** Execute a Gradle compile command */
async runGradleCompile(modulePath?: string): Promise<BuildResult> {
const moduleFlag = modulePath ?`:${modulePath}:` : '';
const command = process.platform === 'win32'
?`.\\gradlew ${moduleFlag}compileKotlin`
: `./gradlew ${moduleFlag}compileKotlin`;
return this.runBuild(command);
}
// --- Git Commands ---
/** Execute a git commit with the given message */
async gitCommit(message: string, files?: string[]): Promise<CommandResult> {
const fileArgs = files && files.length > 0 ?files.map(f => `"${f}"`).join(' ') : '-a';
const cmd = `git commit ${fileArgs} -m "${message.replace(/"/g, '\\"')}"`;
this.diag.log(`Git commit: "${message}"`);
return this.runCommand(cmd, 30, this.workspaceRoot);
}
/** Get git status */
async gitStatus(): Promise<string> {
const result = await this.runCommand('git status --porcelain', 10, this.workspaceRoot);
return result.stdout;
}
/** Get git diff */
async gitDiff(staged: boolean = false): Promise<string> {
const flag = staged ?'--staged' : '';
const result = await this.runCommand(`git diff ${flag}`, 15, this.workspaceRoot);
return result.stdout;
}
// --- Utility ---
/** Get the default Gradle compileKotlin command for the platform */
getDefaultCompileCommand(): string {
return process.platform === 'win32'
? '.\\gradlew :app:server:compileKotlin --console=plain'
: './gradlew :app:server:compileKotlin --console=plain';
}
/** Resolve a path relative to workspace root */
resolvePath(relativePath: string): string {
const path = require('path');
if (path.isAbsolute(relativePath)) return relativePath;
return path.join(this.workspaceRoot, relativePath);
}
/** Check if a file exists in the workspace */
fileExists(filePath: string): boolean {
const fs = require('fs');
const resolved = this.resolvePath(filePath);
return fs.existsSync(resolved);
}
}
