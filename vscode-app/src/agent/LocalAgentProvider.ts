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
 * Features:
 * - Loads YAML configurations from .vscode/i2vision/agents/
 * - Creates LocalI2VisionAgent instances with proper config
 * - Manages config caching and reloading
 * - Provides default configs if YAML files are missing
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
  private workspaceRoot: string;

  constructor(
    context: vscode.ExtensionContext,
    outputChannel: vscode.OutputChannel
  ) {
    this.context = context;
    this.outputChannel = outputChannel;
    this.workspaceRoot = vscode.workspace.workspaceFolders?.[0]?.uri.fsPath || '';
    
    this.log('LocalAgentProvider initialized');
  }

  /**
   * Initialize the provider
   */
  async initialize(): Promise<void> {
    this.log('LocalAgentProvider initialization complete');
  }

  /**
   * Create an agent for a specific VSLFC layer
   */
  async createAgent(layer: VslfcLayer): Promise<LocalI2VisionAgent> {
    this.log(`Creating agent for layer: ${layer}`);
    
    try {
      // Load configuration
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
   * Load configuration for a specific layer
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
    
    // Try to load from YAML file
    const configPath = path.join(
      this.workspaceRoot,
      '.vscode', 'i2vision', 'agents', `${layerName}-agent.yaml`
    );
    
    try {
      const config = await this.loadYamlConfig(configPath);
      this.configCache.set(cacheKey, config);
      this.log(`Loaded config from ${configPath}`);
      return config;
    } catch (error: any) {
      this.log(`Config file not found, using defaults: ${error.message}`);
      
      // Use default configuration
      const config = this.createDefaultConfig(layer);
      this.configCache.set(cacheKey, config);
      return config;
    }
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
      systemPromptTemplate: yamlConfig.prompt?.systemPromptTemplate || 'You are an AI assistant.',
      templateVariables: yamlConfig.prompt?.templateVariables || {},
      ruleSetKeys: yamlConfig.prompt?.ruleSetKeys,
      parserTemplateName: yamlConfig.prompt?.parserTemplateName,
      
      // Model section
      model: {
        id: yamlConfig.model?.id || 'qwen2.5-coder:32b',
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
        methodCandidates: yamlConfig.streaming?.methodCandidates || ['sse', 'websocket'],
        fallbackToNonStreaming: yamlConfig.streaming?.fallbackToNonStreaming ?? true,
        fallbackChunkSize: yamlConfig.streaming?.fallbackChunkSize || 100,
        fallbackChunkDelayMs: yamlConfig.streaming?.fallbackChunkDelayMs || 50
      },
      
      // MCP section
      mcp: {
        enabled: yamlConfig.mcp?.enabled ?? false,
        injectClusterContext: yamlConfig.mcp?.injectClusterContext ?? false,
        directCliEnabled: yamlConfig.mcp?.directCliEnabled ?? true,
        allowedToolPrefixes: yamlConfig.mcp?.allowedToolPrefixes || [],
        strictToolNamePolicy: yamlConfig.mcp?.strictToolNamePolicy ?? false
      }
    };
    
    return config;
  }

  /**
   * Create default configuration for a layer
   */
  private createDefaultConfig(layer: VslfcLayer): AgentConfig {
    const config: AgentConfig = {
      key: `agent-${layer.toLowerCase()}`,
      agentType: 'configurable',
      version: '1.0.0',
      isActive: true,
      
      // Prompt section
      systemPromptTemplate: 'You are an AI assistant specialized in ${layer} layer tasks.',
      templateVariables: {
        layer: layer,
        currentFile: '',
        task: ''
      },
      
      // Model section
      model: {
        id: 'qwen2.5-coder:32b',
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
        methodCandidates: ['sse', 'websocket'],
        fallbackToNonStreaming: true,
        fallbackChunkSize: 100,
        fallbackChunkDelayMs: 50
      },
      
      // MCP section
      mcp: {
        enabled: false,
        injectClusterContext: false,
        directCliEnabled: true,
        allowedToolPrefixes: [],
        strictToolNamePolicy: false
      }
    };
    
    this.log(`Created default config for ${layer}`);
    return config;
  }

  /**
   * Reload configuration from disk
   */
  async reloadConfig(layer: VslfcLayer): Promise<AgentConfig> {
    const layerName = layer.toLowerCase();
    const cacheKey = `agent-${layerName}`;
    
    // Remove from cache
    this.configCache.delete(cacheKey);
    
    // Reload
    const config = await this.loadConfigForLayer(layer);
    this.log(`Reloaded config for ${layerName}`);
    return config;
  }

  /**
   * Get all available layer configurations
   */
  async getAvailableLayers(): Promise<VslfcLayer[]> {
    const layers: VslfcLayer[] = [];
    const agentsDir = path.join(this.workspaceRoot, '.vscode', 'i2vision', 'agents');
    
    try {
      const files = fs.readdirSync(agentsDir);
      
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
      this.log(`Error reading agents directory: ${error.message}`);
    }
    
    // If no config files found, return all layers
    if (layers.length === 0) {
      return Object.values(VslfcLayer);
    }
    
    return layers;
  }

  /**
   * Log a message
   */
  private log(message: string): void {
    this.outputChannel.appendLine(`[LocalAgentProvider] ${message}`);
  }

  /**
   * Dispose of provider resources
   */
  dispose(): void {
    this.log('Disposing LocalAgentProvider');
    this.configCache.clear();
  }
}
