import { ToolDefinition, ToolContext } from '../ToolTypes';

/**
 * Get terminal mode from settings
 */
function getTerminalMode(ctx: ToolContext): 'managed' | 'vscode' | 'hybrid' {
  try {
    const settingsModule = require('../../AgentSettings');
    const settingsManager = settingsModule.AgentSettingsManager.getInstance();
    if (settingsManager) {
      const settings = settingsManager.getSettings();
      return settings.terminal.mode || 'hybrid';
    }
  } catch (e) {
    // Settings not available, default to hybrid
  }
  return 'hybrid';
}

/**
 * Terminal and process management tools
 */
export const terminalTools: ToolDefinition[] = [
  {
    name: 'run_terminal',
    description: 'Run a terminal command. FOR SERVERS: use gradlew :app:server:run. IMPORTANT: Servers take 10-30 seconds to start. Do NOT check terminal_status immediately - wait 15+ seconds first.',
    category: 'terminal',
    isReadOnly: false,
    requiresConfirmation: false,
    parameters: {
      type: 'object',
      properties: {
        command: { 
          type: 'string', 
          description: 'Shell command. For servers: gradlew :app:server:run' 
        },
        workingDir: { 
          type: 'string', 
          description: 'REQUIRED: Directory where command should run (e.g., "frontend", "backend", "app/server"). ALWAYS specify this for npm, yarn, vite, or framework commands.' 
        }
      },
      required: ['command', 'workingDir']
    },
    timeoutMs: 120000,
    async handler(args, ctx) {
      let command = args.command;
      const workingDir = args.workingDir 
        ? ctx.resolvePath(args.workingDir) 
        : ctx.workspaceRoot;
      
      // Validate command length
      const MAX_DIRECT_COMMAND_LENGTH = 3000;
      if (command.length > MAX_DIRECT_COMMAND_LENGTH) {
        return {
          result: `❌ Command too complex (${command.length} characters)`,
          error: `For complex operations, use this pattern:
1. write_file to create a script file
2. run_terminal to execute the script

Example:
- write_file: {"path":"fix.py", "content":"your script here"}
- run_terminal: {"command":"python fix.py", "workingDir":"your/dir"}`
        };
      }
      
      // Auto-prefix gradle commands
      if (/^:/.test(command)) {
        const gradleWrapper = process.platform === 'win32' ? '.\\gradlew' : './gradlew';
        command = `${gradleWrapper} ${command}`;
      }
      
      // Detect and log complex commands
      const isComplexCommand = command.length > 1000 ||
                             command.includes('python -c') ||
                             (command.match(/\n/g) || []).length > 2;
      
      if (isComplexCommand) {
        ctx.log(`[run_terminal] Complex command detected (${command.length} chars, ${(command.match(/\n/g) || []).length} lines)`);
      }
      
      // Windows-specific fixes
      if (process.platform === 'win32') {
        if (/^gradlew(\s|$)/i.test(command)) {
          command = command.replace(/^gradlew/i, '.\\gradlew');
        }
        if (/^\.\//i.test(command)) {
          command = command.replace(/^\.\//, '.\\');
        }
        
        // Handle cd "path" && command pattern
        const cdMatch = command.match(/^cd\s+["']?([^"']+)["']?\s*&&\s*(.+)$/i);
        if (cdMatch) {
          command = cdMatch[2];
        }
        
        // Fix bash && to PowerShell ;
        if (command.includes(' && ')) {
          command = command.replace(/ && /g, '; ');
        }
      }
      
      // Classify command as long-running or short
      const isLongRunning = isBuildCommand(command) || isServerCommand(command);
      
      if (isLongRunning) {
        const terminalName = generateTerminalName(command);
        const result = await ctx.terminalManager.runInTerminal(
          terminalName, 
          command, 
          workingDir, 
          true // auto-restart
        );
        
        return { result };
      } else {
        // Short-running command - just execute and return
        const result = await ctx.runCommand(command, 30000, workingDir);
        const output = (result.stdout || '') + '\n' + (result.stderr || '');
        
        if (result.exitCode !== 0) {
          return { 
            result: `Command executed with exit code ${result.exitCode}\n\n${output.slice(-1000)}`,
            error: 'Command failed'
          };
        }
        
        return { result: `Command executed successfully\n\n${output.slice(-1000)}` };
      }
    }
  },
  
  {
    name: 'kill_terminal',
    description: 'Stop a running managed terminal by name',
    category: 'terminal',
    isReadOnly: false,
    requiresConfirmation: true,
    confirmationMessage: 'This will stop the running process in this terminal.',
    parameters: {
      type: 'object',
      properties: {
        name: { type: 'string', description: 'Terminal name (e.g., "backend", "frontend")' }
      },
      required: ['name']
    },
    async handler(args, ctx) {
      const result = ctx.terminalManager.killTerminal(args.name);
      return { result };
    }
  },
  
  {
    name: 'list_terminals',
    description: 'List all terminals and their status. Shows both agent-managed terminals AND all VS Code terminals (including manually opened ones).',
    category: 'terminal',
    isReadOnly: true,
    parameters: {
      type: 'object',
      properties: {},
      required: []
    },
    async handler(args, ctx) {
      const mode = getTerminalMode(ctx);
      
      // Check managed terminals
      const managed = ctx.terminalManager.listTerminals();
      const hasManaged = managed && managed !== 'No managed terminals running.' && managed.trim() !== '';
      
      // Check all VS Code terminals
      const allVscodeTerminals = ctx.vscode.window.terminals;
      const vscodeTerminalsList = allVscodeTerminals.map(t => ({
        name: t.name,
        isActive: t === ctx.vscode.window.activeTerminal,
        exitStatus: t.exitStatus ? 'closed' : 'running',
        source: 'VS Code (not agent-managed)'
      }));
      
      // Filter based on mode
      if (mode === 'managed') {
        return { result: hasManaged ? managed : 'No agent-managed terminals running. (Terminal mode: managed)' };
      }
      
      if (mode === 'vscode') {
        if (vscodeTerminalsList.length === 0) {
          return { result: 'No VS Code terminals open. (Terminal mode: vscode)' };
        }
        return { 
          result: `Found ${vscodeTerminalsList.length} VS Code terminal(s):\n\n${JSON.stringify(vscodeTerminalsList, null, 2)}` 
        };
      }
      
      // Hybrid mode (default): show both
      if (!hasManaged && vscodeTerminalsList.length === 0) {
        return { result: 'No terminals running.' };
      }
      
      if (!hasManaged) {
        return { 
          result: `No agent-managed terminals, but found ${vscodeTerminalsList.length} VS Code terminal(s):\n\n${JSON.stringify(vscodeTerminalsList, null, 2)}` 
        };
      }
      
      return { 
        result: `Managed terminals:\n${managed}\n\nAll VS Code terminals:\n${JSON.stringify(vscodeTerminalsList, null, 2)}` 
      };
    }
  },
  
  {
    name: 'list_all_terminals',
    description: 'List ALL open terminals in VS Code, including those started manually or from previous sessions. Use this to discover terminals not managed by the agent.',
    category: 'terminal',
    isReadOnly: true,
    parameters: {
      type: 'object',
      properties: {},
      required: []
    },
    async handler(args, ctx) {
      const allTerminals = ctx.vscode.window.terminals;
      const result = allTerminals.map(t => ({
        name: t.name,
        isActive: t === ctx.vscode.window.activeTerminal,
        exitStatus: t.exitStatus ? 'closed' : 'running',
        creationOptions: t.creationOptions
      }));

      let message = '';
      if (allTerminals.length === 0) {
        message = 'No terminals open in VS Code.';
      } else {
        message = `Found ${allTerminals.length} terminal(s):\n\n${JSON.stringify(result, null, 2)}`;
        
        // Analyze terminals and provide actionable guidance
        const nodeTerminal = allTerminals.find(t => t.name.toLowerCase().includes('node'));
        const javaTerminal = allTerminals.find(t => t.name.toLowerCase().includes('java'));
        const hasFrontend = nodeTerminal && !nodeTerminal.exitStatus; // undefined = running, defined = closed
        const hasBackend = javaTerminal && !javaTerminal.exitStatus;
        
        if (hasFrontend || hasBackend) {
          message += '\n\n💡 TIP: Servers are already running!\n';
          if (hasFrontend) {
            message += `- **Frontend** is running in "${nodeTerminal?.name}" terminal\n`;
            message += `  → Check browser at http://localhost:5173 (or check terminal for actual port)\n`;
            message += `  → Open browser DevTools (F12) to see frontend errors\n`;
          }
          if (hasBackend) {
            message += `- **Backend** is running in "${javaTerminal?.name}" terminal\n`;
            message += `  → Check terminal output for startup errors\n`;
          }
          message += '\n**Before starting new servers:** Check if existing terminals show errors!\n';
        }
      }
      
      return { result: message };
    }
  },

  {
    name: 'terminal_status',
    description: 'Check if a specific terminal is running. IMPORTANT: Only use this 15+ seconds after starting a server - servers take time to start up.',
    category: 'terminal',
    isReadOnly: true,
    parameters: {
      type: 'object',
      properties: {
        name: { type: 'string', description: 'Terminal name to check' }
      },
      required: ['name']
    },
    async handler(args, ctx) {
      const name = args.name;

      // Check managed terminals first
      const managed = ctx.terminalManager.getTerminalStatus(name);
      if (managed) {
        // Provide age info to help understand startup progress
        const ageInfo = managed.ageSeconds
          ? ` (started ${managed.ageSeconds}s ago)`
          : '';
        const startupNote = managed.ageSeconds && managed.ageSeconds < 30
          ? ` Server is still starting up - this is normal for Gradle servers.`
          : '';

        return {
          result: `Terminal "${name}" is running${ageInfo}. Auto-restart: ${managed.autoRestart ? 'enabled' : 'disabled'}.${startupNote}`
        };
      }

      // Fall back to VS Code terminals
      const vscodeTerminal = ctx.vscode.window.terminals.find(t =>
        t.name.toLowerCase().includes(name.toLowerCase())
      );

      if (vscodeTerminal) {
        return {
          result: `Found VS Code terminal "${vscodeTerminal.name}" (${vscodeTerminal.exitStatus ? 'closed' : 'running'}). This terminal was not started by the agent, so auto-restart is not available.`
        };
      }

      return { result: `Terminal "${name}" is not running.` };
    }
  },
  
  {
    name: 'kill_port',
    description: 'Find and kill the process using a specific port',
    category: 'terminal',
    isReadOnly: false,
    requiresConfirmation: true,
    confirmationMessage: 'This will forcefully terminate the process using this port.',
    parameters: {
      type: 'object',
      properties: {
        port: { type: 'number', description: 'Port number to free' }
      },
      required: ['port']
    },
    timeoutMs: 10000,
    async handler(args, ctx) {
      const port = args.port;
      const isWindows = process.platform === 'win32';
      
      // Find PID using the port
      const findPidResult = await ctx.runCommand(
        isWindows 
          ? `netstat -ano | findstr :${port}` 
          : `lsof -ti:${port}`,
        5000
      );
      
      if (!findPidResult.stdout.trim()) {
        return { result: `No process found on port ${port}.` };
      }
      
      // Extract PID
      let pid: string | undefined;
      if (isWindows) {
        const pidMatch = findPidResult.stdout.match(/\s+(\d+)\s*$/m);
        pid = pidMatch?.[1];
      } else {
        pid = findPidResult.stdout.trim();
      }
      
      if (!pid) {
        return { result: `Could not identify process on port ${port}.` };
      }
      
      // Kill the process
      const killResult = await ctx.runCommand(
        isWindows 
          ? `taskkill /PID ${pid} /F` 
          : `kill -9 ${pid}`,
        5000
      );
      
      if (killResult.exitCode === 0 || killResult.stdout.trim()) {
        return { result: `Killed process ${pid} on port ${port}.` };
      }
      
      return { 
        result: `Failed to kill process ${pid}. ${killResult.stderr}`,
        error: 'Failed to kill process'
      };
    }
  }
];

/**
 * Check if command is a build command
 */
function isBuildCommand(command: string): boolean {
  return /gradlew|mvn|npm\s+(run\s+)?build|tsc/i.test(command);
}

/**
 * Check if command is a server/long-running command
 */
function isServerCommand(command: string): boolean {
  return /gradlew.*:run|npm\s+start|node.*server|python.*server|java\s+-jar/i.test(command);
}

/**
 * Generate a terminal name from command
 */
function generateTerminalName(command: string): string {
  const match = command.match(/gradlew\s+(:[a-z:]+)/i);
  if (match) {
    return `i2-Vision: ${match[1].replace(/:/g, '-')}`;
  }
  
  const simple = command.split(/\s+/)[0];
  return `i2-Vision: ${simple.substring(0, 20)}`;
}
