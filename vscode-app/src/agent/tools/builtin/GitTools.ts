import { ToolDefinition } from '../ToolTypes';

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
      const result = await ctx.runCommand('git status --porcelain', 5000);
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
      
      const result = await ctx.runCommand(`git diff ${flag} ${filePath}`, 5000);
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
      const result = await ctx.runCommand(`git log --oneline -${count}`, 5000);
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
        const result = await ctx.runCommand('git branch --show-current', 5000);
        return { result: result.stdout.trim() || 'Not in a git repository' };
      } else {
        const result = await ctx.runCommand('git branch', 5000);
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
      
      // Stage files
      for (const f of files) {
        await ctx.runCommand(`git add "${f}"`, 5000);
      }
      
      // Commit
      const safeMessage = message.replace(/"/g, '\\"');
      const result = await ctx.runCommand(`git commit -m "${safeMessage}"`, 5000);
      
      return { 
        result: result.stdout || result.stderr || 'Committed successfully.' 
      };
    }
  }
];
