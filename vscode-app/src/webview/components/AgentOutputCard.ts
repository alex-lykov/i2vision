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
  
  const bannerClasses = `status-banner ${status}`;
  const bannerIcons = {
    'error': '❌',
    'partial': '⚠️',
    'stopped': ''
  };
  const bannerMessages = {
    'error': 'Agent encountered an error',
    'partial': 'Agent completed partially',
    'stopped': 'Agent was stopped'
  };
  
  return `
    <div class="${bannerClasses}">
      <span class="banner-icon">${bannerIcons[status] || 'ℹ️'}</span>
      <span class="banner-message">${bannerMessages[status] || 'Agent status updated'}</span>
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
      
      ${content.thinkingStream ? createThinkingStreamSection(content.thinkingStream, display) : ''}
      
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
        <span class="thinking-icon"></span>
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
 * Create error section
 */
function createErrorSection(error: string): string {
  // Parse error to extract meaningful information
  const errorMessage = extractErrorMessage(error);
  const errorType = extractErrorType(error);
  
  return `
    <div class="error-section">
      <span class="error-icon">❌</span>
      <div class="error-content">
        ${errorType ? `<div class="error-type">${escapeHtml(errorType)}</div>` : ''}
        <div class="error-text">${escapeHtml(errorMessage)}</div>
        ${shouldShowErrorDetails(error) ? createErrorDetailsSection(error) : ''}
      </div>
    </div>
  `;
}

function extractErrorMessage(error: string): string {
  // Extract the main error message from common error patterns
  const patterns = [
    /^(ERROR|FAILED|TOOL_EXECUTION_FAILED|MAX_ITERATIONS_REACHED|BUILD_FIX_CYCLE_FAILED|TOOL_LOOP_DETECTED):\s*(.+)$/i,
    /^(Error:\s*)(.+)$/i,
    /^([^:]+:\s*)(.+)$/
  ];
  
  for (const pattern of patterns) {
    const match = error.match(pattern);
    if (match && match[2]) {
      return match[2].trim();
    }
  }
  
  return error;
}

function extractErrorType(error: string): string | null {
  // Extract error type from common patterns
  const typePatterns = [
    /^(ERROR|FAILED|TOOL_EXECUTION_FAILED|MAX_ITERATIONS_REACHED|BUILD_FIX_CYCLE_FAILED|TOOL_LOOP_DETECTED)/i,
    /^(Error|Exception|Failure)/i
  ];
  
  for (const pattern of typePatterns) {
    const match = error.match(pattern);
    if (match && match[1]) {
      return match[1].toUpperCase();
    }
  }
  
  return null;
}

function shouldShowErrorDetails(error: string): boolean {
  // Show details for specific error types that benefit from more context
  return error.includes('TOOL_EXECUTION_FAILED') || 
         error.includes('BUILD_FIX_CYCLE_FAILED') || 
         error.includes('MAX_ITERATIONS_REACHED') ||
         error.includes('TOOL_LOOP_DETECTED');
}

function createErrorDetailsSection(error: string): string {
  // Extract additional details from error
  let details = '';
  
  if (error.includes('TOOL_EXECUTION_FAILED')) {
    const toolMatch = error.match(/TOOL_EXECUTION_FAILED:\s*(\w+)/i);
    if (toolMatch) {
      details = `Tool: <strong>${escapeHtml(toolMatch[1])}</strong>`;
    }
  } else if (error.includes('MAX_ITERATIONS_REACHED')) {
    const iterationMatch = error.match(/(\d+)\s*iterations/i);
    if (iterationMatch) {
      details = `Iterations: <strong>${escapeHtml(iterationMatch[1])}</strong>`;
    }
  } else if (error.includes('BUILD_FIX_CYCLE_FAILED')) {
    const failureMatch = error.match(/(\d+)\s*consecutive\s*build\s*failures/i);
    if (failureMatch) {
      details = `Build failures: <strong>${escapeHtml(failureMatch[1])}</strong>`;
    }
  } else if (error.includes('TOOL_LOOP_DETECTED')) {
    const toolMatch = error.match(/detected\s*tool:\s*(\w+)/i);
    if (toolMatch) {
      details = `Looping tool: <strong>${escapeHtml(toolMatch[1])}</strong>`;
    }
  }
  
  if (!details) return '';
  
  return `
    <div class="error-details">
      ${details}
    </div>
  `;
}

/**
 * Format response text with basic Markdown-like styling
 */
function formatResponseText(text: string, display: DisplayConfig): string {
  if (!text) return '';
  
  let formatted = escapeHtml(text);
  
  // Convert code blocks (```...```)
  formatted = formatted.replace(/```(\w*)\n([\s\S]*?)```/g, (_, lang, code) => {
    const langClass = lang ? ` class="language-${escapeHtml(lang)}"` : '';
    return `<pre><code${langClass}>${code}</code></pre>`;
  });
  
  // Convert inline code (`...`)
  formatted = formatted.replace(/`([^`]+)`/g, '<code>$1</code>');
  
  // Convert bold (**...**)
  formatted = formatted.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>');
  
  // Convert italic (*...*)
  formatted = formatted.replace(/\*([^*]+)\*/g, '<em>$1</em>');
  
  // Convert newlines to <br>
  formatted = formatted.replace(/\n/g, '<br>');
  
  return formatted;
}

/**
 * Create expand/collapse button
 */
function createExpandButton(isCollapsed: boolean): string {
  const label = isCollapsed ? '▼ Expand' : '▲ Collapse';
  return `
    <button class="expand-button" onclick="this.closest('.response-text-section').classList.toggle('collapsed'); this.textContent = this.closest('.response-text-section').classList.contains('collapsed') ? '▼ Expand' : '▲ Collapse';">
      ${label}
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
        <span class="section-icon"></span>
        <span class="section-title">Tool Calls (${toolCalls.length})</span>
      </div>
      <div class="tool-calls-list">
        ${toolCards}
      </div>
    </div>
  `;
}

function createToolCallCard(toolCall: ToolCallData, index: number, display: DisplayConfig): string {
  const statusIcon = toolCall.error ? '❌' : toolCall.success !== false ? '✅' : '⚠️';
  const durationStr = toolCall.durationMs ? ` (${toolCall.durationMs}ms)` : '';
  const detailsOpen = display.showToolDetails ? 'open' : '';
  
  return `
    <details class="tool-call-card" ${detailsOpen}>
      <summary class="tool-call-summary">
        <span class="tool-status">${statusIcon}</span>
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
        <span class="section-icon">️</span>
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
    <div class="code-blocks-section">
      <div class="section-header">
        <span class="section-icon"></span>
        <span class="section-title">Code Blocks (${codeBlocks.length})</span>
      </div>
      ${blocks}
    </div>
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
      <span class="token-icon"></span>
      ${footer.tokensUsed.toLocaleString()} tokens
    </span>
  ` : '';
  
  const confidenceInfo = footer.confidence !== undefined ? `
    <span class="confidence-info">
      <span class="confidence-icon"></span>
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
