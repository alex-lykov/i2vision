import { ToolContext, ToolDefinition } from '../ToolTypes';

/**
 * Git version control tools.
 *
 * IMPORTANT: every git command below explicitly receives `cwd` pointing at the
 * repository root. The tool-host default cwd is not guaranteed to be inside a
 * git working tree, which caused `not a git repository` / `Working tree clean`
 * when the host process started from a different directory.
 */

function workspaceCandidates(ctx: ToolContext): string[] {
  const candidates: string[] = [];
  if (ctx.workspaceRoot) candidates.push(ctx.workspaceRoot);
  try {
    const folders = ctx.vscode.workspace.workspaceFolders;
    for (const f of folders || []) {
      if (f.uri?.fsPath) candidates.push(f.uri.fsPath);
    }
  } catch (_) {
    // vscode API may be unavailable in headless/test tool hosts
  }
  candidates.push(process.cwd());
  return [...new Set(candidates)];
}

async function resolveGitRoot(ctx: ToolContext): Promise<string> {
  for (const candidate of workspaceCandidates(ctx)) {
    try {
      const probe = await ctx.runCommand('git rev-parse --show-toplevel', 5000, candidate);
      const root = (probe.stdout || '').trim();
      if (probe.exitCode === 0 && root) {
        return root;
      }
    } catch (_) {
      // try next candidate
    }
  }
  return ctx.workspaceRoot || process.cwd();
}

async function runGit(
  ctx: ToolContext,
  args: string,
  timeout = 5000
): Promise<{ stdout: string; stderr: string; exitCode: number | null }> {
  const cwd = await resolveGitRoot(ctx);
  return ctx.runCommand(`git ${args}`, timeout, cwd);
}

/**
 * Git version control tools
 */
export const gitTools: ToolDefinition[] = [
  {
    name: 'git_status',
    description: 'Show working tree status (modified, staged, untracked files)',
    category: 'git',
    isReadOnly: true,
    parameters: {
      type: 'object',
      properties: {},
      required: []
    },
    async handler(args, ctx) {
      const result = await runGit(ctx, 'status --porcelain');
      return { result: result.stdout.trim() || 'Working tree clean.' };
    }
  },

  {
    name: 'git_diff',
    description: 'Show changes between commits, staged, or working tree',
    category: 'git',
    isReadOnly: true,
    parameters: {
      type: 'object',
      properties: {
        target: {
          type: 'string',
          enum: ['staged', 'unstaged', 'all'],
          description: 'What to diff'
        },
        path: { type: 'string', description: 'Specific file or directory (optional)' }
      },
      required: ['target']
    },
    async handler(args, ctx) {
      const target = args.target || 'unstaged';
      const filePath = args.path || '';
      const flag = target === 'staged' ? '--staged' : '';

      const argsStr = `diff ${flag} ${filePath}`.trim();
      const result = await runGit(ctx, argsStr);
      const diffOutput = result.stdout || 'No differences.';

      // If there are actual changes, format as diff card HTML
      if (diffOutput !== 'No differences.' && diffOutput.trim()) {
        return {
          result: `DIFF_CARD_START\n${diffOutput}\nDIFF_CARD_END`,
          error: undefined
        };
      }

      return { result: diffOutput };
    }
  },

  {
    name: 'git_log',
    description: 'Show recent commit history',
    category: 'git',
    isReadOnly: true,
    parameters: {
      type: 'object',
      properties: {
        count: { type: 'number', description: 'Number of commits to show (max 50, default 10)' }
      },
      required: []
    },
    async handler(args, ctx) {
      const count = Math.min(args.count || 10, 50);
      const result = await runGit(ctx, `log --oneline -${count}`);
      return { result: result.stdout || 'No commits.' };
    }
  },

  {
    name: 'git_branch',
    description: 'Show current or all branches',
    category: 'git',
    isReadOnly: true,
    parameters: {
      type: 'object',
      properties: {
        action: {
          type: 'string',
          enum: ['current', 'all'],
          description: 'Show current branch or all branches'
        }
      },
      required: []
    },
    async handler(args, ctx) {
      const action = args.action || 'current';

      if (action === 'current') {
        const result = await runGit(ctx, 'branch --show-current');
        return { result: result.stdout.trim() || 'Not in a git repository' };
      } else {
        const result = await runGit(ctx, 'branch');
        return { result: result.stdout || 'No branches found' };
      }
    }
  },

  {
    name: 'git_commit',
    description: 'Stage files and create a commit',
    category: 'git',
    isReadOnly: false,
    requiresConfirmation: false,
    parameters: {
      type: 'object',
      properties: {
        message: { type: 'string', description: 'Commit message' },
        files: {
          type: 'array',
          items: { type: 'string' },
          description: 'Files to stage (default: all changes)'
        }
      },
      required: ['message']
    },
    async handler(args, ctx) {
      const message = args.message;
      const files = args.files || ['.'];

      // Stage files (from repository root)
      for (const f of files) {
        const addResult = await runGit(ctx, `add -- "${f}"`, 10000);
        if (addResult.exitCode !== 0) {
          return { result: addResult.stderr || `git add failed for ${f}` };
        }
      }

      // Commit
      const safeMessage = message.replace(/"/g, '\\"');
      const result = await runGit(ctx, `commit -m "${safeMessage}"`, 15000);

      if (result.exitCode !== 0) {
        return { result: result.stderr || result.stdout || 'Git commit failed.' };
      }

      return {
        result: result.stdout || result.stderr || 'Committed successfully.'
      };
    }
  }
];
