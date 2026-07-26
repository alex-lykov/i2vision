import { ToolDefinition } from '../ToolTypes';
import { applyEditsToContent, formatEditFailure, EditOperation } from '../../ApplyEditsTool';

/**
 * Strip line number prefixes that read_file injects for display.
 * read_file formats each line as "{pad}{lineNum} | {originalLine}",
 * e.g. "  1 | import { x } from 'y';".
 * When the model copies this into apply_edits search/replace, the
 * prefix must be removed so the search matches the actual file content.
 */
function stripLineNumberPrefix(text: string): string {
  if (typeof text !== 'string' || text === null) return '';
  
  // Handle various line number formats that might come from read_file output
  // Format 1: "  1 | content" (standard read_file format)
  // Format 2: "1: content" (alternative format)
  // Format 3: "Line 1: content" (verbose format)
  // Format 4: "[1] content" (bracket format)
  // Format 5: "1 | content" (compact format)
  // Format 6: "… content" (truncation with ellipsis)
  // Format 7: "... content" (truncation with dots)
  try {
    return text.split('\n').map(line => 
      line.replace(/^\s*\d+\s*\|\s*/, '')  // "  1 | content" and "1 | content"
         .replace(/^\s*\d+:\s*/, '')        // "1: content" 
         .replace(/^\s*Line\s+\d+:\s*/i, '') // "Line 1: content"
         .replace(/^\[\d+\]\s*/, '')       // "[1] content"
         .replace(/^\s*\d+\s+\|\s*/, '')              // "1 | content" with single space
         .replace(/^\s*[…\.]{3,}\s*/, '')               // "… content" or "... content" (truncation)
         .replace(/\s+[…\.]{3,}\s*$/, '')               // trailing truncation indicators
    ).join('\n');
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    console.error(`[stripLineNumberPrefix] Error processing text: ${message}`);
    return text; // Return original text if processing fails
  }
}

/**
 * More aggressive line number removal for cases where the basic stripping failed
 * This handles cases where line numbers might be embedded in multi-line strings
 */
function aggressiveStripLineNumbers(text: string): string {
  if (typeof text !== 'string' || text === null || text === undefined) return '';
  
  try {
    // Pattern to match line numbers at the start of lines
    const lineNumberPattern = /^\s*(?:\d+\s*(?:[|:]|Line\s+\d+:\s*)\s*)+/gim;
    
    // Remove line numbers from the beginning of each line
    let result = text.replace(lineNumberPattern, '');
    
    // Also handle cases where line numbers might be in the middle of content
    // (e.g., when model copies multi-line content with line numbers)
    result = result.replace(/\n\s*\d+\s*[|:]\s*/g, '\n');
    
    // Handle truncation indicators that might appear in content
    result = result.replace(/[…\.]{3,}/g, '').trim();
    
    return result;
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    console.error(`[aggressiveStripLineNumbers] Error: ${message}`);
    return text; // Return original if processing fails
  }
}

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
      try {
      // Comprehensive parameter validation
      if (!args) {
        return {
          result: '',
          error: 'Invalid arguments: args is null or undefined'
        };
      }
      
      if (typeof args !== 'object') {
        return {
          result: '',
          error: `Invalid arguments: expected object, got ${typeof args}`
        };
      }
      
      // Validate required parameters
      if (typeof args.path !== 'string' || !args.path) {
        return {
          result: '',
          error: `Invalid path parameter: expected non-empty string, got ${typeof args.path}`
        };
      }
      
      if (args.edits === undefined || args.edits === null) {
        return {
          result: '',
          error: 'Invalid edits parameter: edits is null or undefined'
        };
      }
      
      const filePath = ctx.resolvePath(args.path);
      
      // Handle both direct array format and proxy-corrected object format
      let edits: EditOperation[] = [];
      if (Array.isArray(args.edits)) {
        edits = args.edits;
      } else if (typeof args.edits === 'object' && args.edits !== null && Array.isArray(args.edits.edits)) {
        ctx.log(`[apply_edits] Using proxy-corrected format: edits.edits`);
        edits = args.edits.edits;
      } else {
        return {
          result: '',
          error: `Invalid edits parameter: expected array or {edits: array}, got ${typeof args.edits}`
        };
      }
      
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
      
      // Large file detection and handling
      const LARGE_FILE_THRESHOLD = 50000; // 50KB
      if (currentContent.length > LARGE_FILE_THRESHOLD) {
        ctx.log(`[apply_edits] Large file detected: ${filePath} (${currentContent.length} chars)`);
        // For large files, extend processing limits and provide guidance
      }
      
      // Validate edits array exists and is properly structured
      if (!Array.isArray(edits)) {
        // Check if edits is already a valid object (from proxy fix)
        if (typeof edits === 'object' && edits !== null && 'edits' in edits && Array.isArray((edits as any).edits)) {
          // This is the corrected format from the proxy - use it directly
          ctx.log(`[apply_edits] Received proxy-corrected format, using edits.edits`);
          // Continue with edits.edits as the actual edits array
        } else {
          return {
            result: '',
            error: `Invalid edits parameter: expected array, got ${typeof edits}`
          };
        }
      }
      
      // Validate each edit has required fields before processing
      const invalidEdits = edits.filter((e, i) => 
        typeof e?.search !== 'string' || typeof e?.replace !== 'string' || e.search === null || e.replace === null
      );
      if (invalidEdits.length > 0) {
        const indices = invalidEdits.map((_, i) => `#${i + 1}`).join(', ');
        const details = invalidEdits.map((e, idx) => {
          const searchPreview = e?.search === null ? 'null' : (e?.search === undefined ? 'undefined' : (typeof e?.search === 'string' ? JSON.stringify(e.search).substring(0, 50) : String(e?.search).substring(0, 50)));
          const replacePreview = e?.replace === null ? 'null' : (e?.replace === undefined ? 'undefined' : (typeof e?.replace === 'string' ? JSON.stringify(e.replace).substring(0, 50) : String(e?.replace).substring(0, 50)));
          return `Edit ${idx + 1}: search=${typeof e?.search} (${searchPreview}), replace=${typeof e?.replace} (${replacePreview})`;
        }).join('; ');
        return {
          result: '',
          error: `Invalid edits at ${indices}: each edit must have non-null string 'search' and 'replace' fields. Details: ${details}`
        };
      }
      
      // Additional validation for empty search strings
      const emptySearchEdits = edits.filter(e => e.search.trim() === '');
      if (emptySearchEdits.length > 0) {
        const indices = emptySearchEdits.map((_, i) => `#${i + 1}`).join(', ');
        return {
          result: '',
          error: `Invalid edits at ${indices}: search string cannot be empty`
        };
      }

      // Strip line number prefixes that read_file injects for display.
      // The model often copies text directly from read_file output which
      // includes "N | " prefixes. Without stripping, the search fails.
      const cleanedEdits: EditOperation[] = edits.map(e => {
        // Final safety check - ensure search and replace are defined
        if (e.search === undefined || e.search === null) {
          ctx.log(`[apply_edits] WARNING: Edit search is undefined/null, using empty string`);
        }
        if (e.replace === undefined || e.replace === null) {
          ctx.log(`[apply_edits] WARNING: Edit replace is undefined/null, using empty string`);
        }
        
        // First try basic stripping with null safety
        let cleanedSearch = stripLineNumberPrefix(e.search || '');
        let cleanedReplace = stripLineNumberPrefix(e.replace || '');
        
        // If the search still looks like it has line numbers, try aggressive stripping
        try {
          if (cleanedSearch && (/^\s*\d+\s*[|:]/.test(cleanedSearch) || (cleanedSearch.includes('\n') && /\d+\s*[|:]/.test(cleanedSearch)))) {
            ctx.log(`[apply_edits] Using aggressive line number stripping for search string`);
            cleanedSearch = aggressiveStripLineNumbers(e.search || '');
            cleanedReplace = aggressiveStripLineNumbers(e.replace || '');
          }
        } catch (error: any) {
          ctx.log(`[apply_edits] ERROR in line number stripping: ${error.message}`);
          // Fallback to original values if stripping fails
          cleanedSearch = e.search || '';
          cleanedReplace = e.replace || '';
        }
        
        return {
          search: cleanedSearch,
          replace: cleanedReplace,
          lineHint: e.lineHint
        };
      });
      
      // Debug logging for troubleshooting
      ctx.log(`[apply_edits] Processing ${cleanedEdits.length} edits on ${filePath}`);
      cleanedEdits.forEach((edit, index) => {
        // Safe string conversion for debug logging
        const searchPreview = edit.search != null && typeof edit.search === 'string' && edit.search.length > 50 
          ? edit.search.substring(0, 50) + '...' 
          : (edit.search != null ? String(edit.search) : 'null');
        const replacePreview = edit.replace != null && typeof edit.replace === 'string' && edit.replace.length > 50 
          ? edit.replace.substring(0, 50) + '...' 
          : (edit.replace != null ? String(edit.replace) : 'null');
        ctx.log(`[apply_edits] Edit ${index + 1}: search="${searchPreview}", replace="${replacePreview}"`);
      });
      
      // Check if current content contains any of the search strings
      const contentPreview = currentContent && typeof currentContent === 'string' && currentContent.length > 200 
        ? currentContent.substring(0, 200) + '...' 
        : (currentContent || 'empty content');
      ctx.log(`[apply_edits] File content preview: ${contentPreview}`);
      
      // Apply edits with retry logic for common failure patterns
      let editResult = applyEditsToContent(currentContent, cleanedEdits);
      
      // If all edits failed due to NOT_FOUND, try some common fixes
      if (editResult.appliedCount === 0 && editResult.failures.every(f => f.reason === 'NOT_FOUND')) {
        ctx.log(`[apply_edits] All edits failed (NOT_FOUND), attempting automatic fixes...`);
        
        // Try removing any remaining line number prefixes that might have been missed
        const retryEdits = cleanedEdits.map(e => ({
          search: aggressiveStripLineNumbers(e.search),
          replace: aggressiveStripLineNumbers(e.replace),
          lineHint: e.lineHint
        }));
        
        // Try again with more aggressive cleaning
        const retryResult = applyEditsToContent(currentContent, retryEdits);
        
        if (retryResult.appliedCount > 0) {
          ctx.log(`[apply_edits] Retry successful: applied ${retryResult.appliedCount} edits`);
          editResult = retryResult;
        } else {
          ctx.log(`[apply_edits] Retry failed: still no matches found`);
        }
      }
      
      // Handle failures
      if (editResult.appliedCount === 0) {
        const failureMessages = editResult.failures.map(f => formatEditFailure(f, filePath));
        
        // Additional debugging for common failure patterns
        if (editResult.failures.some(f => f.reason === 'NOT_FOUND')) {
          ctx.log(`[apply_edits] DEBUG: Some search strings not found in file content`);
          ctx.log(`[apply_edits] DEBUG: File content length: ${currentContent.length} characters`);
          ctx.log(`[apply_edits] DEBUG: File content hash: ${currentContent.length.toString(16)}`);
          
          // Check if any search strings appear to be line-number prefixed
          const possiblyPrefixed = cleanedEdits.filter(e => /^\s*\d+\s*[|:]/.test(e.search));
          if (possiblyPrefixed.length > 0) {
            ctx.log(`[apply_edits] DEBUG: Found ${possiblyPrefixed.length} edits that might still have line number prefixes`);
          }
        }
        
        // Enhanced error message for large files
        if (currentContent.length > LARGE_FILE_THRESHOLD) {
          return { 
            result: `❌ Large file edit failed (${currentContent.length} characters)`,
            error: `apply_edits struggled with this large file. For files over 50KB, consider:
    • Breaking changes into multiple smaller apply_edits calls
    • Using write_file to replace the entire file
    • Using run_terminal with sed/awk for complex transformations
    • Temporarily splitting the file into smaller components`
          };
        }
        
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
      } catch (error: any) {
        ctx.log(`[apply_edits] CRITICAL ERROR: ${error.message}`);
        console.error(`[apply_edits] Handler error:`, error);
        return {
          result: '',
          error: `apply_edits failed with internal error: ${error.message}. Please check logs for details.`
        };
      }
    }
  }
];
