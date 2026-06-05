/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

/**
 * ToolCardFormatter - Formats tool results for display in the webview
 * 
 * Applies formatting, truncation, and folding based on ToolCardConfig.
 * Sits between tool execution and webview display.
 */

import {
    ToolCardConfig,
    ToolDisplayOptions,
    FormattedToolCard,
    DEFAULT_TOOL_CARD_CONFIG,
    SETTING_KEYS
} from './ToolCardConfig';
import * as vscode from 'vscode';

export class ToolCardFormatter {
    private config: ToolCardConfig;

    constructor(config?: ToolCardConfig) {
        this.config = config || ToolCardFormatter.loadConfigFromSettings();
    }

    /**
     * Load configuration from VS Code settings
     */
    static loadConfigFromSettings(): ToolCardConfig {
        const config = vscode.workspace.getConfiguration();

        const showTools = config.get<string>(SETTING_KEYS.showTools, 'all') as 'all' | 'failed_only' | 'none';
        const defaults: ToolDisplayOptions = {
            maxLines: config.get<number>(SETTING_KEYS.defaultMaxLines, 20),
            maxChars: config.get<number>(SETTING_KEYS.defaultMaxChars, 3000),
            foldThreshold: config.get<number>(SETTING_KEYS.defaultFoldThreshold, 10),
            foldDefault: config.get<string>(SETTING_KEYS.defaultFoldDefault, 'collapsed') as 'expanded' | 'collapsed',
            format: config.get<string>(SETTING_KEYS.defaultFormat, 'raw') as 'raw' | 'markdown' | 'tree' | 'table',
            showLineNumbers: config.get<boolean>(SETTING_KEYS.defaultShowLineNumbers, true),
            syntaxHighlight: config.get<boolean>(SETTING_KEYS.defaultSyntaxHighlight, false),
            showArgs: config.get<boolean>(SETTING_KEYS.defaultShowArgs, true),
            showDuration: config.get<boolean>(SETTING_KEYS.defaultShowDuration, true),
            collapseOnSuccess: config.get<boolean>(SETTING_KEYS.defaultCollapseOnSuccess, true)
        };

        // Load per-tool overrides from settings (guard against null/undefined)
        const perToolConfig = config.get<Record<string, Partial<ToolDisplayOptions>>>(SETTING_KEYS.perToolConfig, {}) || {};
        const tools: Record<string, ToolDisplayOptions> = {};

        // Start with defaults from DEFAULT_TOOL_CARD_CONFIG
        for (const [toolName, defaultOptions] of Object.entries(DEFAULT_TOOL_CARD_CONFIG.tools)) {
            const override = perToolConfig[toolName] || {};
            tools[toolName] = { ...defaultOptions, ...override };
        }

        // Add any additional tools from user settings
        for (const [toolName, override] of Object.entries(perToolConfig)) {
            if (!tools[toolName]) {
                tools[toolName] = { ...defaults, ...override };
            }
        }

        return { showTools, defaults, tools };
    }

    /**
     * Reload configuration from VS Code settings
     */
    reloadConfig(): void {
        this.config = ToolCardFormatter.loadConfigFromSettings();
    }

    /**
     * Format a tool result for display in the webview.
     */
    format(
        toolName: string,
        args: Record<string, any>,
        result: string,
        durationMs: number,
        success: boolean
    ): FormattedToolCard | null {
        const options = this.config.tools[toolName] || this.config.defaults;

        // Check visibility
        if (this.config.showTools === 'none') {
            return null;
        }
        if (this.config.showTools === 'failed_only' && success) {
            return null;
        }

        // Determine fold state
        let foldState = options.foldDefault;
        if (success && options.collapseOnSuccess) {
            foldState = 'collapsed';
        }

        // Apply formatting
        let formatted = this.applyFormat(result, options.format);

        // Apply truncation
        formatted = this.applyTruncation(formatted, options);

        // Build the card
        return {
            toolName,
            args: options.showArgs ? args : undefined,
            result: formatted,
            durationMs: options.showDuration ? durationMs : undefined,
            success,
            foldState,
            totalLines: result.split('\n').length,
            totalChars: result.length,
            isTruncated: result.length > options.maxChars || result.split('\n').length > options.maxLines,
            format: options.format,
            showLineNumbers: options.showLineNumbers,
            syntaxHighlight: options.syntaxHighlight
        };
    }

    /**
     * Format a progress event tool call
     */
    formatProgressToolCall(
        toolName: string,
        args: Record<string, any>,
        result: string,
        durationMs: number,
        success: boolean
    ): FormattedToolCard | null {
        return this.format(toolName, args, result, durationMs, success);
    }

    /**
     * Apply format transformation to result
     */
    private applyFormat(result: string, format: string): string {
        switch (format) {
            case 'tree':
                return this.formatAsTree(result);
            case 'table':
                return this.formatAsTable(result);
            case 'markdown':
                return result; // Already markdown
            default:
                return result; // raw
        }
    }

    /**
     * Apply truncation based on options
     */
    private applyTruncation(result: string, options: ToolDisplayOptions): string {
        if (options.maxLines <= 0 && options.maxChars <= 0) {
            return result;
        }

        let lines = result.split('\n');
        let truncated = false;
        const totalLines = lines.length;
        const totalChars = result.length;

        // Apply line truncation
        if (options.maxLines > 0 && lines.length > options.maxLines) {
            lines = lines.slice(0, options.maxLines);
            truncated = true;
        }

        let output = lines.join('\n');

        // Apply character truncation
        if (options.maxChars > 0 && output.length > options.maxChars) {
            output = output.substring(0, options.maxChars);
            // Try to break at a newline to avoid cutting mid-line
            const lastNewline = output.lastIndexOf('\n');
            if (lastNewline > output.length * 0.8) {
                output = output.substring(0, lastNewline);
            }
            truncated = true;
        }

        // Add truncation notice
        if (truncated) {
            output += `\n\n---\n📄 *Truncated: ${totalLines} lines, ${totalChars} chars total* ---`;
        }

        return output;
    }

    /**
     * Format result as a tree structure
     */
    private formatAsTree(result: string): string {
        const lines = result.split('\n').filter(l => l.trim());
        if (lines.length === 0) {
            return '(empty)';
        }

        // Detect directory listings
        const isDirectoryListing = lines.some(l => l.includes('/') || l.includes('\\'));

        if (isDirectoryListing) {
            return this.formatDirectoryTree(lines);
        }

        // Generic tree formatting
        return lines.map((line, i) => {
            const isLast = i === lines.length - 1;
            const prefix = isLast ? '└── ' : '├── ';
            return prefix + line.trim();
        }).join('\n');
    }

    /**
     * Format directory listing as tree
     */
    private formatDirectoryTree(lines: string[]): string {
        const rootName = lines[0]?.split(/[\\/]/).filter(Boolean)[0] || 'root';
        let output = `${rootName}/\n`;

        const entries: Array<{ path: string; isDir: boolean }> = [];

        for (const line of lines) {
            const trimmed = line.trim();
            if (!trimmed) { continue; }

            // Detect directory markers
            const isDir = trimmed.endsWith('/') || trimmed.endsWith('\\');
            const cleanPath = trimmed.replace(/[\\/]$/, '');

            entries.push({ path: cleanPath, isDir });
        }

        // Build tree structure
        const treeLines = this.buildTreeLines(entries, '', true);
        return output + treeLines.join('\n');
    }

    /**
     * Build tree lines with proper indentation
     */
    private buildTreeLines(
        entries: Array<{ path: string; isDir: boolean }>,
        prefix: string,
        isLast: boolean
    ): string[] {
        const lines: string[] = [];

        for (let i = 0; i < entries.length; i++) {
            const entry = entries[i];
            const isLastEntry = i === entries.length - 1;
            const branch = isLastEntry ? '└── ' : '├── ';
            const name = entry.path.split(/[\\/]/).pop() || entry.path;
            const suffix = entry.isDir ? '/' : '';

            lines.push(`${prefix}${branch}${name}${suffix}`);
        }

        return lines;
    }

    /**
     * Format result as a table
     */
    private formatAsTable(result: string): string {
        const lines = result.split('\n').filter(l => l.trim());
        if (lines.length === 0) {
            return '(no results)';
        }

        // Try to detect file:line:match format (search results)
        const fileLineMatch = lines[0].match(/^(.+):(\d+):(.+)$/);

        if (fileLineMatch) {
            // File search results → markdown table
            const rows = lines.map(line => {
                const match = line.match(/^(.+):(\d+):(.+)$/);
                if (match) {
                    const [, file, lineNum, content] = match;
                    return `| \`${file}\` | ${lineNum} | ${this.escapeMarkdown(content.trim())} |`;
                }
                return `| ${this.escapeMarkdown(line)} | | |`;
            });

            return '| File | Line | Match |\n|------|------|-------|\n' + rows.join('\n');
        }

        // Generic table: split by tabs or multiple spaces
        const rows = lines.map(line => {
            const cells = line.split(/\t\s*|\s{2,}/).filter(c => c.trim());
            if (cells.length > 1) {
                return '| ' + cells.map(c => this.escapeMarkdown(c.trim())).join(' | ') + ' |';
            }
            return `| ${this.escapeMarkdown(line)} |`;
        });

        return rows.join('\n');
    }

    /**
     * Escape markdown characters
     */
    private escapeMarkdown(text: string): string {
        return text
            .replace(/\|/g, '\\|')
            .replace(/\*/g, '\\*')
            .replace(/_/g, '\\_')
            .replace(/\[/g, '\\[')
            .replace(/\]/g, '\\]');
    }

    /**
     * Get current configuration
     */
    getConfig(): ToolCardConfig {
        return { ...this.config };
    }

    /**
     * Update configuration
     */
    setConfig(config: ToolCardConfig): void {
        this.config = config;
    }

    /**
     * Check if a tool should be visible
     */
    isToolVisible(toolName: string, success: boolean): boolean {
        if (this.config.showTools === 'none') {
            return false;
        }
        if (this.config.showTools === 'failed_only' && success) {
            return false;
        }
        return true;
    }

    /**
     * Get display options for a specific tool
     */
    getToolOptions(toolName: string): ToolDisplayOptions {
        return this.config.tools[toolName] || this.config.defaults;
    }
}
