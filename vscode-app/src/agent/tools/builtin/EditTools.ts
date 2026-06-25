import { ToolDefinition } from '../ToolTypes';
import { applyEditsToContent, formatEditFailure, EditOperation } from '../../ApplyEditsTool';

/**
 * Edit tools for applying targeted changes to files
 */
export const editTools: ToolDefinition[] = [
  {
    name: 'apply_edits',
    description: 'Apply targeted edits to an existing file. MAX 50 edits per call. For small changes (1-5 lines each). For large rewrites (>50 edits), use write_file instead.',
    category: 'edit',
    isReadOnly: false,
    requiresConfirmation: false,
    parameters: {
      type: 'object',
      properties: {
        path: { type: 'string', description: 'File path relative to workspace root' },
        edits: {
          type: 'array',
          description: 'List of edit operations. MAX 50 edits per call. For larger changes, use write_file.',
          items: {
            type: 'object',
            properties: {
              search: { type: 'string', description: 'Exact text to find (must be unique in file)' },
              replace: { type: 'string', description: 'Replacement text' },
              lineHint: { type: 'number', description: 'Optional: approximate line number' }
            },
            required: ['search', 'replace']
          }
        }
      },
      required: ['path', 'edits']
    },
    timeoutMs: 30000,
    async handler(args, ctx) {
      const filePath = ctx.resolvePath(args.path);
      const edits: EditOperation[] = args.edits;
      
      // Validate edit count
      const MAX_EDITS = 50;
      if (edits.length > MAX_EDITS) {
        return { 
          result: '', 
          error: `Too many edits (${edits.length}). Maximum ${MAX_EDITS} edits per call. For large changes, use write_file to replace the entire file instead.` 
        };
      }
      
      // Snapshot before edit
      if (!ctx.fileSnapshots.has(filePath)) {
        try {
          const original = await ctx.readFile(filePath);
          ctx.fileSnapshots.set(filePath, original);
          ctx.log(`Snapshot saved: ${filePath} (${original.length} chars)`);
        } catch {
          ctx.log(`Reading file for edits: ${filePath}`);
        }
      }
      
      // Read current content
      const currentContent = await ctx.readFile(filePath);
      
      // Apply edits
      const editResult = applyEditsToContent(currentContent, edits);
      
      // Handle failures
      if (editResult.appliedCount === 0) {
        const failureMessages = editResult.failures.map(f => formatEditFailure(f, filePath));
        
        return { 
          result: `❌ No edits applied\n\n${failureMessages.join('\n\n')}`, 
          error: 'All edits failed' 
        };
      }
      
      // Write updated content
      await ctx.writeFile(filePath, editResult.finalContent);
      
      // Build result message
      let resultMessage = `✅ Applied ${editResult.appliedCount}/${editResult.totalCount} edits to ${filePath}`;
      
      if (editResult.failures.length > 0) {
        resultMessage += `\n⚠️ ${editResult.failures.length} edit(s) failed`;
      }
      
      return { result: resultMessage };
    }
  }
];
