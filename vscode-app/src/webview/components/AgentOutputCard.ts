/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

// AgentOutputCard.ts
// Renders the output of an agent action (tool call, reasoning, etc.) in the VSCode webview.

import {AgentOutput, DiffCardData, DisplayConfig} from './AgentOutputCard.types';

const escapeHtml = (str: string): string => {
  const div = document.createElement('div');
  div.textContent = str;
  return div.innerHTML;
};

/**
 * Get the appropriate icon for a section type
 */
function getSectionIcon(type: string): string {
  switch (type) {
    case 'thinking':
      return '';
    case 'tool':
      return '';
    case 'apply':
      return '✏️';
    case 'diff':
      return '';
    case 'error':
      return '❌';
    default:
      return '•';
  }
}

/**
 * Create thinking/reasoning stream section (DeepSeek R1 reasoning_content)
 * Uses a <details> block that can be toggled; auto-opens if short or if showReasoning is true.
 */
function createThinkingSection(
  thinkingText: string,
  display: DisplayConfig,
  isFinal: boolean
): string {
  if (!thinkingText || !thinkingText.trim()) return '';
  const escaped = escapeHtml(thinkingText);
  const isLong = thinkingText.length > 500;
  // Auto-open if the user wants to see reasoning, unless it's too long
  const openAttr = display.showReasoning && !isLong ? 'open' : '';

  return `
    <details class="thinking-stream-section" ${openAttr}>
      <summary class="thinking-stream-summary">
        ${getSectionIcon('thinking')}
        <span class="thinking-label">Thinking</span>
        <span class="thinking-chars">(${thinkingText.length} chars)</span>
        ${isFinal ? '<span class="status-badge">✓</span>' : '<span class="status-badge streaming">●</span>'}
      </summary>
      <div class="thinking-stream-content">
        <pre>${escaped}</pre>
      </div>
    </details>
  `;
}

/**
 * Create a tool call section
 */
function createToolSection(toolName: string, toolArgs: any, display: DisplayConfig): string {
  const argsStr = typeof toolArgs === 'string' ? toolArgs : JSON.stringify(toolArgs, null, 2);
  const escapedArgs = escapeHtml(argsStr);
  return `
    <details class="tool-section" open>
      <summary class="tool-summary">
        ${getSectionIcon('tool')}
        <span class="tool-name">${escapeHtml(toolName)}</span>
      </summary>
      <div class="tool-args">
        <pre>${escapedArgs}</pre>
      </div>
    </details>
  `;
}

/**
 * Create a diff card section
 */
function createDiffSection(diffData: DiffCardData): string {
  const { filePath, diff } = diffData;
  return `
    <div class="diff-card">
      <div class="diff-header">
        ${getSectionIcon('diff')}
        <span class="diff-file">${escapeHtml(filePath)}</span>
      </div>
      <div class="diff-content">
        <pre>${escapeHtml(diff)}</pre>
      </div>
    </div>
  `;
}

/**
 * Create an apply_edits button card
 */
function createApplyEditsButton(actionId: string): string {
  return `
    <div class="action-button-wrapper">
      <button class="apply-edits-button" data-action-id="${escapeHtml(actionId)}">
        ${getSectionIcon('apply')} Apply Edits
      </button>
    </div>
  `;
}

/**
 * Main render function for AgentOutputCard
 * This is called from the webview to render a complete card.
 */
export function renderAgentOutputCard(output: AgentOutput, display: DisplayConfig): string {
  const { toolCalls, reasoning, response, diff, applyActionId, isFinal } = output;

  let sections: string[] = [];

  // 1. Thinking/reasoning section (if present)
  if (reasoning) {
    sections.push(createThinkingSection(reasoning, display, isFinal));
  }

  // 2. Tool calls (if any)
  if (toolCalls && toolCalls.length > 0) {
    for (const tc of toolCalls) {
      sections.push(createToolSection(tc.name, tc.args, display));
    }
  }

  // 3. Diff (if present)
  if (diff) {
    sections.push(createDiffSection(diff));
  }

  // 4. Apply edits button (if present)
  if (applyActionId) {
    sections.push(createApplyEditsButton(applyActionId));
  }

  // 5. Final response (text) – show only if not already shown in tool or reasoning
  if (response && !reasoning && !toolCalls) {
    sections.push(`
      <div class="response-text">
        <pre>${escapeHtml(response)}</pre>
      </div>
    `);
  }

  return `
    <div class="agent-output-card" data-final="${isFinal}">
      ${sections.join('\n')}
    </div>
  `;
}

/**
 * Attach event listeners to the card after mounting
 * This is a separate function so the webview can call it after setting innerHTML.
 */
export function attachAgentOutputListeners(cardDiv: HTMLElement): void {
  // Apply edits button
  const applyBtn = cardDiv.querySelector('.apply-edits-button');
  if (applyBtn) {
    applyBtn.addEventListener('click', (e) => {
      e.stopPropagation();
      const actionId = (applyBtn as HTMLElement).dataset.actionId;
      if (actionId) {
        const event = new CustomEvent('output-card-action', {
          bubbles: true,
          detail: { actionId }
        });
        cardDiv.dispatchEvent(event);
      }
    });
  }

  // Add style for apply_edits button (if not already present)
  if (cardDiv.querySelector('.apply-edits-button') && !document.getElementById('agent-card-apply-styles')) {
    const style = document.createElement('style');
    style.id = 'agent-card-apply-styles';
    style.textContent = `
      .apply-edits-button {
        background: #27ae60;
        color: white;
        border: none;
        padding: 4px 12px;
        border-radius: 4px;
        cursor: pointer;
        font-size: 12px;
        margin: 4px 0;
      }
      .apply-edits-button:hover {
        background: #219a52;
      }
      .thinking-stream-section {
        margin: 4px 0;
        border-left: 2px solid #6a8bff;
        padding-left: 8px;
      }
      .thinking-stream-summary {
        cursor: pointer;
        user-select: none;
        font-size: 13px;
        color: #6a8bff;
      }
      .thinking-stream-content {
        margin: 4px 0;
        background: rgba(106, 139, 255, 0.08);
        border-radius: 4px;
        padding: 6px 8px;
      }
      .thinking-stream-content pre {
        margin: 0;
        white-space: pre-wrap;
        word-break: break-word;
        font-size: 12px;
        font-family: var(--vscode-editor-font-family, monospace);
        color: var(--vscode-editor-foreground, #ccc);
      }
      .status-badge {
        font-size: 11px;
        margin-left: 6px;
        opacity: 0.7;
      }
      .status-badge.streaming {
        color: #f1c40f;
      }
      .tool-section {
        margin: 4px 0;
        border-left: 2px solid #e67e22;
        padding-left: 8px;
      }
      .tool-summary {
        cursor: pointer;
        user-select: none;
        font-size: 13px;
        color: #e67e22;
      }
      .tool-args pre {
        margin: 4px 0;
        background: rgba(230, 126, 34, 0.08);
        border-radius: 4px;
        padding: 6px 8px;
        white-space: pre-wrap;
        word-break: break-word;
        font-size: 12px;
      }
      .diff-card {
        margin: 4px 0;
        border-left: 2px solid #2ecc71;
        padding-left: 8px;
      }
      .diff-header {
        font-size: 13px;
        color: #2ecc71;
      }
      .diff-content pre {
        margin: 4px 0;
        background: rgba(46, 204, 113, 0.08);
        border-radius: 4px;
        padding: 6px 8px;
        white-space: pre-wrap;
        word-break: break-word;
        font-size: 12px;
      }
      .response-text {
        margin: 4px 0;
        background: rgba(255,255,255,0.05);
        border-radius: 4px;
        padding: 6px 8px;
      }
      .response-text pre {
        margin: 0;
        white-space: pre-wrap;
        word-break: break-word;
        font-size: 13px;
        font-family: var(--vscode-editor-font-family, monospace);
      }
    `;
    document.head.appendChild(style);
  }
}

export default { renderAgentOutputCard, attachAgentOutputListeners };
