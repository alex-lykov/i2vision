/**
 * ApplyEditsTool - Structured batch editing with validation
 * 
 * Modern pattern: Model provides search/replace pairs, system validates and applies.
 * Solves the edit_file vs write_file dilemma by:
 * 1. Validating uniqueness before applying
 * 2. Supporting multiple edits in one call
 * 3. Reporting precise failures for retry
 * 
 * Usage:
 * - Small targeted changes (1-5 lines) → apply_edits
 * - Large rewrites (>10 lines) → write_file
 * - New files → write_file
 */

export interface EditOperation {
  /** Exact text to find (must match exactly once) */
  search: string;
  /** Replacement text */
  replace: string;
  /** Optional: line number hint for better error messages */
  lineHint?: number;
}

export interface ApplyEditsResult {
  /** Number of successfully applied edits */
  appliedCount: number;
  /** Total edits attempted */
  totalCount: number;
  /** Failed edits with reasons */
  failures: EditFailure[];
  /** Final file content after all edits */
  finalContent: string;
}

export interface EditFailure {
  /** The edit that failed */
  edit: EditOperation;
  /** Failure reason */
  reason: 'NOT_FOUND' | 'MULTIPLE_MATCHES' | 'ALREADY_APPLIED';
  /** How many times the search string was found */
  occurrences: number;
  /** Suggested fix */
  suggestion?: string;
}

/**
 * Apply a batch of edits to file content
 * Pure function - doesn't touch filesystem, just transforms content
 */
export function applyEditsToContent(
  content: string,
  edits: EditOperation[],
  options: { originalContent?: string } = {}
): ApplyEditsResult {
  const failures: EditFailure[] = [];
  let currentContent = content;
  let appliedCount = 0;

  for (const edit of edits) {
    // Count occurrences
    const occurrences = countOccurrences(currentContent, edit.search);

    // Validate uniqueness
    if (occurrences === 0) {
      let suggestion = 'Check if the text was already modified by a previous edit, or verify the exact whitespace/indentation. Common issues: line number prefixes not stripped, extra spaces, or content already changed.';
      
      // Enhanced suggestion when original content is available
      if (options.originalContent && edit.search) {
        const originalOccurrences = countOccurrences(options.originalContent, edit.search);
        if (originalOccurrences > 0) {
          suggestion = `This search string existed in the original file but was modified by a previous edit. The text was found ${originalOccurrences} time(s) in the original content. Try checking what previous edits might have changed this section.`;
        }
      }
      
      failures.push({
        edit,
        reason: 'NOT_FOUND',
        occurrences: 0,
        suggestion
      });
      continue;
    }

    if (occurrences > 1) {
      failures.push({
        edit,
        reason: 'MULTIPLE_MATCHES',
        occurrences,
        suggestion: 'Make the search string more specific by including more surrounding context (3-5 lines)'
      });
      continue;
    }

    // Apply the edit
    currentContent = currentContent.replace(edit.search, edit.replace);
    appliedCount++;
  }

  return {
    appliedCount,
    totalCount: edits.length,
    failures,
    finalContent: currentContent
  };
}

/**
 * Count non-overlapping occurrences of a string
 */
function countOccurrences(content: string, search: string): number {
  if (!search) return 0;
  
  let count = 0;
  let pos = 0;
  
  while ((pos = content.indexOf(search, pos)) !== -1) {
    count++;
    pos += search.length;
  }
  
  return count;
}

/**
 * Get context around a line number for error reporting
 */
export function getContextAroundLine(
  content: string,
  lineNumber: number,
  contextLines: number = 3
): string {
  const lines = content.split('\n');
  const start = Math.max(0, lineNumber - contextLines - 1);
  const end = Math.min(lines.length, lineNumber + contextLines);
  
  const context = lines
    .slice(start, end)
    .map((line, i) => `${start + i + 1}: ${line}`)
    .join('\n');
  
  return context;
}

/**
 * Generate a helpful error message for failed edits
 */
export function formatEditFailure(failure: EditFailure, filePath: string): string {
  const { edit, reason, occurrences, suggestion } = failure;
  
  const searchPreview = edit.search && edit.search.length > 80 
    ? edit.search.substring(0, 80) + '...' 
    : edit.search || '';
  
  switch (reason) {
    case 'NOT_FOUND':
      return `❌ Edit failed: Search text not found in ${filePath}
   Search: "${searchPreview}"
   💡 ${suggestion}`;
    
    case 'MULTIPLE_MATCHES':
      return `❌ Edit failed: Search text found ${occurrences} times (must be unique) in ${filePath}
   Search: "${searchPreview}"
   💡 ${suggestion}`;
    
    case 'ALREADY_APPLIED':
      return `⚠️ Edit skipped: Already applied in ${filePath}
   Search: "${searchPreview}"`;
    
    default:
      return `❌ Edit failed in ${filePath}: ${reason}`;
  }
}

/**
 * Validate edits before applying (pre-flight check)
 * Returns early warnings without modifying content
 */
export function validateEdits(
  content: string,
  edits: EditOperation[]
): { valid: boolean; warnings: string[] } {
  const warnings: string[] = [];
  
  for (const edit of edits) {
    const occurrences = countOccurrences(content, edit.search);
    
    if (occurrences === 0) {
      warnings.push(`Edit ${edits.indexOf(edit) + 1}: Search text not found`);
    } else if (occurrences > 1) {
      warnings.push(`Edit ${edits.indexOf(edit) + 1}: Search text found ${occurrences} times (not unique)`);
    }
    
    // Warn if search === replace (no-op)
    if (edit.search === edit.replace) {
      warnings.push(`Edit ${edits.indexOf(edit) + 1}: Search and replace are identical (no-op)`);
    }
  }
  
  return {
    valid: warnings.length === 0,
    warnings
  };
}
