/**
 * AgentBridge - Bridge between VSCode extension and conf-agent-core
 * 
 * This class wraps the agent core functionality and provides a clean API
 * for the AgentTabManager to interact with configured agents.
 * 
 * UPDATED: Fixed tool call parsing, improved logging, better error handling
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
    
    // Append strong formatting instructions with examples
    prompt += '\n\n--- OUTPUT FORMAT (STRICT) ---';
    prompt += '\nYou MUST use this exact format:';
    prompt += '\n\nExample 1 (with tool):';
    prompt += '\nreasoning: I need to read the file to understand its contents.';
    prompt += '\ntool_call: {"tool":"read_file","args":{"path":"vscode-app/src/extension.ts"}}';
    prompt += '\nEOS';
    prompt += '\n\nExample 2 (no tool needed):';
    prompt += '\nreasoning: The answer is 42.';
    prompt += '\nEOS';
    prompt += '\n\nIMPORTANT: Always start with "reasoning:" and use "tool_call:" if you need to use a tool.';
    
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
    // 2. Call LLM with system prompt + user input + tools
    // 3. Parse response for tool calls
    // 4. Execute tools and repeat if needed

    try {
      // Step 1: Get project context
      this.log('Getting project context...');
      const projectContext = await this.cli.runDiscovery();
      this.log(`Project context retrieved: ${JSON.stringify(projectContext).substring(0, 100)}...`);
      
      // Step 2: Build messages for LLM
      const messages = [
        { role: 'system', content: systemPrompt },
        { role: 'user', content: userInput }
      ];

      // Step 3: Call LLM with tools
      this.log(`Calling model: ${this.config.model.id}`);
      const tools = this.getAvailableTools();
      this.log(`Passing ${tools.length} tools to LLM`);
      
      const llmResponse = await this.callLLM(messages, tools);
      this.log(`LLM response received (${llmResponse.length} chars): ${llmResponse.substring(0, 200)}...`);
      
      // Step 4: Parse response for tool calls
      const parsed = this.parseLLMResponse(llmResponse);
      this.log(`Parsed response: ${parsed.toolCalls.length} tool calls found`);
      
      if (parsed.toolCalls.length > 0) {
        this.log(`Found ${parsed.toolCalls.length} tool calls`);
        
        // Execute tool calls
        for (const toolCall of parsed.toolCalls) {
          try {
            this.log(`Executing tool: ${toolCall.toolName} with args: ${JSON.stringify(toolCall.args)}`);
            const result = await this.executeTool(toolCall);
            toolCall.result = result;
            toolCalls.push(toolCall);
            this.log(`Tool ${toolCall.toolName} completed successfully (${result.length} chars)`);
          } catch (error: any) {
            toolCall.error = error.message;
            toolCalls.push(toolCall);
            this.log(`Tool ${toolCall.toolName} failed: ${error.message}`);
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
      this.log(`Agent loop error: ${error.message}`);
      this.log(`Stack trace: ${error.stack}`);
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
  private async callLLM(
    messages: Array<{role: string, content: string}>,
    tools?: Array<{
      type: string;
      function: {
        name: string;
        description: string;
        parameters: {
          type: string;
          properties: Record<string, any>;
          required?: string[];
        };
      };
    }>
  ): Promise<string> {
    // Use the CLI to call the LLM
    this.log(`Calling LLM with ${messages.length} messages and ${tools?.length || 0} tools`);
    
    const response = await this.cli.callLLM(
      this.config.model.id,
      messages,
      {
        temperature: this.config.model.temperature,
        top_p: this.config.model.topP,
        max_tokens: this.config.model.maxOutputTokens
      },
      tools
    );
    
    this.log(`LLM response length: ${response.length} chars`);
    return response;
  }

  /**
   * Get the list of tools the agent can use.
   * These are sent to the LLM so it knows what tools are available.
   */
  private getAvailableTools(): Array<{
    type: string;
    function: {
      name: string;
      description: string;
      parameters: {
        type: string;
        properties: Record<string, any>;
        required?: string[];
      };
    };
  }> {
    return [
      {
        type: "function",
        function: {
          name: "i2vision_discover",
          description: "Run full VSLFC discovery on the project. Returns architecture patterns, flows, business rules, and components across all modules.",
          parameters: {
            type: "object",
            properties: {
              path: { 
                type: "string", 
                description: "Project root path to discover" 
              },
              intent: { 
                type: "string",
                enum: ["full_discovery", "quick_overview", "architecture_audit", "flow_mapping"],
                description: "Discovery intent"
              }
            },
            required: ["path"]
          }
        }
      },
      {
        type: "function",
        function: {
          name: "i2vision_get_context",
          description: "Get VSLFC architectural context for a specific file. Returns symbols, related files, business rules, flows, and complexity metrics.",
          parameters: {
            type: "object",
            properties: {
              file: { 
                type: "string", 
                description: "File path relative to workspace root" 
              },
              task: { 
                type: "string", 
                description: "Task type: debug, refactor, add_feature, fix_bug, optimize, discovery" 
              }
            },
            required: ["file"]
          }
        }
      },
      {
        type: "function",
        function: {
          name: "read_file",
          description: "Read the contents of a file",
          parameters: {
            type: "object",
            properties: {
              path: { 
                type: "string", 
                description: "File path relative to workspace root" 
              }
            },
            required: ["path"]
          }
        }
      },
      {
        type: "function",
        function: {
          name: "list_directory",
          description: "List contents of a directory",
          parameters: {
            type: "object",
            properties: {
              path: { 
                type: "string", 
                description: "Directory path relative to workspace root" 
              }
            },
            required: ["path"]
          }
        }
      },
      {
        type: "function",
        function: {
          name: "regex_search",
          description: "Search for a regex pattern across files",
          parameters: {
            type: "object",
            properties: {
              pattern: { 
                type: "string", 
                description: "Regex pattern to search for" 
              },
              path: { 
                type: "string", 
                description: "Directory or file to search in" 
              }
            },
            required: ["pattern", "path"]
          }
        }
      },
      {
        type: "function",
        function: {
          name: "write_file",
          description: "Write content to a file (creates or overwrites)",
          parameters: {
            type: "object",
            properties: {
              path: { 
                type: "string", 
                description: "File path relative to workspace root" 
              },
              content: { 
                type: "string", 
                description: "Content to write to the file" 
              }
            },
            required: ["path", "content"]
          }
        }
      },
      {
        type: "function",
        function: {
          name: "edit_file",
          description: "Edit a file by replacing exact string match",
          parameters: {
            type: "object",
            properties: {
              path: { 
                type: "string", 
                description: "File path relative to workspace root" 
              },
              old_string: { 
                type: "string", 
                description: "The exact text to find in the file" 
              },
              new_string: { 
                type: "string", 
                description: "The replacement text" 
              }
            },
            required: ["path", "old_string", "new_string"]
          }
        }
      }
    ];
  }

  /**
   * Parse LLM response for reasoning and tool calls
   * Updated to handle multiple formats and provide better error handling
   */
  private parseLLMResponse(response: string): { reasoning: string, toolCalls: ToolCall[] } {
    const toolCalls: ToolCall[] = [];
    let reasoning = response;
    
    this.log(`Parsing LLM response (${response.length} chars)`);
    this.log(`Response preview: ${response.substring(0, 300)}`);
    
    // Strategy 1: Try to extract tool calls using the configured pattern
    try {
      const toolCallPattern = new RegExp(this.config.parsing.toolCallPattern, 'g');
      let match;
      
      while ((match = toolCallPattern.exec(response)) !== null) {
        try {
          // Extract JSON from the match
          const jsonStart = response.indexOf('{', match.index);
          const jsonEnd = response.lastIndexOf('}', match.index + match[0].length);
          
          if (jsonStart !== -1 && jsonEnd !== -1) {
            const jsonStr = response.substring(jsonStart, jsonEnd + 1);
            this.log(`Found tool call JSON: ${jsonStr.substring(0, 100)}`);
            const parsed = JSON.parse(jsonStr);
            
            toolCalls.push({
              toolName: parsed.tool,
              args: parsed.args || {}
            });
          }
        } catch (error: any) {
          this.log(`Failed to parse tool call with config pattern: ${error.message}`);
        }
      }
    } catch (error: any) {
      this.log(`Error using config pattern: ${error.message}`);
    }
    
    // Strategy 2: If no tool calls found, try direct "tool_call:" pattern from system prompt
    if (toolCalls.length === 0) {
      this.log('No tool calls found with config pattern, trying direct pattern...');
      const directPattern = /tool_call:\s*(\{[^}]+\})/g;
      let match;
      
      while ((match = directPattern.exec(response)) !== null) {
        try {
          const jsonStr = match[1];
          this.log(`Found tool call with direct pattern: ${jsonStr}`);
          const parsed = JSON.parse(jsonStr);
          
          toolCalls.push({
            toolName: parsed.tool,
            args: parsed.args || {}
          });
        } catch (error: any) {
          this.log(`Failed to parse tool call with direct pattern: ${error.message}`);
        }
      }
    }
    
    // Strategy 3: Try to find any JSON with "tool" field
    if (toolCalls.length === 0) {
      this.log('No tool calls found with direct pattern, trying generic JSON search...');
      const jsonPattern = /\{[^{}]*"tool"[^{}]*\}/g;
      let match;
      
      while ((match = jsonPattern.exec(response)) !== null) {
        try {
          const jsonStr = match[0];
          this.log(`Found potential tool JSON: ${jsonStr}`);
          const parsed = JSON.parse(jsonStr);
          
          if (parsed.tool && parsed.args) {
            toolCalls.push({
              toolName: parsed.tool,
              args: parsed.args
            });
          }
        } catch (error: any) {
          this.log(`Failed to parse generic JSON: ${error.message}`);
        }
      }
    }
    
    // Extract reasoning (everything before first tool_call or after last tool_call)
    const toolCallHeader = this.config.formattingRules.toolCallHeader || 'tool_call:';
    const toolCallIndex = response.indexOf(toolCallHeader);
    if (toolCallIndex !== -1) {
      reasoning = response.substring(0, toolCallIndex).trim();
      // Remove "reasoning:" prefix if present
      const reasoningPrefix = this.config.formattingRules.reasoningHeader || 'reasoning:';
      if (reasoning.startsWith(reasoningPrefix)) {
        reasoning = reasoning.substring(reasoningPrefix.length).trim();
      }
    }
    
    // Also try to extract reasoning after tool calls (for multi-turn responses)
    const eosMarker = this.config.formattingRules.eosMarker || 'EOS';
    const eosIndex = response.indexOf(eosMarker);
    if (eosIndex !== -1 && toolCallIndex !== -1 && eosIndex > toolCallIndex) {
      // There might be more content between tool_call and EOS
      const betweenContent = response.substring(toolCallIndex + toolCallHeader.length, eosIndex).trim();
      if (betweenContent && !toolCalls.some(tc => betweenContent.includes(JSON.stringify(tc.args)))) {
        reasoning += '\n' + betweenContent;
      }
    }
    
    this.log(`Parsed ${toolCalls.length} tool calls, reasoning: ${reasoning.substring(0, 100)}...`);
    
    return { reasoning, toolCalls };
  }

  /**
   * Execute a tool call
   */
  private async executeTool(toolCall: ToolCall): Promise<string> {
    const { toolName, args } = toolCall;
    
    this.log(`Executing tool: ${toolName} with args: ${JSON.stringify(args)}`);
    
    // Map tool names to CLI methods
    switch (toolName) {
      case 'read_file':
        this.log(`Reading file: ${args.path}`);
        return await this.cli.readFile(args.path);
      
      case 'write_file':
        this.log(`Writing file: ${args.path}`);
        return await this.cli.writeFile(args.path, args.content);
      
      case 'edit_file':
        this.log(`Editing file: ${args.path}`);
        return await this.cli.editFile(args.path, args.old_string, args.new_string);
      
      case 'list_directory':
        this.log(`Listing directory: ${args.path}`);
        return await this.cli.listDirectory(args.path);
      
      case 'regex_search':
        this.log(`Regex search: ${args.pattern} in ${args.path}`);
        return await this.cli.regexSearch(args.pattern, args.path);
      
      case 'i2vision_get_context':
        this.log(`Getting context for file: ${args.file}`);
        return JSON.stringify(await this.cli.runDiscovery());
      
      case 'i2vision_discover':
        this.log(`Running discovery on: ${args.path || 'project root'}`);
        return JSON.stringify(await this.cli.runDiscovery());
      
      default:
        this.log(`Unknown tool: ${toolName}`);
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
    console.log(`[Agent:${this.config.key}] ${message}`);
  }

  /**
   * Get agent configuration
   */
  getConfig(): AgentConfig {
    return this.config;
  }
}
