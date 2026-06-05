/**
 * ToolCardManager - Manages tool card formatting for agent tabs
 * 
 * Integrates ToolCardFormatter with the agent tab system.
 * Formats tool results before sending to the webview.
 */

import * as vscode from 'vscode';
import { ToolCardFormatter } from './ToolCardFormatter';
import { FormattedToolCard, ToolCardConfig } from './ToolCardConfig';
import { ProgressEvent, ToolCall } from './AgentBridge';

/**
 * Formatted progress event for webview
 */
export interface FormattedProgressEvent {
    type: 'tool_start' | 'tool_complete' | 'iteration_complete' | 'thinking';
    iteration: number;
    toolCard?: FormattedToolCard;
    toolCall?: {
        toolName: string;
        args: Record<string, any>;
    };
    message?: string;
}

/**
 * Formatted agent response for webview
 */
export interface FormattedAgentResponse {
    text: string;
    toolCards: FormattedToolCard[];
    iterations: number;
    durationMs: number;
    success: boolean;
}

/**
 * ToolCardManager - singleton per workspace
 */
export class ToolCardManager implements vscode.Disposable {
    private formatter: ToolCardFormatter;
    private outputChannel?: vscode.OutputChannel;
    private disposables: vscode.Disposable[] = [];

    constructor(outputChannel?: vscode.OutputChannel) {
        this.outputChannel = outputChannel;
        this.formatter = new ToolCardFormatter();

        // Watch for configuration changes
        const configWatcher = vscode.workspace.onDidChangeConfiguration(e => {
            if (e.affectsConfiguration('i2vision.toolCards')) {
                this.log('Tool card configuration changed, reloading...');
                this.formatter.reloadConfig();
            }
        });
        this.disposables.push(configWatcher);

        this.log('ToolCardManager initialized');
    }

    /**
     * Format a progress event for display
     */
    formatProgressEvent(event: ProgressEvent): FormattedProgressEvent {
        if (event.type === 'tool_complete' && event.toolCall) {
            const toolCard = this.formatter.format(
                event.toolCall.toolName,
                event.toolCall.args || {},
                event.toolCall.result || event.toolCall.error || 'No result',
                event.toolCall.durationMs || 0,
                !event.toolCall.error
            );

            return {
                type: event.type,
                iteration: event.iteration,
                toolCard: toolCard || undefined,
                toolCall: {
                    toolName: event.toolCall.toolName,
                    args: event.toolCall.args || {}
                }
            };
        }

        return {
            type: event.type,
            iteration: event.iteration,
            message: event.message
        };
    }

    /**
     * Format a complete agent response
     */
    formatAgentResponse(
        text: string,
        toolCalls: ToolCall[],
        iterations: number,
        durationMs: number,
        success: boolean
    ): FormattedAgentResponse {
        const toolCards: FormattedToolCard[] = [];

        for (const tc of toolCalls) {
            const card = this.formatter.format(
                tc.toolName,
                tc.args || {},
                tc.result || tc.error || 'No result',
                tc.durationMs || 0,
                !tc.error
            );

            if (card) {
                toolCards.push(card);
            }
        }

        return {
            text,
            toolCards,
            iterations,
            durationMs,
            success
        };
    }

    /**
     * Format a single tool call for real-time display
     */
    formatToolCall(
        toolName: string,
        args: Record<string, any>,
        result: string,
        durationMs: number,
        success: boolean
    ): FormattedToolCard | null {
        return this.formatter.format(toolName, args, result, durationMs, success);
    }

    /**
     * Get current formatter configuration
     */
    getConfig(): ToolCardConfig {
        return this.formatter.getConfig();
    }

    /**
     * Update formatter configuration
     */
    setConfig(config: ToolCardConfig): void {
        this.formatter.setConfig(config);
    }

    /**
     * Reload configuration from VS Code settings
     */
    reloadConfig(): void {
        this.formatter.reloadConfig();
    }

    /**
     * Check if a tool result should be visible
     */
    isToolVisible(toolName: string, success: boolean): boolean {
        return this.formatter.isToolVisible(toolName, success);
    }

    /**
     * Log a message
     */
    private log(message: string): void {
        const timestamp = new Date().toLocaleTimeString();
        const formatted = `[${timestamp}] [ToolCardManager] ${message}`;
        if (this.outputChannel) {
            this.outputChannel.appendLine(formatted);
        }
        console.log(formatted);
    }

    /**
     * Dispose resources
     */
    dispose(): void {
        for (const d of this.disposables) {
            d.dispose();
        }
        this.disposables = [];
        this.log('ToolCardManager disposed');
    }
}
