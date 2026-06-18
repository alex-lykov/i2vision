/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

/**
 * DiffCard Types
 *
 * Defines the structure for rendering git diff output as a rich,
 * interactive card in the agent webview.
 */

/**
 * Type of diff change
 */
export type DiffChangeType = 'added' | 'removed' | 'modified' | 'renamed';

/**
 * A single hunk within a file diff
 */
export interface DiffHunk {
  /** Header line (e.g., "@@ -1,5 +1,7 @@") */
  header: string;
  /** Old file start line */
  oldStart: number;
  /** Old file line count */
  oldLines: number;
  /** New file start line */
  newStart: number;
  /** New file line count */
  newLines: number;
  /** Individual diff lines */
  lines: DiffLine[];
}

/**
 * A single line within a diff hunk
 */
export interface DiffLine {
  /** Line type: context, addition, or removal */
  type: 'context' | 'added' | 'removed' | 'hunk_header';
  /** The line content (without the +/- prefix) */
  content: string;
  /** Line number in old file (for context/removed lines) */
  oldLineNumber?: number;
  /** Line number in new file (for context/added lines) */
  newLineNumber?: number;
}

/**
 * A file-level diff entry
 */
export interface DiffFileEntry {
  /** File path (new path for renamed/modified) */
  filePath: string;
  /** Old file path (for renames) */
  oldFilePath?: string;
  /** Type of change */
  changeType: DiffChangeType;
  /** Parsed hunks */
  hunks: DiffHunk[];
  /** Number of lines added */
  additions: number;
  /** Number of lines removed */
  deletions: number;
  /** Whether the file is binary */
  isBinary: boolean;
}

/**
 * Complete diff card data
 */
export interface DiffCardData {
  /** Diff source description (e.g., "HEAD", "main..feature") */
  source: string;
  /** Parsed file entries */
  files: DiffFileEntry[];
  /** Total additions across all files */
  totalAdditions: number;
  /** Total deletions across all files */
  totalDeletions: number;
  /** Total files changed */
  totalFiles: number;
  /** Display configuration */
  display: DiffDisplayConfig;
}

/**
 * Display configuration for diff cards
 */
export interface DiffDisplayConfig {
  /** Whether to show line numbers */
  showLineNumbers: boolean;
  /** Maximum lines per hunk before collapsing */
  maxLinesPerHunk: number;
  /** Whether to collapse unchanged regions */
  collapseContext: boolean;
  /** Number of context lines to show around changes */
  contextLines: number;
  /** Whether to show file stats summary */
  showStats: boolean;
  /** Default fold state */
  foldDefault: 'expanded' | 'collapsed';
  /** Theme for syntax highlighting */
  theme: 'light' | 'dark' | 'system';
}

/**
 * Default diff display configuration
 */
export const DEFAULT_DIFF_DISPLAY_CONFIG: DiffDisplayConfig = {
  showLineNumbers: true,
  maxLinesPerHunk: 50,
  collapseContext: true,
  contextLines: 3,
  showStats: true,
  foldDefault: 'collapsed',
  theme: 'system',
};

/**
 * Parse a unified diff string into structured DiffCardData
 */
export function parseUnifiedDiff(
  diffText: string,
  source: string = 'HEAD',
  display?: Partial<DiffDisplayConfig>
): DiffCardData {
  const files: DiffFileEntry[] = [];
  let currentFile: DiffFileEntry | null = null;
  let currentHunk: DiffHunk | null = null;
  let totalAdditions = 0;
  let totalDeletions = 0;

  const lines = diffText.split('\n');

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];

    // New file diff header
    if (line.startsWith('diff --git')) {
      // Save previous file
      if (currentFile) {
        if (currentHunk) {
          currentFile.hunks.push(currentHunk);
        }
        files.push(currentFile);
      }

      // Parse file paths from "diff --git a/path b/path"
      const match = line.match(/^diff --git a\/(.*) b\/(.*)$/);
      currentFile = {
        filePath: match ? match[2] : 'unknown',
        oldFilePath: match ? match[1] : undefined,
        changeType: 'modified',
        hunks: [],
        additions: 0,
        deletions: 0,
        isBinary: false,
      };
      currentHunk = null;
      continue;
    }

    // Change type detection
    if (currentFile) {
      if (line.startsWith('new file mode')) {
        currentFile.changeType = 'added';
      } else if (line.startsWith('deleted file mode')) {
        currentFile.changeType = 'removed';
      } else if (line.startsWith('rename from')) {
        currentFile.changeType = 'renamed';
      } else if (line.startsWith('Binary files') || line.startsWith('Binary file')) {
        currentFile.isBinary = true;
      }
    }

    // Hunk header
    const hunkMatch = line.match(/^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@/);
    if (hunkMatch && currentFile) {
      // Save previous hunk
      if (currentHunk) {
        currentFile.hunks.push(currentHunk);
      }

      currentHunk = {
        header: line,
        oldStart: parseInt(hunkMatch[1], 10),
        oldLines: parseInt(hunkMatch[2] || '1', 10),
        newStart: parseInt(hunkMatch[3], 10),
        newLines: parseInt(hunkMatch[4] || '1', 10),
        lines: [],
      };
      continue;
    }

    // Diff lines within a hunk
    if (currentHunk) {
      if (line.startsWith('+')) {
        currentHunk.lines.push({
          type: 'added',
          content: line.substring(1),
          newLineNumber: currentHunk.newStart + currentHunk.lines.filter(l => l.type === 'added' || l.type === 'context').length,
        });
        if (currentFile) {
          currentFile.additions++;
          totalAdditions++;
        }
      } else if (line.startsWith('-')) {
        currentHunk.lines.push({
          type: 'removed',
          content: line.substring(1),
          oldLineNumber: currentHunk.oldStart + currentHunk.lines.filter(l => l.type === 'removed' || l.type === 'context').length,
        });
        if (currentFile) {
          currentFile.deletions++;
          totalDeletions++;
        }
      } else if (line.startsWith(' ')) {
        currentHunk.lines.push({
          type: 'context',
          content: line.substring(1),
          oldLineNumber: currentHunk.oldStart + currentHunk.lines.filter(l => l.type === 'removed' || l.type === 'context').length,
          newLineNumber: currentHunk.newStart + currentHunk.lines.filter(l => l.type === 'added' || l.type === 'context').length,
        });
      }
      // Skip "\ No newline at end of file" and other meta lines
    }
  }

  // Save last file
  if (currentFile) {
    if (currentHunk) {
      currentFile.hunks.push(currentHunk);
    }
    files.push(currentFile);
  }

  return {
    source,
    files,
    totalAdditions,
    totalDeletions,
    totalFiles: files.length,
    display: { ...DEFAULT_DIFF_DISPLAY_CONFIG, ...display },
  };
}

/**
 * Get change type icon
 */
export function getChangeTypeIcon(type: DiffChangeType): string {
  const icons: Record<DiffChangeType, string> = {
    added: '🟢',
    removed: '🔴',
    modified: '🟡',
    renamed: '🔄',
  };
  return icons[type];
}

/**
 * Get change type label
 */
export function getChangeTypeLabel(type: DiffChangeType): string {
  const labels: Record<DiffChangeType, string> = {
    added: 'Added',
    removed: 'Removed',
    modified: 'Modified',
    renamed: 'Renamed',
  };
  return labels[type];
}

/**
 * Get change type CSS class
 */
export function getChangeTypeClass(type: DiffChangeType): string {
  const classes: Record<DiffChangeType, string> = {
    added: 'diff-file-added',
    removed: 'diff-file-removed',
    modified: 'diff-file-modified',
    renamed: 'diff-file-renamed',
  };
  return classes[type];
}