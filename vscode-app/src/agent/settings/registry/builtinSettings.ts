/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

// Built-in setting descriptors for MVVM settings UI
// Each descriptor describes a config UI section

import {SettingDescriptor} from '../model/SettingDescriptor';

export const BUILTIN_SETTING_DESCRIPTORS: SettingDescriptor[] = [
  // ===== STREAMING SETTINGS =====
  {
    key: 'streaming.enabled',
    section: 'Streaming',
    label: 'Enable Streaming',
    description: 'Stream responses from the AI model',
    type: 'boolean',
    defaultValue: true,
    ui: { order: 1 }
  },
  {
    key: 'streaming.chunkSize',
    section: 'Streaming',
    label: 'Chunk Size',
    description: 'Characters per chunk when streaming',
    type: 'number',
    defaultValue: 50,
    min: 10,
    max: 200,
    ui: { order: 2 }
  },
  {
    key: 'streaming.chunkDelayMs',
    section: 'Streaming',
    label: 'Chunk Delay',
    description: 'Delay between chunks in milliseconds',
    type: 'number',
    defaultValue: 20,
    min: 0,
    max: 1000,
    ui: { order: 3 }
  },
  {
    key: 'streaming.showThinkingIndicator',
    section: 'Streaming',
    label: 'Show Thinking Indicator',
    description: 'Display thinking/reasoning indicator',
    type: 'boolean',
    defaultValue: true,
    ui: { order: 4 }
  },

  // ===== TERMINAL SETTINGS =====
  {
    key: 'terminal.mode',
    section: 'Terminal',
    label: 'Terminal Mode',
    description: 'Which terminals to use: agent-managed, VS Code, or both',
    type: 'enum',
    defaultValue: 'hybrid',
    enumValues: [
      { value: 'managed', label: 'Agent-Managed' },
      { value: 'vscode', label: 'VS Code' },
      { value: 'hybrid', label: 'Hybrid (Both)' }
    ],
    ui: { order: 1 }
  },
  {
    key: 'terminal.autoCloseDelayMs',
    section: 'Terminal',
    label: 'Auto-Close Delay',
    description: 'Delay before auto-closing short-lived terminals (ms)',
    type: 'number',
    defaultValue: 5000,
    min: 0,
    max: 60000,
    ui: { order: 2 }
  },
  {
    key: 'terminal.showOutputInWebview',
    section: 'Terminal',
    label: 'Show Output in Webview',
    description: 'Display terminal output in agent webview timeline',
    type: 'boolean',
    defaultValue: true,
    ui: { order: 3 }
  },
  {
    key: 'terminal.preserveTerminals',
    section: 'Terminal',
    label: 'Preserve Terminals',
    description: 'Don\'t auto-close terminals after execution',
    type: 'boolean',
    defaultValue: false,
    ui: { order: 4 }
  },
  {
    key: 'terminal.serverStartupTimeoutMs',
    section: 'Terminal',
    label: 'Server Startup Timeout',
    description: 'Timeout for server startup output capture (ms)',
    type: 'number',
    defaultValue: 60000,
    min: 10000,
    max: 300000,
    ui: { order: 5 }
  },
  {
    key: 'terminal.buildOutputCaptureTimeoutMs',
    section: 'Terminal',
    label: 'Build Output Timeout',
    description: 'Timeout for build output capture (ms)',
    type: 'number',
    defaultValue: 30000,
    min: 5000,
    max: 120000,
    ui: { order: 6 }
  },

  // ===== BUILD SETTINGS =====
  {
    key: 'build.timeoutSeconds',
    section: 'Build',
    label: 'Build Timeout',
    description: 'Build command timeout in seconds',
    type: 'number',
    defaultValue: 120,
    min: 10,
    max: 600,
    ui: { order: 1 }
  },
  {
    key: 'build.captureOutput',
    section: 'Build',
    label: 'Capture Output',
    description: 'Capture build command output',
    type: 'boolean',
    defaultValue: true,
    ui: { order: 2 }
  },
  {
    key: 'build.showErrorsProminently',
    section: 'Build',
    label: 'Show Errors Prominently',
    description: 'Display build errors prominently',
    type: 'boolean',
    defaultValue: true,
    ui: { order: 3 }
  },

  // ===== UI SETTINGS =====
  {
    key: 'ui.showTokenUsage',
    section: 'UI',
    label: 'Show Token Usage',
    description: 'Display token usage statistics',
    type: 'boolean',
    defaultValue: true,
    ui: { order: 1 }
  },
  {
    key: 'ui.showDuration',
    section: 'UI',
    label: 'Show Duration',
    description: 'Display request duration',
    type: 'boolean',
    defaultValue: true,
    ui: { order: 2 }
  },
  {
    key: 'ui.showToolCards',
    section: 'UI',
    label: 'Show Tool Cards',
    description: 'Display tool execution cards',
    type: 'boolean',
    defaultValue: true,
    ui: { order: 3 }
  },
  {
    key: 'ui.collapseOldToolCards',
    section: 'UI',
    label: 'Collapse Old Tool Cards',
    description: 'Automatically collapse old tool cards',
    type: 'boolean',
    defaultValue: true,
    ui: { order: 4 }
  },
  {
    key: 'ui.maxVisibleToolCards',
    section: 'UI',
    label: 'Max Visible Tool Cards',
    description: 'Maximum number of visible tool cards',
    type: 'number',
    defaultValue: 5,
    min: 1,
    max: 20,
    ui: { order: 5 }
  },

  // ===== AGENT BEHAVIOR =====
  {
    key: 'agent.maxIterations',
    section: 'Agent',
    label: 'Max Iterations',
    description: 'Maximum agent iterations per task',
    type: 'number',
    defaultValue: 10,
    min: 1,
    max: 50,
    ui: { order: 1 }
  },
  {
    key: 'agent.maxConsecutiveToolCalls',
    section: 'Agent',
    label: 'Max Consecutive Tool Calls',
    description: 'Maximum consecutive tool calls before synthesis',
    type: 'number',
    defaultValue: 5,
    min: 1,
    max: 20,
    ui: { order: 2 }
  },
  {
    key: 'agent.enableLoopDetection',
    section: 'Agent',
    label: 'Enable Loop Detection',
    description: 'Detect and prevent infinite loops',
    type: 'boolean',
    defaultValue: true,
    ui: { order: 3 }
  },

  // ===== MODEL SETTINGS =====
  {
    key: 'model.defaultProvider',
    section: 'Model',
    label: 'Default Provider',
    description: 'Default LLM provider',
    type: 'enum',
    defaultValue: 'ollama',
    enumValues: [
      { value: 'ollama', label: 'Ollama (Local)' },
      { value: 'deepseek', label: 'DeepSeek (Cloud)' },
      { value: 'mistral', label: 'Mistral (Cloud)' },
      { value: '3d-llm', label: '3D LLM (Proxy)' }
    ],
    ui: { order: 1 }
  },
  {
    key: 'model.defaultModel',
    section: 'Model',
    label: 'Default Model',
    description: 'Default model ID',
    type: 'string',
    defaultValue: 'llama3.2:3b',
    ui: { order: 2, placeholder: 'e.g., llama3.2:3b' }
  },
  {
    key: 'model.contextLength',
    section: 'Model',
    label: 'Context Length',
    description: 'Maximum context window size',
    type: 'number',
    defaultValue: 8192,
    min: 1024,
    max: 131072,
    ui: { order: 3 }
  },
  {
    key: 'model.temperature',
    section: 'Model',
    label: 'Temperature',
    description: 'Sampling temperature (0-2)',
    type: 'number',
    defaultValue: 0.7,
    min: 0,
    max: 2,
    step: 0.1,
    ui: { order: 4 }
  },
  {
    key: 'model.thinkingEnabled',
    section: 'Model',
    label: 'Enable Thinking',
    description: 'Enable model thinking/reasoning',
    type: 'boolean',
    defaultValue: false,
    ui: { order: 5 }
  },

  // ===== PROVIDER URLS =====
  {
    key: 'provider.ollama.baseUrl',
    section: 'Providers',
    label: 'Ollama Base URL',
    description: 'URL of the local Ollama server',
    type: 'string',
    defaultValue: 'http://localhost:11434',
    ui: { order: 1, placeholder: 'http://localhost:11434' }
  },
  {
    key: 'provider.deepseek.baseUrl',
    section: 'Providers',
    label: 'DeepSeek Base URL',
    description: 'Endpoint for DeepSeek API',
    type: 'string',
    defaultValue: 'https://api.deepseek.com',
    ui: { order: 2, placeholder: 'https://api.deepseek.com' }
  },
  {
    key: 'provider.mistral.baseUrl',
    section: 'Providers',
    label: 'Mistral Base URL',
    description: 'Endpoint for Mistral API',
    type: 'string',
    defaultValue: 'https://api.mistral.ai',
    ui: { order: 3, placeholder: 'https://api.mistral.ai' }
  },
  {
    key: 'provider.3d-llm.baseUrl',
    section: 'Providers',
    label: '3D LLM Proxy URL',
    description: 'URL of the 3D LLM proxy server',
    type: 'string',
    defaultValue: 'http://localhost:9655',
    ui: { order: 4, placeholder: 'http://localhost:9655' }
  },
  {
    key: 'mistral.apiKey',
    section: 'Providers',
    label: 'Mistral API Key',
    description: 'API key for Mistral AI',
    type: 'string',
    defaultValue: '',
    ui: { order: 5, placeholder: 'Your Mistral API key' }
  },

  // ===== ADVANCED =====
  {
    key: 'advanced.debugLogging',
    section: 'Advanced',
    label: 'Debug Logging',
    description: 'Enable debug logging',
    type: 'boolean',
    defaultValue: false,
    ui: { order: 1 }
  },
  {
    key: 'advanced.logToolCalls',
    section: 'Advanced',
    label: 'Log Tool Calls',
    description: 'Log all tool calls',
    type: 'boolean',
    defaultValue: true,
    ui: { order: 2 }
  },
  {
    key: 'advanced.logLLMRequests',
    section: 'Advanced',
    label: 'Log LLM Requests',
    description: 'Log raw LLM requests and responses',
    type: 'boolean',
    defaultValue: false,
    ui: { order: 3 }
  },

  // ===== PROMPT SETTINGS =====
  {
    key: 'prompt.coreRulesEnabled',
    section: 'Prompt',
    label: 'Core Rules Enabled',
    description: 'Enable core system prompt rules',
    type: 'boolean',
    defaultValue: true,
    ui: { order: 1 }
  },
  {
    key: 'prompt.projectContextEnabled',
    section: 'Prompt',
    label: 'Project Context Enabled',
    description: 'Enable project context in prompts',
    type: 'boolean',
    defaultValue: true,
    ui: { order: 2 }
  },
  {
    key: 'prompt.toolProtocolEnabled',
    section: 'Prompt',
    label: 'Tool Protocol Enabled',
    description: 'Enable tool protocol in prompts',
    type: 'boolean',
    defaultValue: true,
    ui: { order: 3 }
  }
];
