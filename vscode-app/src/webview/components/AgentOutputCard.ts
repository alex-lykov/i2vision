/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

/**
 * AgentOutputCard - Plain TypeScript HTML Generator
 * 
 * Unified agent output display with configurable formatting, collapsing, and styling.
 * Used in webview to display agent responses consistently across all agent types.
 * 
 * NOTE: This is NOT React/JSX - it's plain TypeScript that generates HTML strings.
 * No bundler required - works directly in VSCode webviews.
 */

import {
  AgentOutputCard,
  AgentStatus,
  CardContent,
  CardFooter,
  CardHeader,
  DEFAULT_DISPLAY_CONFIG,
  DisplayConfig,
  formatDuration,
  getPreviewText,
  getProviderColor,
  getProviderDisplayName,
  getStatusIcon,
  ProviderType,
  shouldAutoCollapse,
  ToolCallData,
} from './AgentOutputCard.types';
import { AutoScroll } from './AutoScroll';
import { getToolStatusIcon, getSectionIcon, getExpandToggle, ICONS } from './AgentIcons';

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
 * Create the complete output card HTML
 */
export function createOutputCard(card: AgentOutputCard): HTMLElement {
  const cardDiv = document.createElement('div');
  cardDiv.className = `agent-output-card theme-${card.display.theme} font-${card.display.fontSize}`;

  // Build card structure
  cardDiv.innerHTML = `
    ${createCardHeader(card.header)}
    ${createCardContent(card.content, card.display)}
    ${createCardFooter(card.footer)}
  `;

  // Add event listeners
  attachEventListeners(cardDiv, card.display);

  // Add CSS for apply_edits specific styling
  addApplyEditsCSS(cardDiv);

  return cardDiv;
}

/**
 * Create card header section
 */
function createCardHeader(header: CardHeader): string {
  const providerColor = getProviderColor(header.provider);
  const statusIcon = getStatusIcon(header.status);
  const providerName = getProviderDisplayName(header.provider);

  return `
    <div class="output-card-header">
      <div class="header-left">
        <span class="provider-badge" style="background-color: ${providerColor}">
          ${providerName}
        </span>
        <span class="model-name">${escapeHtml(header.model)}</span>
      </div>
      <div class="header-right">
        <span class="status-icon">${statusIcon}</span>
        <span class="duration-badge">${formatDuration(header.durationMs)}</span>
        <span class="iterations-badge">${header.iterations} iter</span>
      </div>
    </div>
    ${createStatusBanner(header.status)}
  `;
}

function createStatusBanner(status: AgentStatus): string {
  if (status === 'success') {
    return '';
  }
  
  const statusMessages: Record<string, string> = {
    'error': 'This run encountered an error',
    'warning': 'This run completed with warnings',
    'pending': 'This run is still in progress',
    'running': 'Agent is currently running',
  };
  
  const message = statusMessages[status] || '';
  if (!message) return '';
  
  return `
    <div class="status-banner status-${status}">
      ${message}
    </div>
  `;
}

/**
 * Create card content section
 */
function createCardContent(content: CardContent, display: DisplayConfig): string {
  const parts: string[] = [];

  // Thinking/reasoning stream section (DeepSeek thinking/reasoning_content)
  if (content.thinkingStream && display.showReasoning) {
    parts.push(createThinkingRawSection(content.thinkingStream, display));
  }

  // Main response text
  if (content.text) {
    parts.push(createResponseTextSection(content.text, display));
  }

  // Tool calls
  if (content.toolCalls && content.toolCalls.length > 0 && display.showToolDetails) {
    parts.push(createToolCallsSection(content.toolCalls, display));
  }

  // Code blocks
  if (content.codeBlocks && content.codeBlocks.length > 0 && display.showToolDetails) {
    parts.push(createCodeBlocksSection(content.codeBlocks, display));
  }

  // Build output
  if (content.buildOutput && display.showToolDetails) {
    parts.push(createBuildOutputSection(content.buildOutput));
  }

  return `
    <div class="output-card-content">
      ${parts.join('\n')}
    </div>
  `;
}

/**
 * Create thinking/reasoning stream section
 */
function createThinkingSection(thinkingText: string, display: DisplayConfig): string {
  if (!thinkingText || !thinkingText.trim()) return '';
  
  const isLong = thinkingText.length > 500;
  const openAttr = display.showReasoning && !isLong ? 'open' : '';

  return `
    <details class="thinking-stream-section" ${openAttr}>
      <summary class="thinking-stream-summary">
        ${getSectionIcon('thinking')}
        <span class="thinking-label">Thinking</span>
        <span class="thinking-chars">(${thinkingText.length} chars)</span>
      </summary>
      <div class="thinking-stream-content">
        <pre>${escapeHtml(thinkingText)}</pre>
      </div>
    </details>
  `;
}

/**
 * Create response text section with collapsible content
 */
function createResponseTextSection(text: string, display: DisplayConfig): string {
  if (!text || !text.trim()) return '';

  const previewText = getPreviewText(text, 10);
  const isLong = text.length > 1000;
  const collapsedClass = shouldAutoCollapse(text, display) ? 'collapsed' : '';

  return `
    <div class="response-text-section ${collapsedClass}">
      <div class="response-text-full">
        <pre>${escapeHtml(text)}</pre>
      </div>
      ${isLong ? createExpandButton(true) : ''}
    </div>
  `;
}

/**
 * Create thinking/reasoning stream section (DeepSeek R1 reasoning_content)
 * Rendered as a collapsible <details> block with distinct styling.
 */
function createThinkingStreamSection(thinkingText: string, display: DisplayConfig): string {
  if (!thinkingText || !thinkingText.trim()) return '';
  const escaped = escapeHtml(thinkingText);
  // Collapse by default if content is long
  const isLong = thinkingText.length > 500;
  const openAttr = display.showReasoning && !isLong ? 'open' : '';
  
  return `
    <details class="thinking-stream-section" ${openAttr}>
      <summary class="thinking-stream-summary">
        ${getSectionIcon('thinking')}
        <span class="thinking-label">Thinking</span>
        <span class="thinking-chars">(${thinkingText.length} chars)</span>
      </summary>
      <div class="thinking-stream-content">
        <pre>${escaped}</pre>
      </div>
    </details>
  `;
}

/**
 * Create thinking section with markdown (for main thinking stream)
 */
function createThinkingMarkdownSection(thinkingText: string, display: DisplayConfig): string {
  if (!thinkingText || !thinkingText.trim()) return '';
  
  const isLong = thinkingText.length > 500;
  const openAttr = display.showReasoning && !isLong ? 'open' : '';

  return `
    <details class="thinking-stream-section thinking-markdown" ${openAttr}>
      <summary class="thinking-stream-summary">
        ${getSectionIcon('thinking')}
        <span class="thinking-label">Thinking</span>
        <span class="thinking-chars">(${thinkingText.length} chars)</span>
      </summary>
      <div class="thinking-stream-content">
        <pre>${escapeHtml(thinkingText)}</pre>
      </div>
    </details>
  `;
}

/**
 * Create thinking section with raw pre (for reasoning_content)
 */
function createThinkingRawSection(thinkingText: string, display: DisplayConfig): string {
  if (!thinkingText || !thinkingText.trim()) return '';
  const escaped = escapeHtml(thinkingText);
  const isLong = thinkingText.length > 500;
  const openAttr = display.showReasoning && !isLong ? 'open' : '';
  
  return `
    <details class="thinking-stream-section" ${openAttr}>
      <summary class="thinking-stream-summary">
        ${getSectionIcon('thinking')}
        <span class="thinking-label">Thinking</span>
        <span class="thinking-chars">(${thinkingText.length} chars)</span>
      </summary>
      <div class="thinking-stream-content">
        <pre>${escaped}</pre>
      </div>
    </details>
  `;
}

/**
 * Create expand/collapse button
 */
function createExpandButton(isCollapsed: boolean): string {
  const toggleHtml = getExpandToggle(isCollapsed);
  return `
    <button class="expand-button" onclick="this.closest('.response-text-section').classList.toggle('collapsed'); const btn = this; const collapsed = this.closest('.response-text-section').classList.contains('collapsed'); btn.innerHTML = collapsed ? '${getExpandToggle(true).replace(/'/g, "\\'")}' : '${getExpandToggle(false).replace(/'/g, "\\'")}';">
      ${toggleHtml}
    </button>
  `;
}

/**
 * Create tool calls section
 */
function createToolCallsSection(toolCalls: ToolCallData[], display: DisplayConfig): string {
  if (!toolCalls || toolCalls.length === 0) return '';
  
  const toolCards = toolCalls.map((tc, index) => createToolCallCard(tc, index, display)).join('');
  
  return `
    <div class="tool-calls-section">
      <div class="section-header">
        ${getSectionIcon('toolCalls')}
        <span class="section-title">Tool Calls (${toolCalls.length})</span>
      </div>
      <div class="tool-calls-list">
        ${toolCards}
      </div>
    </div>
  `;
}

function createToolCallCard(toolCall: ToolCallData, index: number, display: DisplayConfig): string {
  const statusIconHtml = getToolStatusIcon(toolCall.error, toolCall.success);
  const durationStr = toolCall.durationMs ? ` (${toolCall.durationMs}ms)` : '';
  const detailsOpen = display.showToolDetails ? 'open' : '';
  
  return `
    <details class="tool-call-card" ${detailsOpen}>
      <summary class="tool-call-summary">
        ${statusIconHtml}
        <span class="tool-name">${escapeHtml(toolCall.toolName)}</span>
        <span class="tool-duration">${durationStr}</span>
      </summary>
      <div class="tool-call-details">
        <div class="tool-args">
          <strong>Arguments:</strong>
          <pre><code>${escapeHtml(JSON.stringify(toolCall.args, null, 2))}</code></pre>
        </div>
        ${toolCall.result ? `
        <div class="tool-result">
          <strong>Result:</strong>
          <pre><code>${escapeHtml(toolCall.result)}</code></pre>
        </div>
        ` : ''}
        ${toolCall.error ? `
        <div class="tool-error">
          <strong>Error:</strong>
          <pre><code>${escapeHtml(toolCall.error)}</code></pre>
        </div>
        ` : ''}
      </div>
    </details>
  `;
}

/**
 * Create build output section
 */
function createBuildOutputSection(buildOutput: string): string {
  if (!buildOutput) return '';
  
  return `
    <details class="build-output-section">
      <summary class="build-output-summary">
        ${getSectionIcon('buildOutput')}
        <span class="section-title">Build Output</span>
      </summary>
      <div class="build-output-content">
        <pre><code>${escapeHtml(buildOutput)}</code></pre>
      </div>
    </details>
  `;
}

/**
 * Create code blocks section
 */
function createCodeBlocksSection(codeBlocks: { language: string; code: string; filePath?: string; startLine?: number }[], display: DisplayConfig): string {
  if (!codeBlocks || codeBlocks.length === 0) return '';
  
  const blocks = codeBlocks.map((block) => {
    const fileInfo = block.filePath ? ` <span class="code-file-path">${escapeHtml(block.filePath)}${block.startLine ? `:${block.startLine}` : ''}</span>` : '';
    return `
      <div class="code-block">
        <div class="code-block-header">
          <span class="code-language">${escapeHtml(block.language || 'text')}</span>
          ${fileInfo}
        </div>
        <pre><code class="language-${escapeHtml(block.language || 'text')}">${escapeHtml(block.code)}</code></pre>
      </div>
    `;
  }).join('');
  
  return `
    <details class="code-blocks-section">
      <summary class="code-blocks-summary">
        ${getSectionIcon('codeBlocks')}
        <span class="section-title">Code Blocks (${codeBlocks.length})</span>
      </summary>
      <div class="code-blocks-list">
        ${blocks}
      </div>
    </details>
  `;
}

/**
 * Create card footer section
 */
function createCardFooter(footer: CardFooter): string {
  const actions = footer.actions.map(action => `
    <button class="footer-action ${action.enabled ? '' : 'disabled'}" 
            ${!action.enabled ? 'disabled' : ''}
            data-action-id="${escapeHtml(action.id)}">
      <span class="action-icon">${action.icon}</span>
      <span class="action-label">${escapeHtml(action.label)}</span>
    </button>
  `).join('');
  
  const tokenInfo = footer.tokensUsed ? `
    <span class="tokens-info">
      <span class="token-icon">${ICONS.tokens}</span>
      ${footer.tokensUsed.toLocaleString()} tokens
    </span>
  ` : '';
  
  const confidenceInfo = footer.confidence !== undefined ? `
    <span class="confidence-info">
      <span class="confidence-icon">${ICONS.confidence}</span>
      ${(footer.confidence * 100).toFixed(0)}% confidence
    </span>
  ` : '';
  
  return `
    <div class="output-card-footer">
      <div class="footer-left">
        ${tokenInfo}
        ${confidenceInfo}
      </div>
      <div class="footer-right">
        ${actions}
      </div>
    </div>
  `;
}

/**
 * Attach event listeners for interactive elements
 */
function attachEventListeners(cardDiv: HTMLElement, display: DisplayConfig): void {
  // Footer action buttons
  cardDiv.querySelectorAll('.footer-action').forEach(button => {
    button.addEventListener('click', (e) => {
      const actionId = (button as HTMLElement).dataset.actionId;
      if (actionId) {
        // Dispatch custom event for action handling
        const event = new CustomEvent('output-card-action', {
          bubbles: true,
          detail: { actionId }
        });
        cardDiv.dispatchEvent(event);
      }
    });
  });
  
  // Apply edits buttons
  cardDiv.querySelectorAll('.apply-edits-button').forEach(button => {
    button.addEventListener('click', (e) => {
      e.stopPropagation();
      const actionId = (button as HTMLElement).dataset.actionId;
      if (actionId) {
        const event = new CustomEvent('output-card-action', {
          bubbles: true,
          detail: { actionId }
        });
        cardDiv.dispatchEvent(event);
      }
    });
  });
}

/**
 * Add CSS for apply_edits specific styling if apply_edits buttons are present
 */
function addApplyEditsCSS(cardDiv: HTMLElement): void {
  const hasApplyEdits = cardDiv.querySelector('.apply-edits-button');
  if (!hasApplyEdits) return;
  
  const style = document.createElement('style');
  style.textContent = `
    .apply-edits-button {
      background: #27ae60;
      color: white;
      border: none;
      padding: 4px 12px;
      border-radius: 4px;
      cursor: pointer;
      font-size: 12px;
      margin-left: 8px;
    }
    .apply-edits-button:hover {
      background: #219a52;
    }
  `;
  cardDiv.appendChild(style);
}
