/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

import type { SettingDescriptor } from '../model/SettingDescriptor';

/**
 * Built-in setting descriptors migrated from the legacy AgentSettings model.
 */
export const BUILTIN_SETTING_DESCRIPTORS: SettingDescriptor[] = [
  // ===== Streaming =====
  {
    key: 'streaming.enabled',
    type: 'boolean',
    defaultValue: true,
    label: 'Enable Streaming',
    description: 'Stream agent responses in real-time',
    group: 'Streaming',
    order: 10,
    visibility: 'basic'
  },
  {
    key: 'streaming.showThinkingIndicator',
    type: 'boolean',
    defaultValue: true,
    label: 'Show Thinking Indicator',
    description: 'Display spinner while agent is thinking',
    group: 'Streaming',
    order: 20,
    visibility: 'basic'
  },
  {
    key: 'streaming.chunkSize',
    type: 'range',
    defaultValue: 50,
    label: 'Chunk Size',
    description: 'Characters per streaming chunk',
    group: 'Streaming',
    order: 30,
    min: 10,
    max: 500,
    step: 10,
    visibility: 'advanced'
  },
  {
    key: 'streaming.chunkDelayMs',
    type: 'range',
    defaultValue: 20,
    label: 'Chunk Delay',
    description: 'Delay between chunks (milliseconds)',
    group: 'Streaming',
    order: 40,
    min: 0,
    max: 500,
    step: 10,
    visibility: 'advanced'
  },

  // ===== Terminal =====
  {
    key: 'terminal.showOutputInWebview',
    type: 'boolean',
    defaultValue: true,
    label: 'Show Output in Webview',
    description: 'Display terminal output in agent timeline',
    group: 'Terminal',
    order: 10,
    visibility: 'basic'
  },
  {
    key: 'terminal.preserveTerminals',
    type: 'boolean',
    defaultValue: false,
    label: 'Preserve Terminals',
    description: "Don't auto-close terminals",
    group: 'Terminal',
    order: 20,
    visibility: 'basic'
  },
  {
    key: 'terminal.autoCloseDelayMs',
    type: 'range',
    defaultValue: 5000,
    label: 'Auto-close Delay',
    description: 'Delay before auto-closing short-lived terminals',
    group: 'Terminal',
    order: 30,
    min: 0,
    max: 60000,
    step: 500,
    visibility: 'advanced'
  },
  {
    key: 'terminal.maxTerminalHistory',
    type: 'range',
    defaultValue: 1000,
    label: 'Max Terminal History',
    description: 'Max lines to keep in terminal history',
    group: 'Terminal',
    order: 40,
    min: 100,
    max: 10000,
    step: 100,
    visibility: 'advanced'
  },
  {
    key: 'terminal.mode',
    type: 'enum',
    defaultValue: 'hybrid',
    label: 'Terminal Mode',
    description: 'Which terminals to use: agent-managed, VS Code, or both',
    group: 'Terminal',
    order: 50,
    enumValues: [
      { value: 'managed', label: 'Managed' },
      { value: 'vscode', label: 'VS Code' },
      { value: 'hybrid', label: 'Hybrid' }
    ],
    visibility: 'advanced'
  },
  {
    key: 'terminal.serverStartupTimeoutMs',
    type: 'range',
    defaultValue: 60000,
    label: 'Server Startup Timeout',
    description: 'Timeout for server startup output capture',
    group: 'Terminal',
    order: 60,
    min: 1000,
    max: 300000,
    step: 1000,
    visibility: 'advanced'
  },
  {
    key: 'terminal.buildOutputCaptureTimeoutMs',
    type: 'range',
    defaultValue: 30000,
    label: 'Build Output Capture Timeout',
    description: 'Timeout for build output capture',
    group: 'Terminal',
    order: 70,
    min: 1000,
    max: 300000,
    step: 1000,
    visibility: 'advanced'
  },

  // ===== Build =====
  {
    key: 'build.timeoutSeconds',
    type: 'range',
    defaultValue: 120,
    label: 'Build Timeout',
    description: 'Build timeout in seconds',
    group: 'Build',
    order: 10,
    min: 10,
    max: 600,
    step: 10,
    visibility: 'basic'
  },
  {
    key: 'build.captureOutput',
    type: 'boolean',
    defaultValue: true,
    label: 'Capture Output',
    description: 'Capture build output',
    group: 'Build',
    order: 20,
    visibility: 'basic'
  },
  {
    key: 'build.showErrorsProminently',
    type: 'boolean',
    defaultValue: true,
    label: 'Show Errors Prominently',
    description: 'Highlight build errors in the UI',
    group: 'Build',
    order: 30,
    visibility: 'basic'
  },
  {
    key: 'build.extractFileReferences',
    type: 'boolean',
    defaultValue: true,
    label: 'Extract File References',
    description: 'Extract file references from build errors',
    group: 'Build',
    order: 40,
    visibility: 'advanced'
  },

  // ===== UI =====
  {
    key: 'ui.showTokenUsage',
    type: 'boolean',
    defaultValue: true,
    label: 'Show Token Usage',
    description: 'Display token usage information',
    group: 'UI',
    order: 10,
    visibility: 'basic'
  },
  {
    key: 'ui.showDuration',
    type: 'boolean',
    defaultValue: true,
    label: 'Show Duration',
    description: 'Display operation duration',
    group: 'UI',
    order: 20,
    visibility: 'basic'
  },
  {
    key: 'ui.showToolCards',
    type: 'boolean',
    defaultValue: true,
    label: 'Show Tool Cards',
    description: 'Display tool call cards in the timeline',
    group: 'UI',
    order: 30,
    visibility: 'basic'
  },
  {
    key: 'ui.collapseOldToolCards',
    type: 'boolean',
    defaultValue: true,
    label: 'Collapse Old Tool Cards',
    description: 'Collapse older tool cards automatically',
    group: 'UI',
    order: 40,
    visibility: 'advanced'
  },
  {
    key: 'ui.maxVisibleToolCards',
    type: 'range',
    defaultValue: 5,
    label: 'Max Visible Tool Cards',
    description: 'Maximum number of visible tool cards',
    group: 'UI',
    order: 50,
    min: 1,
    max: 50,
    step: 1,
    visibility: 'advanced'
  },
  {
    key: 'ui.theme',
    type: 'enum',
    defaultValue: 'auto',
    label: 'Theme',
    description: 'UI theme',
    group: 'UI',
    order: 60,
    enumValues: [
      { value: 'auto', label: 'Auto' },
      { value: 'light', label: 'Light' },
      { value: 'dark', label: 'Dark' }
    ],
    visibility: 'basic'
  },

  // ===== Agent Behavior =====
  {
    key: 'agent.maxIterations',
    type: 'range',
    defaultValue: 10,
    label: 'Max Iterations',
    description: 'Maximum agent iterations',
    group: 'Agent Behavior',
    order: 10,
    min: 1,
    max: 100,
    step: 1,
    visibility: 'advanced'
  },
  {
    key: 'agent.maxConsecutiveToolCalls',
    type: 'range',
    defaultValue: 5,
    label: 'Max Consecutive Tool Calls',
    description: 'Maximum consecutive tool calls before yielding',
    group: 'Agent Behavior',
    order: 20,
    min: 1,
    max: 50,
    step: 1,
    visibility: 'advanced'
  },
  {
    key: 'agent.enableLoopDetection',
    type: 'boolean',
    defaultValue: true,
    label: 'Enable Loop Detection',
    description: 'Detect and stop agent loops',
    group: 'Agent Behavior',
    order: 30,
    visibility: 'basic'
  },
  {
    key: 'agent.autoSaveConversation',
    type: 'boolean',
    defaultValue: true,
    label: 'Auto-save Conversation',
    description: 'Automatically save conversation history',
    group: 'Agent Behavior',
    order: 40,
    visibility: 'basic'
  },
  {
    key: 'agent.conversationHistoryLimit',
    type: 'range',
    defaultValue: 50,
    label: 'Conversation History Limit',
    description: 'Maximum number of conversation entries to retain',
    group: 'Agent Behavior',
    order: 50,
    min: 1,
    max: 500,
    step: 1,
    visibility: 'advanced'
  },

  // ===== Model =====
  {
    key: 'model.defaultProvider',
    type: 'enum',
    defaultValue: 'ollama',
    label: 'Default Provider',
    description: 'Default model provider',
    group: 'Model',
    order: 10,
    enumValues: [
      { value: 'ollama', label: 'Ollama' },
      { value: 'deepseek', label: 'DeepSeek' },
      { value: '3d-llm', label: '3D LLM' },
      { value: 'mistral', label: 'Mistral' }
    ],
    visibility: 'basic'
  },
  {
    key: 'model.defaultModel',
    type: 'string',
    defaultValue: 'llama3.2:3b',
    label: 'Default Model',
    description: 'Default model identifier',
    group: 'Model',
    order: 20,
    visibility: 'basic'
  },
  {
    key: 'model.contextLength',
    type: 'range',
    defaultValue: 8192,
    label: 'Context Length',
    description: 'Maximum context length',
    group: 'Model',
    order: 30,
    min: 512,
    max: 131072,
    step: 512,
    visibility: 'advanced'
  },
  {
    key: 'model.maxOutputTokens',
    type: 'range',
    defaultValue: 4096,
    label: 'Max Output Tokens',
    description: 'Maximum generated tokens',
    group: 'Model',
    order: 40,
    min: 128,
    max: 32768,
    step: 128,
    visibility: 'advanced'
  },
  {
    key: 'model.temperature',
    type: 'range',
    defaultValue: 0.7,
    label: 'Temperature',
    description: 'Model sampling temperature',
    group: 'Model',
    order: 50,
    min: 0,
    max: 2,
    step: 0.1,
    visibility: 'advanced'
  },
  {
    key: 'model.topP',
    type: 'range',
    defaultValue: 0.9,
    label: 'Top P',
    description: 'Nucleus sampling threshold',
    group: 'Model',
    order: 60,
    min: 0,
    max: 1,
    step: 0.05,
    visibility: 'advanced'
  },
  {
    key: 'model.thinkingEnabled',
    type: 'boolean',
    defaultValue: false,
    label: 'Thinking Enabled',
    description: 'Enable extended model thinking',
    group: 'Model',
    order: 70,
    visibility: 'advanced'
  },
  {
    key: 'model.searchEnabled',
    type: 'boolean',
    defaultValue: false,
    label: 'Search Enabled',
    description: 'Enable model-backed search',
    group: 'Model',
    order: 80,
    visibility: 'advanced'
  },

  // ===== Mistral API =====
  {
    key: 'mistral.apiKey',
    type: 'string',
    defaultValue: '',
    label: 'Mistral API Key',
    description: 'API key for Mistral provider',
    group: 'Mistral API',
    order: 10,
    visibility: 'advanced'
  },
  {
    key: 'mistral.baseUrl',
    type: 'string',
    defaultValue: 'https://api.mistral.ai',
    label: 'Mistral Base URL',
    description: 'Base URL for Mistral API',
    group: 'Mistral API',
    order: 20,
    visibility: 'advanced'
  },

  // ===== 3D LLM Proxy =====
  {
    key: 'proxy.baseUrl',
    type: 'string',
    defaultValue: 'http://localhost:9655',
    label: 'Proxy Base URL',
    description: 'Base URL for the 3D LLM proxy',
    group: '3D LLM Proxy',
    order: 10,
    visibility: 'advanced'
  },

  // ===== Advanced =====
  {
    key: 'advanced.debugLogging',
    type: 'boolean',
    defaultValue: false,
    label: 'Debug Logging',
    description: 'Enable debug logging',
    group: 'Advanced',
    order: 10,
    visibility: 'advanced'
  },
  {
    key: 'advanced.logToolCalls',
    type: 'boolean',
    defaultValue: true,
    label: 'Log Tool Calls',
    description: 'Log tool call activity',
    group: 'Advanced',
    order: 20,
    visibility: 'advanced'
  },
  {
    key: 'advanced.logLLMRequests',
    type: 'boolean',
    defaultValue: false,
    label: 'Log LLM Requests',
    description: 'Log raw LLM requests',
    group: 'Advanced',
    order: 30,
    visibility: 'advanced'
  },
  {
    key: 'advanced.enableExperimentalFeatures',
    type: 'boolean',
    defaultValue: false,
    label: 'Enable Experimental Features',
    description: 'Enable experimental agent features',
    group: 'Advanced',
    order: 40,
    visibility: 'advanced'
  },

  // ===== Prompt Parts =====
  {
    key: 'prompt.coreRulesEnabled',
    type: 'boolean',
    defaultValue: true,
    label: 'Core Rules Enabled',
    description: 'Include core rules in prompt assembly',
    group: 'Prompt Parts',
    order: 10,
    visibility: 'advanced'
  },
  {
    key: 'prompt.projectContextEnabled',
    type: 'boolean',
    defaultValue: true,
    label: 'Project Context Enabled',
    description: 'Include project context in prompt assembly',
    group: 'Prompt Parts',
    order: 20,
    visibility: 'advanced'
  },
  {
    key: 'prompt.toolProtocolEnabled',
    type: 'boolean',
    defaultValue: true,
    label: 'Tool Protocol Enabled',
    description: 'Include tool protocol in prompt assembly',
    group: 'Prompt Parts',
    order: 30,
    visibility: 'advanced'
  },
  {
    key: 'prompt.coreRulesText',
    type: 'string',
    defaultValue: 'You are an AI assistant for the VSLFC (Vision-Structure-Logic-Flow-Code) architecture.',
    label: 'Core Rules Text',
    description: 'Core rules injected into the prompt',
    group: 'Prompt Parts',
    order: 40,
    visibility: 'advanced'
  },
  {
    key: 'prompt.projectContextText',
    type: 'string',
    defaultValue: '',
    label: 'Project Context Text',
    description: 'Project context injected into the prompt',
    group: 'Prompt Parts',
    order: 50,
    visibility: 'advanced'
  }
];
