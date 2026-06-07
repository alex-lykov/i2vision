/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

/**
 * AgentOutputCard Component
 * 
 * Unified agent output display with configurable formatting, collapsing, and styling.
 * Used in webview to display agent responses consistently across all agent types.
 */

import {
  AgentOutputCard,
  CardHeader,
  CardContent,
  CardFooter,
  DisplayConfig,
  ToolCallData,
  ProviderType,
  AgentStatus,
  getProviderDisplayName,
  getProviderColor,
  getStatusIcon,
  formatDuration,
  shouldAutoCollapse,
  getPreviewText,
  DEFAULT_DISPLAY_CONFIG,
} from './AgentOutputCard.types';

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
  `;
}

/**
 * Create card content section with collapsing support
 */
function createCardContent(content: CardContent, display: DisplayConfig): string {
  const shouldCollapse = shouldAutoCollapse(content.text, display);
  const previewText = shouldCollapse ? getPreviewText(content.text, display.maxPreviewLines) : content.text;

  return `
    <div class="output-card-content">
      ${content.error ? createErrorSection(content.error) : ''}
      
      <div class="response-text-section ${shouldCollapse && display.collapsed ? 'collapsed' : ''}">
        <div class="response-text">
          ${formatResponseText(previewText, display)}
        </div>
        ${shouldCollapse ? createExpandButton(shouldCollapse && display.collapsed) : ''}
      </div>

      ${content.toolCalls.length > 0 ? createToolCallsSection(content.toolCalls, display) : ''}
      
      ${content.buildOutput ? createBuildOutputSection(content.buildOutput) : ''}
      
      ${content.codeBlocks && content.codeBlocks.length > 0 ? createCodeBlocksSection(content.codeBlocks, display) : ''}
    </div>
  `;
}

/**
 * Create error section
 */
function createErrorSection(error: string): string {
  return `
    <div class="error-section">
      <span class="error-icon">❌</span>
      <span class="error-text">${escapeHtml(error)}</span>
    </div>
  `;
}

/**
 * Format response text with markdown-like elements
 */
function formatResponseText(text: string, display: DisplayConfig): string {
  if (!text) return '<em class="text-muted">No response generated.</em>';

  let formatted = escapeHtml(text);

  // Convert line breaks
  formatted = formatted.replace(/\n/g, '<br>');

  // Highlight code blocks (simple detection)
  formatted = formatted.replace(/```(\w+)?\n([\s\S]*?)```/g, (match, lang, code) => {
    return `<pre class="code-block"><code class="language-${lang || 'plaintext'}">${escapeHtml(code.trim())}</code></pre>`;
  });

  // Highlight inline code
  formatted = formatted.replace(/`([^`]+)`/g, '<code class="inline-code">$1</code>');

  // Bold text
  formatted = formatted.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>');

  // Italic text
  formatted = formatted.replace(/\*([^*]+)\*/g, '<em>$1</em>');

  return formatted;
}

/**
 * Create expand/collapse button
 */
function createExpandButton(isCollapsed: boolean): string {
  return `
    <div class="expand-button" onclick="toggleOutputCard(this)">
      <span class="expand-icon">${isCollapsed ? '▼' : '▲'}</span>
      <span class="expand-text">${isCollapsed ? 'Show more' : 'Show less'}</span>
    </div>
  `;
}

/**
 * Create tool calls section
 */
function createToolCallsSection(toolCalls: ToolCallData[], display: DisplayConfig): string {
  if (!display.showToolDetails) return '';

  const toolCards = toolCalls.map(tc => createToolCallCard(tc)).join('');

  return `
    <div class="tool-calls-section">
      <div class="section-title">
        <span class="section-icon">🛠️</span>
        <span class="section-label">Tool Calls (${toolCalls.length})</span>
      </div>
      <div class="tool-calls-list">
        ${toolCards}
      </div>
    </div>
  `;
}

/**
 * Create individual tool call card
 */
function createToolCallCard(toolCall: ToolCallData): string {
  const successClass = toolCall.success !== false ? 'success' : 'error';
  const icon = toolCall.success !== false ? '✅' : '❌';

  return `
    <div class="tool-call-card ${successClass}">
      <div class="tool-call-header" onclick="toggleToolCallCard(this)">
        <span class="tool-call-toggle">▼</span>
        <span class="tool-call-icon">${icon}</span>
        <span class="tool-call-name">${escapeHtml(toolCall.toolName)}</span>
        <span class="tool-call-meta">
          ${toolCall.durationMs ? `<span class="tool-call-duration">${formatDuration(toolCall.durationMs)}</span>` : ''}
        </span>
      </div>
      <div class="tool-call-body">
        ${Object.keys(toolCall.args).length > 0 ? `
          <div class="tool-call-args">
            <span class="args-label">Args:</span>
            <code>${escapeHtml(JSON.stringify(toolCall.args, null, 2))}</code>
          </div>
        ` : ''}
        ${toolCall.result ? `
          <div class="tool-call-result">
            <span class="result-label">Result:</span>
            <pre>${escapeHtml(toolCall.result)}</pre>
          </div>
        ` : ''}
        ${toolCall.error ? `
          <div class="tool-call-error">
            <span class="error-label">Error:</span>
            <span>${escapeHtml(toolCall.error)}</span>
          </div>
        ` : ''}
      </div>
    </div>
  `;
}

/**
 * Create build output section
 */
function createBuildOutputSection(buildOutput: string): string {
  const isSuccess = !buildOutput.includes('FAILED') && !buildOutput.includes('ERROR');
  const statusClass = isSuccess ? 'success' : 'error';
  const statusIcon = isSuccess ? '✅' : '❌';

  return `
    <div class="build-output-section ${statusClass}">
      <div class="section-title">
        <span class="section-icon">${statusIcon}</span>
        <span class="section-label">${isSuccess ? 'Build Successful' : 'Build Failed'}</span>
      </div>
      <div class="build-output-content">
        <pre class="build-log">${escapeHtml(buildOutput)}</pre>
      </div>
    </div>
  `;
}

/**
 * Create code blocks section
 */
function createCodeBlocksSection(codeBlocks: any[], display: DisplayConfig): string {
  if (!display.codeHighlight) {
    return '';
  }

  const blocks = codeBlocks.map(block => `
    <div class="code-block-container">
      ${block.filePath ? `<div class="code-file-path">${escapeHtml(block.filePath)}</div>` : ''}
      <pre class="code-block"><code class="language-${block.language}">${escapeHtml(block.code)}</code></pre>
    </div>
  `).join('');

  return `
    <div class="code-blocks-section">
      <div class="section-title">
        <span class="section-icon">📄</span>
        <span class="section-label">Code (${codeBlocks.length})</span>
      </div>
      ${blocks}
    </div>
  `;
}

/**
 * Create card footer with actions
 */
function createCardFooter(footer: CardFooter): string {
  const actionButtons = footer.actions
    .filter(action => action.enabled)
    .map(action => `
      <button class="footer-action-btn" onclick="handleFooterAction('${action.id}')">
        <span class="action-icon">${action.icon}</span>
        <span class="action-label">${action.label}</span>
      </button>
    `).join('');

  const metaInfo = [];

  if (footer.tokensUsed !== undefined && footer.tokensUsed > 0) {
    metaInfo.push(`<span class="meta-item">📊 ${footer.tokensUsed} tokens</span>`);
  }

  if (footer.confidence !== undefined && footer.confidence > 0) {
    metaInfo.push(`<span class="meta-item">🎯 ${(footer.confidence * 100).toFixed(0)}% confidence</span>`);
  }

  return `
    <div class="output-card-footer">
      ${metaInfo.length > 0 ? `<div class="footer-meta">${metaInfo.join('')}</div>` : ''}
      ${actionButtons ? `<div class="footer-actions">${actionButtons}</div>` : ''}
    </div>
  `;
}

/**
 * Toggle output card expand/collapse
 */
function toggleOutputCard(button: HTMLElement): void {
  const section = button.parentElement as HTMLElement;
  const icon = button.querySelector('.expand-icon') as HTMLElement;
  const text = button.querySelector('.expand-text') as HTMLElement;

  if (section.classList.contains('collapsed')) {
    section.classList.remove('collapsed');
    icon.textContent = '▲';
    text.textContent = 'Show less';
  } else {
    section.classList.add('collapsed');
    icon.textContent = '▼';
    text.textContent = 'Show more';
  }
}

/**
 * Toggle tool call card expand/collapse
 */
function toggleToolCallCard(header: HTMLElement): void {
  const body = header.nextElementSibling as HTMLElement;
  const toggle = header.querySelector('.tool-call-toggle') as HTMLElement;

  if (body.style.display === 'none') {
    body.style.display = 'block';
    toggle.textContent = '▼';
  } else {
    body.style.display = 'none';
    toggle.textContent = '▶';
  }
}

/**
 * Attach event listeners to card elements
 */
function attachEventListeners(cardDiv: HTMLElement, display: DisplayConfig): void {
  // Expand/collapse functionality for response text
  const expandButton = cardDiv.querySelector('.expand-button');
  if (expandButton) {
    expandButton.addEventListener('click', (e) => {
      e.stopPropagation();
      toggleOutputCard(expandButton as HTMLElement);
    });
  }

  // Tool call card toggles
  const toolCallHeaders = cardDiv.querySelectorAll('.tool-call-header');
  toolCallHeaders.forEach(header => {
    header.addEventListener('click', (e) => {
      e.stopPropagation();
      toggleToolCallCard(header as HTMLElement);
    });
  });
}

// Export toggle functions to global scope for onclick handlers
(window as any).toggleOutputCard = toggleOutputCard;
(window as any).toggleToolCallCard = toggleToolCallCard;

/**
 * Handle footer action button clicks (global function for onclick)
 */
(window as any).handleFooterAction = function(actionId: string) {
  // This will be overridden by the webview to communicate with VSCode
  console.log(`Footer action clicked: ${actionId}`);
};

/**
 * Convert legacy response format to new output card format
 */
export function convertLegacyResponse(
  text: string,
  toolCalls: any[],
  iterations: number,
  durationMs: number,
  success: boolean,
  provider: ProviderType,
  model: string,
  displayConfig: Partial<DisplayConfig> = {}
): AgentOutputCard {
  const config = { ...DEFAULT_DISPLAY_CONFIG, ...displayConfig };

  return {
    header: {
      provider,
      providerName: getProviderDisplayName(provider),
      model,
      timestamp: Date.now(),
      durationMs,
      iterations,
      status: success ? 'success' : 'error',
    },
    content: {
      text,
      toolCalls: toolCalls.map(tc => ({
        toolName: tc.toolName,
        args: tc.args || {},
        result: tc.result,
        durationMs: tc.durationMs,
        success: !tc.error,
        error: tc.error,
      })),
      error: success ? undefined : 'Request failed',
    },
    footer: {
      tokensUsed: undefined, // Not available in legacy format
      confidence: undefined,
      actions: [
        { id: 'copy', label: 'Copy', icon: '📋', enabled: true },
        { id: 'retry', label: 'Retry', icon: '🔄', enabled: true },
      ],
    },
    display: config,
  };
}
