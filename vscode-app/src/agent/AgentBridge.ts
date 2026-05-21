/**
 * AgentBridge - Bridge between VSCode extension and conf-agent-core
 * 
 * This class wraps the agent core functionality and provides a clean API
 * for the AgentTabManager to interact with configured agents.
 */

import * as vscode from 'vscode';
import { I2VisionCLI } from '../cliIntegration';

/**
 * Agent configuration interface (matches YAML structure)
 */
export interface AgentConfig {
  key: string;
  agentType: string;
  version: string;
  isActive: boolean;
  
  // Prompt section
  systemPromptTemplate: string;
  templateVariables: Record<string, string>;
  ruleSetKeys?: string[];
  parserTemplateName?: string;
  
  // Model section
  model: {
    id: string;
    provider: string;
    contextLength: number;
    maxOutputTokens: number;
    temperature: number;
    topP: number;
  };
  
  // LLM behavior section
  llm: {
    timeoutSeconds: number;
    modificationTimeoutSeconds: number;
    finalTurnBonusSeconds: number;
    maxRetries: number;
    retryBackoffMs: number[];
  };
  
  // Formatting rules section
  formattingRules: {
    rules: string;
    brief: string;
    reasoningHeader: string;
    toolCallHeader: string;
    eosMarker: string;
  };
  
  // Iteration section
  iterationSettings: {
    maxIterations: number;
    maxConsecutiveToolCalls: number;
    enableKickstart: boolean;
    kickstartMinInvalidOutputs: number;
  };
  
  // Tool selection section
  toolSelection: {
    requiredToolsForModification: string[];
    defaultRelevanceThreshold: number;
    maxToolsPerTask: number;
    toolTimeoutSeconds: number;
  };
  
  // Safety section
  safety: {
    modificationKeywords: string[];
    listingKeywords: string[];
    listingModificationExclusions: string[];
    blockGeneratedPaths: string[];
    allowNewFileCreationPatterns: string[];
  };
  
  // Parsing section
  parsing: {
    enabledParsers: string[];
    headerPattern: string;
    toolCallPattern: string;
    malformedPattern: string;
    maxResponseSize: number;
    maxProseChars: number;
  };
  
  // Repair strategies section
  repairStrategies: any[];
  
  // Discovery section
  discovery: {
    maxSearchTerms: number;
    maxCandidates: number;
    frameworkProfiles: Record<string, any>;
  };
  
  // Execution section
  execution: {
    enableBuildVerification: boolean;
    buildCommand: string;
    buildTimeoutSeconds: number;
    enableSynthesis: boolean;
    synthesisOnlyForNonModification: boolean;
    fileOperations: {
      mode: string;
      shell: {
        executable: string;
        useNoProfile: boolean;
        readFileEnabled: boolean;
        writeFileEnabled: boolean;
        listDirectoryEnabled: boolean;
        regexSearchEnabled: boolean;
      };
    };
  };
  
  // Formatting section
  formatting: {
    chunkSize: number;
    delayMs: number;
    maxObservationChars: number;
  };
  
  // Streaming section
  streaming: {
    enabled: boolean;
    methodCandidates: string[];
    fallbackToNonStreaming: boolean;
    fallbackChunkSize: number;
    fallbackChunkDelayMs: number;
  };
  
  // MCP section
  mcp: {
    enabled: boolean;
    injectClusterContext: boolean;
    directCliEnabled: boolean;
    allowedToolPrefixes: string[];
    strictToolNamePolicy: boolean;
  };
}

/**
 * Tool call record from agent response
 */
export interface ToolCall {
  toolName: string;
  args: Record<string, any>;
  result?: string;
  error?: string;
}

/**
 * Interaction record for history tracking
 */
export interface InteractionRecord {
  timestamp: number;
  userInput: string;
  agentResponse: string;
  toolCalls: string[];
  iterations: number;
  durationMs: number;
}

/**
 * Agent response after processing
 */
export interface AgentResponse {
  finalText: string;
  toolCalls: ToolCall[];
  iterations: number;
  durationMs: number;
  success: boolean;
  error?: string;
}

/**
 * Context for agent processing
 */
export interface ProcessContext {
  currentFile?: string;
  projectName?: string;
  task?: string;
}

/**
 * AgentBridge - Manages agent lifecycle and communication
 */
export class AgentBridge {
  private config: AgentConfig;
  private cli: I2VisionCLI;
  private isInitialized: boolean = false;
  private outputChannel?: vscode.OutputChannel;

  constructor(config: AgentConfig, outputChannel?: vscode.OutputChannel) {
    this.config = config;
    this.outputChannel = outputChannel;
    
    // Initialize CLI integration
    const workspaceRoot = vscode.workspace.workspaceFolders?.[0]?.uri.fsPath || '';
    this.cli = new I2VisionCLI(workspaceRoot, outputChannel);
  }

  /**
   * Initialize the agent bridge
   */
  async initialize(): Promise<void> {
    if (this.isInitialized) {
      return;
    }

    this.log(`Initializing agent: ${this.config.key} (type: ${this.config.agentType})`);
    this.log(`Model: ${this.config.model.id} (${this.config.model.provider})`);
    this.log(`Max iterations: ${this.config.iterationSettings.maxIterations}`);
    
    // Verify model is available
    try {
      const models = await this.cli.getLoadedModels();
      const isModelLoaded = models.some(m => 
        m.name === this.config.model.id || m.name.includes(this.config.model.id.split(':')[0])
      );
      
      if (!isModelLoaded) {
        this.log(`Warning: Model ${this.config.model.id} may not be loaded`);
      }
    } catch (error) {
      this.log(`Warning: Could not verify model availability: ${error}`);
    }
    
    this.isInitialized = true;
    this.log(`Agent ${this.config.key} initialized successfully`);
  }

  /**
   * Process user input through the agent
   */
  async process(userInput: string, context?: ProcessContext): Promise<AgentResponse> {
    if (!this.isInitialized) {
      await this.initialize();
    }

    const startTime = Date.now();
    this.log(`Processing input: "${userInput.substring(0, 50)}..."`);

    try {
      // Prepare template variables
      const templateVars = {
        ...this.config.templateVariables,
        currentFile: context?.currentFile || this.config.templateVariables.currentFile,
        task: userInput
      };

      // Build the system prompt
      const systemPrompt = this.buildSystemPrompt(templateVars);
      
      this.log(`System prompt built (${systemPrompt.length} chars)`);

      // Execute agent loop using CLI
      const response = await this.executeAgentLoop(userInput, systemPrompt, context);
      
      const durationMs = Date.now() - startTime;
      this.log(`Agent completed in ${durationMs}ms with ${response.iterations} iterations`);
      
      return {
        ...response,
        durationMs,
        success: true
      };
    } catch (error: any) {
      const durationMs = Date.now() - startTime;
      this.log(`Agent error: ${error.message}`);
      
      return {
        finalText: `Error: ${error.message}`,
        toolCalls: [],
        iterations: 0,
        durationMs,
        success: false,
        error: error.message
      };
    }
  }

  /**
   * Build system prompt from template
   */
  private buildSystemPrompt(variables: Record<string, string>): string {
    let prompt = this.config.systemPromptTemplate;
    
    for (const [key, value] of Object.entries(variables)) {
      prompt = prompt.replace(new RegExp(`\\$\\{${key}\\}`, 'g'), value);
    }
    
    return prompt;
  }

  /**
   * Execute the agent loop (simplified version for initial testing)
   * In production, this would integrate with conf-agent-core's BaseConfigurableAgent
   */
  private async executeAgentLoop(
    userInput: string,
    systemPrompt: string,
    context?: ProcessContext
  ): Promise<Omit<AgentResponse, 'durationMs' | 'success'>> {
    const toolCalls: ToolCall[] = [];
    let iterations = 0;
    let finalText = '';

    // For initial testing, we'll use a simplified approach:
    // 1. Get context from i2vision
    // 2. Call LLM with system prompt + user input
    // 3. Parse response for tool calls
    // 4. Execute tools and repeat if needed

    try {
      // Step 1: Get project context
      this.log('Getting project context...');
      const projectContext = await this.cli.getDiscovery();
      
      // Step 2: Build messages for LLM
      const messages = [
        { role: 'system', content: systemPrompt },
        { role: 'user', content: userInput }
      ];

      // Step 3: Call LLM
      this.log(`Calling model: ${this.config.model.id}`);
      const llmResponse = await this.callLLM(messages);
      
      // Step 4: Parse response for tool calls
      const parsed = this.parseLLMResponse(llmResponse);
      
      if (parsed.toolCalls.length > 0) {
        this.log(`Found ${parsed.toolCalls.length} tool calls`);
        
        // Execute tool calls
        for (const toolCall of parsed.toolCalls) {
          try {
            this.log(`Executing tool: ${toolCall.toolName}`);
            const result = await this.executeTool(toolCall);
            toolCall.result = result;
            toolCalls.push(toolCall);
          } catch (error: any) {
            toolCall.error = error.message;
            toolCalls.push(toolCall);
          }
        }
        
        finalText = parsed.reasoning + '\n\nTool results:\n' + 
          toolCalls.map(tc => `- ${tc.toolName}: ${tc.result || tc.error}`).join('\n');
      } else {
        finalText = parsed.reasoning;
      }
      
      iterations = 1;
    } catch (error: any) {
      finalText = `Error during agent loop: ${error.message}`;
      iterations = 0;
    }

    return {
      finalText,
      toolCalls,
      iterations
    };
  }

  /**
   * Call LLM through CLI
   */
  private async callLLM(messages: Array<{role: string, content: string}>): Promise<string> {
    // Use the CLI to call the LLM
    const response = await this.cli.callLLM(
      this.config.model.id,
      messages,
      {
        temperature: this.config.model.temperature,
        top_p: this.config.model.topP,
        max_tokens: this.config.model.maxOutputTokens
      }
    );
    
    return response;
  }

  /**
   * Parse LLM response for reasoning and tool calls
   */
  private parseLLMResponse(response: string): { reasoning: string, toolCalls: ToolCall[] } {
    const toolCalls: ToolCall[] = [];
    let reasoning = response;
    
    // Try to extract tool calls using the configured pattern
    const toolCallPattern = new RegExp(this.config.parsing.toolCallPattern, 'g');
    let match;
    
    while ((match = toolCallPattern.exec(response)) !== null) {
      try {
        // Extract JSON from the match
        const jsonStart = response.indexOf('{', match.index);
        const jsonEnd = response.lastIndexOf('}', match.index + match[0].length);
        
        if (jsonStart !== -1 && jsonEnd !== -1) {
          const jsonStr = response.substring(jsonStart, jsonEnd + 1);
          const parsed = JSON.parse(jsonStr);
          
          toolCalls.push({
            toolName: parsed.tool,
            args: parsed.args || {}
          });
        }
      } catch (error) {
        this.log(`Failed to parse tool call: ${error}`);
      }
    }
    
    // Extract reasoning (everything before first tool_call or after last tool_call)
    const toolCallIndex = response.indexOf(this.config.formattingRules.toolCallHeader);
    if (toolCallIndex !== -1) {
      reasoning = response.substring(0, toolCallIndex).trim();
    }
    
    return { reasoning, toolCalls };
  }

  /**
   * Execute a tool call
   */
  private async executeTool(toolCall: ToolCall): Promise<string> {
    const { toolName, args } = toolCall;
    
    // Map tool names to CLI methods
    switch (toolName) {
      case 'read_file':
        return await this.cli.readFile(args.path);
      
      case 'write_file':
        return await this.cli.writeFile(args.path, args.content);
      
      case 'edit_file':
        return await this.cli.editFile(args.path, args.old_string, args.new_string);
      
      case 'list_directory':
        return await this.cli.listDirectory(args.path);
      
      case 'regex_search':
        return await this.cli.regexSearch(args.pattern, args.path);
      
      case 'i2vision_get_context':
        return JSON.stringify(await this.cli.getDiscovery());
      
      case 'i2vision_discover':
        return JSON.stringify(await this.cli.getDiscovery());
      
      default:
        throw new Error(`Unknown tool: ${toolName}`);
    }
  }

  /**
   * Dispose resources
   */
  dispose(): void {
    this.log(`Disposing agent: ${this.config.key}`);
    this.isInitialized = false;
  }

  /**
   * Log a message
   */
  private log(message: string): void {
    if (this.outputChannel) {
      this.outputChannel.appendLine(`[Agent:${this.config.key}] ${message}`);
    }
  }

  /**
   * Get agent configuration
   */
  getConfig(): AgentConfig {
    return this.config;
  }
}
