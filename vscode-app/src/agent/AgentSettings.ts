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
import * as fs from 'fs';
import * as path from 'path';

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
    defaultProvider: 'ollama' | 'deepseek' | '3d-llm';
    defaultModel: string;
    contextLength: number;
    maxOutputTokens: number;
    temperature: number;
    topP: number;
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
}

/**
 * Default settings
 */
const DEFAULT_SETTINGS: AgentSettings = {
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
    topP: 0.9
  },

  proxy: {
    baseUrl: 'http://localhost:9655'
  },

  advanced: {
    debugLogging: false,
    logToolCalls: true,
    logLLMRequests: false,
    enableExperimentalFeatures: false
  }
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
  private settings: AgentSettings;
  private settingsPath: string;
  private context: vscode.ExtensionContext;
  private onDidChangeEmitter: vscode.EventEmitter<Partial<AgentSettings>>;
  
  private constructor(context: vscode.ExtensionContext) {
    this.context = context;
    this.settings = { ...DEFAULT_SETTINGS };
    this.settingsPath = this.getSettingsPath();
    this.onDidChangeEmitter = new vscode.EventEmitter<Partial<AgentSettings>>();
    
    // Load settings from file
    this.loadSettings();
  }
  
  /**
   * Get singleton instance
   */
  static getInstance(context: vscode.ExtensionContext): AgentSettingsManager {
    if (!AgentSettingsManager.instance) {
      AgentSettingsManager.instance = new AgentSettingsManager(context);
    }
    return AgentSettingsManager.instance;
  }
  
  /**
   * Get settings change event
   */
  get onDidChange(): vscode.Event<Partial<AgentSettings>> {
    return this.onDidChangeEmitter.event;
  }
  
  /**
   * Get settings file path
   */
  private getSettingsPath(): string {
    const workspaceRoot = vscode.workspace.workspaceFolders?.[0]?.uri.fsPath;
    if (!workspaceRoot) {
      return path.join(this.context.storagePath || this.context.extensionPath, SETTINGS_FILE_NAME);
    }
    return path.join(workspaceRoot, '.vscode', SETTINGS_FILE_NAME);
  }
  
  /**
   * Load settings from file and VSCode configuration
   */
  private loadSettings(): void {
    try {
      // Start with defaults
      let mergedSettings = { ...DEFAULT_SETTINGS };

      // Layer 1: Load from custom JSON file (legacy support)
      if (fs.existsSync(this.settingsPath)) {
        const content = fs.readFileSync(this.settingsPath, 'utf-8');
        const userSettings = JSON.parse(content);
        mergedSettings = this.deepMerge(mergedSettings, userSettings);
        console.log(`[AgentSettings] Loaded settings from ${this.settingsPath}`);
      }

      // Layer 2: Override with VSCode settings (highest priority)
      const vscodeConfig = vscode.workspace.getConfiguration('i2vision');
      const vscodeOverrides = this.extractVSCodeSettings(vscodeConfig);
      if (Object.keys(vscodeOverrides).length > 0) {
        mergedSettings = this.deepMerge(mergedSettings, vscodeOverrides);
        console.log(`[AgentSettings] VSCode settings applied: ${JSON.stringify(vscodeOverrides)}`);
      }

      this.settings = mergedSettings;
      console.log(`[AgentSettings] Final maxIterations: ${this.settings.agent.maxIterations}`);
    } catch (error: any) {
      console.error(`[AgentSettings] Error loading settings: ${error.message}`);
      vscode.window.showWarningMessage(`Failed to load i2-Vision settings: ${error.message}`);
    }
  }

  /**
   * Extract settings from VSCode configuration
   */
  private extractVSCodeSettings(config: vscode.WorkspaceConfiguration): Partial<AgentSettings> {
    const overrides: any = {};

    // Agent settings
    const maxIterations = config.get<number>('agent.maxIterations');
    if (maxIterations !== undefined && maxIterations > 0) {
      overrides.agent = overrides.agent || {};
      overrides.agent.maxIterations = maxIterations;
    }

    const maxConsecutiveToolCalls = config.get<number>('agent.maxConsecutiveToolCalls');
    if (maxConsecutiveToolCalls !== undefined && maxConsecutiveToolCalls > 0) {
      overrides.agent = overrides.agent || {};
      overrides.agent.maxConsecutiveToolCalls = maxConsecutiveToolCalls;
    }

    // Streaming settings
    const streamingEnabled = config.get<boolean>('streaming.enabled');
    if (streamingEnabled !== undefined) {
      overrides.streaming = overrides.streaming || {};
      overrides.streaming.enabled = streamingEnabled;
    }

    const chunkSize = config.get<number>('streaming.chunkSize');
    if (chunkSize !== undefined && chunkSize > 0) {
      overrides.streaming = overrides.streaming || {};
      overrides.streaming.chunkSize = chunkSize;
    }

    const chunkDelayMs = config.get<number>('streaming.chunkDelayMs');
    if (chunkDelayMs !== undefined && chunkDelayMs >= 0) {
      overrides.streaming = overrides.streaming || {};
      overrides.streaming.chunkDelayMs = chunkDelayMs;
    }

    // Terminal settings
    const autoCloseDelay = config.get<number>('terminal.autoCloseDelay');
    if (autoCloseDelay !== undefined && autoCloseDelay > 0) {
      overrides.terminal = overrides.terminal || {};
      overrides.terminal.autoCloseDelayMs = autoCloseDelay;
    }

    const terminalMode = config.get<string>('terminal.mode');
    if (terminalMode !== undefined) {
      overrides.terminal = overrides.terminal || {};
      overrides.terminal.mode = terminalMode as any;
    }

    // Model settings
    const contextLength = config.get<number>('model.contextLength');
    if (contextLength !== undefined && contextLength > 0) {
      overrides.model = overrides.model || {};
      overrides.model.contextLength = contextLength;
    }

    const temperature = config.get<number>('model.temperature');
    if (temperature !== undefined) {
      overrides.model = overrides.model || {};
      overrides.model.temperature = temperature;
    }

    return overrides;
  }
  
  /**
   * Deep merge two objects
   */
  private deepMerge<T extends object>(target: T, source: Partial<T>): T {
    const result = { ...target };
    
    for (const key in source) {
      if (source.hasOwnProperty(key)) {
        const sourceValue = source[key];
        const targetValue = result[key as keyof T];
        
        if (
          sourceValue &&
          typeof sourceValue === 'object' &&
          !Array.isArray(sourceValue) &&
          targetValue &&
          typeof targetValue === 'object' &&
          !Array.isArray(targetValue)
        ) {
          result[key as keyof T] = this.deepMerge(
            targetValue as unknown as object,
            sourceValue as object
          ) as unknown as T[keyof T];
        } else {
          result[key as keyof T] = sourceValue as T[keyof T];
        }
      }
    }
    
    return result;
  }
  
  /**
   * Save settings to file
   */
  private saveSettings(): void {
    try {
      const dir = path.dirname(this.settingsPath);
      if (!fs.existsSync(dir)) {
        fs.mkdirSync(dir, { recursive: true });
      }
      
      fs.writeFileSync(
        this.settingsPath,
        JSON.stringify(this.settings, null, 2),
        'utf-8'
      );
      
      console.log(`[AgentSettings] Saved settings to ${this.settingsPath}`);
    } catch (error: any) {
      console.error(`[AgentSettings] Error saving settings: ${error.message}`);
      vscode.window.showErrorMessage(`Failed to save i2-Vision settings: ${error.message}`);
    }
  }
  
  /**
   * Get all settings
   */
  getSettings(): AgentSettings {
    return { ...this.settings };
  }
  
  /**
   * Get a specific setting value
   */
  get<K extends keyof AgentSettings>(section: K): AgentSettings[K] {
    return this.settings[section];
  }
  
  /**
   * Update settings
   */
  async updateSettings(updates: Partial<AgentSettings>): Promise<void> {
    this.settings = this.deepMerge(this.settings, updates);
    this.saveSettings();
    this.onDidChangeEmitter.fire(updates);
    
    vscode.window.showInformationMessage('i2-Vision settings updated');
  }
  
  /**
   * Reset to defaults
   */
  async resetToDefaults(): Promise<void> {
    this.settings = { ...DEFAULT_SETTINGS };
    this.saveSettings();
    this.onDidChangeEmitter.fire(DEFAULT_SETTINGS);
    
    vscode.window.showInformationMessage('i2-Vision settings reset to defaults');
  }
  
  /**
   * Get settings as VSCode-compatible configuration
   */
  getVSCodeConfiguration(): { [key: string]: any } {
    return {
      'i2vision.streaming.enabled': this.settings.streaming.enabled,
      'i2vision.streaming.chunkSize': this.settings.streaming.chunkSize,
      'i2vision.terminal.autoCloseDelay': this.settings.terminal.autoCloseDelayMs,
      'i2vision.build.timeout': this.settings.build.timeoutSeconds,
      'i2vision.agent.maxIterations': this.settings.agent.maxIterations,
      'i2vision.model.provider': this.settings.model.defaultProvider,
      'i2vision.model.model': this.settings.model.defaultModel,
      'i2vision.advanced.debugLogging': this.settings.advanced.debugLogging
    };
  }
}

/**
 * Settings validation
 */
export function validateSettings(settings: Partial<AgentSettings>): { valid: boolean; errors: string[] } {
  const errors: string[] = [];
  
  // Validate streaming
  if (settings.streaming) {
    if (settings.streaming.chunkSize < 10 || settings.streaming.chunkSize > 500) {
      errors.push('Streaming chunk size must be between 10 and 500');
    }
    if (settings.streaming.chunkDelayMs < 0 || settings.streaming.chunkDelayMs > 1000) {
      errors.push('Streaming chunk delay must be between 0 and 1000ms');
    }
  }
  
  // Validate terminal
  if (settings.terminal) {
    if (settings.terminal.autoCloseDelayMs < 0 || settings.terminal.autoCloseDelayMs > 60000) {
      errors.push('Terminal auto-close delay must be between 0 and 60000ms');
    }
    if (settings.terminal.maxTerminalHistory < 100 || settings.terminal.maxTerminalHistory > 10000) {
      errors.push('Terminal history limit must be between 100 and 10000 lines');
    }
    if (settings.terminal.serverStartupTimeoutMs < 5000 || settings.terminal.serverStartupTimeoutMs > 300000) {
      errors.push('Server startup timeout must be between 5000 and 300000ms');
    }
    if (settings.terminal.buildOutputCaptureTimeoutMs < 5000 || settings.terminal.buildOutputCaptureTimeoutMs > 120000) {
      errors.push('Build output capture timeout must be between 5000 and 120000ms');
    }
  }
  
  // Validate build
  if (settings.build) {
    if (settings.build.timeoutSeconds < 10 || settings.build.timeoutSeconds > 600) {
      errors.push('Build timeout must be between 10 and 600 seconds');
    }
  }
  
  // Validate agent
  if (settings.agent) {
    if (settings.agent.maxIterations < 1 || settings.agent.maxIterations > 50) {
      errors.push('Max iterations must be between 1 and 50');
    }
    if (settings.agent.conversationHistoryLimit < 10 || settings.agent.conversationHistoryLimit > 200) {
      errors.push('Conversation history limit must be between 10 and 200');
    }
  }
  
  // Validate model
  if (settings.model) {
    if (settings.model.contextLength < 1024 || settings.model.contextLength > 128000) {
      errors.push('Context length must be between 1024 and 128000');
    }
    if (settings.model.maxOutputTokens < 100 || settings.model.maxOutputTokens > 32000) {
      errors.push('Max output tokens must be between 100 and 32000');
    }
    if (settings.model.temperature < 0 || settings.model.temperature > 2) {
      errors.push('Temperature must be between 0 and 2');
    }
    if (settings.model.topP < 0 || settings.model.topP > 1) {
      errors.push('Top P must be between 0 and 1');
    }
  }
  
  return {
    valid: errors.length === 0,
    errors
  };
}
