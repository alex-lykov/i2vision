/*
* Copyright (c) 2026.Oleksii Lykov.*
* Licensed under the MIT License.* SPDX-License-Identifier: MIT
*/
/**
* AgentBridge.LegacyTools - Legacy tool wrappers for backward compatibility.*
* Extracted from AgentBridge.ts.Provides:
*   - run_command (legacy wrapper around runTerminal)
*   - run_build (legacy wrapper around build execution)
*   - git_commit (legacy wrapper around git operations)
*   - Tool name mapping for older tool call patterns
*
* Dependencies: CommandExecutor (for terminal/build/git execution)
*/
import { CommandExecutor, CommandResult, BuildResult } from './AgentBridge.CommandExecutor';
import { Diagnostics } from './AgentBridge.Diagnostics';
export interface LegacyToolResult {
success: boolean;
output: string;
error?: string;
toolName: string;
durationMs: number;
}
export class LegacyTools {
private executor: CommandExecutor;
private diag: Diagnostics;
/** Mapping of legacy tool names to current tool names */
private static readonly LEGACY_TOOL_MAP: Record<string, string> = {
'run_command': 'run_terminal',
'execute_command': 'run_terminal',
'shell_exec': 'run_terminal',
'compile': 'run_build',
'build': 'run_build',
};
constructor(executor: CommandExecutor, diag: Diagnostics) {
this.executor = executor;
this.diag = diag;
}
/** Map a legacy tool name to its current equivalent */
static mapLegacyToolName(toolName: string): string {
return LegacyTools.LEGACY_TOOL_MAP[toolName] || toolName;
}
// --- Legacy run_command ---
/** Legacy run_command: executes a shell command (wraps runTerminal) */
async runCommand(
command: string,
workingDir?: string,
timeoutSeconds?: number
): Promise<LegacyToolResult> {
const startTime = Date.now();
this.diag.log(`[LegacyTools] run_command: "${command}"`);
const result: CommandResult = await this.executor.runTerminal(
command,
workingDir,
timeoutSeconds || 30
);
const durationMs = Date.now() - startTime;
const output = result.stdout || result.stderr || '';
return {
success: result.exitCode === 0,
output: output.substring(0, 4000), // Truncate for LLM context
error: result.exitCode !== 0 ?result.stderr : undefined,
toolName: 'run_command',
durationMs
};
}
// --- Legacy run_build ---
/** Legacy run_build: executes a build command */
async runBuild(
buildCommand?: string,
timeoutSeconds?: number
): Promise<LegacyToolResult> {
const startTime = Date.now();
const command = buildCommand || (process.platform === 'win32'
?'.\\gradlew compileKotlin'
: './gradlew compileKotlin');
this.diag.log(`[LegacyTools] run_build: "${command}"`);
const result: BuildResult = await this.executor.runBuild(command, timeoutSeconds || 120);
const durationMs = Date.now() - startTime;
return {
success: result.success,
output: result.output.substring(0, 4000),
error: !result.success ?`Build failed with exit code ${result.exitCode}` : undefined,
toolName: 'run_build',
durationMs
};
}
// --- Legacy git_commit ---
/** Legacy git_commit: stages and commits files */
async gitCommit(
message: string,
files?: string[]
): Promise<LegacyToolResult> {
const startTime = Date.now();
this.diag.log(`[LegacyTools] git_commit: "${message}"`);
const result: CommandResult = await this.executor.gitCommit(message, files);
const durationMs = Date.now() - startTime;
return {
success: result.exitCode === 0,
output: result.stdout || result.stderr || 'Commit successful',
error: result.exitCode !== 0 ?result.stderr : undefined,
toolName: 'git_commit',
durationMs
};
}
// --- Utility: Run tool by name ---
/** Dispatch a legacy tool call by name */
async executeLegacyTool(
toolName: string,
args: Record<string, any>
): Promise<LegacyToolResult> {
const mappedName = LegacyTools.mapLegacyToolName(toolName);
this.diag.log(`[LegacyTools] Executing: ${toolName} -> ${mappedName}`);
switch (mappedName) {
case 'run_terminal':
return this.runCommand(
args.command || args.cmd,
args.working_dir || args.workingDir || args.cwd,
args.timeout || args.timeoutSeconds
);
case 'run_build':
return this.runBuild(
args.command || args.cmd,
args.timeout || args.timeoutSeconds
);
case 'git_commit':
return this.gitCommit(
args.message,
args.files
);
default:
this.diag.log(`[LegacyTools] Unknown legacy tool: ${toolName}`, 'warn');
return {
success: false,
output: '',
error: `Unknown legacy tool: ${toolName}.Use run_terminal, run_build, or git_commit.`,
toolName,
durationMs: 0
};
}
}
}