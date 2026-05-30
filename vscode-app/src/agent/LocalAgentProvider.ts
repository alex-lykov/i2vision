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
 */

import * as vscode from 'vscode';
import * as path from 'path';
import * as fs from 'fs';
import * as yaml from 'js-yaml';
import { LocalI2VisionAgent, VslfcLayer } from './LocalI2VisionAgent';
import { AgentConfig } from './AgentBridge';

/**
 * LocalAgentProvider - Creates and manages LocalI2VisionAgent instances
 */
export class LocalAgentProvider {
  private configCache: Map<string, AgentConfig> = new Map();
  private outputChannel: vscode.OutputChannel;
  private context: vscode.ExtensionContext;
  private visionAiDir: string;
  private defaultConfig: AgentConfig | null = null;

  constructor(
    context: vscode.ExtensionContext,
    outputChannel: vscode.OutputChannel
  ) {
    this.context = context;
    this.outputChannel = outputChannel;
    // Load agent configs from workspace's .vision-ai directory
    // Config path: {workspace}/.vision-ai/{layer}-agent.yaml
    this.visionAiDir = path.join(
      vscode.workspace.workspaceFolders?.[0]?.uri.fsPath || '',
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
   * Create an agent for a specific VSLFC layer
   */
  async createAgent(layer: VslfcLayer): Promise<LocalI2VisionAgent> {
    this.log(`Creating agent for layer: ${layer}`);
    
    try {
      // Clear cache to ensure fresh config is loaded on each agent creation
      // This ensures any YAML changes are picked up immediately
      this.clearConfigCache();
      
      // Load configuration (defaults merged with layer overrides)
      const config = await this.loadConfigForLayer(layer);
      
      // Create the agent
      const agent = new LocalI2VisionAgent(layer, config, this.outputChannel);
      
      this.log(`Created agent: ${agent.id} (${agent.displayName})`);
      return agent;
    } catch (error: any) {
      this.log(`Error creating agent: ${error.message}`);
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
      this.log(`Layer config not found at ${configPath}, using defaults only`);
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
        }
      };
    }
    if (overrides.formatting) merged.formatting = { ...defaults.formatting, ...overrides.formatting };
    if (overrides.streaming) merged.streaming = { ...defaults.streaming, ...overrides.streaming };
    if (overrides.mcp) merged.mcp = { ...defaults.mcp, ...overrides.mcp };
    
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
        id: yamlConfig.model?.id || 'qwen3:4b',
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
        toolTimeoutSeconds: yamlConfig.toolSelection?.toolTimeoutSeconds || 60
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
        enabledParsers: yamlConfig.parsing?.enabledParsers || ['tool-call'],
        headerPattern: yamlConfig.parsing?.headerPattern || '',
        toolCallPattern: yamlConfig.parsing?.toolCallPattern || '',
        malformedPattern: yamlConfig.parsing?.malformedPattern || '',
        maxResponseSize: yamlConfig.parsing?.maxResponseSize || 32768,
        maxProseChars: yamlConfig.parsing?.maxProseChars || 16384
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
        enableBuildVerification: yamlConfig.execution?.enableBuildVerification ?? true,
        buildCommand: yamlConfig.execution?.buildCommand || './gradlew build',
        buildTimeoutSeconds: yamlConfig.execution?.buildTimeoutSeconds || 120,
        enableSynthesis: yamlConfig.execution?.enableSynthesis ?? true,
        synthesisOnlyForNonModification: yamlConfig.execution?.synthesisOnlyForNonModification ?? false,
        fileOperations: {
          mode: yamlConfig.execution?.fileOperations?.mode || 'shell',
          shell: {
            executable: yamlConfig.execution?.fileOperations?.shell?.executable || 'bash',
            useNoProfile: yamlConfig.execution?.fileOperations?.shell?.useNoProfile ?? true,
            readFileEnabled: yamlConfig.execution?.fileOperations?.shell?.readFileEnabled ?? true,
            writeFileEnabled: yamlConfig.execution?.fileOperations?.shell?.writeFileEnabled ?? true,
            listDirectoryEnabled: yamlConfig.execution?.fileOperations?.shell?.listDirectoryEnabled ?? true,
            regexSearchEnabled: yamlConfig.execution?.fileOperations?.shell?.regexSearchEnabled ?? true
          }
        }
      },
      
      // Formatting section
      formatting: {
        chunkSize: yamlConfig.formatting?.chunkSize || 100,
        delayMs: yamlConfig.formatting?.delayMs || 50,
        maxObservationChars: yamlConfig.formatting?.maxObservationChars || 8192
      },
      
      // Streaming section
      streaming: {
        enabled: yamlConfig.streaming?.enabled ?? true,
        methodCandidates: yamlConfig.streaming?.methodCandidates || [],
        fallbackToNonStreaming: yamlConfig.streaming?.fallbackToNonStreaming ?? true,
        fallbackChunkSize: yamlConfig.streaming?.fallbackChunkSize || 200,
        fallbackChunkDelayMs: yamlConfig.streaming?.fallbackChunkDelayMs || 50
      },
      
      // MCP section
      mcp: {
        enabled: yamlConfig.mcp?.enabled ?? true,
        injectClusterContext: yamlConfig.mcp?.injectClusterContext ?? true,
        directCliEnabled: yamlConfig.mcp?.directCliEnabled ?? false,
        allowedToolPrefixes: yamlConfig.mcp?.allowedToolPrefixes || [],
        strictToolNamePolicy: yamlConfig.mcp?.strictToolNamePolicy ?? true
      }
    };
    
    return config;
  }

  /**
   * Create default configuration for a layer (fallback if resource file not found)
   */
  private createDefaultConfig(layer: VslfcLayer): AgentConfig {
    const layerName = layer.toLowerCase();
    
    return {
      key: 'agent',
      agentType: 'configurable',
      version: '1.0.0',
      isActive: true,
      
      // Prompt section
      systemPromptTemplate: `You are the ${layerName} layer agent for the VSLFC architecture.
Your role is to analyze and generate artifacts for the ${layerName} layer.

Guidelines:
- Maintain consistency with other layers
- Follow VSLFC contract specifications
- Generate clear, maintainable artifacts`,
      templateVariables: {},
      ruleSetKeys: undefined,
      parserTemplateName: undefined,
      
      // Model section
      model: {
        id: 'qwen3:4b',
        provider: 'ollama',
        contextLength: 32768,
        maxOutputTokens: 4096,
        temperature: 0.7,
        topP: 0.9
      },
      
      // LLM behavior section
      llm: {
        timeoutSeconds: 120,
        modificationTimeoutSeconds: 300,
        finalTurnBonusSeconds: 60,
        maxRetries: 3,
        retryBackoffMs: [1000, 2000, 4000]
      },
      
      // Formatting rules section
      formattingRules: {
        rules: '',
        brief: '',
        reasoningHeader: '## Reasoning',
        toolCallHeader: '## Tool Calls',
        eosMarker: '### END'
      },
      
      // Iteration section
      iterationSettings: {
        maxIterations: 10,
        maxConsecutiveToolCalls: 5,
        enableKickstart: false,
        kickstartMinInvalidOutputs: 3
      },
      
      // Tool selection section
      toolSelection: {
        requiredToolsForModification: [],
        defaultRelevanceThreshold: 0.5,
        maxToolsPerTask: 10,
        toolTimeoutSeconds: 60
      },
      
      // Safety section
      safety: {
        modificationKeywords: [],
        listingKeywords: [],
        listingModificationExclusions: [],
        blockGeneratedPaths: [],
        allowNewFileCreationPatterns: []
      },
      
      // Parsing section
      parsing: {
        enabledParsers: ['tool-call'],
        headerPattern: '',
        toolCallPattern: '',
        malformedPattern: '',
        maxResponseSize: 32768,
        maxProseChars: 16384
      },
      
      // Repair strategies section
      repairStrategies: [],
      
      // Discovery section
      discovery: {
        maxSearchTerms: 5,
        maxCandidates: 10,
        frameworkProfiles: {}
      },
      
      // Execution section
      execution: {
        enableBuildVerification: true,
        buildCommand: './gradlew build',
        buildTimeoutSeconds: 120,
        enableSynthesis: true,
        synthesisOnlyForNonModification: false,
        fileOperations: {
          mode: 'shell',
          shell: {
            executable: 'bash',
            useNoProfile: true,
            readFileEnabled: true,
            writeFileEnabled: true,
            listDirectoryEnabled: true,
            regexSearchEnabled: true
          }
        }
      },
      
      // Formatting section
      formatting: {
        chunkSize: 100,
        delayMs: 50,
        maxObservationChars: 8192
      },
      
      // Streaming section
      streaming: {
        enabled: true,
        methodCandidates: [],
        fallbackToNonStreaming: true,
        fallbackChunkSize: 200,
        fallbackChunkDelayMs: 50
      },
      
      // MCP section
      mcp: {
        enabled: true,
        injectClusterContext: true,
        directCliEnabled: false,
        allowedToolPrefixes: [],
        strictToolNamePolicy: true
      }
    };
  }

  /**
   * Get all available layer configurations
   */
  async getAvailableLayers(): Promise<VslfcLayer[]> {
    const layers: VslfcLayer[] = [];
    const visionAiDir = this.visionAiDir;
    
    try {
      const files = fs.readdirSync(visionAiDir);
      
      for (const file of files) {
        if (file.endsWith('-agent.yaml')) {
          const layerName = file.replace('-agent.yaml', '').toUpperCase();
          try {
            const layer = VslfcLayer[layerName as keyof typeof VslfcLayer];
            if (layer) {
              layers.push(layer);
            }
          } catch {
            // Ignore invalid layer names
          }
        }
      }
    } catch (error: any) {
      this.log(`Error reading .vision-ai directory: ${error.message}`);
    }
    
    // If no config files found, return all layers
    if (layers.length === 0) {
      return Object.values(VslfcLayer);
    }
    
    return layers;
  }

  /**
   * Log a message to the output channel
   */
  private log(message: string): void {
    const timestamp = new Date().toISOString();
    this.outputChannel.appendLine(`[${timestamp}] [LocalAgentProvider] ${message}`);
  }
}
