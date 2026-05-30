# I2Vision Agent Configuration

This package provides YAML configuration loading and adaptation for i2-Vision agents.

## Package Structure

```
com.i2vision.agent.config/
├── AgentPromptConfiguration.kt    # 15-section YAML data class
├── YamlConfigLoader.kt            # YAML file loading
├── ConfigurationAdapter.kt        # Bridge to new interfaces
├── KoogConfigFactory.kt          # Koog-specific config creation
├── ConfigValidation.kt           # Configuration validation
├── DefaultConfigs.kt             # Built-in defaults per layer
└── README.md                     # This file
```

## Overview

The configuration system bridges the legacy 15-section YAML format to the new `I2VisionAgent` interfaces:

```
YAML File (.vision-ai/coding-agent.yaml)
    ↓
YamlConfigLoader (kotlinx.serialization.yaml)
    ↓
AgentPromptConfiguration (15-section data class)
    ↓
ConfigurationAdapter
    ↓
├── AgentConfig (runtime settings)
├── AgentCapabilities (capability declaration)
├── KoogPromptConfig (for task-15)
├── KoogStrategyConfig (for task-14)
└── KoogToolRegistryConfig (for task-16)
```

## YAML Configuration Format

The 15-section YAML format:

```yaml
key: "coding-agent"
agentType: "CODE"
version: "1.0.0"
isActive: true

systemPromptTemplate: |
  You are an expert software engineer...
  Context: {{$workspaceRoot}}

templateVariables:
  workspaceRoot: "/path/to/project"

ruleSetKeys:
  - "code-style"
  - "error-handling"

model:
  provider: "Ollama"
  id: "llama3.2:3b"
  contextLength: 8192
  temperature: 0.3
  topP: 0.9
  topK: 40
  maxTokens: 2048

llm:
  retries: 3
  timeoutSeconds: 60
  streaming: true
  baseUrl: "http://localhost:11434"

formattingRules:
  indentSize: 4
  useTabs: false
  maxLineLength: 120
  trimTrailingWhitespace: true
  insertFinalNewline: true

iterationSettings:
  maxIterations: 10
  maxConsecutiveToolCalls: 12
  enableKickstart: true
  kickstartMinInvalidOutputs: 2
  reflectionEnabled: true
  selfCorrectionEnabled: true

toolSelection:
  enabledTools:
    - "read_file"
    - "write_file"
    - "list_directory"
    - "search_files"
    - "run_command"
  disabledTools: []
  toolTimeoutSeconds: 30
  requireConfirmationFor:
    - "write_file"
    - "run_command"
  readOnlyMode: false

safety:
  allowFileWrites: true
  allowedDirectories: []
  forbiddenDirectories:
    - ".git"
    - "node_modules"
    - "build"
  enableBuildVerification: true
  maxFileSize: 1048576
  requireBackupBeforeWrite: true

parsing:
  strictJsonParsing: true
  allowMarkdownCodeBlocks: true
  fallbackToPlainText: true
  maxParseAttempts: 3

discovery:
  enableClusterContext: true
  cachePath: null
  autoRefresh: false
  refreshIntervalMinutes: 60
  maxCacheAgeHours: 24

execution:
  buildCommand: "./gradlew compileKotlin"
  fileOperationMode: "DIRECT"
  workingDirectory: null
  environmentVariables: {}
  shellPath: null

formatting:
  includeReasoningTrace: true
  includeToolCallDetails: true
  compactMode: false
  syntaxHighlighting: true

streaming:
  enabled: true
  emitReasoning: true
  emitToolCalls: true
  emitProgress: true
  chunkSize: 50

mcp:
  enabled: false
  servers: []
  injectClusterContext: true
  timeoutSeconds: 30
```

## Usage

### Load Configuration from File

```kotlin
// Load from file system
val config = YamlConfigLoader.load("/path/to/coding-agent.yaml")

// Load from classpath resources
val config = YamlConfigLoader.loadFromResources("configs/code-agent.yaml")

// Load all from directory
val configs = YamlConfigLoader.loadAllFromDirectory("/path/to/configs", recursive = true)
```

### Use Default Configurations

```kotlin
// Get default for a specific layer
val codeConfig = DefaultConfigs.forLayer(VslfcLayer.CODE)
val flowConfig = DefaultConfigs.forLayer(VslfcLayer.FLOW)

// Or use predefined constants
val codeConfig = DefaultConfigs.CODE
```

### Convert to AgentConfig

```kotlin
val adapter = ConfigurationAdapter()
val agentConfig = adapter.toAgentConfig(yamlConfig)

// Use with agent
val agent = provider.createAgent(VslfcLayer.CODE, agentConfig)
```

### Convert to Capabilities

```kotlin
val adapter = ConfigurationAdapter()
val capabilities = adapter.toCapabilities(yamlConfig)

println("Model: ${capabilities.modelProvider} ${capabilities.modelId}")
println("Tools: ${capabilities.availableTools.size}")
println("Streaming: ${capabilities.supportsStreaming}")
```

### Create Koog Configurations

```kotlin
val factory = KoogConfigFactory()

// Create all at once
val koogConfigs = factory.createAll(yamlConfig)

// Or individually
val promptConfig = factory.createPromptConfig(yamlConfig)
val strategyConfig = factory.createStrategyConfig(yamlConfig)
val toolRegistryConfig = factory.createToolRegistryConfig(yamlConfig)
val llmConfig = factory.createLlmConfig(yamlConfig)
```

### Validate Configuration

```kotlin
val errors = ConfigValidation.validate(config)
if (errors.isNotEmpty()) {
    throw ConfigValidationException("Invalid config: ${errors.joinToString(", ")}")
}

// Or use validateOrThrow
ConfigValidation.validateOrThrow(config)
```

### Extension Functions

```kotlin
// Load and convert to Koog configs in one step
val koogConfigs = "path/to/config.yaml".toKoogConfigs()

// Create Koog configs from default layer config
val koogConfigs = VslfcLayer.CODE.toKoogConfigs()
```

## Configuration Sections

### 1. Metadata
- `key` - Unique identifier
- `agentType` - VSLFC layer (CODE, FLOW, LOGIC, STRUCTURE, VISION)
- `version` - Configuration version
- `isActive` - Whether this config is active

### 2. System Prompt
- `systemPromptTemplate` - Prompt with `{{$variable}}` placeholders
- `templateVariables` - Variable substitutions

### 3. Rules
- `ruleSetKeys` - References to rule sets

### 4. Model
- `provider` - LLM provider (Ollama, OpenAI, etc.)
- `id` - Model identifier
- `contextLength` - Context window size
- `temperature`, `topP`, `topK` - Sampling parameters
- `maxTokens` - Maximum tokens to generate

### 5. LLM
- `retries` - Number of retries on failure
- `timeoutSeconds` - Request timeout
- `streaming` - Whether streaming is enabled
- `baseUrl` - API base URL
- `apiKey` - API key (if required)

### 6. Formatting Rules
- `indentSize`, `useTabs` - Code formatting
- `maxLineLength` - Maximum line length
- `trimTrailingWhitespace`, `insertFinalNewline` - Cleanup

### 7. Iteration Settings
- `maxIterations` - Maximum think→act cycles
- `maxConsecutiveToolCalls` - Maximum tool calls before pausing
- `enableKickstart` - Kickstart for malformed output
- `kickstartMinInvalidOutputs` - Invalid outputs before kickstart
- `reflectionEnabled`, `selfCorrectionEnabled` - Self-improvement

### 8. Tool Selection
- `enabledTools`, `disabledTools` - Tool availability
- `toolTimeoutSeconds` - Tool execution timeout
- `requireConfirmationFor` - Tools requiring user confirmation
- `readOnlyMode` - Read-only tool execution

### 9. Safety
- `allowFileWrites` - Whether file writes are allowed
- `allowedDirectories`, `forbiddenDirectories` - Directory restrictions
- `enableBuildVerification` - Build after file modifications
- `maxFileSize` - Maximum file size for operations
- `requireBackupBeforeWrite` - Backup before writing

### 10. Parsing
- `strictJsonParsing` - Require valid JSON
- `allowMarkdownCodeBlocks` - Parse markdown code blocks
- `fallbackToPlainText` - Fallback if parsing fails
- `maxParseAttempts` - Maximum parse attempts

### 11. Discovery
- `enableClusterContext` - Inject cluster context
- `cachePath` - Discovery cache path
- `autoRefresh` - Automatic cache refresh
- `refreshIntervalMinutes` - Refresh interval
- `maxCacheAgeHours` - Maximum cache age

### 12. Execution
- `buildCommand` - Build command for verification
- `fileOperationMode` - DIRECT or SHELL
- `workingDirectory` - Working directory for commands
- `environmentVariables` - Environment variables
- `shellPath` - Shell path for SHELL mode

### 13. Formatting
- `includeReasoningTrace` - Include reasoning in output
- `includeToolCallDetails` - Include tool call details
- `compactMode` - Compact output format
- `syntaxHighlighting` - Syntax highlighting

### 14. Streaming
- `enabled` - Whether streaming is enabled
- `emitReasoning`, `emitToolCalls`, `emitProgress` - What to emit
- `chunkSize` - Text chunk size for streaming

### 15. MCP
- `enabled` - Whether MCP is enabled
- `servers` - MCP server configurations
- `injectClusterContext` - Inject cluster context via MCP
- `timeoutSeconds` - MCP timeout

## Default Configurations

Each VSLFC layer has a tailored default configuration:

| Layer | Focus | Iterations | Read-Only | Build Verification |
|-------|-------|------------|-----------|-------------------|
| **CODE** | Implementation | 10 | No | Yes |
| **FLOW** | API/Sequences | 8 | No | No |
| **LOGIC** | Business Rules | 10 | No | Yes |
| **STRUCTURE** | Architecture | 8 | Yes | No |
| **VISION** | Requirements | 6 | Yes | No |

## Validation Rules

The `ConfigValidation` object enforces:

### Required Fields
- `key`, `agentType`, `version`, `systemPromptTemplate`
- `model.provider`, `model.id`

### Value Ranges
- `temperature`: 0.0 to 2.0
- `topP`: 0.0 to 1.0
- `topK`: ≥ 1
- `contextLength`, `maxTokens`: positive
- `maxIterations`: 1 to 100
- `toolTimeoutSeconds`: positive

### Consistency Checks
- Tools cannot be both enabled and disabled
- Directories cannot be both allowed and forbidden
- `streaming.enabled` requires `llm.streaming`
- `enableBuildVerification` requires `buildCommand`
- `readOnlyMode` conflicts with `allowFileWrites`

## Error Handling

### ConfigLoadException
Thrown when loading fails:

```kotlin
try {
    val config = YamlConfigLoader.load("invalid.yaml")
} catch (e: ConfigLoadException) {
    println("Failed to load: ${e.message}")
}
```

### ConfigValidationException
Thrown when validation fails:

```kotlin
try {
    ConfigValidation.validateOrThrow(config)
} catch (e: ConfigValidationException) {
    e.errors.forEach { println("Error: $it") }
}
```

## Integration Points

| Task | Integration |
|------|-------------|
| **task-14** (Koog GraphStrategy) | Uses `KoogStrategyConfig` for iteration limits |
| **task-15** (Koog PromptTemplate) | Uses `KoogPromptConfig` for system prompt |
| **task-16** (ToolRegistry) | Uses `KoogToolRegistryConfig` for tool settings |
| **task-18** (Local TypeScript Agent) | Loads same YAML configs |
| **task-22** (JSON-RPC Server) | Server loads configs to initialize agents |

## Next Steps

1. **task-14**: Use `KoogStrategyConfig` to configure GraphStrategy
2. **task-15**: Use `KoogPromptConfig` to configure PromptTemplate
3. **task-16**: Use `KoogToolRegistryConfig` to configure ToolRegistry
4. **task-18**: Implement TypeScript YAML loader (same format)
5. **task-22**: Load configs in JSON-RPC server initialization

## References

- [I2VisionAgent Interfaces](../README.md)
- [Koog Framework](https://github.com/JetBrains/koog)
- [kotlinx.serialization YAML](https://github.com/Kotlin/kotlinx.serialization/tree/master/formats/yaml)
- [Agent Architecture Roadmap](../../../../../backlog/docs/DOC-4.md)
