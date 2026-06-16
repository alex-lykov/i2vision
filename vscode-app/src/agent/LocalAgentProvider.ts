/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

/**
 * LocalAgentProvider - Factory for creating LocalI2VisionAgent instances
 * 
 * This provider loads agent configurations from YAML files and creates
 * LocalI2VisionAgent instances. It manages the lifecycle of agent configs
 * and provides a clean API for agent creation.
 * 
 * Architecture:
 * - Base defaults: conf-agent-core/src/commonMain/resources/default-agent-config.yaml
 * - Layer overrides: {workspace}/.vision-ai/{layer}-agent.yaml
 * - Final config = defaults merged with layer-specific overrides
 * 
 * Features:
 * - Loads default config from extension resources
 * - Loads layer-specific YAML from {workspace}/.vision-ai/{layer}-agent.yaml
 * - Merges configs (layer overrides defaults)
 * - Manages config caching and reloading
 * - Provides fallback if no config exists
 * 
 * CONTEXT MANAGEMENT: Supports context profiles defined in YAML config
 */

import * as vscode from 'vscode';
import * as path from 'path';
import * as fs from 'fs';
import * as yaml from 'js-yaml';
import { LocalI2VisionAgent, VslfcLayer } from './LocalI2VisionAgent';
import { AgentConfig, ContextProfile, TaskContextProfile } from './AgentBridge';

/**
 * LocalAgentProvider - Creates and manages LocalI2VisionAgent instances
 */
export class LocalAgentProvider {
  private configCache: Map<string, AgentConfig> = new Map();
  private outputChannel: vscode.OutputChannel;
  private context: vscode.ExtensionContext;
  private visionAiDir: string;
  private workspaceRoot: string;
  private defaultConfig: AgentConfig | null = null;

  constructor(
    context: vscode.ExtensionContext,
    outputChannel: vscode.OutputChannel
  ) {
    this.context = context;
    this.outputChannel = outputChannel;
    this.workspaceRoot = vscode.workspace.workspaceFolders?.[0]?.uri.fsPath || '';
    
    // Load agent configs from workspace's .vision-ai directory
    // Config path: {workspace}/.vision-ai/{layer}-agent.yaml
    this.visionAiDir = path.join(
      this.workspaceRoot,
      '.vision-ai'
    );
    
    this.log(`LocalAgentProvider initialized. Config dir: ${this.visionAiDir}`);
  }

  /**
   * Initialize the provider - loads default configuration
   */
  async initialize(): Promise<void> {
    try {
      // Load default config from extension resources
      this.defaultConfig = await this.loadDefaultConfig();
      
      // Load project-specific CLI configuration
      await this.loadProjectCliConfig();
      
      this.log('Default configuration loaded successfully');
      this.log('LocalAgentProvider initialization complete');
    } catch (error: any) {
      this.log(`Warning: Failed to load default config: ${error.message}`);
      this.log('Will use hardcoded defaults instead');
    }
  }

  /**
   * Load default configuration from extension resources
   */
  private async loadDefaultConfig(): Promise<AgentConfig> {
    const defaultConfigPath = path.join(
      this.context.extensionPath,
      'out',
      'conf-agent-core',
      'src',
      'commonMain',
      'resources',
      'default-agent-config.yaml'
    );
    
    return new Promise((resolve, reject) => {
      fs.readFile(defaultConfigPath, 'utf8', (err, data) => {
        if (err) {
          reject(err);
          return;
        }
        
        try {
          const yamlConfig = yaml.load(data) as any;
          const config = this.convertYamlToAgentConfig(yamlConfig);
          resolve(config);
        } catch (error: any) {
          reject(new Error(`Failed to parse default config YAML: ${error.message}`));
        }
      });
    });
  }

  /**
   * Load project-specific CLI configuration from .vision-ai/config/cli.yaml
   * and update VSCode workspace settings
   */
  private async loadProjectCliConfig(): Promise<void> {
    const cliConfigPath = path.join(this.visionAiDir, 'config', 'cli.yaml');
    
    try {
      const data = await fs.promises.readFile(cliConfigPath, 'utf8');
      const yamlConfig = yaml.load(data) as any;
      
      if (yamlConfig?.cli?.path) {
        const cliPath = yamlConfig.cli.path;
        this.log(`CLI path loaded from project config: ${cliPath}`);
        
        // Resolve relative paths to absolute
        const resolvedPath = path.isAbsolute(cliPath) 
          ? cliPath 
          : path.join(this.workspaceRoot, cliPath);
        
        // Update VSCode workspace settings for this project
        const config = vscode.workspace.getConfiguration('i2vision');
        await config.update('cli.path', resolvedPath, vscode.ConfigurationTarget.Workspace);
        this.log(`CLI path set in workspace settings: ${resolvedPath}`);
      } else {
        this.log('No CLI path specified in project config');
      }
      
      if (yamlConfig?.cli?.enabled === false) {
        this.log('CLI disabled in project config');
      }
    } catch (error: any) {
      this.log(`No project CLI config found at ${cliConfigPath} (optional)`);
    }
  }

  /**
   * Create an agent for a specific VSLFC layer
   */
  async createAgent(layer: VslfcLayer): Promise<LocalI2VisionAgent> {
    this.log(`=== Creating agent for layer: ${layer} ===`);
    
    try {
      // Clear cache to ensure fresh config is loaded on each agent creation
      // This ensures any YAML changes are picked up immediately
      this.clearConfigCache();
      this.log(`Config cache cleared`);
      
      // Load configuration (defaults merged with layer overrides)
      this.log(`Loading config for layer: ${layer}`);
      const config = await this.loadConfigForLayer(layer);
      
      this.log(`Config loaded: provider=${config.model.provider}, model=${config.model.id}`);
      
      // Create the agent
      const agent = new LocalI2VisionAgent(layer, config, this.outputChannel, this.context);
      
      this.log(`✅ Created agent: ${agent.id} (${agent.displayName})`);
      this.log(`   Provider: ${config.model.provider}, Model: ${config.model.id}`);
      return agent;
    } catch (error: any) {
      this.log(`❌ Error creating agent: ${error.message}`);
      this.log(`Stack: ${error.stack}`);
      throw error;
    }
  }

  /**
   * Clear the config cache to force reload from disk
   */
  clearConfigCache(): void {
    const size = this.configCache.size;
    this.configCache.clear();
    this.log(`Config cache cleared: ${size} entries removed`);
  }

  /**
   * Load configuration for a specific layer
   * Merges default config with layer-specific overrides
   * Auto-creates config file if it doesn't exist
   */
  private async loadConfigForLayer(layer: VslfcLayer): Promise<AgentConfig> {
    const layerName = layer.toLowerCase();
    const cacheKey = `agent-${layerName}`;
    
    // Check cache first
    const cached = this.configCache.get(cacheKey);
    if (cached) {
      this.log(`Using cached config for ${layerName}`);
      return cached;
    }
    
    // Load layer-specific overrides from YAML file: .vision-ai/{layer}-agent.yaml
    const configPath = path.join(
      this.visionAiDir,
      `${layerName}-agent.yaml`
    );
    
    let layerConfig: AgentConfig | null = null;
    
    try {
      layerConfig = await this.loadYamlConfig(configPath);
      this.log(`Loaded layer config from ${configPath}`);
    } catch (error: any) {
      // Config doesn't exist - auto-create it from defaults
      this.log(`Config not found, auto-creating: ${configPath}`);
      const defaultConfig = this.createDefaultConfig(layer);
      await this.saveConfig(layerName, defaultConfig);
      this.log(`Auto-created config from defaults`);
      layerConfig = defaultConfig;
    }
    
    // Merge configs: layer overrides take precedence
    const finalConfig = layerConfig 
      ? this.mergeConfigs(this.defaultConfig || this.createDefaultConfig(layer), layerConfig)
      : (this.defaultConfig || this.createDefaultConfig(layer));
    
    this.configCache.set(cacheKey, finalConfig);
    return finalConfig;
  }

  /**
   * Merge two configs, with overrides taking precedence
   * Updated to handle context management fields
   */
  private mergeConfigs(defaults: AgentConfig, overrides: AgentConfig): AgentConfig {
    const merged: any = { ...defaults };
    
    // Deep merge nested objects
    if (overrides.model) merged.model = { ...defaults.model, ...overrides.model };
    if (overrides.llm) merged.llm = { ...defaults.llm, ...overrides.llm };
    if (overrides.formattingRules) merged.formattingRules = { ...defaults.formattingRules, ...overrides.formattingRules };
    if (overrides.iterationSettings) merged.iterationSettings = { ...defaults.iterationSettings, ...overrides.iterationSettings };
    if (overrides.toolSelection) merged.toolSelection = { ...defaults.toolSelection, ...overrides.toolSelection };
    if (overrides.safety) merged.safety = { ...defaults.safety, ...overrides.safety };
    if (overrides.parsing) merged.parsing = { ...defaults.parsing, ...overrides.parsing };
    if (overrides.discovery) merged.discovery = { ...defaults.discovery, ...overrides.discovery };
    if (overrides.execution) {
      merged.execution = { 
        ...defaults.execution, 
        ...overrides.execution,
        fileOperations: {
          ...defaults.execution?.fileOperations,
          ...overrides.execution?.fileOperations,
          shell: {
            ...defaults.execution?.fileOperations?.shell,
            ...overrides.execution?.fileOperations?.shell
          }
        },
        // Merge longRunningPatterns - overrides replace defaults
        longRunningPatterns: overrides.execution.longRunningPatterns || defaults.execution?.longRunningPatterns
      };
    }
    if (overrides.formatting) merged.formatting = { ...defaults.formatting, ...overrides.formatting };
    if (overrides.streaming) merged.streaming = { ...defaults.streaming, ...overrides.streaming };
    if (overrides.mcp) merged.mcp = { ...defaults.mcp, ...overrides.mcp };
    
    // ===== CONTEXT MANAGEMENT MERGE =====
    if (overrides.context) {
      merged.context = {
        default: {
          eager: { ...defaults.context?.default.eager, ...overrides.context.default.eager },
          lazy: { ...defaults.context?.default.lazy, ...overrides.context.default.lazy }
        },
        tasks: { ...defaults.context?.tasks, ...overrides.context.tasks }
      };
    } else if (defaults.context) {
      merged.context = defaults.context;
    }
    // ====================================
    
    // Shallow merge for top-level fields
    return { ...merged, ...overrides };
  }

  /**
   * Load configuration from YAML file
   */
  private async loadYamlConfig(configPath: string): Promise<AgentConfig> {
    return new Promise((resolve, reject) => {
      fs.readFile(configPath, 'utf8', (err, data) => {
        if (err) {
          reject(err);
          return;
        }
        
        try {
          const yamlConfig = yaml.load(data) as any;
          const config = this.convertYamlToAgentConfig(yamlConfig);
          resolve(config);
        } catch (error: any) {
          reject(new Error(`Failed to parse YAML: ${error.message}`));
        }
      });
    });
  }

  /**
   * Convert YAML config to AgentConfig
   * Updated to handle context management fields
   */
  private convertYamlToAgentConfig(yamlConfig: any): AgentConfig {
    const config: AgentConfig = {
      key: yamlConfig.key || 'agent',
      agentType: yamlConfig.agentType || 'configurable',
      version: yamlConfig.version || '1.0.0',
      isActive: yamlConfig.isActive ?? true,
      
      // Prompt section
      systemPromptTemplate: yamlConfig.systemPromptTemplate || 'You are an AI assistant.',
      templateVariables: yamlConfig.templateVariables || {},
      ruleSetKeys: yamlConfig.ruleSetKeys,
      parserTemplateName: yamlConfig.parserTemplateName,
      
      // Model section
      model: {
        id: yamlConfig.model?.id || 'minimax-m2.1:cloud',
        provider: yamlConfig.model?.provider || 'ollama',
        contextLength: yamlConfig.model?.contextLength || 32768,
        maxOutputTokens: yamlConfig.model?.maxOutputTokens || 4096,
        temperature: yamlConfig.model?.temperature || 0.7,
        topP: yamlConfig.model?.topP || 0.9
      },
      
      // LLM behavior section
      llm: {
        timeoutSeconds: yamlConfig.llm?.timeoutSeconds || 120,
        modificationTimeoutSeconds: yamlConfig.llm?.modificationTimeoutSeconds || 300,
        finalTurnBonusSeconds: yamlConfig.llm?.finalTurnBonusSeconds || 60,
        maxRetries: yamlConfig.llm?.maxRetries || 3,
        retryBackoffMs: yamlConfig.llm?.retryBackoffMs || [1000, 2000, 4000]
      },
      
      // Formatting rules section
      formattingRules: {
        rules: yamlConfig.formattingRules?.rules || '',
        brief: yamlConfig.formattingRules?.brief || '',
        reasoningHeader: yamlConfig.formattingRules?.reasoningHeader || '## Reasoning',
        toolCallHeader: yamlConfig.formattingRules?.toolCallHeader || '## Tool Calls',
        eosMarker: yamlConfig.formattingRules?.eosMarker || '### END'
      },
      
      // Iteration section
      iterationSettings: {
        maxIterations: yamlConfig.iterationSettings?.maxIterations || 10,
        maxConsecutiveToolCalls: yamlConfig.iterationSettings?.maxConsecutiveToolCalls || 5,
        enableKickstart: yamlConfig.iterationSettings?.enableKickstart ?? false,
        kickstartMinInvalidOutputs: yamlConfig.iterationSettings?.kickstartMinInvalidOutputs || 3
      },
      
      // Tool selection section
      toolSelection: {
        requiredToolsForModification: yamlConfig.toolSelection?.requiredToolsForModification || [],
        defaultRelevanceThreshold: yamlConfig.toolSelection?.defaultRelevanceThreshold || 0.5,
        maxToolsPerTask: yamlConfig.toolSelection?.maxToolsPerTask || 10,
        toolTimeoutSeconds: yamlConfig.toolSelection?.toolTimeoutSeconds || 30
      },
      
      // Safety section
      safety: {
        modificationKeywords: yamlConfig.safety?.modificationKeywords || [],
        listingKeywords: yamlConfig.safety?.listingKeywords || [],
        listingModificationExclusions: yamlConfig.safety?.listingModificationExclusions || [],
        blockGeneratedPaths: yamlConfig.safety?.blockGeneratedPaths || [],
        allowNewFileCreationPatterns: yamlConfig.safety?.allowNewFileCreationPatterns || []
      },
      
      // Parsing section
      parsing: {
        enabledParsers: yamlConfig.parsing?.enabledParsers || [],
        headerPattern: yamlConfig.parsing?.headerPattern || '',
        toolCallPattern: yamlConfig.parsing?.toolCallPattern || '',
        malformedPattern: yamlConfig.parsing?.malformedPattern || '',
        maxResponseSize: yamlConfig.parsing?.maxResponseSize || 10000,
        maxProseChars: yamlConfig.parsing?.maxProseChars || 5000
      },
      
      // Repair strategies section
      repairStrategies: yamlConfig.repairStrategies || [],
      
      // Discovery section
      discovery: {
        maxSearchTerms: yamlConfig.discovery?.maxSearchTerms || 5,
        maxCandidates: yamlConfig.discovery?.maxCandidates || 10,
        frameworkProfiles: yamlConfig.discovery?.frameworkProfiles || {}
      },
      
      // Execution section
      execution: {
        enableBuildVerification: yamlConfig.execution?.enableBuildVerification ?? false,
        buildCommand: yamlConfig.execution?.buildCommand || '',
        buildTimeoutSeconds: yamlConfig.execution?.buildTimeoutSeconds || 60,
        enableSynthesis: yamlConfig.execution?.enableSynthesis ?? false,
        synthesisOnlyForNonModification: yamlConfig.execution?.synthesisOnlyForNonModification ?? false,
        fileOperations: {
          mode: yamlConfig.execution?.fileOperations?.mode || 'shell',
          shell: {
            executable: yamlConfig.execution?.fileOperations?.shell?.executable || 'cmd',
            useNoProfile: yamlConfig.execution?.fileOperations?.shell?.useNoProfile ?? false,
            readFileEnabled: yamlConfig.execution?.fileOperations?.shell?.readFileEnabled ?? true,
            writeFileEnabled: yamlConfig.execution?.fileOperations?.shell?.writeFileEnabled ?? true,
            listDirectoryEnabled: yamlConfig.execution?.fileOperations?.shell?.listDirectoryEnabled ?? true,
            regexSearchEnabled: yamlConfig.execution?.fileOperations?.shell?.regexSearchEnabled ?? true
          }
        },
        longRunningPatterns: yamlConfig.execution?.longRunningPatterns
      },
      
      // Formatting section
      formatting: {
        chunkSize: yamlConfig.formatting?.chunkSize || 1000,
        delayMs: yamlConfig.formatting?.delayMs || 100,
        maxObservationChars: yamlConfig.formatting?.maxObservationChars || 5000
      },
      
      // Streaming section
      streaming: {
        enabled: yamlConfig.streaming?.enabled ?? false,
        methodCandidates: yamlConfig.streaming?.methodCandidates || [],
        fallbackToNonStreaming: yamlConfig.streaming?.fallbackToNonStreaming ?? true,
        fallbackChunkSize: yamlConfig.streaming?.fallbackChunkSize || 500,
        fallbackChunkDelayMs: yamlConfig.streaming?.fallbackChunkDelayMs || 50
      },
      
      // MCP section
      mcp: {
        enabled: yamlConfig.mcp?.enabled ?? false,
        injectClusterContext: yamlConfig.mcp?.injectClusterContext ?? false,
        directCliEnabled: yamlConfig.mcp?.directCliEnabled ?? false,
        allowedToolPrefixes: yamlConfig.mcp?.allowedToolPrefixes || [],
        strictToolNamePolicy: yamlConfig.mcp?.strictToolNamePolicy ?? true
      },

      // ===== CONTEXT MANAGEMENT (NEW) =====
      context: yamlConfig.context ? this.parseContextConfig(yamlConfig.context) : undefined
    };
    
    return config;
  }

  /**
   * Parse context configuration from YAML
   */
  private parseContextConfig(yamlContext: any): { default: ContextProfile; tasks?: Record<string, TaskContextProfile> } {
    const context: { default: ContextProfile; tasks?: Record<string, TaskContextProfile> } = {
      default: {
        eager: {
          currentFile: yamlContext.default?.eager?.currentFile ?? false,
          projectMetadata: yamlContext.default?.eager?.projectMetadata ?? false,
          gitStatus: yamlContext.default?.eager?.gitStatus ?? false,
          gitDiff: yamlContext.default?.eager?.gitDiff ?? false,
          relatedFiles: yamlContext.default?.eager?.relatedFiles ?? false,
          directoryStructure: yamlContext.default?.eager?.directoryStructure ?? false
        },
        lazy: {
          discovery: yamlContext.default?.lazy?.discovery ?? true,
          fullContext: yamlContext.default?.lazy?.fullContext ?? true,
          contractValidation: yamlContext.default?.lazy?.contractValidation ?? true
        }
      }
    };

    // Parse task-specific overrides
    if (yamlContext.tasks) {
      context.tasks = {};
      for (const [taskName, taskConfig] of Object.entries(yamlContext.tasks)) {
        const task: TaskContextProfile = {};
        const taskData = taskConfig as any;
        
        if (taskData.eager) {
          task.eager = {
            currentFile: taskData.eager.currentFile,
            projectMetadata: taskData.eager.projectMetadata,
            gitStatus: taskData.eager.gitStatus,
            gitDiff: taskData.eager.gitDiff,
            relatedFiles: taskData.eager.relatedFiles,
            directoryStructure: taskData.eager.directoryStructure
          };
        }
        
        if (taskData.lazy) {
          task.lazy = {
            discovery: taskData.lazy.discovery,
            fullContext: taskData.lazy.fullContext,
            contractValidation: taskData.lazy.contractValidation
          };
        }
        
        context.tasks[taskName] = task;
      }
    }

    return context;
  }

  /**
   * Create default configuration for a layer
   */
  private createDefaultConfig(layer: string): AgentConfig {
    return {
      key: `${layer}-agent`,
      agentType: 'configurable',
      version: '1.0.0',
      isActive: true,
      systemPromptTemplate: 'You are an AI assistant.',
      templateVariables: {},
      model: {
        id: 'minimax-m2.1:cloud',
        provider: 'ollama',
        contextLength: 32768,
        maxOutputTokens: 4096,
        temperature: 0.7,
        topP: 0.9
      },
      llm: {
        timeoutSeconds: 120,
        modificationTimeoutSeconds: 300,
        finalTurnBonusSeconds: 60,
        maxRetries: 3,
        retryBackoffMs: [1000, 2000, 4000]
      },
      formattingRules: {
        rules: '',
        brief: '',
        reasoningHeader: '## Reasoning',
        toolCallHeader: '## Tool Calls',
        eosMarker: '### END'
      },
      iterationSettings: {
        maxIterations: 10,
        maxConsecutiveToolCalls: 5,
        enableKickstart: false,
        kickstartMinInvalidOutputs: 3
      },
      toolSelection: {
        requiredToolsForModification: [],
        defaultRelevanceThreshold: 0.5,
        maxToolsPerTask: 10,
        toolTimeoutSeconds: 30
      },
      safety: {
        modificationKeywords: [],
        listingKeywords: [],
        listingModificationExclusions: [],
        blockGeneratedPaths: [],
        allowNewFileCreationPatterns: []
      },
      parsing: {
        enabledParsers: [],
        headerPattern: '',
        toolCallPattern: '',
        malformedPattern: '',
        maxResponseSize: 10000,
        maxProseChars: 5000
      },
      repairStrategies: [],
      discovery: {
        maxSearchTerms: 5,
        maxCandidates: 10,
        frameworkProfiles: {}
      },
      execution: {
        enableBuildVerification: false,
        buildCommand: '',
        buildTimeoutSeconds: 60,
        enableSynthesis: false,
        synthesisOnlyForNonModification: false,
        fileOperations: {
          mode: 'shell',
          shell: {
            executable: 'cmd',
            useNoProfile: false,
            readFileEnabled: true,
            writeFileEnabled: true,
            listDirectoryEnabled: true,
            regexSearchEnabled: true
          }
        }
      },
      formatting: {
        chunkSize: 1000,
        delayMs: 100,
        maxObservationChars: 5000
      },
      streaming: {
        enabled: false,
        methodCandidates: [],
        fallbackToNonStreaming: true,
        fallbackChunkSize: 500,
        fallbackChunkDelayMs: 50
      },
      mcp: {
        enabled: false,
        injectClusterContext: false,
        directCliEnabled: false,
        allowedToolPrefixes: [],
        strictToolNamePolicy: true
      },
      // Context management disabled by default - enable in YAML config
      context: undefined
    };
  }

  /**
   * Save configuration to YAML file
   * Used for auto-creation and manual updates
   * Updated to include context management fields
   */
  private async saveConfig(layerName: string, config: AgentConfig): Promise<void> {
    const configPath = path.join(this.visionAiDir, `${layerName}-agent.yaml`);
    
    // Ensure .vision-ai directory exists
    await fs.promises.mkdir(this.visionAiDir, { recursive: true });
    
    // Convert AgentConfig to YAML-friendly format
    const yamlConfig: any = {
      key: config.key,
      agentType: config.agentType,
      version: config.version,
      isActive: config.isActive,
      systemPromptTemplate: config.systemPromptTemplate,
      templateVariables: config.templateVariables,
      model: { ...config.model },
      llm: { ...config.llm },
      formattingRules: { ...config.formattingRules },
      iterationSettings: { ...config.iterationSettings },
      toolSelection: { ...config.toolSelection },
      safety: { ...config.safety },
      parsing: { ...config.parsing },
      discovery: { ...config.discovery },
      execution: { 
        ...config.execution,
        longRunningPatterns: config.execution?.longRunningPatterns
      },
      formatting: { ...config.formatting },
      streaming: { ...config.streaming },
      mcp: { ...config.mcp }
    };

    // Add context management if configured
    if (config.context) {
      yamlConfig.context = {
        default: {
          eager: config.context.default.eager,
          lazy: config.context.default.lazy
        },
        tasks: config.context.tasks
      };
    }
    
    const yamlContent = yaml.dump(yamlConfig, {
      indent: 2,
      lineWidth: -1, // Don't wrap lines
      noRefs: true   // Don't use YAML anchors
    });
    
    await fs.promises.writeFile(configPath, yamlContent, 'utf8');
    this.log(`Saved config to ${configPath}`);
  }

  /**
   * Get configuration for a layer (for UI display)
   */
  getConfig(layer: string): AgentConfig {
    const layerName = layer.toLowerCase();
    const cacheKey = `agent-` + layerName;
    const cached = this.configCache.get(cacheKey);
    if (cached) {
      return cached;
    }
    return this.defaultConfig || this.createDefaultConfig(layerName);
  }

  /**
   * Update configuration for a layer (provider/model changes)
   */
  async updateConfig(layer: string, updates: { provider?: string; model?: string }): Promise<void> {
    const layerName = layer.toLowerCase();
    const cacheKey = `agent-${layerName}`;
    
    // Get current config
    const config = this.getConfig(layer);
    
    // Apply updates
    if (updates.provider) {
      config.model.provider = updates.provider;
    }
    if (updates.model) {
      config.model.id = updates.model;
    }
    
    // Update cache
    this.configCache.set(cacheKey, config);
    
    // Save to YAML file
    await this.saveConfig(layerName, config);
    
    this.log(`Updated config for ${layerName}: provider=${config.model.provider}, model=${config.model.id}`);
  }

  /**
   * Dispose resources
   */
  async dispose(): Promise<void> {
    this.log(`Disposing LocalAgentProvider...`);
    this.clearConfigCache();
    this.log(`LocalAgentProvider disposed`);
  }

  /**
   * Log a message to the output channel
   */
  private log(message: string): void {
    this.outputChannel.appendLine(`[LocalAgentProvider] ` + message);
  }
}

