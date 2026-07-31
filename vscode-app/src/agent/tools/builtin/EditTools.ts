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
    description: 'Apply targeted edits to an existing file. MAX 50 edits per call. For small changes (1-5 lines each). For large rewrites (>50 edits), use write_file instead. Supports multiple formats: {search: "old", replace: "new"} OR {old_string: "old", new_string: "new"}.',
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
              search: { type: 'string', description: 'Exact text to find (must be unique in file). Alternative: use old_string instead of search.' },
              replace: { type: 'string', description: 'Replacement text. Alternative: use new_string instead of replace.' },
              lineHint: { type: 'number', description: 'Optional: approximate line number' }
            },
            required: [] // Handled dynamically to support both search/replace and old_string/new_string formats
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
      
      // Handle multiple input formats adaptively
      let edits: EditOperation[] = [];
      
      // Format 1: Direct array format (standard)
      if (Array.isArray(args.edits)) {
        edits = args.edits;
        ctx.log(`[apply_edits] Using direct array format`);
      }
      // Format 2: Proxy-corrected format {edits: array}
      else if (typeof args.edits === 'object' && args.edits !== null && Array.isArray(args.edits.edits)) {
        ctx.log(`[apply_edits] Using proxy-corrected format: edits.edits`);
        edits = args.edits.edits;
      }
      // Format 3: JSON string format (common from 3D-LLM tools)
      else if (typeof args.edits === 'string') {
        try {
          const parsed = JSON.parse(args.edits);
          if (Array.isArray(parsed)) {
            edits = parsed;
            ctx.log(`[apply_edits] Parsed JSON string format successfully`);
          } else if (typeof parsed === 'object' && parsed !== null && Array.isArray(parsed.edits)) {
            edits = parsed.edits;
            ctx.log(`[apply_edits] Parsed JSON string with edits.edits format`);
          } else {
            // Try to handle old_string/new_string format
            if (Array.isArray(parsed) && parsed.length > 0 && parsed[0].old_string !== undefined) {
              edits = parsed.map((edit: any) => ({
                search: edit.old_string,
                replace: edit.new_string,
                lineHint: edit.lineHint || edit.line_number
              }));
              ctx.log(`[apply_edits] Converted old_string/new_string format to search/replace`);
            } else {
              return {
                result: '',
                error: `Invalid JSON format in edits string. Expected array with search/replace or old_string/new_string format.`
              };
            }
          }
        } catch (parseError) {
          return {
            result: '',
            error: `Failed to parse edits string as JSON: ${parseError instanceof Error ? parseError.message : String(parseError)}`
          };
        }
      }
      // Format 4: Direct object with edits property
      else if (typeof args.edits === 'object' && args.edits !== null && args.edits.edits !== undefined) {
        if (Array.isArray(args.edits.edits)) {
          edits = args.edits.edits;
          ctx.log(`[apply_edits] Using object with edits property`);
        } else {
          return {
            result: '',
            error: `Invalid edits format: edits.edits is not an array, got ${typeof args.edits.edits}`
          };
        }
      }
      // Unsupported format
      else {
        return {
          result: '',
          error: `❌ Invalid edits format. Supported formats:
• Array: [{search: "old", replace: "new"}]
• Array (alternative): [{old_string: "old", new_string: "new"}]
• Object: {edits: [{search: "old", replace: "new"}]}
• JSON string: "[{search: \"old\", replace: \"new\"}]"
• JSON string (alternative): "[{old_string: \"old\", new_string: \"new\"}]"

Received: ${typeof args.edits}
Tip: Check if your tool call is properly formatted as an array or valid JSON string.`
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
      
      // Normalize edit format (handle old_string/new_string -> search/replace)
      const normalizedEdits = edits.map((edit: any) => {
        if (edit.old_string !== undefined && edit.new_string !== undefined) {
          // Convert old_string/new_string format to search/replace
          return {
            search: edit.old_string,
            replace: edit.new_string,
            lineHint: edit.lineHint || edit.line_number
          };
        }
        return edit;
      });

      // Validate each edit has required fields before processing
      const invalidEdits = normalizedEdits.filter((e, i) => 
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
          error: `Invalid edits at ${indices}: each edit must have non-null string 'search' and 'replace' fields (or 'old_string' and 'new_string'). Details: ${details}`
        };
      }
      
      // Additional validation for empty search strings
      const emptySearchEdits = normalizedEdits.filter(e => e.search.trim() === '');
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
      const cleanedEdits: EditOperation[] = normalizedEdits.map(e => {
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
      let editResult = applyEditsToContent(currentContent, cleanedEdits, { originalContent: ctx.fileSnapshots.get(filePath) });
      
      // If all edits failed due to NOT_FOUND, try some common fixes
      if (editResult.appliedCount === 0 && editResult.failures.every(f => f.reason === 'NOT_FOUND')) {
        ctx.log(`[apply_edits] All edits failed (NOT_FOUND), attempting automatic fixes...`);
        
        // Log full search strings for debugging when they fail
        cleanedEdits.forEach((edit, index) => {
          const fullSearch = edit.search != null && typeof edit.search === 'string' 
            ? edit.search 
            : (edit.search != null ? String(edit.search) : 'null');
          ctx.log(`[apply_edits] DEBUG: Full search string ${index + 1}: "${fullSearch}"`);
        });
        
        // Try removing any remaining line number prefixes that might have been missed
        const retryEdits = cleanedEdits.map(e => ({
          search: aggressiveStripLineNumbers(e.search),
          replace: aggressiveStripLineNumbers(e.replace),
          lineHint: e.lineHint
        }));
        
        // Try again with more aggressive cleaning
        const retryResult = applyEditsToContent(currentContent, retryEdits, { originalContent: ctx.fileSnapshots.get(filePath) });
        
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
