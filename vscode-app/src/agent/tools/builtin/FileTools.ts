import { ToolDefinition } from '../ToolTypes';

/**
 * File system tools for reading, writing, and exploring files
 */
export const fileTools: ToolDefinition[] = [
  {
    name: 'list_directory',
    description: 'List files in a directory',
    category: 'file',
    isReadOnly: true,
    parameters: {
      type: 'object',
      properties: {
        path: { type: 'string', description: 'Directory path relative to workspace root' },
        recursive: { type: 'boolean', description: 'Search recursively (default: false)' }
      },
      required: ['path']
    },
    async handler(args, ctx) {
      const dirPath = ctx.resolvePath(args.path);
      const recursive = args.recursive === true;
      
      // Debug logging for path resolution
      ctx.log(`list_directory: args.path="${args.path}" → resolved="${dirPath}", recursive=${recursive}`);

      try {
        const files = await ctx.listFiles(dirPath, recursive);

        ctx.log(`list_directory result: ${files.length} files found`);

        if (files.length === 0) {
          const exists = await ctx.fileExists(dirPath);
          ctx.log(`list_directory: directory exists=${exists}`);
          return {
            result: exists
              ? 'This directory is empty.'
              : `DIRECTORY_NOT_FOUND: '${args.path}' does not exist.`
          };
        }

        return { result: files.join('\n') };
      } catch (error: any) {
        ctx.log(`list_directory error: ${error.message}`);
        if (error.code === 'ENOENT') {
          return { result: `DIRECTORY_NOT_FOUND: '${args.path}' does not exist.` };
        }
        throw error;
      }
    }
  },
  
  {
    name: 'read_file',
    description: 'Read contents of a file. Output includes line numbers (1-based) for precise targeting with apply_edits. Supports optional offset (1-based line number) and limit (max lines) for reading partial content. IMPORTANT: File content is cached during a session - if you read a file and the content is unchanged, you will get the same numbered output without a disk re-read.',
    category: 'file',
    isReadOnly: true,
    parameters: {
      type: 'object',
      properties: {
        path: { type: 'string', description: 'File path relative to workspace root' },
        offset: { type: 'number', description: 'Optional: 1-based line number to start reading from' },
        limit: { type: 'number', description: 'Optional: Maximum number of lines to read' }
      },
      required: ['path']
    },
    async handler(args, ctx) {
      const filePath = ctx.resolvePath(args.path);
      const offset = typeof args.offset === 'number' ? args.offset : undefined;
      const limit = typeof args.limit === 'number' ? args.limit : undefined;

      try {
        let content = await ctx.readFile(filePath);

        if (!content || content.trim() === '') {
          return { result: 'The file exists but is empty.' };
        }

        // Apply offset/limit if specified
        const lines = content.split('\n');
        const startIdx = offset !== undefined ? Math.max(0, offset - 1) : 0;
        const endIdx = limit !== undefined ? startIdx + limit : lines.length;
        const sliced = lines.slice(startIdx, endIdx);

        // Add line numbers (1-based) for precise edit targeting
        const maxLineNum = startIdx + sliced.length;
        const padWidth = String(maxLineNum).length;
        const numberedLines = sliced.map((line, i) => {
          const lineNum = startIdx + i + 1;
          return `${String(lineNum).padStart(padWidth)} | ${line}`;
        });

        content = numberedLines.join('\n');
        
        return { result: content };
      } catch (error: any) {
        if (error.code === 'ENOENT') {
          return { result: 'FILE_NOT_FOUND' };
        }
        throw error;
      }
    }
  },
  
  {
    name: 'write_file',
    description: 'Write content to a file. Use for large changes, new files, or when apply_edits would need more than 50 edits.',
    category: 'file',
    isReadOnly: false,
    requiresConfirmation: false,
    parameters: {
      type: 'object',
      properties: {
        path: { type: 'string', description: 'File path relative to workspace root' },
        content: { type: 'string', description: 'Full file content to write' }
      },
      required: ['path', 'content']
    },
    async handler(args, ctx) {
      const filePath = ctx.resolvePath(args.path);
      const content = args.content;
      
      // Snapshot before edit
      if (!ctx.fileSnapshots.has(filePath)) {
        try {
          const original = await ctx.readFile(filePath);
          ctx.fileSnapshots.set(filePath, original);
          ctx.log(`Snapshot saved: ${filePath} (${original.length} chars)`);
        } catch {
          // New file - no snapshot needed
          ctx.log(`New file: ${filePath}`);
        }
      }
      
      await ctx.writeFile(filePath, content);
      
      return { 
        result: `Successfully wrote ${content.length} characters to ${filePath}` 
      };
    }
  },
  
  {
    name: 'search_files',
    description: 'Search for files by name AND content. Returns each matching file with line numbers, match snippets, and match counts. TIP: Once you see the line numbers and snippets, you can use apply_edits directly without re-reading the file.',
    category: 'file',
    isReadOnly: true,
    parameters: {
      type: 'object',
      properties: {
        pattern: { type: 'string', description: 'Regex pattern to search for' },
        path: { type: 'string', description: 'Directory to search in (optional)' }
      },
      required: ['pattern']
    },
    async handler(args, ctx) {
      const pattern = args.pattern;
      const searchPath = args.path ? ctx.resolvePath(args.path) : undefined;
      
      const results = await ctx.searchFiles(pattern, searchPath);
      
      if (results.length === 0) {
        return { 
          result: `No files found matching pattern "${pattern}". Try a different search term or use list_directory to explore.`, 
          error: 'NO_RESULTS' 
        };
      }
      
      // TOKEN-EFFICIENT: Limit results to prevent context overflow
      const MAX_RESULTS = 8; // Reduced from 10 to save tokens
      const MAX_SNIPPETS_PER_FILE = 2; // Reduced from 3 to save tokens
      const MAX_SNIPPET_LENGTH = 80; // Truncate long snippets

      // Format detailed results with line numbers and snippets
      const formatResult = (result: any): string => {
        const relativePath = result.path.replace(ctx.workspaceRoot, '').replace(/^[/\\]/, '');
        let output = `📄 ${relativePath}`;
        if (result.matchedByName) {
          output += ' (name match)';
        } else {
          output += ` — ${result.matchCount} match(es)`;
        }

        if (result.matches && result.matches.length > 0 && !result.matchedByName) {
          const snippetCount = Math.min(result.matches.length, MAX_SNIPPETS_PER_FILE);
          for (let i = 0; i < snippetCount; i++) {
            const match = result.matches[i];
            const truncatedText = match.text.length > MAX_SNIPPET_LENGTH
              ? match.text.substring(0, MAX_SNIPPET_LENGTH) + '...'
              : match.text;
            output += `\n  L${match.line}: ${truncatedText}`;
          }
          if (result.matches.length > MAX_SNIPPETS_PER_FILE) {
            output += `\n  +${result.matches.length - MAX_SNIPPETS_PER_FILE} more`;
          }
        }
        return output;
      };

      const displayedCount = Math.min(results.length, MAX_RESULTS);
      const formatted = results.slice(0, MAX_RESULTS).map(formatResult);

      if (results.length > MAX_RESULTS) {
        return {
          result: `Found ${results.length} files matching "${pattern}". Top ${displayedCount}:\n${formatted.join('\\n\\n')}\n\n💡 TIP: ${results.length - MAX_RESULTS} more files not shown. Read specific files with read_file, or refine your search pattern to be more specific.`,
          error: 'MANY_RESULTS'
        };
      }

      return {
        result: `Found ${results.length} file(s):\n\n${formatted.join('\\n\\n')}\n\n💡 TIP: Use apply_edits on the line numbers shown, or read_file with offset for more context.`
      };
    }
  },
  
  {
    name: 'get_file_context',
    description: 'Get context for a specific file (classes, functions, imports)',
    category: 'file',
    isReadOnly: true,
    parameters: {
      type: 'object',
      properties: {
        path: { type: 'string', description: 'File path relative to workspace root' }
      },
      required: ['path']
    },
    async handler(args, ctx) {
      const filePath = ctx.resolvePath(args.path);
      
      try {
        const stat = await ctx.fileExists(filePath);
        if (!stat) {
          return { result: '', error: 'FILE_NOT_FOUND' };
        }
      } catch (e: any) {}
      
      const context = await ctx.getFileContext(filePath);
      return { result: JSON.stringify(context, null, 2) };
    }
  },
  
  {
    name: 'revert_file',
    description: 'Revert a file to its original state before edits were made. Use this to undo changes that caused build failures.',
    category: 'file',
    isReadOnly: false,
    requiresConfirmation: true,
    confirmationMessage: 'This will undo all changes made to this file in the current session.',
    parameters: {
      type: 'object',
      properties: {
        path: { type: 'string', description: 'File path relative to workspace root to revert' }
      },
      required: ['path']
    },
    async handler(args, ctx) {
      const filePath = ctx.resolvePath(args.path);
      const original = ctx.fileSnapshots.get(filePath);
      
      if (!original) {
        return { 
          result: '', 
          error: `No snapshot available for ${args.path}. It may not have been edited in this session.` 
        };
      }
      
      await ctx.writeFile(filePath, original);
      ctx.fileSnapshots.delete(filePath);
      ctx.log(`Reverted: ${filePath}`);
      
      return { 
        result: `Reverted ${args.path} to original state (${original.length} chars).` 
      };
    }
  },
  
  {
    name: 'revert_all',
    description: 'Revert ALL modified files to their original state. Use this to undo all changes in the current session.',
    category: 'file',
    isReadOnly: false,
    requiresConfirmation: true,
    confirmationMessage: 'This will undo ALL changes made in the current session.',
    parameters: {
      type: 'object',
      properties: {},
      required: []
    },
    async handler(args, ctx) {
      let count = 0;
      const paths: string[] = [];
      
      for (const [filePath, original] of ctx.fileSnapshots) {
        await ctx.writeFile(filePath, original);
        paths.push(filePath);
        count++;
      }
      
      ctx.fileSnapshots.clear();
      ctx.log(`Reverted all: ${count} files`);
      
      return { 
        result: count > 0 
          ? `Reverted ${count} file(s) to original state:\n${paths.map(p => `  - ${p}`).join('\n')}`
          : 'No files were modified in this session.' 
      };
    }
  },
  
  {
    name: 'list_snapshots',
    description: 'List all files that have been modified and can be reverted.',
    category: 'file',
    isReadOnly: true,
    parameters: {
      type: 'object',
      properties: {},
      required: []
    },
    async handler(args, ctx) {
      if (ctx.fileSnapshots.size === 0) {
        return { result: 'No files have been modified in this session.' };
      }
      
      const files = Array.from(ctx.fileSnapshots.keys());
      return { 
        result: `Modified files (${files.length}):\n${files.map(f => `  - ${f}`).join('\n')}` 
      };
    }
  }
];
