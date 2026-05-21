/**
 * i2-Vision UI Configuration
 * 
 * Defines how the agent experience looks in the UI,
 * separate from what the agent does (conf-agent-core).
 * 
 * This structure can be adapted for any client (VS Code, IntelliJ, Web, CLI).
 */

/**
 * i2-Vision UI Configuration interface
 */
export interface I2VisionUiConfig {
  /**
   * Which agent tabs to show
   */
  agents: {
    enabled: ('vision' | 'structure' | 'logic' | 'flow' | 'code')[];
    defaultAgent: 'vision' | 'structure' | 'logic' | 'flow' | 'code';
  };

  /**
   * How the chat interface behaves
   */
  chat: {
    showReasoningTrace: boolean;
    showToolCalls: boolean;
    showIterationCount: boolean;
    showDuration: boolean;
    maxHistoryDisplay: number;
    inputPlaceholder: string;
  };

  /**
   * How context is gathered before sending to agent
   */
  contextGathering: {
    autoIncludeCurrentFile: boolean;
    autoIncludeDiscoveryCache: boolean;
    autoIncludeVslfcContext: boolean;
    promptForMissingContext: boolean;
  };

  /**
   * How tool calls are displayed
   */
  toolCallDisplay: {
    showToolName: boolean;
    showToolArgs: boolean;
    showToolResult: boolean;
    truncateResultLines: number;
    allowedTools: string[]; // Empty = all tools allowed
  };

  /**
   * Streaming configuration
   */
  streaming: {
    enabled: boolean;
    chunkSize: number;
    delayMs: number;
  };

  /**
   * Keyboard shortcuts
   */
  shortcuts: {
    sendMessage: string; // e.g., "Enter"
    newLine: string; // e.g., "Shift+Enter"
    cancelAgent: string; // e.g., "Escape"
    focusInput: string; // e.g., "Ctrl+I"
  };
}

/**
 * Default configuration for VS Code
 */
export const VSCODE_UI_DEFAULTS: I2VisionUiConfig = {
  agents: {
    enabled: ['code', 'structure', 'flow'],
    defaultAgent: 'code'
  },
  chat: {
    showReasoningTrace: true,
    showToolCalls: true,
    showIterationCount: true,
    showDuration: true,
    maxHistoryDisplay: 50,
    inputPlaceholder: 'Ask the agent to analyze, explain, or modify code...'
  },
  contextGathering: {
    autoIncludeCurrentFile: true,
    autoIncludeDiscoveryCache: true,
    autoIncludeVslfcContext: false, // Only when explicitly requested
    promptForMissingContext: true
  },
  toolCallDisplay: {
    showToolName: true,
    showToolArgs: false, // Can be verbose
    showToolResult: false, // Only on hover
    truncateResultLines: 10,
    allowedTools: [] // All tools allowed
  },
  streaming: {
    enabled: true,
    chunkSize: 200,
    delayMs: 10
  },
  shortcuts: {
    sendMessage: 'Enter',
    newLine: 'Shift+Enter',
    cancelAgent: 'Escape',
    focusInput: 'Ctrl+I'
  }
};

/**
 * Load UI configuration from VS Code settings
 */
export function loadUiConfig(): I2VisionUiConfig {
  const vscode = require('vscode');
  const config = vscode.workspace.getConfiguration('i2vision.ui');

  return {
    agents: {
      enabled: config.get('agents.enabled', VSCODE_UI_DEFAULTS.agents.enabled),
      defaultAgent: config.get('agents.defaultAgent', VSCODE_UI_DEFAULTS.agents.defaultAgent)
    },
    chat: {
      showReasoningTrace: config.get('chat.showReasoningTrace', VSCODE_UI_DEFAULTS.chat.showReasoningTrace),
      showToolCalls: config.get('chat.showToolCalls', VSCODE_UI_DEFAULTS.chat.showToolCalls),
      showIterationCount: config.get('chat.showIterationCount', VSCODE_UI_DEFAULTS.chat.showIterationCount),
      showDuration: config.get('chat.showDuration', VSCODE_UI_DEFAULTS.chat.showDuration),
      maxHistoryDisplay: config.get('chat.maxHistoryDisplay', VSCODE_UI_DEFAULTS.chat.maxHistoryDisplay),
      inputPlaceholder: config.get('chat.inputPlaceholder', VSCODE_UI_DEFAULTS.chat.inputPlaceholder)
    },
    contextGathering: {
      autoIncludeCurrentFile: config.get('context.autoIncludeCurrentFile', VSCODE_UI_DEFAULTS.contextGathering.autoIncludeCurrentFile),
      autoIncludeDiscoveryCache: config.get('context.autoIncludeDiscoveryCache', VSCODE_UI_DEFAULTS.contextGathering.autoIncludeDiscoveryCache),
      autoIncludeVslfcContext: config.get('context.autoIncludeVslfcContext', VSCODE_UI_DEFAULTS.contextGathering.autoIncludeVslfcContext),
      promptForMissingContext: config.get('context.promptForMissingContext', VSCODE_UI_DEFAULTS.contextGathering.promptForMissingContext)
    },
    toolCallDisplay: {
      showToolName: config.get('toolCall.showToolName', VSCODE_UI_DEFAULTS.toolCallDisplay.showToolName),
      showToolArgs: config.get('toolCall.showToolArgs', VSCODE_UI_DEFAULTS.toolCallDisplay.showToolArgs),
      showToolResult: config.get('toolCall.showToolResult', VSCODE_UI_DEFAULTS.toolCallDisplay.showToolResult),
      truncateResultLines: config.get('toolCall.truncateResultLines', VSCODE_UI_DEFAULTS.toolCallDisplay.truncateResultLines),
      allowedTools: config.get('toolCall.allowedTools', VSCODE_UI_DEFAULTS.toolCallDisplay.allowedTools)
    },
    streaming: {
      enabled: config.get('streaming.enabled', VSCODE_UI_DEFAULTS.streaming.enabled),
      chunkSize: config.get('streaming.chunkSize', VSCODE_UI_DEFAULTS.streaming.chunkSize),
      delayMs: config.get('streaming.delayMs', VSCODE_UI_DEFAULTS.streaming.delayMs)
    },
    shortcuts: {
      sendMessage: config.get('shortcuts.sendMessage', VSCODE_UI_DEFAULTS.shortcuts.sendMessage),
      newLine: config.get('shortcuts.newLine', VSCODE_UI_DEFAULTS.shortcuts.newLine),
      cancelAgent: config.get('shortcuts.cancelAgent', VSCODE_UI_DEFAULTS.shortcuts.cancelAgent),
      focusInput: config.get('shortcuts.focusInput', VSCODE_UI_DEFAULTS.shortcuts.focusInput)
    }
  };
}
