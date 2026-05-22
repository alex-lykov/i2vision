# Koog PromptTemplate Integration

This package bridges the i2vision YAML prompt configuration to Koog's native `PromptTemplate` API, enabling dynamic prompt construction with variable substitution, rule sets, and formatting rules.

## Package Structure

```
com.i2vision.agent.koog.prompt/
├── KoogPromptTemplateBuilder.kt   # YAML → Koog PromptTemplate
├── PromptVariableResolver.kt      # Variable substitution
├── RuleSetLoader.kt               # Load rule sets from config
├── FormattingRuleAdapter.kt       # i2vision formatting → Koog format
├── LayerPromptTemplates.kt        # Layer-specific prompt templates
└── README.md                      # This file
```

## Overview

The prompt template system connects YAML configuration to Koog's prompt system:

```
YAML Config (systemPromptTemplate, templateVariables, ruleSetKeys, formattingRules)
    ↓
KoogPromptTemplateBuilder
    ↓
Koog PromptTemplate (with variables, rules, formatters)
    ↓
PromptBuilder (from task-14) uses this to build iteration prompts
```

## Components

### KoogPromptTemplateBuilder

Main builder that creates Koog `PromptTemplate` from YAML configuration:

```kotlin
val builder = KoogPromptTemplateBuilder()
val template = builder.build(
    yamlConfig = yamlConfig,
    layer = VslfcLayer.CODE,
    runtimeVariables = mapOf("currentFile" to "src/main.kt")
)

val prompt = template.render()
```

**Features:**
- Variable substitution (`${variableName}`)
- Rule set application
- Formatting rules adaptation
- Layer-specific customization
- Auto-variables (layer, model info, etc.)

### PromptVariableResolver

Resolves `${variableName}` placeholders with support for:

- **Simple substitution**: `${workspaceRoot}` → `/path/to/project`
- **Default values**: `${currentFile:unknown}` → `src/main.kt` or `unknown`
- **Escaped literals**: `$${literal}` → `${literal}`

```kotlin
val resolver = PromptVariableResolver()

val template = "Hello, ${userName:Guest}! You are in ${workspaceRoot}."
val variables = mapOf("userName" to "Alice", "workspaceRoot" to "/project")

val result = resolver.resolve(template, variables)
// Result: "Hello, Alice! You are in /project."

val unresolved = resolver.findUnresolved(template, emptyMap())
// Result: ["userName", "workspaceRoot"]
```

### RuleSetLoader

Loads named rule sets that constrain agent behavior:

**Built-in Rule Sets:**
- `code-style-kotlin` - Kotlin coding conventions
- `code-style-typescript` - TypeScript coding conventions
- `minimal-changes` - Make smallest possible changes
- `security-critical` - Security best practices
- `test-aware` - Consider test implications
- `performance-conscious` - Performance considerations
- `documentation-required` - Documentation standards
- `error-handling` - Error handling patterns

**Layer-Specific Rules:**
- `CODE` - Read before editing, verify compilation
- `FLOW` - Trace complete call chains
- `LOGIC` - Identify invariants and edge cases
- `STRUCTURE` - Read-only analysis, reference specifics
- `VISION` - Map to requirements, identify gaps

```kotlin
val loader = RuleSetLoader()

// Load by name
val codeStyle = loader.load("code-style-kotlin")

// Load for layer
val layerRules = loader.loadForLayer(VslfcLayer.CODE)

// Load common rules
val common = loader.loadCommon()
```

### FormattingRuleAdapter

Adapts i2vision formatting rules to Koog's output format:

```kotlin
val adapter = FormattingRuleAdapter()
val formatting = adapter.adapt(yamlConfig.formattingRules)

println(formatting.reasoningHeader)  // "reasoning"
println(formatting.toolCallPrefix)   // "tool_call"
println(formatting.allowJson)        // true
println(formatting.maxResponseSize)  // 200000
```

**Output Format Features:**
- Reasoning header format
- Tool call prefix format
- End-of-stream marker
- JSON/XML tool call support
- Response size limits

### LayerPromptTemplates

Default prompt templates for each VSLFC layer:

```kotlin
// Get template for layer
val template = LayerPromptTemplates.forLayer(VslfcLayer.CODE)

// Resolve variables
val resolver = PromptVariableResolver()
val resolved = resolver.resolve(template, mapOf(
    "currentFile" to "src/main.kt",
    "currentTask" to "Refactor this function"
))
```

**Templates:**
- **CODE** - Implementation focus with file operations
- **FLOW** - API and data flow analysis
- **LOGIC** - Business rules and invariants
- **STRUCTURE** - Architecture and dependencies
- **VISION** - Requirements and goals

## Usage

### Build PromptTemplate from YAML

```kotlin
// Load YAML configuration
val yamlConfig = YamlConfigLoader.load("coding-agent.yaml")

// Create builder
val builder = KoogPromptTemplateBuilder()

// Build template
val template = builder.build(
    yamlConfig = yamlConfig,
    layer = VslfcLayer.CODE,
    runtimeVariables = mapOf(
        "currentFile" to "src/main.kt",
        "currentTask" to "Refactor this function",
        "workspaceRoot" to "/path/to/project"
    )
)

// Render complete prompt
val prompt = template.getCompletePrompt()
println(prompt)
```

### Use Default Layer Template

```kotlin
// Get default template
val template = LayerPromptTemplates.forLayer(VslfcLayer.CODE)

// Resolve variables
val resolver = PromptVariableResolver()
val resolved = resolver.resolve(template, mapOf(
    "agentRole" to "Code Implementation Specialist",
    "projectName" to "My Project",
    "currentFile" to "src/main.kt"
))
```

### Apply Rule Sets

```kotlin
val loader = RuleSetLoader()

// Load specific rule sets
val codeStyle = loader.load("code-style-kotlin")
val minimalChanges = loader.load("minimal-changes")

// Load layer rules
val layerRules = loader.loadForLayer(VslfcLayer.CODE)

// Load common rules
val common = loader.loadCommon()

// Create custom rule set
val custom = loader.createCustom(
    "My Custom Rules",
    "Always write tests first",
    "Document public APIs",
    "Use meaningful names"
)
```

### Variable Resolution

```kotlin
val resolver = PromptVariableResolver()

// Simple substitution
val template = "File: ${currentFile}"
val resolved = resolver.resolve(template, mapOf("currentFile" to "src/main.kt"))
// Result: "File: src/main.kt"

// With default
val withDefault = "File: ${currentFile:unknown}"
val resolved1 = resolver.resolve(withDefault, mapOf())
// Result: "File: unknown"
val resolved2 = resolver.resolve(withDefault, mapOf("currentFile" to "test.kt"))
// Result: "File: test.kt"

// Escaped literal
val escaped = "Use $${variable} for substitution"
val resolved = resolver.resolve(escaped, mapOf())
// Result: "Use ${variable} for substitution"

// Find unresolved
val unresolved = resolver.findUnresolved(template, emptyMap())
// Result: ["currentFile"]

// Extract all variable names
val variables = resolver.extractVariableNames(template)
// Result: ["currentFile"]
```

### Formatting Rules

```kotlin
val adapter = FormattingRuleAdapter()

// Adapt from YAML config
val formatting = adapter.adapt(yamlConfig.formattingRules)

// Get defaults for layer
val defaults = adapter.defaultsForLayer(VslfcLayer.CODE)

// Get example
println(formatting.getExample())
```

## Template Variables

### Auto-Variables (Always Available)

| Variable | Description | Example |
|----------|-------------|---------|
| `layer` | VSLFC layer name (lowercase) | `code` |
| `layerDisplayName` | Human-readable layer name | `Code` |
| `layerDescription` | Layer description | `Implementation details...` |
| `layerScope` | Layer's area of focus | `implementation details...` |
| `agentRole` | Agent's role description | `Code Implementation Specialist` |
| `agentType` | Agent type from YAML | `CODE` |
| `version` | Configuration version | `1.0.0` |
| `modelId` | Model identifier | `llama3.2:3b` |
| `modelProvider` | Model provider name | `Ollama` |
| `contextLength` | Model context length | `8192` |
| `maxTokens` | Maximum tokens | `2048` |
| `temperature` | Model temperature | `0.3` |

### Runtime Variables (Provided at Execution)

| Variable | Description | Example |
|----------|-------------|---------|
| `currentFile` | Currently active file | `src/main.kt` |
| `currentTask` | Current task description | `Refactor this function` |
| `workspaceRoot` | Workspace root path | `/path/to/project` |
| `sessionId` | Session identifier | `session-123` |
| `projectName` | Project name (from YAML or default) | `My Project` |

### Custom Variables (From YAML)

Defined in `templateVariables` section of YAML:

```yaml
templateVariables:
  projectName: "My Project"
  teamName: "Platform Team"
  codingStandards: "Kotlin Official Style"
```

## Integration with Task-14

The `PromptBuilder` from task-14 uses the Koog `PromptTemplate`:

```kotlin
class PromptBuilder(
    private val promptTemplate: PromptTemplate,  // From task-15
    private val config: AgentPromptConfiguration
) {
    fun buildIterationPrompt(
        iteration: Int,
        maxIterations: Int,
        history: List<IterationStep>,
        enrichedContext: EnrichedContext,
        runtimeVars: Map<String, String>
    ): String {
        // Build base prompt from Koog template
        val basePrompt = promptTemplate.render(
            variables = runtimeVars + mapOf(
                "enrichedContext" to enrichedContext.toSummary(),
                "historySummary" to history.takeLast(3).toSummary()
            )
        )
        
        return buildString {
            appendLine(basePrompt)
            
            if (history.isNotEmpty()) {
                appendLine("\n## Previous Steps")
                history.takeLast(5).forEach { step ->
                    appendLine(step.toPromptSection())
                }
            }
            
            appendLine("\n## Iteration $iteration of $maxIterations")
        }
    }
}
```

## Output Format

The formatting system defines how agents should structure their output:

### Example 1: Reasoning Only
```
reasoning: I need to analyze the code structure first...
The main entry point is in Main.kt...
[EOS]
```

### Example 2: Tool Call
```
reasoning: I should read the file to understand the current implementation.
tool_call: {"tool": "read_file", "args": {"path": "src/main.kt"}}
```

### Example 3: JSON Tool Call
```json
{"tool": "write_file", "args": {"path": "src/test.kt", "content": "..."}}
```

### Example 4: XML Tool Call
```xml
<invoke name="read_file">
  <arg name="path">src/main.kt</arg>
</invoke>
```

## Layer-Specific Defaults

Each VSLFC layer has tailored formatting defaults:

| Layer | Reasoning Header | Tool Call Header | EOS Marker |
|-------|-----------------|------------------|------------|
| **CODE** | `reasoning:` | `tool_call:` | `[EOS]` |
| **FLOW** | `analysis:` | `invoke:` | `[COMPLETE]` |
| **LOGIC** | `reasoning:` | `tool_call:` | `[EOS]` |
| **STRUCTURE** | `architecture:` | `discover:` | `[ANALYSIS_COMPLETE]` |
| **VISION** | `vision:` | `query:` | `[VISION_COMPLETE]` |

## Error Handling

### Unresolved Variables

```kotlin
val resolver = PromptVariableResolver()
val template = "Hello, ${userName}!"

// Check if fully resolved
if (!resolver.isFullyResolved(template, emptyMap())) {
    val missing = resolver.findUnresolved(template, emptyMap())
    println("Missing variables: $missing")
}
```

### Missing Rule Sets

```kotlin
val loader = RuleSetLoader()
val ruleSet = loader.load("nonexistent")

if (ruleSet == null) {
    println("Rule set not found")
}
```

## Testing

### Test Variable Resolution

```kotlin
@Test
fun testVariableResolution() {
    val resolver = PromptVariableResolver()
    val template = "Hello, ${'$'}{userName:Guest}!"
    
    val result = resolver.resolve(template, mapOf("userName" to "Alice"))
    assertEquals("Hello, Alice!", result)
}
```

### Test Rule Set Loading

```kotlin
@Test
fun testRuleSetLoading() {
    val loader = RuleSetLoader()
    
    val codeStyle = loader.load("code-style-kotlin")
    assertNotNull(codeStyle)
    assertTrue(codeStyle!!.rules.isNotEmpty())
}
```

### Test Layer Templates

```kotlin
@Test
fun testLayerTemplates() {
    val template = LayerPromptTemplates.forLayer(VslfcLayer.CODE)
    
    assertTrue(template.contains("${'$'}{agentRole}"))
    assertTrue(template.contains("${'$'}{layerScope}"))
    
    val unresolved = LayerPromptTemplates.findUnresolved(template)
    assertTrue(unresolved.isNotEmpty()) // Expected - needs resolution
}
```

## Integration Points

| Component | Used By | From Task |
|-----------|---------|-----------|
| **PromptTemplate** | PromptBuilder | task-14 |
| **KoogPromptTemplateBuilder** | I2VisionKoogAgent | task-14 |
| **RuleSetLoader** | KoogPromptTemplateBuilder | task-15 |
| **FormattingRuleAdapter** | KoogPromptTemplateBuilder | task-15 |
| **LayerPromptTemplates** | Default configs | task-15 |

## Next Steps

1. **task-16**: Build ToolRegistry (uses prompt format for tool calls)
2. **task-18**: Implement LocalTypeScriptAgent (uses same prompt system)
3. **task-22**: Implement JSON-RPC Server (serves prompts to clients)

## References

- [Koog Framework Documentation](https://github.com/JetBrains/koog)
- [I2Vision Agent Interfaces](../../README.md)
- [YAML Configuration](../../config/README.md)
- [Koog GraphStrategy](../README.md)
