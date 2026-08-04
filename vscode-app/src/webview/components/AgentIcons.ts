/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

/**
 * AgentIcons - Modern SVG icons for agent output cards
 *
 * Replaces emoji and CSS-based icons with clean, consistent SVG icons.
 * All SVGs use currentColor for theming and are sized 16x16 by default.
 */

/** SVG icon definitions */
export const ICONS = {
  // Status icons
  success: `<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" aria-label="Success"><circle cx="12" cy="12" r="10"/><path d="M8 12l3 3 5-6"/></svg>`,
  
  error: `<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" aria-label="Error"><circle cx="12" cy="12" r="10"/><line x1="15" y1="9" x2="9" y2="15"/><line x1="9" y1="9" x2="15" y2="15"/></svg>`,
  
  warning: `<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-label="Warning"><path d="M10.29 3.86L1.82 18a2 2 0 001.71 3h16.94a2 2 0 001.71-3L13.71 3.86a2 2 0 00-3.42 0z"/><line x1="12" y1="9" x2="12" y2="13"/><line x1="12" y1="17" x2="12.01" y2="17"/></svg>`,

  running: `<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-label="Running"><circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 16 14"/></svg>`,

  pending: `<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-label="Pending"><circle cx="12" cy="12" r="10"/></svg>`,

  // Section icons
  toolCalls: `<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-label="Tool Calls"><path d="M14.7 6.3a1 1 0 000 1.4l1.6 1.6a1 1 0 001.4 0l3.77-3.77a6 6 0 01-7.94 7.94l-6.91 6.91a2.12 2.12 0 01-3-3l6.91-6.91a6 6 0 017.94-7.94l-3.76 3.76z"/></svg>`,

  buildOutput: `<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-label="Build Output"><polyline points="16 18 22 12 16 6"/><polyline points="8 6 2 12 8 18"/></svg>`,

  codeBlocks: `<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-label="Code Blocks"><polyline points="16 18 22 12 16 6"/><polyline points="8 6 2 12 8 18"/><line x1="12" y1="2" x2="12" y2="22"/></svg>`,

  thinking: `<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-label="Thinking"><path d="M9.18 6.82a3 3 0 00-4.24 0 3 3 0 000 4.24l1.42 1.42"/><path d="M14.82 17.18a3 3 0 004.24 0 3 3 0 000-4.24l-1.42-1.42"/><path d="M14.83 9.17a3 3 0 00-4.24 0L9.17 10.59"/><path d="M9.17 13.41l1.42 1.42"/><line x1="12" y1="6" x2="12" y2="8"/><line x1="12" y1="16" x2="12" y2="18"/><line x1="6" y1="12" x2="8" y2="12"/><line x1="16" y1="12" x2="18" y2="12"/></svg>`,

  // Expand/collapse icons
  expand: `<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" aria-label="Expand"><polyline points="6 9 12 15 18 9"/></svg>`,

  collapse: `<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" aria-label="Collapse"><polyline points="18 15 12 9 6 15"/></svg>`,

  // Footer icons
  tokens: `<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-label="Tokens"><circle cx="12" cy="12" r="10"/><line x1="2" y1="12" x2="22" y2="12"/><path d="M12 2a15.3 15.3 0 014 10 15.3 15.3 0 01-4 10 15.3 15.3 0 01-4-10 15.3 15.3 0 014-10z"/></svg>`,

  confidence: `<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-label="Confidence"><path d="M12 20V10"/><path d="M18 20V4"/><path d="M6 20v-4"/></svg>`,

  copy: `<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-label="Copy"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"/><path d="M5 15H4a2 2 0 01-2-2V4a2 2 0 012-2h9a2 2 0 012 2v1"/></svg>`,

  retry: `<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-label="Retry"><polyline points="23 4 23 10 17 10"/><path d="M20.49 15a9 9 0 11-2.12-9.36L23 10"/></svg>`,

  thumbsUp: `<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-label="Thumbs Up"><path d="M14 9V5a3 3 0 00-3-3l-4 9v11h11.28a2 2 0 002-1.7l1.38-9a2 2 0 00-2-2.3H14z"/><rect x="2" y="9" width="4" height="11" rx="1"/></svg>`,

  thumbsDown: `<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-label="Thumbs Down"><path d="M10 15v4a3 3 0 003 3l4-9V2H5.72a2 2 0 00-2 1.7l-1.38 9a2 2 0 002 2.3H10z"/><rect x="18" y="2" width="4" height="11" rx="1"/></svg>`,
};

/**
 * Get the SVG string for an icon name.
 * Returns a wrapped span for proper alignment.
 */
export function getIcon(name: keyof typeof ICONS): string {
  return ICONS[name] || '';
}

/**
 * Get status icon SVG based on tool call status
 */
export function getToolStatusIcon(error?: string, success?: boolean): string {
  if (error) {
    return `<span class="tool-status-icon status-error">${ICONS.error}</span>`;
  }
  if (success === false) {
    return `<span class="tool-status-icon status-warning">${ICONS.warning}</span>`;
  }
  return `<span class="tool-status-icon status-success">${ICONS.success}</span>`;
}

/**
 * Get section header icon
 */
export function getSectionIcon(type: 'toolCalls' | 'buildOutput' | 'codeBlocks' | 'thinking'): string {
  const iconMap = {
    toolCalls: ICONS.toolCalls,
    buildOutput: ICONS.buildOutput,
    codeBlocks: ICONS.codeBlocks,
    thinking: ICONS.thinking,
  };
  return `<span class="section-icon">${iconMap[type]}</span>`;
}

/**
 * Get expand/collapse toggle with SVG arrow
 */
export function getExpandToggle(isCollapsed: boolean): string {
  const icon = isCollapsed ? ICONS.expand : ICONS.collapse;
  const label = isCollapsed ? 'Expand' : 'Collapse';
  return `<span class="expand-toggle-icon">${icon}</span><span class="expand-toggle-label">${label}</span>`;
}
