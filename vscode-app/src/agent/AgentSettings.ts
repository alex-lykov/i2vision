/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

/**
 * AgentSettings - Manages agent configuration and settings
 * 
 * Provides:
 * - Default settings with user overrides
 * - Settings persistence in .vscode/i2-vision-settings.json
 * - Type-safe access to configuration values
 * - Settings change events
 */

import * as vscode from 'vscode';
import {SettingsStore} from './settings/model/SettingsStore';
import type { ProviderRules } from './settings/model/ProviderRules';

/**
 * Agent settings interface
 */
export interface AgentSettings {
  // ===== STREAMING SETTINGS =====
  streaming: {
    enabled: boolean;
    chunkSize: number; // Characters per chunk
    chunkDelayMs: number; // Delay between chunks
    showThinkingIndicator: boolean;
  };
  
  // ===== TERMINAL SETTINGS =====
  terminal: {
    autoCloseDelayMs: number; // Delay before auto-closing short-lived terminals
    showOutputInWebview: boolean; // Show terminal output in webview timeline
    preserveTerminals: boolean; // Don't auto-close terminals
    maxTerminalHistory: number; // Max lines to keep in terminal history
    mode: 'managed' | 'vscode' | 'hybrid'; // Which terminals to use: agent-managed, VS Code, or both
    // NEW: configurable timeouts for long-running command output capture
    serverStartupTimeoutMs: number; // Timeout for server startup output capture (e.g., gradle :app:server:run)
    buildOutputCaptureTimeoutMs: number; // Timeout for build output capture (e.g., gradle build)
  };
  
  // ===== BUILD SETTINGS =====
  build: {
    timeoutSeconds: number;
    captureOutput: boolean;
    showErrorsProminently: boolean;
    extractFileReferences: boolean;
  };
  
  // ===== UI SETTINGS =====
  ui: {
    showTokenUsage: boolean;
    showDuration: boolean;
    showToolCards: boolean;
    collapseOldToolCards: boolean;
    maxVisibleToolCards: number;
    theme: 'auto' | 'light' | 'dark';
  };
  
  // ===== AGENT BEHAVIOR =====
  agent: {
    maxIterations: number;
    maxConsecutiveToolCalls: number;
    enableLoopDetection: boolean;
    autoSaveConversation: boolean;
    conversationHistoryLimit: number;
  };
  
  // ===== MODEL SETTINGS =====
  model: {
    defaultProvider: 'ollama' | 'deepseek' | '3d-llm' | 'mistral';
    defaultModel: string;
    contextLength: number;
    maxOutputTokens: number;
    temperature: number;
    topP: number;
    thinkingEnabled: boolean;
    searchEnabled: boolean;
  };

  // ===== MISTRAL API =====
  mistral: {
    apiKey: string;
    baseUrl: string;
  };

  // ===== 3D LLM PROXY =====
  proxy: {
    baseUrl: string;
  };
  
  // ===== ADVANCED =====
  advanced: {
    debugLogging: boolean;
    logToolCalls: boolean;
    logLLMRequests: boolean;
    enableExperimentalFeatures: boolean;
  };

  // ===== PROMPT PARTS =====
  prompt: {
    coreRulesEnabled: boolean;
    projectContextEnabled: boolean;
    toolProtocolEnabled: boolean;
    coreRulesText: string;
    projectContextText: string;
  };

  // ===== PER-MODEL PROMPT OVERRIDES =====
  modelProfiles: {
    [modelId: string]: {
      coreRulesText?: string;
      projectContextText?: string;
      toolProtocolEnabled?: boolean;
    };
  };

  // ===== PER-PROVIDER PROMPT RULES =====
  providerPromptRules: {
    [providerId: string]: {
      toolRules?: string;
      promptRules?: string;
    };
  };

  /** Editable per‑provider rule blocks */
  providerRules?: ProviderRules;
}

/**
 * Default settings
 */
export const DEFAULT_SETTINGS: AgentSettings = {
  streaming: {
    enabled: true,
    chunkSize: 50,
    chunkDelayMs: 20,
    showThinkingIndicator: true
  },
  
  terminal: {
    autoCloseDelayMs: 5000,
    showOutputInWebview: true,
    preserveTerminals: false,
    maxTerminalHistory: 1000,
    mode: 'hybrid', // Default: use both managed and VS Code terminals
    serverStartupTimeoutMs: 60000, // Server startup can take 30-60s for Gradle
    buildOutputCaptureTimeoutMs: 30000 // Build output capture: 30s
  },
  
  build: {
    timeoutSeconds: 120,
    captureOutput: true,
    showErrorsProminently: true,
    extractFileReferences: true
  },
  
  ui: {
    showTokenUsage: true,
    showDuration: true,
    showToolCards: true,
    collapseOldToolCards: true,
    maxVisibleToolCards: 5,
    theme: 'auto'
  },
  
  agent: {
    maxIterations: 10,
    maxConsecutiveToolCalls: 5,
    enableLoopDetection: true,
    autoSaveConversation: true,
    conversationHistoryLimit: 50
  },
  
  model: {
    defaultProvider: 'ollama',
    defaultModel: 'llama3.2:3b',
    contextLength: 8192,
    maxOutputTokens: 4096,
    temperature: 0.7,
    topP: 0.9,
    thinkingEnabled: false,
    searchEnabled: false
  },

  mistral: {
    apiKey: '',
    baseUrl: 'https://api.mistral.ai'
  },

  proxy: {
    baseUrl: 'http://localhost:9655'
  },

  advanced: {
    debugLogging: false,
    logToolCalls: true,
    logLLMRequests: false,
    enableExperimentalFeatures: false
  },

  prompt: {
    coreRulesEnabled: true,
    projectContextEnabled: true,
    toolProtocolEnabled: true,
    coreRulesText: 'You are an AI assistant for the VSLFC (Vision-Structure-Logic-Flow-Code) architecture.',
    projectContextText: ''
  },
  modelProfiles: {},
  providerPromptRules: {}
};

/**
 * Settings file path
 */
const SETTINGS_FILE_NAME = 'i2-vision-settings.json';

/**
 * AgentSettings - Singleton for managing agent configuration
 */
export class AgentSettingsManager {
  private static instance: AgentSettingsManager | null = null;
  private store: SettingsStore;

  private constructor(context: vscode.ExtensionContext) {
    this.store = new SettingsStore(context);
  }

  static getInstance(context: vscode.ExtensionContext): AgentSettingsManager {
    if (!AgentSettingsManager.instance) {
      AgentSettingsManager.instance = new AgentSettingsManager(context);
    }
    return AgentSettingsManager.instance;
  }

  /** Expose the settings store */
  getStore(): SettingsStore {
    return this.store;
  }

  /** Get all settings */
  getSettings(): AgentSettings {
    return this.store.getAll();
  }

  /** Update settings with partial values */
  async updateSettings(partial: Partial<AgentSettings>): Promise<void> {
    await this.store.update(partial);
  }

  /** Reset settings to defaults */
  async resetToDefaults(): Promise<void> {
    await this.store.reset();
  }
}

/** Simple settings validation - placeholder implementation */
export function validateSettings(_settings: Partial<AgentSettings> | AgentSettings): { valid: boolean; errors: string[] } {
  // TODO: implement proper validation based on schema
  return { valid: true, errors: [] };
}


