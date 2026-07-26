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
    'stopped': '🛑'
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
    const toolMatch = error.match(/calling\s*(\w+)/i);
    if (toolMatch) {
      details = `Tool in loop: <strong>${escapeHtml(toolMatch[1])}</strong>`;
    }
  }
  
  return details ? `<div class="error-details">${details}</div>` : '';
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
 * Tool category icon mappings (must match CATEGORY_ICONS in ToolTypes.ts)
 */
const TOOL_CATEGORY_ICONS: Record<string, { icon: string; color: string; displayName: string }> = {
  file: { icon: 'file', color: '#007acc', displayName: 'File Operations' },
  git: { icon: 'git-commit', color: '#6f42c1', displayName: 'Git Operations' },
  build: { icon: 'gear', color: '#d46b08', displayName: 'Build & Compile' },
  terminal: { icon: 'terminal', color: '#2ea043', displayName: 'Terminal' },
  edit: { icon: 'edit', color: '#d29922', displayName: 'Edit Operations' }
};

/**
 * Get category info for a tool
 */
function getToolCategory(toolName: string): { icon: string; color: string; displayName: string } | undefined {
  // Map tool names to categories
  const toolCategoryMap: Record<string, string> = {
    'list_directory': 'file',
    'read_file': 'file',
    'write_file': 'file',
    'search_files': 'file',
    'get_file_context': 'file',
    'revert_file': 'file',
    'revert_all': 'file',
    'list_snapshots': 'file',
    'git_status': 'git',
    'git_diff': 'git',
    'git_log': 'git',
    'git_branch': 'git',
    'git_commit': 'git',
    'run_build': 'build',
    'run_terminal': 'terminal',
    'kill_terminal': 'terminal',
    'list_terminals': 'terminal',
    'terminal_status': 'terminal',
    'kill_port': 'terminal',
    'apply_edits': 'edit'
  };

  const category = toolCategoryMap[toolName];
  return category ? TOOL_CATEGORY_ICONS[category] : undefined;
}

/**
 * Tool descriptions for tooltips
 */
const TOOL_DESCRIPTIONS: Record<string, string> = {
  'list_directory': 'List files in a directory',
  'read_file': 'Read contents of a file',
  'write_file': 'Write content to a file',
  'apply_edits': 'Apply targeted edits to an existing file (max 50 edits)',
  'search_files': 'Search for files matching a regex pattern',
  'get_file_context': 'Get context for a specific file (classes, functions, imports)',
  'revert_file': 'Revert a file to its original state',
  'revert_all': 'Revert ALL modified files to their original state',
  'list_snapshots': 'List all files that have been modified',
  'git_status': 'Show working tree status',
  'git_diff': 'Show changes between commits',
  'git_log': 'Show recent commit history',
  'git_branch': 'Show current or all branches',
  'git_commit': 'Stage files and create a commit',
  'run_build': 'Run a build command',
  'run_terminal': 'Run a terminal command',
  'kill_terminal': 'Stop a running terminal by name',
  'list_terminals': 'List all managed terminals',
  'terminal_status': 'Check if a terminal is running',
  'kill_port': 'Find and kill the process using a specific port'
};

/**
 * Create individual tool call card with enhanced UI
 */
function createToolCallCard(toolCall: ToolCallData): string {
  const successClass = toolCall.success !== false ? 'success' : 'error';
  const icon = toolCall.success !== false ? '✅' : '❌';

  // Get category info
  const categoryInfo = getToolCategory(toolCall.toolName);
  const description = TOOL_DESCRIPTIONS[toolCall.toolName];

  // Build category badge HTML
  const categoryBadge = categoryInfo ? `
    <span class="tool-category-badge" style="background-color: ${categoryInfo.color}20; border-color: ${categoryInfo.color};" 
          title="${categoryInfo.displayName}">
      <span class="codicon codicon-${categoryInfo.icon}" style="color: ${categoryInfo.color};"></span>
    </span>
  ` : '';

  // Build help tooltip HTML
  const helpTooltip = description ? `
    <span class="tool-help-icon" title="${escapeHtml(description)}">
      <span class="codicon codicon-question"></span>
    </span>
  ` : '';

  return `
    <div class="tool-call-card ${successClass}" data-tool-call-id="${toolCall.toolCallId || ''}">
      <div class="tool-call-header" onclick="toggleToolCallCard(this)">
        <span class="tool-call-status">${icon}</span>
        ${categoryBadge}
        <span class="tool-call-name">${escapeHtml(toolCall.toolName)}</span>
        ${helpTooltip}
        <span class="tool-call-meta">
          ${toolCall.durationMs ? `<span class="tool-call-duration">${formatDuration(toolCall.durationMs)}</span>` : ''}
          ${toolCall.toolCallId ? `<span class="tool-call-id" title="Tool Call ID">${escapeHtml(toolCall.toolCallId)}</span>` : ''}
        </span>
      </div>
      <div class="tool-call-body">
        ${Object.keys(toolCall.args).length > 0 ? `
          <div class="tool-call-args">
            <span class="args-label">Args:</span>
            ${toolCall.toolName === 'apply_edits' ? `
              <div class="apply-edits-args-summary">
                <span class="arg-path">📄 ${escapeHtml(toolCall.args.path)}</span>
                <span class="arg-edits-count">✏️ ${Array.isArray(toolCall.args.edits) ? toolCall.args.edits.length : 0} edits</span>
              </div>
              <details class="apply-edits-args-details">
                <summary>Show full args</summary>
                <pre>${escapeHtml(JSON.stringify(toolCall.args, null, 2))}</pre>
              </details>
            ` : `<code>${escapeHtml(JSON.stringify(toolCall.args, null, 2))}</code>`}
          </div>
        ` : ''}
        ${toolCall.result ? `
          <div class="tool-call-result">
            <span class="result-label">Result:</span>
            ${(toolCall as any).format === 'markdown' ? `
              <div class="markdown-result">${formatResponseText(toolCall.result, DEFAULT_DISPLAY_CONFIG)}</div>
            ` : `<pre>${escapeHtml(toolCall.result)}</pre>`}
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
  const card = header.parentElement as HTMLElement;
  
  if (body.style.display === 'none') {
    body.style.display = 'block';
    card.classList.remove('collapsed');
  } else {
    body.style.display = 'none';
    card.classList.add('collapsed');
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
 * Add CSS for apply_edits specific styling
 */
function addApplyEditsCSS(cardDiv: HTMLElement): void {
  const style = document.createElement('style');
  style.textContent = `
    /* Status banner styles */
    .status-banner {
      padding: 0.5rem 1rem;
      display: flex;
      align-items: center;
      gap: 0.5rem;
      font-weight: 500;
      border-radius: 0 0 4px 4px;
      margin-bottom: 1rem;
    }
    
    .status-banner.error {
      background-color: #ffdddd;
      color: #d32f2f;
      border: 1px solid #ef9a9a;
    }
    
    .status-banner.partial {
      background-color: #fff8e1;
      color: #f57f17;
      border: 1px solid #ffcc80;
    }
    
    .status-banner.stopped {
      background-color: #e8eaf6;
      color: #3f51b5;
      border: 1px solid #c5cae9;
    }
    
    .banner-icon {
      font-size: 1.1rem;
    }
    
    .banner-message {
      font-size: 0.9rem;
    }
    
    /* Enhanced error section styles */
    .error-section {
      background-color: #ffebee;
      border-left: 4px solid #d32f2f;
      padding: 1rem;
      margin: 1rem 0;
      border-radius: 4px;
      display: flex;
      align-items: flex-start;
      gap: 0.75rem;
    }
    
    .error-icon {
      font-size: 1.5rem;
      color: #d32f2f;
      margin-top: 2px;
    }
    
    .error-content {
      flex: 1;
    }
    
    .error-type {
      font-weight: 600;
      color: #c62828;
      margin-bottom: 0.25rem;
      font-size: 0.9rem;
      text-transform: uppercase;
      letter-spacing: 0.5px;
    }
    
    .error-text {
      color: #d32f2f;
      line-height: 1.4;
    }
    
    .error-details {
      margin-top: 0.5rem;
      padding: 0.5rem;
      background-color: #ffcdd2;
      border-radius: 4px;
      font-size: 0.85rem;
      color: #b71c1c;
    }
    
    /* Apply edits specific styling */
    .apply-edits-args-summary {
      display: flex;
      gap: 0.5rem;
      align-items: center;
      margin-bottom: 0.25rem;
      flex-wrap: wrap;
    }

    .arg-path {
      font-family: var(--vscode-editor-font-family);
      color: var(--vscode-editor-foreground);
      background: var(--vscode-editor-background);
      padding: 0.1rem 0.4rem;
      border-radius: 0.2rem;
      border: 1px solid var(--vscode-editor-bracketMatch-border);
      font-size: 0.9em;
    }

    .arg-edits-count {
      color: var(--vscode-editorInfo-foreground);
      font-size: 0.9em;
      display: flex;
      align-items: center;
      gap: 0.3rem;
    }

    .apply-edits-args-details {
      margin-top: 0.5rem;
    }

    .apply-edits-args-details summary {
      cursor: pointer;
      color: var(--vscode-textLink-foreground);
      font-size: 0.9em;
      padding: 0.2rem 0;
      display: inline-block;
    }

    .apply-edits-args-details summary:hover {
      text-decoration: underline;
    }

    .markdown-result {
      line-height: 1.4;
      padding: 0.5rem;
      background: var(--vscode-editor-background);
      border-radius: 0.3rem;
      margin-top: 0.5rem;
    }

    .markdown-result pre {
      background: var(--vscode-editorWidget-background);
      padding: 0.5rem;
      border-radius: 0.2rem;
      overflow-x: auto;
      margin: 0.5rem 0;
    }

    .markdown-result code {
      font-family: var(--vscode-editor-font-family);
      background: var(--vscode-editorWidget-background);
      padding: 0.1rem 0.3rem;
      border-radius: 0.2rem;
    }

    .markdown-result strong {
      font-weight: 600;
      color: var(--vscode-editor-foreground);
    }

    .markdown-result em {
      font-style: italic;
      color: var(--vscode-editor-foreground);
    }
  `;

  // Check if style already exists to avoid duplicates
  if (!document.getElementById('apply-edits-css')) {
    style.id = 'apply-edits-css';
    document.head.appendChild(style);
  }
}

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
