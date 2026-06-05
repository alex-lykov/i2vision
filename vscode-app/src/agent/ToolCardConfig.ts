/**
 * ToolCardConfig - Configurable tool card display system
 * 
 * Controls how tool results are displayed in the chat UI:
 * - Formatting (raw, markdown, tree, table)
 * - Truncation (max lines, max chars)
 * - Folding (threshold, default state)
 * - Visibility (show args, duration, collapse on success)
 * 
 * Preferences are stored in VS Code workspace settings.
 */

export interface ToolCardConfig {
    /** Which tool results to show */
    showTools: 'all' | 'failed_only' | 'none';

    /** Output formatting per tool */
    tools: Record<string, ToolDisplayOptions>;

    /** Default options for unlisted tools */
    defaults: ToolDisplayOptions;
}

export interface ToolDisplayOptions {
    /** Max lines to show (0 = unlimited) */
    maxLines: number;

    /** Max characters (0 = unlimited) */
    maxChars: number;

    /** Lines before folding kicks in */
    foldThreshold: number;

    /** Default fold state */
    foldDefault: 'expanded' | 'collapsed';

    /** Output format */
    format: 'raw' | 'markdown' | 'tree' | 'table';

    /** Show line numbers */
    showLineNumbers: boolean;

    /** Enable syntax highlighting */
    syntaxHighlight: boolean;

    /** Show tool arguments */
    showArgs: boolean;

    /** Show execution time */
    showDuration: boolean;

    /** Auto-collapse successful results */
    collapseOnSuccess: boolean;
}

/**
 * Formatted tool card ready for webview display
 */
export interface FormattedToolCard {
    toolName: string;
    args?: Record<string, any>;
    result: string;
    durationMs?: number;
    success: boolean;
    foldState: 'expanded' | 'collapsed';
    totalLines: number;
    totalChars: number;
    isTruncated: boolean;
    format: string;
    showLineNumbers: boolean;
    syntaxHighlight: boolean;
}

/**
 * Default tool card configuration
 */
export const DEFAULT_TOOL_CARD_CONFIG: ToolCardConfig = {
    showTools: 'all',

    defaults: {
        maxLines: 20,
        maxChars: 3000,
        foldThreshold: 10,
        foldDefault: 'collapsed',
        format: 'raw',
        showLineNumbers: true,
        syntaxHighlight: false,
        showArgs: true,
        showDuration: true,
        collapseOnSuccess: true
    },

    tools: {
        'list_directory': {
            maxLines: 0,          // Show all
            maxChars: 0,
            foldThreshold: 15,
            foldDefault: 'expanded',
            format: 'tree',       // Format as tree
            showLineNumbers: false,
            syntaxHighlight: false,
            showArgs: true,
            showDuration: false,
            collapseOnSuccess: false
        },
        'list_files': {
            maxLines: 0,
            maxChars: 0,
            foldThreshold: 15,
            foldDefault: 'expanded',
            format: 'tree',
            showLineNumbers: false,
            syntaxHighlight: false,
            showArgs: true,
            showDuration: false,
            collapseOnSuccess: false
        },
        'read_file': {
            maxLines: 50,
            maxChars: 5000,
            foldThreshold: 20,
            foldDefault: 'collapsed',
            format: 'raw',
            showLineNumbers: true,
            syntaxHighlight: true,
            showArgs: true,
            showDuration: false,
            collapseOnSuccess: true
        },
        'search_files': {
            maxLines: 15,
            maxChars: 2000,
            foldThreshold: 5,
            foldDefault: 'collapsed',
            format: 'table',
            showLineNumbers: false,
            syntaxHighlight: false,
            showArgs: false,
            showDuration: true,
            collapseOnSuccess: true
        },
        'i2vision_discover': {
            maxLines: 30,
            maxChars: 8000,
            foldThreshold: 10,
            foldDefault: 'expanded',
            format: 'markdown',
            showLineNumbers: false,
            syntaxHighlight: false,
            showArgs: false,
            showDuration: true,
            collapseOnSuccess: false
        },
        'write_file': {
            maxLines: 10,
            maxChars: 1500,
            foldThreshold: 5,
            foldDefault: 'collapsed',
            format: 'raw',
            showLineNumbers: true,
            syntaxHighlight: true,
            showArgs: true,
            showDuration: true,
            collapseOnSuccess: true
        },
        'edit_file': {
            maxLines: 10,
            maxChars: 1500,
            foldThreshold: 5,
            foldDefault: 'collapsed',
            format: 'raw',
            showLineNumbers: true,
            syntaxHighlight: true,
            showArgs: true,
            showDuration: true,
            collapseOnSuccess: true
        },
        'run_command': {
            maxLines: 30,
            maxChars: 4000,
            foldThreshold: 10,
            foldDefault: 'collapsed',
            format: 'raw',
            showLineNumbers: false,
            syntaxHighlight: false,
            showArgs: true,
            showDuration: true,
            collapseOnSuccess: true
        },
        'find_definition': {
            maxLines: 10,
            maxChars: 1500,
            foldThreshold: 5,
            foldDefault: 'collapsed',
            format: 'raw',
            showLineNumbers: true,
            syntaxHighlight: true,
            showArgs: true,
            showDuration: false,
            collapseOnSuccess: true
        },
        'find_references': {
            maxLines: 15,
            maxChars: 2000,
            foldThreshold: 5,
            foldDefault: 'collapsed',
            format: 'table',
            showLineNumbers: false,
            syntaxHighlight: false,
            showArgs: true,
            showDuration: false,
            collapseOnSuccess: true
        },
        'find_symbols': {
            maxLines: 20,
            maxChars: 3000,
            foldThreshold: 10,
            foldDefault: 'collapsed',
            format: 'table',
            showLineNumbers: false,
            syntaxHighlight: false,
            showArgs: true,
            showDuration: false,
            collapseOnSuccess: true
        },
        'find_implementations': {
            maxLines: 15,
            maxChars: 2000,
            foldThreshold: 5,
            foldDefault: 'collapsed',
            format: 'table',
            showLineNumbers: false,
            syntaxHighlight: false,
            showArgs: true,
            showDuration: false,
            collapseOnSuccess: true
        },
        'document_symbols': {
            maxLines: 20,
            maxChars: 3000,
            foldThreshold: 10,
            foldDefault: 'collapsed',
            format: 'tree',
            showLineNumbers: false,
            syntaxHighlight: false,
            showArgs: true,
            showDuration: false,
            collapseOnSuccess: true
        }
    }
};

/**
 * Settings keys for VS Code configuration
 */
export const SETTINGS_PREFIX = 'i2vision.toolCards';

export const SETTING_KEYS = {
    showTools: `${SETTINGS_PREFIX}.showTools`,
    defaultMaxLines: `${SETTINGS_PREFIX}.defaultMaxLines`,
    defaultMaxChars: `${SETTINGS_PREFIX}.defaultMaxChars`,
    defaultFoldThreshold: `${SETTINGS_PREFIX}.defaultFoldThreshold`,
    defaultFoldDefault: `${SETTINGS_PREFIX}.defaultFoldDefault`,
    defaultFormat: `${SETTINGS_PREFIX}.defaultFormat`,
    defaultShowLineNumbers: `${SETTINGS_PREFIX}.defaultShowLineNumbers`,
    defaultSyntaxHighlight: `${SETTINGS_PREFIX}.defaultSyntaxHighlight`,
    defaultShowArgs: `${SETTINGS_PREFIX}.defaultShowArgs`,
    defaultShowDuration: `${SETTINGS_PREFIX}.defaultShowDuration`,
    defaultCollapseOnSuccess: `${SETTINGS_PREFIX}.defaultCollapseOnSuccess`,
    perToolConfig: `${SETTINGS_PREFIX}.perToolConfig`
} as const;
