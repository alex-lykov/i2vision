/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

/**
 * DiffCard - Rich diff display component for agent webview
 *
 * Renders git diff output as an interactive, color-coded card with:
 * - File-level summary with change type badges
 * - Hunk-level expand/collapse
 * - Line numbers for added/removed/context lines
 * - Stats summary (additions/deletions/files changed)
 * - Smart context collapsing
 *
 * NOTE: This is NOT React/JSX - it's plain TypeScript that generates HTML strings.
 * No bundler required - works directly in VSCode webviews.
 */

import {
  DiffCardData,
  DiffFileEntry,
  DiffHunk,
  DiffLine,
  DiffDisplayConfig,
  DiffChangeType,
  parseUnifiedDiff,
  getChangeTypeIcon,
  getChangeTypeLabel,
  getChangeTypeClass,
  DEFAULT_DIFF_DISPLAY_CONFIG,
} from './DiffCard.types';
import { ICONS } from './AgentIcons';

/**
 * Escape HTML to prevent XSS
 */
function escapeHtml(text: string): string {
  if (!text) return '';
  const div = document.createElement('div');
  div.textContent = text;
  return div.innerHTML;
}

/**
 * Create a complete diff card from raw diff text
 */
export function createDiffCard(
  diffText: string,
  source: string = 'HEAD',
  display?: Partial<DiffDisplayConfig>
): HTMLElement {
  const data = parseUnifiedDiff(diffText, source, display);
  return createDiffCardFromData(data);
}

/**
 * Create a diff card from pre-parsed data
 */
export function createDiffCardFromData(data: DiffCardData): HTMLElement {
  const cardDiv = document.createElement('div');
  cardDiv.className = `diff-card theme-${data.display.theme}`;
  cardDiv.dataset.source = data.source;

  cardDiv.innerHTML = `
    ${createDiffHeader(data)}
    ${createDiffStats(data)}
    ${createDiffFiles(data)}
  `;

  // Attach event listeners for expand/collapse
  attachDiffCardListeners(cardDiv, data.display);

  return cardDiv;
}

/**
 * Create diff card header
 */
function createDiffHeader(data: DiffCardData): string {
  return `
    <div class="diff-card-header">
      <div class="diff-header-left">
        <span class="diff-icon">📝</span>
        <span class="diff-source">${escapeHtml(data.source)}</span>
      </div>
      <div class="diff-header-right">
        <span class="diff-file-count">${data.totalFiles} file${data.totalFiles !== 1 ? 's' : ''}</span>
        <span class="diff-additions">+${data.totalAdditions}</span>
        <span class="diff-deletions">-${data.totalDeletions}</span>
      </div>
    </div>
  `;
}

/**
 * Create diff stats summary bar
 */
function createDiffStats(data: DiffCardData): string {
  if (!data.display.showStats) return '';

  const total = data.totalAdditions + data.totalDeletions;
  const addPercent = total > 0 ? (data.totalAdditions / total) * 100 : 0;
  const delPercent = total > 0 ? (data.totalDeletions / total) * 100 : 0;

  return `
    <div class="diff-stats-bar">
      <div class="diff-stats-additions" style="width: ${addPercent}%">+${data.totalAdditions}</div>
      <div class="diff-stats-deletions" style="width: ${delPercent}%">-${data.totalDeletions}</div>
    </div>
  `;
}

/**
 * Create file-level diff sections
 */
function createDiffFiles(data: DiffCardData): string {
  if (data.files.length === 0) {
    return '<div class="diff-empty">No changes detected.</div>';
  }

  const fileSections = data.files.map((file, index) =>
    createDiffFileSection(file, index, data.display)
  ).join('');

  return `
    <div class="diff-files">
      ${fileSections}
    </div>
  `;
}

/**
 * Create a single file diff section
 */
function createDiffFileSection(
  file: DiffFileEntry,
  index: number,
  display: DiffDisplayConfig
): string {
  const changeIcon = getChangeTypeIcon(file.changeType);
  const changeLabel = getChangeTypeLabel(file.changeType);
  const changeClass = getChangeTypeClass(file.changeType);
  const isCollapsed = display.foldDefault === 'collapsed';

  const filePath = file.changeType === 'renamed' && file.oldFilePath
    ? `${escapeHtml(file.oldFilePath)} → ${escapeHtml(file.filePath)}`
    : escapeHtml(file.filePath);

  return `
    <div class="diff-file-section ${changeClass}" data-file-index="${index}">
      <div class="diff-file-header" onclick="toggleDiffFile(this)">
        <span class="diff-file-toggle">${isCollapsed ? '▶' : '▼'}</span>
        <span class="diff-file-icon">${changeIcon}</span>
        <span class="diff-file-path">${filePath}</span>
        <span class="diff-file-badge ${changeClass}">${changeLabel}</span>
        <span class="diff-file-stats">
          <span class="diff-add-count">+${file.additions}</span>
          <span class="diff-del-count">-${file.deletions}</span>
        </span>
      </div>
      <div class="diff-file-content" style="display: ${isCollapsed ? 'none' : 'block'}">
        ${file.isBinary
          ? '<div class="diff-binary-notice">Binary file (content not shown)</div>'
          : file.hunks.map(hunk => createDiffHunk(hunk, display)).join('')
        }
      </div>
    </div>
  `;
}

/**
 * Create a single diff hunk
 */
function createDiffHunk(hunk: DiffHunk, display: DiffDisplayConfig): string {
  const lines = hunk.lines;
  const shouldCollapse = lines.length > display.maxLinesPerHunk;

  // If collapsing context, filter out context lines beyond the threshold
  const visibleLines = display.collapseContext
    ? collapseContextLines(lines, display.contextLines)
    : lines;

  const lineElements = visibleLines.map(line =>
    createDiffLine(line, display.showLineNumbers)
  ).join('');

  return `
    <div class="diff-hunk" data-old-start="${hunk.oldStart}" data-new-start="${hunk.newStart}">
      <div class="diff-hunk-header" onclick="toggleDiffHunk(this)">
        <span class="diff-hunk-toggle">▼</span>
        <code class="diff-hunk-info">${escapeHtml(hunk.header)}</code>
      </div>
      <div class="diff-hunk-content">
        <table class="diff-table">
          ${lineElements}
        </table>
        ${shouldCollapse ? `
          <div class="diff-truncation-notice">
            📄 Showing ${display.maxLinesPerHunk} of ${lines.length} lines.
            <button class="diff-expand-btn" onclick="expandDiffHunk(this)">Show all</button>
          </div>
        ` : ''}
      </div>
    </div>
  `;
}

/**
 * Create a single diff line as a table row
 */
function createDiffLine(line: DiffLine, showLineNumbers: boolean): string {
  const typeClass = `diff-line-${line.type}`;
  const prefix = line.type === 'added' ? '+' : line.type === 'removed' ? '-' : ' ';

  const oldNum = showLineNumbers && line.oldLineNumber !== undefined
    ? `<td class="diff-line-num diff-old-num">${line.oldLineNumber}</td>`
    : '<td class="diff-line-num diff-old-num"></td>';

  const newNum = showLineNumbers && line.newLineNumber !== undefined
    ? `<td class="diff-line-num diff-new-num">${line.newLineNumber}</td>`
    : '<td class="diff-line-num diff-new-num"></td>';

  return `
    <tr class="${typeClass}">
      ${oldNum}
      ${newNum}
      <td class="diff-line-prefix">${prefix}</td>
      <td class="diff-line-content">${escapeHtml(line.content)}</td>
    </tr>
  `;
}

/**
 * Collapse context lines, keeping only N lines around changes
 */
function collapseContextLines(
  lines: DiffLine[],
  contextLines: number
): DiffLine[] {
  // Find indices of added/removed lines
  const changeIndices = lines
    .map((line, i) => line.type === 'added' || line.type === 'removed' ? i : -1)
    .filter(i => i >= 0);

  if (changeIndices.length === 0) {
    // No changes, return first few context lines
    return lines.slice(0, contextLines * 2);
  }

  // Build set of visible indices (change lines + context)
  const visibleIndices = new Set<number>();
  for (const idx of changeIndices) {
    for (let j = Math.max(0, idx - contextLines); j <= Math.min(lines.length - 1, idx + contextLines); j++) {
      visibleIndices.add(j);
    }
  }

  // Build result with collapse markers for gaps
  const result: DiffLine[] = [];
  let lastVisible = -1;

  for (let i = 0; i < lines.length; i++) {
    if (visibleIndices.has(i)) {
      result.push(lines[i]);
      lastVisible = i;
    } else if (i > 0 && !visibleIndices.has(i - 1) && visibleIndices.has(i + 1)) {
      // About to enter a visible region - add collapse marker
      const skipped = i - lastVisible - 1;
      if (skipped > 0) {
        result.push({
          type: 'context',
          content: `⋯ ${skipped} unchanged line${skipped !== 1 ? 's' : ''} ⋯`,
        });
      }
    }
  }

  return result;
}

/**
 * CSS styles for the diff card
 */
export function getDiffCardStyles(): string {
  return `
    .diff-card {
      border: 1px solid var(--vscode-editorWidget-border, #333);
      border-radius: 6px;
      overflow: hidden;
      font-family: var(--vscode-editor-font-family, 'Consolas', monospace);
      font-size: var(--vscode-editor-font-size, 13px);
      background: var(--vscode-editor-background, #1e1e1e);
      color: var(--vscode-editor-foreground, #d4d4d4);
    }

    .diff-card-header {
      display: flex;
      justify-content: space-between;
      align-items: center;
      padding: 8px 12px;
      background: var(--vscode-editorWidget-background, #252526);
      border-bottom: 1px solid var(--vscode-editorWidget-border, #333);
    }

    .diff-header-left {
      display: flex;
      align-items: center;
      gap: 8px;
    }

    .diff-icon {
      font-size: 1.1em;
    }

    .diff-source {
      font-weight: 600;
      color: var(--vscode-foreground, #d4d4d4);
    }

    .diff-header-right {
      display: flex;
      align-items: center;
      gap: 12px;
      font-size: 0.85em;
    }

    .diff-file-count {
      color: var(--vscode-descriptionForeground, #888);
    }

    .diff-additions {
      color: #4ec9b0;
      font-weight: 600;
    }

    .diff-deletions {
      color: #f44747;
      font-weight: 600;
    }

    .diff-stats-bar {
      display: flex;
      height: 6px;
      background: var(--vscode-editorWidget-border, #333);
    }

    .diff-stats-additions {
      background: #4ec9b0;
      color: transparent;
      font-size: 0;
      min-width: 2px;
    }

    .diff-stats-deletions {
      background: #f44747;
      color: transparent;
      font-size: 0;
      min-width: 2px;
    }

    .diff-files {
      max-height: 600px;
      overflow-y: auto;
    }

    .diff-file-section {
      border-bottom: 1px solid var(--vscode-editorWidget-border, #333);
    }

    .diff-file-section:last-child {
      border-bottom: none;
    }

    .diff-file-header {
      display: flex;
      align-items: center;
      gap: 8px;
      padding: 6px 12px;
      background: var(--vscode-editorWidget-background, #252526);
      cursor: pointer;
      user-select: none;
    }

    .diff-file-header:hover {
      background: var(--vscode-list-hoverBackground, #2a2d2e);
    }

    .diff-file-toggle {
      font-size: 0.7em;
      color: var(--vscode-descriptionForeground, #888);
      width: 12px;
    }

    .diff-file-icon {
      font-size: 0.9em;
    }

    .diff-file-path {
      flex: 1;
      font-family: var(--vscode-editor-font-family, monospace);
      font-size: 0.9em;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .diff-file-badge {
      font-size: 0.7em;
      padding: 1px 6px;
      border-radius: 3px;
      font-weight: 600;
      text-transform: uppercase;
    }

    .diff-file-added .diff-file-badge {
      background: rgba(78, 201, 176, 0.2);
      color: #4ec9b0;
    }

    .diff-file-removed .diff-file-badge {
      background: rgba(244, 71, 71, 0.2);
      color: #f44747;
    }

    .diff-file-modified .diff-file-badge {
      background: rgba(220, 200, 100, 0.2);
      color: #dcc864;
    }

    .diff-file-renamed .diff-file-badge {
      background: rgba(100, 150, 220, 0.2);
      color: #6496dc;
    }

    .diff-file-stats {
      display: flex;
      gap: 8px;
      font-size: 0.8em;
    }

    .diff-add-count {
      color: #4ec9b0;
    }

    .diff-del-count {
      color: #f44747;
    }

    .diff-file-content {
      overflow-x: auto;
    }

    .diff-binary-notice {
      padding: 12px;
      text-align: center;
      color: var(--vscode-descriptionForeground, #888);
      font-style: italic;
    }

    .diff-hunk {
      border-top: 1px solid var(--vscode-editorWidget-border, #333);
    }

    .diff-hunk-header {
      display: flex;
      align-items: center;
      gap: 6px;
      padding: 4px 12px;
      background: rgba(100, 150, 220, 0.08);
      cursor: pointer;
      user-select: none;
      font-size: 0.85em;
    }

    .diff-hunk-header:hover {
      background: rgba(100, 150, 220, 0.15);
    }

    .diff-hunk-toggle {
      font-size: 0.7em;
      color: var(--vscode-descriptionForeground, #888);
    }

    .diff-hunk-info {
      color: rgba(100, 150, 220, 0.8);
      font-size: 0.85em;
    }

    .diff-hunk-content {
      overflow-x: auto;
    }

    .diff-table {
      width: 100%;
      border-collapse: collapse;
      font-family: var(--vscode-editor-font-family, monospace);
      font-size: var(--vscode-editor-font-size, 13px);
      line-height: 1.4;
    }

    .diff-line-num {
      width: 50px;
      min-width: 50px;
      padding: 0 8px;
      text-align: right;
      color: var(--vscode-descriptionForeground, #858585);
      background: var(--vscode-editorGutter-background, #1e1e1e);
      user-select: none;
      font-size: 0.85em;
      vertical-align: top;
    }

    .diff-line-prefix {
      width: 15px;
      min-width: 15px;
      padding: 0 4px;
      text-align: center;
      color: var(--vscode-descriptionForeground, #858585);
      user-select: none;
      font-size: 0.85em;
      vertical-align: top;
    }

    .diff-line-content {
      padding: 0 8px;
      white-space: pre-wrap;
      word-break: break-all;
      vertical-align: top;
    }

    .diff-line-added {
      background: rgba(78, 201, 176, 0.12);
    }

    .diff-line-added .diff-line-prefix {
      color: #4ec9b0;
    }

    .diff-line-added .diff-line-content {
      color: #b5cea8;
    }

    .diff-line-removed {
      background: rgba(244, 71, 71, 0.12);
    }

    .diff-line-removed .diff-line-prefix {
      color: #f44747;
    }

    .diff-line-removed .diff-line-content {
      color: #ce9178;
    }

    .diff-line-context {
      background: transparent;
    }

    .diff-truncation-notice {
      padding: 8px 12px;
      text-align: center;
      color: var(--vscode-descriptionForeground, #888);
      font-size: 0.85em;
      background: var(--vscode-editorWidget-background, #252526);
    }

    .diff-expand-btn {
      background: var(--vscode-button-background, #0e639c);
      color: var(--vscode-button-foreground, #fff);
      border: none;
      padding: 2px 10px;
      border-radius: 3px;
      cursor: pointer;
      font-size: 0.85em;
      margin-left: 8px;
    }

    .diff-expand-btn:hover {
      background: var(--vscode-button-hoverBackground, #1177bb);
    }

    .diff-empty {
      padding: 20px;
      text-align: center;
      color: var(--vscode-descriptionForeground, #888);
      font-style: italic;
    }

    /* Light theme overrides */
    .diff-card.theme-light {
      background: #ffffff;
      color: #1e1e1e;
    }

    .diff-card.theme-light .diff-line-added {
      background: rgba(40, 167, 69, 0.12);
    }

    .diff-card.theme-light .diff-line-removed {
      background: rgba(220, 53, 69, 0.12);
    }

    .diff-card.theme-light .diff-additions,
    .diff-card.theme-light .diff-add-count {
      color: #28a745;
    }

    .diff-card.theme-light .diff-deletions,
    .diff-card.theme-light .diff-del-count {
      color: #dc3545;
    }

    .diff-card.theme-light .diff-stats-additions {
      background: #28a745;
    }

    .diff-card.theme-light .diff-stats-deletions {
      background: #dc3545;
    }
  `;
}

/**
 * Toggle a file section expand/collapse
 */
function toggleDiffFile(header: HTMLElement): void {
  const content = header.nextElementSibling as HTMLElement;
  const toggle = header.querySelector('.diff-file-toggle') as HTMLElement;

  if (content.style.display === 'none') {
    content.style.display = 'block';
    toggle.textContent = '▼';
  } else {
    content.style.display = 'none';
    toggle.textContent = '▶';
  }
}

/**
 * Toggle a hunk section expand/collapse
 */
function toggleDiffHunk(header: HTMLElement): void {
  const content = header.nextElementSibling as HTMLElement;
  const toggle = header.querySelector('.diff-hunk-toggle') as HTMLElement;

  if (content.style.display === 'none') {
    content.style.display = 'block';
    toggle.textContent = '▼';
  } else {
    content.style.display = 'none';
    toggle.textContent = '▶';
  }
}

/**
 * Expand a truncated hunk
 */
function expandDiffHunk(button: HTMLElement): void {
  const notice = button.parentElement as HTMLElement;
  notice.style.display = 'none';
  // In a real implementation, this would load the full hunk content
  // For now, we just hide the truncation notice
}

/**
 * Attach event listeners to diff card
 */
function attachDiffCardListeners(cardDiv: HTMLElement, display: DiffDisplayConfig): void {
  // Expose toggle functions globally for onclick handlers
  (window as any).toggleDiffFile = toggleDiffFile;
  (window as any).toggleDiffHunk = toggleDiffHunk;
  (window as any).expandDiffHunk = expandDiffHunk;
}

/**
 * Convert a git_diff tool result into a DiffCard HTML string
 * Convenience function for use in AgentBridge tool result formatting
 */
export function formatDiffResult(
  diffText: string,
  source: string = 'HEAD',
  display?: Partial<DiffDisplayConfig>
): string {
  const data = parseUnifiedDiff(diffText, source, display);
  const card = createDiffCardFromData(data);
  return card.outerHTML;
}